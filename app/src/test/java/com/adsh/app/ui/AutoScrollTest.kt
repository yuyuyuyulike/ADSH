package com.adsh.app.ui

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动滚动的判定（[ScrollFollow] / [scrollTowardTop] / [bottomGapOf] / [shouldPinFix]）——
 * 第 122 轮那套 dsh 口径里能脱离 LazyListState 的部分。每条都对应一轮真机反馈：
 *  - 跟随意图与「现在离底部多少像素」无关（内容每帧长高都不会让它翻转）；
 *  - 方向判据用位置增量，不是 `lastScrolledBackward`（后者在「这一帧没动」时会误判）；
 *  - 离底部的距离必须落在**最后一项**上（否则「回到底部」按钮一闪一灭）；
 *  - 兜底补钉只挡「视口没动、布局却变了」——位置动了就是读者自己滚的，一律不补。
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

    // ---------- 方向（第 96 轮）----------

    @Test
    fun movingUpIsTowardsTheTop() {
        // 同一行内往上：行内偏移变小
        assertTrue(scrollTowardTop(10 to 500, 10 to 499))
        // 跨行往上：第一条可见行变小
        assertTrue(scrollTowardTop(10 to 0, 9 to 800))
    }

    @Test
    fun movingDownOrStandingStillIsNotTowardsTheTop() {
        assertFalse(scrollTowardTop(10 to 500, 10 to 501))
        assertFalse(scrollTowardTop(10 to 0, 11 to 0))
        // 这一帧没动：两个 lastScrolled* 可能同时为 true，位置增量没有这个歧义
        assertFalse(scrollTowardTop(10 to 500, 10 to 500))
    }

    // ---------- 离底部的距离（第 105 / 122 轮）----------

    @Test
    fun atTheVeryBottomTheGapIsExactlyZero() {
        // 最后一项底边 900+200 = 1100 = 视口底边（没有底部内边距）
        assertEquals(
            0,
            bottomGapOf(
                canScrollForward = true,
                lastIndex = 9,
                lastOffset = 900,
                lastSize = 200,
                totalItemsCount = 10,
                viewportEndOffset = 1100,
                afterContentPadding = 0,
            ),
        )
    }

    @Test
    fun bottomPaddingCountsTowardsTheGapAndItCanGoNegative() {
        // 视口底边之上还有 16px 内边距：还差 16px 才算贴到「内容末端」
        assertEquals(16, bottomGapOf(true, 9, 900, 200, 10, 1100, 16))
        // 已经越过内容末端（在底部时正好 0，可为负）
        assertEquals(-50, bottomGapOf(true, 9, 900, 200, 10, 1150, 0))
    }

    @Test
    fun whenTheLastItemIsNotVisibleTheGapIsMaxValue() {
        // 露出来的最后一项不是列表最后一项：后面还有没看到的内容
        assertEquals(Int.MAX_VALUE, bottomGapOf(true, 3, 0, 200, 10, 1100, 0))
        // 视口里一行都没有
        assertEquals(Int.MAX_VALUE, bottomGapOf(true, null, 0, 0, 10, 1100, 0))
        // 滚不动了就是「整个会话的底部」——哪怕最后一项没在可见行里
        assertEquals(0, bottomGapOf(false, 3, 0, 200, 10, 1100, 0))
    }

    // ---------- 兜底补钉（第 122 轮）----------

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

    @Test
    fun pinFixOnlyCoversViewportStillButLayoutChanged() {
        assertTrue(shouldPinFix(moved = false, gapChanged = true))
    }

    @Test
    fun aReaderMoveIsNeverPinnedBack() {
        // 读者自己滚出来的位移：只重算意图、绝不贴底（否则「极小幅度快速上滑」会被一帧帧拽回去）
        assertFalse(shouldPinFix(moved = true, gapChanged = true))
        assertFalse(shouldPinFix(moved = true, gapChanged = false))
    }

    @Test
    fun nothingChangedMeansNothingToDo() {
        assertFalse(shouldPinFix(moved = false, gapChanged = false))
    }

    // ---------- 状态机（R14：从 ChatScreen 主函数搬出来的那份状态）----------

    /** ③ 两个触发指纹：变了才发请求，静止的会话一次都不发（第 122 轮「结束后自动滚动还在跑」） */
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

    /** ② 位置采样：没动就什么都不做；动了才记方向、并按阈值重算跟随意图 */
    @Test
    fun samplingOnlyRunsWhenThePositionActuallyMoved() {
        val s = ChatScrollState(slopPx = 25)
        assertFalse(s.sampled(10 to 500, 10 to 500, atBottom = false))
        assertFalse(s.towardTop)
        assertTrue(s.following)
        // 往上翻（同一行内偏移变小）：方向翻上去、意图交给读者
        assertTrue(s.sampled(10 to 500, 10 to 499, atBottom = false))
        assertTrue(s.towardTop)
        assertFalse(s.following)
        // 往下滚回底部：方向立刻收起来（第 96 轮「往下滑了按钮还在」），25dp 以内恢复跟随
        assertTrue(s.sampled(10 to 499, 11 to 0, atBottom = true))
        assertFalse(s.towardTop)
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
                canScrollForward = false,
                itemCount = 12,
            ),
        )
    }

    @Test
    fun restoreNeverFightsTheReaderOrAnEmptyList() {
        // 已经在跟随：没什么可恢复的（也不该每帧写一次状态）
        assertFalse(shouldRestoreFollow(following = true, touching = false, scrolling = false, canScrollForward = false, itemCount = 12))
        // 手指还在屏幕上 / 读者正在滚：不抢（与 pinAllowed 同一规矩）
        assertFalse(shouldRestoreFollow(following = false, touching = true, scrolling = false, canScrollForward = false, itemCount = 12))
        assertFalse(shouldRestoreFollow(following = false, touching = false, scrolling = true, canScrollForward = false, itemCount = 12))
        // 还有内容可滚：按钮该留着（这正是「交了跟随又没贴底」的正常状态）
        assertFalse(shouldRestoreFollow(following = false, touching = false, scrolling = false, canScrollForward = true, itemCount = 12))
        // 空会话
        assertFalse(shouldRestoreFollow(following = false, touching = false, scrolling = false, canScrollForward = false, itemCount = 0))
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
}
