package com.adsh.app.runtime.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [readProcessTable]：把一棵 `/proc` 形状的目录读成 ppid → 子进程 的表。
 *
 * 真机上这段跑在杀进程树的收尾路径上：读错/漏读的后果是孙进程被 init 收养、再也杀不到
 * （见 [killOrder]）。用假目录是为了钉住三种真实形状：comm 里的空格与括号、非数字目录项、
 * 缺文件或畸形 stat。
 */
class ProcessTableTest {

    private fun procRoot(): File = Files.createTempDirectory("adsh-proc").toFile()

    private fun stat(root: File, pid: Int, comm: String, ppid: Int) {
        val dir = File(root, pid.toString()).apply { mkdirs() }
        File(dir, "stat").writeText(
            pid.toString() + " (" + comm + ") S " + ppid + " 1 1 1 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0\n",
        )
    }

    @Test
    fun `maps every child to its parent`() {
        val root = procRoot()
        stat(root, 100, "bash", 1)
        stat(root, 101, "sleep", 100)
        stat(root, 102, "sleep", 100)
        val table = readProcessTable(root)
        assertEquals(listOf(100), table[1])
        assertEquals(listOf(101, 102), table[100])
    }

    @Test
    fun `comm with spaces and parentheses does not break the ppid read`() {
        val root = procRoot()
        stat(root, 200, "my prog (v2)", 42)
        stat(root, 201, "a)b", 42)
        assertEquals(listOf(200, 201), readProcessTable(root)[42])
    }

    @Test
    fun `non numeric entries and unreadable stat files are skipped`() {
        val root = procRoot()
        File(root, "self").createNewFile()
        File(root, "123").mkdirs()                       // 数字目录但没有 stat
        val broken = File(root, "124").apply { mkdirs() }
        File(broken, "stat").writeText("garbage without a close paren\n")
        stat(root, 125, "bash", 1)
        val table = readProcessTable(root)
        assertEquals(listOf(125), table[1])
        assertEquals(1, table.size)
    }

    @Test
    fun `a missing proc directory reads as an empty table`() {
        assertTrue(readProcessTable(File(procRoot(), "missing")).isEmpty())
    }
}
