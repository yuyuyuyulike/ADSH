package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鲸鱼尾（dsh 0.2.0-rc.1 的 `RunningWhaleTail`）搬过来的**关键帧数据与插值**。
 *
 * 绘制那条路只能真机看，但最容易搬错的两件事在纯函数里就能钉住：
 *  1. 22 个关键帧、每帧 92 个坐标、首尾都是静止形态（dsh 的 `MOTION_PATHS` 首尾各两次 `REST_PATH`）；
 *  2. 插值是**逐坐标线性**的 —— 关键帧时刻取到原帧，两个关键帧之间取到中间值。
 */
class WhaleTailTest {

    @Test
    fun `22 个关键帧，每帧 92 个坐标`() {
        assertEquals(22, WHALE_FRAME_COUNT)
        assertEquals(92, WHALE_FRAME_SIZE)
        (0 until WHALE_FRAME_COUNT).forEach { i ->
            assertEquals("第 $i 帧的坐标个数", 92, whaleFrame(i).size)
        }
    }

    /** dsh 的 MOTION_PATHS 首两个与末两个都是 REST_PATH：t=0 与 t=1 必须是同一形态 */
    @Test
    fun `首尾都是静止形态`() {
        val rest = whaleFrame(0)
        val atStart = whalePoseAt(0f, FloatArray(WHALE_FRAME_SIZE))
        val atEnd = whalePoseAt(1f, FloatArray(WHALE_FRAME_SIZE))
        assertArrayEquals(rest, atStart)
        assertArrayEquals(rest, atEnd)
    }

    /** 落在关键帧时刻上 = 那一帧原样（线性插值的端点必须精确） */
    @Test
    fun `关键帧时刻取到原帧`() {
        // MOTION_TIMES 的最后一个中间帧是 0.5（第 16 个关键帧，0-based 15 附近）；这里逐帧核对
        val times = floatArrayOf(
            0f, 0.028571f, 0.057143f, 0.095238f, 0.114286f, 0.133333f, 0.171429f, 0.2f,
            0.219048f, 0.257143f, 0.285714f, 0.380952f, 0.409524f, 0.447619f, 0.47619f,
            0.495238f, 0.52381f, 0.552381f, 0.571429f, 0.638095f, 0.8f, 1f,
        )
        val out = FloatArray(WHALE_FRAME_SIZE)
        times.forEachIndexed { i, t ->
            if (i < WHALE_FRAME_COUNT) assertArrayEquals("第 $i 帧", whaleFrame(i), whalePoseAt(t, out))
        }
    }

    /** 两个关键帧正中间：每个坐标都应该是两者的中点（线性插值） */
    @Test
    fun `两帧之间取中点`() {
        val a = whaleFrame(0)
        val b = whaleFrame(1)
        val mid = (0f + 0.028571f) / 2f
        val pose = whalePoseAt(mid, FloatArray(WHALE_FRAME_SIZE))
        pose.indices.forEach { k ->
            assertEquals("第 $k 个坐标", (a[k] + b[k]) / 2f, pose[k], 1e-5f)
        }
    }

    /** 越界要夹住（动画重启的第一帧 t 可能因为取整略微小于 0） */
    @Test
    fun `越界夹在两端`() {
        val out = FloatArray(WHALE_FRAME_SIZE)
        assertArrayEquals(whaleFrame(0), whalePoseAt(-0.5f, out))
        assertArrayEquals(whaleFrame(0), whalePoseAt(1.5f, out))
    }

    /** 尾巴确实在动：中间某一帧不能等于静止形态（否则动画是死的、只剩一条静止的尾巴） */
    @Test
    fun `中间帧与静止形态不同`() {
        val rest = whaleFrame(0)
        val moving = (1 until WHALE_FRAME_COUNT - 1).count { i -> !whaleFrame(i).contentEquals(rest) }
        assertTrue("中间没有一个关键帧与静止形态不同", moving > 0)
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) =
        assertArrayEquals("", expected, actual)

    private fun assertArrayEquals(message: String, expected: FloatArray, actual: FloatArray) {
        assertEquals(message + " 长度", expected.size, actual.size)
        expected.indices.forEach { k ->
            assertEquals(message + " 第 $k 个坐标", expected[k], actual[k], 1e-6f)
        }
    }
}
