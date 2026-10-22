package com.adsh.app.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.adsh.app.seam.llm.ApiProtocol
import com.adsh.app.seam.llm.DEEPSEEK_ID
import com.adsh.app.seam.llm.DEEPSEEK_NAME

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
    /**
     * DashScope 兼容模式（qwen 系）的思考开关：false = 这次不思考；null = 不发（别的路由没这个字段）。
     *
     * 为什么标题那次小调用需要它：qwen 系默认会思考，而标题只要 64 个输出 token，推理会把预算
     * 整段吃光 —— 实测这条路由（ws-*.maas.aliyuncs.com）基线下推理 187-290 字、2.8-7.3 秒、
     * content 为空，标题只能退回兜底值；发 enable_thinking=false 之后推理 0 字、1.6 秒、标题正常。
     */
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
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

    /**
     * 解码窗口（dsh 的 decodeMs）：模型**开始出字之后**到这一步收尾的那一段，TPS 的分母。
     *
     * dsh 每个 assistant 步各记一条 completedTime − firstTokenTime 再求和（dsh-client-ui-chat
     * 的 assistantStepReading / deriveStats），而「步耗时之和」减「首 token 时延之和」正好等于它
     * —— 同一批步、同一个起点：Sum(completed − firstToken) = Sum(completed − start) − Sum(firstToken − start)。
     * 所以这里直接从**已经存进库的两个数**推出来，不额外加列（库表不用迁移）。
     */
    val decodeMillis: Long get() = (llmMillis - ttftMillis).coerceAtLeast(0)

    /** 解码窗口里产出的 token（dsh 的 decodeTokens：每个 assistant 步的 outputTokens 之和） */
    val decodeTokens: Long get() = completionTokens

    /**
     * 输出速度（dsh 的 message.tokensPerSecond / stats.dialog.speed）：**只算解码窗口**。
     *
     * **分母不能是整段模型用时（llmMillis）**：那样首 token 之前的排队与 prefill 全被算进
     * 「速度」里：新会话首轮要冷 prefill 8.5K token，同样一段回答显示 10 tok/s，第二轮命中
     * 前缀缓存显示 11 tok/s（用户报的「首轮模型速度慢好多」）；换成 dsh 口径后两轮都在
     * 50 tok/s 上下，差的就是那点 prefill 本身（TTFT 上照实体现）。
     */
    val tps: Double get() = if (decodeMillis <= 0) 0.0 else decodeTokens * 1000.0 / decodeMillis

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
    val api: String = ApiProtocol.OPENAI_COMPLETIONS,
    /** 提供方 id 与展示名（dsh 的 provider / displayName） */
    val providerId: String = DEEPSEEK_ID,
    val providerName: String = DEEPSEEK_NAME,
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
    /** 本轮模型用时合计（各步 completed − start 之和；TPS 的分母由它减首 token 时延得到） */
    val llmMillis: Long = 0,
    /** 本轮工具用时合计（各步工具执行耗时之和） */
    val toolMillis: Long = 0,
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

    /**
     * **主请求已经写到连接上**（dsh 的会话事件 request/header；dsh 在 agent-loop 装配请求时
     * append 它、紧接着发主流，ADSH 这条由 LlmClient 在请求体写进连接之后发 —— 见那里的注释：
     * 只对齐 dsh 的事件位置做不到「标题一定排在主请求后面」，主请求体 30 多 KB 要编码 + 上传，
     * 标题那 1KB 反而会先上网，真机日志实测早 19ms）。
     *
     * **标题那次小调用订在这一刻**（dsh 的 `SessionTitleService.onRequestHeader`）：主请求已经
     * 写到连接上之后再并发去要标题，既不挡主请求、也不抢在它前面上网（见上面那段实测）。
     */
    data object RequestHeader : ChatEvent
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
     * 这一步彻底失败了（重试预算耗尽 / 不可重试的失败：HTTP 4xx、没配密钥……）。
     * **可重试的失败不走到这里** —— 它们由 [RetryScheduled] 转成 dsh 的会话流重试行。
     *
     * [code] 是终态失败的规范化 code（dsh 的 LlmError.code）；自动恢复按它分流：
     * [CONTEXT_WINDOW_EXCEEDED] 表示请求超出上下文窗口，压缩可以接管重试；其余（含 null）原样透传。
     */
    data class Failed(val message: String, val code: String? = null) : ChatEvent

    /**
     * 这一次尝试失败、**已经调度一次重试**（dsh 的 `llm/retry`，log-only 事件）：
     * [retry] 从 1 起、[maximum] = [LlmRetry.MAX_RETRIES]、[delayMs] 退避时长、
     * [failure] 是失败原因（界面展开体里的「失败原因」）。界面按 dsh 的 model-retry 行渲染
     * —— 它是**会话流里的一行**，不是输入框边上的连接条。
     */
    data class RetryScheduled(
        val retry: Int,
        val maximum: Int,
        val delayMs: Long,
        val failure: String,
    ) : ChatEvent

    /**
     * 退避等待结束、下一次请求即将发出（dsh 的 `llm/retry-started`）：同一行从「正在重试」
     * 切成「已重试模型请求」。重试成功后这一行保持「已重试」（dsh 的恢复上下文行）。
     */
    data class RetryStarted(val retry: Int) : ChatEvent

    /**
     * 重试会**重新生成这一步**：消费者必须把上一次尝试已经收到的 Delta / Reasoning /
     * ToolCallDelta 全部丢掉（与 dsh 客户端收到 `llm/retry` 时清空 assistant 流一致）。
     */
    data object StreamReset : ChatEvent

    /**
     * 自动压缩开始（dsh 的 `compaction/start`）：界面把「压缩中」状态亮起来。
     *
     * 只有自动触发（[CompactionCause.PRESSURE] / [CompactionCause.OVERFLOW]）走这条通道；
     * 手动 /compact 由界面自己置状态。
     */
    data class CompactionStarted(val cause: CompactionCause) : ChatEvent

    /**
     * 自动压缩结束（dsh 的 `compaction/end`）。
     *
     * [applied] = 是否真的有替换落地；false 表示这次触发什么都没改（历史照旧、界面只需收起压缩中）。
     */
    data class CompactionFinished(val cause: CompactionCause, val applied: Boolean) : ChatEvent
}

/** 压缩的触发原因（dsh 的 CompactionTrigger）。 */
enum class CompactionCause {
    /** 用户显式 /compact */
    MANUAL,

    /** 请求装配前的阈值压力（window × 80%） */
    PRESSURE,

    /** 模型请求报上下文超限后的强制恢复 */
    OVERFLOW,
}
