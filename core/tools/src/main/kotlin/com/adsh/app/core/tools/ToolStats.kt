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
    /**
     * **累积**到 [drain] 取走为止（一次 run_code 里的每条 `await tools.x()` 结算时各报一条）。
     *
     * 必须累积（`addAll`）而不是整份替换：唯一的生产者（PtcToolRunner.record）每次只报**一条**，
     * 替换会让一次 run_code 里调了十个工具、落库只剩最后一条。
     * 子调用可以并行结算（读类调用各自在自己的 IO 线程上跑），所以用同步列表。
     */
    private val pending = java.util.Collections.synchronizedList(ArrayList<com.adsh.app.seam.ptc.SubCall>())

    fun record(calls: List<com.adsh.app.seam.ptc.SubCall>) {
        if (calls.isEmpty()) return
        synchronized(pending) { pending.addAll(calls) }
    }

    fun drain(): List<com.adsh.app.seam.ptc.SubCall> = synchronized(pending) {
        val current = pending.toList()
        pending.clear()
        current
    }
}
