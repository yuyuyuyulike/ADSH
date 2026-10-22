package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 折叠思考摘要的段首行语义（dsh 的 ReasoningRow.latestCompletedParagraphFirstLine）。
 *
 * 这些用例就是 dsh 那 10 行 JS 的逐条翻译：段落按空行切；只有段首行的换行到了才算完成；
 * 最后一段未完成时沿用上一段的结果。
 */
class ReasoningSummaryTest {

    @Test
    fun `空文本没有摘要`() {
        assertEquals("", latestCompletedParagraphFirstLine(""))
    }

    @Test
    fun `首行还没换行时没有摘要`() {
        assertEquals("", latestCompletedParagraphFirstLine("正在思考"))
    }

    @Test
    fun `首行换行到了就取首行`() {
        assertEquals("正在思考", latestCompletedParagraphFirstLine("正在思考\n"))
    }

    @Test
    fun `同一段里后面的行不算摘要`() {
        assertEquals("甲", latestCompletedParagraphFirstLine("甲\n乙\n"))
    }

    @Test
    fun `推进到最新一个已完成段落的首行`() {
        assertEquals("乙", latestCompletedParagraphFirstLine("甲\n\n乙\n"))
    }

    @Test
    fun `最后一段首行未完成时沿用上一段`() {
        assertEquals("甲", latestCompletedParagraphFirstLine("甲\n\n乙"))
    }

    @Test
    fun `CRLF 段落分隔同样生效`() {
        assertEquals("乙", latestCompletedParagraphFirstLine("甲\r\n\r\n乙\r\n"))
    }

    @Test
    fun `连续空行与缩进空白都算段落分隔`() {
        assertEquals("乙", latestCompletedParagraphFirstLine("  甲  \n\n  \n乙\n"))
    }

    @Test
    fun `摘要保留原文标记由调用方去掉`() {
        assertEquals("**粗**", latestCompletedParagraphFirstLine("**粗**\n"))
    }
}
