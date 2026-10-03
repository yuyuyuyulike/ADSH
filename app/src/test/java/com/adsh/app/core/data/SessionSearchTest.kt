package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 会话搜索的两段纯逻辑（[likeLiteralPattern] / [snippetAround]）。
 *
 * 前者决定用户输入的 `%` 会不会变成「匹配任意内容」，后者是搜索结果行唯一看到的文字。
 * 两处以前埋在 [ConversationRepository.searchSessions] 里，零用例。
 */
class SessionSearchTest {

    /** 一个反斜杠（写成变量免得满屏 \\） */
    private val bs = "\\"
    private val pct = "%"
    private val us = "_"

    // ------------------------------------------------------------ LIKE 字面量模式

    @Test
    fun `百分号与下划线都要转义`() {
        assertEquals("50" + bs + pct, likeLiteralPattern("50" + pct))
        assertEquals(bs + us, likeLiteralPattern(us))
        assertEquals("a" + bs + us + "b" + bs + pct, likeLiteralPattern("a" + us + "b" + pct))
    }

    /**
     * **顺序敏感**：反斜杠必须最先转义。输入「一个反斜杠 + 一个百分号」的正确结果是
     * 三个反斜杠 + 百分号；若先转义 `%`，第二步会把刚加上的反斜杠再转义一遍，得到四个。
     */
    @Test
    fun `反斜杠最先转义：顺序写反会多出一层`() {
        assertEquals(bs + bs + bs + pct, likeLiteralPattern(bs + pct))
        assertNotEquals(bs + bs + bs + bs + pct, likeLiteralPattern(bs + pct))
        assertEquals(bs + bs, likeLiteralPattern(bs))
    }

    @Test
    fun `普通文字原样返回`() {
        assertEquals("deepseek", likeLiteralPattern("deepseek"))
        assertEquals("", likeLiteralPattern(""))
        assertEquals("中文 空格", likeLiteralPattern("中文 空格"))
    }

    // ------------------------------------------------------------ 命中处的片段

    @Test
    fun `命中在中间：两侧都有省略号`() {
        assertEquals("…cdefgh…", snippetAround("abcdefghij", "ef", radius = 2))
    }

    @Test
    fun `命中在开头：没有前导省略号`() {
        assertEquals("abcd…", snippetAround("abcdefghij", "ab", radius = 2))
    }

    @Test
    fun `命中在结尾：没有尾随省略号`() {
        assertEquals("…ghij", snippetAround("abcdefghij", "ij", radius = 2))
    }

    @Test
    fun `正文比窗口短就整段返回，不加省略号`() {
        assertEquals("abc", snippetAround("abc", "b", radius = 2))
    }

    @Test
    fun `换行与连续空白压成单空格`() {
        assertEquals("a b c", snippetAround("a\n\n  b\t c", "b"))
    }

    @Test
    fun `匹配大小写不敏感，片段保留原文大小写`() {
        assertEquals("…GPT…", snippetAround("xx GPT-6 yy", "gpt", radius = 0))
    }

    /** DAO 那边命中了、这里压平后对不上：退回正文开头，**不加省略号**（宁可显示开头，也不给空片段） */
    @Test
    fun `找不到 needle 时退回开头一段`() {
        assertEquals("abcd", snippetAround("abcdefghij", "zz", radius = 2))
    }

    @Test
    fun `radius 为 0 时片段就是命中本身`() {
        assertEquals("…ef…", snippetAround("abcdefghij", "ef", radius = 0))
    }
}
