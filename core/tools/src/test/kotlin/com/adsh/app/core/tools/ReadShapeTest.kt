package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * read 的窗口整形（dsh 的 buildWindow 三条上限）。
 *
 * 上限是**内存**约束，不是文件大小约束：单行 2000 字符、单次 50 KiB、窗口外只计数。
 * 窗口对输入分块无感 —— 流式读与整读必须给出同一个结果。
 */
class ReadShapeTest {

    private fun read(
        text: String,
        offset: Int = 1,
        limit: Int = 2000,
        maxLineLength: Int = 2000,
        maxBytes: Int = 50 * 1024,
        chunk: Int = Int.MAX_VALUE,
    ): ReadWindowResult {
        val window = ReadWindow(offset, limit, maxLineLength, maxBytes)
        var from = 0
        while (from < text.length) {
            val to = minOf(from + chunk, text.length)
            window.consume(text.substring(from, to))
            from = to
        }
        return window.finish()
    }

    /** 单行超过 2000 字符就截断，并带上 dsh 的那句标注 */
    @Test
    fun longLineIsTruncatedWithDshSuffix() {
        val long = "x".repeat(5_000_000)
        val shaped = truncateReadLine(long)
        assertEquals(2000 + "... (line truncated to 2000 chars)".length, shaped.length)
        assertTrue(shaped.startsWith("x".repeat(2000)))
        assertTrue(shaped.endsWith("... (line truncated to 2000 chars)"))

        // 正好 2000 字符不动
        val exact = "y".repeat(2000)
        assertEquals(exact, truncateReadLine(exact))
    }

    /** 5 MB 单行分块喂入：行缓冲有界，输出只与两条上限有关 */
    @Test
    fun fiveMegabyteSingleLineStaysBoundedAcrossChunks() {
        val result = read("x".repeat(5_000_000), chunk = 256)
        assertEquals(1, result.totalLines)
        assertEquals(1, result.lines.size)
        assertEquals(2000 + "... (line truncated to 2000 chars)".length, result.lines[0].length)
        assertFalse("一行远不到 50 KiB 的预算，不该判成 capped", result.truncatedByBytes)
    }

    /** 整段 50 KiB 预算：装不下的行不再输出，并给出 dsh 的 Output capped 页脚 */
    @Test
    fun byteBudgetStopsAndReportsCappedFooter() {
        val lines = List(100) { "z".repeat(2000) }
        val result = read(lines.joinToString("\n"))
        assertTrue("50 KiB / 2001 字节每行 ⇒ 25 行左右", result.lines.size in 20..26)
        val text = readEnvelope("f.txt", 1, result.lines, result.totalLines, result.truncatedByBytes)
        assertTrue(text.contains("(Output capped. Showing lines 1-"))
        assertTrue(text.contains("Use offset="))
        // 正文总字节不超过预算（页脚与行号前缀按 dsh 不计入）
        val body = result.lines.joinToString("\n")
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= 50 * 1024)
    }

    /** dsh 的记账边界：maxBytes=9 时 aaaa/bbbb 正好 9 字节（首行不计换行），cccc 触发 capped */
    @Test
    fun byteBudgetMatchesDshFirstLineAccounting() {
        val result = read("aaaa\nbbbb\ncccc", maxBytes = 9)
        assertEquals(listOf("aaaa", "bbbb"), result.lines)
        assertEquals(3, result.totalLines)
        assertTrue(result.truncatedByBytes)
    }

    /**
     * dsh 的行口径：遇 `\n` 计一行、末尾残段非空才计一行、行末 `\r` 剥掉。
     * 表里同时钉住 Kotlin `lines()` 的两个偏差：末尾 `\n` 不多出空行、孤立的 `\r` 不当换行。
     */
    @Test
    fun dshLineSplitTable() {
        fun lines(text: String) = read(text).lines
        assertEquals(emptyList<String>(), lines(""))
        assertEquals(listOf("a"), lines("a\n"))
        assertEquals(listOf("a", "b"), lines("a\nb"))
        assertEquals(listOf("a", "b"), lines("a\nb\n"))
        assertEquals(listOf("a", "b"), lines("a\r\nb\r\n"))
        assertEquals(listOf("", ""), lines("\n\n"))
        assertEquals(listOf("a\rb"), lines("a\rb"))
        assertEquals(listOf("crlf"), lines("crlf\r"))
    }

    /** 块边界与行边界无关：任意切块喂入，行、总数、截断标记都与整串一致 */
    @Test
    fun chunkBoundariesDoNotChangeLineSplitting() {
        val text = "one\ntwo\r\nthree\nfour"
        val whole = read(text)
        for (chunk in 1..5) {
            val chunked = read(text, chunk = chunk)
            assertEquals("chunk=" + chunk, whole.lines, chunked.lines)
            assertEquals(whole.totalLines, chunked.totalLines)
            assertEquals(whole.truncatedByBytes, chunked.truncatedByBytes)
        }
    }

    @Test
    fun offsetAndLimitWindowWhileTotalLinesStaysExact() {
        val text = (1..10).joinToString("\n") { "line " + it }
        val result = read(text, offset = 3, limit = 4, chunk = 2)
        assertEquals(listOf("line 3", "line 4", "line 5", "line 6"), result.lines)
        assertEquals(10, result.totalLines)
    }

    /** 没超预算、后面还有行 → dsh 的 continue 页脚；读到底 → End of file */
    @Test
    fun footersMatchDshThreeCases() {
        val lines = List(10) { "line " + it }
        val mid = read(lines.joinToString("\n"), limit = 4)
        assertEquals(4, mid.lines.size)
        assertFalse(mid.truncatedByBytes)
        assertTrue(
            readEnvelope("f.txt", 1, mid.lines, mid.totalLines)
                .contains("(Showing lines 1-4 of 10. Use offset=5 to continue.)"),
        )

        val all = read(lines.joinToString("\n"))
        assertTrue(readEnvelope("f.txt", 1, all.lines, all.totalLines).contains("(End of file - total 10 lines)"))
    }
}
