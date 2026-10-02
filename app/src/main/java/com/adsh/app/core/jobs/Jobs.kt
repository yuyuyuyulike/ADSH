package com.adsh.app.core.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 后台任务注册表 —— dsh 的 `ctx.jobs`（dsh-jobs 的合同 + dsh-jobs-local 的内存实现）。
 *
 * 为什么要有这一层（dsh 的三条缝，见 docs/subsystems/jobs.md）：
 *  - **生产者**（bash）自己拥有进程，只声明 kind / label / owner，并交出一对钩子（cancel + done）；
 *  - **注册表**（这里）拥有身份、生命周期状态、有界输出环、模型游标、事件与准入；
 *  - **模型侧消费**（job_output / job_list / job_kill）只通过这张表读写，不碰进程。
 *
 * 移植到 Android 的取舍（有意，逐条写在 HANDOFF.md 的『不要破的硬规矩』里）：
 *  - 进程内单例（ADSH 是单进程、单 App），owner 就是**会话 id**；没有跨进程/持久化（进程一死，记录与
 *    子进程一起没 —— dsh 的 note 就是这么提醒的）。
 *  - 没有 pull 源与 150ms 泵：生产者本来就是「一根读线程 → append」，环就是唯一存储。dsh 的泵是
 *    为了把 subprocess 的有界观测缓冲搬进环，这里没有那一层。
 *  - 没有 spill 文件（ADSH 从来没有落盘的大输出通道），所以 lossy 读的提示里「full output」永远是
 *    (unavailable)。
 *
 * 硬语义（dsh 逐条对齐，缺一条就会出 bug）：
 *  - id = `<kind>-N`，按 kind 独立计数；**准入拒绝不消耗序号**（先判准入再发号）。
 *  - owner 栅栏：带 owner 的任务只有同会话的调用者能看/读/杀；unowned（没有会话的调用）对谁都开。
 *  - 状态机 running →（可选 stopping）→ 恰好一个终态；settle 先提交记录、再放 waiters、
 *    最后才发通知（通知里读到的 JobView 已经是终态）。
 *  - 环按 **UTF-8 字节**偏移，头部淘汰只推进 earliest、已分配的偏移永不改动；超窗口的读是 lossy
 *    而不是报错。结算时裁到 max(settledRetainBytes, total - modelCursor)：模型还没读过的字节一定留到
 *    它第一次终态读。
 *  - 两套游标：read() 是模型的消费游标（会推进），readAt() 是观察者的非消费读（界面用；绝不动游标）。
 *  - 完成通知六条抑制：awaited（wait 已经拿走终态）/ 模型自己 kill / teardown / unowned / 没有 owner。
 */
object Jobs {

    /** 运行中的环保留上限（dsh-jobs-local 的 retainBytes 默认 262144） */
    const val RETAIN_BYTES = 256 * 1024

    /** 结算后保留的上限（dsh 的 settledRetainBytes 默认 16384） */
    const val SETTLED_RETAIN_BYTES = 16 * 1024

    /** 每个 owner 允许的活跃任务数（dsh 的 maxConcurrentJobsPerOwner 默认 10） */
    const val MAX_JOBS_PER_OWNER = 10

    /** dsh 状态机的 wire 名（模型看到的、界面显示的都用它） */
    enum class Status(val wire: String) {
        RUNNING("running"),
        STOPPING("stopping"),
        COMPLETED("completed"),
        KILLED("killed"),
        FAILED("failed");

        val terminal: Boolean get() = this == COMPLETED || this == KILLED || this == FAILED
    }

    /** 输出块上的流标签：stdout / stderr 进模型，log 只给观察者（dsh 的 JobChannel） */
    enum class Channel(val wire: String) { STDOUT("stdout"), STDERR("stderr"), LOG("log") }

    /** 环里的一块：绝对字节偏移 + 原文（读出去的永远是新对象） */
    data class Chunk(
        val at: Int,
        val text: String,
        val channel: Channel? = null,
        val gapBefore: Boolean = false,
    )

