package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考正文的切块（[reasoningChunks]）：块边界优先（空行、不切围栏、前缀稳定），单块过大时按行边界兜底。
 * 边界类型决定相邻两块之间的间距口径（[ReasoningBoundary]），也决定拼回去是否与原文逐字一致。
 */
class ReasoningChunksTest {

    /** 一段普通段落：没有块标记、没有空行 */
    private fun para(index: Int, width: Int = 300): String = "第" + index + "段" + "字".repeat(width)

    /** 每段 303 字 + 空行：4 段才超过 answerChunks 的 2×预算早退阈值（960 字） */
    private fun paragraphs(count: Int): String = (1..count).joinToString("\n\n") { para(it) }

    @Test
    fun `短正文不切`() {
        val chunks = reasoningChunks("第一行\n第二行")
        assertEquals(1, chunks.size)
        assertEquals(ReasoningBoundary.NONE, chunks.single().after)
    }

    @Test
    fun `只在空行切_边界是 BLOCK_末块是 NONE`() {
        val text = paragraphs(4)
        val chunks = reasoningChunks(text)
        assertTrue("1200 字应当被切开", chunks.size >= 2)
        assertEquals(ReasoningBoundary.NONE, chunks.last().after)
        chunks.dropLast(1).forEach { assertEquals(ReasoningBoundary.BLOCK, it.after) }
        // 切点是空行：按块边界拼回去必须逐字等于原文（空行本身被 renderer 的块间距替代）
        assertEquals(text, chunks.joinToString("\n\n") { it.text })
    }

    @Test
    fun `前缀稳定_尾部追加不改变已经切出来的块`() {
        val before = reasoningChunks(paragraphs(4))
        val after = reasoningChunks(paragraphs(5))
        assertTrue(after.size > before.size)
        before.dropLast(1).forEachIndexed { index, chunk -> assertEquals(chunk.text, after[index].text) }
    }

    @Test
    fun `不切进围栏代码块`() {
        val code = "```kotlin\n" + (1..30).joinToString("\n") { "val x$it = $it" } + "\n\n\n" + "val tail = 1\n```"
        val text = para(1, 400) + "\n\n" + code + "\n\n" + para(2, 400)
        val chunks = reasoningChunks(text, budget = 40)
        val fence = chunks.first { it.text.contains("```kotlin") }
        assertTrue("围栏不能被劈开：代码块的收尾必须在同一块里", fence.text.contains("val tail = 1"))
        assertEquals(1, chunks.count { it.text.contains("val tail = 1") })
    }

    @Test
    fun `单段超长时按行边界兜底_LINE`() {
        // 一行一个硬换行、没有空行：answerChunks 切不动，必须靠行边界兜底
        val text = (1..200).joinToString("\n") { "第 $it 行 " + "字".repeat(20) }
        val chunks = reasoningChunks(text)
        assertTrue("应当被行边界切成多块", chunks.size >= 3)
        assertEquals(ReasoningBoundary.NONE, chunks.last().after)
        chunks.dropLast(1).forEach { assertEquals(ReasoningBoundary.LINE, it.after) }
        // 行边界切点：按行拼回去逐字等于原文
        assertEquals(text, chunks.joinToString("\n") { it.text })
    }

    @Test
    fun `巨型围栏块切不动时保持一块`() {
        val text = "```\n" + (1..400).joinToString("\n") { "code line $it" } + "\n```"
        val chunks = reasoningChunks(text)
        assertEquals(1, chunks.size)
        assertEquals(ReasoningBoundary.NONE, chunks.single().after)
    }

    @Test
    fun `巨型列表切不动时保持一块`() {
        // 切进列表会丢编号 / 缩进：宁可整块（残余风险，见 ChatItem.ReasoningBody 的注释）
        val text = (1..400).joinToString("\n") { "- 列表项 $it " + "字".repeat(10) }
        val chunks = reasoningChunks(text)
        assertEquals(1, chunks.size)
    }
}
