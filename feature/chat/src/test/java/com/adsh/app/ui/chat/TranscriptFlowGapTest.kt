package com.adsh.app.ui.chat

import androidx.compose.ui.unit.dp
import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话流的行距照 dsh 的 ChatView.module.css：默认 6px、最终回答前后 12px、折叠行（turn-process）
 * 与它下面那一行 16px。行距由「后一行自己的样式」决定，所以整条流拼出来后逐行检查上边距。
 */
class TranscriptFlowGapTest {

    private fun user(id: Long) =
        MessageEntity(id = id, conversationId = 1, role = "user", content = "u$id", createdAt = id * 10)

    private fun answer(id: Long, text: String = "答") =
        MessageEntity(id = id, conversationId = 1, role = "assistant", content = text, createdAt = id * 10)

    private fun calls(id: Long) = MessageEntity(
        id = id,
        conversationId = 1,
        role = "assistant",
        content = "",
        toolCallsJson = """[
            {"id":"c$id-1","type":"function","function":{"name":"run_code","arguments":"{}"}},
            {"id":"c$id-2","type":"function","function":{"name":"bash","arguments":"{}"}}
        ]""",
        createdAt = id * 10,
    )

    @Test
    fun simpleAnswerSitsTwelveBelowTheUserMessage() {
        val items = buildChatItems(messages = listOf(user(1), answer(2)), liveTurnId = null)
        assertEquals("用户消息是流的第一行", 0.dp, items[0].gap)
        assertEquals("最终回答（response）与上一行 12dp", 12.dp, items[1].gap)
        assertEquals("轮尾跟在回答后面也是 12dp", 12.dp, items[2].gap)
    }

    @Test
    fun foldedTurnKeepsTheDshSixTwelveSixteenRhythm() {
        val messages = listOf(user(1), calls(2), answer(4))
        val folded = buildChatItems(messages = messages, liveTurnId = null)
        assertEquals("折叠行（turn-process）与用户消息 16dp", 16.dp, folded[1].gap)
        assertEquals("折叠时回答在折叠行下面，被 turn-process 的 16dp 规则接管", 16.dp, folded[2].gap)
        assertEquals(12.dp, folded[3].gap)

        val open = buildChatItems(messages = messages, liveTurnId = null, foldOpen = { true })
        assertEquals(16.dp, open[1].gap)
        assertEquals("展开后第一条过程行同样吃 16dp", 16.dp, open[2].gap)
        assertEquals("过程行之间回到默认 6dp", 6.dp, open[3].gap)
        assertEquals("回答前 12dp", 12.dp, open[4].gap)
        assertEquals(12.dp, open[5].gap)
    }

    @Test
    fun systemPromptContextAndUserShareTheDefaultGap() {
        val messages = listOf(
            MessageEntity(id = 1, conversationId = 1, role = "sysprompt", content = "s", createdAt = 10),
            MessageEntity(id = 2, conversationId = 1, role = "context", content = "c", name = "sandbox:policy", createdAt = 20),
            user(3),
            answer(4),
        )
        val items = buildChatItems(messages = messages, liveTurnId = null)
        assertEquals(0.dp, items[0].gap)
        assertEquals("系统提示与上下文之间 6dp", 6.dp, items[1].gap)
        assertEquals("上下文与用户消息之间 6dp", 6.dp, items[2].gap)
        assertEquals(12.dp, items[3].gap)
    }

    @Test
    fun theNextTurnStartsSixBelowThePreviousTail() {
        val items = buildChatItems(
            messages = listOf(user(1), answer(2), user(3), answer(4)),
            liveTurnId = null,
        )
        val third = items.filterIsInstance<ChatItem.User>()[1]
        assertEquals("跨轮：轮尾之后的新用户消息 6dp（dsh 的默认 flow gap）", 6.dp, third.gap)
    }
}
