package com.adsh.app.core.tools

import com.adsh.app.seam.ptc.SubCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run_code 结束（超时 / worker 崩溃 / 用户中断）时，还没结算的子调用按 dsh 的 abort 语义收尾：
 * 结束态、失败、正文取 dsh 的 toolAbortedResult 原文 —— 不能让界面那一行永远在转流光。
 */
class AbortedSubCallTest {

    @Test
    fun abortedCallIsSettledAndNeverRunning() {
        val started = SubCall(
            name = "bash",
            args = "{\"command\":\"sleep 20\"}",
            ok = true,
            result = "",
            durationMs = 0,
            id = "7:ptc:1",
            running = true,
        )
        val settled = abortedSubCall(started, startedAt = 1_000L, now = 2_500L)
        assertFalse("必须落到结束态（界面据此停流光）", settled.running)
        assertFalse("abort 是失败", settled.ok)
        assertEquals("Error: tool call aborted", settled.result)
        assertEquals(1_500L, settled.durationMs)
        assertEquals("7:ptc:1", settled.id)
        assertEquals("bash", settled.name)
        assertTrue(settled.images.isEmpty())
        assertTrue(settled.deliverables.isEmpty())
    }

    @Test
    fun durationNeverGoesNegative() {
        val settled = abortedSubCall(
            SubCall(name = "x", args = "{}", ok = true, result = "", durationMs = 0, id = "1:ptc:1", running = true),
            startedAt = 2_000L,
            now = 1_000L,
        )
        assertEquals(0L, settled.durationMs)
    }
}
