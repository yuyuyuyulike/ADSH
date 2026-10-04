package com.adsh.app.core.ptc

/**
 * `:ptc` 进程与主进程之间的协议（第 183 轮）。
 *
 * 为什么要有这一段：dsh 的 `dsh-ptc-runtime-node` 把程序放进**独立子进程**跑，父进程通过
 * 管道 RPC 处理程序里的 `tools.x()`，并在 `setTimeout(timeoutMs)` 到点后
 * `handle.terminate()` **杀掉子进程**。ADSH 用安卓自己的进程模型做同一件事：
 * 程序跑在 `android:process=":ptc"` 的 [PtcWorkerService] 里，工具执行留在主进程
 * （[PtcToolRunner]），两边用 [android.os.Messenger] 传消息。到点由主进程
 * `Process.killProcess(workerPid)` —— 同步死循环当场结束，**这是 dsh 的 terminate 的等价物**。
 */
internal object PtcProtocol {

    /** 宿主 → worker：跑一段程序（`replyTo` = 宿主的 Messenger） */
    const val MSG_RUN = 1

    /** worker → 宿主：我的 pid（宿主要用它来杀进程；收到 RUN 后第一件事就是发它） */
    const val MSG_PID = 2

    /** worker → 宿主：执行一次 `await tools.x()`，**等宿主回信封**（同步 RPC） */
    const val MSG_CALL = 3

    /** worker → 宿主：执行一次 `Promise.all([...])` */
    const val MSG_CALL_ALL = 4

    /** 宿主 → worker：信封（对应上面的请求 id） */
    const val MSG_RESULT = 5

    /** worker → 宿主：程序跑完了 */
    const val MSG_DONE = 6

    const val KEY_REQUEST_ID = "requestId"
    const val KEY_PROGRAM = "program"
    const val KEY_TOOL_NAMES = "toolNames"
    const val KEY_TIMEOUT_MS = "timeoutMs"
    const val KEY_NAME = "name"
    const val KEY_ARGS = "args"
    const val KEY_PAYLOAD = "payload"
    const val KEY_WIRE = "wire"
    const val KEY_PID = "pid"
    const val KEY_VALUE_JSON = "valueJson"
    const val KEY_LOGS = "logs"
    const val KEY_ERROR = "error"
    const val KEY_DURATION_MS = "durationMs"
    const val KEY_TOOL_CALLS = "toolCalls"
    const val KEY_FAILURE_KIND = "failureKind"

    /** 心跳粒度：到点判定的最坏延迟（也要够小，「停止」才跟手） */
    const val TICK_MS = 100L
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
 * `handle.terminate()`；这里同一个循环里判三件事：结果回来了没有、到点没有、用户停没停。
 * 到点/停止都调 [kill]（主进程杀 `:ptc` 进程）—— 这是**唯一**能真正打断同步死循环的手段。
 *
 * @param finished worker 是否已把结果交回来（非阻塞地看一眼）
 * @param cancelRequested 用户是否按了停止（每 tick 问一次）
 * @param workerAlive worker 进程是否还活着（binder 断了 / 已经确认死亡 = false）
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
