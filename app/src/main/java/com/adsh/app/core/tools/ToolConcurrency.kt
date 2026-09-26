package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 工具执行的**全进程**并发调度器（dsh 的 `agent-loop.maxParallelToolCalls` /
 * `tools.maxParallelSubCalls`，默认 10、最小 1）。
 *
 * 为什么要有这一层：PTC 里 `Promise.all` 那一批原先各自建一个信号量，
 * 于是「程序分两批发起」也能超过上限 —— 用户实测一瞬间跑了 30 个并行工具（dsh 里只有 10）。
 *
 * **第 81 轮重写成 dsh 的调度器形状**（`packages/core/tools/src/ptc.ts` 的 per-run scheduler，约
 * 411-520 行）：一个**提交顺序的单队列**，每次只看队首 ——
 *  - 队首是「并行安全」的调用：池子里还有空位（`inFlight < maxParallel`）就开跑；
 *  - 队首是**写类**调用（bash / write / edit / 未登记的工具）：要等池子**排空**（`inFlight == 0`），
 *    开跑期间没有任何别的调用能进来（dsh 的原话："an exclusive call waits for the pool to drain,
 *    runs alone"）；
 *  - 队首开不了就整队等着（head-of-line）—— 这样写类调用不会被后面源源不断的读类调用饿死，
 *    顺序也就是提交顺序（dsh 的 "submission-ordered starts"）。
 *
 * 为什么必须这么做：提示词的 tools:sdk 段里逐字写着 dsh 那句
 * "safe calls run concurrently; mutating calls run alone, in submission order"。
 * 旧实现只有一个计数信号量 —— 读类和写类、写类和写类全都能重叠，`Promise.all([tools.write(a),
 * tools.write(b)])` 真的会同时写同一个文件：既写坏了文件，也让那句承诺变成假的（第 81 轮审查发现）。
 *
 * **可重入**（这一条是修 bug 加的）：工具自己内部再并发时（`web_search` 把多个 query
 * 扇出去就是），它在同一个调度器上再取一次许可 —— 上限是 1 的时候，外层那一下就把名额拿光了，
 * 内层永远等不到，一直到 run_code 的 120s 超时才有结果。dsh 里不存在这个问题：它的上限只挂在
 * 「一次子调用」上，工具内部做什么是工具自己的事（`ctx.web` 的扇出根本没经过 tools 的上限）。
 * 现在用协程上下文里的一个标记识别「这次调用已经在许可里」，嵌套时直接放行，
 * 与 dsh 的口径一致：**一次子调用占一个名额**。
 */
object ToolConcurrency {

    /** 没有显式配置时的上限（与设置里的默认值一致） */
    private const val DEFAULT_LIMIT = 10

    /**
     * 「并行安全」的工具（dsh 把子调用分成 parallel / exclusive 两类）：只有它们能彼此重叠。
     *
     * 其余（bash / write / edit / 以及**将来新增的、这里没登记的工具**）一律按写类处理 ——
     * 宁严勿松：漏登记只是少一点并行，错登记就是并发写坏文件。
     */
    private val SAFE_TOOLS = setOf(
        "read", "read_image", "glob", "grep", "web_fetch", "web_search",
        "present", "ask_user_question", "todo_write",
    )

    /** 这条工具调用能不能和别的调用重叠执行（见 [SAFE_TOOLS]） */
    fun isSafe(name: String): Boolean = name in SAFE_TOOLS

    /** 标记：当前协程已经持有许可（嵌套调用直接放行，不再排队也不重复计数） */
    private class Holding : AbstractCoroutineContextElement(Holding) {
        companion object Key : CoroutineContext.Key<Holding>
    }

    /** 一次子调用的调度位（dsh 的 PendingDispatch）：等 [granted] 被调度器兑现后才开跑 */
    private class Ticket(val exclusive: Boolean) {
        val granted = CompletableDeferred<Unit>()
    }

    private val lock = Any()

    /** 提交顺序的等待队列（dsh 的 pendingQueue） */
    private val queue = ArrayDeque<Ticket>()

    /** 已经拿到名额、正在跑（或马上就要跑）的子调用数 */
    private var inFlight = 0

    @Volatile
    private var maxParallel: Int = DEFAULT_LIMIT

    /** 当前正在跑的子调用数（logcat / 测试用） */
    val running: Int get() = synchronized(lock) { inFlight }

    /**
     * 应用设置里的「并行工具调用数」。立即生效：池子里正在跑的调用跑完就不再补位，
     * 直到 `inFlight < maxParallel`（计数器实现，不像以前换信号量那样必须等空闲）。
     */
    fun configure(value: Int) {
        synchronized(lock) { maxParallel = value.coerceAtLeast(1) }
        pump()
    }

    /** 测试用：把队列与计数复位（避免用例之间互相影响） */
    fun resetForTest(value: Int = DEFAULT_LIMIT) {
        synchronized(lock) {
            queue.clear()
            inFlight = 0
            maxParallel = value.coerceAtLeast(1)
        }
    }

    /** 测试用：当前协程是否已经在许可里（可重入的判据就是它） */
    internal suspend fun holdingNow(): Boolean = coroutineContext[Holding.Key] != null

    /** 并行安全的调用（read / grep / web_* 这些）：与其它安全调用重叠，最多 [configure] 个 */
    suspend fun <T> withPermit(block: suspend () -> T): T = schedule(exclusive = false, block)

    /**
     * **写类**调用（bash / write / edit / 未知工具）：等池子排空、独自跑完，
     * 与 dsh 的 exclusive 分类一致。
     */
    suspend fun <T> withExclusivePermit(block: suspend () -> T): T = schedule(exclusive = true, block)

    /** 按工具名分类：并行安全的走 [withPermit]，其余（含未知工具）走 [withExclusivePermit] */
    suspend fun <T> withToolPermit(toolName: String, block: suspend () -> T): T =
        schedule(exclusive = !isSafe(toolName), block)

    private suspend fun <T> schedule(exclusive: Boolean, block: suspend () -> T): T {
        // 嵌套调用（工具内部的并发）不占新的名额 —— 判据必须在**入队之前**，
        // 否则它会留下一个永远没人跑的调度位，把整个队列堵死
        if (coroutineContext[Holding.Key] != null) return block()
        val ticket = synchronized(lock) { Ticket(exclusive).also { queue.addLast(it) } }
        pump()
        ticket.granted.await()
        try {
            return withContext(Holding()) { block() }
        } finally {
            synchronized(lock) { if (inFlight > 0) inFlight-- }
            pump()
        }
    }

    /** 只让「队首且放得下」的调度位开跑；开不了就整队等着（head-of-line） */
    private fun pump() {
        synchronized(lock) {
            while (true) {
                val head = queue.firstOrNull() ?: return
                val fits = if (head.exclusive) inFlight == 0 else inFlight < maxParallel
                if (!fits) return
                queue.removeFirst()
                inFlight++
                head.granted.complete(Unit)
            }
        }
    }
}
