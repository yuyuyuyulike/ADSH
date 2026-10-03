package com.adsh.app.core.tools

import com.adsh.app.core.ptc.SubCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run_code 失败回执（第 101 轮加，**第 105 轮按测试 agent 的反馈重做**）：失败消息里要能读出
 * 「哪些子调用已经跑成、结果是成功还是失败、值还在不在」，模型据此只补发该补的那几步。
 *
 * 用户口径：run_code 下一个工具失败会带崩它后面的，只能重发 —— 抛错语义照 dsh 不动
 * （dsh 也是 reject + 中止，且「Programs are never replayed automatically」），
 * 能省的是**重发的内容**。
 *
 * 第 105 轮改掉的两处（实测报告问题 1）：
 *  - 旧文案一句 "their effects are kept, so do not redo them" 对**只读**调用是误导：
 *    读操作没有「副作用被保留」这回事，需要的是结果，而结果跟程序一起没了；
 *  - 摘要只有结果的第一行 —— 探测型命令（curl 打状态码）看不出成败，也没有 exit code。
 */
class RunCodeDigestTest {

    private fun call(name: String, result: String, ok: Boolean = true) = SubCall(
        name = name,
        args = "{}",
        ok = ok,
        result = result,
        durationMs = 1,
        id = name + "-1",
    )

    @Test
    fun noSuccessfulSubCallMeansNoReceipt() {
        assertNull(RunCodeTool.completedDigest(emptyList()))
        // 全是失败的：正文里已经写了「哪个工具失败、原始错误是什么」，再来一张空回执只是噪音
        assertNull(RunCodeTool.completedDigest(listOf(call("write", "EACCES: denied", ok = false))))
    }

    @Test
    fun failedSubCallsAreNotListedAsCompleted() {
        val digest = RunCodeTool.completedDigest(
            listOf(
                call("read", "1\t第一行\n2\t第二行"),
                call("write", "EACCES: permission denied", ok = false),
                call("glob", "src/a.kt\nsrc/b.kt"),
            ),
        )!!
        assertTrue(digest.contains("already completed (2)"))
        assertTrue(digest.contains("- read (read-only) — 1\t第一行"))
        assertTrue(digest.contains("- glob (read-only) — src/a.kt"))
        assertFalse("失败的那一条不进回执（它在正文里）", digest.contains("EACCES"))
        // 顺序 = 子调用顺序
        assertTrue(digest.indexOf("- read") < digest.indexOf("- glob"))
    }

    /** 只读 / 写类必须分开说：读的结果没了（要就重跑），写的效果留着（别重做） */
    @Test
    fun theReceiptSeparatesReadsFromMutations() {
        val digest = RunCodeTool.completedDigest(
            listOf(
                call("bash", "ok\n[exit code: 0]"),
                call("write", "created"),
                call("grep", "hit"),
            ),
        )!!
        assertTrue("写类标 mutating", digest.contains("- write (mutating) — created"))
        // bash 在调度器里是写类（独占），回执里也必须按写类说
        assertTrue("bash 按写类标", digest.contains("- bash (mutating)"))
        assertTrue("grep 标只读", digest.contains("- grep (read-only)"))
        assertTrue("写类的口径：效果留着、别重做", digest.contains("mutations stayed in effect, so do not redo those"))
        assertTrue(
            "只读的口径：结果没了、需要就重跑",
            digest.contains("a read-only call's result died with the program"),
        )
        assertFalse("旧的误导性说法（对读操作不成立）不再出现", digest.contains("their effects are kept"))
    }

    @Test
    fun theReceiptSaysWhatToDoInsteadOfResending() {
        val digest = RunCodeTool.completedDigest(listOf(call("read", "ok")))!!
        assertTrue(digest.contains("only the remaining steps"))
        assertTrue(digest.contains("instead of re-sending this one"))
    }

    @Test
    fun theExitStatusOfAProbeIsShown() {
        // 探测型命令：结果第一行是 curl 自己打的数字，成败只能看末尾那行 [exit code: N]
        val digest = RunCodeTool.completedDigest(
            listOf(call("bash", "200 0.080088s\n[exit code: 0]")),
        )!!
        assertTrue("退出码要带出来：" + digest, digest.contains("[exit code: 0]"))
        assertTrue("结果第一行也要在：" + digest, digest.contains("200 0.080088s"))
        assertTrue("退出码在前（一眼看出成败）", digest.indexOf("[exit code: 0]") < digest.indexOf("200 0.080088s"))
    }

    @Test
    fun moreCallsAreListedThanBefore() {
        // 第 105 轮：6 条太少（实测里一批 8 个并发探测「哪些跑过」都还原不出来），放宽到 12
        val calls = (1..14).map { call("read", "file-$it") }
        val digest = RunCodeTool.completedDigest(calls)!!
        assertTrue("总数要写出来", digest.contains("already completed (14, last 12)"))
        assertFalse("更早的几条不再列", digest.contains("file-2"))
        assertTrue(digest.contains("file-3"))
        assertTrue(digest.contains("file-14"))
    }

    @Test
    fun digestsAreSingleLineAndCapped() {
        val long = "x".repeat(500)
        val digest = RunCodeTool.completedDigest(
            listOf(call("bash", "\n\n  " + long + "\n第二行不该出现")),
        )!!
        assertFalse("只取第一行非空文本", digest.contains("第二行不该出现"))
        val run = Regex("x+").find(digest)?.value ?: ""
        assertTrue("单条摘要要封顶（实际 " + run.length + "）", run.length == RunCodeTool.MAX_DIGEST_CHARS)
        assertTrue("掐过的地方要看得出被掐过：" + digest.takeLast(1), digest.contains("x…"))
    }

    @Test
    fun anEmptyResultStillNamesTheCall() {
        val digest = RunCodeTool.completedDigest(listOf(call("write", "")))!!
        assertTrue(digest.contains("- write"))
        // 没有结果正文时不留下一个孤零零的破折号
        assertFalse(digest.contains("- write (mutating) — \n"))
        assertEquals(1, Regex("- write\\b").findAll(digest).count())
    }
}
