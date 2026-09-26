package com.adsh.app.ui

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式尾巴的形态（第六十九轮之后补充，第七十轮）：
 *
 *  - **思考行的 running** 由 `reasoningRunning` 决定 —— 模型一开始写工具调用（tool_call delta）
 *    就该停，而不是等工具行出现（用户报的「思考完不动，等工具调用蹦出来才一起变回第一句」）；
 *  - 停止（cancel）之后仍然按 `liveTurnId` 挂流式内容 —— 否则那一轮的内容会先消失、再从库里长回来
 *    （用户报的「终止时画面闪烁」）。
 *
 * 这里的判据都落在 `buildChatItems` 上：界面看到什么，完全由它决定。
 */
class TurnListLiveTest {

    private fun user(id: Long = 1) =
        MessageEntity(id = id, conversationId = 1, role = "user", content = "u$id", createdAt = id * 10)

    private fun reasoningEntry(entries: List<ProcessEntry>) =
        entries.filterIsInstance<ProcessEntry.Reasoning>().firstOrNull()

    /** 这一轮当前渲染出来的过程条目（liveTurnId = null 时这一轮已结束，库里只有那条用户消息） */
    private fun entries(
        reasoningRunning: Boolean,
        liveTurnId: Long? = 1,
        reasoning: String = "第一句\n第二句\n第三句",
    ): List<ProcessEntry> {
        val messages = listOf(user(1))
        return buildChatItems(
            messages = messages,
            reasoning = reasoning,
            reasoningRunning = reasoningRunning,
            liveTurnId = liveTurnId,
        ).filterIsInstance<ChatItem.TurnEntry>().firstOrNull()?.view?.entries.orEmpty()
    }

    @Test
    fun reasoningRowRunsOnlyWhileThinkingIsTheTail() {
        // 模型还在思考：行处于「运行中」形态（摘要跟着最后一行滚动 + 扫光）
        val running = reasoningEntry(entries(reasoningRunning = true))
        assertTrue(running != null && running.running)

        // 模型开始写工具调用（或正文）→ 同一行立刻变成「已结束」形态，
        // 界面据此把摘要从最后一行切回第一行 —— 这一步不该等工具行出现
        val done = reasoningEntry(entries(reasoningRunning = false))
        assertTrue(done != null && !done.running)
        assertEquals("第一句\n第二句\n第三句", done!!.text)
    }

    @Test
    fun liveTailStaysAttachedWhileTheTurnIsStillLive() {
        // 停止之后 sending 已经是 false，但 liveTurnId 还没清（AgentLoop 正在把已生成的内容落库）：
        // 这段时间里流式内容必须继续挂在这一轮上，否则就是「先消失、再长回来」的闪烁
        assertTrue(
            "the live reasoning row must stay visible until the turn is wound down",
            reasoningEntry(entries(reasoningRunning = false, liveTurnId = 1)) != null,
        )

        // liveTurnId 清掉之后（finally 里与「库为准」的消息同一次更新）才按已结束渲染
        assertTrue(
            "once the turn is wound down the live row must be gone",
            reasoningEntry(entries(reasoningRunning = false, liveTurnId = null)) == null,
        )
    }
}
