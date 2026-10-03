package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式文本的**平滑显现**。
 *
 * 用户口径的演变（三条都在这张表里）：
 *  - 第 95 轮：「模型输出速度较慢时是多个字逐批输出，看起来卡顿，快的时候就很流畅」——
 *    症状来自「一个 SSE 块整块落地」，于是加了「按比例分步显现」；
 *  - 第 101 轮：「思考内容在展示时，对于速度较慢的模型经常出现：在一个单词上顿住一瞬然后再铺展至
 *    一行的情况，应该顺畅些」—— 那一轮把分母从 4 提到 10（摊到 10 拍）；
 *  - **第 105 轮**：「流式输出呈现出先快后慢的感觉，这肯定不对，有时 agent 都开始调用工具了，
 *    输出才结束，就是输出顺畅即可，不一定非要一个字一个字的输出」。
 *
 * 第 105 轮重做的两条规矩（旧算法的毛病见 [revealLength] 的注释）：
 *  - 一拍至少 [REVEAL_MIN_STEP] 个字 —— 不再有「积压越小走得越慢」的逐字滴；
 *  - 积压不超过 [REVEAL_MAX_LAG] 个字 —— 尾巴有界，模型开始写工具参数时更是直接补齐
 *    （那一支在 [StreamReveal.tick] 里，见 `toolArgsFlowing`）。
 */
class StreamRevealTest {

    @Test
    fun aLoneCharacterArrivesOnTheNextFrame() {
        assertEquals(1, revealLength(0, 1))
        assertEquals(2, revealLength(1, 2))
    }

    @Test
    fun smallBatchesAreNeverSpelledOutOneCharacterAtATime() {
        // 一次来 6 个字：不再六拍一字一字地滴 —— 一拍就把 REVEAL_MIN_STEP 走掉
        var shown = 0
        val steps = ArrayList<Int>()
        while (shown < 6) {
            shown = revealLength(shown, 6)
            steps += shown
        }
        assertEquals(listOf(REVEAL_MIN_STEP, 6), steps)
    }

    @Test
    fun aLongBatchIsSpreadOverOnlyAFewFrames() {
        // 慢模型一次吐一整行（30 字）：几拍铺完（不再摊到十几拍），单步也不至于把整行砸下来
        var shown = 0
        var frames = 0
        var biggest = 0
        while (shown < 30) {
            val next = revealLength(shown, 30)
            biggest = maxOf(biggest, next - shown)
            shown = next
            frames++
        }
        assertEquals(30, shown)
        assertTrue("铺完一行用了 $frames 拍，期望 3~6 拍", frames in 3..6)
        // 第一拍会带走「超过积压上限的那部分」（上限 18 = 一拍最多砸下 18 字），之后逐拍收敛；
        // 上限的作用是让尾巴有界，代价就是一次超大突发的前一两拍会跳一点 —— 这是有意的取舍：
        // 用户要的是「别让输出拖在工具调用后面」，而不是「一个字一个字地显」。
        assertTrue("单步跨了 $biggest 字，太跳了", biggest <= REVEAL_MAX_LAG)
    }

    @Test
    fun theRevealNeverOvershootsTheTarget() {
        for (shown in 0..40) {
            for (target in shown..60) {
                val next = revealLength(shown, target)
                assertTrue("next 必须落在 ($shown, $target]", next > shown || target == shown)
                assertTrue("next 不能超过 target（shown=$shown target=$target next=$next）", next <= target)
            }
        }
    }

    @Test
    fun theBacklogIsCappedSoTheTailCannotLinger() {
        // 输出很快、每一拍都来一大块：积压**永远不超过上限**（旧算法只保证「不越拖越远」，
        // 稳态积压是输入的 10 拍，一个快模型能攒下几百字，交接时就要滴好几秒）
        var shown = 0
        var target = 0
        repeat(60) {
            target += 40
            shown = revealLength(shown, target)
            assertTrue(
                "target - shown = " + (target - shown) + " 超过上限",
                target - shown <= REVEAL_MAX_LAG,
            )
        }
        assertTrue(shown > 0)
    }

