package com.adsh.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行级绘制剔除窗口的不变量：**桶内任何真实滚动位置下可见的行都必须被判为可画**。
 *
 * 窗口把滚动位置按 [CULL_STEP_PX] 取整（每帧不再逐行读 scrollState，否则滚动 / 键盘动画每帧
 * 把上千行全部标脏）；取整的代价是窗口边界比真实视口宽一点，但绝不能窄 —— 窄了就会在快速
 * 滑动时露出空白行。这条用例把边界扫一遍。
 */
class CullWindowTest {

    private val step = CULL_STEP_PX
    private val overdraw = CULL_OVERDRAW_PX
    private val viewport = 2000
    private val bucket = 3 * step

    @Test
    fun everyVisibleRowInsideTheBucketIsDrawn() {
        for (scroll in bucket until bucket + step step 7) {
            val visibleTops = listOf(
                scroll - overdraw - 10f,          // 刚好在 overdraw 下沿之外一点
                scroll.toFloat(),                  // 视口上沿
                (scroll + viewport - 10).toFloat(), // 视口下沿
                (scroll + viewport + overdraw - 10).toFloat(), // overdraw 上沿
            )
            visibleTops.forEach { top ->
                assertTrue(
                    "scroll=$scroll top=$top 的行在屏幕上可见，窗口却把它剔掉了",
                    rowVisibleInCullWindow(bucket, viewport, 0f, top, 40f),
                )
            }
        }
    }

    @Test
    fun rowsSafelyOutsideTheWindowAreCulled() {
        assertFalse(rowVisibleInCullWindow(bucket, viewport, 0f, bucket - overdraw - 100f, 40f))
        assertFalse(
            rowVisibleInCullWindow(
                bucket,
                viewport,
                0f,
                bucket + step + viewport + overdraw + 100f,
                40f,
            ),
        )
    }

    @Test
    fun bottomCorrectionMovesTheRowIntoTheWindow() {
        // 贴底补偿把内容向上画了 500px：行还在原坐标，但它在屏幕上的位置变了
        assertTrue(rowVisibleInCullWindow(bucket, viewport, -500f, bucket + 500f, 40f))
        assertFalse(rowVisibleInCullWindow(bucket, viewport, 0f, bucket + viewport + overdraw + 1000f, 40f))
    }
}
