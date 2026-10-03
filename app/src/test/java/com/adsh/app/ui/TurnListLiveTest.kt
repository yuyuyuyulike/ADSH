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
 * 第 111 轮起流式尾巴的**去留只有一道门**：`reasoningActive` / `streamingActive`（与库里的重读
 * 出自同一次状态更新，见 ChatViewModel 的 `SessionBody.Step` 分支），不再拿正文内容和库里那行比对。
 *
 * 这里的判据都落在 `buildChatItems` 上：界面看到什么，完全由它决定。
 */
class TurnListLiveTest {

    private fun user(id: Long = 1) =
        MessageEntity(id = id, conversationId = 1, role = "user", content = "u$id", createdAt = id * 10)

    private fun assistant(id: Long, text: String) = MessageEntity(
        id = id,
        conversationId = 1,
        role = "assistant",
        content = text,
        createdAt = id * 10,
    )

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

    /**
     * 思考尾巴的**原子门**（第 95 轮）：思考现在和正文一样走 33ms 采样 + 平滑显现，
     * 落库那一帧采样值可能还停在前缀上 —— 那时「与库里那一行逐字相等」判不中，
     * 同一段思考会挂两行（列表长高又拽回 = 闪一下）。`reasoningActive` 与 messages 同一次更新，
     * 它一为 false 尾巴就必须消失。
     */
    @Test
    fun reasoningTailIsDroppedAtomicallyWhenTheStepIsCommitted() {
        val messages = listOf(user(1))
        fun live(reasoningActive: Boolean, reasoning: String): List<ProcessEntry> = buildChatItems(
            messages = messages,
            reasoning = reasoning,
            reasoningRunning = true,
            liveTurnId = 1,
            reasoningActive = reasoningActive,
        ).filterIsInstance<ChatItem.TurnEntry>().firstOrNull()?.view?.entries.orEmpty()

        // 还在流：尾巴挂着
        assertTrue(reasoningEntry(live(reasoningActive = true, reasoning = "第一步")) != null)
        // 这一步已经落库（streaming / reasoning 与 messages 在同一次状态更新里被清空）：
        // 采样值哪怕还留着半截，也不能再挂一行
        assertTrue(reasoningEntry(live(reasoningActive = false, reasoning = "第一")) == null)
    }

    /**
     * 尾巴的去留**只看门、不看正文内容**（第 111 轮把「与库里那行逐字比对」删了）。
     *
     * 库里已经有一段一模一样的正文、而门还开着（采样值还停在这一段的开头几帧）时，尾巴照挂 ——
     * 判据不看内容，就不会出现「采样值落后最后几个 token → 判不中 → 同一段文字挂两行」的闪烁；
     * 门一关（`SessionBody.Step` 落日志，与库里的重读同一次状态更新），尾巴立刻消失。
     */
    @Test
    fun theTextTailIsDecidedByTheGateNotByTextEquality() {
        val messages = listOf(user(1), assistant(2, "同一句话"))
        fun texts(streamingActive: Boolean) = buildChatItems(
            messages = messages,
            streaming = "同一句话",
            liveTurnId = 1,
            streamingActive = streamingActive,
        ).filterIsInstance<ChatItem.TurnEntry>().first().view.entries
            .filterIsInstance<ProcessEntry.Text>()

        assertEquals("门开着：库里那一行 + 还没交班的尾巴", 2, texts(streamingActive = true).size)
        assertEquals("门关上：只剩库里那一行", 1, texts(streamingActive = false).size)
    }
}
