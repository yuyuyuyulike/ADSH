package com.adsh.app.core.tools

import com.adsh.app.core.jobs.Jobs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台任务的两个渲染（[renderJobDelta] / [renderPromoted]）。
 *
 * 两者都是纯字符串、原先零用例，但它们是模型**唯一**能看到的那部分：job_output 读回来的一屏，
 * 以及「前台命令超时转后台」时那三行正文。dsh 的 renderModelDelta / renderPromoted 逐字对照。
 */
class JobRenderTest {

    private fun chunk(text: String, channel: Jobs.Channel? = Jobs.Channel.STDOUT, gap: Boolean = false) =
        Jobs.Chunk(at = 0, text = text, channel = channel, gapBefore = gap)

    // ------------------------------------------------------------ 消费读的正文

    @Test
    fun `stdout 与 stderr 分段，stderr 带方括号头`() {
        assertEquals(
            "out\n[stderr]\nerr",
            renderJobDelta(listOf(chunk("out"), chunk("err", Jobs.Channel.STDERR)), lossy = false),
        )
    }

    /** stdout 自己以换行结尾时不再补 —— 与 bash 的 renderResult 同一口径 */
    @Test
    fun `stdout 以换行结尾就不重复补`() {
        assertEquals("out\n[stderr]\nerr", renderJobDelta(listOf(chunk("out\n"), chunk("err", Jobs.Channel.STDERR)), false))
    }

    @Test
    fun `LOG 通道不进模型`() {
        assertEquals("out", renderJobDelta(listOf(chunk("out"), chunk("log line", Jobs.Channel.LOG)), false))
    }

    @Test
    fun `只有 LOG 时正文是空串`() {
        assertEquals("", renderJobDelta(listOf(chunk("x", Jobs.Channel.LOG)), false))
    }

    @Test
    fun `掉字节（lossy）时补 dsh 的丢弃提示`() {
        val body = renderJobDelta(listOf(chunk("out")), lossy = true)
        assertEquals("out\n[some output was dropped from memory; full output: (unavailable)]", body)
    }

    /** 块上打了 gap 也算掉字节：游标落在保留窗口之前时生产者在块上标了 gapBefore */
    @Test
    fun `块上带 gap 也补提示`() {
        assertEquals(
            "out\n[some output was dropped from memory; full output: (unavailable)]",
            renderJobDelta(listOf(chunk("out", gap = true)), lossy = false),
        )
    }

    @Test
    fun `正文空又要补提示：提示自成一行`() {
        assertEquals(
            "[some output was dropped from memory; full output: (unavailable)]",
            renderJobDelta(emptyList(), lossy = true),
        )
    }

    @Test
    fun `正文以换行结尾时提示不重复补换行`() {
        assertEquals(
            "out\n[some output was dropped from memory; full output: (unavailable)]",
            renderJobDelta(listOf(chunk("out\n", gap = true)), lossy = false),
        )
    }

    // ------------------------------------------------------- 超时转后台的正文

    @Test
    fun `转后台：保留已产出的输出 + 两句交代`() {
        val body = renderPromoted("job-7", 3000, "partial")
        assertEquals(
            "partial\n" +
                "[still running after 3000ms; moved to background job job-7]\n" +
                "The command keeps running in the background. You will be notified when it finishes; " +
                "read newer output with job_output, stop it with job_kill.",
            body,
        )
    }

    @Test
    fun `转后台：没有输出时不补空行`() {
        val body = renderPromoted("job-7", 1000, "")
        assertEquals(
            "[still running after 1000ms; moved to background job job-7]\n" +
                "The command keeps running in the background. You will be notified when it finishes; " +
                "read newer output with job_output, stop it with job_kill.",
            body,
        )
    }

    @Test
    fun `转后台：输出已以换行结尾就不重复补`() {
        val body = renderPromoted("job-1", 10, "x\n")
        assertEquals(
            "x\n[still running after 10ms; moved to background job job-1]\n" +
                "The command keeps running in the background. You will be notified when it finishes; " +
                "read newer output with job_output, stop it with job_kill.",
            body,
        )
    }
}
