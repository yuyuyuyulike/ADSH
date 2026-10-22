package com.adsh.app.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HTTP 失败 → 规范化 code 的那张表（[streamFailureCode]）：自动恢复只按 code 分流，
 * 「上下文超限」必须与其它 400 分开，否则压缩不会被触发。
 */
class StreamFailureCodeTest {

    @Test
    fun `上下文超限的 400 映射成 CONTEXT_WINDOW_EXCEEDED`() {
        assertEquals(
            CONTEXT_WINDOW_EXCEEDED,
            streamFailureCode(400, """{"error":{"message":"This model's maximum context length is 1048576 tokens"}}"""),
        )
        assertEquals(CONTEXT_WINDOW_EXCEEDED, streamFailureCode(400, "Prompt is too long"))
    }

    @Test
    fun `其余 4xx 是请求本身不对、没有可重试 code`() {
        assertNull(streamFailureCode(400, "invalid tool_call_id"))
        assertNull(streamFailureCode(401, "invalid api key"))
        assertNull(streamFailureCode(404, "not found"))
    }

    @Test
    fun `429 与 5xx 保持可重试映射`() {
        assertEquals("RATE_LIMIT", streamFailureCode(429, ""))
        assertEquals("SERVER", streamFailureCode(500, "internal error"))
        assertEquals("SERVER", streamFailureCode(503, "overloaded"))
    }
}
