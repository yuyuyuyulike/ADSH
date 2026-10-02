package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第 66 轮：read / edit 的「这段字节能当文本读吗」判断（dsh-fs-local 的 FS_NOT_TEXT 语义）。
 *
 * 真机现场：read 一份二进制 PDF 会吐出几千行替换字符（既不报错也不提示）。现在按 dsh 的做法
 * 先查前 8192 字节有没有 NUL，再严格校验 UTF-8。
 */
class NotTextTest {

    @Test
    fun plainTextIsReadable() {
        assertNull(notTextReason("hello\nworld\n".toByteArray(Charsets.UTF_8)))
        assertNull(notTextReason("中文、emoji 🙂 都没问题".toByteArray(Charsets.UTF_8)))
        assertNull(notTextReason(ByteArray(0)))
    }

    @Test
    fun nulByteMeansBinary() {
        assertEquals("binary file", notTextReason(byteArrayOf(1, 2, 0, 3)))
        // 8192 字节之后才出现的 NUL 不算（dsh 的 BINARY_SAMPLE_BYTES 只看开头）
        val late = ByteArray(9000) { 'a'.code.toByte() }
        late[8500] = 0
        assertNull(notTextReason(late))
    }

    @Test
    fun invalidUtf8IsRejected() {
        // 0xC3 后面必须是 0x80..0xBF；跟一个 ASCII '(' 就是非法序列
        assertEquals("invalid UTF-8 text", notTextReason(byteArrayOf(0xC3.toByte(), 0x28)))
        // 单独的 UTF-16 BOM（FF FE）也不是合法 UTF-8
        assertEquals("invalid UTF-8 text", notTextReason(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
    }
}
