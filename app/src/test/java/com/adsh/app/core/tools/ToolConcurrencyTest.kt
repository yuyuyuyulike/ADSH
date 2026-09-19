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
                ToolConcurrency.withPermit {
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
                ToolConcurrency.withPermit {
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
            ToolConcurrency.withPermit {
                // 模拟 web_search 的 queries 扇出：真实代码是 coroutineScope { async { … } }，
                // 扇出的 scope 从**当前协程**派生（才带得上「已持有许可」的标记）
                kotlinx.coroutines.coroutineScope {
                    (1..4).map { async(Dispatchers.Default) { ToolConcurrency.withPermit { it } } }.awaitAll()
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
                ToolConcurrency.withPermit {
                    val now = running.incrementAndGet()
                    if (now > peakInside) peakInside = now
                    kotlinx.coroutines.coroutineScope {
                        (1..3).map { async(Dispatchers.Default) { ToolConcurrency.withPermit { Thread.sleep(2) } } }
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
     * `runBlocking { withPermit { async(…) } }` 里时它绑的是 runBlocking 的 scope（不带标记），
     * 只有 `coroutineScope { async(…) }` 这种「从当前协程派生」的形状才带得上 ——
     * 真实代码（QuickJsRuntime → WebSearchTool）正是后一种形状。
     * 第一版测试就是写成了前一种形状，于是把一段正确的实现误判成了死锁。
     */
    @Test(timeout = 20_000)
    fun theHoldingMarkerReachesNestedFanOut() = runBlocking {
        ToolConcurrency.resetForTest(1)
        val inside = CompletableDeferred<Boolean>()
        ToolConcurrency.withPermit {
            inside.complete(coroutineScope { async(Dispatchers.Default) { ToolConcurrency.holdingNow() }.await() })
        }
        assertTrue("withPermit 的标记没有传到嵌套的 async 里", inside.await())
        ToolConcurrency.resetForTest(10)
    }
}
