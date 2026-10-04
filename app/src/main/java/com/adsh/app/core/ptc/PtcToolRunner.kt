package com.adsh.app.core.ptc

import com.adsh.app.core.tools.SubCallTrace
import com.adsh.app.core.tools.Tool
import com.adsh.app.core.tools.ToolContext
import com.adsh.app.core.tools.ToolResult
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * PTC 程序的**工具侧**（宿主进程）：程序里每一次 `await tools.x(...)` 都落在这里执行。
 *
 * 第 183 轮按 dsh 的隔离模型重排：dsh 的 `dsh-ptc-runtime-node` 把程序放进**独立子进程**
 * （`isolation = "process"`，每次调用一个新进程），父进程通过 RPC 处理 `tools.x()` —— 于是
 * 程序里的同步死循环最多烧死那个子进程，父进程（以及整轮对话）不受影响。ADSH 照这个模型把
 * 程序搬进 `:ptc` 进程（见 [PtcWorkerService]），**工具执行留在主进程**（这里）：工具要用的
 * `ToolContext`（工作区 / 沙箱 / 设置 / 会话）本来就在主进程，跨进程搬过去没有意义。
 *
 * 所以这个类就是那条 RPC 的服务端：`call` / `callAll` 是**同步**入口（worker 里的 JS 线程
 * 会一直等到信封回来），闸门（[com.adsh.app.core.tools.ToolConcurrency]）、用户中断探针、
 * 子调用轨迹（界面那几行 + 落库）全在这里，和搬走之前一模一样。
 */
