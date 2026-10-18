package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 一次 PTC 程序执行内的工具调度器（dsh 的 `agent-loop.maxParallelToolCalls` /
 * `tools.maxParallelSubCalls`，默认 10、最小 1）。
 *
 * **每个 run_code 一份，不是全进程一份**（dsh 的 per-run scheduler）：调用方超时 / worker 崩溃时
 * 在途的子调用可能被打断在半路（CPU 计算没有协作取消点），它占着的调度位就不一定及时归还 ——
 * 共用一份实例时，下一次 run_code 的每条调用都会排在它后面，整个网关永久卡死（真机实测：
 * 一次 edit 挂住之后连参数校验都进不去）。每轮一份，卡住的调度位随本轮一起丢掉。
 *
 * 调度形状照 dsh（`packages/core/tools/src/ptc.ts` 的 per-run scheduler）：
 * 一个**提交顺序的单队列**，每次只看队首 ——
 *  - 队首是「并行安全」的调用：池子里还有空位（`inFlight < maxParallel`）就开跑；
 *  - 队首是**写类**调用（bash / write / edit / 未登记的工具）：要等池子**排空**（`inFlight == 0`），
 *    开跑期间没有任何别的调用能进来（dsh："an exclusive call waits for the pool to drain,
 *    runs alone"）；
 *  - 队首开不了就整队等着（head-of-line）—— 写类调用不会被后面源源不断的读类调用饿死，
 *    顺序也就是提交顺序（dsh 的 "submission-ordered starts"）。
 *
 * **不能只用一个计数信号量**：那样读类和写类、写类和写类全都能重叠，
 * `Promise.all([tools.write(a), tools.write(b)])` 真的会同时写同一个文件 —— 既写坏文件，也让
 * 提示词里 "safe calls run concurrently; mutating calls run alone, in submission order" 变成假的。
 *
 * **可重入**：工具自己内部若再取一次许可（嵌套调用），直接放行 —— 上限只挂在「一次子调用」上，
 * 工具内部做什么是工具自己的事（dsh 的口径）。没有这个标记，上限是 1 时外层那一下就把名额拿光了，
 * 内层永远等不到。协程上下文里的标记识别「这次调用已经在许可里」。
 */
class ToolConcurrency(maxParallel: Int = DEFAULT_LIMIT) {

    /** 一次子调用的调度位（dsh 的 PendingDispatch）：等 [granted] 被调度器兑现后才开跑。 */
    private class Ticket(val exclusive: Boolean) {
        val granted = CompletableDeferred<Unit>()
        val state = java.util.concurrent.atomic.AtomicInteger(STATE_QUEUED)
    }

    /** 标记：当前协程已经持有许可（嵌套调用直接放行，不再排队也不重复计数） */
    private class Holding : AbstractCoroutineContextElement(Holding) {
        companion object Key : CoroutineContext.Key<Holding>
    }

    private val lock = Any()

    /** 提交顺序的等待队列（dsh 的 pendingQueue） */
    private val queue = ArrayDeque<Ticket>()

    /** 已经拿到名额、正在跑（或马上就要跑）的子调用数 */
    private var inFlight = 0

    /**
     * 正在跑的**写类**子调用数（0 或 1：写类之间本来就串行）。
     *
     * 没有它，「独占」只兑现了一半：写类确实会**等到池子排空才开始**，但它一开始跑，
     * 排在它后面的读类调度位照样满足 `inFlight < maxParallel`，于是被放进来与它重叠 ——
     * 实测现场就是「读着文件的时候 bash 把它改掉」。真正的独占要求**它在跑的整段时间里
     * 没有任何新调度位被放行**，所以读类的放行条件里也要看这个计数。
     */
    private var exclusiveInFlight = 0

    @Volatile
    private var maxParallel: Int = maxParallel.coerceAtLeast(1)

    /** 当前正在跑的子调用数（测试用） */
    val running: Int get() = synchronized(lock) { inFlight }

    /** 应用设置里的「并行工具调用数」；立即生效（跑完的调用不再补位，直到低于上限） */
    fun configure(value: Int) {
        synchronized(lock) { maxParallel = value.coerceAtLeast(1) }
        pump()
    }

    /** 测试用：当前协程是否已经在许可里（可重入的判据就是它） */
    internal suspend fun holdingNow(): Boolean = coroutineContext[Holding.Key] != null

    /**
     * 取一次子调用许可，按**工具名**分类：并行安全的走读类（可与其它读类重叠、最多 [configure] 个），
     * 其余（含未知工具）走写类（等池子排空、独自跑完）。这是生产代码唯一的入口 ——
     * `QuickJsRuntime` 的每一次 `tools.x()` 都从这里过闸。
     */
    suspend fun <T> withToolPermit(toolName: String, block: suspend () -> T): T =
        schedule(exclusive = !isSafe(toolName), block)

