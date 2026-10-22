package com.adsh.app.core.agent.compaction

import com.adsh.app.core.agent.AgentHooks
import com.adsh.app.core.llm.CONTEXT_WINDOW_EXCEEDED
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.CompactionCause
import kotlinx.coroutines.CancellationException

/**
 * 把 [CompactionService] 接到主循环的两个决策点上（dsh 的 compaction-basic 自动触发）：
 *
 *  - pressure：每个 step 装配请求前检查 token 压力（window × 80%）；超阈值先剪枝，仍超才摘要。
 *  - overflow：请求报 [CONTEXT_WINDOW_EXCEEDED] 时忽略阈值强制压一次；只有 [CompactionResult.progress]
 *    （历史确实变短 / 有替换落地）才要求主循环重试。
 *
 * 失败语义：压缩是可选能力，**失败不打断这一轮** —— pressure 失败照常发请求，overflow 失败保留
 * 原始请求错误（dsh 的 pre-step 监听器同口径）。取消除外，取消必须原样抛。
 *
 * 自动压缩没有计划模式信息（[AgentHooks] 端口不带它）：摘要请求的系统提示词按非计划模式装配。
 */
class CompactionHooks(private val service: CompactionService) : AgentHooks {

    override suspend fun beforeStep(conversationId: Long, emit: (ChatEvent) -> Unit): Boolean {
        val window = service.contextWindow
        if (window <= 0) return true
        // 未达阈值时一个事件都不发：压缩中条不能每步闪一下
        if (!service.overPressure(conversationId, window)) return true
        emit(ChatEvent.CompactionStarted(CompactionCause.PRESSURE))
        val result = compactIfPossible(conversationId, window, CompactionCause.PRESSURE)
        emit(ChatEvent.CompactionFinished(CompactionCause.PRESSURE, result.applied))
        return true
    }

    override suspend fun onRequestError(
        conversationId: Long,
        failure: ChatEvent.Failed,
        emit: (ChatEvent) -> Unit,
    ): Boolean {
        if (failure.code != CONTEXT_WINDOW_EXCEEDED) return false
        val window = service.contextWindow
        if (window <= 0) return false
        emit(ChatEvent.CompactionStarted(CompactionCause.OVERFLOW))
        val result = compactIfPossible(conversationId, window, CompactionCause.OVERFLOW)
        emit(ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, result.applied))
        // 只有 durable progress 才证明重试不会原样再撞一次窗口
        return result.progress
    }

    private suspend fun compactIfPossible(
        conversationId: Long,
        window: Long,
        cause: CompactionCause,
    ): CompactionResult =
        try {
            service.compact(
                conversationId = conversationId,
                window = window,
                planMode = false,
                trigger = cause.trigger,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            CompactionResult(applied = false, progress = false)
        }

    private val CompactionCause.trigger: CompactionTrigger
        get() = when (this) {
            CompactionCause.MANUAL -> CompactionTrigger.Manual
            CompactionCause.PRESSURE -> CompactionTrigger.Pressure
            CompactionCause.OVERFLOW -> CompactionTrigger.Overflow
        }
}
