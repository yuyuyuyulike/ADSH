package com.adsh.app.ui.chat

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮尾分支的可见 / 可用判据（对齐 dsh 的 TurnTailNodeView + turn-tail.ts）：
 *  - **每个已完成轮次**各自判断，不要求是最新一轮、也不要求会话空闲；
 *  - 可见 = 该轮有一条带正文的 assistant 收尾回答（dsh 的 closing != null）；
 *  - 可用 = closing 同时是本轮 transcript 的最后一条内容（dsh 的 latestTranscriptSeq 判据），
 *    且最后一步不带 tool_calls；
 *  - 分叉点 = closing 那一行（dsh 传 turn/end seq，ADSH 按消息行截断）。
 */
class TurnBranchTest {

    private fun message(
        id: Long,
        role: String,
        content: String,
        toolCallsJson: String? = null,
    ): MessageEntity = MessageEntity(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        createdAt = id,
    )

    private fun turnViews(messages: List<MessageEntity>): List<TurnView> =
        buildChatItems(messages, compact = false)
            .filterIsInstance<ChatItem.TurnTail>()
            .map { it.view }

    @Test
    fun `每个已完成轮次都可以分支，不要求是最新一轮`() {
        val tails = turnViews(
            listOf(
                message(1, "user", "q1"),
                message(2, "assistant", "a1"),
                message(3, "user", "q2"),
                message(4, "assistant", "a2"),
            ),
        )
        assertEquals(2, tails.size)
        assertTrue(tails[0].branchVisible)
        assertTrue(tails[0].branchEnabled)
        assertEquals(2L, tails[0].branchMessageId)
        assertTrue(tails[1].branchVisible)
        assertTrue(tails[1].branchEnabled)
        assertEquals(4L, tails[1].branchMessageId)
    }

    @Test
    fun `回答之后还有一条内容时只可见、不可用`() {
        val tails = turnViews(
            listOf(
                message(1, "user", "q"),
                message(2, "assistant", "a"),
                message(3, "tool", "output"),
            ),
        )
        val view = tails.single()
        assertTrue(view.branchVisible)
        assertFalse(view.branchEnabled)
        assertNull(view.branchMessageId)
    }

    @Test
    fun `最后一步还带 tool_calls 时不算收尾回答`() {
        val tails = turnViews(
            listOf(
                message(1, "user", "q"),
                message(
                    2,
                    "assistant",
                    "先说明再调用",
                    toolCallsJson = "[{\"id\":\"c1\",\"type\":\"function\"," +
                        "\"function\":{\"name\":\"run_code\",\"arguments\":\"{}\"}}]",
                ),
            ),
        )
        val view = tails.single()
        assertTrue(view.branchVisible)
        assertFalse(view.branchEnabled)
    }

    @Test
    fun `整轮没有正文时分支按钮不可见`() {
        val tails = turnViews(
            listOf(
                message(1, "user", "q"),
                message(2, "assistant", ""),
            ),
        )
        assertFalse(tails.single().branchVisible)
    }

    @Test
    fun `未结束的轮次不可分支`() {
        val view = TurnView(
            key = 1L,
            entries = listOf(ProcessEntry.Text("a")),
            answerIndex = 0,
            closed = false,
            lastMessageId = 2L,
            closingMessageId = 2L,
        )
        assertFalse(view.branchVisible)
        assertFalse(view.branchEnabled)
    }
}
