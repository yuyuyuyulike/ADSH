package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 沙箱升级（sandbox escalation）—— 逐字对齐 dsh-sandbox/lib/index.js 的 escalation 段。
 *
 * dsh 的编排（bash 与 fs 两个强制族共用同一份实现，这里也共用）：
 *  1. 模型先用**当前预设**跑一次；内核/围栏拒绝时，工具结果里带上 denialMarker 与
 *     hintMarker，模型据此知道「可以申请一次更宽的权限」；
 *  2. 模型重试时带 sandbox_permissions（更宽的模式）+ justification（一句话理由），
 *     两者必须成对出现；
 *  3. approveEscalation 先做「严格更宽」检查，再走审批通道问用户，
 *     只有 allowed-once 才把这次调用换成更宽的模式，别的结果一律失败关闭（什么都不执行）。
 *
 * 与 dsh 的差别（Android 侧没有内核沙箱：没有 bwrap / seatbelt / Landlock）：
 *  - dsh 的 read-only / workspace-write 都是「命令照跑，写效果由沙箱判决」；ADSH 用
 *    LD_PRELOAD 的 libadshfence.so 做到同一件事（read-only = 空白名单，workspace-write =
 *    工作区 + 临时目录 + $ADSH_SCRATCH），所以**纯读命令不会再带上提权提示** ——
 *    只有真的越权写时 shim 才回 marker + hint（第 56 轮修正；此前 read-only 整条拒掉 bash）；
 *  - 文件类工具（write / edit）按路径围栏，拒绝文案同样是 marker + hint（见 Args.gateWrite）；
 *  - 「严格更宽」的梯子、拒绝文案、审核文案与 dsh 完全一致。
 */
object Escalation {

    /** dsh 的沙箱模式名（也是 prompt 里 filePolicy 的取值） */
    const val READ_ONLY = "read-only"
    const val WORKSPACE_WRITE = "workspace-write"
    const val FULL_ACCESS = "danger-full-access"

    /**
     * dsh 的 WIDER_MODES：一次调用当前模式是 key 时，**允许升级到**的值列表。
     * 只在执行期判（绝不能写进 schema 的 enum —— schema 是全局的，模式是每次调用的真相）。
     */
    val WIDER_MODES: Map<String, List<String>> = mapOf(
        READ_ONLY to listOf(WORKSPACE_WRITE, FULL_ACCESS),
        WORKSPACE_WRITE to listOf(FULL_ACCESS),
    )

    /** dsh 的 sandboxDenialMarker：两个强制族共用的一条拒绝标记，模型据此识别「这是策略拒绝」 */
    fun denialMarker(mode: String): String = "[sandbox: file access denied under " + mode + " mode]"

    /** dsh 的 escalationHintMarker：拒绝的同一条结果里就给出升级路径，不指望模型自己想起来 */
    fun hintMarker(subject: String): String =
        "[sandbox: escalation available — retry this exact " + subject +
            " once with sandbox_permissions (the narrowest wider mode that suffices) + justification; " +
            "the approval prompt asks the user]"

    /** 拒绝正文 = dsh 的两行标记（先 marker、再 hint） */
    fun denial(mode: String, subject: String): String =
        denialMarker(mode) + "\n" + hintMarker(subject)

    /**
     * dsh 的 validateEscalationArgs：schema 表达不了的成对约束。
     * @return 错误原文；null = 合法
     */
    fun validateArgs(sandboxPermissions: String?, justification: String?): String? {
        if (sandboxPermissions != null && justification == null) {
            return "invalid escalation: sandbox_permissions requires a justification"
        }
        if (justification != null && sandboxPermissions == null) {
            return "invalid escalation: justification is only valid together with sandbox_permissions"
        }
        if (justification != null && justification.trim().isEmpty()) {
            return "invalid justification: expected a non-empty sentence"
        }
        return null
    }
}

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

    /** 这一轮被取消 / 面板被关掉（dsh 的 cancelled） */
    fun cancel() {
        _pending.value?.completer?.complete(Outcome.CANCELLED)
    }
}

