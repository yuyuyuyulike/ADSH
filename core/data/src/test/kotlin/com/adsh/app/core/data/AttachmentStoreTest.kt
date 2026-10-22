package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 附件仓库的落点语义（dsh 的 attachment-local）：
 *  - 文件按 sha256 内容寻址、逐字节原样、同名内容只存一份；
 *  - 空文件合法；叶子名按 UTF-8 字节截断、净化 Windows 非法字符与设备名；
 *  - 仓库在应用私有目录 `attachments/v1` 下（不在工作区）。
 */
class AttachmentStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("adsh-attach").toFile()

    @Test
    fun verbatimFileIsContentAddressedAndDeduplicated() {
        val filesDir = tempDir()
        val staging = File(filesDir, "staging")
        val one = AttachmentStore.save(filesDir, staging, "hello".byteInputStream(), "notes.txt")
        val two = AttachmentStore.save(filesDir, staging, "hello".byteInputStream(), "notes.txt")

        assertTrue(File(one.path).isFile)
        assertEquals("hello", File(one.path).readText())
        assertEquals(AttachmentStore.sha256Of("hello".toByteArray()), one.sha256)
        assertEquals("notes.txt", one.name)
        assertEquals("同一份字节必须复用同一个对象", one.path, two.path)
        assertTrue(one.path.replace('\\', '/').contains("/attachments/v1/files/"))
    }

    @Test
    fun emptyFileIsAllowed() {
        val filesDir = tempDir()
        val attachment = AttachmentStore.save(filesDir, File(filesDir, "staging"), ByteArray(0).inputStream(), "empty.txt")

        assertEquals(0L, attachment.bytes)
        assertTrue(File(attachment.path).isFile)
    }

    @Test
    fun leafNameTruncatesByUtf8Bytes() {
        val name = "中".repeat(200) + ".txt"
        val leaf = AttachmentStore.leafName(name)

        assertTrue("UTF-8 字节必须 ≤ 255", leaf.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(leaf.isNotEmpty())
    }

    @Test
    fun leafNameSanitizesPathsAndDeviceNames() {
        assertEquals("notes.txt", AttachmentStore.leafName("C:\\tmp\\notes.txt"))
        assertEquals("_con", AttachmentStore.leafName("con"))
        assertEquals("file", AttachmentStore.leafName(null))
        assertEquals("a_b_c", AttachmentStore.leafName("a:b|c"))
    }

    @Test
    fun nonImagePathIsUnderFilesRoot() {
        val filesDir = tempDir()
        val attachment = AttachmentStore.save(filesDir, File(filesDir, "staging"), "x".byteInputStream(), "a.txt")
        val root = AttachmentStore.root(filesDir)

        assertTrue(File(attachment.path).absolutePath.startsWith(root.absolutePath))
        assertFalse(attachment.isImage)
    }
}
