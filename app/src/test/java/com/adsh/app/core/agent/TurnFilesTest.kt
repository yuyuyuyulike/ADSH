package com.adsh.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮尾文件卡片的行集合（第 86 轮并表）。
 *
 * 起因是用户点名两条：「交付的文件上面多了一块」（同一个文件被画了两张卡）
 * 与「你做的那一块少了 +a -b 部分」—— 现在改动与交付并成一行一个文件，
 * 改过的带数字，只交付的没有数字。
 */
class TurnFilesTest {

    private val root = "/storage/emulated/0/1/App"

    private fun change(path: String, added: Int = 0, deleted: Int = 0, binary: Boolean = false, oversized: Boolean = false) =
        FileChange(path = path, added = added, deleted = deleted, binary = binary, oversized = oversized)

    /** 只有改动：行就是改动记录本身（带数字） */
    @Test
    fun `只有改动时逐行带数字`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("a.md", added = 3, deleted = 1), change("b/c.kt", added = 9)),
            deliverables = emptyList(),
            workspaceRoot = root,
        )
        assertEquals(listOf("a.md", "b/c.kt"), rows.map { it.path })
        assertTrue(rows.all { it.edited })
        assertEquals(3, rows[0].added)
        assertEquals(1, rows[0].deleted)
        assertEquals(9, rows[1].added)
    }

    /** 只有交付：行没有数字（这一轮没改过它） */
    @Test
    fun `只有交付时没有数字`() {
        val rows = mergeTurnFiles(
            changes = emptyList(),
            deliverables = listOf(PresentedFile("out/report.pdf", "构建产物"), PresentedFile("a.md")),
            workspaceRoot = root,
        )
        assertEquals(listOf("out/report.pdf", "a.md"), rows.map { it.path })
        assertTrue(rows.none { it.edited })
        assertEquals(0, rows[0].added)
    }

    /** 改过又交付过：**只留一行**，并且保留改动那一行的数字（这是「多了一块」的根治） */
    @Test
    fun `改过又交付的文件只出现一次且带数字`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("测试.md", added = 7)),
            deliverables = listOf(PresentedFile("测试.md", "写好的测试文件")),
            workspaceRoot = root,
        )
        assertEquals(1, rows.size)
        assertEquals("测试.md", rows[0].path)
        assertTrue(rows[0].edited)
        assertEquals(7, rows[0].added)
    }

    /** 交付写的是工作区内的**绝对**路径：裁掉前缀后与改动那条是同一个文件，仍然只留一行 */
    @Test
    fun `交付的绝对路径裁前缀后与改动合并`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("app/src/Main.kt", added = 2, deleted = 2)),
            deliverables = listOf(PresentedFile("$root/app/src/Main.kt")),
            workspaceRoot = root,
        )
        assertEquals(listOf("app/src/Main.kt"), rows.map { it.path })
        assertEquals(2, rows[0].added)
    }

    /** 模型爱写的 `./x.md` 也算同一个文件（不额外多一行） */
    @Test
    fun `点斜杠前缀不影响合并`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("x.md", added = 1)),
            deliverables = listOf(PresentedFile("./x.md")),
            workspaceRoot = root,
        )
        assertEquals(listOf("x.md"), rows.map { it.path })
    }

    /** 工作区外的交付物原样保留（那是它真实的落地位置），也不会跟工作区里的同名文件混淆 */
    @Test
    fun `工作区外的交付物原样保留`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("x.md", added = 1)),
            deliverables = listOf(PresentedFile("/tmp/x.md"), PresentedFile("/tmp/y.md")),
            workspaceRoot = root,
        )
        assertEquals(listOf("x.md", "/tmp/x.md", "/tmp/y.md"), rows.map { it.path })
        assertFalse(rows[1].edited)
        assertFalse(rows[2].edited)
    }

    /** 顺序：改动在前（按记录顺序），只交付的追加在后（按交付顺序）；空路径丢掉 */
    @Test
    fun `顺序与空路径`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("a.md", added = 1), change("   ", added = 5), change("b.md", added = 1)),
            deliverables = listOf(PresentedFile(""), PresentedFile("c.md"), PresentedFile("b.md")),
            workspaceRoot = root,
        )
        assertEquals(listOf("a.md", "b.md", "c.md"), rows.map { it.path })
    }

    /** 二进制 / 过大 的标记要跟着改动那一行一起进卡片（界面据此不显示数字） */
    @Test
    fun `二进制与过大标记保留`() {
        val rows = mergeTurnFiles(
            changes = listOf(change("pic.png", binary = true), change("big.log", oversized = true)),
            deliverables = listOf(PresentedFile("pic.png")),
            workspaceRoot = root,
        )
        assertEquals(2, rows.size)
        assertTrue(rows[0].binary)
        assertTrue(rows[1].oversized)
    }
}
