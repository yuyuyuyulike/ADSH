package com.adsh.app.seam.exec

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
 *    只有真的越权写时 shim 才回 marker + hint；
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
