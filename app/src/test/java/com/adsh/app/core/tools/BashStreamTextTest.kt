package com.adsh.app.core.tools

import com.adsh.app.runtime.termux.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bash 正文里那行「丢过字节」的提示 —— dsh-tool-bash 的 `streamText`（第 185 轮，逐字对齐）。
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
