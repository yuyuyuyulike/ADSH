package com.adsh.app.ui

import com.adsh.app.core.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘防误触：落在左右边缘带里的「按下」要被摘掉（见 EdgeTouchGuard.kt）。
 *
 * Compose 的指针派发跑不进 JVM 单测，所以判定逻辑单独写成纯函数 [isEdgeTouch]，
 * 这里验它的边界；「消费掉整根手指」那一步在真机上验。
 */
class EdgeGuardTest {

    @Test
    fun `带内按下算边缘，带外不算`() {
        // 400px 宽、单边 30px：0..30 与 370..400 是边缘带
        assertTrue(isEdgeTouch(0f, 400f, 30f))
        assertTrue(isEdgeTouch(30f, 400f, 30f))
        assertTrue(isEdgeTouch(370f, 400f, 30f))
        assertTrue(isEdgeTouch(400f, 400f, 30f))
        assertFalse(isEdgeTouch(31f, 400f, 30f))
        assertFalse(isEdgeTouch(200f, 400f, 30f))
        assertFalse(isEdgeTouch(369f, 400f, 30f))
    }

    @Test
    fun `关闭时任何位置都不算边缘`() {
        assertFalse(isEdgeTouch(0f, 400f, 0f))
        assertFalse(isEdgeTouch(0f, 400f, -1f))
    }

    @Test
    fun `屏幕比两条带还窄时只吃掉两侧各三分之一`() {
        // 60px 宽、单边要求 30px：正好各占 1/3，中间 20px 仍然可点
        assertTrue(isEdgeTouch(20f, 60f, 30f))
        assertFalse(isEdgeTouch(21f, 60f, 30f))
        assertFalse(isEdgeTouch(30f, 60f, 30f))
        assertFalse(isEdgeTouch(39f, 60f, 30f))
        assertTrue(isEdgeTouch(40f, 60f, 30f))
        // 极端情况（宽度为 0 的早期测量）不能把一切都判成边缘
        assertFalse(isEdgeTouch(0f, 0f, 30f))
    }

    @Test
    fun `带宽固定为系统的边缘定义 12dp（第 99 轮撤掉设置项）`() {
        // 原来有「关闭 / 窄 / 中 / 宽」四档可选，用户口径是「别让我配，直接默认」——
        // 于是只剩这一个常量：12dp = AOSP ViewConfiguration.EDGE_SLOP。
        assertEquals(12, SettingsStore.EDGE_GUARD_WIDTH_DP)
    }
}
