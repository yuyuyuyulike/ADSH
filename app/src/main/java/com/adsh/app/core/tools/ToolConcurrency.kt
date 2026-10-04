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
     * 「可并行」工具名的**排序副本**：系统提示词的 tools:sdk 段直接读它（第九十一轮）。
     *
     * 以前提示词只说「safe calls run concurrently」，哪些工具算 safe 得自己猜 —— 而 `bash` 与
     * 只读工具在 schema 上完全同形，猜错就是把写操作塞进 `Promise.all`。清单从实现里生成、
     * 再用一条单测钉住，就不会出现「提示词说 A、调度器按 B」的漂移。
     */
    val safeToolList: List<String> = SAFE_TOOLS.sorted()

    /** 标记：当前协程已经持有许可（嵌套调用直接放行，不再排队也不重复计数） */
    private class Holding : AbstractCoroutineContextElement(Holding) {
        companion object Key : CoroutineContext.Key<Holding>
    }

    /**
     * 一次子调用的调度位（dsh 的 PendingDispatch）：等 [granted] 被调度器兑现后才开跑。
     *
     * [state] 让「兑现」与「归还」互斥且幂等（0 = 排队中、1 = 已兑现并占了名额、2 = 已归还）：
     * 取消可能发生在 `granted.await()` 上 —— 那一刻名额**可能已经被 pump 记上了**，
     * 不还回去的话 `inFlight` 就永久少一格（攒够 maxParallel 之后所有写类调用一起卡死）。
     * 反过来，还没兑现就取消的调度位一次都不能减，所以减计数只走 [release] 这一条路。
     */
    private class Ticket(val exclusive: Boolean) {
        val granted = CompletableDeferred<Unit>()
        val state = java.util.concurrent.atomic.AtomicInteger(STATE_QUEUED)
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
     * 没有任何新调度位被放行**，所以读类的放行条件里也要看这个计数（第 98 轮的单测抓到的）。
     */
    private var exclusiveInFlight = 0

    @Volatile
    private var maxParallel: Int = DEFAULT_LIMIT

    /** 当前正在跑的子调用数（测试用） */
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
            exclusiveInFlight = 0
            maxParallel = value.coerceAtLeast(1)
        }
    }

    /** 测试用：当前协程是否已经在许可里（可重入的判据就是它） */
    internal suspend fun holdingNow(): Boolean = coroutineContext[Holding.Key] != null

    /**
     * 取一次子调用许可，按**工具名**分类：并行安全的走读类（可与其它读类重叠、最多 [configure] 个），
     * 其余（含未知工具）走写类（等池子排空、独自跑完）。这是生产代码唯一的入口 ——
     * `QuickJsRuntime` 的每一次 `tools.x()` 都从这里过闸，别再另开一条按「安全 / 独占」直接取许可的
     * 旁路：分类表（[SAFE_TOOLS]）才是唯一真源。
     */
    suspend fun <T> withToolPermit(toolName: String, block: suspend () -> T): T =
        schedule(exclusive = !isSafe(toolName), block)

    /**
     * 批调用（`Promise.all`）那套序号链在第 186 轮删掉了：绑定现在**每次调用各自一帧、各自异步**
     * （见 [com.adsh.app.core.ptc.QuickJsRuntime] 的 PREAMBLE），宿主把它们按
     * **到达顺序**登记到这张队列上 —— 派发器是单线程的（[com.adsh.app.core.ptc.PtcProcess]），
     * 所以「谁的调度位在前」就等于「模型写的顺序」：写类调用照样独占、照样按提交顺序跑，
     * 读类照样彼此重叠。旧实现（第 98 轮）为了让**一次批量 RPC** 里的顺序可依赖，在批内按
     * 下标逐个跑写类；那条约束现在由生产者侧保证，闸门这边只剩 [withToolPermit] 一个入口。
     */
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
     *  - 读类：还有名额（`inFlight < maxParallel`）**且没有写类在跑**（`exclusiveInFlight == 0`）——
     *    后半句就是「写类跑的时候没有任何别的调用能进来」，没有它独占只兑现一半。
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
}
