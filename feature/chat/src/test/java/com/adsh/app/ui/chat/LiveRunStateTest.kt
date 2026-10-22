package com.adsh.app.ui.chat

import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切会话不打断正在跑的那一轮」的**状态归属**：切走再切回来，正在跑的那一轮看着停了。
 * **不能「切走时抓一帧」**：命中正文缓存的快路径会先把屏幕 id 改成目标会话，抓帧会把
 * **刚读出来的库快照**写进正在跑那条会话的格子。登记表模型：[resetTurnFields] 复位、
 * [withLiveRun] 盖回来，顺序同 ViewModel。
 */
class LiveRunStateTest {

    private val running = 11L
    private val other = 22L

    private fun message(id: Long, content: String): MessageEntity = MessageEntity(
        id = id,
        conversationId = running,
        role = if (id % 2 == 0L) "assistant" else "user",
        content = content,
        name = null,
        attachmentsJson = null,
        createdAt = 1_700_000_000_000L + id,
    )

    private val toolEvents = listOf(
        SessionEvent(seq = 0, at = 100L, body = SessionBody.ToolCall("c1", 7L, "run_code", "{}")),
        SessionEvent(seq = 1, at = 250L, body = SessionBody.ToolResult("c1", 7L, "run_code", "ok", false)),
    )

    /** 正在跑的那条会话此刻的样子（登记表里那一份） */
    private val liveRun = ChatUiState(
        conversationId = running,
        messages = listOf(message(1, "问")),
        streaming = "它正在写的这一句",
        reasoning = "想了一半",
        reasoningRunning = true,
        toolArgsFlowing = true,
        sending = true,
        liveTurnId = 1L,
        turnEvents = toolEvents,
        liveTurn = foldLiveTurn(toolEvents),
        runStartedAt = 1_000L,
        queuedCount = 2,
    )

    /** ViewModel 里登记表的查法：看这条会话时读屏幕，否则读登记表 */
    private fun liveRunOf(conversationId: Long): ChatUiState? =
        if (conversationId == running) liveRun else null

    /** 刚切到另一条会话：屏幕上那条被切走会话的轮内痕迹必须整块复位 */
    @Test
    fun switchToIdleConversationDropsEveryTurnTrace() {
        val opened = liveRun.copy(
            conversationId = other,
            messages = listOf(message(21, "另一条会话")),
            stats = SessionStats(turns = 3),
            planMode = true,
        ).resetTurnFields(queuedCount = 0).withLiveRun(liveRunOf(other))

        assertFalse(opened.sending)
        assertNull(opened.liveTurnId)
        assertEquals("", opened.streaming)
        assertEquals("", opened.reasoning)
        assertFalse(opened.reasoningRunning)
        assertFalse(opened.toolArgsFlowing)
        assertEquals(0, opened.liveTurn.calls.size)
        assertEquals(0, opened.turnEvents.size)
        assertEquals(0L, opened.runStartedAt)
        assertEquals(other, opened.conversationId)
        // 会话级的那几项跟着会话走，不许被复位
        assertEquals(listOf(message(21, "另一条会话")), opened.messages)
        assertEquals(3, opened.stats.turns)
        assertTrue(opened.planMode)
    }

    /**
     * 用户报的那件事的回归：切回**正在跑**的那条会话。
     *
     * 基线是「刚从库里读出来的快照」（sending=false、没有任何轮内痕迹）—— **不能**在这上面接
     * 登记表里那份**被覆盖过的**垃圾，否则尾巴、工具行、岛全没了。
     */
    @Test
    fun switchingBackToRunningConversationRestoresTheLiveTurn() {
        val fromLibrary = ChatUiState(
            conversationId = running,
            messages = listOf(message(1, "问"), message(2, "定稿的那一步")),
            stats = SessionStats(turns = 1),
        )
        val resumed = fromLibrary.resetTurnFields(queuedCount = 2).withLiveRun(liveRunOf(running))

        assertTrue(resumed.sending)
        assertEquals("它正在写的这一句", resumed.streaming)
        assertEquals("想了一半", resumed.reasoning)
        assertTrue(resumed.reasoningRunning)
        assertTrue(resumed.toolArgsFlowing)
        assertEquals(1L, resumed.liveTurnId)
        assertEquals(foldLiveTurn(toolEvents), resumed.liveTurn)
        assertEquals(toolEvents, resumed.turnEvents)
        assertEquals(1_000L, resumed.runStartedAt)
        assertEquals(2, resumed.queuedCount)
        // 正文以**库**为准：切回来时刚读过一次，库里那份比登记表里那份全（含已定稿的步）
        assertEquals(listOf(message(1, "问"), message(2, "定稿的那一步")), resumed.messages)
    }

