package com.adsh.app.core.ptc

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * PTC 程序的宿主进程（`android:process=":ptc"`）：**每次调用一个新进程**，到点由主进程杀掉
 * —— 这正是 dsh 的 `dsh-ptc-runtime-node` 的形状（每个 run 都 spawn 一个 Node 进程，
 * 到点 `handle.terminate()`）。程序里的同步死循环因此最多烧死这个进程。
 *
 * 两条线程的分工（少了哪条都会死锁）：
 *  - **主线程（本进程的 Looper）**：收宿主的消息。`MSG_RESULT` 必须在这里被派发 ——
 *    引擎线程那时正阻塞在 `SynchronousQueue.take()` 上等信封，它自己的 Looper 是转不起来的；
 *  - **引擎线程**（显式 2MB 栈）：跑 QuickJS。栈必须明显大于 QuickJS 自己记账的 256KB，
 *    否则递归会先吃穿真实栈变成 SIGSEGV（第 120 轮真机教训，见 [QuickJsRuntime.run] 的注释）。
 */
class PtcWorkerService : Service() {

    private val pending = ConcurrentHashMap<Int, SynchronousQueue<String>>()
    private val requestSeq = AtomicInteger(0)

    @Volatile
    private var host: Messenger? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                PtcProtocol.MSG_RUN -> startRun(msg)
                PtcProtocol.MSG_RESULT -> {
                    val id = msg.data.getInt(PtcProtocol.KEY_REQUEST_ID)
                    // 宿主回信封：把等在那儿的引擎线程放行
                    pending.remove(id)?.put(msg.data.getString(PtcProtocol.KEY_WIRE) ?: "null")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    private fun startRun(msg: Message) {
        val replyTo = msg.replyTo ?: return
        host = replyTo
        val data = msg.data
        val program = data.getString(PtcProtocol.KEY_PROGRAM) ?: return
        val names = data.getStringArrayList(PtcProtocol.KEY_TOOL_NAMES).orEmpty()
        val timeoutMs = data.getLong(PtcProtocol.KEY_TIMEOUT_MS)
        // 宿主先知道我们的 pid：到点它要杀的就是这个进程（同步死循环没有别的手段能停）
        replyTo.send(
            Message.obtain(null, PtcProtocol.MSG_PID).apply {
                setData(Bundle().apply { putInt(PtcProtocol.KEY_PID, Process.myPid()) })
            },
        )
        Thread(
            null,
            {
                // QuickJS 包装库要求**调用线程有 Looper**（QuickJSContext.create 里要拿它做对象回收），
                // 少了这一句就是 "the main-thread Looper the runtime expects is missing"：
                // 程序一行都没跑，run_code 每次都以 worker-exit 失败（第 183 轮真机实测）。
                Looper.prepare()
                runProgram(program, names, timeoutMs)
            },
            ENGINE_THREAD_NAME,
            ENGINE_STACK_BYTES,
        ).apply { isDaemon = true }.start()
    }

    private fun runProgram(program: String, names: List<String>, timeoutMs: Long) {
        val result = try {
            QuickJsRuntime().run(
                program = program,
                toolNames = names,
                invoke = { name, args -> callHost(PtcProtocol.MSG_CALL, name, args) },
                invokeAll = { payload -> callHost(PtcProtocol.MSG_CALL_ALL, payload) },
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
        val bundle = Bundle().apply {
            putString(PtcProtocol.KEY_VALUE_JSON, result.valueJson)
            putStringArrayList(PtcProtocol.KEY_LOGS, ArrayList(result.logs))
            putString(PtcProtocol.KEY_ERROR, result.error)
            putLong(PtcProtocol.KEY_DURATION_MS, result.durationMs)
            putInt(PtcProtocol.KEY_TOOL_CALLS, result.toolCalls)
            putString(PtcProtocol.KEY_FAILURE_KIND, result.failureKind)
        }
        runCatching { host?.send(Message.obtain(null, PtcProtocol.MSG_DONE).apply { setData(bundle) }) }
    }

    /** 同步 RPC：把一次 `tools.x()` 交给宿主跑，拿到信封再继续（引擎线程在这里阻塞） */
    private fun callHost(what: Int, name: String, args: String): String {
        val id = requestSeq.incrementAndGet()
        val queue = SynchronousQueue<String>()
        pending[id] = queue
        val bundle = Bundle().apply {
            putInt(PtcProtocol.KEY_REQUEST_ID, id)
            putString(PtcProtocol.KEY_NAME, name)
            putString(PtcProtocol.KEY_ARGS, args)
        }
        host?.send(Message.obtain(null, what).apply { setData(bundle) })
        return queue.take()
    }

    private fun callHost(what: Int, payload: String): String {
        val id = requestSeq.incrementAndGet()
        val queue = SynchronousQueue<String>()
        pending[id] = queue
        val bundle = Bundle().apply {
            putInt(PtcProtocol.KEY_REQUEST_ID, id)
            putString(PtcProtocol.KEY_PAYLOAD, payload)
        }
        host?.send(Message.obtain(null, what).apply { setData(bundle) })
        return queue.take()
    }

    private companion object {
        const val ENGINE_THREAD_NAME = "adsh-ptc-engine"

        /** 引擎线程栈：必须远大于 QuickJS 自记账的 256KB（安卓默认约 1MB，这里给 2MB） */
        const val ENGINE_STACK_BYTES = 2L * 1024 * 1024
    }
}
