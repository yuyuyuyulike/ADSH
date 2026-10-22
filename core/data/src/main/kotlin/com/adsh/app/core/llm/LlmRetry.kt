package com.adsh.app.core.llm

/**
 * 一次模型请求失败后的**重试策略** —— 对齐 dsh 的 llm-retry 插件默认策略
 * （packages/llm/llm/src/retry-policy.ts：mode=normal、maxRetries=5、
 * retryableCodes=[EMPTY_RESPONSE,RATE_LIMIT,SERVER,TIMEOUT,TRANSPORT]、
 * initialDelayMs=500、maxDelayMs=10000、jitterRatio=0.1）。
 *
 * 预算粒度：dsh 是「每个 step × 每个 provider + 完整策略键」；ADSH 没有 provider 策略对象，
 * [LlmClient.stream] 每次调用（= 每一步）新建一个 budget，所以「每步最多重试
 * [MAX_RETRIES] 次、换模型/通道开新预算」与它同义。
 *
 * 重试发生在**同一个 step 内**（重新发一次请求），不会新开一轮；失败的那一次尝试已经收到的
 * 增量要作废（[ChatEvent.StreamReset]，dsh 的 resetForRetry）。
 */
object LlmRetry {

    /** dsh 默认策略的 maxRetries */
    const val MAX_RETRIES = 5

    /** dsh 默认策略的 initialDelayMs */
    const val INITIAL_DELAY_MS = 500L

    /** dsh 默认策略的 maxDelayMs */
    const val MAX_DELAY_MS = 10_000L

    /** dsh 默认策略的 jitterRatio */
    const val JITTER_RATIO = 0.1

    /**
     * dsh 默认策略的 retryableCodes。本客户端只产生这五种：
     * 其余 4xx（请求本身不对）与取消一律不重试、直接终态。
     */
    val RETRYABLE_CODES = setOf("EMPTY_RESPONSE", "RATE_LIMIT", "SERVER", "TIMEOUT", "TRANSPORT")

    fun isRetryable(code: String?): Boolean = code != null && code in RETRYABLE_CODES

    /**
     * 第 [retry] 次重试（1 基）之前的等待：`min(initial * 2^(retry-1), max)`，再乘抖动系数
     * `1 - jitter + 2 * jitter * random`（dsh 的 backoffDelay）—— 结果落在 cap 的 90%..110%。
     *
     * @param random 0..1 的随机数（单测注入常数用）
     */
    fun backoffDelayMs(retry: Int, random: Double = Math.random()): Long {
        val exponent = (retry - 1).coerceAtLeast(0)
        val grown = INITIAL_DELAY_MS * Math.pow(2.0, exponent.toDouble())
        val cap = if (grown >= MAX_DELAY_MS.toDouble()) MAX_DELAY_MS else grown.toLong().coerceAtLeast(1L)
        val factor = 1.0 - JITTER_RATIO + 2.0 * JITTER_RATIO * random.coerceIn(0.0, 1.0)
        return (cap * factor).toLong().coerceAtLeast(1L)
    }
}

/**
 * 上下文窗口超限的规范化失败 code（dsh 的 CONTEXT_WINDOW_EXCEEDED）。
 *
 * 触发条件：HTTP 400 且错误正文命中 [CONTEXT_OVERFLOW_MARKERS]。它**不在**
 * [LlmRetry.RETRYABLE_CODES] 里 —— 重发同一个请求不会变小；只有自动压缩接管之后才允许再试一次
 * （每个 turn 最多一次，见 core/agent 的 CompactionHooks）。
 */
const val CONTEXT_WINDOW_EXCEEDED = "CONTEXT_WINDOW_EXCEEDED"

/** 服务端「请求超出上下文窗口」的正文特征（各提供方错误文案；大小写不敏感）。 */
private val CONTEXT_OVERFLOW_MARKERS = listOf(
    "maximum context length",
    "context length",
    "prompt is too long",
    "reduce the length",
)

/**
 * 这次 HTTP 失败是不是「上下文超限」。
 *
 * 只有 HTTP 400 + 正文命中特征才算：其余 400 是请求本身不对（参数 / 历史形状），压缩救不了。
 */
fun isContextOverflow(status: Int, body: String): Boolean =
    status == 400 && CONTEXT_OVERFLOW_MARKERS.any { body.contains(it, ignoreCase = true) }
