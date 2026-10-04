package com.adsh.app.core.ptc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.os.Process
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 主进程这一侧的 `:ptc` 客户端：建通道 → 绑进程 → 发程序 → 处理程序里的 `tools.x()`
 * → **到点杀进程**。
 *
 * 与 dsh 的对照（`dsh-ptc-runtime-node/lib/index.js`）：
 * | dsh | 这里 |
 * |---|---|
 * | `subprocess.spawn(node, ...)`，每次调用一个新进程 | `bindService` 到 `:ptc`，跑完就杀（下次是新进程） |
 * | 父进程建 `control` 管道、子进程继承 fd | 宿主 `LocalServerSocket`，worker 按名字连过来（[PtcWorkerService.EXTRA_CHANNEL]） |
 * | `JsonChannel`：4 字节大端长度 + JSON 帧 | [PtcChannel]（同格式、同上限、同错误分类） |
 * | `setTimeout(spec.timeoutMs)` → `controller.abort` | [awaitPtcWorker] 的墙钟判据 |
 * | `handle.terminate()`（杀子进程） | `Process.killProcess(workerPid)` |
 * | failure kind `timeout` / `worker-exit` / `protocol` / `abort` | 同名同义（见 [CodeRunResult.failureKind]） |
 *
 * 为什么必须杀：QuickJS 进了同步死循环就再也不把执行权还给宿主，泵循环的超时判据与「停止」
 * 探针都没有机会执行 —— 只有进程级的终止能把它按停（第 183 轮真机事故，见 [QuickJsRuntime]）。
 *
 * 为什么不用 Binder 传载荷（第 184 轮真机事故）：见 [PtcChannel] 的 KDoc。工具执行仍然留在
 * **主进程**（[PtcToolRunner]，`ToolContext` 只在这里有效），所以通道两头分别是
 * 「主进程的 caller 线程」与「`:ptc` 里的引擎线程」。
 */
internal object PtcProcess {

    /** 同一时刻只允许一次执行：run_code 是写类工具，[com.adsh.app.core.tools.ToolConcurrency] 已经串行化 */
    private val lock = Any()

    /** 绑定等待上限：绑不上就是环境问题（进程起不来），照 worker-exit 报给模型 */
    private const val BIND_TIMEOUT_MS = 5_000L

    /** 通道名序号：抽象命名空间是全设备共享的，名字里带上 pid 与序号避免撞车 */
    private val channelSeq = AtomicInteger(0)

    fun run(
        context: Context,
        program: String,
        toolNames: List<String>,
        timeoutMs: Long,
        runner: PtcToolRunner,
        cancel: () -> Boolean,
    ): CodeRunResult = synchronized(lock) {
        val started = System.currentTimeMillis()
        val session = Session(context.applicationContext, runner, toolNames)
        try {
            session.bind()
            if (!session.ready.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return@run session.exit("PTC 进程没起来（绑定超时）")
            }
            session.start(program, timeoutMs)
            return@run when (awaitPtcWorker(
                timeoutMs = timeoutMs,
                startedAt = started,
                finished = { session.hasResult.get() },
                cancelRequested = cancel,
                workerAlive = { session.alive.get() },
                kill = { session.kill() },
            )) {
                PtcWaitOutcome.Finished -> session.result()
                // 到点被杀：**已经打印的内容照样带走**（dsh 的 log 帧是边打印边回的，见 PtcWorkerService）
                PtcWaitOutcome.TimedOut -> CodeRunResult(
                    valueJson = null,
                    logs = session.streamedLogs(),
                    // dsh 的失败信封逐字：kind = timeout、message = "execution deadline reached (Nms)"
                    error = "execution deadline reached (" + timeoutMs + "ms)",
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = "timeout",
                )
                PtcWaitOutcome.Cancelled -> CodeRunResult(
                    valueJson = null,
                    logs = session.streamedLogs(),
                    error = "已停止",
                    durationMs = System.currentTimeMillis() - started,
                    failureKind = "abort",
                    aborted = true,
                )
                // 通道断了（worker 崩了 / 被杀 / 帧不合法）：dsh 也走 worker-exit 与 protocol 这两支
                PtcWaitOutcome.WorkerExit -> session.exit("PTC 进程退出了（崩溃或被系统回收）")
            }
        } finally {
            // 每次调用一个新进程（dsh 的 fresh process）：跑完就杀 + 解绑，下次 bind 会重新 fork
            session.kill()
            session.close()
        }
    }

