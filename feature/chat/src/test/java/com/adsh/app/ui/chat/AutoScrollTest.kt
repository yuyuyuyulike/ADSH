package com.adsh.app.ui.chat

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动滚动判定（[ChatScrollState] / [pinAllowed] / [shouldRestoreFollow]）—— 与滚动容器无关的部分：
 * 读者在底部才跟随；只认读者自己的滚动；手指在屏上 / 正在滚时一律不贴。
 */
class AutoScrollTest {

    // ---------- 跟随状态 ----------

    @Test
    fun startsFollowingAndOnlyReaderMovementChangesIt() {
        val s = ChatScrollState()
        assertTrue(s.following)
        s.pause()
        assertFalse(s.following)
        s.resume()
        assertTrue(s.following)
        // 程序位移（贴底 / 内容变短后的回夹）不改状态
        assertTrue(s.sampled(previous = 500, current = 400, atBottom = false, readerScrolling = false))
        assertTrue(s.following)
        // 读者上滑离开底部：立即交出跟随
        assertTrue(s.sampled(previous = 500, current = 499, atBottom = false, readerScrolling = true))
        assertFalse(s.following)
        // 读者滑回末端：恢复跟随
        assertTrue(s.sampled(previous = 499, current = 900, atBottom = true, readerScrolling = true))
        assertTrue(s.following)
    }

    @Test
    fun samplingDoesNothingWhenThePositionDidNotMove() {
        val s = ChatScrollState()
        s.pause()
        assertFalse(s.sampled(previous = 500, current = 500, atBottom = true, readerScrolling = true))
        assertFalse(s.following)
    }

    // ---------- 贴底的闸门 ----------

    @Test
    fun pinIsAllowedOnlyWhileTheReaderIsIdleAndFollowing() {
        assertTrue(pinAllowed(following = true, touching = false, scrolling = false))
        // 已经离开底部
        assertFalse(pinAllowed(following = false, touching = false, scrolling = false))
        // 手指还在屏幕上
        assertFalse(pinAllowed(following = true, touching = true, scrolling = false))
        // 读者正在滚（拖拽 / 惯性）：这一拍不贴，否则刚滑开的位置会被按回去
        assertFalse(pinAllowed(following = true, touching = false, scrolling = true))
    }

    // ---------- 状态机 ----------

    /** 贴底请求只由内容变化触发：静止的会话一次都不发。 */
    @Test
    fun pinRequestOnlyFiresWhenContentChanges() {
        val s = ChatScrollState()
        val items = Any()
        // 第一次：指纹还没记过 ⇒ 进会话贴一次底
        assertTrue(s.pinRequest(content = items, scrolling = false))
        assertEquals("content", s.pinReason)
        // 同一份 items：一次都不发
        assertFalse(s.pinRequest(content = items, scrolling = false))
        // items 换了实例（列表的任何一个输入变了）
        assertTrue(s.pinRequest(content = Any(), scrolling = false))
        assertEquals("content", s.pinReason)
    }

    @Test
    fun aBlockedPinRequestStillRemembersTheFingerprint() {
        val s = ChatScrollState()
        assertTrue(s.pinRequest(content = Any(), scrolling = false))
        // 内容变了 + 读者正在滚：这一拍不贴
        val items = Any()
        assertFalse(s.pinRequest(content = items, scrolling = true))
        // 读者停下、内容没再变：也不该补一发（补了就是把读者按回底部）
        assertFalse(s.pinRequest(content = items, scrolling = false))
        // 手指还按着：同理
        s.touch(true)
        assertFalse(s.pinRequest(content = items, scrolling = false))
        s.touch(false)
        assertFalse(s.pinRequest(content = items, scrolling = false))
        // 内容真的又变了，这时才贴
        assertTrue(s.pinRequest(content = Any(), scrolling = false))
    }

    @Test
    fun pausingFollowBlocksThePinEvenWhenContentGrows() {
        val s = ChatScrollState()
        s.pause()
        assertFalse(s.following)
        val items = Any()
        assertFalse(s.pinRequest(content = items, scrolling = false))
        s.resume()
        assertTrue(s.following)
        // 指纹已经记过：恢复跟随本身不发请求（点「回到底部」会显式贴一次）
        assertFalse(s.pinRequest(content = items, scrolling = false))
    }

    /** 只有「用户自己发的消息」换了 id 才恢复跟随；刚进这一屏时只记不跟随。 */
    @Test
    fun ownInputFollowsOnlyWhenARealUserMessageArrives() {
        val s = ChatScrollState()
        assertFalse(s.ownInput(0L))
        assertFalse(s.ownInput(0L))
        assertTrue(s.ownInput(7L))
        assertFalse(s.ownInput(7L))
        assertTrue(s.ownInput(9L))
    }

    // ---------- 内容缩到贴底时恢复跟随 ----------

    @Test
    fun aCollapseThatLandsAtTheEndRestoresFollowing() {
        // 读者早已交出跟随，现在内容缩到贴底 ⇒ 跟随状态收回来（按钮据此消失）
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
        assertFalse(shouldRestoreFollow(following = true, touching = false, scrolling = false, atBottom = true, hasContent = true))
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
        val s = ChatScrollState()
        val mine = row(7L, "user")
        val notice = row(9L, "user", ConversationRepository.JOB_NOTICE)
        assertFalse(s.ownInput(lastOwnUserId(emptyList())))            // 首次只记，不跟随
        assertTrue(s.ownInput(lastOwnUserId(listOf(mine))))            // 真用户行 → 恢复跟随
        assertFalse(s.ownInput(lastOwnUserId(listOf(mine, notice))))   // 通知落库：判据仍是 7，不拉动
    }
}
