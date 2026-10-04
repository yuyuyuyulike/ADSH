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
 *   3) 宿主函数 `__adsh_call__` 是**同步阻塞**的（跨进程 RPC 到宿主，由 [PtcToolRunner] 跑工具），
 *      所以程序里的 `await tools.x(...)` 在下一个泵周期即可继续。
 * 代价：同一程序内的多个工具调用是串行执行的（dsh 支持并行子调用），已在文档中记为已知差异。
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
        invoke: (name: String, argsJson: String) -> String,
        invokeAll: (payloadJson: String) -> String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxLogLines: Int = MAX_LOG_LINES,
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
            override fun log(info: String?) = addLog(logs, info, maxLogLines)
            override fun info(info: String?) = addLog(logs, info, maxLogLines)
            override fun warn(info: String?) = addLog(logs, info, maxLogLines)
            override fun error(info: String?) = addLog(logs, info, maxLogLines)
        })

        try {
            val global = engine.getGlobalObject()

            // 一次 await tools.x()：同步 RPC 到宿主（宿主执行工具、记轨迹、回信封）
            global.setProperty("__adsh_call__", JSCallFunction { args ->
                val name = args.getOrNull(0) as? String
                    ?: return@JSCallFunction "{\"__error\":true,\"message\":\"bad tool name\"}"
                val rawArgs = args.getOrNull(1) as? String ?: "{}"
                calls.incrementAndGet()
                invoke(name, rawArgs)
            })

            // 一次 Promise.all([tools.a(), tools.b()])：整批交给宿主（闸门与提交序都在那边）
            global.setProperty("__adsh_callAll__", JSCallFunction { args ->
                invokeAll(args.getOrNull(0) as? String ?: "[]")
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
                // 忘了 await 的写法兜底：把没执行过的惰性调用按顺序补跑（不会因为改成惰性就丢调用）
                "    try { globalThis.__adsh_flush(); } catch (e) {}\n" +
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

    private fun addLog(logs: MutableList<String>, info: String?, cap: Int) {
        if (logs.size >= cap) return
        logs += (info ?: "")
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
         * dsh 的 SDK 里 `tools.x()` 返回的是 Promise，所以这里也让它是「惰性 thenable」：
         *  - 直接 `await tools.x()` → then() 里同步调一次 __adsh_call__（老路径，行为不变）；
         *  - `Promise.all([tools.a(), tools.b()])` → 走一次 __adsh_callAll__，
         *    多个工具在宿主侧并发跑（上限 = 设置里的「并行工具调用数」）；
         *  - 忘了 await 的写法兜底：程序收尾时 __adsh_flush 把没执行过的调用按顺序补跑，
         *    不会因为改成惰性就把调用弄丢。
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
            globalThis.__adsh_pending = [];
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
            /* 错误信封 → ToolCallError（单调用与批量调用共用一个判定）。
               **入参是已经解析好的 JS 值**：批量路径拿到的就是 JSON.parse 的结果，
               再交给 __adsh_parse 会二次 JSON.parse（对象被转成 "[object Object]"）
               —— 那正是 Promise.all 在真机上必然抛 SyntaxError 的原因，见 __adsh_runAll。 */
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
            globalThis.__adsh_handle = function (name, args) {
              var raw = JSON.stringify(args === undefined ? {} : args);
              var handle = {
                __adshLazy: true,
                name: name,
                raw: raw,
                then: function (resolve, reject) {
                  globalThis.__adsh_cancel(handle);
                  /* 返回一个**真正的 Promise**。dsh 里 tools.x() 就是 Promise，模型会写
                     `tools.x({...}).then(f).catch(g)`；以前 then 什么都不返回，`.catch` 立刻炸在
                     "not a function" 上，看起来像这个工具不存在（第 91 轮真机实测，报告 B1 的另一半）。
                     thenable 协议本身不要求返回什么，所以 await / Promise.all 的行为不变。 */
                  var p = new Promise(function (res, rej) {
                    try { res(globalThis.__adsh_parse(__adsh_call__(name, raw), name)); }
                    catch (e) { rej(e); }
                  });
                  return p.then(resolve, reject);
                },
                /* dsh 的绑定是 **async 函数**（真 Promise），所以那边 `tools.x().catch(g)` 与
                   `.finally(f)` 都是合法的；ADSH 的 handle 是惰性的手写 thenable，本来只有 then ——
                   模型直接 `.catch` 会拿到 "not a function"，看起来像工具不存在（实测报告第 9 点）。
                   这两个方法按 Promise 语义补上；真正的调用仍然只发生在 then() 里，
                   所以惰性批量合并（Promise.all → __adsh_callAll__）一点不受影响。 */
                catch: function (onRejected) {
                  return handle.then(undefined, onRejected);
                },
                finally: function (onFinally) {
                  var run = function () { return typeof onFinally === 'function' ? onFinally() : undefined; };
                  return handle.then(
                    function (value) { return Promise.resolve(run()).then(function () { return value; }); },
                    function (error) { return Promise.resolve(run()).then(function () { throw error; }); }
                  );
                }
              };
              globalThis.__adsh_pending.push(handle);
              return handle;
            };
            globalThis.__adsh_cancel = function (handle) {
              var at = globalThis.__adsh_pending.indexOf(handle);
              if (at >= 0) globalThis.__adsh_pending.splice(at, 1);
            };
            globalThis.__adsh_settle = function (handle, raw) {
              globalThis.__adsh_cancel(handle);
              return globalThis.__adsh_parse(raw, handle.name);
            };
            globalThis.__adsh_flush = function () {
              var rest = globalThis.__adsh_pending.slice();
              globalThis.__adsh_pending.length = 0;
              for (var i = 0; i < rest.length; i++) {
                try { __adsh_call__(rest[i].name, rest[i].raw); } catch (e) {}
              }
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
                  globalThis.tools[name] = function (args) { return globalThis.__adsh_handle(name, args); };
                })(names[i]);
              }
            })();
            /* Promise.all / allSettled 特判：全是惰性 handle 时合并成一次并发调用 */
            globalThis.__adsh_nativeAll = Promise.all.bind(Promise);
            globalThis.__adsh_nativeAllSettled = Promise.allSettled.bind(Promise);
            globalThis.__adsh_allLazy = function (items) {
              if (!items || items.length === 0) return false;
              for (var i = 0; i < items.length; i++) {
                var it = items[i];
                if (!it || it.__adshLazy !== true) return false;
              }
              return true;
            };
            globalThis.__adsh_runAll = function (items, settled) {
              var payload = [];
              for (var i = 0; i < items.length; i++) {
                globalThis.__adsh_cancel(items[i]);
                payload.push([items[i].name, items[i].raw]);
              }
              var raw = __adsh_callAll__(JSON.stringify(payload));
              /* wires 已经是**解析过的 JS 值**，所以下面只能走 __adsh_wire（对象进、对象出），
                 不能再走 __adsh_parse —— 它内部还要 JSON.parse 一次，对象被转成 "[object Object]"，
                 直接抛 SyntaxError: unexpected token: 'object'。
                 这正是真机上 `Promise.all([tools.x()])`（哪怕只有一个元素）必然失败的原因：
                 提示词唯一推荐的并发写法，曾经是运行时唯一不接受的写法（第 91 轮实测，报告 B1）。 */
              var wires = JSON.parse(raw || '[]');
              if (settled) {
                var out = [];
                for (var j = 0; j < items.length; j++) {
                  try { out.push({ status: 'fulfilled', value: globalThis.__adsh_wire(wires[j], items[j].name) }); }
                  catch (e) { out.push({ status: 'rejected', reason: e }); }
                }
                /* 直接兑现成**结果数组**，不能再交给 nativeAllSettled：那样每个元素会被再包一层
                   （返回 [{status:'fulfilled', value:{status:'rejected', reason:…}}]），
                   allSettled 的语义就废了 —— 元素的 status/reason 全被吞掉。 */
                return new Promise(function (resolve) { resolve(out); });
              }
              return new Promise(function (resolve, reject) {
                for (var k = 0; k < items.length; k++) {
                  try { globalThis.__adsh_wire(wires[k], items[k].name); }
                  catch (e) { reject(e); return; }
                }
                var values = [];
                for (var m = 0; m < items.length; m++) values.push(globalThis.__adsh_wire(wires[m], items[m].name));
                resolve(values);
              });
            };
            Promise.all = function (items) {
              if (Array.isArray(items) && globalThis.__adsh_allLazy(items)) return globalThis.__adsh_runAll(items, false);
              return globalThis.__adsh_nativeAll(items);
            };
            Promise.allSettled = function (items) {
              if (Array.isArray(items) && globalThis.__adsh_allLazy(items)) return globalThis.__adsh_runAll(items, true);
              return globalThis.__adsh_nativeAllSettled(items);
            };
        """.trimIndent()
    }
}
