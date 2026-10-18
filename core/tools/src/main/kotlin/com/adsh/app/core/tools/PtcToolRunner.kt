package com.adsh.app.core.tools

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.adsh.app.seam.ptc.INTERRUPTED_CODE
import com.adsh.app.seam.ptc.SubCall
import com.adsh.app.seam.ptc.toolErrorWire

/**
 * PTC 程序的**工具侧**（宿主进程）：程序里每一次 `await tools.x(...)` 都落在这里执行。
 *
 * 隔离模型照 dsh：dsh 的 `dsh-ptc-runtime-node` 把程序放进**独立子进程**
 * （`isolation = "process"`，每次调用一个新进程），父进程通过 RPC 处理 `tools.x()` —— 于是
 * 程序里的同步死循环最多烧死那个子进程，父进程（以及整轮对话）不受影响。ADSH 照这个模型把
 * 程序搬进 `:ptc` 进程（见 [com.adsh.app.platform.ptc.PtcWorkerService]），**工具执行留在主进程**（这里）：工具要用的
 * `ToolContext`（工作区 / 沙箱 / 设置 / 会话）本来就在主进程，跨进程搬过去没有意义。
 *
 * 所以这个类就是那条 RPC 的服务端：`call` 是**一次工具调用的执行体**（没有
 * 「批调用」这个入口：JS 那边每次 `tools.x()` 各自发一帧、各自异步，谁与谁重叠由闸门决定），
 * 闸门（[com.adsh.app.core.tools.ToolConcurrency]）、用户中断探针、子调用轨迹（界面那几行 +
 * 落库）全在这里，和搬走之前一模一样。
 */
