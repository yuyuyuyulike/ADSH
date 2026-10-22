package com.adsh.app.core.tools

import com.adsh.app.seam.exec.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bash 正文里那行「丢过字节」的提示 —— dsh-tool-bash 的 `streamText`（逐字对齐）。
 *
 * 这一行是 spill 机制的**出口**：收集器把全文写到文件里，模型要能从正文里拿到那个路径，
 * 否则「头部不再丢」只是换了个地方藏起来。
 */
class BashStreamTextTest {

    @Test
    fun theNoticeNamesTheSpillFile() {
        assertEquals(
            "out\n[output truncated; full output: /tmp/x.log]",
            streamText("out", true, "/tmp/x.log"),
        )
    }

    @Test
    fun nothingIsAppendedWhenTheStreamWasNotTruncated() {
        assertEquals("out", streamText("out", false, "/tmp/x.log"))
    }

    @Test
    fun withoutAPathItSaysUnavailable() {
        assertEquals(
            "out\n[output truncated; full output: (unavailable)]",
            streamText("out", true, null),
        )
    }

    @Test
    fun theValueCarriesThePathOnlyWhenThereIsOne() {
        val withPath = streamValue("out", true, "/tmp/x.log")
        assertEquals("out", withPath["text"]!!.toString().trim('"'))
        assertEquals("true", withPath["truncated"].toString())
        assertEquals("/tmp/x.log", withPath["spillPath"]!!.toString().trim('"'))

        val without = streamValue("out", false, null)
        assertFalse("没有路径时不该编一个字段出来", without.containsKey("spillPath"))
    }

    /** 字节对齐的尾巴可能从半行开始：丢掉半截首行；没有换行时原样保留 */
    @Test
    fun aTruncatedTailDropsItsPartialFirstLine() {
        assertEquals("b\nc", dropPartialFirstLine("part\nb\nc"))
        assertEquals("b\nc", dropPartialFirstLine("\nb\nc"))
        assertEquals("single", dropPartialFirstLine("single"))
        // 尾巴只有「一条被截断的长行 + 换行」：不能丢成空串，保留它（丢了多少字节由 omittedBytes 说明）
        assertEquals("AAAA\n", dropPartialFirstLine("AAAA\n"))
        assertEquals("", dropPartialFirstLine("\n"))
        assertEquals("b", wholeLineTail("part\nb", true))
        assertEquals("part\nb", wholeLineTail("part\nb", false))
    }

    /** 正文渲染只认「有没有截断」，标记互斥那套不受影响 */
    @Test
    fun theStatusMarkersStillFollowTheTruncationNotice() {
        val result = ExecResult(
            exitCode = 1,
            output = streamText("boom", true, "/tmp/y.log"),
            timedOut = false,
            truncated = true,
            durationMs = 1,
            stderr = "",
            timeoutMs = 1_000,
        )
        val body = renderBash(result, result.output, result.stderr)
        assertTrue(body, body.startsWith("boom\n[output truncated; full output: /tmp/y.log]"))
        assertTrue(body, body.endsWith("[exit code: 1]"))
    }
}
