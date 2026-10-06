package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * read 的输出整形（dsh 的两条上限）。
 *
 * 第 197 轮真机复现的 OOM 就是从这条路上来的：ADSH 原来只限**行数**（2000 行），一行可以几 MB
 * —— 那个字符串会进日志、进上下文、进 Compose 的文本排版，把 256MB 的 Java 堆打爆。
 * dsh 的 read 一直是 **2000 字符/行 + 50 KiB/次**两条上限（read-render.ts 的
 * READ_MAX_LINE_LENGTH / READ_MAX_BYTES），这里把口径钉住。
 */
class ReadShapeTest {

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

    /** 5 MB 单行文件：一次 read 只回 2000 字符 + 标注 —— 输出与文件大小无关 */
    @Test
    fun fiveMegabyteSingleLineYieldsTwoKilochars() {
        val lines = listOf("x".repeat(5_000_000))
        val slice = readSlice(lines, offset = 1, limit = 2000)
        assertEquals(1, slice.lines.size)
        assertEquals(2000 + "... (line truncated to 2000 chars)".length, slice.lines[0].length)
        assertFalse("一行远不到 50 KiB 的预算，不该判成 capped", slice.truncatedByBytes)
    }

    /** 整段 50 KiB 预算：装不下的行不再输出，并给出 dsh 的 Output capped 页脚 */
    @Test
    fun byteBudgetStopsAndReportsCappedFooter() {
        val lines = List(100) { "z".repeat(2000) }
        val slice = readSlice(lines, offset = 1, limit = 2000)
        assertTrue("50 KiB / 2001 字节每行 ⇒ 25 行左右", slice.lines.size in 20..26)
        val text = readEnvelope("f.txt", 1, slice.lines, lines.size, slice.truncatedByBytes)
        assertTrue(text.contains("(Output capped. Showing lines 1-"))
        assertTrue(text.contains("Use offset="))
        // 正文总字节不超过预算（页脚与行号前缀按 dsh 不计入）
        val body = slice.lines.joinToString("\n") { it }
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= 50 * 1024)
    }

    /** 没超预算、后面还有行 → dsh 的 continue 页脚；读到底 → End of file */
    @Test
    fun footersMatchDshThreeCases() {
        val lines = List(10) { "line " + it }
        val slice = readSlice(lines, offset = 1, limit = 4)
        assertEquals(4, slice.lines.size)
        assertFalse(slice.truncatedByBytes)
        val mid = readEnvelope("f.txt", 1, slice.lines, lines.size)
        assertTrue(mid.contains("(Showing lines 1-4 of 10. Use offset=5 to continue.)"))

        val all = readSlice(lines, offset = 1, limit = 2000)
        val end = readEnvelope("f.txt", 1, all.lines, lines.size)
        assertTrue(end.contains("(End of file - total 10 lines)"))
    }
}
