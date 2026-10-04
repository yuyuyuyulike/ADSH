package com.adsh.app.ui

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
 * 「切会话不打断正在跑的那一轮」的**状态归属**（第 181 轮）。
 *
 * 这里钉的是用户反复报的那件事：切走再切回来，正在跑的那一轮**看着停了**。
 * 真因不在渲染，而在这两个函数的用法 —— 旧实现在切走时「抓一帧」存起来，抓的判据是屏幕上的
 * 会话 id；而命中正文缓存的快路径会先把屏幕 id 改成目标会话，于是抓帧把**刚读出来的库快照**
 * （sending=false、streaming=""）写进了正在跑那条会话的格子：切回来时接上的是这份垃圾。
 *
 * 现在改成登记表模型：一轮在跑期间它的状态一直住在 `liveRuns` 里，「看哪一条」只是一层投影 ——
 * [resetTurnFields] 负责把视图复位成「这条会话没有在跑」，[withLiveRun] 负责把登记表里那一份盖回来。
 * 顺序就是 ViewModel 里的顺序（先复位、后盖），下面每条都按这个顺序打。
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
        connection = ConnectionState.Reconnecting(2, "网络抖了一下"),
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
        assertEquals(ConnectionState.Idle, opened.connection)
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
     * 基线是「刚从库里读出来的快照」（sending=false、没有任何轮内痕迹）—— 旧实现就是在这上面接
     * 登记表里那份**被覆盖过的**垃圾，于是尾巴、工具行、岛全没了。
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
        assertEquals(ConnectionState.Reconnecting(2, "网络抖了一下"), resumed.connection)
        assertEquals(1_000L, resumed.runStartedAt)
        assertEquals(2, resumed.queuedCount)
        // 正文以**库**为准：切回来时刚读过一次，库里那份比登记表里那份全（含已定稿的步）
        assertEquals(listOf(message(1, "问"), message(2, "定稿的那一步")), resumed.messages)
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
