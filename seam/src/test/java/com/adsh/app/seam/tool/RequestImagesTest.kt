package com.adsh.app.seam.tool

import com.adsh.app.seam.model.UserAttachment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户消息的 wire content 顺序（dsh 的 ui-conversation service.ts：`content = [...attachments, text]`）。
 *
 * 顺序错了模型看到的图文次序就反了：dsh 是「附件句柄 / 图片在前、正文在最后」。
 */
class RequestImagesTest {

    private fun file(): UserAttachment = UserAttachment(
        path = "/data/user/0/com.termux/files/attachments/v1/files/ab/abcdef/notes.md",
        name = "notes.md",
        bytes = 3,
        sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    )

    @Test
    fun attachmentHandleComesBeforeTheText() = runBlocking {
        val parts = userContentPart("read it", listOf(file()), imageCapable = false) as JsonArray

        assertEquals(2, parts.size)
        val handle = parts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("附件句柄必须在前", handle.startsWith("[File \"notes.md\" (3 bytes, sha256:abcdef01)"))
        assertEquals("read it", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyTextLeavesOnlyTheAttachmentHandle() = runBlocking {
        val parts = userContentPart("   ", listOf(file()), imageCapable = false) as JsonArray

        assertEquals(1, parts.size)
        assertTrue(parts[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("[File "))
    }
}