    /** dsh 的 JobView（模型可见投影 + 界面要的环坐标；owner/outputLimitBytes/spill 不进模型） */
    data class View(
        val id: String,
        val kind: String,
        val label: String,
        /** 拥有它的会话（界面按当前会话过滤；模型面的投影里没有这个字段） */
        val owner: Long? = null,
        val status: Status,
        val detail: String? = null,
        val startedAt: Long = 0L,
        val finishedAt: Long? = null,
        val total: Int = 0,
        val earliest: Int = 0,
    ) {
        /** dsh 的 statusLine：`[status: completed, exit code: 0]` */
        val statusLine: String
            get() = if (detail != null) "[status: ${status.wire}, $detail]" else "[status: ${status.wire}]"

        /** 界面的可展开判据（dsh 的 isObservable）：还活着，或者还留着输出 */
        val observable: Boolean get() = !status.terminal || total > 0
    }

    /** dsh 的 JobRead：自模型游标以来的块 + lossy + 终局结果（结算后只给一次） */
    data class Read(val chunks: List<Chunk>, val lossy: Boolean, val result: String?, val job: View)

    /** dsh 的 JobOutputRead：观察者读到的一段 + 续读偏移（next 永远是块边界） */
    data class Observe(val chunks: List<Chunk>, val lossy: Boolean, val next: Int)

    /** 生产者交出的终局（dsh 的 JobOutcome；status 只允许三个终态） */
    data class Outcome(val status: Status, val detail: String? = null, val result: String? = null)

    /** 一次结算要投给 owner 会话的通知（dsh 的完成通知） */
    data class Notice(val owner: Long, val text: String)

    /** 生产者的写入口（dsh 的 JobHandle） */
    interface Handle {
        val id: String
        /** 追加一块输出；结算之后写进来的会被丢掉（dsh 只记日志） */
        fun append(text: String, channel: Channel? = null)
    }

    /** 生产者交给注册表的控制钩子（dsh 的 JobHooks） */
    interface Hooks {
        /** 请求终止：必须同步、幂等；原因原样转发。抛错会从 kill() 往外传（dsh 的契约） */
        fun cancel(reason: String?)

        /** 生产者释放资源之后 resolve，永不 reject */
        val done: CompletableDeferred<Outcome>
    }

    private enum class Cause { PRODUCER, KILL, TEARDOWN }

    private val lock = Any()
    private val store = LinkedHashMap<String, Tracked>()
    private val counters = HashMap<String, Int>()
    private val _roster = MutableStateFlow<List<View>>(emptyList())
    private val notices = CopyOnWriteArrayList<(Notice) -> Unit>()

    /** 模型自己 job_kill 过、但还没结算的 id：结算通知与工具结果重复，跳过（dsh 的账本） */
    private val killedByModel = HashSet<String>()

    /** 当前全部任务（按注册顺序，含其它会话的；界面按 owner 过滤） */
    val roster: StateFlow<List<View>> = _roster.asStateFlow()

    /** 花名册的合并窗口（dsh-api-job-controller 的 DEFAULT_OBSERVE_FLUSH_MS） */
    private const val ROSTER_FLUSH_MS = 100L

    /** 合并窗口的定时器：一次提交排一帧，窗口内的后续提交直接并进去 */
    private val rosterFlush = java.util.concurrent.ScheduledThreadPoolExecutor(
        1,
        { runnable -> Thread(runnable, "adsh-jobs-roster").apply { isDaemon = true } },
    )
    private val rosterPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 环里的一块的内部形状（缓存字节长度，省掉重复编码） */
    private class RingChunk(
        val at: Int,
        val text: String,
        val bytes: Int,
        val channel: Channel?,
        val gapBefore: Boolean,
    )

