package com.adsh.app.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 chunk 的解析：**形状意外的网关不能让整条流变成 Failed**。
 *
 * 真机背景：非 DeepSeek 的模型（走自建/第三方 OpenAI 兼容端点）在这条链路上更容易踩到
 * 形状差异 —— 不带 id 的 tool_calls、拍平的 tool_calls、把 arguments 给成对象、
 * content 给成 content parts 数组。以前这些都用 `jsonPrimitive` 取，遇到对象/数组会抛，
 * 抛出去整个流就变成一条 Failed（用户看到的是「一用就中断」）。
 */
class StreamChunkShapeTest {

    private val client = LlmClient({ ProviderConfig(baseUrl = "http://127.0.0.1", apiKey = "k", model = "m") })

    private fun deltas(chunk: String): List<ChatEvent.ToolCallDelta> =
        client.decodeChunk(chunk).orEmpty().filterIsInstance<ChatEvent.ToolCallDelta>()

    @Test
    fun aToolCallWithoutIdStillParses() {
        // qwen 的 token-plan 端点：delta 里没有 id
        val calls = deltas(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"run_code","arguments":"{\"code\":\"1\"}"}}]}}]}""",
        )
        assertEquals(1, calls.size)
        assertEquals("run_code", calls[0].name)
        assertEquals(null, calls[0].id)
        assertEquals("{\"code\":\"1\"}", calls[0].argumentsChunk)
    }

    @Test
    fun aFlattenedToolCallIsAccepted() {
        // 有些网关把 function 那一层拍平，arguments 还给成对象
        val calls = deltas("""{"choices":[{"delta":{"tool_calls":[{"name":"run_code","arguments":{"code":"1"}}]}}]}""")
        assertEquals(1, calls.size)
        assertEquals("run_code", calls[0].name)
        assertEquals("{\"code\":\"1\"}", calls[0].argumentsChunk)
    }

    @Test
    fun alternateIdKeysAreAccepted() {
        val calls = deltas(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"call_id":"call_x","function":{"name":"run_code"}}]}}]}""",
        )
        assertEquals("call_x", calls[0].id)
    }

    @Test
    fun contentPartsArrayIsJoinedInsteadOfThrowing() {
        val events = client.decodeChunk(
            """{"choices":[{"delta":{"content":[{"type":"text","text":"你"},{"type":"text","text":"好"}]}}]}""",
        ).orEmpty()
        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("你好", text)
    }

    @Test
    fun unexpectedShapesDoNotThrow() {
        // content 是对象、id 是对象、index 是字符串、finish_reason 是数组：
        // 都不认识，但必须原样返回一个事件列表（或者 null），绝不能抛
        val chunk = """{"choices":[{"delta":{"content":{"a":1},"tool_calls":[{"index":"x","id":{"y":1}}]},""" +
            """"finish_reason":["stop"]}]}"""
        val events = client.decodeChunk(chunk)
        assertTrue(events == null || events.none { it is ChatEvent.Delta })
    }

    @Test
    fun usageWithUnexpectedShapeDoesNotThrow() {
        val events = client.decodeChunk("""{"usage":{"prompt_tokens":{"nested":1},"completion_tokens":"7"}}""").orEmpty()
        val usage = events.filterIsInstance<ChatEvent.Usage>().firstOrNull()
        assertEquals(7, usage?.completionTokens)
    }
}
