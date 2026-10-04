package com.adsh.app.core.ptc

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * PTC 程序的宿主进程（`android:process=":ptc"`）：**每次调用一个新进程**，到点由主进程杀掉
 * —— 这正是 dsh 的 `dsh-ptc-runtime-node` 的形状（每个 run 都 spawn 一个 Node 进程，
 * 到点 `handle.terminate()`）。程序里的同步死循环因此最多烧死这个进程。
 *
 * 两条线程的分工（少了哪条都会死锁）：
 *  - **收帧线程**：读宿主的 `boot` 与每一条 `reply`，把信封交给等在那儿的引擎线程；
 *  - **引擎线程**（显式 2MB 栈）：跑 QuickJS。栈必须明显大于 QuickJS 自己记账的 256KB，
 *    否则递归会先吃穿真实栈变成 SIGSEGV（第 120 轮真机教训，见 [QuickJsRuntime.run] 的注释）。
 *
 * 第 184 轮换通道：与宿主的联系从 `Messenger` 换成 `LocalSocket`（4 字节大端长度 +
 * JSON 帧，见 [PtcChannel]）。**Binder 只用来绑定** —— `onBind` 返回一个空 [Binder]，通道名
 * 走 Intent 的 extra；业务载荷一个字节都不走 Binder，于是没有「一次事务约 1MB」这回事。
 */
