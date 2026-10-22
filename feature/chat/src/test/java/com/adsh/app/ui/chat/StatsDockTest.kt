package com.adsh.app.ui.chat

import com.adsh.app.core.llm.SessionStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框下方胶囊行的可见性判据（[statsDockVisible]）—— UI 与会话载入时的「入场动画是否武装」
 * 共用这一条：有速度或 token 才算跑过一轮，两者都没有时整排不出现。
 */
class StatsDockTest {

    @Test
    fun anEmptySessionShowsNoDock() {
        assertFalse(statsDockVisible(SessionStats()))
    }

    @Test
    fun tokensAloneShowTheDock() {
        assertTrue(statsDockVisible(SessionStats(promptTokens = 1200)))
        assertTrue(statsDockVisible(SessionStats(completionTokens = 42)))
        // 缓存命中算进 billedInput（prompt + cacheHit），也要算「跑过一轮」
        assertTrue(statsDockVisible(SessionStats(cacheHitTokens = 900)))
    }

    @Test
    fun aMeasuredSpeedShowsTheDock() {
        // decodeMillis = llmMillis - ttftMillis = 2000 → tps = 100
        val stats = SessionStats(completionTokens = 200, llmMillis = 3000, ttftMillis = 1000)
        assertTrue(statsDockVisible(stats))
    }
}
