package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 启动回到哪条会话（用户第 191 轮口径：不再每次启动都开一条新会话）。
 *
 * 钉住三级回落：上次那条 → 最近一条非空会话 → null（停在首页，不建）。
 */
class SessionResumeTest {

    private fun conversation(id: Long, updatedAt: Long) = ConversationEntity(
        id = id,
        title = "会话 " + id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    /** Repository.conversations 按 updatedAt 倒序，所以列表顺序就是「最近」 */
    private val all = listOf(conversation(7, 700), conversation(6, 600), conversation(5, 500))

    @Test
    fun resumesTheConversationThatWasOnScreen() {
        assertEquals(5L, resumeConversationId(all, blankIds = emptySet(), lastId = 5L))
    }

    @Test
    fun fallsBackToTheMostRecentWhenTheLastOneIsGone() {
        assertEquals(7L, resumeConversationId(all, blankIds = emptySet(), lastId = 99L))
    }

    @Test
    fun skipsBlankSessions() {
        // 7 号是一条「新会话」占位：回到它等于又开了一条新的 —— 往下找真正聊过的那条
        assertEquals(6L, resumeConversationId(all, blankIds = setOf(7L), lastId = 99L))
    }

    @Test
    fun aBlankLastSessionIsStillWhatTheUserLeftOn() {
        // 上次关掉时正停在一条空会话上（用户自己点了「新会话」再退出）：回到它就是回到那一屏，
        // 这里**不跳过** —— 跳过等于把用户离开时的样子换掉。空白只在回落那一级才排除。
        assertEquals(7L, resumeConversationId(all, blankIds = setOf(7L), lastId = 7L))
    }

    @Test
    fun nothingToResumeWhenThereIsNoConversation() {
        assertNull("一条会话都没有：不建，停在首页", resumeConversationId(emptyList(), blankIds = emptySet(), lastId = null))
        assertNull(
            "只剩空会话、也没有「上次那条」：同样停在首页",
            resumeConversationId(all, blankIds = setOf(5L, 6L, 7L), lastId = 99L),
        )
    }

    @Test
    fun withoutARememberedIdItStillResumesTheMostRecent() {
        assertEquals(7L, resumeConversationId(all, blankIds = emptySet(), lastId = null))
    }
}
