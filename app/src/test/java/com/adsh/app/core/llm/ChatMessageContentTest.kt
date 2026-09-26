package com.adsh.app.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wire 上的 content 有两种形态（OpenAI 兼容）：
 *  - 纯文本 = JSON 字符串（助手 / 工具 / 系统消息都必须是这个形态）；
 *  - 带图片 = 内容块数组 [{"type":"text"},{"type":"image_url","image_url":{"url":"data:..."}}]。
 * 用 JsonElement 承载就是为了两种形态共用一个字段（见 ChatMessage 的注释）。
 */
class ChatMessageContentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun encode(vararg messages: ChatMessage): String =
        json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = "deepseek-v4-flash", messages = messages.toList()),
        )

    /** 取回第一条消息的 content */
    private fun contentOf(body: String) = json.parseToJsonElement(body)
        .jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!

    @Test
    fun textMessageStaysPlainString() {
        val body = encode(ChatMessage(role = "assistant", content = textContent("你好")))
        assertTrue(body.contains("\"content\":\"你好\""))
        assertTrue("纯文本不该变成数组", !body.contains("\"content\":["))
        assertEquals("你好", contentOf(body).jsonPrimitive.content)
    }

    /**
     * 只有 tool_calls 的助手消息 content 为 null：Json 配了 encodeDefaults，
     * 所以它会显式写成 "content":null（换字段以前也是这样，服务端一直收得下）。
     */
    @Test
    fun nullContentStillCarriesToolCalls() {
        val body = encode(
            ChatMessage(role = "assistant", toolCalls = listOf(ToolCall(function = ToolCallFunction(name = "run_code")))),
        )
        assertTrue(body.contains("\"tool_calls\""))
        assertTrue(body.contains("\"name\":\"run_code\""))
        assertTrue(contentOf(body) is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun imageMessageSerializesAsContentParts() {
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "看看这张图") })
            add(buildJsonObject { put("type", "text"); put("text", "\nImage \"a.png\"; request preview 640x480px.") })
            add(
                buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,AAAA") })
                },
            )
        }
        val body = encode(ChatMessage(role = "user", content = content))
        val parts = contentOf(body).jsonArray
        assertEquals(3, parts.size)
        assertEquals("看看这张图", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            parts[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun textContentOfMultimodalMessageIsEmpty() {
        val content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "x") }) }
        assertEquals("", ChatMessage(role = "user", content = content).textContent)
        assertEquals("hi", ChatMessage(role = "user", content = textContent("hi")).textContent)
    }
}
