package com.adsh.app.core.jobs

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表本体的行为（[Jobs]）—— 这一刀**没有改生产代码**，只把 [Jobs] 开头那段「硬语义」清单
 * 从注释变成用例（R23 织网那一套手法：先有网，再谈拆）。
 *
 * 为什么值得：`Jobs` 是纯 Kotlin（整个文件没有一处 `android.` 引用），却一条用例都没有 ——
 * 而它管着**后台命令的身份、状态机、输出账本与完成通知**。这几条错了都只在真机上表现为
 * 「模型收到一条指向不存在的任务的通知」这类怪事（第 118 轮真机实测过）。
 *
 * 注册表是**进程内单例**（跨用例共享），所以每个用例都用**自己的 kind 与 owner**
 * （[nextKind] / [nextOwner]）：id 只跟 kind 有关、可见性只跟 owner 有关，这样断言才与执行顺序无关。
 */
class JobsRegistryTest {

    /** 一个可手动驱动的生产者：拿住 handle、记下 cancel 调用、由用例决定何时结算 */
    private class Producer(private val kind: String, private val label: String = "ls") {
        val done = CompletableDeferred<Jobs.Outcome>()
        val cancels = ArrayList<String?>()
        private var handle: Jobs.Handle? = null

        fun start(owner: Long?, hold: Boolean = false): String =
            Jobs.start(kind, label, owner, hold = hold) { h ->
                handle = h
                object : Jobs.Hooks {
                    override fun cancel(reason: String?) {
                        cancels += reason
                    }

                    override val done: CompletableDeferred<Jobs.Outcome> get() = this@Producer.done
                }
            }

        fun append(text: String, channel: Jobs.Channel = Jobs.Channel.STDOUT) {
            handle!!.append(text, channel)
        }

        fun finish(
            status: Jobs.Status = Jobs.Status.COMPLETED,
            detail: String? = "exit code: 0",
            result: String? = null,
        ) {
            done.complete(Jobs.Outcome(status, detail, result))
        }
    }

    companion object {
        private val seq = AtomicLong(0L)

        /** 每次调用给一个**只属于这个用例**的 kind：id 因此是 `<kind>-1` 这种可预期的形态 */
        private fun nextKind(): String = "t" + seq.incrementAndGet()
        private fun nextOwner(): Long = 1000L + seq.incrementAndGet()
    }

    // ---------------------------------------------------------------- 身份与准入

    @Test
    fun `id 按 kind 独立计数`() {
        val owner = nextOwner()
        val k1 = nextKind()
        val k2 = nextKind()
        assertEquals(k1 + "-1", Producer(k1).start(owner))
        assertEquals(k1 + "-2", Producer(k1).start(owner))
        assertEquals(k2 + "-1", Producer(k2).start(owner))
    }

