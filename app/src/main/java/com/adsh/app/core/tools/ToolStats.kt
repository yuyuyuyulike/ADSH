package com.adsh.app.core.tools

import java.util.concurrent.atomic.AtomicInteger

/**
 * 子调用步数计数器：dsh 状态栏里的「N 轮 M 步」中的「步」= 工具调用次数
 * （PTC 的一次 run_code 算一步，程序内每个 await tools.x() 也算一步）。
 * 每次向模型发起一轮请求前由 AgentLoop 取走并清零。
 */
object ToolStepCounter {
    private val counter = AtomicInteger(0)

    fun bump(steps: Int = 1) {
        if (steps > 0) counter.addAndGet(steps)
    }

    /** 取走累计步数并清零 */
    fun drain(): Int = counter.getAndSet(0)
}

/**
 * 子调用轨迹旁路：QuickJS 运行时不知道会话/数据库，AgentLoop 又拿不到
 * [ToolResult] 之外的返回值，于是用这个一次性通道把 run_code 的程序内调用明细
 * 交给 AgentLoop 落库（供「轨迹」视图还原）。
 */
object SubCallTrace {
    @Volatile private var pending: List<com.adsh.app.core.ptc.SubCall> = emptyList()

    fun record(calls: List<com.adsh.app.core.ptc.SubCall>) {
        if (calls.isNotEmpty()) pending = calls
    }

    fun drain(): List<com.adsh.app.core.ptc.SubCall> {
        val current = pending
        pending = emptyList()
        return current
    }
}
