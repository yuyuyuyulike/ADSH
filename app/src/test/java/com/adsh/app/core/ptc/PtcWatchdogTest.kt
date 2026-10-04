package com.adsh.app.core.ptc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宿主看门狗的判据（第 183 轮）：程序不让出执行权时，宿主什么时候放弃这一次 [QuickJsRuntime] 调用。
 *
 * 为什么值得一条测试：这条判据的背后是一次真机事故 —— harness 的 CPU 预算探针
 * （`for (let i = 0; i < 1e11; i++)`）进了同步死循环，泵循环里的超时判据**没有机会执行**，
 * 整轮无限期挂住：界面显示在跑、灵动岛一直亮着、后台任务的完成通知永远等不到下一个步边界
 * （用户看到的「它没收到结果、一直在等」就是这么来的）。QuickJS 包装库**没有中断接口**
 * （javap 确认：只有 setMemoryLimit / setMaxStackSize / runGC，没有 JS_SetInterruptHandler），
 * 所以只能在宿主这一侧放弃。
 *
 * 这里钉三件事：正常跑不许被打断、超预算 + 宽限才放弃、按了停止要能压过超时（先报「已停止」）。
 */
class PtcWatchdogTest {

    private fun verdict(
        programMs: Long,
        budgetMs: Long = 120_000L,
        timeoutGraceMs: Long = 5_000L,
        cancelRequested: Boolean = false,
        cancelGraceMs: Long = 2_000L,
    ) = ptcWatchdogVerdict(programMs, budgetMs, timeoutGraceMs, cancelRequested, cancelGraceMs)

    @Test
    fun normalRunIsNeverAbandoned() {
        assertEquals(PtcWatchdog.RUNNING, verdict(0))
        assertEquals(PtcWatchdog.RUNNING, verdict(119_999))
    }

    @Test
    fun budgetPlusGraceTripsTheWatchdog() {
        // 预算内、宽限内都不动手：正常路径由泵循环自己收尾
        assertEquals(PtcWatchdog.RUNNING, verdict(120_000))
        assertEquals(PtcWatchdog.RUNNING, verdict(125_000))
        // 越过宽限 = JS 没让出执行权（泵循环那条判据没机会跑）
        assertEquals(PtcWatchdog.ABANDON_TIMEOUT, verdict(125_001))
        assertEquals(PtcWatchdog.ABANDON_TIMEOUT, verdict(13 * 60 * 1000L))
    }

    @Test
    fun cancelWinsOverTimeout() {
        // 按了停止：宽限内先等泵循环自己停
        assertEquals(PtcWatchdog.RUNNING, verdict(2_000, cancelRequested = true))
        // 宽限外＝停不下来（同步死循环），报「已停止」而不是「超时」
        assertEquals(PtcWatchdog.ABANDON_CANCELLED, verdict(2_001, cancelRequested = true))
        assertEquals(PtcWatchdog.ABANDON_CANCELLED, verdict(600_000, cancelRequested = true))
    }

    @Test
    fun waitingForToolsDoesNotCountAgainstTheBudget() {
        // programMs 是**扣掉等工具时间**之后的数：等了一个 115s 的 bash，程序自己只跑了 100ms
        val programMs = 100L
        assertEquals(PtcWatchdog.RUNNING, verdict(programMs))
    }
}