    @Test
    fun `kind 与 label 不能是空串`() {
        val owner = nextOwner()
        assertThrows(IllegalArgumentException::class.java) {
            Jobs.start("", "x", owner) { throw AssertionError("不该走到生产者") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Jobs.start(nextKind(), "", owner) { throw AssertionError("不该走到生产者") }
        }
    }

    /** 准入拒绝**不消耗序号**（先判准入再发号）：满了被拒之后，腾出位置拿到的还是下一个号 */
    @Test
    fun `准入拒绝不消耗序号`() {
        val owner = nextOwner()
        val kind = nextKind()
        val producers = (1..Jobs.MAX_JOBS_PER_OWNER).map { Producer(kind).also { p -> p.start(owner) } }
        assertThrows(IllegalStateException::class.java) { Producer(kind).start(owner) }
        // 让出一个活跃名额（结算 + 丢掉记录）
        producers.first().finish()
        Jobs.remove(kind + "-1", owner)
        assertEquals(kind + "-" + (Jobs.MAX_JOBS_PER_OWNER + 1), Producer(kind).start(owner))
    }

    // ------------------------------------------------------------------ owner 栅栏

    @Test
    fun `list 只给本会话的与 unowned 的`() {
        val mine = nextOwner()
        val other = nextOwner()
        val owned = Producer(nextKind()).start(mine)
        val foreign = Producer(nextKind()).start(other)
        val unowned = Producer(nextKind()).start(null)
        val seen = Jobs.list(mine).map { it.id }
        assertTrue(owned in seen)
        assertTrue(unowned in seen)
        assertFalse(foreign in seen)
    }

    @Test
    fun `看不到别的会话的任务：读、杀、删都拦`() {
        val mine = nextOwner()
        val other = nextOwner()
        val id = Producer(nextKind()).start(other)
        assertThrows(IllegalArgumentException::class.java) { Jobs.get(id, mine) }
        assertThrows(IllegalArgumentException::class.java) { Jobs.read(id, mine) }
        assertThrows(IllegalArgumentException::class.java) { Jobs.kill(id, mine, "nope") }
        assertThrows(IllegalArgumentException::class.java) { Jobs.remove(id, mine) }
        // 没有会话的调用者只看得见 unowned 的：有主的一律拦（JobRulesTest 里那条栅栏的另一半）
        val unowned = Producer(nextKind()).start(null)
        assertNotNull(Jobs.get(unowned, null))
        assertThrows(IllegalArgumentException::class.java) { Jobs.get(id, null) }
    }

    // -------------------------------------------------------------- 状态机与结算

    @Test
    fun `结算一次：终态之后 kill 不再接受`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        assertEquals(Jobs.Status.RUNNING, Jobs.get(id, owner).status)
        p.finish(Jobs.Status.COMPLETED)
        val settled = Jobs.get(id, owner)
        assertEquals(Jobs.Status.COMPLETED, settled.status)
        assertEquals("exit code: 0", settled.detail)
        assertNotNull(settled.finishedAt)
        assertFalse(Jobs.kill(id, owner, "too late"))
        assertEquals(0, p.cancels.size)
    }

