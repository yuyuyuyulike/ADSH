package com.adsh.app.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 会话流里的**模型请求重试**（dsh 的 model-retry 节点）：
 * 一次失败的请求尝试与其退避等待，在会话流里占**一行**（同一条重试链只有一行，attempt 原地更新）。
 *
 * dsh 的 `RetryChatData` 有两个字段（attempts / current）；ADSH 只需要当前 attempt：
 * 历史 attempt 只在「（retry/maximum）」里体现。`cancelled` 不是事件，而是**渲染时**按
 * 「这一轮已经结束、最后一条 attempt 仍是 scheduled」推导出来的（dsh 的 retry.ts）。
 */
data class RetryInfo(
    /** 第几次重试，1 基（dsh 的 LlmRetryEventData.retry） */
    val retry: Int,
    /** 重试上限（dsh 的 normal 模式 maxRetries；always 模式在 ADSH 没有对应） */
    val maximum: Int,
    /** 这一次退避的等待时长（毫秒） */
    val delayMs: Long,
    /** 失败原因（展开体里的「失败原因」：dsh 对已知 code 本地化，其余原样显示 message） */
    val failure: String,
    /** 退避结束、请求已重发（dsh 的 `llm/retry-started`）——之后这一行固定显示「已重试」 */
    val started: Boolean,
)

/** 重试行在存储里的正文（消息 role = "retry"，只给界面用、不进模型请求） */
internal object RetryInfoCodec {

    fun encode(info: RetryInfo): String = buildJsonObject {
        put("retry", info.retry)
        put("maximum", info.maximum)
        put("delayMs", info.delayMs)
        put("failure", info.failure)
        put("started", info.started)
    }.toString()

    fun decode(raw: String): RetryInfo? = runCatching {
        val obj = Json.parseToJsonElement(raw) as JsonObject
        RetryInfo(
            retry = obj["retry"]?.jsonPrimitive?.intOrNull ?: return null,
            maximum = obj["maximum"]?.jsonPrimitive?.intOrNull ?: return null,
            delayMs = obj["delayMs"]?.jsonPrimitive?.longOrNull ?: return null,
            failure = obj["failure"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            started = obj["started"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }.getOrNull()
}

/** 重试行的显示阶段（dsh 的 ModelRetryNode.retryState） */
internal enum class RetryPhase {
    /** 正在退避等待、倒计时中（dsh 的 active 文案；dsh 的 scheduled 文案在这个接线下不可达） */
    Active,

    /** 退避结束、请求已重发（行保留为「已重试」） */
    Started,

    /** 最后一条 attempt 仍是 scheduled，而轮次已经结束（用户停止 / 轮尾失败） */
    Cancelled,
}

/** 由数据 + 轮次是否已结束推导阶段（dsh 的 retry.ts buildViewNode：cancelled 只在边界关闭时派生） */
internal fun retryPhase(info: RetryInfo, turnClosed: Boolean): RetryPhase = when {
    info.started -> RetryPhase.Started
    turnClosed -> RetryPhase.Cancelled
    else -> RetryPhase.Active
}

/** dsh 的 `message.retry.status`：`{label}（{retry}/{maximum}） · {seconds}s`（中文全角括号） */
internal fun retryStatusText(info: RetryInfo, phase: RetryPhase, seconds: Int): String {
    val label = when (phase) {
        RetryPhase.Active -> "正在重试模型请求"
        RetryPhase.Started -> "已重试模型请求"
        RetryPhase.Cancelled -> "模型请求重试已取消"
    }
    return label + "（" + info.retry + "/" + info.maximum + "） · " + seconds + "s"
}

/** dsh 的 retrySeconds：ceil 到秒、**下限 1 秒**（永不显示 0） */
internal fun retrySeconds(remainingMs: Long): Int =
    maxOf(1, kotlin.math.ceil(remainingMs.coerceAtLeast(0L).toDouble() / 1000.0).toInt())

/** started / cancelled 用的静态秒数：ceil(delayMs / 1000) */
internal fun retryStaticSeconds(info: RetryInfo): Int = retrySeconds(info.delayMs)

/** 展开体第一行：dsh 的 `重试延迟：` + `duration.milliseconds`（四舍五入到毫秒） */
internal fun retryDelayText(info: RetryInfo): String =
    "重试延迟：" + java.lang.Math.round(info.delayMs.toDouble()) + "毫秒"

/** 展开体第二行：dsh 的 `失败原因：` + failure.message */
internal fun retryFailureText(info: RetryInfo): String = "失败原因：" + info.failure