    /**
     * 有界输出环（dsh-jobs-local 的 OutputRing）：
     *  - 追加整块落地，偏移是**已追加的 UTF-8 字节总数**；
     *  - trim 从头部整块丢，earliest 只往前推；
     *  - 单独一块就超上限时只留它的 **UTF-8 安全尾**（绝不从码点中间切）并打 gapBefore。
     */
    private class OutputRing {
        val chunks = ArrayList<RingChunk>()
        var retained = 0
        var total = 0
        var earliest = 0

        fun append(text: String, channel: Channel?, gapBefore: Boolean, cap: Int): Boolean {
            if (text.isEmpty()) return false
            val bytes = text.toByteArray(Charsets.UTF_8).size
            chunks += RingChunk(total, text, bytes, channel, gapBefore)
            total += bytes
            retained += bytes
            trim(cap)
            return true
        }

        fun trim(cap: Int) {
            val limit = cap.coerceAtLeast(1)
            while (retained > limit && chunks.size > 1) retained -= chunks.removeAt(0).bytes
            val single = chunks.singleOrNull()
            if (single != null && single.bytes > limit) {
                val raw = single.text.toByteArray(Charsets.UTF_8)
                val tail = utf8Tail(raw, limit)
                chunks[0] = RingChunk(
                    at = single.at + raw.size - tail.size,
                    text = String(tail, Charsets.UTF_8),
                    bytes = tail.size,
                    channel = single.channel,
                    gapBefore = true,
                )
                retained = tail.size
            }
            earliest = if (chunks.isEmpty()) total else chunks[0].at
        }

        /** [from, total) 相交的块（新对象）；offset 落在某块内部时整块返回（dsh 的同一条规则） */
        fun readFrom(from: Int): Pair<List<Chunk>, Boolean> {
            val out = ArrayList<Chunk>()
            for (chunk in chunks) {
                if (chunk.at + chunk.bytes <= from) continue
                out += Chunk(chunk.at, chunk.text, chunk.channel, chunk.gapBefore)
            }
            return out to (from < earliest)
        }
    }

    /** 注册表里的一条记录（绝不直接交出去，读出去的都是新对象） */
    private class Tracked(
        val id: String,
        val kind: String,
        val label: String,
        val owner: Long?,
        val retainBytes: Int,
    ) {
        val ring = OutputRing()
        val startedAt = System.currentTimeMillis()
        val settled = CompletableDeferred<Unit>()
        var status = Status.RUNNING
        var detail: String? = null
        var result: String? = null
        var resultDelivered = false
        var modelCursor = 0
        var finishedAt: Long? = null
        var killReason: String? = null
        var cause = Cause.PRODUCER
        var cancel: (String?) -> Unit = {}
        var waiters = 0
        /**
         * 前台调用的「持位」：注册那一刻就记上「马上会有人 wait 我」。
         *
         * 为什么需要它（dsh 不需要）：dsh 的 waiter 是在 start 之后**同步**注册的，单线程事件
         * 循环里跑不出窗口；Kotlin 是多线程，`echo` 这种极短命令能在 wait 之前就结算 ——
         * 那条路径会既发一条完成通知、又让随后的 wait 拿到一个立刻被 remove 的 id
         * （真机实测：模型收到 bash-1 的通知，job_output bash-1 却是 unknown job）。
         */
        var held = false
        /** owner 没了（删会话 / App 收尾）：它一结算就把记录丢掉，不在内存里留看不见的记录 */
        var dropWhenSettled = false
    }

    private class HandleImpl(private val job: Tracked) : Handle {
        override val id: String get() = job.id

        override fun append(text: String, channel: Channel?) {
            // 读线程是**多根**的（stdout / stderr 各一根），环的读、裁、查都在 [lock] 里 ——
            // 这一处的写必须同一把锁：否则「一边 append 一边 readFrom/trim」会撞 ArrayList。
            synchronized(lock) {
                // 结算之后写进来的丢掉（dsh：生产者 append 到已结算的任务只记日志）
                if (job.status.terminal) return
                job.ring.append(text, channel, false, job.retainBytes)
            }
        }
    }

