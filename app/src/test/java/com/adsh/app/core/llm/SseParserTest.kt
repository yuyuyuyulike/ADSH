package com.adsh.app.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun singleDataEvent() {
        val parser = SseParser()
        assertNull(parser.feed("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", parser.feed(""))
    }

    @Test
    fun multilineDataJoinedByNewline() {
        val parser = SseParser()
        parser.feed("data: line1")
        parser.feed("data: line2")
        assertEquals("line1\nline2", parser.feed(""))
    }

    @Test
    fun commentsAndOtherFieldsIgnored() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed("event: message"))
        assertNull(parser.feed("id: 42"))
        assertNull(parser.feed("data: [DONE]"))
        assertEquals("[DONE]", parser.feed(""))
    }

    @Test
    fun endFlushesUnterminatedEvent() {
        val parser = SseParser()
        parser.feed("data: tail")
        assertEquals("tail", parser.end())
        assertNull(parser.end())
    }

    @Test
    fun decodeContentDelta() {
        val client = LlmClient({ ProviderConfig("", "", "", true) })
        val events = client.decodeChunk("""{"choices":[{"delta":{"content":"hi"}}]}""")!!
        assertEquals(1, events.size)
        assertTrue(events[0] is ChatEvent.Delta)
        assertEquals("hi", (events[0] as ChatEvent.Delta).text)
    }

    @Test
    fun decodeReasoningAndFinishReason() {
        val client = LlmClient({ ProviderConfig("", "", "", true) })
        val events = client.decodeChunk(
            """{"choices":[{"delta":{"reasoning_content":"think"},"finish_reason":"stop"}]}"""
        )!!
        assertTrue(events.any { it is ChatEvent.Reasoning })
        assertTrue(events.any { it is ChatEvent.Finished })
    }

    @Test
    fun decodeUsage() {
        val client = LlmClient({ ProviderConfig("", "", "", true) })
        val events = client.decodeChunk("""{"usage":{"prompt_tokens":3,"completion_tokens":4}}""")!!
        val usage = events.first() as ChatEvent.Usage
        assertEquals(3, usage.promptTokens)
        assertEquals(4, usage.completionTokens)
    }

    @Test
    fun decodeToolCallDelta() {
        val client = LlmClient({ ProviderConfig("", "", "", true) })
        val events = client.decodeChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"run_code","arguments":"{\"a\""}}]}}]}"""
        )!!
        val call = events.filterIsInstance<ChatEvent.ToolCallDelta>().first()
        assertEquals("run_code", call.name)
        assertEquals("call_1", call.id)
    }
}
