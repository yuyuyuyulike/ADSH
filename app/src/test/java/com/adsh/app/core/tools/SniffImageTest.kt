package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * read_image 的魔数嗅探（dsh-tool-fs 的 sniffImageMediaType）：
 * 只认 PNG / JPEG / GIF87a·GIF89a / RIFF….WEBP 四种；扩展名由调用方另行校验。
 */
class SniffImageTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun recognizesTheFourDshFormats() {
        assertEquals("image/png", sniffSupportedImage(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("image/jpeg", sniffSupportedImage(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", sniffSupportedImage("GIF87a".toByteArray() + bytes(1, 2)))
        assertEquals("image/gif", sniffSupportedImage("GIF89a".toByteArray() + bytes(1, 2)))
        assertEquals(
            "image/webp",
            sniffSupportedImage("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WEBP".toByteArray()),
        )
    }

    @Test
    fun rejectsEverythingElseIncludingFormatsAdshSendsAsUserAttachments() {
        // BMP / HEIC 是**用户附件**认的格式（设备解得出），read_image 按 dsh 不认
        assertNull(sniffSupportedImage(bytes(0x42, 0x4D, 0x00, 0x00)))
        assertNull(sniffSupportedImage(bytes(0, 0, 0, 0x18) + "ftypheic".toByteArray()))
        assertNull(sniffSupportedImage("not an image at all".toByteArray()))
        assertNull(sniffSupportedImage(ByteArray(0)))
        // RIFF 但不是 WEBP（例如 wav）
        assertNull(sniffSupportedImage("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WAVE".toByteArray()))
        // 前缀像 PNG 但被截断在签名中间
        assertNull(sniffSupportedImage(bytes(0x89, 0x50, 0x4E)))
    }
}
