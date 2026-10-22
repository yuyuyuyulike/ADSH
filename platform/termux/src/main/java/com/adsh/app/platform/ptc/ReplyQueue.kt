package com.adsh.app.platform.ptc

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * worker 侧「宿主回执」的排队口：收帧线程放、引擎线程一次取走。
 *
 * 为什么要阻塞取：泵循环以前每 1 ms 求值一次空表达式来等回执 —— 一次等几十秒的
 * `tools.bash` 就是每秒一千次 QuickJS 求值 + 一千次线程唤醒。dsh 的 Node 运行时在
 * `await` 上是真事件循环、不烧 CPU；这里用同一语义：没有回执就睡在队列上，来一条立刻醒。
 */
internal class ReplyQueue {

    private val queue = LinkedBlockingQueue<Pair<Int, String>>()

    fun put(id: Int, wire: String) {
        queue.put(id to wire)
    }

    /** 取走所有**已经到达**的回执（非阻塞，保持到达顺序） */
    fun drain(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        while (true) out += queue.poll() ?: break
        return out
    }

    /**
     * 等一条回执，最多 [timeoutMs]；超时返回 null。
     * 超时只用来让泵循环去看一眼「程序结算了吗 / 到点了吗 / 用户停了吗」，不是回执的上限。
     */
    fun await(timeoutMs: Long): Pair<Int, String>? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
}
