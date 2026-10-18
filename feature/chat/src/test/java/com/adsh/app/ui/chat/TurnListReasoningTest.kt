package com.adsh.app.ui.chat

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考正文按块拆成独立会话流行：头仍是 [ChatItem.TurnEntry]（[TurnView.entries] 的语义不变），
 * 展开时正文按 [reasoningChunks] 的块各占一行 —— 行级绘制剔除（ChatTranscript 的 CulledRow）因此
 * 管得到正文，见 [ChatItem.ReasoningBody]。
 */
class TurnListReasoningTest {

    private fun user(id: Long = 1) =
        MessageEntity(id = id, conversationId = 1, role = "user", content = "u$id", createdAt = id * 10)

    /** 四段共 1200 字：块预算 480（2×预算早退阈值 960）⇒ 至少切两块 */
    private val reasoning = (1..4).joinToString("\n\n") { "第" + it + "段" + "字".repeat(300) }

    private fun items(open: (String) -> Boolean = { false }): List<ChatItem> = buildChatItems(
        messages = listOf(user()),
        reasoning = reasoning,
        reasoningRunning = false,
        liveTurnId = 1,
        reasoningOpen = open,
    )

    private fun entryOf(item: ChatItem.TurnEntry): ProcessEntry? = item.view.entries.getOrNull(item.index)

    private fun head(): ChatItem.TurnEntry =
        items().filterIsInstance<ChatItem.TurnEntry>().first { entryOf(it) is ProcessEntry.Reasoning }

    @Test
    fun `收起时只有头_没有正文块`() {
        val list = items()
        assertTrue(list.none { it is ChatItem.ReasoningBody })
        val heads = list.filterIsInstance<ChatItem.TurnEntry>()
        assertEquals(1, heads.size)
        assertTrue(entryOf(heads.single()) is ProcessEntry.Reasoning)
    }

    @Test
    fun `展开时头后面跟着正文块_边界首尾 NONE 且内部连续`() {
        val head = head()
        val key = reasoningKey(head.view, head.index)
        val list = items { it == key }
        val bodies = list.filterIsInstance<ChatItem.ReasoningBody>()
        assertTrue("1200 字的思考应当被切成多块", bodies.size >= 2)
        assertEquals(ReasoningBoundary.NONE, bodies.first().before)
        assertEquals(ReasoningBoundary.NONE, bodies.last().after)
        bodies.zipWithNext().forEach { (current, next) -> assertEquals(current.after, next.before) }
        bodies.forEachIndexed { index, body -> assertEquals(index, body.chunk) }
        // 头还在，且轮次条目（toolCount / messageCount / foldable 的来源）一点没变
        assertEquals(1, list.filterIsInstance<ChatItem.TurnEntry>().size)
        assertEquals(1, head.view.entries.size)
        // 切块不改变正文：按块边界拼回去等于原文
        assertEquals(reasoning, bodies.joinToString("\n\n") { it.text })
    }

    @Test
    fun `正文块紧跟在头后面_行 key 稳定且与头不同`() {
        val head = head()
        val key = reasoningKey(head.view, head.index)
        val list = items { it == key }
        val headIndex = list.indexOfFirst { it is ChatItem.TurnEntry }
        val body = list[headIndex + 1]
        assertTrue(body is ChatItem.ReasoningBody)
        assertTrue(transcriptKey(body).startsWith(transcriptKey(head)))
        assertTrue(transcriptKey(body).endsWith("-body#0"))
        assertTrue(transcriptKey(body) != transcriptKey(head))
    }

    @Test
    fun `折叠的已结束轮次里思考不出现`() {
        val messages = listOf(
            user(1),
            MessageEntity(
                id = 2,
                conversationId = 1,
                role = "assistant",
                content = "回答",
                reasoning = reasoning,
                createdAt = 20,
            ),
        )
        val list = buildChatItems(messages = messages, compact = true)
        assertTrue(
            "未展开的轮次里不该出现思考行（最终回答那一条 TurnEntry 不算）",
            list.filterIsInstance<ChatItem.TurnEntry>().none { entryOf(it) is ProcessEntry.Reasoning },
        )
        assertTrue(list.none { it is ChatItem.ReasoningBody })
        assertTrue(list.any { it is ChatItem.TurnFold })
    }
}
