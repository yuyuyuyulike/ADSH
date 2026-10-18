package com.adsh.app.core.agent

import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.ToolCallFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工具调用与工具结果的配对（history 装配）。
 *
 * 背景是真机上的一类故障：有些 OpenAI 兼容网关（实测 qwen 的 token-plan 端点）流式返回的
 * tool_calls **不带 id**，assistant 行与结果行的 id 都是空串。只按 id 配对的话，每一次调用都会
 * 被判成「有调用、没结果」，装配时补一条 TOOL_OUTCOME_UNKNOWN（"The tool call was interrupted
 * after it was recorded..."）—— 工具其实跑完了，模型却被告知中断，于是反复重试。
 */
class ToolResultPairingTest {

    private fun call(id: String?): ToolCall =
        ToolCall(id = id, function = ToolCallFunction(name = "run_code", arguments = "{}"))

    private fun result(id: String?, content: String): MessageEntity = MessageEntity(
        id = 0,
        conversationId = 1,
        role = "tool",
        content = content,
        toolCallId = id,
        name = "run_code",
        createdAt = 0,
    )

    @Test
    fun idsMatchExactlyEvenWhenOrderIsSwapped() {
        val calls = listOf(call("a"), call("b"))
        val results = listOf(result("b", "second"), result("a", "first"))
        val paired = pairToolResults(calls, results)
        assertEquals("first", paired[0]?.content)
        assertEquals("second", paired[1]?.content)
    }

    @Test
    fun blankIdsFallBackToOrder() {
        // qwen 的真实形状：两边都是空串 —— 位置就是配对依据
        val calls = listOf(call(""), call(""))
        val results = listOf(result("", "first"), result("", "second"))
        val paired = pairToolResults(calls, results)
        assertEquals("first", paired[0]?.content)
        assertEquals("second", paired[1]?.content)
    }

    @Test
    fun nullIdsBehaveLikeBlankOnes() {
        val calls = listOf(call(null))
        val results = listOf(result(null, "only"))
        assertEquals("only", pairToolResults(calls, results)[0]?.content)
    }

    @Test
    fun aCallWithoutAnyResultStaysNull() {
        val calls = listOf(call("a"), call("b"))
        val results = listOf(result("a", "first"))
        val paired = pairToolResults(calls, results)
        assertEquals("first", paired[0]?.content)
        assertNull(paired[1])
    }

    @Test
    fun aResultIsConsumedOnlyOnce() {
        // 同一个 id 出现两次（残局）：第一次按 id 配上，多出来的进顺序队列，不会被重复使用
        val calls = listOf(call("a"), call("b"), call(""))
        val results = listOf(result("a", "one"), result("a", "dup"))
        val paired = pairToolResults(calls, results)
        assertEquals(listOf("one", "dup", null), paired.map { it?.content })
    }

    @Test
    fun mixedIdsAndBlankIdsStillPairEverything() {
        val calls = listOf(call("a"), call(""), call("c"))
        val results = listOf(result("", "blank"), result("a", "one"), result("c", "three"))
        val paired = pairToolResults(calls, results)
        assertEquals(listOf("one", "blank", "three"), paired.map { it?.content })
    }

    @Test
    fun everyCallQualifiesEvenWhenThereAreNoResults() {
        val calls = listOf(call("a"), call(""))
        val paired = pairToolResults(calls, emptyList())
        assertEquals(2, paired.size)
        assertNull(paired[0])
        assertNull(paired[1])
    }
}
