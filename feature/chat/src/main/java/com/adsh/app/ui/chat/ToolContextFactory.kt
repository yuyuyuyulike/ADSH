package com.adsh.app.ui.chat

import com.adsh.app.core.tools.ToolContext

/**
 * 工具上下文工厂：**消费方定义、组合根实现**（:app 的 ToolingHost）。
 *
 * 全局项（运行时 / 14 项设置 / PTC 提供者实现）在实现侧装配；本模块只给每轮才变的东西，
 * 这样 chat 不认识 PTC 运行时实现（com.adsh.app.platform.ptc），依赖方向保持 :app → :feature:chat。
 */
fun interface ToolContextFactory {
    fun contextFor(
        conversationId: Long,
        workspacePath: String?,
        turn: Int,
        planMode: Boolean,
        onPlanModeChanged: (Boolean) -> Unit,
    ): ToolContext
}
