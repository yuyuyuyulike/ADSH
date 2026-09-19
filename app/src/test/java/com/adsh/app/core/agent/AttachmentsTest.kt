package com.adsh.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件在模型侧的表示（dsh 的 dsh-llm/content.ts）：
 * 这些字符串是模型**唯一**能看到的附件信息，格式错了模型就会瞎猜路径 / 声称看不到图。
 */
class AttachmentsTest {

    private fun file(name: String, bytes: Long = 1234L): UserAttachment = UserAttachment(
        path = "/data/user/0/com.adsh.app/files/ws/.adsh/attachments/1/" + name,
        name = name,
        bytes = bytes,
        sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    )

    private fun image(name: String = "photo.png"): UserAttachment = UserAttachment(
        path = "/ws/.adsh/attachments/1/" + name,
        name = name,
        bytes = 2048L,
        mediaType = "image/png",
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        width = 1200,
        height = 800,
    )

    @Test
    fun fileHandleTextMatchesDsh() {
        val text = fileHandleText(file("notes.md"))
        assertEquals(
            "[File \"notes.md\" (1234 bytes, sha256:abcdef01): verbatim read-only copy saved at " +
                "\"/data/user/0/com.adsh.app/files/ws/.adsh/attachments/1/notes.md\". " +
                "Read that path with your file tools when its contents are needed; " +
                "copy it to a writable location before modifying it.]",
            text,
        )
    }

    @Test
    fun textOnlyPlaceholderHasDigestAndNoPath() {
        val text = textOnlyImageText(image())
        assertEquals("[image omitted because this model accepts text only; attachment sha256:01234567]", text)
        assertTrue("纯文本占位里不该出现路径", !text.contains("/ws"))
    }

    @Test
    fun imageHandleDescribesPreviewAndReadOnlyCopy() {
        val text = imageHandleText(image(), 640, 427)
        assertTrue(text.startsWith("Image \"photo.png\" (sha256:0123456789"))
        assertTrue(text.contains("request preview 640x427px."))
        assertTrue(text.contains("Normalized copy (read-only; may be resized or re-encoded): \"/ws/.adsh/attachments/1/photo.png\" (1200x800px, image/png)."))
        assertTrue(text.endsWith("Copy to a writable path ending in .png before editing."))
    }

    @Test
    fun attachmentsRoundTripThroughJson() {
        val list = listOf(file("a.txt"), image("b.jpg"))
        val raw = encodeAttachments(list)
        assertEquals(list, decodeAttachments(raw))
        assertEquals(emptyList<UserAttachment>(), decodeAttachments(null))
        assertEquals(emptyList<UserAttachment>(), decodeAttachments("not json"))
    }

    @Test
    fun emptyListStoresNoJson() {
        assertEquals(null, encodeAttachments(emptyList()))
    }

    /** dsh 的 requestImageDimensions：等比缩到像素预算内，小图不放大 */
    @Test
    fun requestDimensionsShrinkToBudget() {
        assertEquals(800 to 600, requestImageDimensions(800, 600, 640_000))
        val (width, height) = requestImageDimensions(4000, 3000, 640_000)
        assertTrue("必须缩进预算内", width * height <= 640_000)
        assertTrue("必须等比（±1px）", kotlin.math.abs(width.toDouble() / height - 4000.0 / 3000.0) < 0.01)
        val (portraitWidth, portraitHeight) = requestImageDimensions(3000, 4000, 640_000)
        assertTrue(portraitWidth * portraitHeight <= 640_000)
        assertTrue(portraitHeight > portraitWidth)
    }
}
