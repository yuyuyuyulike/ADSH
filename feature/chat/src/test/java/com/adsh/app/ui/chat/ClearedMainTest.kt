package com.adsh.app.ui.chat

import com.adsh.app.core.llm.SessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [clearedMain] 是删掉当前会话 / 新会话无处可落时回到的首页状态（dsh 的 clearMain）：
 * 会话、工作区归属、统计、上下文占用、附件、轮内痕迹全部清掉 —— 只要漏一项，
 * 空态首页就会带着上一条会话的残留（最典型的是输入框上方还挂着上一轮的统计胶囊）。
 */
class ClearedMainTest {

    private val stocked = ChatUiState(
        conversationId = 7L,
        messages = emptyList(),
        workspaceId = 3L,
        workspacePath = "/storage/emulated/0/notes",
        stats = SessionStats(promptTokens = 10, completionTokens = 5),
        context = ContextUsage(system = 100, tools = 10, messages = 20, window = 1000),
        planMode = true,
        pendingAttachments = listOf("/tmp/a.png"),
        pendingSteering = listOf(PendingSteer(1L, "插话", 2L)),
        compacting = true,
        streaming = "正在写",
        sending = true,
        liveTurnId = 7L,
        runStartedAt = 5L,
        error = "boom",
        themePreference = "dark",
        contentFontSize = 16,
        modelLabel = "DeepSeek-V4.1-Flash",
        permission = "read_only",
    )

    @Test
    fun `清掉会话与工作区归属，界面回到首页`() {
        val cleared = stocked.clearedMain()
        assertNull(cleared.conversationId)
        assertNull(cleared.workspaceId)
        assertNull(cleared.workspacePath)
        assertTrue(cleared.messages.isEmpty())
        assertTrue(!cleared.planMode)
    }

    @Test
    fun `清掉会话级投影与待发附件`() {
        val cleared = stocked.clearedMain()
        assertEquals(SessionStats(), cleared.stats)
        assertEquals(ContextUsage(), cleared.context)
        assertTrue(cleared.pendingAttachments.isEmpty())
        assertTrue(cleared.pendingSteering.isEmpty())
        assertTrue(!cleared.compacting)
    }

    @Test
    fun `轮内痕迹整块复位`() {
        val cleared = stocked.clearedMain()
        assertTrue(!cleared.sending)
        assertEquals("", cleared.streaming)
        assertNull(cleared.liveTurnId)
        assertEquals(0L, cleared.runStartedAt)
        assertNull(cleared.error)
    }

    @Test
    fun `与会话无关的偏好原样保留`() {
        val cleared = stocked.clearedMain()
        assertEquals("dark", cleared.themePreference)
        assertEquals(16, cleared.contentFontSize)
        assertEquals("DeepSeek-V4.1-Flash", cleared.modelLabel)
        assertEquals("read_only", cleared.permission)
    }
}
