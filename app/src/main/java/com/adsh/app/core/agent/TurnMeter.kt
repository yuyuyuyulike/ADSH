package com.adsh.app.core.agent

import com.adsh.app.core.data.TurnSettings
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.llm.TurnUsage
import kotlinx.serialization.json.Json

/**
 * 一轮的**记账**（dsh 的 turn tokenUsage + runMs）：token 累计、首字时延、工具耗时与步数，
 * 以及轮尾把它们写回**轮首那一行**这件事。
 *
 * 为什么单独成类：[AgentLoop] 原先用 6 个「本轮」局部量 + 9 个「这一步」局部量 + 一个局部函数
 * 记账，散在 100 多行里，而且没有任何测试碰得到（那条路要 Android、要网络）。搬进来之后它是
 * 纯 Kotlin：`TurnMeterTest` 直接喂 [ChatEvent.Usage] 就能把口径钉住。
 *
 * 口径（改动前先看测试，这几条都是真机踩出来的）：
 *  - 一次请求只有**一份** usage，取最后一份 —— 把多个 chunk 相加，网关重复上报时数字会翻倍；
 *  - `prompt` 是「未命中缓存」的口径（[com.adsh.app.core.llm.LlmClient] 按 dsh 的 mapUsage 扣过）；
 *  - 首字时延只认**本轮第一次**出字（思考也算），`ttftSamples` 是「有没有量到」；
 *  - `steps / llmMillis / toolMillis` 都是**这一步**的增量，`turns` 是会话轮次
 *    （在写本轮用户行**之前**统计，所以首轮是 0 —— 与 dsh 状态栏一致）。
 */
internal class TurnMeter(
    private val store: TurnStore,
    private val settings: TurnSettings,
    private val json: Json,
    private val conversationId: Long,
    private val conversationTurns: Int,
    private val model: String,
    private val turnStartedAt: Long,
) {

    // 本轮累计：轮尾「用量 / 用时」两个按钮展开的明细
    private var turnPrompt = 0L
    private var turnCompletion = 0L
    private var turnCacheHit = 0L
    private var turnReasoning = 0L
    private var turnLlmMillis = 0L
    private var turnTtft = 0L

    // 这一步的快照（每一步重来）
    private var roundStartedAt = 0L
    private var roundLlmMillis = 0L
    private var roundToolMillis = 0L
    private var roundSteps = 0
    private var firstTokenAt = 0L
    private var roundPrompt = 0L
    private var roundCompletion = 0L
    private var roundCacheHit = 0L
    private var roundCacheMiss = 0L
    private var roundReasoning = 0L

    /** 这一步开始：清掉上一步的增量。 */
    fun beginRound(now: Long) {
        roundStartedAt = now
        roundLlmMillis = 0L
        roundToolMillis = 0L
        roundSteps = 0
        firstTokenAt = 0L
        roundPrompt = 0L
        roundCompletion = 0L
        roundCacheHit = 0L
        roundCacheMiss = 0L
        roundReasoning = 0L
    }

    /** 本步第一次出字（正文或思考）；只认第一次，之后调用无副作用。 */
    fun markFirstToken(now: Long) {
        if (firstTokenAt == 0L) firstTokenAt = now
    }

    /** 掉线重连（[ChatEvent.StreamReset]）：这一步要重新生成，首字时延跟着作废。 */
    fun resetFirstToken() {
        firstTokenAt = 0L
    }

    /** 一次请求的 usage：**覆盖**（取最后一份），不累加。 */
    fun onUsage(usage: ChatEvent.Usage) {
        roundPrompt = usage.promptTokens.toLong()
        roundCompletion = usage.completionTokens.toLong()
        roundCacheHit = usage.cacheHitTokens.toLong()
        roundCacheMiss = usage.cacheMissTokens.toLong()
        roundReasoning = usage.reasoningTokens.toLong()
    }

    /** 一次工具调用的耗时（并进这一步的工具耗时）。 */
    fun addToolMillis(millis: Long) {
        roundToolMillis += millis
    }

    /** 这一步执行了几个工具步（[com.adsh.app.core.tools.ToolStepCounter] 抽干后的值）。 */
    fun setSteps(steps: Int) {
        roundSteps = steps
    }

    /** 这一步结束：把增量并进本轮累计。 */
    fun endRound(now: Long) {
        roundLlmMillis = now - roundStartedAt
        turnPrompt += roundPrompt
        turnCompletion += roundCompletion
        turnCacheHit += roundCacheHit
        turnReasoning += roundReasoning
        turnLlmMillis += roundLlmMillis
        if (turnTtft == 0L && firstTokenAt > 0) turnTtft = firstTokenAt - roundStartedAt
    }

    /** 这一步的用量与时延快照（增量）。字段语义与 dsh 状态栏一一对应。 */
    fun stats(): SessionStats = SessionStats(
        turns = conversationTurns,
        steps = roundSteps,
        promptTokens = roundPrompt,
        completionTokens = roundCompletion,
        cacheHitTokens = roundCacheHit,
        cacheMissTokens = roundCacheMiss,
        llmMillis = roundLlmMillis,
        toolMillis = roundToolMillis,
        ttftMillis = if (firstTokenAt > 0) firstTokenAt - roundStartedAt else 0,
        ttftSamples = if (firstTokenAt > 0) 1 else 0,
    )

    /**
     * 轮尾把用量与**本轮文件改动**写回轮首那一行（开启这一轮的用户消息）。
     *
     * 轮首的判据是 [TurnStore.lastTurnOpener]：插话（STEERING）与权限切换通知（SANDBOX_SWITCH）
     * 也是 role = user，但它们不是轮首 —— 写错了，轮尾的「用量」按钮就空了。
     * 两处写入都包在 runCatching 里：库写失败不该改变这一轮的结果。
     */
    suspend fun persist(workspaceRoot: String?) {
        val userId = store.lastTurnOpener(conversationId)?.id ?: return
        val decodeSeconds = turnLlmMillis / 1000.0
        val usage = TurnUsage(
            provider = settings.providerRoute,
            model = model,
            uncachedInputTokens = turnPrompt.coerceAtLeast(0),
            cacheReadTokens = turnCacheHit,
            // DeepSeek 只有「命中 / 未命中」两个桶，没有单独的缓存写入计费
            cacheWriteTokens = 0,
            outputTokens = turnCompletion,
            reasoningTokens = turnReasoning,
            runMillis = (System.currentTimeMillis() - turnStartedAt).coerceAtLeast(0),
            ttftMillis = turnTtft,
            tps = if (decodeSeconds > 0) turnCompletion / decodeSeconds else 0.0,
        )
        runCatching { store.setUsage(userId, json.encodeToString(TurnUsage.serializer(), usage)) }
        // 本轮的文件改动（dsh 的 workspace/changes）：与用量同一处收口 —— 轮尾的「已编辑 N 个文件」
        // 卡片和「用量」按钮一样，都挂在**开启这一轮的那条用户消息**上
        val changes = TurnChangeTracker.finish(workspaceRoot)
        runCatching { store.setTurnChanges(userId, encodeFileChanges(changes)) }
    }
}
