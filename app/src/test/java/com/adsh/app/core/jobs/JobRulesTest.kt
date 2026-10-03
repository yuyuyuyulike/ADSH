package com.adsh.app.core.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表里的纯判定（[visibleTo] / [mergedDetail]）与两个已经在 [Jobs.View] 上的口径
 * （`statusLine` / `observable`）。
 *
 * 判错都不崩，只是静默做错事：栅栏判松了 = 一个会话能看/杀另一个会话的任务；
 * detail 拼错了 = 终局原因少一半。
 */
class JobRulesTest {

    // ------------------------------------------------------------------ owner 栅栏

    /** unowned（没有会话的调用）对谁都开 */
    @Test
    fun `unowned 谁都能看`() {
        assertTrue(visibleTo(owner = null, caller = 7L))
        assertTrue(visibleTo(owner = null, caller = null))
    }

    @Test
    fun `同会话可见`() {
        assertTrue(visibleTo(owner = 7L, caller = 7L))
    }

    @Test
    fun `别的会话不可见`() {
        assertFalse(visibleTo(owner = 7L, caller = 8L))
    }

    /** 没有会话上下文的调用者看得到 unowned，但看不到任何有主的 */
    @Test
    fun `没有 caller 时看不到有主的`() {
        assertFalse(visibleTo(owner = 7L, caller = null))
    }

    // -------------------------------------------------------------------- detail

    @Test
    fun `killed：生产者的事实在前，被谁杀的在后`() {
        assertEquals(
            "exit code: 137; killed by user",
            mergedDetail(Jobs.Status.KILLED, "exit code: 137", "killed by user"),
        )
    }

    @Test
    fun `killed 但生产者没给 detail：只剩 kill 原因`() {
        assertEquals("killed by user", mergedDetail(Jobs.Status.KILLED, null, "killed by user"))
    }

    @Test
    fun `killed 但没人 kill 过它：原样`() {
        assertEquals("signal 9", mergedDetail(Jobs.Status.KILLED, "signal 9", null))
        assertNull(mergedDetail(Jobs.Status.KILLED, null, null))
    }

    /** 跑赢了 kill 的终局不带那条原因（那次 kill 没有真的决定结果） */
    @Test
    fun `非 killed 的终局不带 kill 原因`() {
        assertEquals("exit code: 0", mergedDetail(Jobs.Status.COMPLETED, "exit code: 0", "killed by user"))
        assertEquals("boom", mergedDetail(Jobs.Status.FAILED, "boom", "killed by user"))
    }

    // -------------------------------------------------------- View 上的两条口径

    private fun view(status: Jobs.Status, detail: String? = null, total: Int = 0) =
        Jobs.View(id = "bash-1", kind = "bash", label = "ls", status = status, detail = detail, total = total)

    @Test
    fun `statusLine 两种形态`() {
        assertEquals("[status: running]", view(Jobs.Status.RUNNING).statusLine)
        assertEquals(
            "[status: completed, exit code: 0]",
            view(Jobs.Status.COMPLETED, "exit code: 0").statusLine,
        )
    }

    /** 界面的可展开判据：还活着，或者还留着输出（dsh 的 isObservable） */
    @Test
    fun `observable：活着或还有输出`() {
        assertTrue(view(Jobs.Status.RUNNING).observable)
        assertTrue(view(Jobs.Status.STOPPING).observable)
        assertTrue(view(Jobs.Status.COMPLETED, total = 12).observable)
        assertFalse(view(Jobs.Status.COMPLETED).observable)
        assertFalse(view(Jobs.Status.KILLED).observable)
        assertFalse(view(Jobs.Status.FAILED).observable)
    }

    @Test
    fun `三个终态`() {
        assertTrue(Jobs.Status.COMPLETED.terminal)
        assertTrue(Jobs.Status.KILLED.terminal)
        assertTrue(Jobs.Status.FAILED.terminal)
        assertFalse(Jobs.Status.RUNNING.terminal)
        assertFalse(Jobs.Status.STOPPING.terminal)
    }
}
