package com.adsh.app.core.data

import com.adsh.app.core.llm.TurnUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分叉 / 分支会话的统计重算：逐轮用量（usageJson）-> 会话聚合列。
 * 口径与 [com.adsh.app.core.llm.SessionStats] 一致（billedInput = prompt + cacheHit）。
 */
class SessionStatsAggregateTest {

    @Test
    fun 逐轮用量相加且轮步数按行数给() {
        val stats = aggregateTurnUsage(
            usages = listOf(
                TurnUsage(
                    uncachedInputTokens = 100,
                    cacheReadTokens = 900,
                    outputTokens = 50,
                    llmMillis = 2_000,
                    toolMillis = 300,
                    ttftMillis = 400,
                ),
                TurnUsage(
                    uncachedInputTokens = 10,
                    cacheReadTokens = 0,
                    outputTokens = 20,
                    llmMillis = 1_000,
                    toolMillis = 0,
                    ttftMillis = 200,
                ),
            ),
            turns = 2,
            steps = 5,
        )
        assertEquals(2, stats.turns)
        assertEquals(5, stats.steps)
        assertEquals(110L, stats.promptTokens)
        assertEquals(900L, stats.cacheHitTokens)
        assertEquals(70L, stats.completionTokens)
        assertEquals(3_000L, stats.llmMillis)
        assertEquals(300L, stats.toolMillis)
        assertEquals(600L, stats.ttftMillis)
        assertEquals(2, stats.ttftSamples)
    }

    @Test
    fun 旧用量没有毫秒数时用速度反推解码窗口() {
        // 1000 tok / 100 tok/s = 10s 解码 + 500ms 首 token = 10.5s 模型用时
        val stats = aggregateTurnUsage(
            usages = listOf(
                TurnUsage(outputTokens = 1_000, tps = 100.0, ttftMillis = 500),
            ),
            turns = 1,
            steps = 0,
        )
        assertEquals(10_500L, stats.llmMillis)
        assertEquals(100L, stats.tps.toLong())
    }

    @Test
    fun 没有用量时只留轮步数() {
        val stats = aggregateTurnUsage(usages = emptyList(), turns = 3, steps = 2)
        assertEquals(3, stats.turns)
        assertEquals(2, stats.steps)
        assertEquals(0L, stats.totalTokens)
        assertEquals(0.0, stats.tps, 0.0)
    }
}