internal class PtcToolRunner(
    private val tools: List<Tool>,
    private val context: ToolContext,
    private val cancel: () -> Boolean,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 程序内 `await tools.x()` 的次数（dsh 状态栏「步」的一部分） */
    private val calls = AtomicInteger(0)

    /** 子调用序号：`<execToken>:ptc:<n>`，n 在**提交那一刻**按程序顺序分配（见 [callAll]） */
    private val subSeq = AtomicInteger(0)

    /** 已经结算的子调用（按结算顺序）：失败回执 completedDigest 读它 */
    private val settled = java.util.Collections.synchronizedList(ArrayList<SubCall>())

    fun toolCalls(): Int = calls.get()

    fun subCalls(): List<SubCall> = synchronized(settled) { settled.toList() }

    private fun nextSubId(): String = context.execToken.toString() + ":ptc:" + subSeq.incrementAndGet()

    /**
     * 报一次「开始」（dsh 的 `tool/ptc-dispatch-start`）：界面先把这一行画成运行中（带扫光），
     * 跑完再用同一个 id 贴回结果 —— 否则子行一出现就是完成态，永远看不到扫光。
     */
    private fun announce(name: String, rawArgs: String, id: String) {
        runCatching {
            context.onSubCallStart?.invoke(
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

    /** 结算一条子调用：界面（onSubCall）与这一轮的工具行落库（SubCallTrace）都读它 */
    private fun record(entry: SubCall) {
        settled += entry
        runCatching { context.onSubCall?.invoke(entry) }
        SubCallTrace.record(listOf(entry))
    }

    /** 一次 `await tools.x()`：同步跑一个工具，返回给程序的 wire 信封 */
    fun call(name: String, rawArgs: String): String {
        val id = nextSubId()
        announce(name, rawArgs, id)
        val started = System.currentTimeMillis()
        val outcome = try {
            runBlocking {
                com.adsh.app.core.tools.ToolConcurrency.configure(context.maxParallelSubCalls)
                watchCancel {
                    com.adsh.app.core.tools.ToolConcurrency.withToolPermit(name) {
                        callToolSuspend(name, rawArgs)
                    }
                }
            }
        } catch (cancelSignal: kotlinx.coroutines.CancellationException) {
            interruptedOutcome(name, rawArgs)
        }
        calls.incrementAndGet()
        record(
            SubCall(
                name = name,
                args = rawArgs,
                ok = outcome.ok,
                result = outcome.text,
                durationMs = System.currentTimeMillis() - started,
                id = id,
                images = outcome.images,
                deliverables = outcome.deliverables,
            ),
        )
        return outcome.wire
    }

    /**
     * `Promise.all([tools.a(), tools.b()])`：一次 RPC 里并发跑一批。
     *
     * 提交序 = 程序序（dsh 的 pendingQueue / commitQueue，spec-log §4.1-B）：id 在任何挂起之前
     * 一次分完、「开始」也按这个顺序宣布；结算按提交序入日志（[SubCallOrder] 的队头阻塞）。
     * 于是子行的顺序、id 序号、交付物与图片的收集顺序都等于模型写下的顺序。
     */
    fun callAll(payload: String): String {
        val entries = runCatching { json.parseToJsonElement(payload).jsonArray }.getOrNull() ?: return "[]"
        val parsed = entries.mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            val name = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArgs = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "{}"
            name to rawArgs
        }
        if (parsed.isEmpty()) return "[]"
        val ids = parsed.map { nextSubId() }
        ids.forEachIndexed { index, id -> announce(parsed[index].first, parsed[index].second, id) }
        val order = SubCallOrder(parsed.size)
        com.adsh.app.core.tools.ToolConcurrency.configure(context.maxParallelSubCalls)
        val wires = arrayOfNulls<String>(parsed.size)

        suspend fun runOne(index: Int): String {
            val (name, rawArgs) = parsed[index]
            return com.adsh.app.core.tools.ToolConcurrency.withToolPermit(name) {
                calls.incrementAndGet()
                val callStarted = System.currentTimeMillis()
                val outcome = callToolSuspend(name, rawArgs)
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
                ).forEach(::record)
                outcome.wire
            }
        }

        try {
            runBlocking {
                watchCancel {
                    // 读类并发、写类按提交顺序（见 ToolConcurrency.runBatch 的长注释）
                    com.adsh.app.core.tools.ToolConcurrency.runBatch(parsed.map { it.first }) { index ->
                        wires[index] = runOne(index)
                    }
                }
            }
        } catch (cancelSignal: kotlinx.coroutines.CancellationException) {
            // 中断：已经跑完的那些仍按提交序入日志（没跑完的号永远不进日志，dsh 的 abandon()）
            order.drain().forEach(::record)
            return parsed.joinToString(separator = ",", prefix = "[", postfix = "]") { (name, rawArgs) ->
                interruptedOutcome(name, rawArgs).wire
            }
        }
        return wires.joinToString(separator = ",", prefix = "[", postfix = "]") { it ?: "null" }
    }

    /** 一次工具调用的结果：wire 给程序、text 给轨迹与日志、images 给轨迹与模型 */
    private data class CallOutcome(
        val wire: String,
        val text: String,
        val ok: Boolean,
        val images: List<com.adsh.app.core.agent.ToolImage> = emptyList(),
        val deliverables: List<com.adsh.app.core.agent.PresentedFile> = emptyList(),
    )

    /** 被用户中断的子调用：给程序一个「已停止」的失败信封（dsh 的 code = interrupted） */
    private fun interruptedOutcome(name: String, rawArgs: String): CallOutcome = CallOutcome(
        wire = toolErrorWire(
            name,
            rawArgs,
            "已停止：用户中断了这一轮",
            retryable = false,
            code = QuickJsRuntime.INTERRUPTED_CODE,
        ),
        text = "已停止：用户中断了这一轮",
        ok = false,
    )

    /**
     * 中断看门狗：探针命中就把这一段协程作用域取消掉。
     *
     * 子调用是在 `runBlocking` 里同步跑的（RPC 的 JS 线程要等信封），所以外层的协程取消
     * **传不进来** —— 没有这个看门狗，程序里一个 120s 的 bash 就让「停止」等两分钟。看门狗每
     * [CANCEL_PROBE_MS] 问一次，命中即取消：正在跑的工具（bash 的 Process.waitFor、文件读写）
     * 会看到自己的 job 已经取消，各自按可中断路径收尾。
     */
    private suspend fun <T> watchCancel(block: suspend () -> T): T = coroutineScope {
        val scopeJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val watchdog = launch {
            while (true) {
                delay(CANCEL_PROBE_MS)
                if (cancel()) {
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

    private suspend fun callToolSuspend(name: String, rawArgs: String): CallOutcome {
        fun failure(rawMessage: String, retryable: Boolean = false, code: String? = null): CallOutcome {
            // 子调用的正文（界面、轨迹、落库）同样过一遍脱敏；wire 保持原样给程序用
            val message = com.adsh.app.core.tools.SecretRedaction.redact(rawMessage)
            return CallOutcome(
                wire = toolErrorWire(name, rawArgs, rawMessage, retryable, code),
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
                text = com.adsh.app.core.tools.SecretRedaction.redact(result.text),
                ok = true,
                images = result.images,
                deliverables = result.deliverables,
            )
            is ToolResult.Error -> failure(result.message, result.retryable, result.code)
        }
    }

    private companion object {
        /** 中断看门狗的轮询粒度：正在跑的子调用最多迟这么久发现「用户停了」 */
        const val CANCEL_PROBE_MS = 50L
    }
}

/**
 * 失败时的 wire 形态：带上 toolName / 参数原文 / retryable / code，
 * 这样程序没 catch 时，最外层也能说清楚「哪个工具、什么参数、原始错误」（dsh 的 ToolCallError），
 * 而程序 catch 到之后还能按 code 分支（dsh 的 WebError.code 就是这么用的）。
 *
 * 三个调用方共用一份：工具失败（[PtcToolRunner]）、用户中断（同上）、控制通道断掉
 * （[PtcWorkerService] 要拿它把还等着的引擎线程放行）。
 */
internal fun toolErrorWire(
    name: String,
    rawArgs: String,
    message: String,
    retryable: Boolean = false,
    code: String? = null,
): String = buildString {
    append("{\"__error\":true,\"toolName\":\"").append(jsStringEscape(name))
    append("\",\"args\":\"").append(jsStringEscape(rawArgs.take(600)))
    append("\",\"message\":\"").append(jsStringEscape(message))
    append("\",\"retryable\":").append(retryable)
    append(",\"code\":")
    if (code == null) append("null") else append('"').append(jsStringEscape(code)).append('"')
    append('}')
}

/** JS 字符串转义（toolErrorWire 与给程序注入工具名列表共用一份，别再各写一遍） */
internal fun jsStringEscape(s: String): String = buildString(s.length + 16) {
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
