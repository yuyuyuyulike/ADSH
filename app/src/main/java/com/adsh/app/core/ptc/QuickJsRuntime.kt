package com.adsh.app.core.ptc

import com.adsh.app.core.tools.Tool
import com.adsh.app.core.tools.ToolContext
import com.adsh.app.core.tools.ToolResult
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 程序内一次 await tools.x() 的痕迹（轨迹视图用） */
@kotlinx.serialization.Serializable
data class SubCall(
    val name: String,
    val args: String,
    val ok: Boolean,
    val result: String,
    val durationMs: Long,
    /** 这次子调用的稳定 id：界面用它把「开始」与「结束」贴成同一行 */
    val id: String = "",
    /** 是否正在跑：只有流式期间的实时行会为 true，落库的轨迹一律是 false */
    val running: Boolean = false,
    /**
     * 这次子调用产出的图片（只有 read_image 会有）。
     * 界面在子行下面渲染画廊；AgentLoop 把它收进工具行的 imagesJson，
     * 装配请求时按 dsh 的 tools-ptc deferContext 作为一条 user 消息回灌模型。
     */
    val images: List<com.adsh.app.core.agent.ToolImage> = emptyList(),
)

data class CodeRunResult(
    val valueJson: String?,
    val logs: List<String>,
    val error: String?,
    val durationMs: Long,
    /** 程序内 await tools.x() 的次数（dsh 状态栏「步」的一部分） */
    val toolCalls: Int = 0,
    /** 子调用明细 */
    val subCalls: List<SubCall> = emptyList(),
) {
    val ok: Boolean get() = error == null
}

/**
 * PTC 代码运行时（QuickJS 后端）。
 *
 * ## 为什么是这个形状（M3 真机探针实测）
 * 该 QuickJS 包装库**没有 executePendingJob**：单次 evaluate 内产生的 promise 永远不会 settle。
 * 但探针证明 **microtask 会在下一次 evaluate 调用时被排空**（`Promise.resolve().then(...)` 在第二次
 * evaluate 时才生效）。因此采用「泵 + 同步宿主函数」模型：
 *   1) 把程序包进 async IIFE，结果写进 `globalThis.__adsh_state`；
 *   2) 反复 evaluate 一个空表达式来泵动 microtask 队列，直到 settled 或超时；
 *   3) 宿主函数 `__adsh_call__` 是**同步阻塞**的（内部 runBlocking 调工具），
 *      所以程序里的 `await tools.x(...)` 在下一个泵周期即可继续。
 * 代价：同一程序内的多个工具调用是串行执行的（dsh 支持并行子调用），已在文档中记为已知差异。
 *
 * ## 异常契约（dsh-tools 的 binding errorClass + worker.cjs 的 namespaces）
 *  - **已声明**的工具失败 → 抛 `ToolCallError`：`e.name === "ToolCallError"`、
 *    `e.toolName` 是被调工具名、`e.message` 是宿主侧原文（不带 `Error: ` 前缀）；
 *  - **未声明**的名字 → `tools.<name>` 就是 undefined，调用得到原生
 *    `TypeError: tools.<name> is not a function`，整个程序失败。
 *    dsh 的 namespaces 是一个只定义了已声明名字的空原型对象，不做兜底翻译 —— 保持一致。
 */
