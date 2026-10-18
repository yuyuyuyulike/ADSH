package com.adsh.app.ui.chat

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动滚动的判定（[ScrollFollow] / [pinAllowed] / [shouldRestoreFollow]）—— 与滚动容器无关的部分：
 * 跟随意图与「离底部多少像素」无关；只有读者真的移动过才重算；读者在滚 / 手指在屏上一律不贴。
 */
class AutoScrollTest {

    // ---------- 跟随意图 ----------

    @Test
    fun intentStartsFollowingAndOnlyChangesWhenWritten() {
        val follow = ScrollFollow()
        assertTrue(follow.following)
        follow.pause()
        assertFalse(follow.following)
        follow.follow()
        assertTrue(follow.following)
        // 采样：读者自己移动过才重算（落在 25dp 阈值以内 = 还算贴着底部）
        follow.sample(atBottom = false)
        assertFalse(follow.following)
        follow.sample(atBottom = true)
        assertTrue(follow.following)
    }

    // ---------- 贴底的门 ----------

    @Test
    fun pinIsAllowedOnlyWhileTheReaderIsIdleAndFollowing() {
        assertTrue(pinAllowed(following = true, touching = false, scrolling = false))
        // 跟随意图已经交出去
        assertFalse(pinAllowed(following = false, touching = false, scrolling = false))
        // 手指还在屏幕上（dsh 的 pending）
        assertFalse(pinAllowed(following = true, touching = true, scrolling = false))
        // 读者正在滚（拖拽 / 惯性）：dsh 的 onScroll 只在 !movedByReader 时才 followTail() ——
        // 少了这一条，流式长高的每一帧都会把「极小幅度快速上滑」的惯性按回底部（真机反馈的形状）
        assertFalse(pinAllowed(following = true, touching = false, scrolling = true))
    }

    // ---------- ---------- 状态机 ----------

