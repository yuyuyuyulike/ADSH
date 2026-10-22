package com.adsh.app.core.agent

import com.adsh.app.core.agent.ContextLedger.Row
import com.adsh.app.core.agent.ContextLedger.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时上下文的**落库计划**。
 *
 * 这一层是「信息动态注入」的判据所在，错了的表现很难在真机上复现：
 *  - 该追加却就地改 → 前缀缓存整段作废（系统提示词没变，历史却从中间被改写）；
 *  - 该就地改却追加 → 界面上同一条注入越堆越多；
 *  - 快照里混进叙述 → 与 dsh 的 `joinContextSections` 不一致（叙述是**独立 user 消息**）。
 */
class ContextLedgerTest {

    private val policy = { mode: String ->
        PromptAssembler.Injection(
            label = PromptAssembler.LABEL_SANDBOX_POLICY,
            form = PromptAssembler.FORM_SNAPSHOT,
            text = PromptAssembler.sandboxPolicyText(mode),
            delivery = PromptAssembler.Delivery.MESSAGE,
        )
    }
    private val env = PromptAssembler.Injection(
        label = PromptAssembler.LABEL_ENV,
        form = PromptAssembler.FORM_SNAPSHOT,
        text = "## 环境细则",
        delivery = PromptAssembler.Delivery.MESSAGE,
    )
    private val plan = PromptAssembler.Injection(
        label = PromptAssembler.LABEL_PLAN_POLICY,
        form = PromptAssembler.FORM_SNAPSHOT,
        text = PromptAssembler.PLAN_SECTION,
        delivery = PromptAssembler.Delivery.MESSAGE,
    )

    private val snapshotLabel = PromptAssembler.LABEL_ENV
    private val planSnapshotLabel = PromptAssembler.LABEL_ENV + "·plan"

    private fun snapshotText(mode: String, withPlan: Boolean = false): String {
        val sections = listOf(PromptAssembler.sandboxPolicyText(mode), env.text)
        return PromptAssembler.snapshotText(
            (if (withPlan) sections + plan.text else sections).joinToString("\n\n"),
        )
    }

    private fun snapshotRow(id: Long, mode: String, withPlan: Boolean = false): Row =
        Row(id, snapshotText(mode, withPlan), PromptAssembler.FORM_SNAPSHOT)

    private fun planNow(
        previousSnapshot: Row? = null,
        previousInstructions: Row? = null,
        named: Map<String, Row> = emptyMap(),
        injections: List<PromptAssembler.Injection>,
    ): List<Write> = ContextLedger.plan(
        previousSnapshot = previousSnapshot,
        previousInstructions = previousInstructions,
        previousDisplay = named,
        injections = injections,
    )

    private fun count(text: String, needle: String): Int {
        var index = 0
        var found = 0
        while (true) {
            val next = text.indexOf(needle, index)
            if (next < 0) return found
            found++
            index = next + needle.length
        }
    }