class QuickJsRuntime(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    fun run(
        program: String,
        tools: List<Tool>,
        context: ToolContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxLogLines: Int = MAX_LOG_LINES,
    ): CodeRunResult {
        val started = System.currentTimeMillis()
        val logs = ArrayList<String>(16)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val trace = java.util.Collections.synchronizedList(ArrayList<SubCall>())
        // 子调用一跑完就回调（dsh 的 tool/ptc-dispatch）：界面可以逐行长出来，
        // 不必等整个程序结束再一次性蹦出一整棵调用树
        val onSubCall = context.onSubCall
        // 子调用「开始」也回调一次（dsh 的 tool/call）：界面先把这一行画成运行中（带扫光），
        // 跑完再用同一个 id 贴回结果 —— 否则子行一出现就是完成态，永远看不到扫光。
        val onSubCallStart = context.onSubCallStart
        val subSeq = java.util.concurrent.atomic.AtomicInteger(0)
        val engine = QuickJSContext.create()
        engine.setMaxStackSize(2 * 1024 * 1024)
        engine.setMemoryLimit(64 * 1024 * 1024)
        engine.setConsole(object : QuickJSContext.Console {
            override fun log(info: String?) = addLog(logs, info, maxLogLines)
            override fun info(info: String?) = addLog(logs, info, maxLogLines)
            override fun warn(info: String?) = addLog(logs, info, maxLogLines)
            override fun error(info: String?) = addLog(logs, info, maxLogLines)
        })

        try {
            val global = engine.getGlobalObject()
            fun record(entry: SubCall) {
                trace.add(entry)
                runCatching { onSubCall?.invoke(entry) }
            }

            /** 开跑前先报一次「开始」，返回这次子调用的 id（结束时用同一个 id 收回） */
            fun begin(name: String, rawArgs: String): String {
                val id = "sc" + subSeq.incrementAndGet()
                runCatching {
                    onSubCallStart?.invoke(
                        SubCall(
                            name = name,
                            args = rawArgs,
                            ok = true,
                            result = "",
                            durationMs = 0,
                            id = id,
                            running = true,
                        ),
                    )
                }
                return id
            }

            // 一次 await tools.x()：同步跑一个工具（dsh 的单次子调用）
            global.setProperty("__adsh_call__", JSCallFunction { args ->
                val name = args.getOrNull(0) as? String ?: return@JSCallFunction "{\"__error\":true,\"message\":\"bad tool name\"}"
                val rawArgs = args.getOrNull(1) as? String ?: "{}"
                calls.incrementAndGet()
                val callStarted = System.currentTimeMillis()
                val id = begin(name, rawArgs)
                val outcome = callTool(tools, context, name, rawArgs)
                record(
                    SubCall(
                        name = name,
                        args = rawArgs,
                        ok = outcome.ok,
                        // 轨迹里存「人看的正文」，不存带转义的 wire 信封
                        result = outcome.text,
                        durationMs = System.currentTimeMillis() - callStarted,
                        id = id,
                        // 图片随子调用一起进轨迹：界面在那一行下面渲染画廊，
                        // AgentLoop 落库后装配请求时再回灌给模型
                        images = outcome.images,
                    ),
                )
                outcome.wire
            })

            // 一次 Promise.all([tools.a(), tools.b()])：宿主侧按 maxParallelSubCalls 并发跑
            global.setProperty("__adsh_callAll__", JSCallFunction { args ->
                val payload = args.getOrNull(0) as? String ?: "[]"
                callToolsConcurrently(tools, context, payload, calls, ::begin, ::record)
            })

            // SDK 门面：把宿主回调包装成 `tools.<name>(args)`。
            // 先注入**声明过的名字**：只有它们会成为 tools 的属性（未声明的名字是原生 TypeError）
            val namesJson = tools.joinToString(separator = ",", prefix = "[", postfix = "]") {
                "\"" + escape(it.name) + "\""
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
                // （参数**不**往最终错误文本里带，见下面 errorValue 的注释）
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

            // 泵动 microtask：直到 settled 或超时
            var settled = false
            var pumps = 0
            val deadline = started + timeoutMs
            while (!settled && System.currentTimeMillis() < deadline && pumps < MAX_PUMPS) {
                engine.evaluate("0", "pump.js")
                settled = (engine.evaluate("globalThis.__adsh_state.settled === true", "check.js") as? Boolean) ?: false
                pumps++
                if (!settled) Thread.sleep(1)
            }

            if (!settled) {
                return CodeRunResult(
                    valueJson = null,
                    logs = logs,
                    error = "程序在 " + timeoutMs + "ms 内未结束（可能死循环或工具未返回）",
                    durationMs = System.currentTimeMillis() - started,
                    toolCalls = calls.get(),
                    subCalls = trace.toList(),
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
                // located 是从 errorValue 派生的，所以走到 else 分支时 errorValue 一定非空（编译器自己也能推出来）
                error = if (located == null) errorValue else errorValue + "\n" + located,
                durationMs = System.currentTimeMillis() - started,
                toolCalls = calls.get(),
                subCalls = trace.toList(),
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
                trace.toList(),
            )
        } finally {
            runCatching { engine.releaseObjectRecords(true) }
            runCatching { engine.close() }
        }
    }

    /** 一次工具调用的结果：wire 给程序、text 给轨迹与日志、images 给轨迹与模型 */
    private data class CallOutcome(
        val wire: String,
        val text: String,
        val ok: Boolean,
        val images: List<com.adsh.app.core.agent.ToolImage> = emptyList(),
    )

    /** 同步入口（单次子调用用）：内部 runBlocking 到 suspend 版 */
    private fun callTool(tools: List<Tool>, context: ToolContext, name: String, rawArgs: String): CallOutcome =
        runBlocking {
            com.adsh.app.core.tools.ToolConcurrency.configure(context.maxParallelSubCalls)
            // 写类（bash / write / edit / 未知工具）独占：dsh 的 "mutating calls run alone,
            // in submission order"（tools:sdk 段里逐字写着）——见 ToolConcurrency 的注释
            com.adsh.app.core.tools.ToolConcurrency.withToolPermit(name) {
                callToolSuspend(tools, context, name, rawArgs)
            }
        }

    /**
     * Promise.all 形态：一次宿主调用里并发跑多个子调用，
     * 并发上限 = 设置里的「并行工具调用数」（dsh 的 agent-loop.maxParallelToolCalls /
     * tools.maxParallelSubCalls 就是干这个的）。返回一个 JSON 数组，元素与入参一一对应。
     */
    private fun callToolsConcurrently(
        tools: List<Tool>,
        context: ToolContext,
        payload: String,
        calls: java.util.concurrent.atomic.AtomicInteger,
        begin: (String, String) -> String,
        record: (SubCall) -> Unit,
    ): String {
        val entries = runCatching { json.parseToJsonElement(payload).jsonArray }.getOrNull() ?: return "[]"
        val parsed = entries.mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            val name = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArgs = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "{}"
            name to rawArgs
        }
        if (parsed.isEmpty()) return "[]"
        // 全进程一个闸门（见 ToolConcurrency）：原先每批各自建一个信号量，
        // 「分两批发起」或「工具内部再并发」都能超过设置里的上限（实测一瞬间 30 个并行）
        com.adsh.app.core.tools.ToolConcurrency.configure(context.maxParallelSubCalls)
        val wires = runBlocking {
            kotlinx.coroutines.coroutineScope {
                parsed.map { (name, rawArgs) ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        // 写类独占、读类重叠（dsh 的 "safe calls run concurrently; mutating calls
                        // run alone, in submission order"）：这一次并发调用里的写类子调用会排队
                        // 一个个跑，读类之间仍然并行
                        com.adsh.app.core.tools.ToolConcurrency.withToolPermit(name) {
                            calls.incrementAndGet()
                            val callStarted = System.currentTimeMillis()
                            val id = begin(name, rawArgs)
                            val outcome = callToolSuspend(tools, context, name, rawArgs)
                            record(
                                SubCall(
                                    name = name,
                                    args = rawArgs,
                                    ok = outcome.ok,
                                    result = outcome.text,
                                    durationMs = System.currentTimeMillis() - callStarted,
                                    id = id,
                                    images = outcome.images,
                                ),
                            )
                            outcome.wire
                        }
                    }
                }.awaitAll()
            }
        }
        // 每个 wire 本身就是合法 JSON 值，直接拼成数组
        return wires.joinToString(separator = ",", prefix = "[", postfix = "]")
    }

    private suspend fun callToolSuspend(
        tools: List<Tool>,
        context: ToolContext,
        name: String,
        rawArgs: String,
    ): CallOutcome {
        fun failure(message: String, retryable: Boolean = false, code: String? = null): CallOutcome = CallOutcome(
            wire = errorWire(name, rawArgs, message, retryable, code),
            text = message,
            ok = false,
        )
        val tool = tools.firstOrNull { it.name == name } ?: return failure("未知工具：" + name)
        val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }
            .getOrElse { return failure("参数不是 JSON 对象：" + rawArgs.take(200)) }
        val result = runCatching { tool.execute(args, context) }
            .getOrElse { return failure(it.message ?: (it::class.java.simpleName + "（工具内部异常）")) }
        return when (result) {
            // 程序拿到的是「结构化值」（dsh 的 output.schema）；没给 value 的老工具退化成 { result: text }
            // 程序拿到的是「结构化值」（dsh 的 output.schema）；没给 value 的工具退化成 JSON 字符串
            is ToolResult.Ok -> CallOutcome(
                wire = result.value?.toString()
                    ?: kotlinx.serialization.json.JsonPrimitive(result.text).toString(),
                text = result.text,
                ok = true,
                images = result.images,
            )
            is ToolResult.Error -> failure(result.message, result.retryable, result.code)
        }
    }

    /**
     * 失败时的 wire 形态：带上 toolName / 参数原文 / retryable / code，
     * 这样程序没 catch 时，最外层也能说清楚「哪个工具、什么参数、原始错误」（dsh 的 ToolCallError），
     * 而程序 catch 到之后还能按 code 分支（dsh 的 WebError.code 就是这么用的）。
     */
    private fun errorWire(
        name: String,
        rawArgs: String,
        message: String,
        retryable: Boolean,
        code: String?,
    ): String = buildString {
        append("{\"__error\":true,\"toolName\":\"").append(escape(name))
        append("\",\"args\":\"").append(escape(rawArgs.take(600)))
        append("\",\"message\":\"").append(escape(message))
        append("\",\"retryable\":").append(retryable)
        append(",\"code\":")
        if (code == null) append("null") else append('"').append(escape(code)).append('"')
        append('}')
    }

    private fun addLog(logs: MutableList<String>, info: String?, cap: Int) {
        if (logs.size >= cap) return
        logs += (info ?: "")
    }

    private fun escape(s: String): String = buildString(s.length + 16) {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(" ") else append(c)
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MAX_LOG_LINES = 500
        private const val MAX_PUMPS = 200_000

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
              var parsed = JSON.parse(raw || '{}');
              if (parsed && parsed.__error) {
                var err = new globalThis.ToolCallError(parsed.message || 'tool failed', parsed.toolName || String(name));
                /* ADSH 比 dsh 多带三个字段：最外层没 catch 时能说清「哪个工具、什么参数」，
                   catch 到之后还能按 code 分支（dsh 的 WebError.code） */
                err.toolArgs = parsed.args || raw;
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
                  try { resolve(globalThis.__adsh_parse(__adsh_call__(name, raw), name)); }
                  catch (e) { reject(e); }
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
              var wires = JSON.parse(raw || '[]');
              if (settled) {
                var out = [];
                for (var j = 0; j < items.length; j++) {
                  try { out.push({ status: 'fulfilled', value: globalThis.__adsh_parse(wires[j], items[j].name) }); }
                  catch (e) { out.push({ status: 'rejected', reason: e }); }
                }
                return globalThis.__adsh_nativeAllSettled(out);
              }
              return new Promise(function (resolve, reject) {
                for (var k = 0; k < items.length; k++) {
                  try { globalThis.__adsh_parse(wires[k], items[k].name); }
                  catch (e) { reject(e); return; }
                }
                var values = [];
                for (var m = 0; m < items.length; m++) values.push(globalThis.__adsh_parse(wires[m], items[m].name));
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