    /**
     * 注册一个任务（dsh 的 JobRegistry.start）：准入 → 发号 → 建环 → 调生产者 → 提交。
     * 生产者抛错 = 什么都没注册（序号已经消耗，与 dsh 一致）；返回之后注册不再有可失败步骤。
     */
    // getCompleted() 是唯一能同步读出一个已完成 deferred 的取值口（invokeOnCompletion 恰好在提交之后
    // 跑一次），它带 ExperimentalCoroutinesApi 标记 —— 这一处就是它被设计出来的用法，显式 opt-in。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start(
        kind: String,
        label: String,
        owner: Long?,
        retainBytes: Int = RETAIN_BYTES,
        /** true = 调用方随后会 wait 它；那次结算因此不会重复发通知（见 [Tracked.held]） */
        hold: Boolean = false,
        run: (Handle) -> Hooks,
    ): String {
        if (kind.isEmpty()) throw IllegalArgumentException("invalid job kind: expected a non-empty string")
        if (label.isEmpty()) throw IllegalArgumentException("invalid job label: expected a non-empty string")
        val id = synchronized(lock) {
            val active = store.values.count { it.owner == owner && !it.status.terminal }
            if (active >= MAX_JOBS_PER_OWNER) {
                throw IllegalStateException(
                    "background job limit reached for this owner (limit: $MAX_JOBS_PER_OWNER); " +
                        "use job_kill to stop an unneeded job, wait for it to finish, then retry",
                )
            }
            val count = (counters[kind] ?: 0) + 1
            counters[kind] = count
            "$kind-$count"
        }
        val job = Tracked(id, kind, label, owner, retainBytes)
        job.held = hold
        val hooks = run(HandleImpl(job))
        job.cancel = { reason -> hooks.cancel(reason) }
        synchronized(lock) { store[id] = job }
        emitRoster()
        // 生产者结束（或起不来时立刻给出 failed）→ 结算。first-wins 由 settle 内部保证。
        hooks.done.invokeOnCompletion {
            // getCompleted 只在 done 正常完成时有值；被取消（不该发生）就按「生产者契约违规」结算，
            // 绝不让异常从完成回调里炸到别人线程上
            val outcome = runCatching { hooks.done.getCompleted() }.getOrElse {
                Outcome(Status.FAILED, "producer done promise was cancelled: $it")
            }
            settle(job, outcome, job.cause)
        }
        return id
    }

    /** 调用者能看到的任务（自己的 + unowned），按注册顺序 */
    fun list(caller: Long?): List<View> = synchronized(lock) {
        store.values.filter { it.owner == null || it.owner == caller }.map { view(it) }
    }

    fun get(id: String, caller: Long?): View = view(expect(id, caller))

    /** 模型的一次消费读：自游标以来的块，游标推进到 total；终局结果只给一次 */
    fun read(id: String, caller: Long?): Read {
        val job = expect(id, caller)
        synchronized(lock) {
            val (chunks, lossy) = job.ring.readFrom(job.modelCursor)
            job.modelCursor = job.ring.total
            val result = if (job.status.terminal && !job.resultDelivered) job.result else null
            if (result != null) job.resultDelivered = true
            if (job.status.terminal) job.ring.trim(SETTLED_RETAIN_BYTES)
            return Read(chunks, lossy, result, view(job))
        }
    }

    /** 观察者的非消费读（界面展开输出面板用）：绝不动模型游标，也绝不碰通知状态 */
    fun readAt(id: String, from: Int, caller: Long?): Observe {
        require(from >= 0) { "invalid output read offset: expected a non-negative integer, got $from" }
        val job = expect(id, caller)
        synchronized(lock) {
            val (chunks, lossy) = job.ring.readFrom(from)
            return Observe(chunks, lossy, job.ring.total)
        }
    }

    /**
     * 请求取消（dsh 的 JobRegistry.kill）：`true` = 这次请求被接受，`false` = 已经结算。
     * cancel 先调、状态后改：生产者抛错时状态保持不变、错误原样往外传。
     */
    fun kill(id: String, caller: Long?, reason: String?): Boolean {
        val job = expect(id, caller)
        synchronized(lock) { if (job.status.terminal) return false }
        job.cancel(reason)
        synchronized(lock) {
            if (job.status.terminal) return false
            job.status = Status.STOPPING
            // 最后一次 kill 意图胜出（dsh 的 last writer wins）
            if (reason != null) job.killReason = reason
            job.cause = Cause.KILL
        }
        emitRoster()
        return true
    }

    /**
     * 有界等待（dsh 的 JobRegistry.wait）：只等不杀；超时是**成功的观察**（返回还在跑的投影）。
     * 这个 wait 释放了一次结算 → 那次结算算 awaited，调用者拿到的结果就是终态，不再发通知。
     */
    suspend fun wait(id: String, timeoutMs: Long, caller: Long?): View {
        require(timeoutMs > 0) {
            "invalid wait timeout: expected a positive number of milliseconds, got $timeoutMs"
        }
        val job = expect(id, caller)
        var counted = false
        synchronized(lock) {
            // 持位（前台调用在 start 那一刻记的「马上有人等」）只负责盖住「start 与 wait 之间」那个窗口：
            // 认领之后**仍然要真正占一个 waiter 名额** —— 否则「wait 期间才结算」的那次 awaited 判据是 0，
            // 前台命令的完成通知就会漏给模型（真机实测：`sleep 10` 的前台调用也收到一条通知）。
            if (job.held) job.held = false
            if (!job.status.terminal) {
                job.waiters++
                counted = true
            }
        }
        try {
            if (!job.status.terminal) withTimeoutOrNull(timeoutMs) { job.settled.await() }
        } finally {
            // 超时/取消的 wait 同步离开 waiter 集合：它没资格认领这次结算的通知
            if (counted) synchronized(lock) { job.waiters-- }
        }
        return view(job)
    }

    /**
     * 丢掉一条记录（dsh 的 JobRegistry.remove）：只允许终态。
     * 唯一用途是「前台调用自己 wait 收走了终态、而且从没把 id 交给模型」——否则 job_list 会堆满
     * 每一条已经跑完的 ls。
     */
    fun remove(id: String, caller: Long?) {
        val job = expect(id, caller)
        synchronized(lock) {
            if (!job.status.terminal) {
                throw IllegalStateException(
                    "job $id is still ${job.status.wire}; kill it and wait for settlement before removing it",
                )
            }
            store.remove(id)
        }
        emitRoster()
    }

    /** 模型自己 kill 的任务：结算时不发通知（工具结果已经把终态说清楚了） */
    fun markKilledByModel(id: String) {
        synchronized(lock) { killedByModel.add(id) }
    }

    /** 订阅结算通知（返回退订器） */
    fun onNotice(listener: (Notice) -> Unit): () -> Unit {
        notices += listener
        return { notices -= listener }
    }

    /**
     * owner（会话）没了：请求取消它的活任务、等结算、丢记录（dsh 的 disposeOwned）。
     * 这里只做到「请求取消 + 强制失败收尾」——生产者会自己结算，通知整类跳过（cause = teardown）。
     */
    fun cancelOwner(owner: Long, reason: String) {
        val mine = synchronized(lock) { store.values.filter { it.owner == owner } }
        for (job in mine) {
            if (job.status.terminal) {
                // 已经结算的记录：owner 没了就一起丢掉（dsh 的 disposeOwned 也是 drop 掉）
                synchronized(lock) { store.remove(job.id) }
                continue
            }
            job.cause = Cause.TEARDOWN
            job.dropWhenSettled = true
            val result = runCatching { job.cancel(reason) }
            synchronized(lock) { if (!job.status.terminal) job.status = Status.STOPPING }
            val error = result.exceptionOrNull()
            if (error != null) {
                // dsh：cancel 抛错只能强制失败记录 + 留痕，不能假装工作停了
                settle(
                    job,
                    Outcome(Status.FAILED, "cancel threw during teardown; work may be orphaned: $error"),
                    Cause.TEARDOWN,
                )
            }
        }
        emitRoster()
    }

    /** 注册表自己的撤销（App 关掉/View 销毁）：所有任务一起停 */
    fun cancelAll(reason: String) {
        val owners = synchronized(lock) { store.values.mapNotNull { it.owner }.distinct() }
        owners.forEach { cancelOwner(it, reason) }
    }

    /** 提交终局：先记记录、再放 waiters、最后才发通知（dsh 的顺序） */
    private fun settle(job: Tracked, outcome: Outcome, cause: Cause) {
        var awaited = false
        synchronized(lock) {
            if (job.status.terminal) return
            job.status = outcome.status
            // killed 的终局把记录的 kill 原因并在后面（生产者事实在前）；跑赢了 kill 的终局不带它
            job.detail = when {
                outcome.status == Status.KILLED && job.killReason != null ->
                    if (outcome.detail != null) "${outcome.detail}; ${job.killReason}" else job.killReason
                else -> outcome.detail
            }
            job.result = outcome.result
            job.finishedAt = System.currentTimeMillis()
            // 结算结束这条流：裁到 settled cap，但绝不裁掉模型游标还没读过的字节
            job.ring.trim(maxOf(SETTLED_RETAIN_BYTES, job.ring.total - job.modelCursor))
            awaited = job.waiters > 0 || job.held
        }
        job.settled.complete(Unit)
        if (job.dropWhenSettled) synchronized(lock) { store.remove(job.id) }
        emitRoster()
        val owner = job.owner
        if (awaited || cause == Cause.TEARDOWN || owner == null) return
        val byModel = synchronized(lock) { killedByModel.remove(job.id) }
        if (byModel) return
        val view = view(job)
        val text = "background job ${job.id} (${job.kind}: ${job.label}) finished ${view.statusLine}. " +
            "Read its output with job_output."
        for (listener in notices) runCatching { listener(Notice(owner, text)) }
    }

    private fun expect(id: String, caller: Long?): Tracked {
        val job = synchronized(lock) { store[id] } ?: throw IllegalArgumentException("unknown job $id")
        if (job.owner != null && job.owner != caller) {
            throw IllegalArgumentException("job ${job.id} belongs to another session")
        }
        return job
    }

    private fun shiftRoster() {
        _roster.value = synchronized(lock) { store.values.map { view(it) } }
    }

    /**
     * 每次生命周期提交后重算 roster（output append 不刷新，与 dsh 的 rows 流同一条规则）。
     *
     * **合并成一帧再推**（dsh 的 rows 流：`await sleep(flushMs)` 之后才 yield 一帧，
     * `DEFAULT_OBSERVE_FLUSH_MS = 100`）：一个任务结算与另一个任务注册之间会有一瞬间
     * 「本会话一个任务都没有」，界面照那一瞬间画的话「已结束」整段会展开又收起 ——
     * 用户看到的就是卡片「刷新了一下」（第 119 轮实测）。合并之后中间态根本不会推出去。
     */
    private fun emitRoster() {
        if (!rosterPending.compareAndSet(false, true)) return
        rosterFlush.schedule({
            rosterPending.set(false)
            shiftRoster()
        }, ROSTER_FLUSH_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun view(job: Tracked): View = synchronized(lock) {
        View(
            id = job.id,
            kind = job.kind,
            label = job.label,
            owner = job.owner,
            status = job.status,
            detail = job.detail,
            startedAt = job.startedAt,
            finishedAt = job.finishedAt,
            total = job.ring.total,
            earliest = job.ring.earliest,
        )
    }

    /** 一块输出的 UTF-8 安全尾：切点若落在续字节上就往后跳过（dsh 的 utf8Tail） */
    private fun utf8Tail(raw: ByteArray, maxBytes: Int): ByteArray {
        var start = (raw.size - maxBytes).coerceAtLeast(0)
        while (start < raw.size && (raw[start].toInt() and 0xC0) == 0x80) start++
        return raw.copyOfRange(start, raw.size)
    }
}
