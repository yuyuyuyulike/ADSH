package com.adsh.app.core.ptc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 主进程这一侧的 `:ptc` 客户端：绑进程 → 发程序 → 处理程序里的 `tools.x()` → **到点杀进程**。
 *
 * 与 dsh 的对照（`dsh-ptc-runtime-node/lib/index.js`）：
 * | dsh | 这里 |
 * |---|---|
 * | `subprocess.spawn(node, ...)`，每次调用一个新进程 | `bindService` 到 `:ptc`，跑完就杀（下次是新进程） |
 * | `setTimeout(spec.timeoutMs)` → `controller.abort` | [awaitPtcWorker] 的墙钟判据 |
 * | `handle.terminate()`（SIGTERM/KILL 子进程） | `Process.killProcess(workerPid)` |
 * | failure kind `timeout` / `worker-exit` / `abort` | 同名同义（见 [CodeRunResult.failureKind]） |
 *
 * 为什么必须杀：QuickJS 进了同步死循环就再也不把执行权还给宿主，泵循环的超时判据与「停止」
 * 探针都没有机会执行 —— 只有进程级的终止能把它按停（第 183 轮真机事故，见 [QuickJsRuntime]）。
 */
internal object PtcProcess {

    /** 同一时刻只允许一次执行：run_code 是写类工具，[com.adsh.app.core.tools.ToolConcurrency] 已经串行化 */
    private val lock = Any()

    /** 绑定等待上限：绑不上就是环境问题（进程起不来），照 worker-exit 报给模型 */
    private const val BIND_TIMEOUT_MS = 5_000L

    fun run(
        context: Context,
        program: String,
        toolNames: List<String>,
        timeoutMs: Long,
        runner: PtcToolRunner,
        cancel: () -> Boolean,
    ): CodeRunResult = synchronized(lock) {
        val started = System.currentTimeMillis()
        val session = Session(context.applicationContext, runner)
        try {
            session.bind()
            if (!session.connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return@run CodeRunResult(
                    valueJson = null,
                    logs = emptyList(),
                    error = "worker-exit: PTC 进程没起来（绑定超时）",
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = "worker-exit",
                )
            }
            session.start(program, toolNames, timeoutMs)
            return@run when (
                val outcome = awaitPtcWorker(
                    timeoutMs = timeoutMs,
                    startedAt = started,
                    finished = { session.hasResult.get() },
                    cancelRequested = cancel,
                    workerAlive = { session.alive.get() },
                    kill = { session.kill() },
                )
            ) {
                PtcWaitOutcome.Finished -> session.result()
                PtcWaitOutcome.TimedOut -> CodeRunResult(
                    valueJson = null,
                    logs = emptyList(),
                    // dsh 的失败信封逐字：kind = timeout、message = "execution deadline reached (Nms)"
                    error = "execution deadline reached (" + timeoutMs + "ms)",
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = "timeout",
                )
                PtcWaitOutcome.Cancelled -> CodeRunResult(
                    valueJson = null,
                    logs = emptyList(),
                    error = "已停止",
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = "abort",
                    aborted = true,
                )
                PtcWaitOutcome.WorkerExit -> CodeRunResult(
                    valueJson = null,
                    logs = emptyList(),
                    error = "worker-exit: PTC 进程退出了（崩溃或被系统回收）",
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = "worker-exit",
                )
            }
        } finally {
            // 每次调用一个新进程（dsh 的 fresh process）：跑完就杀 + 解绑，下次 bind 会重新 fork
            session.kill()
            session.close()
        }
    }

    /** 一次执行的会话：绑定、消息分发、结果收集、杀进程 */
    private class Session(private val context: Context, private val runner: PtcToolRunner) {

        val connected = CountDownLatch(1)
        val hasResult = AtomicBoolean(false)
        val alive = AtomicBoolean(true)
        private val done = CountDownLatch(1)
        private val resultRef = AtomicReference<Bundle?>(null)
        private val pid = AtomicInteger(-1)
        private val thread = HandlerThread("adsh-ptc-host")

        private lateinit var worker: Messenger
        private lateinit var own: Messenger
        private lateinit var handler: Handler

