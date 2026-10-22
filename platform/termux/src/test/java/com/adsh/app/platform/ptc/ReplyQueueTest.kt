package com.adsh.app.platform.ptc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ReplyQueue]：收帧线程与引擎线程之间唯一的交接面。
 *
 * 钉住三件事：按到达顺序 drain、await 在放进来之后立刻返回、空队列时按超时返回 null
 * （泵循环靠后者周期性地看结算/超时/停止探针，不能被卡死）。
 */
class ReplyQueueTest {

    @Test
    fun `drain returns replies in arrival order and empties the queue`() {
        val q = ReplyQueue()
        q.put(1, "a")
        q.put(2, "b")
        assertEquals(listOf(1 to "a", 2 to "b"), q.drain())
        assertEquals(emptyList<Pair<Int, String>>(), q.drain())
    }

    @Test
    fun `await wakes up when a reply arrives`() {
        val q = ReplyQueue()
        val started = CountDownLatch(1)
        val t = Thread {
            started.countDown()
            Thread.sleep(30)
            q.put(7, "wire")
        }.apply { isDaemon = true; start() }
        started.await(1, TimeUnit.SECONDS)
        assertEquals(7 to "wire", q.await(5_000))
        t.join(1_000)
    }

    @Test
    fun `await returns null when the timeout expires`() {
        val q = ReplyQueue()
        val before = System.currentTimeMillis()
        assertNull(q.await(20))
        // 不会提前返回（下限留足余量给慢机器）：至少等到接近 20ms
        assertEquals(true, System.currentTimeMillis() - before >= 15)
    }
}