class PtcWorkerService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL)
        if (channelName == null) {
            Log.w(TAG, "绑定没带通道名，worker 不启动")
            return Binder()
        }
        Thread({ Worker(channelName).serve() }, WORKER_THREAD_NAME).apply { isDaemon = true }.start()
        return Binder()
    }

    /** 一次绑定的会话：连通道 → 收 boot → 跑程序 → 回信封 */
    private inner class Worker(private val channelName: String) {

        /** 等宿主信封的引擎线程：id → 只放一个回复的队列 */
        private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<String>>()
        private val requestSeq = AtomicInteger(0)

        fun serve() {
            var channel: PtcChannel? = null
            try {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(channelName, LocalSocketAddress.Namespace.ABSTRACT))
                channel = PtcChannel(socket.inputStream, socket.outputStream)
                // dsh 的子进程起来第一帧就是 {type:"ready"}（process.js）；ADSH 多带一个 pid：
                // 安卓没有「spawn 返回 pid」，宿主要靠它才能在到点时杀进程
                channel.send(
                    buildJsonObject {
                        put(PtcProtocol.TYPE, PtcProtocol.TYPE_READY)
                        put(PtcProtocol.FIELD_PID, Process.myPid())
                    },
                )
                val boot = channel.receive() ?: return
                if (PtcProtocol.typeOf(boot) != PtcProtocol.TYPE_BOOT) return
                startEngine(channel, boot)
                while (true) {
                    val frame = channel.receive() ?: break
                    if (!deliver(frame)) break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "PTC 通道结束", t)
            } finally {
                wakePending()
                runCatching { channel?.close() }
            }
        }

        /** 宿主回的 reply：把等在那儿的引擎线程放行 */
        private fun deliver(frame: JsonObject): Boolean {
            if (PtcProtocol.typeOf(frame) != PtcProtocol.TYPE_REPLY) return false
            val id = PtcProtocol.int(frame, PtcProtocol.FIELD_ID) ?: return false
            pending.remove(id)?.offer(PtcProtocol.text(frame, PtcProtocol.FIELD_WIRE) ?: "null")
            return true
        }

        private fun startEngine(channel: PtcChannel, boot: JsonObject) {
            val program = PtcProtocol.text(boot, PtcProtocol.FIELD_PROGRAM) ?: return
            val names = PtcProtocol.strings(boot, PtcProtocol.FIELD_TOOLS)
            val timeoutMs = PtcProtocol.long(boot, PtcProtocol.FIELD_TIMEOUT_MS)
                ?: QuickJsRuntime.DEFAULT_TIMEOUT_MS
            Thread(
                null,
                { runProgram(channel, program, names, timeoutMs) },
                ENGINE_THREAD_NAME,
                ENGINE_STACK_BYTES,
            ).apply { isDaemon = true }.start()
        }

        private fun runProgram(channel: PtcChannel, program: String, names: List<String>, timeoutMs: Long) {
            // QuickJS 包装库要求**调用线程有 Looper**（QuickJSContext.create 里要拿它做对象回收），
            // 少了这一句就是 "the main-thread Looper the runtime expects is missing"：
            // 程序一行都没跑，run_code 每次都以 worker-exit 失败（第 183 轮真机实测）。
            Looper.prepare()
            val result = try {
                QuickJsRuntime().run(
                    program = program,
                    toolNames = names,
                    invoke = { name, args -> callHost(channel, false, name, args) },
                    invokeAll = { payload -> callHost(channel, true, "", payload) },
                    timeoutMs = timeoutMs,
                )
            } catch (t: Throwable) {
                CodeRunResult(
                    valueJson = null,
                    logs = emptyList(),
                    error = t.message ?: t::class.java.simpleName,
                    durationMs = 0,
                    failureKind = "exception",
                )
            }
            // 收尾那一帧必须是 done（dsh 的 terminalSent）：发不出去说明宿主已经走了，直接结束
            runCatching { channel.send(doneFrame(result)) }
        }

        /** dsh 的 done 帧：value 与 error 二选一；ADSH 把日志也放在这一帧里（dsh 用 log 帧流式送） */
        private fun doneFrame(result: CodeRunResult): JsonObject = buildJsonObject {
            put(PtcProtocol.TYPE, PtcProtocol.TYPE_DONE)
            result.valueJson?.let { put(PtcProtocol.FIELD_VALUE, it) }
            result.error?.let { message ->
                put(
                    PtcProtocol.FIELD_ERROR,
                    buildJsonObject {
                        put(PtcProtocol.FIELD_KIND, result.failureKind ?: "exception")
                        put(PtcProtocol.FIELD_MESSAGE, message)
                    },
                )
            }
            put(PtcProtocol.FIELD_LOGS, buildJsonArray { result.logs.forEach { add(it) } })
            put(PtcProtocol.FIELD_DURATION_MS, result.durationMs)
            put(PtcProtocol.FIELD_TOOL_CALLS, result.toolCalls)
        }

        /** 同步 RPC：把一次 tools.x() 交给宿主跑，拿到信封再继续（引擎线程在这里阻塞） */
        private fun callHost(channel: PtcChannel, all: Boolean, name: String, args: String): String {
            val id = requestSeq.incrementAndGet()
            val queue = ArrayBlockingQueue<String>(1)
            pending[id] = queue
            val frame = buildJsonObject {
                put(PtcProtocol.TYPE, PtcProtocol.TYPE_CALL)
                put(PtcProtocol.FIELD_ID, id)
                put(PtcProtocol.FIELD_ALL, all)
                put(PtcProtocol.FIELD_NAME, name)
                put(PtcProtocol.FIELD_ARGS, args)
            }
            try {
                channel.send(frame)
            } catch (t: Throwable) {
                pending.remove(id)
                return toolErrorWire(name, args, PtcProtocol.CHANNEL_ENDED)
            }
            return try {
                queue.take()
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                toolErrorWire(name, args, PtcProtocol.CHANNEL_ENDED)
            }
        }

        /** 通道断了：把还等着的引擎线程全部放行（否则它会一直挂在 take() 上） */
        private fun wakePending() {
            val wake = toolErrorWire("", "", PtcProtocol.CHANNEL_ENDED)
            pending.values.forEach { it.offer(wake) }
            pending.clear()
        }
    }

    companion object {
        /** 宿主 → worker 的通道名（抽象命名空间），走绑定的 Intent extra */
        internal const val EXTRA_CHANNEL = "adsh.ptc.channel"

        private const val TAG = "ADSH"
        private const val WORKER_THREAD_NAME = "adsh-ptc-worker"
        private const val ENGINE_THREAD_NAME = "adsh-ptc-engine"

        /** 引擎线程栈：必须远大于 QuickJS 自记账的 256KB（安卓默认约 1MB，这里给 2MB） */
        private const val ENGINE_STACK_BYTES = 2L * 1024 * 1024
    }
}
