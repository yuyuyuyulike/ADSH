package com.adsh.app.core.tools

import com.adsh.app.seam.tool.ApprovalChannel

import com.adsh.app.seam.exec.Escalation

import kotlinx.serialization.json.JsonObject
import com.adsh.app.seam.exec.filePolicyOf

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
 * 解析成「这次调用被授予的模式」。顺序也是 dsh 的（重复当前模式直接放行 → 严格更宽 → 审批通道 →
 * 结论映射）；参数配对校验在 [resolveCallMode] 里先做（dsh 的两个工具族各校验一次）。
 *
 * @param requestedMode 模型给的 sandbox_permissions（null = 没有申请升级）
 * @param effectiveMode 这次调用当前的模式（权限预设 → filePolicy）
 * @return 授予的模式：没有申请时是 [effectiveMode]，重复当前模式 / 申请通过时是 [requestedMode]
 * @throws IllegalStateException dsh 的逐字错误文案（工具层把它变成这次调用的 isError 结果，什么都没跑）
 */
suspend fun approveEscalation(
    requestedMode: String?,
    effectiveMode: String,
    justification: String?,
    ask: EscalationAsk,
    ctx: ToolContext,
): String {
    if (requestedMode == null) return effectiveMode
    // dsh：repeating the effective mode returns it without approval（bash 与 fs 两个族共用这句）。
    if (requestedMode == effectiveMode) return effectiveMode
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
    Args.str(args, "sandbox_permissions")?.takeIf { it.isNotBlank() }

/** 这次调用声明的 justification（没有就是 null） */
fun justificationOf(args: JsonObject): String? =
    Args.str(args, "justification")

/**
 * 工具入口统一用这一句：把 args 里的升级申请解析成这次调用的模式。
 * 抛出的错误由调用方转成 ToolResult.Error（dsh 的 tool registry 也是这么做的）。
 *
 * @param bashRules dsh 的 bash 族在参数校验上比 fs 族松一档（dsh-tool-bash 的 validateBashArgs）：
 *   重复当前模式时连 justification 配对都不校验；没给模式时空白 justification 视同缺省。
 *   fs 族（write / edit）按 dsh-tool-fs 原样严格校验。
 */
suspend fun resolveCallMode(
    args: JsonObject,
    ctx: ToolContext,
    ask: EscalationAsk,
    bashRules: Boolean = false,
): String {
    val requested = sandboxPermissionsOf(args)
    var justification = justificationOf(args)
    if (bashRules) {
        // 重复当前模式不需要审批，也不需要理由（dsh-tool-bash 的 validateBashArgs 提前返回）。
        if (requested != null && requested == effectiveMode(ctx)) return requested
        if (requested == null && justification?.trim()?.isEmpty() == true) justification = null
    }
    Escalation.validateArgs(requested, justification)?.let { throw IllegalStateException(it) }
    return approveEscalation(
        requestedMode = requested,
        // 预设 → dsh 的模式名（filePolicyOf 是同一份映射）。
        // 以**运行时**读到的预设为准：用户在对话中途改了预设，下一次受限调用就用新的
        // （dsh 的 sandboxPolicy.resolve 每个调用现算，见 ToolContext.livePermission）。
        effectiveMode = effectiveMode(ctx),
        justification = justification,
        ask = ask,
        ctx = ctx,
    )
}

/** 这次调用当前的 dsh 沙箱模式（权限预设 → filePolicy；运行时读，长轮次里会变） */
private fun effectiveMode(ctx: ToolContext): String =
    filePolicyOf(ctx.livePermission?.invoke() ?: ctx.permission)
