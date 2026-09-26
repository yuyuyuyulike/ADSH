package com.adsh.app.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一条 wire 消息。
 *
 * content 是 JsonElement 而不是 String：dsh 的用户消息可以带图片内容块
 * （OpenAI 兼容的 content 数组：[{type:text},{type:image_url}]），
 * 纯文本时仍然是普通的 JSON 字符串 —— 两种形态共用一个字段，序列化时不会多出空字段。
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    /**
     * 思考模式下**必须回传**的推理正文（DeepSeek 的 reasoning_content）。
     *
     * 这是 400 的直接原因之一：实测（api.deepseek.com，deepseek-flash 默认就是思考模式）
     * 一条带 tool_calls 的历史 assistant 消息如果没有 reasoning_content，下一轮请求直接
     * 被判 400「The `reasoning_content` in the thinking mode must be passed back to the API.」——
     * 也就是「第一次工具调用能发出去，工具结果回填的那一轮必失败」。
     *
     * dsh 的做法是 serializeAssistant 里原样带上（dsh-llm-deepseek/lib/index.js:122），
     * 这里同样把库里存的 reasoning 回传；库里的 reasoning 为空但这条消息带 tool_calls 时，
     * DeepSeek 路由下回一个空串（实测空串合法：thinking=enabled / disabled 都过）。
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    /** 纯文本内容（多模态时给出其中的文本部分，供演示流/日志使用） */
    val textContent: String
        get() = when (val value = content) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            else -> ""
        }
}

/** 纯文本内容（构造 wire 消息的快捷方式） */
fun textContent(text: String): JsonElement = JsonPrimitive(text)

@Serializable
data class ToolCall(
    val id: String? = null,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

/** dsh 的 thinking 字段：DeepSeek 用 enabled / disabled 两态，不用 reasoning_effort=off */
@Serializable
data class ThinkingOption(val type: String)

/** dsh 的 stream_options：流式最后一个 chunk 带上 usage */
@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** dsh 的 reasoning_effort：off / low / high / max；null = 走提供方默认 */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** dsh 的 thinking：off → disabled，low/high/max → enabled */
    val thinking: ThinkingOption? = null,
    /** dsh 的 stream_options：让流式响应带 usage（token 统计靠它） */
    @SerialName("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
    val tools: List<JsonObject>? = null,
)

/**
 * 会话统计（对齐 dsh 状态栏的两组数字）：
 *   1)「{turns} 轮 {steps} 步」+ 输出速度 TPS
 *   2)「{count} tok」+「缓存命中 {percent}%」，点击展开明细（模型用时 / 工具用时 / TTFT）
 */
data class SessionStats(
    val turns: Int = 0,
    val steps: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
    val llmMillis: Long = 0,
    val toolMillis: Long = 0,
    val ttftMillis: Long = 0,
    val ttftSamples: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    val cacheHitPercent: Int
        get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total <= 0) 0 else ((cacheHitTokens * 100) / total).toInt()
        }

    val tps: Double get() = if (llmMillis <= 0) 0.0 else completionTokens * 1000.0 / llmMillis

    val ttftAverage: Long get() = if (ttftSamples <= 0) 0 else ttftMillis / ttftSamples

    operator fun plus(other: SessionStats): SessionStats = SessionStats(
        turns = maxOf(turns, other.turns),
        steps = steps + other.steps,
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
        llmMillis = llmMillis + other.llmMillis,
        toolMillis = toolMillis + other.toolMillis,
        ttftMillis = ttftMillis + other.ttftMillis,
        ttftSamples = ttftSamples + other.ttftSamples,
    )
}

data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 本地回环演示模式：不发网络请求，用固定文本流式返回（用于无密钥时验证 UI 与链路） */
    val mock: Boolean = false,
    /** API 协议（dsh 的 api）：openai-completions / anthropic-messages */
    val api: String = com.adsh.app.core.data.ApiProtocol.OPENAI_COMPLETIONS,
    /** 提供方 id 与展示名（dsh 的 provider / displayName） */
    val providerId: String = com.adsh.app.core.data.BuiltInProviders.DEEPSEEK_ID,
    val providerName: String = com.adsh.app.core.data.BuiltInProviders.DEEPSEEK_NAME,
)

/**
 * 本轮（一条用户消息到下一轮之间）的用量与时延 —— dsh 的 turn tokenUsage / runMs，
 * 落在这一轮的用户消息行上，供轮尾的「用量 / 用时」两个按钮展开明细。
 *
 * 字段名与 dsh 的 TokenUsage / TurnUsagePanel 一一对应：
 *   uncachedInputTokens = 未缓存输入，cacheReadTokens = 缓存读取（命中），outputTokens = 输出。
 */
@kotlinx.serialization.Serializable
data class TurnUsage(
    /** 提供方路由 id（dsh 的 provider id，例如 deepseek-official） */
    val provider: String = "",
    /** 模型 id（dsh 的 model id） */
    val model: String = "",
    val uncachedInputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    /** 输出里属于推理的部分（dsh 的「其中推理 N tok」） */
    val reasoningTokens: Long = 0,
    /** 本轮总用时（dsh 的 message.turnTime.duration） */
    val runMillis: Long = 0,
    /** 首 token 用时（dsh 的 message.turnTime.ttft） */
    val ttftMillis: Long = 0,
    /** 输出速度（dsh 的 message.turnTime.speed） */
    val tps: Double = 0.0,
) {
    val billedInputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens
    val totalTokens: Long get() = billedInputTokens + outputTokens
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Reasoning(val text: String) : ChatEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsChunk: String?,
    ) : ChatEvent
    /** 一步 assistant 输出定稿落库（dsh 的 assistant 步）：界面要立刻改从库里读，重新分步 */
    data class StepCommitted(val step: String) : ChatEvent
    data class ToolStarted(val callId: String?, val name: String, val arguments: String) : ChatEvent
    data class ToolFinished(
        val callId: String?,
        val name: String,
        val output: String,
        val isError: Boolean,
    ) : ChatEvent
    data class Usage(
        /**
         * 未命中缓存的输入 token（dsh 的 TokenUsage.inputTokens 口径）。
         * 注意：wire 上的 prompt_tokens 是**含缓存命中**的总输入，
         * LlmClient 已经按 dsh 的 mapUsage 把命中的部分减掉了，这里不再重复扣。
         */
        val promptTokens: Int,
        val completionTokens: Int,
        /** 上下文缓存命中量（dsh 的 cacheReadTokens） */
        val cacheHitTokens: Int = 0,
        val cacheMissTokens: Int = 0,
        /** 输出里属于推理的部分（dsh 的 reasoningTokens：「其中推理 N tok」） */
        val reasoningTokens: Int = 0,
    ) : ChatEvent


    /** 一轮（一次模型请求 + 其后的工具执行）的用量与时延 */
    data class Stats(val stats: SessionStats) : ChatEvent
    data class Finished(val reason: String?) : ChatEvent
    data class Failed(val message: String, val retryable: Boolean = false) : ChatEvent
}
