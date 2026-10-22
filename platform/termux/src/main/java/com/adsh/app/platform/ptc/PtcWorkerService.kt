package com.adsh.app.platform.ptc

import com.adsh.app.seam.ptc.CodeRunResult

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

/**
 * PTC 程序的宿主进程（`android:process=":ptc"`）：**每次调用一个新进程**，到点由主进程杀掉
 * —— 这正是 dsh 的 `dsh-ptc-runtime-node` 的形状（每个 run 都 spawn 一个 Node 进程，
 * 到点 `handle.terminate()`）。程序里的同步死循环因此最多烧死这个进程。
 *
 * 两条线程的分工（少了哪条都会死锁）：
 *  - **收帧线程**：读宿主的 `boot` 与每一条 `reply`，把信封交给等在那儿的引擎线程；
 *  - **引擎线程**（显式 2MB 栈）：跑 QuickJS。栈必须明显大于 QuickJS 自己记账的 256KB，
 *    否则递归会先吃穿真实栈变成 SIGSEGV（见 [QuickJsRuntime.run] 的注释）。
 *
 * 与宿主的联系走 `LocalSocket`（4 字节大端长度 + JSON 帧，见 [PtcChannel]），不再用 `Messenger`。
 * **Binder 只用来绑定** —— `onBind` 返回一个空 [Binder]，通道名
 * 走 Intent 的 extra；业务载荷一个字节都不走 Binder，于是没有「一次事务约 1MB」这回事。
 */
class PtcWorkerService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        val channelName = intent?.getStringExtra(EXTRA_CHANNEL)
        if (channelName == null) {
            Log.w(TAG, "bind intent carried no channel name; worker not started")
            return Binder()
        }
        Thread({ Worker(channelName).serve() }, WORKER_THREAD_NAME).apply { isDaemon = true }.start()
        return Binder()
    }

    /** 一次绑定的会话：连通道 → 收 boot → 跑程序 → 回信封 */
    private inner class Worker(private val channelName: String) {

        /** 已经到达、还没被泵循环取走的回执（id → wire）；收帧线程放、引擎线程取 */
        private val replies = ReplyQueue()

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
                Log.w(TAG, "PTC channel ended", t)
            } finally {
                runCatching { channel?.close() }
            }
        }

        /**
         * 宿主回的 reply：**只入队**，不叫醒谁。
         *
         * 回执进 replies，引擎线程的泵循环自己来取（takeReplies），谁都不等谁 ——
         * JS 线程绝不能在工具跑完之前阻塞；通道断了也不用「叫醒所有等待者」，因为没人等。
         */
        private fun deliver(frame: JsonObject): Boolean {
            if (PtcProtocol.typeOf(frame) != PtcProtocol.TYPE_REPLY) return false
            val id = PtcProtocol.int(frame, PtcProtocol.FIELD_ID) ?: return false
            replies.put(id, PtcProtocol.text(frame, PtcProtocol.FIELD_WIRE) ?: "null")
            return true
        }

        /** 已经到达、还没兑现的回执（引擎线程一次取走；没有时泵循环睡在 [ReplyQueue.await] 上） */
        private fun takeReplies(): List<Pair<Int, String>> = replies.drain()

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
            // 程序一行都没跑，run_code 每次都以 worker-exit 失败（真机实测）。
            Looper.prepare()
            val result = try {
                QuickJsRuntime().run(
                    program = program,
                    toolNames = names,
                    postCall = { id, name, args -> postCall(channel, id, name, args) },
                    takeReplies = { takeReplies() },
                    // 有在途调用时睡在队列上等回执，而不是每毫秒求值一次空表达式（见 QuickJsRuntime）
                    awaitReply = { timeoutMs -> replies.await(timeoutMs) },
                    timeoutMs = timeoutMs,
                    onLog = { line -> sendLog(channel, line) },
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

        /**
         * 一行程序输出**立刻**送回宿主（dsh 的 `{type:"log", text}`）：到点被杀时宿主手里
         * 已经有它了 —— 日志若只随 `done` 一帧回来，程序一被杀就全丢。
         */
        private fun sendLog(channel: PtcChannel, line: String) {
            runCatching {
                channel.send(
                    buildJsonObject {
                        put(PtcProtocol.TYPE, PtcProtocol.TYPE_LOG)
                        put(PtcProtocol.FIELD_TEXT, line)
                    },
                )
            }
        }

        /**
         * 发起一次 tools.x()：**只把调用帧写出去**（dsh 的子进程 postMessage 同形）。
         * 写不出去就把异常抛给 JS —— 那条 Promise 当场 reject，程序自己去 catch。
         */
        private fun postCall(channel: PtcChannel, id: Int, name: String, args: String) {
            channel.send(
                buildJsonObject {
                    put(PtcProtocol.TYPE, PtcProtocol.TYPE_CALL)
                    put(PtcProtocol.FIELD_ID, id)
                    put(PtcProtocol.FIELD_NAME, name)
                    put(PtcProtocol.FIELD_ARGS, args)
                },
            )
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
