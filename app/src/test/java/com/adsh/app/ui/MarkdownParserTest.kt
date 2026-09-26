package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 分块解析：列表嵌套、任务列表、代码围栏语言、引用、表格、图片。
 * 这些是「看齐 dsh」时新加的能力（旧实现把它们全拍平了）。
 */
class MarkdownParserTest {

    @Test
    fun `无序列表按缩进嵌套`() {
        val blocks = parseMarkdown(
            """
            - 第一层甲
              - 第二层
              - 第二层乙
            - 第一层乙
            """.trimIndent(),
        )
        val list = blocks.filterIsInstance<MdBlock.Bullets>().single()
        assertEquals(false, list.ordered)
        assertEquals(2, list.items.size)
        assertEquals("第一层甲", list.items[0].text)
        assertEquals(2, list.items[0].children.filterIsInstance<MdBlock.Bullets>().single().items.size)
        assertEquals("第一层乙", list.items[1].text)
        assertTrue(list.items[1].children.isEmpty())
    }

    @Test
    fun `有序列表带起始序号`() {
        val list = parseMarkdown(
            """
            3. 三
            4. 四
            """.trimIndent(),
        ).filterIsInstance<MdBlock.Bullets>().single()
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(listOf("三", "四"), list.items.map { it.text })
    }

    @Test
    fun `任务列表解析复选框并去掉标记`() {
        val items = parseMarkdown(
            """
            - [x] 已完成
            - [ ] 未完成
            - 普通项
            """.trimIndent(),
        ).filterIsInstance<MdBlock.Bullets>().single().items
        assertEquals(true, items[0].checked)
        assertEquals("已完成", items[0].text)
        assertEquals(false, items[1].checked)
        assertEquals(null, items[2].checked)
        assertEquals("普通项", items[2].text)
    }

    @Test
    fun `围栏代码块带语言`() {
        val code = parseMarkdown(
            """
            ```bash
            echo hi
            echo there
            ```
            """.trimIndent(),
        ).filterIsInstance<MdBlock.Code>().single()
        assertEquals("bash", code.lang)
        assertEquals("echo hi\necho there", code.text)
    }

    @Test
    fun `波浪号围栏与无语言围栏`() {
        val code = parseMarkdown("~~~\nplain\n~~~").filterIsInstance<MdBlock.Code>().single()
        assertEquals(null, code.lang)
        assertEquals("plain", code.text)
    }

    @Test
    fun `引用块内容按块解析`() {
        val quote = parseMarkdown(
            """
            > 一段引用
            >
            > - 引用里的列表
            """.trimIndent(),
        ).filterIsInstance<MdBlock.Quote>().single()
        assertTrue(quote.blocks.any { it is MdBlock.Paragraph })
        assertTrue(quote.blocks.any { it is MdBlock.Bullets })
    }

    @Test
    fun `表格需要分隔行`() {
        val table = parseMarkdown(
            """
            | 名称 | 值 |
            | --- | --- |
            | 甲 | 1 |
            | 乙 | 2 |
            """.trimIndent(),
        ).filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("名称", "值"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("乙", "2"), table.rows[1])

        // 没有分隔行的 | 开头文本只是一段普通文字
        val plain = parseMarkdown("| 不是表格 |")
        assertTrue(plain.none { it is MdBlock.Table })
    }

    @Test
    fun `单独成段的图片解析成图片块`() {
        val image = parseMarkdown("![截图](/tmp/shot.png)").filterIsInstance<MdBlock.Image>().single()
        assertEquals("截图", image.alt)
        assertEquals("/tmp/shot.png", image.src)
    }

    @Test
    fun `段落与标题的边界`() {
        val blocks = parseMarkdown("## 标题\n\n正文\n第二行\n\n---\n\n尾段")
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(2, (blocks[0] as MdBlock.Heading).level)
        // 段落里的软换行按空格合并（与 CommonMark 的 lazy continuation 一致）
        assertEquals("正文 第二行", (blocks[1] as MdBlock.Paragraph).text)
        assertTrue(blocks[2] is MdBlock.Rule)
        assertEquals("尾段", (blocks[3] as MdBlock.Paragraph).text)
    }

    @Test
    fun `段落后面的列表不会被吞进段落`() {
        val blocks = parseMarkdown("说明：\n- 甲\n- 乙")
        assertEquals("说明：", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals(2, blocks.filterIsInstance<MdBlock.Bullets>().single().items.size)
    }
}
