package com.adsh.app.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式 Markdown 的增量块解析：**每个增长前缀的结果必须等于全量解析**。
 * 这条断言就是「冻结尾块」的安全网 —— 冻结错了（把还会变的块冻住）这里立刻红。
 */
class MarkdownStreamParserTest {

    private fun assertPrefixesMatchFull(document: String) {
        val parser = MarkdownStreamParser()
        for (end in 1..document.length) {
            val prefix = document.substring(0, end)
            assertEquals(
                "前缀长度 " + end + " 的增量解析结果与全量解析不一致",
                parseMarkdown(prefix),
                parser.update(prefix),
            )
        }
    }

    @Test
    fun 段落与标题的增量解析与全量一致() {
        assertPrefixesMatchFull(
            "第一段第一行\n第一段第二行\n\n## 标题\n\n第二段，带 **加粗** 与 `code`。\n\n\n最后一段。",
        )
    }

    @Test
    fun 列表的增量解析与全量一致() {
        assertPrefixesMatchFull(
            "前言\n\n- 甲\n- 乙\n  - 嵌套一\n  - 嵌套二\n- 丙\n\n1. 一\n2. 二\n\n尾段",
        )
    }

    @Test
    fun 围栏代码块的增量解析与全量一致() {
        assertPrefixesMatchFull(
            "说明\n\n```kotlin\nfun main() {\n  println(1)\n}\n```\n\n结束",
        )
    }

    @Test
    fun 表格与引用块的增量解析与全量一致() {
        assertPrefixesMatchFull(
            "| 名称 | 值 |\n| --- | --- |\n| 甲 | 1 |\n\n> 引用第一行\n> 引用第二行\n\n尾段",
        )
    }

    @Test
    fun 非追加输入会重置而不是拼出脏块() {
        val parser = MarkdownStreamParser()
        parser.update("甲\n\n乙\n")
        val replaced = parser.update("丙\n\n丁\n")
        assertEquals(parseMarkdown("丙\n\n丁\n"), replaced)
    }
}
