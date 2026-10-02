package com.adsh.app.ui

import com.adsh.app.core.data.MessageEntity
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右侧两个快捷导航按钮的目标（第 95 轮第 3 条）。
 *
 * 三个按钮的可见性由 ChatScreen 管（复用 showToBottom 的迟滞 + atTop），
 * 这里只钉住「往上找哪一行」这个判据。
 */
class QuickNavTest {

    private fun user(id: Long) = ChatItem.User(key = id, text = "u$id")
    private fun entry(key: Long) = ChatItem.TurnEntry(
        view = TurnView(key = key, entries = listOf(ProcessEntry.Text("a"))),
        index = 0,
        gap = 0.dp,
    )

    @Test
    fun findsTheNearestUserMessageAboveTheViewport() {
        val items = listOf(user(1), entry(1), entry(1), user(2), entry(2), entry(2), user(3), entry(3))
        // 视口停在第三轮中间（第一条可见行是下标 7）→ 目标是第三轮那条用户消息（下标 6）
        assertEquals(6, previousUserItemIndex(items, 7))
        // 视口停在第二轮中间（第一条可见行是下标 4 或 5）→ 目标是下标 3
        assertEquals(3, previousUserItemIndex(items, 4))
        assertEquals(3, previousUserItemIndex(items, 5))
        // 第一条可见行就是用户消息本身（下标 3）→ 上面那条（下标 0）
        assertEquals(0, previousUserItemIndex(items, 3))
    }

    @Test
    fun noTargetAtTheVeryTop() {
        val items = listOf(user(1), entry(1), entry(1))
        // 视口在会话最上面：第一条可见行就是第 0 项，上面没有别的用户消息
        assertNull(previousUserItemIndex(items, 0))
        // 越界（LazyColumn 还没量出来时的 -1 / 超大下标）也不能炸
        assertNull(previousUserItemIndex(items, -1))
        assertEquals(0, previousUserItemIndex(items, 99))
    }

    @Test
    fun messagesOtherThanUserAreNotTargets() {
        val items = listOf(
            ChatItem.SystemPrompt(key = 9, text = "s", update = false),
            ChatItem.Context(key = 8, label = "l", form = "f", text = "t"),
            entry(1),
        )
        assertNull(previousUserItemIndex(items, 3))
    }

    @Test
    fun ignoresTheLastUserMessageEntityShape() {
        // 只是防止有人以后把 items 换成别的载体时悄悄失去 User 判据
        val message = MessageEntity(id = 1, conversationId = 1, role = "user", content = "hi", createdAt = 1)
        val items = buildChatItems(messages = listOf(message), liveTurnId = null)
        assertTrue(items.first() is ChatItem.User)
    }
}

/**
 * 扫光相位（第 95 轮第 1 条）：**只由共用时钟的起点推**，与「这一行什么时候进入组合」无关。
 * 以前是把墙钟偏移与动画进度相加，而这个 composable 每帧都会重组 —— 等于两个时钟一起走，
 * 扫光按两倍速跑还会在回绕处跳一下（用户看到的「流光卡顿」）。
 */
class SweepPhaseTest {

    @Test
    fun staysAtTheStartDuringTheEntryDelay() {
        // 入场延迟（1s）内不扫：相位固定在 0
        assertEquals(0f, sweepPhaseAt(epoch = 1_000, nowMillis = 1_000), 0.0001f)
        assertEquals(0f, sweepPhaseAt(epoch = 1_000, nowMillis = 1_999), 0.0001f)
    }

    @Test
    fun advancesLinearlyAfterTheDelay() {
        val epoch = 10_000L
        // 延迟 1000ms 之后开始：再走半个周期就是 0.5。
        // 周期从 SWEEP_PERIOD_MS 推（第 101 轮把它从 4200 提到 3600 时，写死数字的期望值当场失效）
        val half = sweepPhaseAt(epoch, epoch + 1_000 + SWEEP_PERIOD_MS / 2)
        assertEquals(0.5f, half, 0.01f)
        // 四分之一周期
        val quarter = sweepPhaseAt(epoch, epoch + 1_000 + SWEEP_PERIOD_MS / 4)
        assertEquals(0.25f, quarter, 0.01f)
    }

    @Test
    fun wrapsEveryPeriod() {
        val epoch = 0L
        val period = SWEEP_PERIOD_MS
        val start = epoch + 1_000
        val a = sweepPhaseAt(epoch, start + 100)
        val b = sweepPhaseAt(epoch, start + period + 100)
        assertEquals(a, b, 0.0001f)
    }

    @Test
    fun twoRowsWithTheSameEpochShareThePhase() {
        // 同源的行（父子）任意时刻取到的相位一致：这是「子行不掉拍」的判据
        val epoch = 5_000L
        val parent = sweepPhaseAt(epoch, 12_345)
        val child = sweepPhaseAt(epoch, 12_345)
        assertEquals(parent, child, 0.0f)
    }

    @Test
    fun phaseStaysInRange() {
        val epoch = 0L
        for (now in 0L..60_000L step 137L) {
            val phase = sweepPhaseAt(epoch, now)
            assertTrue("phase=$phase", phase >= 0f && phase < 1f)
        }
    }
}
