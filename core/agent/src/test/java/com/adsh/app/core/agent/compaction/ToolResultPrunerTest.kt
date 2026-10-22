package com.adsh.app.core.agent.compaction

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果剪枝：确定性 head + 省略标记 + tail、只动可压区间里的 tool 行、不破坏行身份与工具配对。
 */
class ToolResultPrunerTest {

    private fun row(
        id: Long,
        role: String,
        content: String,
        name: String? = null,
        toolCallId: String? = null,
    ): MessageEntity =
        MessageEntity(
            id = id,
            conversationId = 1L,
            role = role,
            content = content,
            name = name,
            toolCallId = toolCallId,
            createdAt = 0,
        )

    @Test
    fun `不超过 8192 码点的正文不动`() {
        assertNull(ToolResultPruner.pruneContent("x".repeat(8192)))
        assertNull(ToolResultPruner.pruneContent(""))
    }

    @Test
    fun `超长正文保留头 4096 尾 1024、中间换成省略标记`() {
        val content = "H".repeat(4096) + "M".repeat(5000) + "T".repeat(1024)
        val pruned = ToolResultPruner.pruneContent(content)!!
        assertEquals("\n\n[... tool result middle pruned ...]\n\n", ToolResultPruner.PRUNE_MARKER)
        assertEquals(
            "H".repeat(4096) + ToolResultPruner.PRUNE_MARKER + "T".repeat(1024),
            pruned,
        )
        assertTrue(pruned.length < content.length)
        // 确定性：同一输入两次结果相同
        assertEquals(pruned, ToolResultPruner.pruneContent(content))
    }

    @Test
    fun `按码点切、不切开代理对`() {
        // 每个 😀 = 1 码点 / 2 个 UTF-16 code unit：按 code unit 切会留下孤立代理项
        val content = "😀".repeat(10_000)
        val pruned = ToolResultPruner.pruneContent(content)!!
        assertEquals(
            4096 + ToolResultPruner.PRUNE_MARKER.length + 1024,
            pruned.codePointCount(0, pruned.length),
        )
        assertTrue(pruned.startsWith("😀".repeat(4096)))
        assertTrue(pruned.endsWith("😀".repeat(1024)))
    }

    @Test
    fun `只剪可压区间里的 tool 行`() {
        val long = "x".repeat(20_000)
        val range = CompactionRange(
            prefix = listOf(row(1, "sysprompt", long)),
            older = listOf(
                row(2, "user", long),
                row(3, "tool", long, name = "bash", toolCallId = "call_1"),
                row(4, "tool", "short", name = "bash", toolCallId = "call_2"),
                row(5, "assistant", long),
            ),
            keep = listOf(row(6, "tool", long, name = "bash", toolCallId = "call_3")),
        )
        val result = ToolResultPruner.prune(range)
        assertEquals(1, result.replacements.size)
        assertEquals(3L, result.replacements.single().id)
        assertTrue(result.applied)
    }

    @Test
    fun `替换只改正文、行身份与配对字段原样保留`() {
        val toolRow = row(7, "tool", "y".repeat(20_000), name = "run_code", toolCallId = "call_7")
        val range = CompactionRange(
            prefix = emptyList(),
            older = listOf(toolRow),
            keep = emptyList(),
        )
        val replacement = ToolResultPruner.prune(range).replacements.single()
        val applied = ToolResultPruner.apply(listOf(toolRow), listOf(replacement)).single()
        assertEquals(7L, applied.id)
        assertEquals("tool", applied.role)
        assertEquals("run_code", applied.name)
        assertEquals("call_7", applied.toolCallId)
        assertTrue(applied.content.contains(ToolResultPruner.PRUNE_MARKER))
        assertEquals(replacement.content, applied.content)
    }
}
