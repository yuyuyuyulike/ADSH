package com.adsh.app.core.ptc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看门狗的**调度循环**（第 183 轮）：桌面单测里用一个「永不结束的 body」顶替 QuickJS 程序。
 *
 * 为什么测这一层：QuickJS 是原生库（桌面上加载不了），但那句真正的修复 ——
 * 「宿主不再无限等一个不让出执行权的程序」—— 落在 [awaitPtcProgram] 这段调度里。
 * 真机事故的复现很贵（要跑满 2 分钟 + 一个不听话的模型），所以把这段逻辑钉在 JVM 上：
 * 提交、轮询、按判据放弃、正常完成照原样返回、放弃时 cancel + shutdown。
 */
class PtcWatchdogRunnerTest {

    private val toolWait = AtomicLong(0)

    /** 一个提交上去就再也不返回的 body（模拟同步死循环） */
    private fun neverEnding(executor: java.util.concurrent.ExecutorService) =
        executor.submit<String> {
            CountDownLatch(1).await()   // 永远等不到：模拟同步死循环
            "（不会到这里）"
        }

    @Test
    fun aBodyThatNeverYieldsIsAbandonedAndTheExecutorIsShutDown() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-ptc").apply { isDaemon = true } }
        val started = System.currentTimeMillis()
        val outcome = awaitPtcProgram(
            executor = executor,
            startedAt = started,
            toolWaitMs = toolWait,
            budgetMs = 200,
            timeoutGraceMs = 100,
            cancelGraceMs = 100,
            tickMs = 20,
            cancelRequested = { false },
            start = { neverEnding(executor) },
        )
        assertEquals(PtcWatchdogOutcome.TimedOut, outcome)
        assertTrue("放弃之后线程池要收掉（不再往里提交）", executor.isShutdown)
    }

    @Test
    fun toolWaitTimeDoesNotCountAgainstTheBudget() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-ptc").apply { isDaemon = true } }
        // 等工具已经花掉 10 秒：预算 200ms 的「程序自己跑的时间」其实还没用完
        toolWait.set(10_000)
        val started = System.currentTimeMillis()
        val outcome = awaitPtcProgram(
            executor = executor,
            startedAt = started,
            toolWaitMs = toolWait,
            budgetMs = 200,
            timeoutGraceMs = 100,
            cancelGraceMs = 100,
            tickMs = 20,
            cancelRequested = { false },
            start = { neverEnding(executor) },
        ) { System.currentTimeMillis() }
        assertEquals("预算只看程序自己的执行时间", PtcWatchdogOutcome.TimedOut, outcome)
        toolWait.set(0)
    }

    @Test
    fun aCancelledRunReportsCancelledNotTimeout() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-ptc").apply { isDaemon = true } }
        val outcome = awaitPtcProgram(
            executor = executor,
            startedAt = System.currentTimeMillis(),
            toolWaitMs = toolWait,
            budgetMs = 60_000,
            timeoutGraceMs = 100,
            cancelGraceMs = 20,
            tickMs = 10,
            cancelRequested = { true },
            start = { neverEnding(executor) },
        )
        assertEquals(PtcWatchdogOutcome.Cancelled, outcome)
    }

    @Test
    fun aFinishedBodyComesBackUntouched() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-ptc").apply { isDaemon = true } }
        val outcome = awaitPtcProgram(
            executor = executor,
            startedAt = System.currentTimeMillis(),
            toolWaitMs = toolWait,
            budgetMs = 60_000,
            timeoutGraceMs = 100,
            cancelGraceMs = 100,
            tickMs = 20,
            cancelRequested = { false },
            start = { executor.submit<String> { "程序自己的结果" } as Future<String> },
        )
        assertEquals(PtcWatchdogOutcome.Finished("程序自己的结果"), outcome)
        assertTrue(executor.isShutdown)
    }

    @Test
    fun waitingThreadsDoNotLeakIntoTheNextRun() {
        // 每次 run 建自己的线程池（互不影响）：这里只是把「用完就 shutdown」这条钉死
        val executor = Executors.newSingleThreadExecutor()
        executor.submit { }.get(1, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
    }
}