internal class PtcToolRunner(
    private val tools: List<Tool>,
    private val context: ToolContext,
    private val cancel: () -> Boolean,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    /** 子调用序号：`<execToken>:ptc:<n>`，n 在**提交那一刻**按程序顺序分配（见 [callAll]） */
    private val subSeq = AtomicInteger(0)

    /** 已经结算的子调用（按结算顺序）：失败回执 completedDigest 读它 */
    private val settled = java.util.Collections.synchronizedList(ArrayList<SubCall>())

    /**
     * 这一次 run_code 的工具调度器（dsh 的 per-run scheduler，见 [ToolConcurrency]）。
     * **每轮一份**：上一轮卡在非协作取消里的调用不会把下一轮的队列堵死。
     */
    private val concurrency = ToolConcurrency(context.maxParallelSubCalls)

    /**
     * 已经报过「开始」、还没结算的子调用（按提交顺序）。**run_code 结束（超时 / worker 崩溃 /
     * 用户中断）时要把它们按 dsh 的 abort 语义结算掉** —— 否则父行报错了，下面那几行还在转流光，
     * 而且晚到的完成结果会覆盖成成功（用户报的「run-code 超时后子调用还在执行」）。
     */
    private val open = java.util.Collections.synchronizedMap(LinkedHashMap<String, OpenCall>())

    private class OpenCall(val sub: SubCall, val startedAt: Long)

    fun subCalls(): List<SubCall> = synchronized(settled) { settled.toList() }

    private fun nextSubId(): String = context.execToken.toString() + ":ptc:" + subSeq.incrementAndGet()

    /**
     * 报一次「开始」（dsh 的 `tool/ptc-dispatch-start`）：界面先把这一行画成运行中（带扫光），
     * 跑完再用同一个 id 贴回结果 —— 否则子行一出现就是完成态，永远看不到扫光。
     */
    private fun announce(name: String, rawArgs: String, id: String) {
        val sub = SubCall(
            name = name,
            args = rawArgs,
            ok = true,
            result = "",
            durationMs = 0,
            id = id,
            running = true,
        )
        open[id] = OpenCall(sub, System.currentTimeMillis())
        runCatching { context.onSubCallStart?.invoke(sub) }
    }

    /**
     * 结算一条子调用：界面（onSubCall）与这一轮的工具行落库（SubCallTrace）都读它。
     *
     * **只结算一次**：已经按 abort 结算过的 id 不在这里、也不再写（超时之后才跑完的工具不能把
     * 失败行覆盖成成功）；同一次调用晚到的第二个结果也直接丢掉。
     */
    private fun record(entry: SubCall) {
        val registered = synchronized(open) { open.remove(entry.id) }
        if (registered == null) return
        settle(entry)
    }

    private fun settle(entry: SubCall) {
        settled += entry
        runCatching { context.onSubCall?.invoke(entry) }
        SubCallTrace.record(listOf(entry))
    }

    /**
     * run_code 结束时把**还没结算**的子调用按 dsh 的 abort 语义结算：失败、结束态、正文取
     * dsh 的 toolAbortedResult 原文 tool call aborted。
     *
     * 调用点必须在父工具行的 tool/result **之前**（见 RunCodeTool）：界面先看到子行收尾，
     * 再看到 run_code 那一行报错，顺序与 dsh 的「先 drain dispatch 再收 run_code」一致。
     */
    fun abortOpenCalls() {
        val pending = synchronized(open) {
            val snapshot = open.values.toList()
            open.clear()
            snapshot
        }
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        pending.forEach { call ->
            settle(abortedSubCall(call.sub, startedAt = call.startedAt, now = now))
        }
    }

    /**
     * 一次 `await tools.x()`：跑一个工具，返回给程序的 wire 信封。
     *
     * **suspend 的**：宿主把它 launch 在一个
     * 单线程派发器上 —— 每个调用**先按到达顺序登记调度位**（[com.adsh.app.core.tools.ToolConcurrency]），
     * 然后**立刻让出那根线程**；真正跑工具的那段挪到 `Dispatchers.IO`。这样：
     *  - 读类调用各自在自己的 IO 线程上跑，真正重叠（不是「一次批量 RPC」才重叠）；
     *  - 写类调用仍然独占、顺序 = 提交顺序（调度位是按到达顺序登记的）；
     *  - 派发线程永远不被工具挡住，后面到的调用照样能马上登记。
     */
    suspend fun call(name: String, rawArgs: String): String {
        val id = nextSubId()
        announce(name, rawArgs, id)
        val started = System.currentTimeMillis()
        val outcome = try {
            concurrency.configure(context.maxParallelSubCalls)
            watchCancel {
                concurrency.withToolPermit(name) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        callToolSuspend(name, rawArgs)
                    }
                }
            }
        } catch (cancelSignal: kotlinx.coroutines.CancellationException) {
            interruptedOutcome(name, rawArgs)
        }
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



    /** 一次工具调用的结果：wire 给程序、text 给轨迹与日志、images 给轨迹与模型 */
    private data class CallOutcome(
        val wire: String,
        val text: String,
        val ok: Boolean,
        val images: List<com.adsh.app.seam.tool.ToolImage> = emptyList(),
        val deliverables: List<com.adsh.app.seam.tool.PresentedFile> = emptyList(),
    )

    /** 被用户中断的子调用：给程序一个「已停止」的失败信封（dsh 的 code = interrupted） */
    private fun interruptedOutcome(name: String, rawArgs: String): CallOutcome = CallOutcome(
        wire = toolErrorWire(
            name,
            rawArgs,
            ABORTED_SUB_CALL_MESSAGE,
            retryable = false,
            code = INTERRUPTED_CODE,
        ),
        text = ABORTED_SUB_CALL_MESSAGE,
        ok = false,
    )

    /**
     * 中断看门狗：探针命中就把这一段协程作用域取消掉。
     *
     * 子调用跑在宿主自己的协程里（不是外层那一轮协程的子节点），所以外层的取消**传不进来**
     * —— 没有这个看门狗，程序里一个 120s 的 bash 就让「停止」等两分钟。看门狗每
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
            is ToolResult.Ok -> {
                val wire = result.value?.toString()
                    ?: kotlinx.serialization.json.JsonPrimitive(result.text).toString()
                // **设备侧上限**（有意偏离 dsh，理由写在下面）：这个值要跨进程交给 :ptc 里的程序，
                // 而它前后会在主进程里留好几份（value 树 / toString / 帧 JSON / 帧字节）。
                // 手机主进程的 Java 堆是 256MB —— 真机实测：一个 5MB 单行文件的 grep
                // 值就把堆顶到 growth limit、app 闪退（同一份程序在 dsh 桌面端跑得动，V8 的堆大得多）。
                // dsh 对等的上限是控制帧 128MiB（maxMessageBytes），这里按 read 的整文件上限取同量级。
                // 写类工具（write / edit）已经把回显截到 MAX_ECHO_BYTES，撞不到这条；还剩 read /
                // grep / glob 这些只读工具 —— 它们没有写副作用，同样的调用缩小范围后重试是安全的。
                if (wire.toByteArray(Charsets.UTF_8).size > PTC_MAX_VALUE_BYTES) {
                    return failure(
                        "tool result for " + name + " is " + wire.toByteArray(Charsets.UTF_8).size +
                            " bytes, over the " + PTC_MAX_VALUE_BYTES +
                            "-byte cap this device can hand to a PTC program; narrow the request " +
                            "(read with offset/limit, or grep a smaller path) or process the file with bash",
                    )
                }
                CallOutcome(
                    wire = wire,
                    text = com.adsh.app.core.tools.SecretRedaction.redact(result.text),
                    ok = true,
                    images = result.images,
                    deliverables = result.deliverables,
                )
            }
            is ToolResult.Error -> failure(result.message, result.retryable, result.code)
        }
    }

    private companion object {
        /** 中断看门狗的轮询粒度：正在跑的子调用最多迟这么久发现「用户停了」 */
        const val CANCEL_PROBE_MS = 50L

        /**
         * 一次子调用的值交给 PTC 程序的上限（4 MiB）。dsh 没有这条（它的对等物是 128MiB 的控制帧
         * 上限，桌面 V8 扛得住）；手机主进程 256MB 的 Java 堆扛不住。
         * 正常工具结果（bash 64KB、read 50KB、grep 预览）离它很远，只有「一条几 MB 的单行」
         * 这类病态输入会碰到。
         */
        private const val PTC_MAX_VALUE_BYTES = 4 * 1024 * 1024
    }
}

/** dsh 的 toolAbortedResult 正文：取消 / 超时导致子调用结算时，界面摘要取这一行原文 */
internal const val ABORTED_SUB_CALL_MESSAGE = "tool call aborted"

/** 一次「按 abort 结算」的子调用：结束态、失败、没有结果正文（纯函数，单测直接打） */
internal fun abortedSubCall(start: SubCall, startedAt: Long, now: Long): SubCall = start.copy(
    ok = false,
    result = ABORTED_SUB_CALL_MESSAGE,
    durationMs = (now - startedAt).coerceAtLeast(0),
    running = false,
    images = emptyList(),
    deliverables = emptyList(),
)
