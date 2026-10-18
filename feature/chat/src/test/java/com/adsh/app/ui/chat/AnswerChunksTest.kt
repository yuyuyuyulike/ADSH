package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [answerChunks] 的判据：长回答要能被切成多块，且切开之后**渲染结果不变**。
 * 三条口径各有用例：切点只落在「上下都是普通段落」的空行上（标题 / 列表 / 表格 / 围栏代码块旁边不切）；
 * **前缀稳定**：追加正文（流式）不会改动前面的块（否则行 key 一抖就整块重建）；切不动时退回单块。
 */
class AnswerChunksTest {

    /** 一段「安全」的长正文：n 个普通段落，段间一个空行 */
    private fun paragraphs(n: Int, prefix: String = "这是一个足够长的普通段落"): String =
        (1..n).joinToString("\n\n") { "$prefix$it，用来把这一块撑到预算之上。" }

    @Test
    fun shortAnswerStaysOneChunk() {
        val text = paragraphs(2)
        assertEquals(listOf(text), answerChunks(text, budget = 320))
    }

    @Test
    fun longAnswerIsSplitIntoSeveralChunks() {
        val text = paragraphs(12)
        val chunks = answerChunks(text, budget = 120)
        assertTrue("应当切成多块，实际 " + chunks.size, chunks.size > 1)
        // 每一块都是原文里的一段
        chunks.forEach { assertTrue("每一块都应当来自原文", text.contains(it.trim())) }
        // 块序 = 原文顺序
        var cursor = 0
        chunks.forEach { chunk ->
            val at = text.indexOf(chunk, cursor)
            assertTrue("块的顺序不能变", at >= cursor)
            cursor = at
        }
    }

    @Test
    fun chunksArePrefixStableWhileStreaming() {
        val head = paragraphs(10)
        val grown = head + "\n\n" + paragraphs(4, prefix = "流式追加的段落")
        val before = answerChunks(head, budget = 100)
        val after = answerChunks(grown, budget = 100)
        assertTrue(before.size > 1)
        // 前面的块逐字不变（只有最后一块会因为追加而变长）
        before.dropLast(1).forEachIndexed { index, chunk ->
            assertEquals("第 " + index + " 块不该因为追加而变", chunk, after[index])
        }
    }

    @Test
    fun neverSplitsInsideFencedCode() {
        // 围栏里的空行不是切点：整条回答只有一处「安全」的空行，剩下的都在代码块里
        val code = (1..30).joinToString("\n\n") { "int x$it = $it;" }
        val text = paragraphs(3) + "\n\n```kotlin\n" + code + "\n```\n\n" + paragraphs(3)
        val chunks = answerChunks(text, budget = 60)
        chunks.forEach { chunk ->
            val fences = chunk.split("\n").count { it.trimStart().startsWith("```") }
            assertEquals("每一块的围栏必须配平：" + chunk.take(20), 0, fences % 2)
        }
    }

    @Test
    fun doesNotCutNextToBlocksWithTheirOwnGaps() {
        // 标题 / 列表 / 引用 / 表格旁边不切：整条回答只有普通段落之间才有切点，
        // 于是「标题 + 列表」这一段永远和它前面的段落待在同一个块里
        val text = paragraphs(3) + "\n\n## 一个小标题\n\n- 第一项\n- 第二项\n\n" + paragraphs(6)
        val chunks = answerChunks(text, budget = 60)
        val head = chunks.first()
        assertTrue("标题不能被切到块首（否则标题上边距会丢）", !head.trimStart().startsWith("##"))
    }

    @Test
    fun fallsBackToSingleChunkWhenNothingIsSafe() {
        // 全是列表项：没有「上下都是普通段落」的空行
        val text = (1..40).joinToString("\n\n") { "- 列表项 $it" }
        assertEquals(listOf(text), answerChunks(text, budget = 60))
    }
}