    /** 会话第一次发送：一条快照，抬头只出现一次，各段按顺序 */
    @Test
    fun firstSendWritesOneCombinedSnapshot() {
        val writes = planNow(injections = listOf(policy("workspace-write"), env))
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(snapshotLabel, append.label)
        assertEquals(PromptAssembler.FORM_SNAPSHOT, append.form)
        assertEquals(PromptAssembler.RUNTIME_CONTEXT_PREAMBLE, append.text.substringBefore("\n\n"))
        assertEquals("抬头只出现一次", 1, count(append.text, PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
        assertTrue(append.text.contains("Current DSH file policy: workspace-write"))
        assertTrue(append.text.contains("## 环境细则"))
        assertTrue("策略段在环境段之前", append.text.indexOf("Current DSH") < append.text.indexOf("## 环境细则"))
    }

    /** 第二次发送内容没变：一个字都不写（不然每发一条消息就多一条注入） */
    @Test
    fun unchangedSnapshotsWriteNothing() {
        val rows = planNow(
            previousSnapshot = snapshotRow(1, "workspace-write"),
            injections = listOf(policy("workspace-write"), env),
        )
        assertEquals(emptyList<Write>(), rows)
    }

    /**
     * 权限预设换了：**只追加一条新快照**，没有「从 X 换成 Y」的叙述 ——
     * dsh 的沙箱策略段只有「当前策略」正文（packages/sandbox/sandbox-policy/src/index.ts:42-56），
     * 变化就是 retained 文本不同 → 追加。
     */
    @Test
    fun policyChangeAppendsNewSnapshotWithoutNarration() {
        val writes = planNow(
            previousSnapshot = snapshotRow(7, "workspace-write"),
            injections = listOf(policy("danger-full-access"), env),
        )
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertTrue(append.text.contains("Current DSH file policy: danger-full-access"))
        assertFalse(append.text.contains("changed from"))
        assertEquals("抬头仍然只有一次", 1, count(append.text, PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
    }

    /** 快照身份按 form 找：上一轮有哪些段不影响「这是不是同一条快照」 */
    @Test
    fun previousSnapshotIsFoundByFormNotByLabel() {
        val writes = planNow(
            previousSnapshot = Row(11, snapshotText("workspace-write"), PromptAssembler.FORM_SNAPSHOT),
            injections = listOf(policy("workspace-write"), env),
        )
        assertEquals(emptyList<Write>(), writes)
    }

    /** 展示行（自定义后缀）内容变了就地换，不往时间线上堆 */
    @Test
    fun displayRowsAreReplacedInPlace() {
        val injection = PromptAssembler.Injection(
            label = "system-prompt-suffix",
            form = PromptAssembler.FORM_NOTICE,
            text = "新的后缀",
            delivery = PromptAssembler.Delivery.DISPLAY,
        )
        val writes = planNow(
            named = mapOf("system-prompt-suffix" to Row(42, "旧的后缀", "notice")),
            injections = listOf(injection),
        )
        assertEquals(listOf(Write.Replace(42, "新的后缀")), writes)
    }

    /** 计划模式打开：快照多一段 plan（标签也跟着变成三段），没有叙述 */
    @Test
    fun enteringPlanModeReappendsTheSnapshotWithThePlanSection() {
        val writes = planNow(
            previousSnapshot = snapshotRow(1, "workspace-write"),
            injections = listOf(policy("workspace-write"), env, plan),
        )
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(planSnapshotLabel, append.label)
        assertTrue(append.text.contains("You are in plan mode."))
        assertFalse(append.text.contains(PromptAssembler.PLAN_OFF_NARRATION))
        assertEquals(1, count(append.text, PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
    }

    /** 计划模式开着、内容没变：不写 */
    @Test
    fun planModeSnapshotIsWrittenOnce() {
        val rows = planNow(
            previousSnapshot = snapshotRow(3, "workspace-write", withPlan = true),
            injections = listOf(policy("workspace-write"), env, plan),
        )
        assertEquals(emptyList<Write>(), rows)
    }

    /**
     * 计划模式关掉：快照因为少了 plan 段而追加一条，但**叙述不在快照里** ——
     * dsh 的 narration 是独立的 user 消息（AgentLoop 写 [com.adsh.app.core.data.ConversationRepository.PLAN_MODE_NARRATION]）。
     */
    @Test
    fun leavingPlanModeKeepsTheNarrationOutOfTheSnapshot() {
        val writes = planNow(
            previousSnapshot = snapshotRow(3, "workspace-write", withPlan = true),
            injections = listOf(policy("workspace-write"), env),
        )
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(snapshotLabel, append.label)
        assertFalse(append.text.contains(PromptAssembler.PLAN_OFF_NARRATION))
        assertFalse(append.text.contains("You are in plan mode."))
    }

    /** 指令文件链：内容变了追加一条新基线；没变不写 */
    @Test
    fun instructionBaselineAppendsOnlyWhenItChanges() {
        val first = PromptAssembler.Injection(
            label = "/ws/AGENTS.md",
            form = PromptAssembler.FORM_INSTRUCTIONS,
            text = "do things",
            delivery = PromptAssembler.Delivery.INSTRUCTIONS,
        )
        val appended = planNow(injections = listOf(first))
        assertEquals(1, appended.size)
        val row = appended[0] as Write.Append
        assertEquals(PromptAssembler.FORM_INSTRUCTIONS, row.form)
        assertTrue(row.text.contains(PromptAssembler.INSTRUCTIONS_INTRO))

        val unchanged = planNow(
            previousInstructions = Row(5, row.text, PromptAssembler.FORM_INSTRUCTIONS),
            injections = listOf(first),
        )
        assertEquals(emptyList<Write>(), unchanged)
    }
}
