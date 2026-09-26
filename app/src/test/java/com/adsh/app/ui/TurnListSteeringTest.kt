package com.adsh.app.ui

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插话（dsh 的 steering 节点）在会话流里的位置 —— 第六十九轮。
 *
 * 用户报的现象：AI 干活时插话发送，**工具调用的展示会被吞掉、过一会儿又蹦出来**。
 * 根因是插话被当成新一轮的开头（`liveTurnId` 跟着挪到它身上）：正在跑的那一轮当场被判成
 * 「已结束」，流式正文 / 思考 / 运行中的工具行 / PTC 子调用全都不再挂到它上面，要等落库了才回来。
 *
 * dsh 的语义（dsh-client-ui-chat 的 messageDefinition + turn-process-presentation）：
 *  - 被 next-step inbox 认领的用户消息节点 kind = `steering`，它**不开新的一轮**；
 *  - 轮内出现 human 节点（user / steering）时 `compactAnswer` 为 false —— 这一轮不折成摘要行。
 * 这两条钉在下面。
 */
class TurnListSteeringTest {

    private fun user(id: Long, text: String = "u$id", name: String? = null, at: Long = id * 10) =
        MessageEntity(id = id, conversationId = 1, role = "user", content = text, name = name, createdAt = at)

    private fun assistant(id: Long, text: String, calls: String? = null, at: Long = id * 10) =
        MessageEntity(
            id = id,
            conversationId = 1,
            role = "assistant",
            content = text,
            toolCallsJson = calls,
            createdAt = at,
        )

    private fun toolResult(id: Long, callId: String, output: String, at: Long = id * 10) =
        MessageEntity(
            id = id,
            conversationId = 1,
            role = "tool",
            content = output,
            toolCallId = callId,
            name = "bash",
            createdAt = at,
        )

    private val toolCallJson =
        """[{"id":"c1","type":"function","function":{"name":"run_code","arguments":"{}"}}]"""

    /** 一轮里的全部过程条目（把拆开的行重新拼起来） */
    private fun turnOf(items: List<ChatItem>, key: Long): TurnView? =
        items.filterIsInstance<ChatItem.TurnEntry>().firstOrNull { it.view.key == key }?.view

    @Test
    fun steeringStaysInsideTheRunningTurn() {
        val messages = listOf(
            user(1, "第一条"),
            assistant(2, "", toolCallJson),
            toolResult(3, "c1", "ok"),
            user(4, "插一句", name = ConversationRepository.STEERING),
            assistant(5, "收到"),
        )
        val live = listOf(LiveCall(id = 1, callId = "c2", name = "run_code", arguments = "{}", startedAt = 100))
        val items = buildChatItems(
            messages = messages,
            liveCalls = live,
            liveTurnId = 1,
            compact = true,
        )

        // 插话**不**单独占一行用户气泡（它不是新一轮），而是这一轮过程里的一条
        assertTrue(
            "steering must not open its own user row",
            items.none { it is ChatItem.User && it.key == 4L },
        )
        // 整个列表里只有一轮（折叠行/过程行/轮尾都属于同一个 turn key）
        val turnKeys = buildSet {
            items.filterIsInstance<ChatItem.TurnEntry>().forEach { add(it.view.key) }
            items.filterIsInstance<ChatItem.TurnFold>().forEach { add(it.view.key) }
            items.filterIsInstance<ChatItem.TurnTail>().forEach { add(it.view.key) }
        }
        assertEquals("steering must not open a second turn", setOf(1L), turnKeys)

        val view = turnOf(items, 1L)
        assertTrue("the running turn must still be rendered", view != null)
        val steering = view!!.entries.filterIsInstance<ProcessEntry.Steering>()
        assertEquals(1, steering.size)
        assertEquals("插一句", steering.first().text)
        // 落库的工具行与流式中的工具行都还在**这一轮**里（旧实现会整体挪到插话下面）
        assertEquals(2, view.entries.count { it is ProcessEntry.Call })
        assertTrue(
            "the live call must keep running inside the running turn",
            view.entries.filterIsInstance<ProcessEntry.Call>().any { it.running },
        )
    }

    @Test
    fun steeringKeepsTheTurnUnfolded() {
        val messages = listOf(
            user(1, "第一条"),
            assistant(2, "过程里的一句话"),
            assistant(3, "", toolCallJson),
            toolResult(4, "c1", "ok"),
            user(5, "插一句", name = ConversationRepository.STEERING),
            assistant(6, "收尾回答"),
        )
        val items = buildChatItems(messages = messages)
        val view = turnOf(items, 1L) ?: error("turn 1 missing")
        assertTrue("a turn with an interjection is never folded away", view.hasSteering)
        assertFalse("foldable must be false when the user interjected", view.foldable)
        // 最终回答仍然认得出来（answerIndex 指向它），只是这一轮不折成摘要行 ——
        // dsh 的 compactAnswer=false 就是这个意思：过程与回答都平铺展示。
        assertEquals(view.entries.lastIndex, view.answerIndex)
        assertEquals("收尾回答", view.answer)
    }

    @Test
    fun renamedDanglingSteeringOpensTheNextTurn() {
        // 插话到达时这一轮已经收尾（模型没机会看到它）：ChatViewModel.continueIfDangling 把 name
        // 抹掉，它就成了下一轮的开头 —— 界面必须跟着把它画成一行用户气泡 + 新的一轮。
        val messages = listOf(
            user(1, "第一条"),
            assistant(2, "回答"),
            user(3, "插一句", name = null),
        )
        val items = buildChatItems(messages = messages, liveTurnId = 3)
        assertTrue("a renamed steering row is a user bubble", items.any { it is ChatItem.User && it.key == 3L })
        assertEquals(
            "and it opens its own turn",
            2,
            items.filterIsInstance<ChatItem.TurnTail>().size,
        )
    }

    @Test
    fun unclaimedSteeringStaysPutUntilItIsRenamed() {
        // name 还没抹掉的那一两帧（以及被停止时没抹掉的那种）：它仍在原来那一轮里 ——
        // 先挪出去再挪回来就是用户报的「吞掉又蹦出来」，所以这里要它**不动**。
        val messages = listOf(
            user(1, "第一条"),
            assistant(2, "回答"),
            user(3, "插一句", name = ConversationRepository.STEERING),
        )
        val items = buildChatItems(messages = messages, liveTurnId = 1)
        assertTrue("no separate user row", items.none { it is ChatItem.User && it.key == 3L })
        assertEquals(
            "still one turn",
            1,
            items.filterIsInstance<ChatItem.TurnTail>().size,
        )
        val view = turnOf(items, 1L) ?: error("turn 1 missing")
        assertEquals(1, view.entries.filterIsInstance<ProcessEntry.Steering>().size)
    }
}
