package com.adsh.app.core.ptc

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext

/**
 * PTC 引擎：把一段程序喂给 QuickJS，程序里的 `await tools.x()` 通过 [run] 的 invoke 回调
 * **同步**交给宿主进程执行。这个类**只负责跑**，工具执行与轨迹记录都在宿主（[PtcToolRunner]）。
 *
 * ## 为什么搬进独立进程（第 183 轮，照 dsh 的隔离模型）
 *
 * dsh 的 `dsh-ptc-runtime-node` 对每次调用 **spawn 一个新 Node 进程**（`isolation = "process"`，
 * executionInstructions 原文：Each call runs in a fresh Node process），宿主在
 * `setTimeout(spec.timeoutMs)` 上守着：到点 `controller.abort("execution deadline reached")`
 * 然后 `handle.terminate()` **杀掉那个子进程**。于是程序里的同步死循环
 * （`for (let i = 0; i < 1e11; i++)`）最多烧死子进程 —— 父进程、整轮对话、界面全都活着。
 *
 * ADSH 以前是同进程 QuickJS，这条退路不存在：QuickJS 一旦进了同步死循环就再也不把执行权还给
 * 宿主，泵循环里的超时判据（以及用户按停止的探针）**都没有机会执行**，整轮无限期挂住 ——
 * 第 183 轮真机实测：harness 的 CPU 预算探针把一轮挂了 13 分钟，一直挂到进程被系统清掉。
 * 现在照 dsh 的模型把程序搬进 `:ptc` 进程（见 [PtcWorkerService]），到点由宿主杀进程
 * （见 [PtcProcess]），并且**每次调用一个新进程**（与 dsh 的 fresh process 一致）。
 *
 * ## 为什么是这个泵模型（M3 真机探针实测）
 * 该 QuickJS 包装库**没有 executePendingJob**：单次 evaluate 内产生的 promise 永远不会 settle。
 * 但探针证明 **microtask 会在下一次 evaluate 调用时被排空**（`Promise.resolve().then(...)` 在第二次
 * evaluate 时才生效）。因此采用「泵 + 同步宿主函数」模型：
 *   1) 把程序包进 async IIFE，结果写进 `globalThis.__adsh_state`；
 *   2) 反复 evaluate 一个空表达式来泵动 microtask 队列，直到 settled 或超时；
 *   3) 宿主函数 `__adsh_post__` **只把调用帧写出去就返回**（非阻塞）；回执到达后由泵循环喂给
 *      `__adsh_settleAll__` 兑现 —— 于是同一程序里的多个 `tools.x(...)` 各自异步执行，
 *      谁与谁重叠由宿主侧的 [com.adsh.app.core.tools.ToolConcurrency] 决定（第 186 轮；
 *      dsh 的绑定就是这个形状：子进程 postMessage 之后继续跑）。
 *
 * ## 超时（与 dsh 同一条口径）
 * `timeoutMs` 是**墙钟**预算（dsh 的 wallTimer 同样从执行开始计时，等工具的时间也算在内），默认
 * [DEFAULT_TIMEOUT_MS]。泵循环到点会走「优雅收尾」这条路（返回 failureKind = timeout）；如果程序
 * 根本不让出执行权，宿主那份 deadline 会直接杀掉这个进程（见 [PtcProcess]）。
 *
 * ## 异常契约（dsh-tools 的 binding errorClass + worker.cjs 的 namespaces）
 *  - **已声明**的工具失败 → 抛 `ToolCallError`：`e.name === "ToolCallError"`、
 *    `e.toolName` 是被调工具名、`e.message` 是宿主侧原文（不带 `Error: ` 前缀）；
 *  - **未声明**的名字 → `tools.<name>` 就是 undefined，调用得到原生
 *    `TypeError: tools.<name> is not a function`，整个程序失败。
 *    dsh 的 namespaces 是一个只定义了已声明名字的空原型对象，不做兜底翻译 —— 保持一致。
 */
class QuickJsRuntime {

