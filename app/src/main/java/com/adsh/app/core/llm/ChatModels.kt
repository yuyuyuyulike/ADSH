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
    /**
     * 会话日志追加了一条事件（dsh 的 `session/event` 广播）。
     *
     * **轮内事件的界面通道只有这一种**：日志是唯一真相，界面收到的是它的**投影**
     * （见 [com.adsh.app.core.session.SessionLog]）。工具的开始 / 结算、子调用的开始 / 结算、
     * 一步 assistant 输出定稿，全都走这里 —— 界面不再有「某类事件各带一套对账」的分支。
     */
    data class Appended(val event: com.adsh.app.core.session.SessionEvent) : ChatEvent

    /**
     * 收件箱（插话 / 任务通知）里的行被认领、已经写进库（dsh 的 `preStep` → `inbox.claim`）。
     *
     * 它们**到达时只入队**，到这一步开始装配请求之前才落库 —— 位置一次定死（= dsh 日志里的位置：
     * 上一步的工具结果之后、这一步的 assistant 之前）。界面收到这条就重读一次消息，把刚认领的行
     * 画出来，同时撤掉本地回显。
     */
    data object InboxClaimed : ChatEvent
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
    /**
     * 这一步彻底失败了（不可重试：HTTP 4xx、没配密钥……）。**可重试的失败不会走到这里** ——
     * 它们由 [ChatEvent.Reconnecting] 那条路自动重连（见 `ConnectionRecovery`），
     * 所以这里不再需要 `retryable` 这个字段（第 96 轮清理：它已经没有任何读者）。
     */
    data class Failed(val message: String) : ChatEvent

    /**
     * 这一步的流断了，正在按 dsh 的退避策略重连（第 [attempt] 次，等 [delayMs] 之后重发）。
     *
     * dsh 的 ConnectionController 在丢线时发 `connecting` 状态、由 ConnectionIndicator 显示
     * 「正在连接」；这里同构：界面据此显示重连条，用户也可以点它立刻重试（dsh 的
     * `connection.reconnect()`：attempt 归零 + 立刻重试）。
     */
    data class Reconnecting(
        val attempt: Int,
        val message: String,
        val delayMs: Long,
    ) : ChatEvent

    /**
     * 重连成功（HTTP 200 已经回来）——dsh 的 `connected`：指示器切成「已恢复」并保留 2 秒。
     * 它**不代表**这一步的正文完整，只代表连接回来了。
     */
    data object Reconnected : ChatEvent

    /**
     * 重连接下来的这一次请求会**从头生成这一步**：消费者必须把上一次尝试已经收到的
     * Delta / Reasoning / ToolCallDelta 全部丢掉（[ChatEvent.Reconnected] 之前的那些增量）。
     *
     * dsh 的重连是「新的一代以完整快照开场」（a reconnect's first frame is already the whole
     * truth），客户端不保留断线期间的非持久增量 —— 这里同理：没落库的部分不留。
     */
    data object StreamReset : ChatEvent
}
