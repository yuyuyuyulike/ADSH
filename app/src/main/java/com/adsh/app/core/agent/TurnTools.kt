package com.adsh.app.core.agent

/**
 * [AgentLoop] 面对「工具世界」的唯一接触面（甲方案第二步）。
 *
 * **为什么需要它**：AgentLoop 以前直接拿 [com.adsh.app.core.tools.ToolContext]，而它要一个
 * `TermuxRuntime(Context)` —— 纯 JVM 单测里根本构造不出来。结果是整条**工具轮**（请求带上
 * run_code 的 schema、日志里补成对的 call/result、工具行落库、死循环判据）一直没有测试，
 * 而那正是 runTurnBody 里最难改的一段。
 *
 * 生产实现是 AgentLoop 内部的 `ToolContextTools`（ToolContext + 本轮的会话日志）；
 * 测试用假实现（见 AgentLoopToolRoundTest），工具轮就也能跑在 JVM 里。
 *
 * 端口故意只有两样东西：**工作区**（提示词要它、轮尾要记 lastWorkspacePath）与
 * **执行一次顶层调用**。子调用轨迹 / 审批 / 图片都不在端口的语义里 —— 它们属于实现。
 */
internal interface TurnTools {

    /** 这一轮的工作区：提示词的 workspace 段与 `lastWorkspacePath` 都读它。 */
    val workspace: com.adsh.app.core.workspace.Workspace

    /**
     * 执行一次顶层工具调用，返回 (输出, 是否错误)。
     *
     * [callId] 是模型给的 id（审批面板要显示是哪一次调用），[execToken] 是宿主生成的
     * 进程内单调序号（PTC 子调用的归属写在它上面）。两者都由调用方给，实现不许自己造。
     */
    suspend fun run(name: String, argsJson: String, callId: String?, execToken: Long): Pair<String, Boolean>
}
