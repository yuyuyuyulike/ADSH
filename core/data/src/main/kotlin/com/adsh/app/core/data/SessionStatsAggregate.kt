package com.adsh.app.core.data

import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.llm.TurnUsage

/**
 * 把逐轮用量（[TurnUsage]：落在每轮用户消息的 usageJson 上）重新聚合成会话统计。
 *
 * 统计列（conversations.statXxx）是随会话持久化的聚合值；分叉 / 分支出来的新会话没有这份历史，
 * 而 dsh 的统计本来就是**日志投影** —— 从复制过来的轮次重算即可。旧数据没有 llmMillis /
 * toolMillis 两项时，用该轮的 tps 与 outputTokens 反推解码窗口（llm = decode + ttft）。
 * 纯函数，单测直接打（core/data 的 SessionStatsAggregateTest）。
 */
internal fun aggregateTurnUsage(
    usages: List<TurnUsage>,
    turns: Int,
    steps: Int,
): SessionStats {
    var prompt = 0L
    var completion = 0L
    var cacheHit = 0L
    var llm = 0L
    var tool = 0L
    var ttft = 0L
    var samples = 0
    usages.forEach { usage ->
        prompt += usage.uncachedInputTokens
        completion += usage.outputTokens
        cacheHit += usage.cacheReadTokens
        llm += if (usage.llmMillis > 0) usage.llmMillis else decodedMillisOf(usage)
        tool += usage.toolMillis
        if (usage.ttftMillis > 0) {
            ttft += usage.ttftMillis
            samples++
        }
    }
    return SessionStats(
        turns = turns,
        steps = steps,
        promptTokens = prompt,
        completionTokens = completion,
        cacheHitTokens = cacheHit,
        cacheMissTokens = 0,
        llmMillis = llm,
        toolMillis = tool,
        ttftMillis = ttft,
        ttftSamples = samples,
    )
}

/** 旧用量没有 llmMillis 时的兜底：解码窗口 = outputTokens / tps，再补回首 token 时延 */
private fun decodedMillisOf(usage: TurnUsage): Long {
    if (usage.outputTokens <= 0 || usage.tps <= 0.0) return usage.ttftMillis
    return (usage.outputTokens * 1000.0 / usage.tps).toLong() + usage.ttftMillis
}
