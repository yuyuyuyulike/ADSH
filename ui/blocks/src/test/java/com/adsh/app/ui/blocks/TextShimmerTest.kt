package com.adsh.app.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh 的 TextShimmer 时序（TextShimmer.module.css）：延迟 0.3s、周期 1.5s、前 2/3 走完平移。
 * 相位只由墙钟决定 —— 行滚出视口再回来不会跳相，慢一步出现的子行也不重新等。
 */
class TextShimmerTest {

    @Test
    fun startsOnlyAfterTheDshDelay() {
        assertEquals(-1f, textShimmerPhaseAt(0), 0.0001f)
        assertEquals(-1f, textShimmerPhaseAt(TEXT_SHIMMER_DELAY_MS - 1), 0.0001f)
        assertEquals(0f, textShimmerPhaseAt(TEXT_SHIMMER_DELAY_MS), 0.0001f)
    }

    @Test
    fun phaseWrapsOnTheDshPeriod() {
        val start = TEXT_SHIMMER_DELAY_MS
        assertEquals(0.25f, textShimmerPhaseAt(start + TEXT_SHIMMER_PERIOD_MS / 4), 0.001f)
        assertEquals(0.75f, textShimmerPhaseAt(start + TEXT_SHIMMER_PERIOD_MS * 3 / 4), 0.001f)
        // 周期之间连续：下一轮的四分之一处与上一轮相同
        assertEquals(
            textShimmerPhaseAt(start + TEXT_SHIMMER_PERIOD_MS / 4),
            textShimmerPhaseAt(start + TEXT_SHIMMER_PERIOD_MS + TEXT_SHIMMER_PERIOD_MS / 4),
            0.0001f,
        )
    }

    @Test
    fun bandTravelsAcrossOnlyDuringTheFirstTwoThirds() {
        assertEquals(0f, textShimmerSweepProgress(0f), 0.0001f)
        assertEquals(0.5f, textShimmerSweepProgress(TEXT_SHIMMER_SWEEP_FRACTION / 2), 0.0001f)
        // 2/3 处正好扫到右外侧；之后空档 1/3 周期不再移动
        assertEquals(1f, textShimmerSweepProgress(TEXT_SHIMMER_SWEEP_FRACTION), 0.0001f)
        assertEquals(1f, textShimmerSweepProgress(0.9f), 0.0001f)
        assertEquals(1f, textShimmerSweepProgress(1f), 0.0001f)
    }

    @Test
    fun sweepFractionIsTwoThirds() {
        // dsh 的关键帧：0% 与 66.6667% 之间走完全程，剩下是空档
        assertTrue(TEXT_SHIMMER_SWEEP_FRACTION in 0.66f..0.67f)
    }
}
