package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式正文/思考的上屏口径（dsh 的流式语义）：**收到什么就画什么**，33ms 节拍只做重绘节流，
 * 不做逐字平滑；只有「变短 / 换段」要立刻跟上（[shouldLatch]，否则会重复画一段正文）。
 */
class StreamRevealTest {

    @Test
    fun aLiveChunkIsShownAsItArrives() {
        val reveal = StreamReveal("", "")
        reveal.tick(liveText = "第一块", liveThinking = "")
        assertEquals("第一块", reveal.text)
        reveal.tick(liveText = "第一块第二块", liveThinking = "")
        assertEquals("第一块第二块", reveal.text)
    }

    @Test
    fun theThinkingTailFollowsTheSameRule() {
        val reveal = StreamReveal("", "")
        reveal.tick(liveText = "", liveThinking = "想了一半")
        assertEquals("想了一半", reveal.thinking)
        reveal.tick(liveText = "", liveThinking = "想完了，开始写")
        assertEquals("想完了，开始写", reveal.thinking)
    }

    @Test
    fun latchOnlyWhenTheTailNoLongerMatchesTheShownPrefix() {
        // 清零 / 换段：立刻跟上（否则会「正文重复一行、工具行跳一下」）
        assertTrue(shouldLatch(shown = "你好世界", live = ""))
        assertTrue(shouldLatch(shown = "你好世界", live = "换了一段"))
        // 正常增长与一模一样：走 33ms 节拍，不在这一支上抢跑
        assertFalse(shouldLatch(shown = "你好", live = "你好世界"))
        assertFalse(shouldLatch(shown = "", live = "你好"))
        assertFalse(shouldLatch(shown = "你好", live = "你好"))
    }

    @Test
    fun latchStepsInImmediatelyOnAShrinkingTail() {
        val reveal = StreamReveal("上一段正文", "上一段思考")
        reveal.latchText("")
        reveal.latchThinking("换了一段思考")
        assertEquals("", reveal.text)
        assertEquals("换了一段思考", reveal.thinking)
        // 增长不在这里抢跑（等 33ms 那一拍）
        reveal.latchText("新的一段")
        assertEquals("", reveal.text)
    }

    @Test
    fun settleFlushesTheFinalFrame() {
        val reveal = StreamReveal("", "")
        reveal.settle(liveText = "最后几个字", liveThinking = "想完了")
        assertEquals("最后几个字", reveal.text)
        assertEquals("想完了", reveal.thinking)
    }
}