    @Test
    fun `kill：先叫生产者停，再把状态改成 stopping`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        assertTrue(Jobs.kill(id, owner, "user pressed stop"))
        assertEquals(listOf<String?>("user pressed stop"), p.cancels)
        assertEquals(Jobs.Status.STOPPING, Jobs.get(id, owner).status)
        // 生产者随后结算成 killed：记录里把 kill 原因并在它自己的 detail 后面
        p.finish(Jobs.Status.KILLED, "signal 9")
        assertEquals("signal 9; user pressed stop", Jobs.get(id, owner).detail)
    }

    @Test
    fun `wait 拿到终态，并且这次结算算 awaited（不发通知）`() = runBlocking {
        val owner = nextOwner()
        val notices = ArrayList<String>()
        val off = Jobs.onNotice { if (it.owner == owner) notices += it.text }
        try {
            val p = Producer(nextKind())
            // hold = true 就是前台调用的形态：在 start 那一刻先记上「马上会有人 wait 我」，
            // 盖住「start 与 wait 之间」那个窗口 —— 否则极短命令会在 wait 之前结算，
            // 既发一条通知、又让随后的 wait 拿到一个立刻被 remove 的 id（第 118 轮真机实测）
            val id = p.start(owner, hold = true)
            p.append("hello")
            p.finish()
            val view = Jobs.wait(id, 5_000, owner)
            assertEquals(Jobs.Status.COMPLETED, view.status)
            assertEquals(0, notices.size)
        } finally {
            off()
        }
    }

    @Test
    fun `没人等的任务结算时给 owner 发一条通知`() {
        val owner = nextOwner()
        val notices = ArrayList<String>()
        val off = Jobs.onNotice { if (it.owner == owner) notices += it.text }
        try {
            val p = Producer(nextKind(), "sleep 5")
            val id = p.start(owner)
            p.finish(Jobs.Status.FAILED, "exit code: 1")
            assertEquals(1, notices.size)
            assertEquals(
                "background job " + id + " (t" + id.removePrefix("t").substringBefore("-") +
                    ": sleep 5) finished [status: failed, exit code: 1]. Read its output with job_output.",
                notices[0],
            )
        } finally {
            off()
        }
    }

    @Test
    fun `模型自己 kill 的不发通知`() {
        val owner = nextOwner()
        val notices = ArrayList<String>()
        val off = Jobs.onNotice { if (it.owner == owner) notices += it.text }
        try {
            val p = Producer(nextKind())
            val id = p.start(owner)
            Jobs.markKilledByModel(id)
            p.finish(Jobs.Status.KILLED, "killed")
            assertEquals(0, notices.size)
        } finally {
            off()
        }
    }

    @Test
    fun `unowned 的任务不发通知（没有会话可通知）`() {
        val notices = ArrayList<String>()
        val off = Jobs.onNotice { notices += it.text }
        try {
            Producer(nextKind()).also { it.start(null) }.finish()
            assertEquals(0, notices.size)
        } finally {
            off()
        }
    }

    // -------------------------------------------------------------- 输出与游标

    @Test
    fun `read 推进模型游标，第二次读只剩新追加的`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        p.append("first")
        assertEquals("first", Jobs.read(id, owner).chunks.joinToString("") { it.text })
        assertTrue(Jobs.read(id, owner).chunks.isEmpty())
        p.append("second")
        assertEquals("second", Jobs.read(id, owner).chunks.joinToString("") { it.text })
    }

    @Test
    fun `终局结果只给一次`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        p.finish(Jobs.Status.COMPLETED, "exit code: 0", result = "the answer")
        assertEquals("the answer", Jobs.read(id, owner).result)
        assertNull(Jobs.read(id, owner).result)
    }

    @Test
    fun `readAt 不动模型游标`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        p.append("abc")
        val observed = Jobs.readAt(id, 0, owner)
        assertEquals("abc", observed.chunks.joinToString("") { it.text })
        assertEquals(3, observed.next)
        // 观察读之后，模型仍然能拿到全部
        assertEquals("abc", Jobs.read(id, owner).chunks.joinToString("") { it.text })
    }

    // ------------------------------------------------------------ 删除与 teardown

    @Test
    fun `remove 只允许终态`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        assertThrows(IllegalStateException::class.java) { Jobs.remove(id, owner) }
        p.finish()
        Jobs.remove(id, owner)
        assertThrows(IllegalArgumentException::class.java) { Jobs.get(id, owner) }
    }

    @Test
    fun `owner 没了：请求取消 + 结算后把记录丢掉`() {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        Jobs.cancelOwner(owner, "session closed")
        assertEquals(listOf<String?>("session closed"), p.cancels)
        assertEquals(Jobs.Status.STOPPING, Jobs.get(id, owner).status)
        p.finish(Jobs.Status.KILLED, "signal 9")
        assertThrows(IllegalArgumentException::class.java) { Jobs.get(id, owner) }
    }

    @Test
    fun `cancelAll 覆盖所有 owner`() {
        val a = nextOwner()
        val b = nextOwner()
        val pa = Producer(nextKind()).also { it.start(a) }
        val pb = Producer(nextKind()).also { it.start(b) }
        Jobs.cancelAll("app closing")
        assertEquals(1, pa.cancels.size)
        assertEquals(1, pb.cancels.size)
    }

    @Test
    fun `wait 超时是成功的观察（返回还在跑的投影）`() = runBlocking {
        val owner = nextOwner()
        val p = Producer(nextKind())
        val id = p.start(owner)
        val view = Jobs.wait(id, 20, owner)
        assertEquals(Jobs.Status.RUNNING, view.status)
        assertNull(view.finishedAt)
        p.finish()
    }
}