    /** 一次执行的会话：建通道、收帧、跑工具、收结果、杀进程 */
    private class Session(
        private val context: Context,
        private val runner: PtcToolRunner,
        private val toolNames: List<String>,
    ) {

        val ready = CountDownLatch(1)
        val hasResult = AtomicBoolean(false)
        val alive = AtomicBoolean(true)

        private val startedAt = System.currentTimeMillis()
        private val resultRef = AtomicReference<CodeRunResult?>(null)

        /**
         * worker 边跑边回的日志（dsh 的 log 帧）。收帧线程写、主流程读，所以是同步列表。
         * 到点被杀 / worker 崩了时，这份就是模型唯一拿得到的「已经打印了什么」。
         */
        private val liveLogs = java.util.Collections.synchronizedList(ArrayList<String>())
        private val pid = AtomicInteger(-1)
        private val channelName = "adsh.ptc." + Process.myPid() + "." + channelSeq.incrementAndGet()

        /**
         * 工具派发的**单线程**执行器（第 186 轮）：每次 `tools.x()` 在这里 launch 一个协程。
         *
         * 为什么必须是单线程：调度位要按**到达顺序**登记（dsh 的 submission order），而登记发生在
         * 协程开始跑的那一刻 —— 单线程保证 launch 顺序 = 开始顺序。工具真正跑起来之后由
         * [PtcToolRunner.call] 让出这根线程（工具体在 Dispatchers.IO 上），所以派发不会被挡住。
         */
        private val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "adsh-ptc-dispatch").apply { isDaemon = true }
        }
        private val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + caller.asCoroutineDispatcher(),
        )

        private val thread = Thread({ serve() }, "adsh-ptc-host").apply { isDaemon = true }

        @Volatile private var server: LocalServerSocket? = null
        @Volatile private var client: LocalSocket? = null
        @Volatile private var channel: PtcChannel? = null

        /** 通道断在哪一步：dsh 把「帧不合法」记 protocol，把「流断了」记 worker-exit */
        @Volatile private var endKind: String? = null
        @Volatile private var endMessage: String? = null

        /** 帧里的调用序号必须严格递增（dsh 的 raw.id !== nextId 判据）；只有收帧线程碰它 */
        private var expectedId = 0

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit

            override fun onServiceDisconnected(name: ComponentName?) {
                alive.set(false)
            }

            override fun onBindingDied(name: ComponentName?) {
                alive.set(false)
            }
        }

        fun bind() {
            val listening = try {
                LocalServerSocket(channelName)
            } catch (t: Throwable) {
                end("worker-exit", "PTC 控制通道建不起来：" + (t.message ?: t::class.java.simpleName))
                return
            }
            server = listening
            thread.start()
            // 同应用、非 exported 的服务：直接 bindService（不需要 startService）；通道名走 Intent 带过去
            context.bindService(
                Intent(context, PtcWorkerService::class.java)
                    .putExtra(PtcWorkerService.EXTRA_CHANNEL, channelName),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        /** worker 已经 ready（pid 到手）之后才发 boot：dsh 也是等 ready 再发 boot */
        fun start(program: String, timeoutMs: Long) {
            send(
                buildJsonObject {
                    put(PtcProtocol.TYPE, PtcProtocol.TYPE_BOOT)
                    put(PtcProtocol.FIELD_PROGRAM, program)
                    put(PtcProtocol.FIELD_TOOLS, buildJsonArray { toolNames.forEach { add(it) } })
                    put(PtcProtocol.FIELD_TIMEOUT_MS, timeoutMs)
                },
            )
        }

        /** 收帧循环：accept → ready → call/done，直到 done、流结束或协议错误 */
        private fun serve() {
            try {
                val accepted = server?.accept() ?: return
                client = accepted
                val established = PtcChannel(accepted.inputStream, accepted.outputStream)
                channel = established
                while (true) {
                    val frame = established.receive() ?: break
                    if (!dispatch(frame)) break
                }
                if (!hasResult.get()) end("worker-exit", PtcProtocol.CHANNEL_ENDED)
            } catch (t: Throwable) {
                if (!hasResult.get()) {
                    if (t is PtcProtocolException) end("protocol", t.message ?: "invalid control frame")
                    else end("worker-exit", PtcProtocol.CHANNEL_ENDED)
                }
            } finally {
                if (!hasResult.get()) alive.set(false)
            }
        }

        private fun dispatch(frame: JsonObject): Boolean = when (PtcProtocol.typeOf(frame)) {
            PtcProtocol.TYPE_READY -> {
                pid.set(PtcProtocol.int(frame, PtcProtocol.FIELD_PID) ?: -1)
                ready.countDown()
                true
            }
            PtcProtocol.TYPE_CALL -> {
                enqueue(frame)
                true
            }
            PtcProtocol.TYPE_LOG -> {
                PtcProtocol.text(frame, PtcProtocol.FIELD_TEXT)?.let { liveLogs += it }
                true
            }
            PtcProtocol.TYPE_DONE -> {
                resultRef.set(readResult(frame))
                hasResult.set(true)
                false
            }
            // dsh 的 protocolFailure("unknown control message")
            else -> throw PtcProtocolException("unknown control message")
        }

        private fun enqueue(frame: JsonObject) {
            val id = PtcProtocol.int(frame, PtcProtocol.FIELD_ID)
                ?: throw PtcProtocolException("invalid binding call identity")
            if (id != expectedId + 1) throw PtcProtocolException("invalid binding call identity")
            expectedId = id
            val name = PtcProtocol.text(frame, PtcProtocol.FIELD_NAME).orEmpty()
            val args = PtcProtocol.text(frame, PtcProtocol.FIELD_ARGS) ?: "{}"
            // dsh 的 protocolFailure("program requested an undeclared binding")
            if (toolNames.none { it == name }) {
                throw PtcProtocolException("program requested an undeclared binding")
            }
            // 每次调用各自一个协程（第 186 轮）：闸门（ToolConcurrency）决定谁与谁重叠，
            // 单线程派发器保证「谁先到谁先登记」= dsh 的 submission order
            scope.launch {
                val wire = try {
                    runner.call(name, args)
                } catch (t: Throwable) {
                    // 工具侧的异常不能把主进程带走（第 184 轮之前那条 Binder 路径上它是 FATAL）
                    toolErrorWire(name, args, t.message ?: t::class.java.simpleName)
                }
                send(
                    buildJsonObject {
                        put(PtcProtocol.TYPE, PtcProtocol.TYPE_REPLY)
                        put(PtcProtocol.FIELD_ID, id)
                        put(PtcProtocol.FIELD_WIRE, wire)
                    },
                )
            }
        }
        private fun send(frame: JsonObject) {
            try {
                channel?.send(frame)
            } catch (t: Throwable) {
                end("worker-exit", PtcProtocol.CHANNEL_ENDED)
                alive.set(false)
            }
        }

        /** dsh 的 done 帧：`{type:"done", value}` 或 `{type:"done", error:{kind,message}}` */
        private fun readResult(frame: JsonObject): CodeRunResult {
            val error = frame[PtcProtocol.FIELD_ERROR] as? JsonObject
            return CodeRunResult(
                valueJson = PtcProtocol.text(frame, PtcProtocol.FIELD_VALUE),
                // done 帧带的是完整列表；它缺席（或为空）时用边跑边收的那份
                logs = PtcProtocol.strings(frame, PtcProtocol.FIELD_LOGS).ifEmpty { streamedLogs() },
                error = PtcProtocol.text(error, PtcProtocol.FIELD_MESSAGE),
                durationMs = PtcProtocol.long(frame, PtcProtocol.FIELD_DURATION_MS)
                    ?: (System.currentTimeMillis() - startedAt),
                toolCalls = PtcProtocol.int(frame, PtcProtocol.FIELD_TOOL_CALLS) ?: runner.toolCalls(),
                failureKind = PtcProtocol.text(error, PtcProtocol.FIELD_KIND),
            )
        }

        /** 阻塞到结果：调用方（[awaitPtcWorker]）已经确认它回来了，这里只是取出来 */
        fun result(): CodeRunResult = resultRef.get() ?: exit("PTC 结果没回来")

        /** 已经打印出来的那些行（dsh 的 captured output：失败信封里也带上） */
        fun streamedLogs(): List<String> = synchronized(liveLogs) { liveLogs.toList() }

        /** 通道没给出结果时的收尾信封：kind 取通道断掉的方式，message 取 dsh 的原文 */
        fun exit(fallback: String): CodeRunResult {
            val kind = endKind ?: "worker-exit"
            return CodeRunResult(
                valueJson = null,
                logs = streamedLogs(),
                error = endMessage ?: fallback,
                durationMs = System.currentTimeMillis() - startedAt,
                failureKind = kind,
            )
        }

        private fun end(kind: String, message: String) {
            if (endKind == null) {
                endKind = kind
                endMessage = message
            }
        }

        /** dsh 的 handle.terminate()：同一个 uid，直接杀我们自己的 `:ptc` 进程 */
        fun kill() {
            val target = pid.get()
            if (target > 0) {
                alive.set(false)
                runCatching { Process.killProcess(target) }
            }
        }

        fun close() {
            runCatching { context.unbindService(connection) }
            runCatching { channel?.close() }
            runCatching { client?.close() }
            runCatching { server?.close() }
            // 只 shutdown，不 interrupt（第 186 轮）：不等 await 的调用在 dsh 里也照样跑完，
            // 结果丢掉而已 —— interrupt 会把一次已经发出去的写操作掐断在半路
            caller.shutdown()
        }
    }
}
