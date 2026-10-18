package com.adsh.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉手势松手后该开还是该关（[drawerOpensAfterRelease]）。
 * 它只决定**方向**：速度不进动画，动画是 AppRoot 里那条定长缓动 ——
 * 把手势速度当弹簧初速度接上去会「末尾抖动、很花哨」。
 */
class DrawerSettleTest {

    @Test
    fun 往右甩开往左甩关() {
        assertTrue(drawerOpensAfterRelease(progress = 0.2f, velocityPxPerS = 800f))
        assertFalse(drawerOpensAfterRelease(progress = 0.8f, velocityPxPerS = -800f))
    }

    @Test
    fun 慢慢松手按过没过半定方向() {
        assertTrue(drawerOpensAfterRelease(0.6f, 100f))
        assertFalse(drawerOpensAfterRelease(0.4f, -100f))
        // 正好一半算关：progress > 0.5 才开
        assertFalse(drawerOpensAfterRelease(0.5f, 0f))
    }

    /** 阈值那一档：正好 250 不算甩，比它快一点才算 —— 两条边界两个方向都钉住 */
    @Test
    fun 阈值正好等于250不算甩() {
        assertFalse(drawerOpensAfterRelease(progress = 0.2f, velocityPxPerS = 250f))
        assertTrue(drawerOpensAfterRelease(progress = 0.2f, velocityPxPerS = 250.5f))
        assertTrue(drawerOpensAfterRelease(progress = 0.8f, velocityPxPerS = -250f))
        assertFalse(drawerOpensAfterRelease(progress = 0.8f, velocityPxPerS = -250.5f))
    }
}
