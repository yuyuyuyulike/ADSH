package com.adsh.app.ui

import com.adsh.app.core.ptc.SubCall
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具族的界面形态**只由日志折叠**（dsh 的 cell 模型）。
 *
 * 这几条是第 109 轮重写的护栏：以前界面自己维护 liveCalls / liveSubCalls 再和库里对账，
 * 现在归属与顺序全部来自日志 —— 所以「结果贴回哪一行」「子调用挂在谁下面」必须只由 id 决定。
 */
class LiveTurnTest {

    private var seq = 0L
    private fun event(body: SessionBody, at: Long = 1000L + seq): SessionEvent =
        SessionEvent(seq = seq++, at = at, body = body)

    private fun sub(parent: Long, n: Int, running: Boolean = false) = SubCall(
        name = "bash",
        args = "{}",
        ok = true,
        result = "out",
        durationMs = 3,
        id = parent.toString() + ":ptc:" + n,
        running = running,
    )

    @Test
    fun aCallAndItsResultFoldIntoOneRow() {
        val turn = foldLiveTurn(
            listOf(
                event(SessionBody.ToolCall("c1", 7L, "run_code", "{}")),
                event(SessionBody.ToolResult("c1", 7L, "run_code", "done", false), at = 2000L),
            ),
        )
        assertEquals(1, turn.calls.size)
        assertEquals("done", turn.calls.single().output)
        assertFalse("结果到了就不再是运行中", turn.calls.single().running)
        assertEquals(1000L, turn.calls.single().startedAt)
        assertEquals(2000L, turn.calls.single().finishedAt)
    }

    @Test
    fun subCallsAreAttributedByTheirIdPrefix() {
        val turn = foldLiveTurn(
            listOf(
                event(SessionBody.ToolCall("c1", 7L, "run_code", "{}")),
                // 第一次调用的子行
                event(SessionBody.PtcDispatchStart(sub(7L, 1))),
                event(SessionBody.PtcDispatch(sub(7L, 1))),
                // 第二次调用（新的 harnessId）
                event(SessionBody.ToolCall("c2", 8L, "run_code", "{}")),
                event(SessionBody.PtcDispatchStart(sub(8L, 1))),
            ),
        )
        assertEquals(2, turn.calls.size)
        assertEquals(listOf("7:ptc:1", "8:ptc:1"), turn.subCalls.map { it.id })
        // 各有各的前缀：TurnList 就是按前缀取的（第一条只拿得到自己那一行）
        assertEquals(listOf("7:ptc:1"), turn.subCalls.filter { it.id.startsWith(turn.calls[0].prefix()) }.map { it.id })
        assertEquals(listOf("8:ptc:1"), turn.subCalls.filter { it.id.startsWith(turn.calls[1].prefix()) }.map { it.id })
    }

    @Test
    fun aSettleWithoutItsStartStillLandsSomewhereVisible() {
        // dsh 的客户端契约：settle 先到也要能补挂（绝不丢行）
        val turn = foldLiveTurn(
            listOf(
                event(SessionBody.ToolCall("c1", 7L, "run_code", "{}")),
                event(SessionBody.PtcDispatch(sub(7L, 1))),
            ),
        )
        assertEquals(listOf("7:ptc:1"), turn.subCalls.map { it.id })
    }


    /**
     * 步数也是折叠出来的（第 111 轮）：一步 assistant 输出定稿 = 日志里多一条 `Step`。
     * 界面拿它当流式尾巴的交班信号（每多一步，这一段的正文/思考就交给库里那一行）。
     */
    @Test
    fun stepsAreCountedFromTheLog() {
        val turn = foldLiveTurn(
            listOf(
                event(SessionBody.Step("第一步的正文", "想了一下")),
                event(SessionBody.ToolCall("c1", 7L, "run_code", "{}")),
                event(SessionBody.ToolResult("c1", 7L, "run_code", "done", false)),
                event(SessionBody.Step("第二步的正文", "")),
            ),
        )
        assertEquals(2, turn.steps)
        assertEquals(1, turn.calls.size)
    }

    @Test
    fun aResultForAnUnknownCallIsIgnored() {
        // 日志自检会先把这种情形报出来（SessionLog.violations），界面不再自己兜
        val turn = foldLiveTurn(listOf(event(SessionBody.ToolResult("c9", 99L, "x", "oops", true))))
        assertTrue(turn.calls.isEmpty())
    }
}