    @Test
    fun theTailDrainsInOnlyAFewFramesAfterTheModelStops() {
        // 模型说完最后一个字就转去吐工具参数：剩下的尾巴必须在几拍之内排空
        var shown = 0
        val target = 200
        var frames = 0
        while (shown < target) {
            shown = revealLength(shown, target)
            frames++
            assertTrue("排空尾巴用了太多拍", frames <= 10)
        }
        assertEquals(target, shown)
        // 旧算法（分母 10 + 小积压一拍 1 字）在同样的输入下要 20 拍以上
        assertTrue("排空只用了 $frames 拍", frames <= 8)
    }

    @Test
    fun shrinkingOrReplacedTextJumpsImmediately() {
        // 清零 / 换了一段：立刻跟上，不能按「显现」慢慢退
        assertEquals("", revealFrom("你好世界", ""))
        assertEquals("换了一段", revealFrom("你好世界", "换了一段"))
        // 前缀关系（正常增长）走「显现」：一次来 2 个字时一拍就显完（最小步长 3），
        // 尾巴稍长时按最小步长推进而不是逐字
        assertEquals("你好世界", revealFrom("你好", "你好世界"))
        assertEquals("你好世界你", revealFrom("你好", "你好世界你好世界"))
    }

    // ---------- 采样状态（R14 第五步：从 ChatScreen 主函数搬出来的那份状态机）----------

    @Test
    fun latchOnlyWhenTheTailNoLongerMatchesTheShownPrefix() {
        // 清零 / 换段：立刻跟上（第 81 轮那个「正文重复一行、工具行跳一下」的 bug）
        assertTrue(shouldLatch(shown = "你好世界", live = ""))
        assertTrue(shouldLatch(shown = "你好世界", live = "换了一段"))
        // 正常增长与一模一样：走 33ms 节拍，不在这一支上抢跑
        assertFalse(shouldLatch(shown = "你好", live = "你好世界"))
        assertFalse(shouldLatch(shown = "", live = "你好"))
        assertFalse(shouldLatch(shown = "你好", live = "你好"))
    }

    @Test
    fun toolArgsFlowingFlushesTheTextTailInOneTick() {
        val live = "这一段正文其实已经写完了".repeat(20)
        val reveal = StreamReveal("", "")
        // 模型开始写 run_code 的参数：不再按拍滴，一次补齐（第 105 轮第 1 条）
        reveal.tick(liveText = live, liveThinking = "", toolArgsFlowing = true, reasoningRunning = false)
        assertEquals(live, reveal.text)
    }

    @Test
    fun aGrowingTextTailStillDrips() {
        val live = "还在往外吐的正文".repeat(20)
        val reveal = StreamReveal("", "")
        reveal.tick(liveText = live, liveThinking = "", toolArgsFlowing = false, reasoningRunning = false)
        assertTrue("一拍只走一小步：" + reveal.text.length, reveal.text.length < live.length)
        assertTrue(reveal.text.isNotEmpty())
        // 但尾巴有界：不会一直差着老远（REVEAL_MAX_LAG）
        assertTrue(reveal.text.length >= live.length - REVEAL_MAX_LAG)
    }

    @Test
    fun theThinkingTailFlushesAsSoonAsItsStepStopsThinking() {
        val live = "很长的思考".repeat(30)
        val reveal = StreamReveal("", "")
        reveal.tick(liveText = "", liveThinking = live, toolArgsFlowing = false, reasoningRunning = true)
        assertTrue(reveal.thinking.length < live.length)
        reveal.tick(liveText = "", liveThinking = live, toolArgsFlowing = false, reasoningRunning = false)
        assertEquals(live, reveal.thinking)
    }

    @Test
    fun settleFlushesWhateverIsLeftOnTheFinalFrame() {
        val reveal = StreamReveal("", "")
        reveal.settle(liveText = "最后几个字", liveThinking = "想完了")
        assertEquals("最后几个字", reveal.text)
        assertEquals("想完了", reveal.thinking)
    }
}
