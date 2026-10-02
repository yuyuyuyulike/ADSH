package com.adsh.app.core.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一轮的文件改动（dsh 的 `workspace-changes`，第八十四轮）。
 *
 * 判据全在 [TurnChangeTracker]：`write` / `edit` 落盘**之前**留一份底，轮尾再比一遍。
 * 卡片上的 `+A -B` 就是从这两个快照算出来的 —— 数错了用户一眼就能看出来，所以这里逐种情形钉住。
 */
class FileChangesTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "adsh-changes-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun file(name: String, text: String): File = File(root, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun newFileCountsAsAddedLines() {
        val target = File(root, "out/new.txt")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)   // 还不存在 = 本轮新建
        target.parentFile?.mkdirs()
        target.writeText("a\nb\nc\n")
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertEquals("out/new.txt", changes[0].path)
        assertEquals(3, changes[0].added)
        assertEquals(0, changes[0].deleted)
    }

    @Test
    fun editCountsAddedAndDeletedLines() {
        val target = file("src/app.md", "# 标题\n旧的一行\n最后一行\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        target.writeText("# 标题\n新的一行\n又一行\n最后一行\n")
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertEquals(2, changes[0].added)
        assertEquals(1, changes[0].deleted)
    }

    @Test
    fun unchangedFileIsNotListed() {
        val target = file("same.txt", "内容\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        // 留底之后没动
        assertEquals(emptyList<FileChange>(), TurnChangeTracker.finish(root.absolutePath))
    }

    /** dsh：只差结尾换行算没变（a missing final newline compares as unchanged） */
    @Test
    fun trailingNewlineOnlyIsNotAChange() {
        val target = file("nl.txt", "内容")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        target.writeText("内容\n")
        assertEquals(emptyList<FileChange>(), TurnChangeTracker.finish(root.absolutePath))
    }

    /** 同一个路径一轮只留一次底：改两次仍然是一行，行数按「本轮开始 vs 本轮结束」算 */
    @Test
    fun repeatedEditsToOneFileCountOnce() {
        val target = file("twice.txt", "a\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        target.writeText("a\nb\n")
        TurnChangeTracker.captureBefore(target.absolutePath)   // 第二次不该覆盖留底
        target.writeText("a\nb\nc\n")
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertEquals(2, changes[0].added)
        assertEquals(0, changes[0].deleted)
    }

    @Test
    fun deletedFileCountsDeletedLines() {
        val target = file("gone.txt", "x\ny\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        target.delete()
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertEquals(0, changes[0].added)
        assertEquals(2, changes[0].deleted)
    }

    /** 超过 2 MiB（dsh 的 maxFileBytes）：不比较内容，只列出来 */
    @Test
    fun oversizedFileIsListedWithoutCounts() {
        val target = File(root, "big.bin")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        target.writeBytes(ByteArray((TurnChangeTracker.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() })
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertTrue(changes[0].oversized)
        assertEquals(0, changes[0].added)
    }

    @Test
    fun binaryFileIsListedWithoutCounts() {
        val target = file("img.bin", "text\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(target.absolutePath)
        target.writeBytes(byteArrayOf(1, 0, 2, 3))
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertTrue(changes[0].binary)
    }

    /** 工作区外的路径保持绝对路径（与 Args.display 同一条规则） */
    @Test
    fun pathOutsideTheWorkspaceStaysAbsolute() {
        val outside = File(System.getProperty("java.io.tmpdir"), "adsh-outside-changes.txt")
        outside.writeText("one\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(outside.absolutePath)
        outside.writeText("one\ntwo\n")
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals(1, changes.size)
        assertEquals(outside.absolutePath, changes[0].path)
        outside.delete()
    }

    @Test
    fun lineDiffCountsAreExactForSmallFiles() {
        assertEquals(1 to 0, TurnChangeTracker.lineDiffCounts(listOf("a"), listOf("a", "b")))
        assertEquals(0 to 1, TurnChangeTracker.lineDiffCounts(listOf("a", "b"), listOf("a")))
        assertEquals(1 to 1, TurnChangeTracker.lineDiffCounts(listOf("a", "b"), listOf("a", "c")))
        assertEquals(2 to 0, TurnChangeTracker.lineDiffCounts(emptyList(), listOf("a", "b")))
        assertEquals(0 to 2, TurnChangeTracker.lineDiffCounts(listOf("a", "b"), emptyList()))
    }

    /** 太长的两边按「整文件替换」降级（dsh 的 diffTimeoutMs 超时后也是这个口径） */
    @Test
    fun hugeComparisonDegradesToWholeFileReplacement() {
        val old = (1..3000).map { "old $it" }
        val new = (1..3000).map { "new $it" }
        assertEquals(3000 to 3000, TurnChangeTracker.lineDiffCounts(old, new))
    }

    @Test
    fun jsonRoundTripAndEmptyStoresNull() {
        val list = listOf(FileChange("a/b.md", 3, 1), FileChange("/tmp/x", oversized = true))
        assertEquals(list, decodeFileChanges(encodeFileChanges(list)))
        assertEquals(null, encodeFileChanges(emptyList()))
        assertEquals(emptyList<FileChange>(), decodeFileChanges(null))
        assertEquals(emptyList<FileChange>(), decodeFileChanges("{not json"))
    }

    @Test
    fun noCaptureMeansNoChanges() {
        TurnChangeTracker.beginTurn()
        assertEquals(emptyList<FileChange>(), TurnChangeTracker.finish(root.absolutePath))
    }

    @Test
    fun nestedPathIsRelativeToTheWorkspace() {
        val nested = file("a/b/c.txt", "1\n")
        TurnChangeTracker.beginTurn()
        TurnChangeTracker.captureBefore(nested.absolutePath)
        nested.writeText("1\n2\n")
        val changes = TurnChangeTracker.finish(root.absolutePath)
        assertEquals("a/b/c.txt", changes[0].path)
        assertFalse(changes[0].path.startsWith("/"))
    }
}
