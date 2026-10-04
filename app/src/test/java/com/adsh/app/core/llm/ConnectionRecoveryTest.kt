package com.adsh.app.core.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 掉线重连的策略（第 95 轮，照搬 dsh 的 ConnectionController）。
 *
 * 这些数值不是随手定的：dsh 的 `ConnectionRecoveryConfigSchema` 就是
 * backoffBaseMs = 500 / backoffFactor = 2 / backoffMaxMs = 10000，
 * 单次等待是 `cap/2 + random*(cap/2)`，cap = `min(max, base * factor^(attempt-1))`。
 * 一旦有人改这里，dsh 那边的对照值就是判据。
 */
class ConnectionRecoveryTest {

    @Test
    fun backoffCapGrowsExponentiallyThenClamps() {
        assertEquals(500L, ConnectionRecovery.backoffCapMs(1))
        assertEquals(1_000L, ConnectionRecovery.backoffCapMs(2))
        assertEquals(2_000L, ConnectionRecovery.backoffCapMs(3))
        assertEquals(4_000L, ConnectionRecovery.backoffCapMs(4))
        assertEquals(8_000L, ConnectionRecovery.backoffCapMs(5))
        // 第 6 次本该是 16000：被 backoffMaxMs 夹到 10000，之后一直是它
        assertEquals(10_000L, ConnectionRecovery.backoffCapMs(6))
        assertEquals(10_000L, ConnectionRecovery.backoffCapMs(50))
        // attempt <= 0（第一次尝试）也不能炸：按 base 算
        assertEquals(500L, ConnectionRecovery.backoffCapMs(0))
    }

    @Test
    fun backoffDelayIsHalfFixedHalfJitter() {
        // random = 0 → cap/2；random → 1 → 逼近 cap
        assertEquals(250L, ConnectionRecovery.backoffDelayMs(1, 0.0))
        assertEquals(500L, ConnectionRecovery.backoffDelayMs(1, 1.0))
        assertEquals(500L, ConnectionRecovery.backoffDelayMs(2, 0.0))
        assertEquals(1_000L, ConnectionRecovery.backoffDelayMs(2, 1.0))
        // 随机数落在区间内：永远是 [cap/2, cap]
        for (attempt in 1..8) {
            val cap = ConnectionRecovery.backoffCapMs(attempt)
            val delay = ConnectionRecovery.backoffDelayMs(attempt, 0.37)
            assertTrue("attempt $attempt: $delay 应该落在 [" + (cap / 2) + ", " + cap + "]", delay in (cap / 2)..cap)
        }
    }

    @Test
    fun randomIsClampedSoTheDelayNeverExceedsTheCap() {
        assertEquals(500L, ConnectionRecovery.backoffDelayMs(1, 9.0))
        assertEquals(250L, ConnectionRecovery.backoffDelayMs(1, -9.0))
    }

    /**
     * 退避等待可以被「立刻重连」打断（dsh 的 `retryDelay?.abort(MANUAL_RECONNECT)`）：
     * 不等剩下的时间，马上回来。
     */
    @Test
    fun manualReconnectInterruptsTheBackoffWait() {
        val generation = ManualReconnect.generation()
        val worker = Thread { Thread.sleep(30); ManualReconnect.request() }
        worker.start()
        val started = System.currentTimeMillis()
        val interrupted = runBlocking { ConnectionRecovery.awaitRetry(5_000, generation) }
        val elapsed = System.currentTimeMillis() - started
        worker.join()
        assertTrue("手动重连应当打断退避等待", interrupted)
        assertTrue("不该等满 5 秒（实际 " + elapsed + "ms）", elapsed < 2_000)
    }

    @Test
    fun backoffWaitCompletesWhenNobodyInterrupts() {
        val generation = ManualReconnect.generation()
        val started = System.currentTimeMillis()
        val interrupted = runBlocking { ConnectionRecovery.awaitRetry(120, generation) }
        assertFalse(interrupted)
        assertTrue(System.currentTimeMillis() - started >= 100)
    }

    /**
     * 断网挂起（dsh 的 `setNetworkAvailable`）：网络不可用时 [ConnectionRecovery.awaitNetwork]
     * 不会返回，网络一回来立刻返回。
     *
     * 这条钉的是「断网时不盲目退避」这条机制 —— 以前 ADSH 没有它：断网了也照 500ms/1s/2s…
     * 一路重试，打服务端、界面还说「正在重连」，用户分不清「手机没网」和「服务端挂了」。
     */
    @Test
    fun awaitNetworkHoldsUntilTheNetworkComesBack() {
        val generation = ManualReconnect.generation()
        NetworkAvailability.set(false)
        try {
            var returned = false
            val worker = Thread {
                runBlocking { ConnectionRecovery.awaitNetwork(generation) }
                returned = true
            }
            worker.start()
            Thread.sleep(300)
            assertFalse("断网期间不该放行", returned)
            NetworkAvailability.set(true)
            worker.join(2_000)
            assertTrue("网络回来应当立刻放行", returned)
        } finally {
            NetworkAvailability.set(true)
        }
    }

    /** 断网期间用户点了「重试」：不必等网络回来，立刻放行（dsh 的 immediateRetry 那条出口） */
    @Test
    fun manualReconnectInterruptsTheOfflineWait() {
        val generation = ManualReconnect.generation()
        NetworkAvailability.set(false)
        try {
            val worker = Thread { Thread.sleep(30); ManualReconnect.request() }
            worker.start()
            val started = System.currentTimeMillis()
            runBlocking { ConnectionRecovery.awaitNetwork(generation) }
            val elapsed = System.currentTimeMillis() - started
            worker.join()
            assertTrue("手动重连应当打断断网等待（实际 " + elapsed + "ms）", elapsed < 1_000)
        } finally {
            NetworkAvailability.set(true)
        }
    }

    /** 网络可用时它不该挂起（第一次尝试就断网的情况由调用方判，这里只保证「可用 = 直接过」） */
    @Test
    fun awaitNetworkReturnsImmediatelyWhenOnline() {
        NetworkAvailability.set(true)
        val started = System.currentTimeMillis()
        runBlocking { ConnectionRecovery.awaitNetwork(ManualReconnect.generation()) }
        assertTrue(System.currentTimeMillis() - started < 200)
    }
}