    private suspend fun <T> schedule(exclusive: Boolean, block: suspend () -> T): T {
        // 嵌套调用（工具内部的并发）不占新的名额 —— 判据必须在**入队之前**，
        // 否则它会留下一个永远没人跑的调度位，把整个队列堵死
        if (coroutineContext[Holding.Key] != null) return block()
        val ticket = synchronized(lock) { Ticket(exclusive).also { queue.addLast(it) } }
        pump()
        try {
            ticket.granted.await()
        } catch (t: Throwable) {
            // 取消（用户中断 / 看门狗）打在等名额上：名额可能已经兑现，必须还回去
            release(ticket)
            throw t
        }
        try {
            return withContext(Holding()) { block() }
        } finally {
            release(ticket)
        }
    }

    /**
     * 兑现一个调度位（必须在 [lock] 里调用）。
     *
     * CAS 决定「这个调度位算不算占了名额」：[release] 只看同一个 [Ticket.state]，
     * 所以「兑现」与「取消后归还」这两条路不会各减一次、也不会一次都不减。
     */
    private fun grant(ticket: Ticket) {
        if (!ticket.state.compareAndSet(STATE_QUEUED, STATE_GRANTED)) return
        inFlight++
        if (ticket.exclusive) exclusiveInFlight++
        ticket.granted.complete(Unit)
    }

    /**
     * 归还一个调度位（幂等，两条路都走它）：
     *  - **已兑现**（[STATE_GRANTED]）：减计数 —— 取消可能打在 `granted.await()` 上；
     *  - **还在排队**（[STATE_QUEUED]）：从队列里摘掉 —— 不摘的话 pump 之后会兑现一个**没人等**的
     *    调度位，名额就此永久占住（攒够上限后所有写类调用一起卡死）。
     *
     * 与 [grant] 靠同一个 [Ticket.state] 的 CAS 定胜负，所以不会出现「两边各减一次」或
     * 「一次都没减」：要么 pump 先兑现（我们减计数），要么我们先作废（pump 的 grant 落空）。
     */
    private fun release(ticket: Ticket) {
        if (ticket.state.compareAndSet(STATE_GRANTED, STATE_RELEASED)) {
            synchronized(lock) {
                if (inFlight > 0) inFlight--
                if (ticket.exclusive && exclusiveInFlight > 0) exclusiveInFlight--
            }
            pump()
            return
        }
        if (ticket.state.compareAndSet(STATE_QUEUED, STATE_RELEASED)) {
            synchronized(lock) { queue.remove(ticket) }
            pump()
        }
    }

    /**
     * 只让「队首且放得下」的调度位开跑；开不了就整队等着（head-of-line）。
     *
     * 「放得下」对两类调用不一样：
     *  - 写类：池子必须**空**（`inFlight == 0`）—— 它要独占；
     *  - 读类：还有名额（`inFlight < maxParallel`）**且没有写类在跑**（`exclusiveInFlight == 0`）。
     */
    private fun pump() {
        synchronized(lock) {
            while (true) {
                val head = queue.firstOrNull() ?: return
                val fits = if (head.exclusive) {
                    inFlight == 0
                } else {
                    inFlight < maxParallel && exclusiveInFlight == 0
                }
                if (!fits) return
                queue.removeFirst()
                grant(head)
            }
        }
    }

    companion object {
        /** 没有显式配置时的上限（与设置里的默认值一致） */
        const val DEFAULT_LIMIT = 10

        /** [Ticket.state] 的三个取值（见 Ticket 的注释） */
        private const val STATE_QUEUED = 0
        private const val STATE_GRANTED = 1
        private const val STATE_RELEASED = 2

        /**
         * 「并行安全」的工具（dsh 把子调用分成 parallel / exclusive 两类）：只有它们能彼此重叠。
         *
         * 其余（bash / write / edit / 以及**将来新增的、这里没登记的工具**）一律按写类处理 ——
         * 宁严勿松：漏登记只是少一点并行，错登记就是并发写坏文件。
         */
        private val SAFE_TOOLS = setOf(
            "read", "read_image", "glob", "grep", "web_fetch", "web_search",
            "present", "ask_user_question", "todo_write",
            // 后台任务的读侧（job_output / job_list）：只读注册表，可以与其他读类重叠。
            // **job_kill 不在这里**：它要杀进程，按写类独占处理（与 bash 同档）。
            "job_output", "job_list",
        )

        /** 这条工具调用能不能和别的调用重叠执行（见 [SAFE_TOOLS]） */
        fun isSafe(name: String): Boolean = name in SAFE_TOOLS

        /**
         * 「可并行」工具名的**排序副本**：系统提示词的 tools:sdk 段直接读它。
         *
         * **不要让提示词只写「safe calls run concurrently」**：哪些工具算 safe 得自己猜 ——
         * 而 `bash` 与只读工具在 schema 上完全同形，猜错就是把写操作塞进 `Promise.all`。清单从实现里
         * 生成、再用一条单测钉住，就不会出现「提示词说 A、调度器按 B」的漂移。
         */
        val safeToolList: List<String> = SAFE_TOOLS.sorted()
    }
}
