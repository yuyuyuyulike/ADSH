package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 重试行的纯判定（dsh 的 model-retry：文案模板 / 秒数 / 四态推导 / 存储编解码）。
 * 这些数字与文案是界面的唯一来源，单测钉住它们，避免「看着差不多」地漂移。
 */
class RetryModelTest {

    private val scheduled = RetryInfo(
        retry = 1,
        maximum = 2,
        delayMs = 2_500L,
        failure = "连接被重置",
        started = false,
    )

    @Test
    fun `状态文案照 dsh 的 message-retry-status 模板`() {
        assertEquals(
            "正在重试模型请求（1/2） · 3s",
            retryStatusText(scheduled, RetryPhase.Active, seconds = 3),
        )
        assertEquals(
            "已重试模型请求（2/2） · 4s",
            retryStatusText(scheduled.copy(retry = 2, delayMs = 3_500L, started = true), RetryPhase.Started, seconds = 4),
        )
        assertEquals(
            "模型请求重试已取消（1/2） · 3s",
            retryStatusText(scheduled, RetryPhase.Cancelled, seconds = 3),
        )
    }

    @Test
    fun `倒计时向上取整且下限一秒`() {
        assertEquals(1, retrySeconds(0L))
        assertEquals(1, retrySeconds(1L))
        assertEquals(1, retrySeconds(1_000L))
        assertEquals(2, retrySeconds(1_001L))
        assertEquals(3, retrySeconds(2_500L))
        assertEquals(4, retryStaticSeconds(scheduled.copy(delayMs = 3_500L)))
    }

    @Test
    fun `展开体文案：延迟四舍五入到毫秒、失败原因原样`() {
        assertEquals("重试延迟：2500毫秒", retryDelayText(scheduled))
        assertEquals("重试延迟：2500毫秒", retryDelayText(scheduled.copy(delayMs = 2_500L)))
        assertEquals("失败原因：连接被重置", retryFailureText(scheduled))
    }

    @Test
    fun `阶段：started 优先；等待中看轮次是否已结束`() {
        assertEquals(RetryPhase.Active, retryPhase(scheduled, turnClosed = false))
        assertEquals(RetryPhase.Cancelled, retryPhase(scheduled, turnClosed = true))
        assertEquals(RetryPhase.Started, retryPhase(scheduled.copy(started = true), turnClosed = false))
        // 已 started 的 attempt 在轮次中止后保持「已重试」，不回退成「已取消」（dsh 同此）
        assertEquals(RetryPhase.Started, retryPhase(scheduled.copy(started = true), turnClosed = true))
    }

    @Test
    fun `存储编解码往返与坏数据`() {
        val encoded = RetryInfoCodec.encode(scheduled)
        assertEquals(scheduled, RetryInfoCodec.decode(encoded))
        assertEquals(scheduled.copy(started = true), RetryInfoCodec.decode(RetryInfoCodec.encode(scheduled.copy(started = true))))
        assertNull(RetryInfoCodec.decode("not json"))
        assertNull(RetryInfoCodec.decode("""{"retry":1}"""))
    }
}