    /** ③ 两个触发指纹：变了才发请求，静止的会话一次都不发 */
    @Test
    fun pinRequestOnlyFiresWhenContentOrViewportChanges() {
        val s = ChatScrollState(slopPx = 25)
        val items = Any()
        // 第一次：两个指纹都还没记过 ⇒ 进会话贴一次底
        assertTrue(s.pinRequest(content = items, viewport = 0, scrolling = false))
        assertEquals("contentvp", s.pinReason)
        // 同一份 items、同一个视口：一次都不发
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = false))
        // items 换了实例（列表的任何一个输入变了）
        assertTrue(s.pinRequest(content = Any(), viewport = 0, scrolling = false))
        // 视口变了（键盘弹出 / 收起）
        assertTrue(s.pinRequest(content = items, viewport = 400, scrolling = false))
        assertEquals("contentvp", s.pinReason)
        // 只有视口变：原因里不该有 content
        assertTrue(s.pinRequest(content = items, viewport = 480, scrolling = false))
        assertEquals("vp", s.pinReason)
    }

    /**
     * ⑦ 读者正在滚（拖拽 / 惯性）时这一拍不贴，**但指纹照记** —— 松手之后不会补一发。
     * 少了这一条就是真机上那个形状：agent 思考时极小幅度快速上滑被一帧一帧拽回底部。
     */
    @Test
    fun aBlockedPinRequestStillRemembersTheFingerprint() {
        val s = ChatScrollState(slopPx = 25)
        assertTrue(s.pinRequest(content = Any(), viewport = 0, scrolling = false))
        // 流式长高 + 读者正在滚：这一拍不贴
        val items = Any()
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = true))
        // 读者停下、内容没再变：也不该补一发（补了就是把读者按回底部）
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = false))
        // 手指还按着：同理
        s.touch(true)
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = false))
        s.touch(false)
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = false))
        // 内容真的又变了，这时才贴
        assertTrue(s.pinRequest(content = Any(), viewport = 0, scrolling = false))
    }

    /** ⑤ 读者动作交出跟随之后不再自动贴底；⑥ 恢复跟随要走显式那一条（[ChatScrollState.resume]） */
    @Test
    fun pausingFollowBlocksThePinEvenWhenContentGrows() {
        val s = ChatScrollState(slopPx = 25)
        s.pause()
        assertFalse(s.following)
        val items = Any()
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = false))
        s.resume()
        assertTrue(s.following)
        // 指纹已经记过：恢复跟随本身不发请求（点「回到底部」会显式贴一次）
        assertFalse(s.pinRequest(content = items, viewport = 0, scrolling = false))
    }

    /** ⑥ 只有「用户自己发的消息」换了 id 才恢复跟随；刚进这一屏时只记不跟随 */
    @Test
    fun ownInputFollowsOnlyWhenARealUserMessageArrives() {
        val s = ChatScrollState(slopPx = 25)
        assertFalse(s.ownInput(0L))
        assertFalse(s.ownInput(0L))
        assertTrue(s.ownInput(7L))
        assertFalse(s.ownInput(7L))
        assertTrue(s.ownInput(9L))
    }

    /** ② 位置采样：没动就什么都不做；动了才按阈值重算跟随意图 */
    @Test
    fun samplingOnlyRunsWhenThePositionActuallyMoved() {
        val s = ChatScrollState(slopPx = 25)
        assertFalse(s.sampled(500, 500, atBottom = false))
        assertTrue(s.following)
        // 往上翻：意图交给读者
        assertTrue(s.sampled(500, 499, atBottom = false))
        assertFalse(s.following)
        // 滚回底部（25dp 以内）：恢复跟随
        assertTrue(s.sampled(499, 0, atBottom = true))
        assertTrue(s.following)
    }

    /** 输入区高度（第三个指纹）：首次测量不算变化；交出跟随 / 手指还在 / 空会话时一律不贴 */
    @Test
    fun chromeHeightPinsOnlyOnARealChangeWhileFollowing() {
        val s = ChatScrollState(slopPx = 25)
        assertFalse(s.chromeMeasured(height = 200, total = 10))
        assertFalse(s.chromeMeasured(height = 200, total = 10))
        // 输入框自己长高（草稿折行）：本屏没有任何状态写进来，只有这里能补
        assertTrue(s.chromeMeasured(height = 260, total = 10))
        // 空会话没有可贴的底
        assertFalse(s.chromeMeasured(height = 300, total = 0))
        s.touch(true)
        assertFalse(s.chromeMeasured(height = 340, total = 10))
        s.touch(false)
        s.pause()
        assertFalse(s.chromeMeasured(height = 380, total = 10))
        // 恢复跟随后同一个高度变化才补：判据里确实带着「跟随意图 + 手指」这两道门
        s.resume()
        assertTrue(s.chromeMeasured(height = 420, total = 10))
    }

    // ---------- 折叠之后的意图恢复（真机反馈：折叠回去按钮不消失）----------

    @Test
    fun aCollapseThatLandsAtTheEndRestoresFollowing() {
        // 读者早已交出跟随，现在折叠回去、视口已经贴着内容末端 ⇒ 意图收回来（按钮据此消失）
        assertTrue(
            shouldRestoreFollow(
                following = false,
                touching = false,
                scrolling = false,
                atBottom = true,
                hasContent = true,
            ),
        )
    }

    @Test
    fun restoreNeverFightsTheReaderOrAnEmptyList() {
        // 已经在跟随：没什么可恢复的（也不该每帧写一次状态）
        assertFalse(shouldRestoreFollow(following = true, touching = false, scrolling = false, atBottom = true, hasContent = true))
        // 手指还在屏幕上 / 读者正在滚：不抢（与 pinAllowed 同一规矩）
        assertFalse(shouldRestoreFollow(following = false, touching = true, scrolling = false, atBottom = true, hasContent = true))
        assertFalse(shouldRestoreFollow(following = false, touching = false, scrolling = true, atBottom = true, hasContent = true))
        // 还有内容可滚：按钮该留着（这正是「交了跟随又没贴底」的正常状态）
        assertFalse(shouldRestoreFollow(following = false, touching = false, scrolling = false, atBottom = false, hasContent = true))
        // 空会话
        assertFalse(shouldRestoreFollow(following = false, touching = false, scrolling = false, atBottom = true, hasContent = false))
    }

    // ---------- 「自己发的消息」的判据（ChatDecisions） ----------

    private fun row(id: Long, role: String, name: String? = null) =
        MessageEntity(id = id, conversationId = 1L, role = role, content = "x", name = name, createdAt = 0L)

    /** 带 name 的 user 行（通知 / 插话）不是「自己发的」；这条判据全库六处共用。 */
    @Test
    fun onlyUnnamedUserRowsCountAsOwnMessages() {
        val mine = row(7L, "user")
        val notice = row(5L, "user", ConversationRepository.JOB_NOTICE)
        val steering = row(6L, "user", ConversationRepository.STEERING)
        val assistant = row(8L, "assistant")
        assertTrue(isOwnUserMessage(mine))
        assertFalse(isOwnUserMessage(notice))
        assertFalse(isOwnUserMessage(steering))
        assertFalse(isOwnUserMessage(assistant))

        assertEquals(7L, lastOwnUserId(listOf(notice, mine, assistant, steering)))
        assertEquals(0L, lastOwnUserId(emptyList()))
        assertEquals(0L, lastOwnUserId(listOf(assistant, notice)))
        assertNull(lastOwnUserMessage(listOf(assistant)))
        assertEquals(7L, lastOwnUserMessage(listOf(mine, assistant))?.id)
    }

    /** 通知落库**不恢复跟随**：ownInput 吃的是 lastOwnUserId 的结果（通知换了 id，但它不是轮首）。 */
    @Test
    fun aBackgroundNoticeDoesNotPullTheReaderBackToTheBottom() {
        val s = ChatScrollState(slopPx = 25)
        val mine = row(7L, "user")
        val notice = row(9L, "user", ConversationRepository.JOB_NOTICE)
        assertFalse(s.ownInput(lastOwnUserId(emptyList())))            // 首次只记，不跟随
        assertTrue(s.ownInput(lastOwnUserId(listOf(mine))))            // 真用户行 → 恢复跟随
        assertFalse(s.ownInput(lastOwnUserId(listOf(mine, notice))))   // 通知落库：判据仍是 7，不拉动
    }

    // ---------- ⑨ 读者上滑：哪怕几个像素也立刻交出跟随 ----------

    @Test
    fun anyUpwardReaderScrollReleasesFollowEvenInsideTheHysteresis() {
        val s = ChatScrollState(slopPx = 25)
        assertTrue(s.following)
        // 只上滑 3px（远在 25dp 迟滞以内）：仍然立刻交出跟随，下一次贴底不会把读者按回去
        assertTrue(s.sampled(previous = 1000, current = 997, atBottom = true, readerScrolling = true))
        assertFalse(s.following)
    }

    @Test
    fun programmaticClampIsNotAnUpwardReaderScroll() {
        val s = ChatScrollState(slopPx = 25)
        // 内容变短导致回夹（value 变小、落回内容末端），不是读者上滑：跟随意图不能被关掉
        assertTrue(s.sampled(previous = 1000, current = 900, atBottom = true, readerScrolling = false))
        assertTrue(s.following)
    }

    @Test
    fun scrollingBackToTheBottomRestoresFollow() {
        val s = ChatScrollState(slopPx = 25)
        s.sampled(previous = 1000, current = 500, atBottom = false, readerScrolling = true)
        assertFalse(s.following)
        // 读者自己滑回底部（偏移变大、落进 25dp 以内）：恢复跟随
        s.sampled(previous = 500, current = 900, atBottom = true, readerScrolling = true)
        assertTrue(s.following)
    }


    // ---------- ⑩ 键盘 / 视口变化：只在跟随中才把视口带到新末端 ----------

    /** 贴着底时键盘弹起：视口变化要贴到新末端（消息跟着键盘一起上移）。 */
    @Test
    fun aViewportChangePinsWhileFollowing() {
        val s = ChatScrollState(slopPx = 25)
        val items = Any()
        assertTrue(s.pinRequest(content = items, viewport = 0, scrolling = false))
        // 键盘弹起（视口指纹变了）：跟随中 ⇒ 贴
        assertTrue(s.pinRequest(content = items, viewport = 480, scrolling = false))
    }

    /** 读者翻过历史（「回到底部」按钮可见）时，键盘 / 内容 / 输入区高度怎么变都不许把视口拽回底部。 */
    @Test
    fun aReaderWhoScrolledUpIsNeverPulledBack() {
        val s = ChatScrollState(slopPx = 25)
        val items = Any()
        assertTrue(s.pinRequest(content = items, viewport = 0, scrolling = false))
        s.pause()
        assertFalse(s.following)
        // 键盘弹起 / 内容长高 / 输入区变高：一律不贴
        assertFalse(s.pinRequest(content = items, viewport = 480, scrolling = false))
        assertFalse(s.pinRequest(content = Any(), viewport = 480, scrolling = false))
        assertFalse(s.chromeMeasured(height = 200, total = 10))
        assertFalse(s.chromeMeasured(height = 260, total = 10))
    }

    /** 点「回到底部」（[ChatScrollState.resume]）之后，键盘等视口变化照常贴。 */
    @Test
    fun returningToTheBottomResumesKeyboardPinning() {
        val s = ChatScrollState(slopPx = 25)
        s.pause()
        assertFalse(s.pinRequest(content = Any(), viewport = 480, scrolling = false))
        s.resume()
        assertTrue(s.following)
        assertTrue(s.pinRequest(content = Any(), viewport = 960, scrolling = false))
    }
}
