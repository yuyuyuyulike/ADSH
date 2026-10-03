package com.adsh.app.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端输入行的纯逻辑（R10 第一步：把它们从 548 行的 TerminalPanel 里拆出来）。
 *
 * 为什么先拆这一层：这几个决定原来是 @Composable 里的局部函数，**一行都测不了** ——
 * 纯 JVM 单测起不了 Compose，离线缓存里也没有 Robolectric。而它们恰恰是用户最常撞见的问题：
 * 退格删不掉、↑ 翻不出历史、Ctrl-C 不生效。拆成纯函数之后这里直接打表。
 */
class TerminalInputTest {

    // ---------- 历史翻阅（↑ / ↓） ----------

    @Test
    fun `历史为空时不动`() {
        val m = nextHistory(history = emptyList(), index = -1, live = "", current = "abc", delta = -1)
        assertEquals("abc", m.draft)
        assertEquals(-1, m.index)
    }

    @Test
    fun `第一次往上翻进历史末条`() {
        val m = nextHistory(listOf("ls", "pwd"), index = -1, live = "", current = "一半", delta = -1)
        assertEquals("pwd", m.draft)
        assertEquals(1, m.index)
        // 手里那半行被记住，翻回底时要还给它
        assertEquals("一半", m.live)
    }

    @Test
    fun `继续往上翻到更早的条目`() {
        val m = nextHistory(listOf("ls", "pwd"), index = 1, live = "一半", current = "pwd", delta = -1)
        assertEquals("ls", m.draft)
        assertEquals(0, m.index)
    }

    @Test
    fun `已经到最早一条再往上翻仍停在那里`() {
        val m = nextHistory(listOf("ls", "pwd"), index = 0, live = "一半", current = "ls", delta = -1)
        assertEquals("ls", m.draft)
        assertEquals(0, m.index)
    }

    @Test
    fun `往下翻过底要还回那半行`() {
        val m = nextHistory(listOf("ls", "pwd"), index = 1, live = "一半", current = "pwd", delta = 1)
        assertEquals("一半", m.draft)
        assertEquals(-1, m.index)
    }

    @Test
    fun `还没进历史时往下翻什么也不做`() {
        val m = nextHistory(listOf("ls"), index = -1, live = "", current = "编辑中", delta = 1)
        assertEquals("编辑中", m.draft)
        assertEquals(-1, m.index)
    }

    // ---------- 回车提交 ----------

    @Test
    fun `没有换行时只更新输入行`() {
        val c = commitDraft("echo hi")
        assertTrue(c.lines.isEmpty())
        assertEquals("echo hi", c.remaining)
    }

    @Test
    fun `回车提交一条并把输入行清空`() {
        val c = commitDraft("ls\n")
        assertEquals(listOf("ls"), c.lines)
        assertEquals("", c.remaining)
    }

    @Test
    fun `空输入行上的回车也要提交一次`() {
        // 用户只按回车时，仍然要把一个空行送进 PTY（这是「回车」本身）
        val c = commitDraft("\n")
        assertEquals(listOf(""), c.lines)
        assertEquals("", c.remaining)
    }

    @Test
    fun `粘贴多条命令时逐条提交`() {
        val c = commitDraft("a\nb\nc")
        assertEquals(listOf("a", "b"), c.lines)
        assertEquals("c", c.remaining)
    }

    @Test
    fun `连续回车产生多个空行提交`() {
        val c = commitDraft("\n\n")
        assertEquals(listOf("", ""), c.lines)
        assertEquals("", c.remaining)
    }

    // ---------- 历史记录 ----------

    @Test
    fun `空行与连续重复不进历史`() {
        assertEquals(listOf("ls"), historyWith(listOf("ls"), ""))
        assertEquals(listOf("ls"), historyWith(listOf("ls"), "   "))
        assertEquals(listOf("ls"), historyWith(listOf("ls"), "ls"))
        assertEquals(listOf("ls", "pwd"), historyWith(listOf("ls"), "pwd"))
    }

    // ---------- 附加键 ----------

    @Test
    fun `控制键送出的是原始字节`() {
        assertEquals("\u001b", CONTROL_KEYS.getValue("Esc"))
        assertEquals("\t", CONTROL_KEYS.getValue("Tab"))
        assertEquals("\u001b[5~", CONTROL_KEYS.getValue("PgUp"))
        assertEquals("\u001b[6~", CONTROL_KEYS.getValue("PgDn"))
        assertEquals(4, CONTROL_KEYS.size)
    }

