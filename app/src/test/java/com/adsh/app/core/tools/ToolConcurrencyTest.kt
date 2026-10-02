package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并行工具闸门：30 个并发请求 + 上限 10，实际重叠数不能超过 10
 * （用户实测 dsh 里 10、ADSH 里一瞬间 30，这条测试就是钉住这个上限）。
 */
class ToolConcurrencyTest {

    @Test(timeout = 30_000)
    fun neverExceedsConfiguredLimit() = runBlocking {
        ToolConcurrency.configure(10)
        var peakInside = 0
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        (1..30).map {
            async(Dispatchers.Default) {
                ToolConcurrency.withToolPermit("read") {
                    val now = running.incrementAndGet()
                    if (now > peakInside) peakInside = now
                    Thread.sleep(5)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()
        assertTrue("重叠数 $peakInside 超过了上限 10", peakInside <= 10)
        assertEquals(0, ToolConcurrency.running)
    }

    @Test(timeout = 30_000)
    fun limitOneIsSerial() = runBlocking {
        ToolConcurrency.configure(1)
        var peakInside = 0
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        (1..8).map {
            async(Dispatchers.Default) {
                ToolConcurrency.withToolPermit("read") {
                    val now = running.incrementAndGet()
                    if (now > peakInside) peakInside = now
                    Thread.sleep(2)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, peakInside)
    }

    /**
     * 闸门必须**可重入**：工具自己内部再并发时（web_search 把多个 query 扇出去就是）
     * 会在同一个信号量上再取一次许可 —— 上限是 1 的时候，外层那一下就把许可拿光了，
     * 内层永远等不到，一直到 run_code 的 120s 超时才有结果。
     * dsh 里不存在这个问题：它的上限只挂在「一次子调用」上，工具内部的事与它无关。
     */
    @Test(timeout = 30_000)
    fun nestedFanOutDoesNotDeadlockAtLimitOne() = runBlocking {
        ToolConcurrency.resetForTest(1)
        val done = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("read") {
                // 模拟 web_search 的 queries 扇出：真实代码是 coroutineScope { async { … } }，
                // 扇出的 scope 从**当前协程**派生（才带得上「已持有许可」的标记）
                kotlinx.coroutines.coroutineScope {
                    (1..4).map { async(Dispatchers.Default) { ToolConcurrency.withToolPermit("read") { it } } }.awaitAll()
                }
            }
        }
        assertEquals(listOf(1, 2, 3, 4), kotlinx.coroutines.withTimeout(5_000) { done.await() })
        assertEquals("嵌套调用不该重复计数", 0, ToolConcurrency.running)
    }

    /** 嵌套调用占的还是同一个名额：并发数不会因为可重入而被放大 */
    @Test(timeout = 30_000)
    fun nestedCallsStillCountAsOneSlot() = runBlocking {
        ToolConcurrency.resetForTest(2)
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        var peakInside = 0
        (1..8).map {
            async(Dispatchers.Default) {
                ToolConcurrency.withToolPermit("read") {
                    val now = running.incrementAndGet()
                    if (now > peakInside) peakInside = now
                    kotlinx.coroutines.coroutineScope {
                        (1..3).map { async(Dispatchers.Default) { ToolConcurrency.withToolPermit("read") { Thread.sleep(2) } } }
                            .awaitAll()
                    }
                    running.decrementAndGet()
                }
            }
        }.awaitAll()
        assertTrue("重叠数 $peakInside 超过了上限 2", peakInside <= 2)
        assertEquals(0, ToolConcurrency.running)
        ToolConcurrency.resetForTest(10)
    }

    // ------------------------------------------------------------ 写类独占（第 81 轮）

    /**
     * 写类调用（bash / write / edit / 未知工具）**独占**：同时刻最多一个在跑。
     *
     * dsh 的 tools:sdk 段里逐字写着 "safe calls run concurrently; mutating calls run alone,
     * in submission order"。ADSH 以前只有一个计数信号量 —— 那句承诺是假的：
     * `Promise.all([tools.write(a), tools.write(b)])` 真的会同时写同一个文件。
     */
    @Test(timeout = 30_000)
    fun exclusiveCallsNeverOverlap() = runBlocking {
        ToolConcurrency.resetForTest(4)
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        var peakInside = 0
        (1..6).map {
            async(Dispatchers.Default) {
                ToolConcurrency.withToolPermit("bash") {
                    val now = running.incrementAndGet()
                    if (now > peakInside) peakInside = now
                    Thread.sleep(5)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals("写类调用重叠了", 1, peakInside)
        assertEquals(0, ToolConcurrency.running)
        ToolConcurrency.resetForTest(10)
    }

    /** 写类调用要等**正在跑的读类调用**跑完才进来（run alone 的另一半） */
    @Test(timeout = 30_000)
    fun exclusiveWaitsForRunningSafeCalls() = runBlocking {
        ToolConcurrency.resetForTest(4)
        val safeInside = CompletableDeferred<Unit>()
        val releaseSafe = CompletableDeferred<Unit>()
        val writerStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val safe = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("read") {
                safeInside.complete(Unit)
                releaseSafe.await()
            }
        }
        safeInside.await()
        val writer = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("bash") {
                writerStarted.set(true)
            }
        }
        // 读类还在跑：写类必须还在等（给它两帧的时间，别把「还没调度到」误判成「等着」）
        Thread.sleep(30)
        assertTrue("读类调用还在跑时，写类调用就已经进来了", !writerStarted.get())
        releaseSafe.complete(Unit)
        safe.await()
        writer.await()
        assertTrue("读类结束后写类没有进来", writerStarted.get())
        ToolConcurrency.resetForTest(10)
    }

    /**
     * 反向的那一半（第 98 轮的单测抓到的真 bug）：写类**开始跑之后**，排在它后面的读类
     * 也必须等着 —— 旧实现只保证「写类等到池子排空才开跑」，一旦它开跑，
     * `inFlight(1) < maxParallel` 又成立，后面的读类调度位照样被放行，于是读文件的同时
     * bash 把它改掉。真正独占要求「它在跑的整段时间里没有任何新调度位被放行」。
     */
    @Test(timeout = 30_000)
    fun safeCallWaitsWhileAnExclusiveCallRuns() = runBlocking {
        ToolConcurrency.resetForTest(4)
        val writerInside = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val safeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("bash") {
                writerInside.complete(Unit)
                releaseWriter.await()
            }
        }
        writerInside.await()
        val safe = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("read") { safeStarted.set(true) }
        }
        Thread.sleep(30)
        assertTrue("写类还在跑时读类就进来了", !safeStarted.get())
        releaseWriter.complete(Unit)
        writer.await()
        safe.await()
        assertTrue("写类结束后读类没有进来", safeStarted.get())
        assertEquals(0, ToolConcurrency.running)
        ToolConcurrency.resetForTest(10)
    }

    /** 分类表：只有登记过的工具名算并行安全；未知工具按写类处理（宁严勿松） */
    @Test
    fun onlyListedToolsAreSafe() {
        assertTrue(ToolConcurrency.isSafe("read"))
        assertTrue(ToolConcurrency.isSafe("grep"))
        assertTrue(ToolConcurrency.isSafe("web_search"))
        assertTrue(!ToolConcurrency.isSafe("bash"))
        assertTrue(!ToolConcurrency.isSafe("write"))
        assertTrue(!ToolConcurrency.isSafe("edit"))
        assertTrue(!ToolConcurrency.isSafe("some-future-tool"))
    }

    // ------------------------------------------------- Promise.all 批调用的提交顺序（第 98 轮）

    /**
     * 写类子调用必须**按提交顺序**跑（测试报告 1.1 的真机实测：10 个并发 bash 的顺序是
     * 2,1,3,5,6,7,8,10,9,4；6 个 write 抢同一个文件时赢的是 writer3/writer2/writer4/writer5）。
     *
     * SDK 段逐字写着 dsh 的 "mutating calls run alone, in submission order" —— 串行只是其中一半。
     */
    @Test(timeout = 30_000)
    fun batchRunsMutatingCallsInSubmissionOrder() = runBlocking {
        ToolConcurrency.resetForTest(10)
        val names = List(10) { "bash" }
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val results = ToolConcurrency.runBatch(names) { index ->
            order.add(index)
            Thread.sleep(3)
            index * 2
        }
        assertEquals("写类子调用的执行顺序必须等于提交顺序", (0..9).toList(), order.toList())
        assertEquals("返回值仍然与下标一一对应", (0..9).map { it * 2 }, results)
        assertEquals(0, ToolConcurrency.running)
    }

    /** 同一批里读类照样能重叠：顺序保证不能把并行也一起吃掉（名额仍由闸门管，见 withToolPermit） */
    @Test(timeout = 30_000)
    fun batchStillOverlapsSafeCalls() = runBlocking {
        ToolConcurrency.resetForTest(4)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        val names = List(8) { "read" }
        // 真实形状（QuickJsRuntime.runOne）：批调度只排顺序，名额由每个子调用自己过闸
        ToolConcurrency.runBatch(names) { index ->
            ToolConcurrency.withToolPermit(names[index]) {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                Thread.sleep(5)
                running.decrementAndGet()
                index
            }
        }
        assertTrue("读类子调用没有重叠（peak=$peak）", peak.get() > 1)
        assertTrue("读类子调用超过了上限（peak=$peak）", peak.get() <= 4)
        assertEquals(0, ToolConcurrency.running)
    }

    /** 混批：写类仍然独占（跑的时候没有任何读类在跑） */
    @Test(timeout = 30_000)
    fun batchKeepsMutatingCallsExclusive() = runBlocking {
        ToolConcurrency.resetForTest(4)
        val inside = java.util.concurrent.atomic.AtomicInteger(0)
        val writerOverlapped = java.util.concurrent.atomic.AtomicBoolean(false)
        val writersSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val names = listOf("read", "bash", "read", "edit", "read", "write", "grep")
        ToolConcurrency.runBatch(names) { index ->
            ToolConcurrency.withToolPermit(names[index]) {
                val now = inside.incrementAndGet()
                if (!ToolConcurrency.isSafe(names[index])) {
                    writersSeen.incrementAndGet()
                    if (now > 1) writerOverlapped.set(true)
                }
                Thread.sleep(4)
                inside.decrementAndGet()
                index
            }
        }
        assertEquals("三个写类子调用都要跑", 3, writersSeen.get())
        assertTrue("写类调用与别的调用重叠了", !writerOverlapped.get())
        assertEquals(0, ToolConcurrency.running)
    }

    /**
     * 等名额时被取消（用户中断 / run_code 的看门狗）**不能漏水**：名额可能已经被 pump 兑现，
     * 不还回去的话池子永久少一格 —— 攒够上限之后所有写类调用一起卡死（实测现场：
     * 「停止」之后整条会话再也跑不动工具）。
     */
    @Test(timeout = 30_000)
    fun cancellingWhileQueuedReleasesTheSlot() = runBlocking {
        ToolConcurrency.resetForTest(1)
        val firstInside = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("read") {
                firstInside.complete(Unit)
                releaseFirst.await()
            }
        }
        firstInside.await()
        // 第二个（写类）只能排队等着 —— 它要么在 granted.await() 上被取消，要么已经兑现
        val second = async(Dispatchers.Default) {
            ToolConcurrency.withToolPermit("bash") { }
        }
        Thread.sleep(50)
        second.cancel()
        releaseFirst.complete(Unit)
        first.await()
        runCatching { second.await() }
        // 给 pump 一点时间把归还后的名额放出去
        Thread.sleep(50)
        assertEquals("取消一个排队中的调用漏水了：池子里还占着名额", 0, ToolConcurrency.running)
        ToolConcurrency.resetForTest(10)
    }

    // ------------------------------------------------------------ 标记是怎么传下去的

    private class Marker : AbstractCoroutineContextElement(Marker) {
        companion object Key : CoroutineContext.Key<Marker>
    }

    /** 协程上下文里的自定义元素能穿过 withContext + async(Dispatchers.Default) */
    @Test(timeout = 20_000)
    fun aContextElementSurvivesWithContextAndAsync() = runBlocking {
        val seen = CompletableDeferred<Boolean>()
        kotlinx.coroutines.withContext(Marker()) {
            seen.complete(async(Dispatchers.Default) { coroutineContext[Marker.Key] != null }.await())
        }
        assertTrue("标记没有传下去", seen.await())
    }

    /**
     * 「已持有许可」的标记确实传到了嵌套的扇出里。
     *
     * 注意 `async` 绑的是**词法上的 CoroutineScope 接收者**：直接写在
     * `runBlocking { withToolPermit("read") { async(…) } }` 里时它绑的是 runBlocking 的 scope（不带标记），
     * 只有 `coroutineScope { async(…) }` 这种「从当前协程派生」的形状才带得上 ——
     * 真实代码（QuickJsRuntime → WebSearchTool）正是后一种形状。
     * 第一版测试就是写成了前一种形状，于是把一段正确的实现误判成了死锁。
     */
    @Test(timeout = 20_000)
    fun theHoldingMarkerReachesNestedFanOut() = runBlocking {
        ToolConcurrency.resetForTest(1)
        val inside = CompletableDeferred<Boolean>()
        ToolConcurrency.withToolPermit("read") {
            inside.complete(coroutineScope { async(Dispatchers.Default) { ToolConcurrency.holdingNow() }.await() })
        }
        assertTrue("withToolPermit 的标记没有传到嵌套的 async 里", inside.await())
        ToolConcurrency.resetForTest(10)
    }
}
