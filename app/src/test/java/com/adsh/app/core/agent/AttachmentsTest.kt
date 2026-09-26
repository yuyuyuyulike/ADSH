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

// ---------------------------------------------------------------- read_image（工具读到的图片）

    private fun toolImage(name: String = "shot.png", envelope: String = "<path>shot.png</path>"): ToolImage =
        ToolImage(attachment = image(name), envelope = envelope)

    @Test
    fun toolImagesRoundTripThroughJson() {
        val list = listOf(toolImage(), toolImage("b.webp", "<path>b.webp</path>"))
        assertEquals(list, decodeToolImages(encodeToolImages(list)))
        assertEquals(emptyList<ToolImage>(), decodeToolImages(null))
        assertEquals(emptyList<ToolImage>(), decodeToolImages("not json"))
        assertEquals(null, encodeToolImages(emptyList()))
    }

    /** dsh 的 formatImageReadOutput 信封：路径 / 类型 / 尺寸 / 字节数，缩小时带坐标换算建议 */
    @Test
    fun imageEnvelopeMatchesDsh() {
        assertEquals(
            "<path>app/src/shot.png</path>\n<type>image</type>\n<content>\n" +
                "image/png image, 1200x800 px, 2048 bytes\n</content>",
            toolImageEnvelope("app/src/shot.png", "image/png", 2048L, 1200, 800),
        )
        // 横向缩小到 1/4：x 与 y 的比例相同 → 只给一句「乘 4.00」
        assertEquals(
            "<path>shot.png</path>\n<type>image</type>\n<content>\n" +
                "image/png image, 300x200 px, 2048 bytes (downscaled from 1200x800 px; " +
                "multiply coordinates by 4.00 to locate features in the original file)\n</content>",
            toolImageEnvelope("shot.png", "image/png", 2048L, 300, 200, 1200, 800),
        )
        // 非等比（信封只在两张图比例不同时才会出现，但函数要能表达）→ 分开给 x / y
        val text = toolImageEnvelope("shot.png", "image/png", 2048L, 300, 400, 1200, 800)
        assertTrue(text.contains("multiply x coordinates by 4.00 and y coordinates by 2.00"))
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
