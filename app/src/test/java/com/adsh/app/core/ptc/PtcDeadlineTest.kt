package com.adsh.app.core.ptc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `:ptc` 执行的收尾判据（第 183 轮）：到点**杀进程**、按停止也**杀进程**。
 *
 * 为什么是这条判据：dsh 的 `dsh-ptc-runtime-node` 把程序放进独立子进程，宿主在
 * `setTimeout(spec.timeoutMs)` 到点后 `controller.abort("execution deadline reached")` 并
 * `handle.terminate()` —— 子进程被**终止**，所以程序里的同步死循环伤不到父进程。ADSH 以前是
 * 同进程 QuickJS，没有这条退路：真机上一个 `for (let i = 0; i < 1e11; i++)` 把整轮挂了 13 分钟
 * （泵循环的超时判据与「停止」探针都没有机会执行）。现在程序跑在 `:ptc` 进程里，
 * `Process.killProcess(workerPid)` 就是 dsh 的 terminate —— 这段判据必须钉死：
 * 到点杀、停止杀、正常结束不杀、worker 自己死了照实报。时间由假时钟推进（测试不等真时间）。
 */
class PtcDeadlineTest {

    private class Fake(stepMs: Long) {
        var clock = 0L
        val step = stepMs
        var finished = false
        var cancelled = false
        var alive = true
        var kills = 0

        fun await(timeoutMs: Long) = awaitPtcWorker(
            timeoutMs = timeoutMs,
            startedAt = 0L,
            tickMs = 0L,
            now = { clock += step; clock },
            finished = { finished },
            cancelRequested = { cancelled },
            workerAlive = { alive },
            kill = { kills++ },
        )
    }

    @Test
    fun deadlineKillsTheWorker() {
        val fake = Fake(stepMs = 10_000)
        val outcome = fake.await(timeoutMs = 120_000)
        assertEquals(PtcWaitOutcome.TimedOut, outcome)
        assertEquals("到点必须杀掉 :ptc 进程（dsh 的 handle.terminate）", 1, fake.kills)
    }

    @Test
    fun cancelKillsTheWorker() {
        val fake = Fake(stepMs = 1_000).apply { cancelled = true }
        val outcome = fake.await(timeoutMs = 120_000)
        assertEquals(PtcWaitOutcome.Cancelled, outcome)
        assertEquals(1, fake.kills)
    }

    @Test
    fun aFinishedRunIsNotKilled() {
        val fake = Fake(stepMs = 10_000).apply { finished = true }
        assertEquals(PtcWaitOutcome.Finished, fake.await(timeoutMs = 120_000))
        assertEquals(0, fake.kills)
    }

    @Test
    fun aDeadWorkerIsReportedAsWorkerExit() {
        val fake = Fake(stepMs = 1_000).apply { alive = false }
        assertEquals(PtcWaitOutcome.WorkerExit, fake.await(timeoutMs = 120_000))
        assertEquals(0, fake.kills)
    }

    @Test
    fun theDeadlineIsWallClock() {
        // dsh 的 wallTimer 从执行开始计时：等工具的耗时**也算在预算里**（第 91 轮那条「扣掉等工具
        // 时间」的本地口径随这次重构一起去掉了 —— 判据与 dsh 对齐，模型要更长就传更大的 timeoutMs）
        val fake = Fake(stepMs = 60_000)
        assertEquals(PtcWaitOutcome.TimedOut, fake.await(timeoutMs = 120_000))
        assertEquals("60s×2 = 120s：到点就杀", 1, fake.kills)
    }
}