    fun run(
        program: String,
        toolNames: List<String>,
        /**
         * 发起一次 `tools.x()`：**只把调用帧写出去就返回**（不阻塞 JS 线程）。
         * dsh 的绑定就是这个形状 —— 子进程 postMessage 之后继续跑，宿主那边各自异步执行。
         */
        postCall: (id: Int, name: String, argsJson: String) -> Unit,
        /** 取走**已经到达**的回执（id → wire），非阻塞；没有就返回空表 */
        takeReplies: () -> List<Pair<Int, String>>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxLogLines: Int = MAX_LOG_LINES,
        /**
         * 每打印一行就回调一次 —— dsh 的 log 帧（process.js 的 `LogBuffer` → `{type:"log", text}`）。
         * 宿主据此**边跑边收**：到点被杀时，已经打印的内容仍然在宿主手里（第 185 轮，
         * 之前日志只随 done 一帧回来，程序一被杀就全丢）。
         */
        onLog: ((String) -> Unit)? = null,
        cancel: (() -> Boolean)? = null,
    ): CodeRunResult {
        val started = System.currentTimeMillis()
        val logs = ArrayList<String>(16)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val engine = QuickJSContext.create()
        // **这个数必须小于执行线程的真实栈**（第 120 轮真机实测：一个 f(n)=1+f(n-1) 递归 5000 层的
        // 探针把 App 整个打崩了）。QuickJS 的溢出判据是 `sp < stack_top - stack_size` —— 报的是它
        // **自己记账**的用量；账比 pthread 栈还大时它永远不会先开口，递归一路吃穿栈：
        // SIGSEGV（crash 日志 Cause: "stack pointer is not in a rw map"）。取它自己的默认值 256 KB，
        // 执行线程（PtcWorkerService 的引擎线程）显式给 2MB 栈，见那里的注释。
        engine.setMaxStackSize(256 * 1024)
        engine.setMemoryLimit(64 * 1024 * 1024)
        engine.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) = addLog(logs, info, maxLogLines, onLog)
            override fun info(info: String?) = addLog(logs, info, maxLogLines, onLog)
            override fun warn(info: String?) = addLog(logs, info, maxLogLines, onLog)
            override fun error(info: String?) = addLog(logs, info, maxLogLines, onLog)
        })

        try {
            val global = engine.getGlobalObject()

            // tools.x() 的**发起**（非阻塞）：把 id / 名字 / 参数原文交给宿主，立刻返回。
            // 回执不走这条调用栈 —— 泵循环把 takeReplies() 收到的信封喂给 __adsh_settleAll__。
            global.setProperty("__adsh_post__", JSCallFunction { args ->
                val id = (args.getOrNull(0) as? Number)?.toInt()
                    ?: return@JSCallFunction null
                val name = args.getOrNull(1) as? String ?: return@JSCallFunction null
                val rawArgs = args.getOrNull(2) as? String ?: "{}"
                calls.incrementAndGet()
                postCall(id, name, rawArgs)
                null
            })

            // SDK 门面：把宿主回调包装成 tools.<name>(args)。
            // 先注入**声明过的名字**：只有它们会成为 tools 的属性（未声明的名字是原生 TypeError）
            val namesJson = toolNames.joinToString(separator = ",", prefix = "[", postfix = "]") {
                "\"" + jsStringEscape(it) + "\""
            }
            engine.evaluate("globalThis.__adsh_names = " + namesJson, "names.js")
            engine.evaluate(PREAMBLE, "sdk.js")

            val wrapper = "(async () => {\n" +
                "  try {\n" +
                "    globalThis.__adsh_state.value = await (async () => {\n" +
                program +
                "\n    })();\n" +
                "  } catch (e) {\n" +
                // 分开存：message 是给人看的，toolName 用来还原「哪个工具失败了」
                "    globalThis.__adsh_state.error = (e && e.message) || String(e);\n" +
                "    globalThis.__adsh_state.errorTool = (e && e.toolName) || null;\n" +
                "    globalThis.__adsh_state.errorStack = (e && e.stack) || null;\n" +
                "  } finally {\n" +
                "    globalThis.__adsh_state.settled = true;\n" +
                "  }\n" +
                "})();"
            engine.evaluate(wrapper, "program.js")

            // 泵动 microtask：直到 settled、超时、或者用户中断。
            // **墙钟**预算（dsh 的 wallTimer 同一条口径）：等工具的耗时也算在内。
            var settled = false
            var pumps = 0
            while (!settled &&
                System.currentTimeMillis() - started < timeoutMs &&
                pumps < MAX_PUMPS &&
                cancel?.invoke() != true
            ) {
                engine.evaluate("0", "pump.js")
                // 到了的回执先兑现（同一个泵 tick 里）：JS 侧那些 Promise 就是在这里被 resolve 的
                val arrived = takeReplies()
                if (arrived.isNotEmpty()) {
                    engine.evaluate("globalThis.__adsh_settleAll__(" + replyPayload(arrived) + ")", "settle.js")
                }
                settled = (engine.evaluate("globalThis.__adsh_state.settled === true", "check.js") as? Boolean) ?: false
                pumps++
                if (!settled) Thread.sleep(1)
            }

            if (cancel?.invoke() == true) {
                return CodeRunResult(
                    valueJson = null,
                    logs = logs,
                    error = "已停止",
                    durationMs = System.currentTimeMillis() - started,
                    toolCalls = calls.get(),
                    failureKind = "abort",
                    aborted = true,
                )
            }

            if (!settled) {
                // dsh 的失败信封：kind = timeout、message = "execution deadline reached (Nms)"
                return CodeRunResult(
                    valueJson = null,
                    logs = logs,
                    error = "execution deadline reached (" + timeoutMs + "ms)",
                    durationMs = System.currentTimeMillis() - started,
                    toolCalls = calls.get(),
                    failureKind = "timeout",
                )
            }

            val rawError = engine.evaluate("globalThis.__adsh_state.error", "error.js") as? String
            // 出错的调用栈（第 81 轮）：QuickJS 的栈里带 program.js:行号，据此把错误还原到
            // 模型自己写的那一行 —— 没有它，模型只能整段重写再发一次（连续调用与高报错率的主因）。
            val errorStack = engine.evaluate("globalThis.__adsh_state.errorStack", "errorStack.js") as? String
            // 顶层没被 catch 的失败：说清楚是哪个工具、原始错误是什么。
            //
            // **不回显参数**：dsh 的 ToolCallError 只有 message（dsh-code-runtime-worker-thread
            // 的 binding errorClass），参数在轨迹里本来就看得见；而 edit 这种工具的参数里躺着
            // 整段 old_string / new_string，回显等于把两段文件正文塞进错误信息里，噪音极大
            // （用户点名的第 2 项）。工具名保留：没有它，模型只能从错误文案猜是哪个调用失败的。
            val failedTool = engine.evaluate("globalThis.__adsh_state.errorTool", "errorTool.js") as? String
            val errorValue = if (failedTool != null) {
                "工具 " + failedTool + " 调用失败\n原始错误：" + (rawError ?: "(无)")
            } else {
                rawError
            }
            // 定位（第 81 轮）：把「程序第几行 + 那一行原文」补在错误后面；语法失败时再补一句
            // 「本运行时是 QuickJS，TypeScript 写法是语法错误」。模型据此一次就能改对。
            val located = errorValue?.let {
                ProgramDiagnostics.describe(program, rawError, errorStack, parserFailure = false)
            }
            val valueJson = engine.evaluate(
                "JSON.stringify(globalThis.__adsh_state.value === undefined ? null : globalThis.__adsh_state.value)",
                "value.js",
            ) as? String
            return CodeRunResult(
                valueJson = valueJson,
                logs = logs,
                // located 是从 errorValue 派生的，所以走到 else 分支时 errorValue 一定非空
                error = if (located == null) errorValue else errorValue + "\n" + located,
                durationMs = System.currentTimeMillis() - started,
                toolCalls = calls.get(),
                failureKind = if (errorValue != null) "exception" else null,
            )
        } catch (t: Throwable) {
            // 走到这里基本都是**语法**错误：程序体是直接 evaluate 的，parse 阶段就抛了，
            // 程序一行都没跑（state.error / errorStack 都还是空的）。所以这里按 parserFailure 处理：
            // 运行时不给行号，就由 ProgramDiagnostics 在程序里找出第一处 TypeScript 写法报给模型。
            val message = t.message ?: t::class.java.simpleName
            val located = ProgramDiagnostics.describe(program, message, null, parserFailure = true)
            return CodeRunResult(
                null,
                logs,
                if (located == null) message else message + "\n" + located,
                System.currentTimeMillis() - started,
                calls.get(),
                failureKind = "exception",
            )
        } finally {
            runCatching { engine.releaseObjectRecords(true) }
            runCatching { engine.close() }
        }
    }

    /** 回执打包成 __adsh_settleAll__ 吃的那张表：[[id, wire], …]（wire 交给 JsonPrimitive 转义） */
    private fun replyPayload(replies: List<Pair<Int, String>>): String =
        replies.joinToString(separator = ",", prefix = "[", postfix = "]") { (id, wire) ->
            "[" + id + "," + kotlinx.serialization.json.JsonPrimitive(wire) + "]"
        }

    /** 收一行控制台输出：进结果列表（有上限），同时**立刻**推给宿主（dsh 的 log 帧） */
    private fun addLog(logs: MutableList<String>, info: String?, cap: Int, onLog: ((String) -> Unit)?) {
        if (logs.size >= cap) return
        val line = info ?: ""
        logs += line
        runCatching { onLog?.invoke(line) }
    }

    companion object {
        /** dsh 的 ptc-runtime-node config：`timeoutMs` 默认 12e4 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** dsh 的 config `maxTimeoutMs`：程序预算的上限（模型可以通过 run_code 的 timeoutMs 抬到它） */
        const val MAX_TIMEOUT_MS = 600_000L

        const val MAX_LOG_LINES = 500
        private const val MAX_PUMPS = 200_000

        /**
         * 子调用信封里表示「被用户中断」的 code（dsh 的 tool-call 块 `error.code === "interrupted"`，
         * 界面据此把这一行画成 stopped 而不是 error）。
         */
        const val INTERRUPTED_CODE = "interrupted"

        /**
         * 注入 tools 门面与状态对象。
         *
         * dsh 的 SDK 里 `tools.x()` 返回的是 Promise —— 这里**就是真的 Promise**（第 186 轮）：
         *  - 每次调用**立刻**把帧发出去（`__adsh_post__`，非阻塞）并返回一个 Promise；
         *  - 回执由泵循环喂给 `__adsh_settleAll__`，于是多个调用在宿主侧各自异步执行、
         *    由 [com.adsh.app.core.tools.ToolConcurrency] 决定谁与谁重叠（读类并发、写类独占）；
         *  - `Promise.all` 不再需要任何补丁：原生语义就是对的。
         * 第 185 轮曾经用「惰性 thenable + 一个 tick 合并成一次批量 RPC」来近似并发，
         * 那套机器（__adsh_handle / __adsh_pending / __adsh_flush / __adsh_runAll / __adsh_callAll__）
         * 随这一轮一起删掉了。
         */
        private val PREAMBLE = """
            /* console 的对象格式化（第 81 轮）：宿主侧的回调只收**一个字符串**
               （QuickJSContext.Console.log(info: String?)），对象经 JS_ToCString 会变成
               "[object Object]" —— 模型 console.log 一个对象时等于什么都没看到，只好再写一个程序
               换个打印法（dsh 的 Node 运行时会把对象打成结构化文本）。这里把原生 console 方法包一层：
               非字符串参数先 JSON.stringify，多参数按空格连接。 */
            (function () {
              if (typeof console === 'undefined' || typeof console.log !== 'function') return;
              var format = function (value) {
                if (typeof value === 'string') return value;
                if (value === undefined) return 'undefined';
                if (value instanceof Error) return (value.name || 'Error') + ': ' + (value.message || '');
                try { return JSON.stringify(value); } catch (e) { return String(value); }
              };
              var names = ['log', 'info', 'warn', 'error'];
              for (var i = 0; i < names.length; i++) {
                (function (name) {
                  var native = console[name];
                  if (typeof native !== 'function') return;
                  console[name] = function () {
                    var parts = [];
                    for (var j = 0; j < arguments.length; j++) parts.push(format(arguments[j]));
                    return native.call(console, parts.join(' '));
                  };
                })(names[i]);
              }
            })();
            globalThis.__adsh_state = { settled: false, value: undefined, error: null };
            /* dsh 的 binding errorClass（dsh-code-runtime-worker-thread 的 makeBindingErrorClass）：
               程序里能 `e instanceof ToolCallError`，且 e.name 恒为字面量 "ToolCallError"、
               e.toolName 是被调工具名。以前这里直接抛 new Error(...)，于是 e.name === "Error"，
               SDK 里声明的那两行（name / toolName）有一条是假的。 */
            globalThis.ToolCallError = function ToolCallError(message, toolName) {
              var err = new Error(message);
              Object.setPrototypeOf(err, ToolCallError.prototype);
              Object.defineProperty(err, 'name', { value: 'ToolCallError', enumerable: true });
              Object.defineProperty(err, 'toolName', { value: toolName, enumerable: true });
              return err;
            };
            globalThis.ToolCallError.prototype = Object.create(Error.prototype);
            globalThis.ToolCallError.prototype.constructor = globalThis.ToolCallError;
            globalThis.__adsh_parse = function (raw, name) {
              return globalThis.__adsh_wire(JSON.parse(raw || '{}'), name);
            };
            /* 错误信封 → ToolCallError（唯一判定）。
               **入参是已经解析好的 JS 值**（回执路径拿到的就是 JSON.parse 的结果），
               所以这里不能再走 __adsh_parse —— 那会二次 JSON.parse，对象被转成 "[object Object]"。 */
            globalThis.__adsh_wire = function (parsed, name) {
              if (parsed && parsed.__error) {
                var err = new globalThis.ToolCallError(parsed.message || 'tool failed', parsed.toolName || String(name));
                /* ADSH 比 dsh 多带三个字段：最外层没 catch 时能说清「哪个工具、什么参数」，
                   catch 到之后还能按 code 分支（dsh 的 WebError.code） */
                err.toolArgs = parsed.args || null;
                err.retryable = parsed.retryable === true;
                err.code = parsed.code || null;
                throw err;
              }
              return parsed;
            };
            /* 一次 tools.x()：**立刻**把调用帧发出去，返回一个**真正的 Promise**（第 186 轮）。
               dsh 的绑定就是这个形状：子进程 postMessage 之后继续跑，宿主那边各自异步执行、
               由调度器决定谁与谁重叠。以前 ADSH 的 handle 是惰性的手写 thenable，then() 会
               **同步**等工具返回 —— 于是 Promise.all 里第二个调用要等第一个跑完（真机实测
               3 × web_fetch(delay/2) 串行 7.2s，而提示词写着 safe calls run concurrently）。 */
            globalThis.__adsh_next_call = 1;
            globalThis.__adsh_open_calls = {};
            /* 宿主回执的兑现口：泵循环每个 tick 把收到的信封喂进来（kotlin 侧调它）。
               参数是 [[id, wire], …]，wire 已经是 JSON 原文，所以这里走 __adsh_wire（对象进、
               对象出）—— 不能再走 __adsh_parse，那会二次 JSON.parse。 */
            globalThis.__adsh_settleAll__ = function (payload) {
              /* 调用方（kotlin 的 replyPayload）直接生成**数组字面量**，所以这里是「数组进、数组出」；
                 接受字符串形态只为容错（JSON.parse 一个数组会先 String() 成 "1,…" 再炸
                 "unexpected data at the end" —— 第 186 轮真机上就是这么踩的）。 */
              var list = (typeof payload === 'string') ? JSON.parse(payload || '[]') : (payload || []);
              for (var i = 0; i < list.length; i++) {
                var entry = globalThis.__adsh_open_calls[list[i][0]];
                if (!entry) continue;
                delete globalThis.__adsh_open_calls[list[i][0]];
                try { entry.resolve(globalThis.__adsh_wire(JSON.parse(list[i][1] || '{}'), entry.name)); }
                catch (e) { entry.reject(e); }
              }
            };
            globalThis.__adsh_invoke = function (name, args) {
              var raw = JSON.stringify(args === undefined ? {} : args);
              return new Promise(function (resolve, reject) {
                var id = globalThis.__adsh_next_call++;
                globalThis.__adsh_open_calls[id] = { resolve: resolve, reject: reject, name: name };
                try {
                  __adsh_post__(id, name, raw);
                } catch (e) {
                  delete globalThis.__adsh_open_calls[id];
                  reject(e);
                }
              });
            };
            /* 只有 SDK 里声明过的名字才是 tools 的属性 —— dsh 的 namespaces 就是这么建的
               （worker.cjs 的 makeNamespaces：null 原型对象 + 只定义已声明的名字）。
               以前这里是个 catch-all Proxy：tools.不存在的名字() 会走到宿主，
               得到一条可 catch 的 ToolCallError；dsh 里那是原生 TypeError，
               整个程序直接失败（SDK 文本里「only names supplied as separate tool schemas
               may be called directly」说的就是这件事）。 */
            globalThis.tools = {};
            (function () {
              var names = globalThis.__adsh_names || [];
              for (var i = 0; i < names.length; i++) {
                (function (name) {
                  globalThis.tools[name] = function (args) { return globalThis.__adsh_invoke(name, args); };
                })(names[i]);
              }
            })();
            /* Promise.all / allSettled 不再特判（第 186 轮）：绑定返回的是**真的 Promise**，
               原生那套就是对的 —— 谁先发谁先跑，宿主侧的调度器决定并发。以前为了让一次批量 RPC
               看起来像 Promise.all，这里替换过原生实现、还特判过「全是惰性 handle」；
               那套补丁与 __adsh_runAll / __adsh_callAll__ 一起删掉了。 */
        """.trimIndent()
    }
}
