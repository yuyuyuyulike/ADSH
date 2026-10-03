package com.adsh.app.core.agent

import com.adsh.app.core.agent.ContextLedger.Row
import com.adsh.app.core.agent.ContextLedger.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时上下文的**落库计划**（第八十三轮）。
 *
 * 这一层是「信息动态注入」的判据所在，错了的表现很难在真机上复现：
 *  - 该追加却就地改 → 前缀缓存整段作废（系统提示词没变，历史却从中间被改写）；
 *  - 该就地改却追加 → 界面上同一条注入越堆越多；
 *  - 漏一条通知 → 模型拿着旧策略继续干活。
 *
 * 第八十八轮（用户点名）：所有快照段拼成**一条**消息（dsh 的 joinContextSections），
 * 抬头 "Current runtime context. …" 只出现一次 —— 下面第一条测试就钉着这句话。
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
        text = "## Android / Termux environment",
        delivery = PromptAssembler.Delivery.MESSAGE,
    )
    private val plan = PromptAssembler.Injection(
        label = PromptAssembler.LABEL_PLAN_POLICY,
        form = PromptAssembler.FORM_SNAPSHOT,
        text = PromptAssembler.PLAN_SECTION,
        delivery = PromptAssembler.Delivery.MESSAGE,
    )

    private val snapshotLabel = PromptAssembler.LABEL_SANDBOX_POLICY + "、" + PromptAssembler.LABEL_ENV
    private val planSnapshotLabel = snapshotLabel + "、" + PromptAssembler.LABEL_PLAN_POLICY

    /** 上一条快照行（name = 组合标签，正文 = 拼好的那一条消息） */
    private fun snapshotRow(id: Long, mode: String, withPlan: Boolean = false, notes: List<String> = emptyList()): Row {
        val sections = listOf(PromptAssembler.sandboxPolicyText(mode), env.text)
        return Row(
            id = id,
            text = PromptAssembler.snapshotText(
                section = (if (withPlan) sections + plan.text else sections).joinToString("\n\n"),
                notes = notes,
            ),
            form = PromptAssembler.FORM_SNAPSHOT,
        )
    }

    private fun latestOf(rows: Map<String, Row>): (String) -> Row? = { rows[it] }

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

    /**
     * 会话第一次发送：**一条**快照，抬头只出现一次，各段按顺序都在里面
     * （第八十八轮用户点名：以前 sandbox:policy 与 env 各一条，同一句抬头重复两遍）。
     */
    @Test
    fun firstSendWritesOneCombinedSnapshot() {
        val writes = ContextLedger.plan(latestOf(emptyMap()), listOf(policy("workspace-write"), env))
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(snapshotLabel, append.label)
        assertEquals(PromptAssembler.FORM_SNAPSHOT, append.form)
        assertEquals(PromptAssembler.RUNTIME_CONTEXT_PREAMBLE, append.text.substringBefore("\n\n"))
        assertEquals("抬头只出现一次", 1, count(append.text, PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
        assertTrue(append.text.contains("Current DSH file policy: workspace-write"))
        assertTrue(append.text.contains("## Android / Termux environment"))
        assertTrue("策略段在环境段之前", append.text.indexOf("Current DSH") < append.text.indexOf("## Android"))
    }

    /** 第二次发送内容没变：一个字都不写（不然每发一条消息就多一条注入） */
    @Test
    fun unchangedSnapshotsWriteNothing() {
        val rows = mapOf(snapshotLabel to snapshotRow(1, "workspace-write"))
        assertEquals(emptyList<Write>(), ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env)))
    }

    /**
     * 权限预设换了：**只写一条** —— dsh 那句「从 X 换成 Y」并进新快照的抬头下
     * （第八十四轮，用户点名：以前「通知一行 + 快照一行」说的是同一件事）。
     */
    @Test
    fun policySwitchWritesOneMergedSnapshot() {
        val rows = mapOf(snapshotLabel to snapshotRow(7, "workspace-write"))
        val writes = ContextLedger.plan(latestOf(rows), listOf(policy("danger-full-access"), env))
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(snapshotLabel, append.label)
        assertTrue(
            "变化说明并进快照正文",
            append.text.contains(
                "The DSH file policy changed from \"workspace-write\" to \"danger-full-access\" (changed by the user).",
            ),
        )
        assertTrue(append.text.contains("Current DSH file policy: danger-full-access"))
        assertTrue("说明在抬头之后、正文之前", append.text.indexOf("changed from") < append.text.indexOf("Current DSH"))
        assertEquals("抬头仍然只有一次", 1, count(append.text, PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
    }

    /**
     * 带说明的那一条写完之后，下一轮内容没变就**一个字都不写**：说明只解释这一次变化，
     * 它不是内容；拿整串去比的话，带说明的那一条每轮都会被再写一遍（老代码就有这个毛病）。
     */
    @Test
    fun notedSnapshotIsNotRewrittenNextRound() {
        val rows = mapOf(
            snapshotLabel to snapshotRow(
                id = 8,
                mode = "danger-full-access",
                notes = listOf(ContextLedger.policySwitchNotice("workspace-write", "danger-full-access")),
            ),
        )
        assertEquals(
            emptyList<Write>(),
            ContextLedger.plan(latestOf(rows), listOf(policy("danger-full-access"), env)),
        )
    }

    /** 展示行（指令文件链 / 自定义后缀）内容变了就地换，不往时间线上堆 */
    @Test
    fun displayRowsAreReplacedInPlace() {
        val injection = PromptAssembler.Injection(
            label = "system-prompt-suffix",
            form = PromptAssembler.FORM_NOTICE,
            text = "新的后缀",
            delivery = PromptAssembler.Delivery.DISPLAY,
        )
        val writes = ContextLedger.plan(
            latestOf(mapOf("system-prompt-suffix" to Row(42, "旧的后缀", "notice"))),
            listOf(injection),
        )
        assertEquals(listOf(Write.Replace(42, "新的后缀")), writes)
    }

    /** 计划模式打开：快照多一段 plan（标签也跟着变成三段），没有叙述 */
    @Test
    fun enteringPlanModeReappendsTheSnapshotWithThePlanSection() {
        val rows = mapOf(snapshotLabel to snapshotRow(1, "workspace-write"))
        val writes = ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env, plan))
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(planSnapshotLabel, append.label)
        assertTrue(append.text.contains("You are in plan mode."))
        assertFalse("进入计划模式不需要叙述", append.text.contains(PromptAssembler.PLAN_OFF_NARRATION))
        assertEquals(1, count(append.text, PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
    }

    /** 计划模式开着、内容没变：不写（不然每发一条消息都追加一条快照） */
    @Test
    fun planModeSnapshotIsWrittenOnce() {
        val rows = mapOf(planSnapshotLabel to snapshotRow(3, "workspace-write", withPlan = true))
        assertEquals(
            emptyList<Write>(),
            ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env, plan)),
        )
    }

    /**
     * 计划模式关掉：补一条 dsh 的叙述（模型据此知道计划模式结束了），
     * 且**并进同一条快照**的抬头下 —— 不再单发一行 plan:policy。
     */
    @Test
    fun leavingPlanModeNarratesInTheSameSnapshot() {
        val rows = mapOf(planSnapshotLabel to snapshotRow(3, "workspace-write", withPlan = true))
        val writes = ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env))
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(snapshotLabel, append.label)
        assertTrue(append.text.contains(PromptAssembler.PLAN_OFF_NARRATION))
        assertTrue("叙述在抬头之后、正文之前", append.text.indexOf(PromptAssembler.PLAN_OFF_NARRATION) < append.text.indexOf("Current DSH"))
        assertFalse(append.text.contains("You are in plan mode."))
    }

    /** 已经叙述过了：不再重复（否则每发一条消息都要说一遍「你退出了计划模式」） */
    @Test
    fun leavingPlanModeNarratesOnce() {
        val rows = mapOf(
            snapshotLabel to snapshotRow(
                id = 4,
                mode = "workspace-write",
                notes = listOf(PromptAssembler.PLAN_OFF_NARRATION),
            ),
        )
        assertEquals(emptyList<Write>(), ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env)))
    }

    /** 退出又开回来：上一条快照的形状是「没有 plan」，也要认出「现在开着」并重写一条 */
    @Test
    fun reenteringPlanModeReappendsTheSnapshot() {
        val rows = mapOf(
            snapshotLabel to snapshotRow(
                id = 4,
                mode = "workspace-write",
                notes = listOf(PromptAssembler.PLAN_OFF_NARRATION),
            ),
        )
        val writes = ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env, plan))
        assertEquals(1, writes.size)
        assertEquals(planSnapshotLabel, (writes[0] as Write.Append).label)
        assertTrue(writes[0].let { (it as Write.Append).text.contains("You are in plan mode.") })
    }

    /** 老会话（升级前写下的、label 还是逐段名的旧快照）：查不到组合标签 → 追加一条新的 */
    @Test
    fun legacyPerSectionRowsGetASupersedingAppend() {
        val rows = mapOf(
            PromptAssembler.LABEL_SANDBOX_POLICY to Row(9, "Current DSH file policy: workspace-write. ...", "snapshot"),
            PromptAssembler.LABEL_ENV to Row(10, "## Android / Termux environment", "snapshot"),
        )
        val writes = ContextLedger.plan(latestOf(rows), listOf(policy("workspace-write"), env))
        assertEquals(1, writes.size)
        val append = writes[0] as Write.Append
        assertEquals(snapshotLabel, append.label)
        assertTrue(append.text.startsWith(PromptAssembler.RUNTIME_CONTEXT_PREAMBLE))
        // 模式没变 → 快照正文里不出现「changed from」
        assertFalse(append.text.contains("changed from"))
    }

    /** snapshotLabels：两种组合都列出来（找上一条快照时计划模式开/关各查一次） */
    @Test
    fun snapshotLabelsCoverBothPlanModes() {
        assertEquals(
            listOf(snapshotLabel, planSnapshotLabel),
            ContextLedger.snapshotLabels(listOf(policy("read-only"), env)),
        )
        assertEquals(
            listOf(planSnapshotLabel, snapshotLabel),
            ContextLedger.snapshotLabels(listOf(policy("read-only"), env, plan)),
        )
        assertEquals(emptyList<String>(), ContextLedger.snapshotLabels(emptyList()))
    }
}