    @Test
    fun `Ctrl 粘滞键把字母变成控制字节`() {
        // Ctrl-C = 0x03（行规程负责把它变成 SIGINT）
        assertEquals(3, controlByte('c')[0].code)
        assertEquals(3, controlByte('C')[0].code)
        assertEquals(1, controlByte('a')[0].code)
    }

    // ---------- 粘滞 Ctrl 取的是「插进来的那一个字符」 ----------

    @Test
    fun `末尾追加时取新字符`() {
        assertEquals('X', firstInsertedChar("ab", "abX"))
    }

    /** 光标在行中时**不能**取末尾那一位：旧实现拿的是别人家的字符（行尾 c → 误发 Ctrl-C）。 */
    @Test
    fun `光标在行中插入时取插入点那一个`() {
        assertEquals('X', firstInsertedChar("abc", "aXbc"))
        assertEquals('X', firstInsertedChar("abc", "abXc"))
        assertEquals('X', firstInsertedChar("abc", "Xabc"))
        // 旧写法会取到 'c'（= Ctrl-C），这一行就是那次 bug 的回归钉子
        assertEquals('c', "aXbc"["abc".length])
    }

    @Test
    fun `一次落进来多个字符只取第一个`() {
        assertEquals('X', firstInsertedChar("ab", "abXYZ"))
    }

    @Test
    fun `删除或替换成更短时没有插入字符`() {
        assertEquals(null, firstInsertedChar("abc", "ab"))
        assertEquals(null, firstInsertedChar("abc", "abc"))
        assertEquals(null, firstInsertedChar("abc", "aX"))
    }

    @Test
    fun `空行上敲一个字符也算插入`() {
        assertEquals('x', firstInsertedChar("", "x"))
    }

    // ---------- 光标与插入 ----------

    @Test
    fun `插入字符落在光标处`() {
        val (text, caret) = insertRange("ac", 1, 1, "b")
        assertEquals("abc", text)
        assertEquals(2, caret)
    }

    @Test
    fun `有选中时插入替换选中内容`() {
        val (text, caret) = insertRange("abcd", 1, 3, "X")
        assertEquals("aXd", text)
        assertEquals(2, caret)
    }

    @Test
    fun `越界的光标位置会被夹住`() {
        val (text, caret) = insertRange("ab", 99, 99, "X")
        assertEquals("abX", text)
        assertEquals(3, caret)
    }

    @Test
    fun `光标左右移一格并被夹在两端`() {
        assertEquals(1, moveCursorTo(0, 1, 3))
        assertEquals(0, moveCursorTo(0, -1, 3))
        assertEquals(3, moveCursorTo(3, 1, 3))
        assertEquals(2, moveCursorTo(3, -1, 3))
    }

    // ---------- 输入行变化的判定（draftOutcome） ----------

    @Test
    fun `普通编辑原样采用输入法给的值`() {
        assertEquals(DraftOutcome.Typed, draftOutcome("ab", "abc", ctrl = false))
        assertEquals(DraftOutcome.Typed, draftOutcome("abc", "ab", ctrl = false))   // 退格
    }

    @Test
    fun `粘滞 Ctrl 取插入点那一个字符`() {
        // 末尾插入 c → Ctrl-C
        assertEquals(DraftOutcome.Control("\u0003"), draftOutcome("ab", "abc", ctrl = true))
        // 行中插入 X → Ctrl-X（不能取末尾那一位，那是 R38 修的 bug）
        assertEquals(DraftOutcome.Control("\u0018"), draftOutcome("abc", "aXbc", ctrl = true))
        // 一次落进来多个（输入法上屏 / 粘贴）只取第一个
        assertEquals(DraftOutcome.Control("\u0018"), draftOutcome("ab", "abXYZ", ctrl = true))
    }

    @Test
    fun `粘滞 Ctrl 遇到删除不当成控制键`() {
        assertEquals(DraftOutcome.Typed, draftOutcome("abc", "ab", ctrl = true))
    }

    @Test
    fun `回车提交并留下最后一段`() {
        assertEquals(DraftOutcome.Submit(listOf("ls"), ""), draftOutcome("", "ls\n", ctrl = false))
        assertEquals(DraftOutcome.Submit(listOf("a", "b"), "c"), draftOutcome("", "a\nb\nc", ctrl = false))
    }

    @Test
    fun `单独一个空回车也算提交`() {
        assertEquals(DraftOutcome.Submit(listOf(""), ""), draftOutcome("", "\n", ctrl = false))
    }

    @Test
    fun `多行里的空行不提交`() {
        assertEquals(DraftOutcome.Submit(listOf("a"), "b"), draftOutcome("", "a\n\nb", ctrl = false))
        assertEquals(DraftOutcome.Submit(emptyList(), ""), draftOutcome("", "\n\n", ctrl = false))
    }
}
