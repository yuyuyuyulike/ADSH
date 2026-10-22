package com.adsh.app.core.agent

import com.adsh.app.core.tools.ToolOutcome

/**
 * [AgentLoop] 面对「工具世界」的唯一接触面（甲方案第二步）。
 *
 * **为什么需要它**：AgentLoop 不能直接拿 [com.adsh.app.core.tools.ToolContext]（它要一个
 * `TermuxRuntime(Context)`，纯 JVM 单测里根本构造不出来），否则整条**工具轮**（请求带上
 * run_code 的 schema、日志里补成对的 call/result、工具行落库、死循环判据）就没法测试，
 * 而那正是 runTurnBody 里最难改的一段。
 *
 * 生产实现是 AgentLoop 内部的 `ToolContextTools`（ToolContext + 本轮的会话日志）；
 * 测试用假实现（见 AgentLoopToolRoundTest），工具轮就也能跑在 JVM 里。
 *
 * 端口故意只有两样东西：**工作区**（提示词要它）与
 * **执行一次顶层调用**。子调用轨迹 / 审批 / 图片都在返回值 [ToolOutcome] 里 —— 它们的生产属于实现。
 */
internal interface TurnTools {

    /** 这一轮的工作区：提示词的 workspace 段读它。 */
    val workspace: com.adsh.app.core.workspace.Workspace

    /**
     * 执行一次顶层工具调用，返回规范化结果（[ToolOutcome] 的正文已脱敏）。
     *
     * [callId] 是模型给的 id（审批面板要显示是哪一次调用），[execToken] 是宿主生成的
     * 进程内单调序号（PTC 子调用的归属写在它上面）。两者都由调用方给，实现不许自己造。
     */
    suspend fun run(name: String, argsJson: String, callId: String?, execToken: Long): ToolOutcome
}