/**
 * 一次调用的沙箱编排（dsh 的 approveEscalation + 由调用方提供的 subject / detail）。
 *
 * @param subject dsh 里这一族的名词：bash 是 "command"，文件改动是 "operation"
 * @param detail 展示给用户的具体内容（命令行 / 路径），dsh 由 tool 自己的 detail 槽位渲染
 */
data class EscalationAsk(
    val toolName: String,
    val callId: String?,
    val subject: String,
    val detail: String? = null,
)

/**
 * dsh 的 approveEscalation：在**任何东西执行之前**把一次 sandbox_permissions 请求
 * 解析成「这次调用被授予的模式」。顺序也是 dsh 的（先严格更宽、再审批通道、最后映射结论）。
 *
 * @param requestedMode 模型给的 sandbox_permissions（null = 没有申请升级）
 * @param effectiveMode 这次调用当前的模式（权限预设 → filePolicy）
 * @return 授予的模式：没有申请时是 [effectiveMode]，申请通过时是 [requestedMode]
 * @throws IllegalStateException dsh 的逐字错误文案（工具层把它变成这次调用的 isError 结果，什么都没跑）
 */
suspend fun approveEscalation(
    requestedMode: String?,
    effectiveMode: String,
    justification: String?,
    ask: EscalationAsk,
    ctx: ToolContext,
): String {
    Escalation.validateArgs(requestedMode, justification)?.let { throw IllegalStateException(it) }
    if (requestedMode == null) return effectiveMode
    if (!(Escalation.WIDER_MODES[effectiveMode] ?: emptyList()).contains(requestedMode)) {
        throw IllegalStateException(
            "sandbox escalation to \"" + requestedMode + "\" is not strictly wider than this call's " +
                "current \"" + effectiveMode + "\" mode",
        )
    }
    val outcome = ApprovalChannel.request(
        toolName = ask.toolName,
        // dsh 的 reason 原文
        reason = "escalate sandbox to " + requestedMode + ": " + (justification ?: ""),
        detail = ask.detail,
        callId = ask.callId,
        timeoutMs = ctx.approvalTimeoutMs,
    )
    return when (outcome) {
        ApprovalChannel.Outcome.ALLOWED_ONCE -> requestedMode
        ApprovalChannel.Outcome.REJECTED ->
            throw IllegalStateException(
                "the user rejected escalating this " + ask.subject + " to \"" + requestedMode + "\"",
            )
        ApprovalChannel.Outcome.CANCELLED ->
            throw IllegalStateException("approval for escalating to \"" + requestedMode + "\" was cancelled")
        ApprovalChannel.Outcome.UNAVAILABLE ->
            throw IllegalStateException(
                "sandbox escalation to \"" + requestedMode + "\" requires approval, but no approval " +
                    "channel is available",
            )
    }
}

/** 这次调用声明的 sandbox_permissions（没有就是 null） */
fun sandboxPermissionsOf(args: JsonObject): String? =
    args["sandbox_permissions"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** 这次调用声明的 justification（没有就是 null） */
fun justificationOf(args: JsonObject): String? =
    args["justification"]?.jsonPrimitive?.contentOrNull

/**
 * 工具入口统一用这一句：把 args 里的升级申请解析成这次调用的模式。
 * 抛出的错误由调用方转成 ToolResult.Error（dsh 的 tool registry 也是这么做的）。
 */
suspend fun resolveCallMode(args: JsonObject, ctx: ToolContext, ask: EscalationAsk): String =
    approveEscalation(
        requestedMode = sandboxPermissionsOf(args),
        // 预设 → dsh 的模式名（PromptAssembler.filePolicyOf 是同一份映射）。
        // 以**运行时**读到的预设为准：用户在对话中途改了预设，下一次受限调用就用新的
        // （dsh 的 sandboxPolicy.resolve 每个调用现算，见 ToolContext.livePermission）。
        effectiveMode = com.adsh.app.core.agent.PromptAssembler.filePolicyOf(
            ctx.livePermission?.invoke() ?: ctx.permission,
        ),
        justification = justificationOf(args),
        ask = ask,
        ctx = ctx,
    )
