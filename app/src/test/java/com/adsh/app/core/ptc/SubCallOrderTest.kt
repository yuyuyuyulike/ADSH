package com.adsh.app.core.ptc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTC 子调用的提交序（dsh 的 `commitQueue`，spec-log §4.1-B）：
 * 派发可重叠，**入档严格按提交序** —— 队头没结算，谁都别想提交。
 *
 * 真机上的理由：`Promise.all` 的读类子调用各自并发，谁先跑完由调度决定（旧写法实测 10 个
 * 并发 bash 的展示顺序是 2,1,3,5,6,7,8,10,9,4）。日志顺序必须等于模型写下的顺序，
 * 否则界面的子行顺序、id 序号、交付物收集顺序都会跟着调度漂。
 */
class SubCallOrderTest {

    private fun sub(id: Int) = SubCall(
        name = "glob",
        args = "{}",
        ok = true,
        result = "r" + id,
        durationMs = 1,
        id = "1:ptc:" + id,
    )

    @Test
    fun inOrderSettlesCommitImmediately() {
        val order = SubCallOrder(3)
        assertEquals(listOf("r1"), order.settle(0, sub(1)).map { it.result })
        assertEquals(listOf("r2"), order.settle(1, sub(2)).map { it.result })
        assertEquals(listOf("r3"), order.settle(2, sub(3)).map { it.result })
    }

    @Test
    fun laterSettlesWaitForTheHead() {
        val order = SubCallOrder(3)
        assertTrue("队头没结算，第二条不许提交", order.settle(1, sub(2)).isEmpty())
        assertTrue("第三条同样压着", order.settle(2, sub(3)).isEmpty())
        // 队头一到，攒着的按提交序一次放出来
        assertEquals(listOf("r1", "r2", "r3"), order.settle(0, sub(1)).map { it.result })
    }

    @Test
    fun eachEntryIsCommittedExactlyOnce() {
        val order = SubCallOrder(2)
        assertTrue(order.settle(1, sub(2)).isEmpty())
        assertEquals(listOf("r1", "r2"), order.settle(0, sub(1)).map { it.result })
        assertTrue("提交过的号不会再提交一次", order.settle(1, sub(2)).isEmpty())
    }

    @Test
    fun drainSkipsAbandonedSlotsButKeepsOrder() {
        val order = SubCallOrder(4)
        assertTrue(order.settle(2, sub(3)).isEmpty())
        assertTrue(order.settle(3, sub(4)).isEmpty())
        // 下标 0 / 1 被取消，永远不会结算：drain 跳过它们，把跑完的按提交序放出来
        assertEquals(listOf("r3", "r4"), order.drain().map { it.result })
        assertTrue("drain 之后没有东西可提交了", order.settle(0, sub(1)).isEmpty())
    }
}
