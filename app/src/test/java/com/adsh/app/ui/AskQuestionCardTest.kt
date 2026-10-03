package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ask_user_question 的问答卡（对齐 dsh 的 AskQuestionCard）的纯逻辑部分。
 *
 * 判据容易错在细节上：题目与答案怎么配对（按 id，不是按下标）、没答的题怎么标、
 * 「N/M 已回答」的 N 数的是什么、以及流式半截 JSON 不能被当成合法参数。
 */
class AskQuestionCardTest {

    private val twoQuestions =
        "{\"questions\":[{\"id\":\"q1\",\"question\":\"先做哪个？\",\"header\":\"范围\"}," +
            "{\"id\":\"q2\",\"question\":\"要不要备份？\"}]}"

    private val twoAnswers =
        "{\"answers\":[{\"id\":\"q1\",\"selected\":[\"先做 A\"]}," +
            "{\"id\":\"q2\",\"selected\":[],\"custom\":\"要，先拷一份\"}]}"

    // ---------------------------------------------------------------- 参数解析

    @Test
    fun questionsRequireIdAndText() {
        assertEquals(
            listOf("q1" to "先做哪个？", "q2" to "要不要备份？"),
            askQuestionEntries(twoQuestions).map { it.id to it.question },
        )
        // 缺 id / 缺正文 / 根本不是题目的参数：一律不认（那一行退回 ioCard）
        assertEquals(0, askQuestionEntries("{\"questions\":[{\"question\":\"没有 id\"}]}").size)
        assertEquals(0, askQuestionEntries("{\"questions\":[{\"id\":\"q1\"}]}").size)
        assertEquals(0, askQuestionEntries("{\"file_path\":\"/a/b\"}").size)
        assertEquals(0, askQuestionEntries("").size)
        // 流式半截 JSON（截在字符串中间）：走的是 ToolArgs 的补全解析 —— 能补成合法 JSON 就认
        // （与工具行摘要同一个口径，见 ToolArgs 的注释），补不出来的才是空表
        assertEquals(listOf("q1"), askQuestionEntries("{\"questions\":[{\"id\":\"q1\",\"question\":\"半").map { it.id })
        assertEquals(0, askQuestionEntries("{\"questions\":").size)
    }

    // ---------------------------------------------------------------- 卡片形态

    /** 运行中：unanswered 形态（dsh 的 model.state === "running" 不画答案，只报在等） */
    @Test
    fun runningShowsThePendingVerdict() {
        val card = askCardOf(twoQuestions, null, running = true, error = false)!!
        assertEquals(listOf("先做哪个？", "要不要备份？"), card.questions.map { it.question })
        assertEquals(listOf(0, 0), card.questions.map { it.answers.size })
        assertEquals("本轮没有提交回答", card.verdict)
    }

    /** 答完：answered 形态（<dl>），题目与答案按 id 配对，没答的那道挂「未回答」 */
    @Test
    fun answeredPairsByIdAndKeepsUnansweredQuestions() {
        val card = askCardOf(twoQuestions, twoAnswers, running = false, error = false)!!
        assertNull(card.verdict)
        assertEquals(listOf("先做 A"), card.questions[0].answers)
        assertEquals(listOf("要，先拷一份"), card.questions[1].answers)
        assertEquals(2, card.answered)
        assertEquals(2, card.total)

        // 只答了第一道：第二道保留在卡里（dsh 也是逐题列出，没答的标 skippedLabel）
        val partial = askCardOf(
            twoQuestions,
            "{\"answers\":[{\"id\":\"q1\",\"selected\":[\"先做 A\"]},{\"id\":\"q2\",\"selected\":[]}]}",
            running = false,
            error = false,
        )!!
        assertEquals(listOf("先做 A"), partial.questions[0].answers)
        assertEquals(0, partial.questions[1].answers.size)
        assertEquals(1, partial.answered)
    }

    /** 多选 + 自定义：两者同时存在时各自占一行（dsh 的 answerLine） */
    @Test
    fun multiSelectAndCustomBothShow() {
        val card = askCardOf(
            twoQuestions,
            "{\"answers\":[{\"id\":\"q1\",\"selected\":[\"A\",\"B\"],\"custom\":\"再加 C\"}]}",
            running = false,
            error = false,
        )!!
        assertEquals(listOf("A", "B", "再加 C"), card.questions[0].answers)
    }

    /** 超时 / 被跳过：AskUserTool 返回错误，卡片仍是 unanswered（dsh 的 cancelled / interrupted 形态） */
    @Test
    fun failedAskKeepsTheQuestions() {
        val card = askCardOf(twoQuestions, "the user did not answer (timed out or skipped)", running = false, error = true)!!
        assertEquals("本轮没有提交回答", card.verdict)
        assertEquals(2, card.questions.size)
    }

    /** 参数不是题目：不画卡（null），那一行落到 ioCard */
    @Test
    fun nonQuestionArgumentsProduceNoCard() {
        assertNull(askCardOf("{\"queries\":[\"x\"]}", null, running = false, error = false))
    }

    // ---------------------------------------------------------------- 行摘要

    @Test
    fun rowSummaryCountsAnsweredQuestions() {
        val running = askCardOf(twoQuestions, null, running = true, error = false)
        // 运行中没有结果可数：摘要显示第一道题（dsh 那行是「waiting」，ADSH 这条是通用摘要的退路）
        assertEquals("先做哪个？", askRowSummary(running, running = true, error = false))

        val answered = askCardOf(twoQuestions, twoAnswers, running = false, error = false)
        assertEquals("2/2 已回答", askRowSummary(answered, running = false, error = false))

        val partial = askCardOf(
            twoQuestions,
            "{\"answers\":[{\"id\":\"q1\",\"selected\":[\"先做 A\"]},{\"id\":\"q2\",\"selected\":[]}]}",
            running = false,
            error = false,
        )
        assertEquals("1/2 已回答", askRowSummary(partial, running = false, error = false))

        val failed = askCardOf(twoQuestions, "boom", running = false, error = true)
        assertEquals(ASK_UNANSWERED_LABEL, askRowSummary(failed, running = false, error = true))

        // 结果还没到（没有 answers）：摘要显示第一道题，不编数字
        val pending = askCardOf(twoQuestions, null, running = false, error = false)
        assertEquals("先做哪个？", askRowSummary(pending, running = false, error = false))

        // 不是题目：没有专属摘要
        assertNull(askRowSummary(null, running = false, error = false))
    }
}
