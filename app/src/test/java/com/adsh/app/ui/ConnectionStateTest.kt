package com.adsh.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接条的三态里，哪些是「临时态」（轮结束 / 按停止时要收掉）—— 见 [isTransient]。
 *
 * 「已恢复」不是临时态：它有自己的 2 秒计时（dsh 的 RECOVERY_CONFIRMATION_MS），
 * 提前收掉就等于用户永远看不到「已恢复」。这条区分以前散在两处 `is Reconnecting` 判断里。
 */
class ConnectionStateTest {

    @Test
    fun reconnectingAndDisconnectedAreTransient() {
        assertTrue(ConnectionState.Reconnecting(attempt = 2, message = "timeout").isTransient())
        assertTrue(ConnectionState.Disconnected(message = "网络不可用").isTransient())
    }

    @Test
    fun idleAndRecoveredAreNot() {
        assertFalse(ConnectionState.Idle.isTransient())
        assertFalse(ConnectionState.Recovered(at = 1L).isTransient())
    }
}
