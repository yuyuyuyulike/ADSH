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
}
