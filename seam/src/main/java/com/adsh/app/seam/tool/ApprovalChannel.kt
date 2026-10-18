package com.adsh.app.seam.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 审批通道（dsh 的 ctx.approval.request）：工具挂起等待，UI 观察 [pending] 并调用
 * [allowOnce] / [reject] 解除挂起。**失败关闭**：没有可用的应答方（超时、App 不在前台、
 * 协程被取消）时，一律不成事 —— dsh 的 unavailable / cancelled。
 */
object ApprovalChannel {

    /** dsh 的审批结论词汇（EscalationOutcome）：只有一次性授权，没有「总是允许」 */
    enum class Outcome { ALLOWED_ONCE, REJECTED, CANCELLED, UNAVAILABLE }

    /** 一条待审批的请求（dsh 的 PendingApproval：工具名 + 理由 + 可选 callId / 细节） */
    data class Pending(
        val token: String,
        val toolName: String,
        /** 人可读的理由（dsh 的 reason：escalate sandbox to <mode>: <justification>） */
        val reason: String,
        /** 工具自己补充的细节（bash 的命令行 / 文件路径），dsh 走 conversation.approval.detail 槽位 */
        val detail: String? = null,
        val callId: String? = null,
        val completer: CompletableDeferred<Outcome>,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * 同一时刻只允许一个问题挂着：PTC 里一个程序可以并发发出多个子调用，
     * 两个都申请升级时后一个会把前一个的 pending 顶掉（前一个只能等超时 → unavailable）。
     * 串行化之后第二个会在第一个问完再问，用户逐个拍板。
     */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /**
     * 问一次用户。
     * @param timeoutMs 等待上限（对齐 ask_user_question 的 10 分钟；超时按 unavailable 失败关闭）
     */
    suspend fun request(
        toolName: String,
        reason: String,
        detail: String?,
        callId: String?,
        timeoutMs: Long,
    ): Outcome {
        return lock.withLock {
            val completer = CompletableDeferred<Outcome>()
            val token = UUID.randomUUID().toString()
            _pending.value = Pending(token, toolName, reason, detail, callId, completer)
            try {
                withTimeoutOrNull(timeoutMs) { completer.await() } ?: Outcome.UNAVAILABLE
            } finally {
                // 只有还是自己这一条时才清（用户可能在超时后又点了按钮，那是下一条请求的事）
                if (_pending.value?.token == token) _pending.value = null
            }
        }
    }

    /** 允许一次（dsh 的 allowed-once） */
    fun allowOnce() {
        _pending.value?.completer?.complete(Outcome.ALLOWED_ONCE)
    }

    /** 拒绝（dsh 的 rejected） */
    fun reject() {
        _pending.value?.completer?.complete(Outcome.REJECTED)
    }
}
