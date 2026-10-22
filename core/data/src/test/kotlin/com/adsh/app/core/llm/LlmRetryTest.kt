package com.adsh.app.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型请求重试策略（dsh 的 llm-retry 默认策略）：
 * 只重试 TRANSPORT / TIMEOUT / SERVER / RATE_LIMIT / EMPTY_RESPONSE；
 * 退避 = min(500 * 2^(retry-1), 10000)，再乘 10% 抖动；每步最多 5 次。
 */
class LlmRetryTest {

    @Test
    fun `重试上限与退避参数对齐 dsh 默认策略`() {
        assertEquals(5, LlmRetry.MAX_RETRIES)
        assertEquals(500L, LlmRetry.INITIAL_DELAY_MS)
        assertEquals(10_000L, LlmRetry.MAX_DELAY_MS)
    }

    @Test
    fun `可重试 code 就是 dsh 的默认集合`() {
        listOf("EMPTY_RESPONSE", "RATE_LIMIT", "SERVER", "TIMEOUT", "TRANSPORT").forEach {
            assertTrue(it, LlmRetry.isRetryable(it))
        }
        // 4xx（请求本身不对）/ 未知 / null 一律不重试
        assertFalse(LlmRetry.isRetryable(null))
        assertFalse(LlmRetry.isRetryable("AUTH"))
        assertFalse(LlmRetry.isRetryable("INTERRUPTED"))
    }

    @Test
    fun `退避按指数增长、封顶 10 秒、带 10 percent 抖动`() {
        // random = 0.5 时抖动系数 = 1：延迟正好等于 cap
        assertEquals(500L, LlmRetry.backoffDelayMs(1, random = 0.5))
        assertEquals(1_000L, LlmRetry.backoffDelayMs(2, random = 0.5))
        assertEquals(2_000L, LlmRetry.backoffDelayMs(3, random = 0.5))
        assertEquals(4_000L, LlmRetry.backoffDelayMs(4, random = 0.5))
        assertEquals(8_000L, LlmRetry.backoffDelayMs(5, random = 0.5))
        // 超出上限后一直封顶
        assertEquals(10_000L, LlmRetry.backoffDelayMs(6, random = 0.5))
        assertEquals(10_000L, LlmRetry.backoffDelayMs(20, random = 0.5))
    }

    @Test
    fun `抖动落在 cap 的 90 percent 到 110 percent 之间`() {
        assertEquals(450L, LlmRetry.backoffDelayMs(1, random = 0.0))
        assertEquals(550L, LlmRetry.backoffDelayMs(1, random = 1.0))
        assertEquals(9_000L, LlmRetry.backoffDelayMs(9, random = 0.0))
        assertEquals(11_000L, LlmRetry.backoffDelayMs(9, random = 1.0))
    }

    @Test
    fun `上下文超限是独立 code、不可重试、由四种提供方文案识别`() {
        assertFalse(LlmRetry.isRetryable(CONTEXT_WINDOW_EXCEEDED))
        listOf(
            "This model's maximum context length is 1048576 tokens.",
            "context length exceeded",
            "Prompt is too long: 1100000 tokens",
            "Please reduce the length of the messages",
        ).forEach { body -> assertTrue(body, isContextOverflow(400, body)) }
        // 非 400、以及其它 400 都不算「上下文超限」
        assertFalse(isContextOverflow(500, "maximum context length"))
        assertFalse(isContextOverflow(429, "context length"))
        assertFalse(isContextOverflow(400, "invalid tool_call_id"))
    }
}
