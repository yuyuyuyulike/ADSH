package com.adsh.app.core.agent

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.TurnUsage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TurnMeter] 的口径表（R25 从 `runTurnBody` 里搬出来的记账）。
 *
 * 这个类以前是 6 个"本轮"局部量 + 9 个"这一步"局部量 + 一个局部函数，散在 100 多行里、
 * 没有任何测试碰得到。搬出来之后这些口径第一次能直接钉：
 *  - usage 取**最后一份**（网关重复上报时相加会把数字翻倍）；
 *  - `stats()` 是**这一步**的增量，`persist()` 写的是**本轮累计**；
 *  - 首字时延只认本轮第一次出字，掉线重连（StreamReset）作废重来；
 *  - 工具耗时 / 步数每一步归零；
 *  - 用量与文件改动都写在**轮首那一行**（[TurnStore.lastTurnOpener]），没有轮首就什么都不写。
 *
 * 时间字段（runMillis / llmMillis / ttftMillis）在断言里用定值喂进去，所以是精确相等，不是 >= 0。
 */
class TurnMeterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun meter(store: FakeTurnStore, settings: FakeTurnSettings = FakeTurnSettings(), turns: Int = 0) =
        TurnMeter(
            store = store,
            settings = settings,
            json = json,
            conversationId = 1L,
            conversationTurns = turns,
            model = "deepseek-flash",
            turnStartedAt = 1_000L,
        )

    private fun usage(prompt: Int, completion: Int, cacheHit: Int, cacheMiss: Int, reasoning: Int) =
        ChatEvent.Usage(
            promptTokens = prompt,
            completionTokens = completion,
            cacheHitTokens = cacheHit,
            cacheMissTokens = cacheMiss,
            reasoningTokens = reasoning,
        )

    /** 一次请求只有一份 usage：再来一份是**覆盖**，不是相加。 */
    @Test
    fun usageIsOverwrittenByTheLastSample() {
        val m = meter(FakeTurnStore())
        m.beginRound(1_000L)
        m.onUsage(usage(12, 5, 4, 8, 2))
        m.onUsage(usage(1, 1, 1, 1, 1))
        val stats = m.stats()
        assertEquals(1, stats.promptTokens)
        assertEquals(1, stats.completionTokens)
        assertEquals(1, stats.cacheHitTokens)
        assertEquals(1, stats.cacheMissTokens)
    }

    /** stats() 是这一步的增量；persist() 写的是本轮累计（两步相加）。 */
    @Test
    fun statsArePerStepWhilePersistedUsageIsTheTurnTotal() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(1L, "user", "你好")
        val m = meter(store, turns = 3)

        m.beginRound(1_000L)
        m.markFirstToken(1_100L)
        m.onUsage(usage(12, 5, 4, 8, 2))
        m.endRound(1_500L)
        val first = m.stats()
        assertEquals(12, first.promptTokens)
        assertEquals(5, first.completionTokens)
        assertEquals(4, first.cacheHitTokens)
        assertEquals(8, first.cacheMissTokens)
        assertEquals(500L, first.llmMillis)
        assertEquals(100L, first.ttftMillis)
        assertEquals(1, first.ttftSamples)
        assertEquals(3, first.turns)

        m.beginRound(2_000L)
        m.markFirstToken(2_300L)
        m.onUsage(usage(7, 3, 1, 6, 0))
        m.endRound(2_500L)
        val second = m.stats()
        assertEquals("第二步只报这一步的增量", 7, second.promptTokens)
        assertEquals(3, second.completionTokens)
        assertEquals(500L, second.llmMillis)

        m.persist(null)
        assertEquals("UPDATE 必须打在真实行上", emptyList<Long>(), store.missedUpdateIds)
        val row = store.rows(1L).single()
        val written = json.decodeFromString(TurnUsage.serializer(), row.usageJson ?: "")
        assertEquals("DeepSeek", written.provider)
        assertEquals("deepseek-flash", written.model)
        assertEquals("两块相加 = 本轮累计", 19, written.uncachedInputTokens)
        assertEquals(5, written.cacheReadTokens)
        assertEquals(8, written.outputTokens)
        assertEquals(2, written.reasoningTokens)
        assertEquals(0, written.cacheWriteTokens)
        assertEquals("首字时延只认本轮第一次", 100L, written.ttftMillis)
    }

    /** 首字时延只认本轮第一次出字；第二步自己的 ttft 照报，但不覆盖本轮值。 */
    @Test
    fun ttftIsOnlyTheFirstTokenOfTheTurn() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(1L, "user", "你好")
        val m = meter(store)
        m.beginRound(1_000L)
        m.markFirstToken(1_100L)
        m.markFirstToken(1_200L)
        m.endRound(1_500L)
        assertEquals("同一个 ttft 只记第一次", 100L, m.stats().ttftMillis)

        m.beginRound(2_000L)
        m.markFirstToken(2_400L)
        m.endRound(2_500L)
        assertEquals("这一步自己的 ttft", 400L, m.stats().ttftMillis)
        m.persist(null)
        val written = json.decodeFromString(TurnUsage.serializer(), store.rows(1L).single().usageJson ?: "")
        assertEquals("本轮值不被第二步覆盖", 100L, written.ttftMillis)
    }

    /**
     * 掉线重连 = 这一步重新生成：首字时延作废，重来的第一段字成为新的首字。
     * 口径注意：量的还是**这一步开始**（beginRound）到首字的差 —— 重连不移动步起点
     * （与今天的行为一致：roundStarted 只在 beginRound 里取）。
     */
    @Test
    fun streamResetForgetsTheFirstToken() {
        val m = meter(FakeTurnStore())
        m.beginRound(1_000L)
        m.markFirstToken(1_100L)
        m.resetFirstToken()
        m.markFirstToken(1_300L)
        m.endRound(1_500L)
        assertEquals(300L, m.stats().ttftMillis)
        assertEquals(1, m.stats().ttftSamples)
    }

    /** 没出过字就没有 ttft（ttftSamples = 0）—— 失败轮就是这样。 */
    @Test
    fun noTokenMeansNoTtftSample() {
        val m = meter(FakeTurnStore())
        m.beginRound(1_000L)
        m.endRound(1_200L)
        assertEquals(0L, m.stats().ttftMillis)
        assertEquals(0, m.stats().ttftSamples)
        assertEquals(200L, m.stats().llmMillis)
    }

    /**
     * TPS 的分母是**解码窗口**（dsh 的 decodeMs = completedTime − firstTokenTime），不是整步耗时。
     *
     * 用户报的「新会话首轮模型速度慢好多」就出在这条口径上：首轮要冷 prefill 8.5K token，
     * 一步 2 秒里首 token 花掉 1.5 秒 —— 拿整步当分母只有 25 tok/s，按 dsh 的口径是 100 tok/s
     * （第二轮命中前缀缓存 TTFT 短，两个口径才会接近）。
     */
    @Test
    fun tpsCountsOnlyTheDecodeWindow() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(1L, "user", "你好")
        val m = meter(store)
        m.beginRound(1_000L)
        m.markFirstToken(2_500L)
        m.onUsage(usage(8_000, 50, 0, 8_000, 0))
        m.endRound(3_000L)

        val stats = m.stats()
        assertEquals("整步 2 秒", 2_000L, stats.llmMillis)
        assertEquals("首 token 花了 1.5 秒", 1_500L, stats.ttftMillis)
        assertEquals("解码窗口只剩 0.5 秒", 500L, stats.decodeMillis)
        assertEquals(100.0, stats.tps, 0.001)

        m.persist(null)
        val written = json.decodeFromString(TurnUsage.serializer(), store.rows(1L).single().usageJson ?: "")
        assertEquals(100.0, written.tps, 0.001)
        assertEquals(1_500L, written.ttftMillis)
    }

    /**
     * 一次 usage 都没来（没量到输出 token）就不算解码读数 —— dsh 的 outputTokens === null 那一支：
     * 那条读数既不进 decodeMs 也不进 decodeTokens，本轮的 TPS 因此是 0（不是拿整步去除 0 个 token）。
     *
     * 会话级那个速度是从库里两个数推的（llmMillis − ttftMillis，见 SessionStats.decodeMillis）：
     * 这种「有首 token 但没有 usage」的步推不出来，会把那一小段算进分母 —— 网关不回 usage 才碰得到。
     */
    @Test
    fun decodeWindowNeedsBothAFirstTokenAndUsage() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(1L, "user", "你好")
        val m = meter(store)
        m.beginRound(1_000L)
        m.markFirstToken(1_200L)
        m.endRound(2_000L)
        m.persist(null)
        val written = json.decodeFromString(TurnUsage.serializer(), store.rows(1L).single().usageJson ?: "")
        assertEquals("没有 usage 就没有解码读数", 0.0, written.tps, 0.001)
    }

    /** 工具耗时与步数是每一步的增量，下一步归零。 */
    @Test
    fun toolMillisAndStepsArePerStep() {
        val m = meter(FakeTurnStore())
        m.beginRound(1_000L)
        m.addToolMillis(30L)
        m.addToolMillis(20L)
        m.setSteps(3)
        m.endRound(2_000L)
        assertEquals(50L, m.stats().toolMillis)
        assertEquals(3, m.stats().steps)

        m.beginRound(3_000L)
        m.endRound(3_100L)
        assertEquals(0L, m.stats().toolMillis)
        assertEquals(0, m.stats().steps)
    }

    /** 没有轮首（比如第一条就是系统通知）就什么都不写，也不抛。 */
    @Test
    fun persistWithoutATurnOpenerWritesNothing() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(1L, "user", "插话", name = ConversationRepository.STEERING)
        val m = meter(store)
        m.beginRound(1_000L)
        m.endRound(1_100L)
        m.persist(null)
        assertNull(store.rows(1L).single().usageJson)
        assertNull(store.rows(1L).single().changesJson)
        assertTrue(store.missedUpdateIds.isEmpty())
    }
}
