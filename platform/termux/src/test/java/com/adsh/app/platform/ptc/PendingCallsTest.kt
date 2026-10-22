package com.adsh.app.platform.ptc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PendingCalls]：dsh 的 maxPendingCalls / maxMessageBytes 两道闸。
 *
 * 超限必须是「整次运行 protocol 失败」而不是静默排队 —— 否则一个循环里发几千个调用的程序
 * 只会把主进程拖死，模型连错误都看不到。
 */
class PendingCallsTest {

    @Test
    fun `allows up to the call cap and rejects the next one`() {
        val budget = PendingCalls(maxCalls = 2, maxBytes = 1_000)
        assertNull(budget.tryAcquire(10))
        assertNull(budget.tryAcquire(10))
        assertEquals(PendingCalls.ERROR, budget.tryAcquire(10))
        budget.release(10)
        assertNull(budget.tryAcquire(10))
    }

    @Test
    fun `rejects when pending argument bytes exceed the byte cap`() {
        val budget = PendingCalls(maxCalls = 10, maxBytes = 100)
        assertNull(budget.tryAcquire(60))
        assertEquals(PendingCalls.ERROR, budget.tryAcquire(60))
        // 被拒的那次没有占用额度：释放 60 之后还能再登记 100
        budget.release(60)
        assertNull(budget.tryAcquire(100))
    }

    @Test
    fun `a rejected call does not leak call slots`() {
        val budget = PendingCalls(maxCalls = 1, maxBytes = 1_000)
        assertNull(budget.tryAcquire(1))
        assertEquals(PendingCalls.ERROR, budget.tryAcquire(1))
        assertEquals(PendingCalls.ERROR, budget.tryAcquire(1))
        budget.release(1)
        assertNull(budget.tryAcquire(1))
    }
}