    /**
     * 用户报的第二件事的回归：Agent 干活时切走再切回来，输入框下方三个胶囊会消失，等下一次
     * 工具调用结算才回来。根因是**统计比库新**：库里的 stats 只在收尾落盘（finishTurn → setStats），
     * 一轮在跑时切回来若拿库值（首轮就是零值），三个胶囊的可见判据都不成立。
     */
    @Test
    fun switchingBackToRunningConversationKeepsLiveStatsAndContext() {
        val liveStats = SessionStats(
            turns = 2,
            steps = 6,
            promptTokens = 12_000,
            completionTokens = 900,
            cacheHitTokens = 4_000,
            toolMillis = 2_500,
        )
        val liveContext = ContextUsage(system = 8_400, tools = 120, messages = 3_100, window = 128_000)
        val live = liveRun.copy(stats = liveStats, context = liveContext)
        val fromLibrary = ChatUiState(
            conversationId = running,
            messages = listOf(message(1, "问")),
            stats = SessionStats(),
            context = ContextUsage(window = 128_000),
        )

        val resumed = fromLibrary.resetTurnFields(queuedCount = 0).withLiveRun(live)

        assertEquals(liveStats, resumed.stats)
        assertEquals(liveContext, resumed.context)
    }

    /** 这条会话没有在跑的一轮：盖回来的必须是复位后的样子，不是别处的残留 */
    @Test
    fun noLiveRunLeavesTheResetStateAlone() {
        val fresh = ChatUiState(conversationId = other, messages = listOf(message(21, "嗨")))
            .resetTurnFields(queuedCount = 4)
        assertEquals(fresh, fresh.withLiveRun(null))
        assertEquals(4, fresh.queuedCount)
        assertFalse(fresh.sending)
    }

    /**
     * 空态（删掉当前会话 / 没有可落工作区的 clearMain）没有胶囊：入场动画的武装位必须一起清掉，
     * 否则下一条会话载入后第一次出现胶囊会带着上一条会话的旧决定播一次高度动画。
     */
    @Test
    fun clearedMainDisarmsTheDockEntrance() {
        val armed = ChatUiState(
            conversationId = running,
            messages = listOf(message(1, "问")),
            stats = SessionStats(turns = 1),
            dockEntranceArmed = true,
        )
        val cleared = armed.clearedMain()
        assertFalse(cleared.dockEntranceArmed)
        assertNull(cleared.conversationId)
    }

    /** 复位**只动轮内字段**：模型 / 字号 / 对话显示 / 上下文占用这些是全局视图，不许被清 */
    @Test
    fun resetOnlyTouchesTurnFields() {
        val before = ChatUiState(
            conversationId = running,
            modelLabel = "deepseek-v4",
            providerName = "DeepSeek",
            transcriptView = "normal",
            contentFontSize = 16,
            workspaceId = 9L,
            workspacePath = "/sdcard/ws",
            permission = "full-access",
            reasoningEffort = "high",
            compacting = true,
            blankConversationIds = setOf(5L),
        )
        val after = before.resetTurnFields(queuedCount = 1)
        assertEquals("deepseek-v4", after.modelLabel)
        assertEquals("DeepSeek", after.providerName)
        assertEquals("normal", after.transcriptView)
        assertEquals(16, after.contentFontSize)
        assertEquals(9L, after.workspaceId)
        assertEquals("/sdcard/ws", after.workspacePath)
        assertEquals("full-access", after.permission)
        assertEquals("high", after.reasoningEffort)
        assertTrue(after.compacting)
        assertEquals(setOf(5L), after.blankConversationIds)
        assertEquals(1, after.queuedCount)
    }
}
