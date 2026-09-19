package com.adsh.app.core.tools

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 工具执行的**全进程**并发闸门（dsh 的 `agent-loop.maxParallelToolCalls` / `tools.maxParallelSubCalls`，
 * 默认 10、最小 1）。
 *
 * 为什么要有这一层：PTC 里 `Promise.all` 那一批原先各自建一个信号量，
 * 于是「程序分两批发起」也能超过上限 —— 用户实测一瞬间跑了 30 个并行工具（dsh 里只有 10）。
 * 闸门做成全进程一个，任何路径（批量调用 / 单次调用）都从这里取许可。
 *
 * 语义与 dsh 对齐：并行安全的子调用最多重叠 limit 个；limit=1 就是串行。
 *
 * **可重入**（这一条是修 bug 加的）：工具自己内部再并发时（`web_search` 把多个 query
 * 扇出去就是），它在同一个信号量上再取一次许可 —— 并行调用一多就会**自己等自己**，
 * 一直到 run_code 的 120s 超时才有结果。dsh 里不存在这个问题：它的上限只挂在
 * 「一次子调用」上，工具内部做什么是工具自己的事（`ctx.web` 的扇出根本没经过 tools 的上限）。
 * 现在用协程上下文里的一个标记识别「这次调用已经在许可里」，嵌套时直接放行，
 * 与 dsh 的口径一致：**一次子调用占一个名额**。
 */
object ToolConcurrency {

    /** 没有显式配置时的上限（与设置里的默认值一致） */
    private const val DEFAULT_LIMIT = 10

    /** 标记：当前协程已经持有许可（嵌套调用直接放行，不再排队也不重复计数） */
    private class Holding : AbstractCoroutineContextElement(Holding) {
        companion object Key : CoroutineContext.Key<Holding>
    }

    private val inFlight = AtomicInteger(0)

    @Volatile
    private var limit: Int = DEFAULT_LIMIT

    @Volatile
    private var pendingLimit: Int? = null

    @Volatile
    private var gate: Semaphore = Semaphore(DEFAULT_LIMIT)

    /** 当前正在跑的子调用数（logcat / 测试用） */
    val running: Int get() = inFlight.get()

    /**
     * 应用设置里的「并行工具调用数」。空闲时立即生效；
     * 正在跑工具时记下来，等这一批结束（回到空闲）再换信号量 —— 不能把有人持锁的信号量换掉。
     */
    fun configure(value: Int) {
        val next = value.coerceAtLeast(1)
        if (next == limit) return
        if (inFlight.get() == 0) {
            limit = next
            gate = Semaphore(next)
            pendingLimit = null
        } else {
            pendingLimit = next
        }
    }

    /** 测试用：把计数与上限复位（避免用例之间互相影响） */
    fun resetForTest(value: Int = DEFAULT_LIMIT) {
        limit = value.coerceAtLeast(1)
        gate = Semaphore(limit)
        pendingLimit = null
        inFlight.set(0)
    }

    /** 测试用：当前协程是否已经在许可里（可重入的判据就是它） */
    internal suspend fun holdingNow(): Boolean = coroutineContext[Holding.Key] != null

    /** 取一个许可再执行：并发数永远不会超过当前上限；已经在许可里的嵌套调用直接放行 */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        if (coroutineContext[Holding.Key] != null) return block()
        return gate.withPermit {
            inFlight.incrementAndGet()
            try {
                withContext(Holding()) { block() }
            } finally {
                inFlight.decrementAndGet()
                val pending = pendingLimit
                if (pending != null && inFlight.get() == 0) configure(pending)
            }
        }
    }
}
