package com.adsh.app.core.session

import com.adsh.app.core.ptc.SubCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话日志的契约（dsh 的 `Session`，spec-log §1 / §7.2）。
 *
 * 这些断言是重写的护栏：界面以后只读日志，所以「seq 连续」「结果必有调用」「子调用必须挂在
 * 已开始的父调用上」这三条一旦破了，界面表现为「少一行 / 多一行」，而不是抛错。
 */
class SessionLogTest {

    private fun sub(parent: Long, n: Int) = SubCall(
        name = "bash",
        args = "{}",
        ok = true,
        result = "ok",
        durationMs = 1,
        id = parent.toString() + ":ptc:" + n,
    )

    @Test
    fun seqIsTheLogLengthAndAppendOrderIsTheOrder() {
        val log = SessionLog()
        log.append(SessionBody.ToolCall("c1", 7L, "run_code", "{}"))
        log.append(SessionBody.PtcDispatchStart(sub(7L, 1)))
        log.append(SessionBody.PtcDispatch(sub(7L, 1)))
        log.append(SessionBody.ToolResult("c1", 7L, "run_code", "done", false))

        assertEquals(listOf(0L, 1L, 2L, 3L), log.events.map { it.seq })
        assertTrue(SessionLog.violations(log.events).isEmpty())
    }

    /** 一步定稿也是一条普通事件：进日志、占一个 seq、不影响任何跨事件不变量（第 111 轮） */
    @Test
    fun aCommittedStepIsJustAnotherEvent() {
        val log = SessionLog()
        log.append(SessionBody.ToolCall("c1", 7L, "run_code", "{}"))
        log.append(SessionBody.Step(text = "正文", reasoning = "思考"))
        log.append(SessionBody.ToolResult("c1", 7L, "run_code", "done", false))
        log.append(SessionBody.Step(text = "收尾", reasoning = "", interrupted = true))

        assertEquals(listOf(0L, 1L, 2L, 3L), log.events.map { it.seq })
        assertTrue(SessionLog.violations(log.events).isEmpty())
    }

    @Test
    fun observersAreNotifiedSynchronouslyInAppendOrder() {
        val log = SessionLog()
        val seen = ArrayList<Long>()
        log.observe { seen += it.seq }
        log.append(SessionBody.ToolCall("c1", 1L, "bash", "{}"))
        log.append(SessionBody.ToolResult("c1", 1L, "bash", "out", false))
        // 同步广播：append 返回时观察者已经看过
        assertEquals(listOf(0L, 1L), seen)
    }

    @Test
    fun aThrowingObserverDoesNotBlockTheCommit() {
        val log = SessionLog()
        log.observe { error("observer boom") }
        val event = log.append(SessionBody.ToolCall("c1", 1L, "bash", "{}"))
        assertEquals(0L, event.seq)
        assertEquals(1, log.events.size)
    }

    @Test
    fun violationsCatchAResultWithoutItsCall() {
        val log = SessionLog()
        log.append(SessionBody.ToolResult("c1", 3L, "bash", "out", false))
        val bad = SessionLog.violations(log.events)
        assertTrue(bad.toString(), bad.any { it.contains("结果没有对应的调用") })
    }

    @Test
    fun violationsCatchASubCallWhoseParentNeverStarted() {
        val log = SessionLog()
        // 子调用的 id 前缀指向 9，但日志里没有 harnessId=9 的调用
        log.append(SessionBody.PtcDispatchStart(sub(9L, 1)))
        val bad = SessionLog.violations(log.events)
        assertTrue(bad.toString(), bad.any { it.contains("找不到父调用") })
    }

    @Test
    fun violationsCatchADoubleSettle() {
        val log = SessionLog()
        log.append(SessionBody.ToolCall("c1", 5L, "bash", "{}"))
        log.append(SessionBody.ToolResult("c1", 5L, "bash", "out", false))
        log.append(SessionBody.ToolResult("c1", 5L, "bash", "out again", false))
        val bad = SessionLog.violations(log.events)
        assertTrue(bad.toString(), bad.any { it.contains("结算了两次") })
    }
}