        /**
         * 消息分发。**Handler 必须在 [HandlerThread.start] 之后才构造**：
         * `Handler(thread.looper)` 在 looper 还是 null 时构造，抛的就是
         * "Attempt to read from field 'MessageQueue Looper.mQueue' on a null object reference"
         * —— 第 183 轮真机实测：每次 run_code 都以这条 213 字符的 worker-exit 失败。
         */
        private fun onMessage(msg: Message) {
                when (msg.what) {
                    PtcProtocol.MSG_PID -> pid.set(msg.data.getInt(PtcProtocol.KEY_PID))
                    // 一次 await tools.x()：在**这条 IPC 线程**上同步跑工具（工具自己有超时与中断路径）
                    PtcProtocol.MSG_CALL -> {
                        val data = msg.data
                        val wire = runCatching {
                            runner.call(
                                data.getString(PtcProtocol.KEY_NAME).orEmpty(),
                                data.getString(PtcProtocol.KEY_ARGS) ?: "{}",
                            )
                        }.getOrElse { "{\"__error\":true,\"message\":\"host failed\"}" }
                        reply(data.getInt(PtcProtocol.KEY_REQUEST_ID), wire)
                    }
                    PtcProtocol.MSG_CALL_ALL -> {
                        val data = msg.data
                        val wire = runCatching {
                            runner.callAll(data.getString(PtcProtocol.KEY_PAYLOAD) ?: "[]")
                        }.getOrElse { "[]" }
                        reply(data.getInt(PtcProtocol.KEY_REQUEST_ID), wire)
                    }
                    PtcProtocol.MSG_DONE -> {
                        resultRef.set(msg.data)
                        hasResult.set(true)
                        done.countDown()
                    }
                }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                worker = Messenger(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                alive.set(false)
            }

            override fun onBindingDied(name: ComponentName?) {
                alive.set(false)
            }
        }

        fun bind() {
            thread.start()
            handler = object : Handler(thread.looper) {
                override fun handleMessage(msg: Message) = onMessage(msg)
            }
            own = Messenger(handler)
            // 同应用、非 exported 的服务：直接 bindService（不需要 startService）
            context.bindService(
                Intent(context, PtcWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        fun start(program: String, toolNames: List<String>, timeoutMs: Long) {
            val bundle = Bundle().apply {
                putString(PtcProtocol.KEY_PROGRAM, program)
                putStringArrayList(PtcProtocol.KEY_TOOL_NAMES, ArrayList(toolNames))
                putLong(PtcProtocol.KEY_TIMEOUT_MS, timeoutMs)
            }
            worker.send(Message.obtain(null, PtcProtocol.MSG_RUN).apply {
                setData(bundle)
                replyTo = own
            })
        }

        private fun reply(requestId: Int, wire: String) {
            val bundle = Bundle().apply {
                putInt(PtcProtocol.KEY_REQUEST_ID, requestId)
                putString(PtcProtocol.KEY_WIRE, wire)
            }
            worker.send(Message.obtain(null, PtcProtocol.MSG_RESULT).apply { setData(bundle) })
        }

        /** 阻塞到结果：调用方（[awaitPtcWorker]）已经确认它回来了，这里只是取出来 */
        fun result(): CodeRunResult {
            done.await(1, TimeUnit.SECONDS)
            val data = resultRef.get()
            return CodeRunResult(
                valueJson = data?.getString(PtcProtocol.KEY_VALUE_JSON),
                logs = data?.getStringArrayList(PtcProtocol.KEY_LOGS).orEmpty(),
                error = data?.getString(PtcProtocol.KEY_ERROR),
                durationMs = data?.getLong(PtcProtocol.KEY_DURATION_MS) ?: 0L,
                toolCalls = data?.getInt(PtcProtocol.KEY_TOOL_CALLS) ?: runner.toolCalls(),
                failureKind = data?.getString(PtcProtocol.KEY_FAILURE_KIND),
            )
        }

        /** dsh 的 `handle.terminate()`：同一个 uid，直接杀我们自己的 `:ptc` 进程 */
        fun kill() {
            val target = pid.get()
            if (target > 0) {
                alive.set(false)
                runCatching { Process.killProcess(target) }
            }
        }

        fun close() {
            runCatching { context.unbindService(connection) }
            runCatching { thread.quitSafely() }
        }
    }
}
