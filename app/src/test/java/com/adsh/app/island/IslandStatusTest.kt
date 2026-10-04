package com.adsh.app.island

import com.adsh.app.core.tools.ApprovalChannel
import com.adsh.app.core.tools.Question
import com.adsh.app.core.tools.UserQuestionChannel
import com.adsh.app.ui.ChatUiState
import com.adsh.app.ui.LiveCall
import com.adsh.app.ui.LiveTurn
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灵动岛的状态推导（纯函数）：哪一格、展开态哪几行。
 *
 * 这一层不碰 Android（[ChatUiState] 是纯数据），所以桌面上直接打。
 */
class IslandStatusTest {

    private fun state(
        sending: Boolean = true,
        streaming: String = "",
        reasoning: String = "",
        reasoningRunning: Boolean = false,
        toolArgsFlowing: Boolean = false,
        calls: List<LiveCall> = emptyList(),
    ) = ChatUiState(
        sending = sending,
        streaming = streaming,
        reasoning = reasoning,
        reasoningRunning = reasoningRunning,
        toolArgsFlowing = toolArgsFlowing,
        liveTurn = LiveTurn(calls = calls),
    )

    @Test
    fun thinkingWhenReasoningIsRunning() {
        val work = islandWorkOf(state(reasoning = "第一行\n第二行", reasoningRunning = true))
        assertEquals(IslandPhase.THINKING, work?.phase)
        assertEquals(listOf("第一行", "第二行"), work?.lines)
    }

    @Test
    fun outputWhenTheBodyIsStreaming() {
        val work = islandWorkOf(state(streaming = "正文一\n正文二"))
        assertEquals(IslandPhase.OUTPUT, work?.phase)
        assertEquals(listOf("正文一", "正文二"), work?.lines)
    }

    @Test
    fun toolWhenACallIsRunning() {
        val work = islandWorkOf(
            state(
                calls = listOf(
                    LiveCall(name = "run_code", arguments = "{}", startedAt = 1L),
                ),
            ),
        )
        assertEquals(IslandPhase.TOOL, work?.phase)
        assertEquals("run_code", work?.lines?.first())
    }

    @Test
    fun finishedCallNoLongerCountsAsTool() {
        // 工具跑完（finishedAt != null）而这一轮还在继续 → 不该再报「在调用工具」
        val work = islandWorkOf(
            state(
                streaming = "接着写",
                calls = listOf(LiveCall(name = "run_code", arguments = "{}", startedAt = 1L, finishedAt = 2L)),
            ),
        )
        assertEquals(IslandPhase.OUTPUT, work?.phase)
    }

    @Test
    fun flowingToolArgumentsCountAsTool() {
        val work = islandWorkOf(state(streaming = "{\"description\":\"x\"}", toolArgsFlowing = true))
        assertEquals(IslandPhase.TOOL, work?.phase)
    }

    @Test
    fun waitingBeatsEverythingElse() {
        val work = islandWorkOf(
            state(
                reasoning = "在想",
                reasoningRunning = true,
                calls = listOf(LiveCall(name = "run_code", arguments = "{}", startedAt = 1L)),
            ),
            waiting = IslandWaiting(ASK_TOOL_LABEL, listOf("选哪个？")),
        )
        assertEquals(IslandPhase.ASK, work?.phase)
        assertEquals(listOf("选哪个？"), work?.lines)
    }

    @Test
    fun idleWhenTheTurnIsOver() {
        assertNull(islandWorkOf(state(sending = false, streaming = "写完了")))
    }

    @Test
    fun aFreshTurnCountsAsThinking() {
        assertEquals(IslandPhase.THINKING, islandWorkOf(state())?.phase)
    }

    /**
     * 一帧的全部字符串来自 [islandFrameOf] 这一处（第 182 轮熵减）：服务只负责把一帧画出去。
     * 这里钉住三件事：文案就是五格文案、展开态那行取最新一行、以及「算不算在干活」——
     * 最后一格（已结束）必须是 STOPPED，否则媒体岛不会收。
     */
    @Test
    fun frameCarriesLabelLineAndActiveFlag() {
        val thinking = islandFrameOf(IslandWork(IslandPhase.THINKING, listOf("在想这件事", "第二行")))
        assertEquals("思考", thinking.label)
        assertEquals("在想这件事", thinking.line)
        assertTrue(thinking.active)

        val coding = islandFrameOf(IslandWork(IslandPhase.TOOL, listOf("run_code")))
        assertEquals("#代码", coding.label)
        assertTrue(coding.active)

        val done = islandFrameOf(IslandWork(IslandPhase.DONE))
        assertEquals("已结束", done.label)
        assertEquals("", done.line)
        assertFalse("已结束那一帧必须是 STOPPED（岛随之收掉）", done.active)
    }

    @Test
    fun onlyTheLastThreeNonBlankLinesAreKept() {
        val lines = islandLines("一\n\n二\n三\n四\n```\n五")
        assertEquals(listOf("三", "四", "五"), lines)
    }

    @Test
    fun aLongLineIsClippedToTheIslandWidth() {
        val lines = islandLines("x".repeat(120))
        assertEquals(1, lines.size)
        assertEquals(ISLAND_LINE_CHARS, lines[0].length)
        assertTrue(lines[0].endsWith("…"))
    }

    @Test
    fun theHugeTextIsOnlyScannedAtItsTail() {
        // 尾巴之前的内容一律不看（岛跟着状态重画，不能整串切行）
        val lines = islandLines("旧".repeat(ISLAND_TAIL_CHARS * 2) + "\n新的最后一行")
        assertEquals(listOf("新的最后一行"), lines)
    }

    @Test
    fun waitingComesFromTheQuestionChannelFirst() {
        val question = UserQuestionChannel.Pending(
            token = "t",
            questions = listOf(Question(id = "q", question = "要哪个？\n第二个")),
            completer = CompletableDeferred(),
        )
        val waiting = islandWaitingOf(question, null)
        assertEquals(ASK_TOOL_LABEL, waiting?.label)
        assertEquals(listOf("要哪个？", "第二个"), waiting?.lines)
        assertNull(islandWaitingOf(null, null))
    }

    @Test
    fun approvalShowsTheToolThatIsWaiting() {
        val approval = ApprovalChannel.Pending(
            token = "t",
            toolName = "run_code",
            reason = "越权",
            completer = CompletableDeferred(),
        )
        assertEquals("run_code", islandWaitingOf(null, approval)?.label)
    }
}
