package com.adsh.app.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 终端输出清洗流水线（[TerminalText]）—— 第六十九轮。
 *
 * 这一层是终端页唯一有状态、且直接决定「屏幕上看到什么」的逻辑：PTY 出来的是**原始字节流**，
 * 一次 read 会从任意位置切开（多字节字符、`\r\n`、转义序列都可能被劈成两半）。
 * 下面每一条都对应真机上见过的一类乱码：
 *  - 方框：一个汉字被切在两次 read 之间；
 *  - `[0;32m` 这种垃圾：这一页没有 VT 解析器，转义序列要自己吃掉；
 *  - apt / pip 的进度条：`\r` 是「回到行首重写」，不是换行。
 */
class TerminalTextTest {

    private fun feed(vararg chunks: String): String {
        val terminal = TerminalText()
        chunks.forEach { chunk ->
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            terminal.feed(bytes, bytes.size)
        }
        return terminal.snapshot()
    }

    @Test
    fun multibyteCharacterSplitAcrossReadsSurvives() {
        // 「终」= E7 BB 88：从中间切开喂进去，两半都不该变成 U+FFFD
        val bytes = "终".toByteArray(Charsets.UTF_8)
        val terminal = TerminalText()
        terminal.feed(bytes.copyOfRange(0, 1), 1)
        assertEquals("", terminal.snapshot())
        terminal.feed(bytes.copyOfRange(1, bytes.size), bytes.size - 1)
        assertEquals("终", terminal.snapshot())
    }

    @Test
    fun ansiSequencesAreEatenNotPrinted() {
        // bash 的彩色提示符 + 清屏 + 光标移动 + 终端标题（OSC）
        assertEquals(
            "~ $ ls",
            feed("\u001b[0;32m~ $\u001b[0m \u001b[2K\u001b[1G", "ls"),
        )
        assertEquals("done", feed("\u001b]0;title\u0007done"))
        assertEquals("st", feed("\u001b]0;title\u001b\\st"))
    }

    @Test
    fun escapeSequenceSplitAcrossReadsIsStillEaten() {
        assertEquals("ok", feed("\u001b[0;3", "2mok"))
        assertEquals("ok", feed("\u001b]8;;http://x\u0007", "ok"))
    }

    @Test
    fun carriageReturnRewritesTheCurrentLine() {
        // apt / pip 的进度条：同一行反复重写，屏幕上只该留最后一次
        assertEquals("100%", feed("10%\r", "50%\r", "100%"))
        // 新内容比旧的短：整行替换，不是叠加
        assertEquals("ab", feed("abcdef\rab"))
    }

    @Test
    fun carriageReturnNewlineSplitAcrossReadsIsOneNewline() {
        assertEquals("a\nb", feed("a\r", "\nb"))
        // 单独一个 \r 结尾：还没决定是换行还是重写，此时什么都不该发生
        assertEquals("a", feed("a\r"))
        // 重写只杀当前行，不动更早的行
        assertEquals("one\ntwo", feed("one\nxxx\rtwo"))
    }

    @Test
    fun backspaceAndControlCharacters() {
        assertEquals("ab", feed("abc\b"))
        assertEquals("ab", feed("a\u0007b"))          // BEL 丢掉，不画方框
        assertEquals("a\tb", feed("a\tb"))            // tab 保留
    }

    @Test
    fun transcriptChunksSplitsOnLinesWithoutTrailingNewline() {
        val head = (1..450).joinToString("\n") { "l$it" }
        val chunks = transcriptChunks(head)
        assertEquals(3, chunks.size)
        assertEquals(200, chunks[0].split("\n").size)
        assertEquals(200, chunks[1].split("\n").size)
        assertEquals(50, chunks[2].split("\n").size)
        assertEquals(head, chunks.joinToString("\n"))
        assertEquals(emptyList<String>(), transcriptChunks(""))
    }
}
