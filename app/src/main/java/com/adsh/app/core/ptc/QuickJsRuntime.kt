package com.adsh.app.core.ptc

import com.adsh.app.core.tools.Tool
import com.adsh.app.core.tools.ToolContext
import com.adsh.app.core.tools.ToolResult
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /**
     * 这次子调用声明的交付物（只有 present 会有）—— dsh 的 deliverables/presented。
     * 与 [images] 同路：AgentLoop 把它收进工具行的 deliverablesJson，界面按轮画在轮尾。
     */
    val deliverables: List<com.adsh.app.core.agent.PresentedFile> = emptyList(),
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
    /** 这次运行是因为用户中断而停下的（[run] 的 `cancel` 探针命中），不是程序自己结束 */
    val aborted: Boolean = false,
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

    /**
     * 跑一段 PTC 程序。
     *
     * @param cancel 「用户中断了吗」的探针。为 null = 不可中断（旧行为）。命中时立刻停：
     *   ① 泵循环退出、程序不再往下跑；② 正在等工具的那些子调用会被看门狗取消（见 [callTool]）,
     *   于是阻塞在 bash / 文件 IO 里的那一层也跟着结束。没有它的话「停止」要等程序自己跑完 ——
     *   程序里一个 120s 的 bash 就把整个中断拖到两分钟（用户实测的「中断响应不够迅速」）。
     */
    fun run(
        program: String,
        tools: List<Tool>,
        context: ToolContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxLogLines: Int = MAX_LOG_LINES,
        cancel: (() -> Boolean)? = null,
    ): CodeRunResult {
        val started = System.currentTimeMillis()
        val logs = ArrayList<String>(16)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val trace = java.util.Collections.synchronizedList(ArrayList<SubCall>())
        // 子调用一跑完就回调（dsh 的 tool/ptc-dispatch）：界面可以逐行长出来，
        // 不必等整个程序结束再一次性蹦出一整棵调用树
        val onSubCall = context.onSubCall
        // 这次顶层执行的序号：两个回调都带着它回去，界面据此认领轨迹（见 ToolContext.onSubCall）
        val execToken = context.execToken
        // 子调用「开始」也回调一次（dsh 的 tool/call）：界面先把这一行画成运行中（带扫光），
        // 跑完再用同一个 id 贴回结果 —— 否则子行一出现就是完成态，永远看不到扫光。
        val onSubCallStart = context.onSubCallStart
        val subSeq = java.util.concurrent.atomic.AtomicInteger(0)
        // 「等工具」的时间不计入程序预算（第九十一轮，审查报告 S3/T1）：工具调用是同步阻塞的
        // （见下面的泵循环），以前拿 started + timeoutMs 当死线 —— 用户在提问卡上想了两分钟，
        // 工具一返回循环条件立刻为假，程序被判超时；而子调用的正文本来就不回灌模型
        // （AgentLoop：模型只看到 run_code 的最终返回），等于问题白问、答案直接丢。
        // 现在预算只算「程序自己跑了多久」：把每次子调用的耗时累加进来，每个工具仍有自己的超时。
        val toolWaitMs = java.util.concurrent.atomic.AtomicLong(0)
        val engine = QuickJSContext.create()
        // **这个数必须小于执行线程的真实栈**（第 120 轮真机实测：一个 f(n)=1+f(n-1) 递归 5000 层的
        // 探针把 App 整个打崩了）。QuickJS 的溢出判据是 `sp < stack_top - stack_size` —— 报的是它
        // **自己记账**的用量；账比 pthread 栈还大时它永远不会先开口，递归一路吃穿栈：
        // SIGSEGV（crash 日志 Cause: "stack pointer is not in a rw map"，512 帧全是同一个
        // JS_CallInternal 帧），进程级，界面上什么都来不及说。
        // dsh 没有这层风险 —— 它的 PTC 程序跑在**独立进程**里（dsh-ptc-runtime-node 是 spawn 出来的
        // Node 进程），程序把自己跑崩只死那个进程。ADSH 是同进程 QuickJS，只能让 QuickJS 先开口：
        // 取它自己的默认值 256 KB（Android 线程栈约 1 MB，留 3 倍余量），同样的递归于是得到
        // 一条可 catch 的 `InternalError: stack overflow`，走 [ProgramDiagnostics] 报给模型。
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
            fun record(entry: SubCall) {
                trace.add(entry)
                runCatching { onSubCall?.invoke(entry) }
            }

            /**
             * 下一个子调用的 id（dsh 的 `subCallId = callId + ':ptc:' + n`，spec-log §3）：
             * 身份里带着父调用，界面按前缀归属，不需要「谁先到」的判据；
             * **n 在提交那一刻按程序顺序分配**（任何挂起 / 抢闸门之前），并发跑完也用同一个号。
             */
            fun nextSubId(): String = execToken.toString() + ":ptc:" + subSeq.incrementAndGet()

            /**
             * 报一次「开始」（dsh 的 `tool/ptc-dispatch-start`）：按提交序调用，界面先把这一行
             * 画成运行中（带扫光），跑完再用同一个 id 贴回结果 —— 否则子行一出现就是完成态。
             */
            fun announce(name: String, rawArgs: String, id: String) {
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
            }

            // 一次 await tools.x()：同步跑一个工具（dsh 的单次子调用）
            global.setProperty("__adsh_call__", JSCallFunction { args ->
                val name = args.getOrNull(0) as? String ?: return@JSCallFunction "{\"__error\":true,\"message\":\"bad tool name\"}"
                val rawArgs = args.getOrNull(1) as? String ?: "{}"
                calls.incrementAndGet()
                val callStarted = System.currentTimeMillis()
                val id = nextSubId()
                announce(name, rawArgs, id)
                // 中断时这个子调用是按「已停止」收尾的（见 interruptedOutcome）：异常**不能**
                // 穿过 JSCallFunction 抛回 native —— 那是在 JNI 回调里，行为不可控。
                // 真正让程序停下的判断在泵循环里（run 的 cancel 检查）。
                val outcome = try {
                    callTool(tools, context, name, rawArgs, cancel)
                } catch (cancelSignal: kotlinx.coroutines.CancellationException) {
                    interruptedOutcome(name, rawArgs)
                }
                toolWaitMs.addAndGet(System.currentTimeMillis() - callStarted)
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
                        // 交付物同路：AgentLoop 把它写进工具行的 deliverablesJson（dsh 的
                        // deliverables/presented），界面在轮尾画文件卡片
                        deliverables = outcome.deliverables,
                    ),
                )
                outcome.wire
            })

            // 一次 Promise.all([tools.a(), tools.b()])：宿主侧按 maxParallelSubCalls 并发跑
            global.setProperty("__adsh_callAll__", JSCallFunction { args ->
                val payload = args.getOrNull(0) as? String ?: "[]"
                val batchStarted = System.currentTimeMillis()
                val wires = callToolsConcurrently(
                    tools, context, payload, calls, ::nextSubId, ::announce, ::record, cancel,
                )
                toolWaitMs.addAndGet(System.currentTimeMillis() - batchStarted)
                wires
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

            // 泵动 microtask：直到 settled、超时、或者用户中断。
            // 预算 = 程序自己的执行时间：等工具那部分（toolWaitMs）扣掉（见上面的注释）。
            var settled = false
            var pumps = 0
            while (!settled &&
                System.currentTimeMillis() - started - toolWaitMs.get() < timeoutMs &&
                pumps < MAX_PUMPS &&
                cancel?.invoke() != true
            ) {
                engine.evaluate("0", "pump.js")
                settled = (engine.evaluate("globalThis.__adsh_state.settled === true", "check.js") as? Boolean) ?: false
                pumps++
                if (!settled) Thread.sleep(1)
            }

            if (cancel?.invoke() == true) {
                // 中断：程序剩下的部分不再跑（没 await 的调用也不补跑），交给上层抛取消
                return CodeRunResult(
                    valueJson = null,
                    logs = logs,
                    error = "已停止",
                    durationMs = System.currentTimeMillis() - started,
                    toolCalls = calls.get(),
                    subCalls = trace.toList(),
                    aborted = true,
                )
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
        val deliverables: List<com.adsh.app.core.agent.PresentedFile> = emptyList(),
    )

    /** 同步入口（单次子调用用）：内部 runBlocking 到 suspend 版 */
    private fun callTool(
        tools: List<Tool>,
        context: ToolContext,
        name: String,
        rawArgs: String,
        cancel: (() -> Boolean)? = null,
    ): CallOutcome =
        runBlocking {
            com.adsh.app.core.tools.ToolConcurrency.configure(context.maxParallelSubCalls)
            // 写类（bash / write / edit / 未知工具）独占：dsh 的 "mutating calls run alone,
            // in submission order"（tools:sdk 段里逐字写着）——见 ToolConcurrency 的注释
            watchCancel(cancel) {
                com.adsh.app.core.tools.ToolConcurrency.withToolPermit(name) {
                    callToolSuspend(tools, context, name, rawArgs)
                }
            }
        }

    /** 被用户中断的子调用：给程序一个「已停止」的失败信封（dsh 的 code = interrupted） */
    private fun interruptedOutcome(name: String, rawArgs: String): CallOutcome = CallOutcome(
        wire = errorWire(name, rawArgs, "已停止：用户中断了这一轮", retryable = false, code = INTERRUPTED_CODE),
        text = "已停止：用户中断了这一轮",
        ok = false,
    )

    /**
     * 中断看门狗：探针命中就把这一段协程作用域取消掉。
     *
     * 子调用是在 `runBlocking` 里同步跑的（QuickJS 的回调是同步宿主函数，见文件头的模型说明），
     * 所以外层的协程取消**传不进来** —— 没有这个看门狗，程序里一个 120s 的 bash 就让「停止」
     * 等两分钟。看门狗每 [CANCEL_PROBE_MS] 问一次，命中即取消：正在跑的工具（bash 的
     * Process.waitFor、文件的读写）会看到自己的 job 已经取消，各自按可中断路径收尾。
     */
    private suspend fun <T> watchCancel(interrupted: (() -> Boolean)?, block: suspend () -> T): T {
        if (interrupted == null) return block()
        return coroutineScope {
            // 这个 coroutineScope 的 Job：看门狗把它取消掉，正在跑的工具就会看到自己的
            // job 已经取消（bash 的 Process.waitFor 因此提前收手）
            val scopeJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val watchdog = launch {
                while (true) {
                    delay(CANCEL_PROBE_MS)
                    if (interrupted()) {
                        scopeJob?.cancel()
                        return@launch
                    }
                }
            }
            try {
                block()
            } finally {
                watchdog.cancel()
            }
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
        nextId: () -> String,
        announce: (String, String, String) -> Unit,
        record: (SubCall) -> Unit,
        cancel: (() -> Boolean)? = null,
    ): String {
        val entries = runCatching { json.parseToJsonElement(payload).jsonArray }.getOrNull() ?: return "[]"
        val parsed = entries.mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            val name = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArgs = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "{}"
            name to rawArgs
        }
        if (parsed.isEmpty()) return "[]"
        // **提交序 = 程序序**（dsh 的 pendingQueue / commitQueue，spec-log §4.1-B）：
        //  - id 在这里按程序顺序一次分完（任何挂起之前，spec-log §3「n 在任何 await 之前自增」）；
        //  - 「开始」也按这个顺序宣布 —— 一次 `Promise.all` 是一次**原子提交**，dsh 的单车道会按
        //    并发策略逐条 start，顺序相同（这里整批一起宣布：行先出现，跑仍然要过闸门）；
        //  - 结算按提交序入日志（[SubCallOrder] 的队头阻塞）：谁先跑完都不影响日志顺序。
        // 于是子行的顺序、它们的 id 序号、交付物与图片的收集顺序都等于模型写下的顺序，
        // 不再由协程调度决定（旧写法实测 10 个并发 bash 的展示顺序是 2,1,3,5,6,7,8,10,9,4）。
        val ids = parsed.map { nextId() }
        ids.forEachIndexed { index, id -> announce(parsed[index].first, parsed[index].second, id) }
        val order = SubCallOrder(parsed.size)
        // 全进程一个闸门（见 ToolConcurrency）：原先每批各自建一个信号量，
        // 「分两批发起」或「工具内部再并发」都能超过设置里的上限（实测一瞬间 30 个并行）
        com.adsh.app.core.tools.ToolConcurrency.configure(context.maxParallelSubCalls)
        val wires = arrayOfNulls<String>(parsed.size)

        /** 跑第 index 个：与单调用同一条路（拿到名额 → 执行 → 按提交序记账） */
        suspend fun runOne(index: Int): String {
            val (name, rawArgs) = parsed[index]
            return com.adsh.app.core.tools.ToolConcurrency.withToolPermit(name) {
                calls.incrementAndGet()
                val callStarted = System.currentTimeMillis()
                val outcome = callToolSuspend(tools, context, name, rawArgs)
                order.settle(
                    index,
                    SubCall(
                        name = name,
                        args = rawArgs,
                        ok = outcome.ok,
                        result = outcome.text,
                        durationMs = System.currentTimeMillis() - callStarted,
                        id = ids[index],
                        images = outcome.images,
                        deliverables = outcome.deliverables,
                    ),
                ).forEach(record)
                outcome.wire
            }
        }

        try {
            runBlocking {
                watchCancel(cancel) {
                    // 读类并发、写类按提交顺序（见 ToolConcurrency.runBatch 的长注释：
                    // 旧写法让写类各自 async 抢闸门，串行成立但顺序由协程调度决定 ——
                    // 真机实测 10 个并发 bash 的执行顺序是 2,1,3,5,6,7,8,10,9,4，报告 1.1）
                    com.adsh.app.core.tools.ToolConcurrency.runBatch(parsed.map { it.first }) { index ->
                        wires[index] = runOne(index)
                    }
                }
            }
        } catch (cancelSignal: kotlinx.coroutines.CancellationException) {
            // 中断：已经跑完的那些仍按提交序入日志（没跑完的号永远不进日志，dsh 的 abandon()），
            // 整批都按「已停止」回信封（程序随后由泵循环的 cancel 检查终止）
            order.drain().forEach(record)
            return parsed.joinToString(separator = ",", prefix = "[", postfix = "]") { (name, rawArgs) ->
                interruptedOutcome(name, rawArgs).wire
            }
        }
        // 每个 wire 本身就是合法 JSON 值，直接拼成数组
        return wires.joinToString(separator = ",", prefix = "[", postfix = "]") { it ?: "null" }
    }

    private suspend fun callToolSuspend(
        tools: List<Tool>,
        context: ToolContext,
        name: String,
        rawArgs: String,
    ): CallOutcome {
        fun failure(rawMessage: String, retryable: Boolean = false, code: String? = null): CallOutcome {
            // 子调用的正文（界面、轨迹、落库）同样过一遍脱敏；wire 保持原样给程序用
            val message = com.adsh.app.core.tools.SecretRedaction.redact(rawMessage)
            return CallOutcome(
                wire = errorWire(name, rawArgs, rawMessage, retryable, code),
                text = message,
                ok = false,
            )
        }
        val tool = tools.firstOrNull { it.name == name } ?: return failure("未知工具：" + name)
        val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }
            .getOrElse { return failure("参数不是 JSON 对象：" + rawArgs.take(200)) }
        val result = runCatching { tool.execute(args, context) }
            .getOrElse {
                // 取消（用户中断 / 看门狗）不能被吞成一条「工具失败」：那样程序会以为自己
                // 只是调用失败、接着往下跑，而外层已经不要结果了
                if (it is kotlinx.coroutines.CancellationException) throw it
                // 工具内部异常在这里留一份堆栈：模型只看到一行 message，真机上要定位只能靠它
                android.util.Log.w("ADSH", "工具 " + name + " 抛出异常", it)
                return failure(it.message ?: (it::class.java.simpleName + "（工具内部异常）"))
            }
        return when (result) {
            // 程序拿到的是「结构化值」（dsh 的 output.schema）；没给 value 的工具退化成 JSON 字符串
            is ToolResult.Ok -> CallOutcome(
                wire = result.value?.toString()
                    ?: kotlinx.serialization.json.JsonPrimitive(result.text).toString(),
                // 正文过脱敏（wire 不过：程序自己还要用那份数据，见 SecretRedaction 的注释）
                text = com.adsh.app.core.tools.SecretRedaction.redact(result.text),
                ok = true,
                images = result.images,
                deliverables = result.deliverables,
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
         * 中断看门狗的轮询粒度：正在跑的子调用最多迟这么久发现「用户停了」。
         * 与 [com.adsh.app.runtime.termux.TermuxRuntime.run] 的探针粒度同量级。
         */
        private const val CANCEL_PROBE_MS = 50L

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
