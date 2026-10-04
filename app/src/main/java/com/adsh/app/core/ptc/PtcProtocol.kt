package com.adsh.app.core.ptc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `:ptc` 进程与主进程之间的**帧词汇表**与**到点判据**（第 96 轮建，第 184 轮换通道）。
 *
 * 帧走 [PtcChannel]（4 字节大端长度 + UTF-8 JSON 的字节流），形状与 dsh 的
 * `dsh-ptc-runtime-node` 一一对应：
 *
 * | 帧 | 方向 | dsh 对应 |
 * |---|---|---|
 * | [TYPE_READY] | worker → 宿主 | 子进程起来后的第一帧（`process.js`：`channel.send({type:"ready"})`）；ADSH 多带一个 `pid` |
 * | [TYPE_BOOT] | 宿主 → worker | `{type:"boot", data}`：程序正文、工具名、时间预算 |
 * | [TYPE_CALL] | worker → 宿主 | `{type:"call", id, global, name, args}`：一次 `await tools.x()` |
 * | [TYPE_REPLY] | 宿主 → worker | `{type:"reply", id, ok, value}`：那一次调用的信封 |
 * | [TYPE_DONE] | worker → 宿主 | `{type:"done", value}` 或 `{type:"done", error:{kind,message}}` |
 *
 * ADSH 唯一的结构差异：`pid` 是安卓特有的（没有「spawn 返回 pid」，宿主要靠它才能在到点时
 * 杀进程）。第 186 轮起 `call` 与 dsh 逐字同形 —— **每次 `tools.x()` 一帧、各自异步**，
 * 不再有批量标记（`Promise.all` 就是原生语义，见 [QuickJsRuntime] 的 PREAMBLE）。
 */
internal object PtcProtocol {

    const val TYPE = "type"
    const val TYPE_READY = "ready"
    const val TYPE_BOOT = "boot"
    const val TYPE_CALL = "call"
    const val TYPE_REPLY = "reply"
    const val TYPE_DONE = "done"

    /** worker → 宿主：一行程序输出（dsh 的 `{type:"log", text}`，边打印边送） */
    const val TYPE_LOG = "log"
    const val FIELD_TEXT = "text"

    const val FIELD_PID = "pid"
    const val FIELD_PROGRAM = "program"
    const val FIELD_TOOLS = "tools"
    const val FIELD_TIMEOUT_MS = "timeoutMs"
    const val FIELD_ID = "id"
    const val FIELD_NAME = "name"
    const val FIELD_ARGS = "args"
    const val FIELD_WIRE = "wire"
    const val FIELD_VALUE = "value"
    const val FIELD_ERROR = "error"
    const val FIELD_KIND = "kind"
    const val FIELD_MESSAGE = "message"
    const val FIELD_LOGS = "logs"
    const val FIELD_DURATION_MS = "durationMs"
    const val FIELD_TOOL_CALLS = "toolCalls"

    /** 通道在程序结算之前断掉 —— dsh `JsonChannel.onEnd` 的原文 */
    const val CHANNEL_ENDED = "control channel ended before the program settled"

    /** 心跳粒度：到点判定的最坏延迟（也要够小，「停止」才跟手） */
    const val TICK_MS = 100L

    fun typeOf(frame: JsonObject?): String? = text(frame, TYPE)

    /** 取字符串字段：缺字段 / JSON null / 不是标量都算没有（帧来自另一个进程，不能假定形状） */
    fun text(frame: JsonObject?, key: String): String? =
        frame?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    fun long(frame: JsonObject?, key: String): Long? = text(frame, key)?.toLongOrNull()

    fun int(frame: JsonObject?, key: String): Int? = text(frame, key)?.toIntOrNull()

    fun strings(frame: JsonObject?, key: String): List<String> =
        (frame?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

/** 一次 `:ptc` 执行的收尾方式 */
internal sealed interface PtcWaitOutcome {
    /** worker 把结果送回来了 */
    data object Finished : PtcWaitOutcome

    /** 到点了：**已经杀掉 worker 进程** */
    data object TimedOut : PtcWaitOutcome

    /** 用户按了停止：**已经杀掉 worker 进程** */
    data object Cancelled : PtcWaitOutcome

    /** worker 自己没了（崩了 / 被系统杀了 / 被杀之后确认死亡） */
    data object WorkerExit : PtcWaitOutcome
}

/**
 * 等一次 `:ptc` 执行收尾 —— **纯调度**，桌面上可测（见 PtcDeadlineTest）。
 *
 * 与 dsh 的对应关系：dsh 在 `setTimeout(spec.timeoutMs)` 里 `controller.abort(...)` 再
 * `handle.terminate()`；这里同一个循环里判四件事：结果回来了没有、worker 还在不在、用户停没停、
 * 到点没有。到点/停止都调 [kill]（主进程杀 `:ptc` 进程）—— 这是**唯一**能真正打断
 * QuickJS 同步死循环的手段（第 183 轮真机事故，见 [QuickJsRuntime]）。
 *
 * @param finished worker 是否已把结果交回来（非阻塞地看一眼）
 * @param cancelRequested 用户是否按了停止（每 tick 问一次）
 * @param workerAlive worker 进程是否还活着（通道断了 / 已经确认死亡 = false）
 * @param kill 杀 worker 进程（`Process.killProcess(pid)`）
 */
internal fun awaitPtcWorker(
    timeoutMs: Long,
    startedAt: Long,
    tickMs: Long = PtcProtocol.TICK_MS,
    now: () -> Long = System::currentTimeMillis,
    finished: () -> Boolean,
    cancelRequested: () -> Boolean,
    workerAlive: () -> Boolean = { true },
    kill: () -> Unit,
): PtcWaitOutcome {
    while (true) {
        if (finished()) return PtcWaitOutcome.Finished
        if (!workerAlive()) return PtcWaitOutcome.WorkerExit
        if (cancelRequested()) {
            kill()
            return PtcWaitOutcome.Cancelled
        }
        if (now() - startedAt >= timeoutMs) {
            kill()
            return PtcWaitOutcome.TimedOut
        }
        Thread.sleep(tickMs)
    }
}
