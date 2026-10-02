package com.adsh.app.core.agent

/**
 * 运行时上下文的**落库计划**（纯函数，桌面单测直接打这张表）。
 *
 * 第八十三轮把「会变的事实」从系统提示词里搬出来之后，`recordContext` 每次发送前要做一堆
 * 「写哪一条、追加还是就地换」的判断。这些判断是这个功能里最容易错、又最难在真机上复现的部分
 * （错了的表现是「模型看到的策略是旧的」或者「界面上同一条注入堆了一屏」），所以从
 * [AgentLoop.recordContext] 里摘出来单独测。
 *
 * 规则（逐条对齐 dsh）：
 *  - **快照段**（[PromptAssembler.Delivery.MESSAGE]）：所有段拼成**一条**消息（dsh 的
 *    `joinContextSections`：抬头 + 各段空行相连，抬头只出现一次 —— 第八十八轮用户点名，
 *    以前每段一条、同一句抬头重复两三遍），内容与**上一条快照**不同才**追加**一条新的，
 *    旧行留在历史里由抬头声明被取代（dsh 的 RuntimeContextProjection：append，
 *    前缀缓存才不会被一次权限切换整段作废）；
 *  - **指令文件链**（[PromptAssembler.Delivery.INSTRUCTIONS]）：自己一条 user 消息
 *    （dsh 的 agent-instructions），内容变了**追加**一条新的 —— 抬头那句声明它取代了之前所有基线；
 *    身份按 form 查（换工作区后路径全变）；
 *  - **展示行**（[PromptAssembler.Delivery.DISPLAY]）：正文已经在系统提示词里，就地换掉那一行；
 *  - **策略切换**：新快照抬头下先写**一条**说明 —— dsh 那句
 *    "The DSH file policy changed from \"x\" to \"y\" (changed by the user)."
 *    （第八十四轮，用户点名：以前「通知一行 + 快照一行」说的是同一件事）；
 *  - **计划模式退出**：上一条快照里写着「在计划模式里」、这一轮没有了 → 同一条新快照的抬头下
 *    补一句 dsh 的 narration（[PromptAssembler.PLAN_OFF_NARRATION]）。
 */
internal object ContextLedger {

    /** 现有一行（只需要这三个字段） */
    data class Row(val id: Long, val text: String, val form: String?)

    /** 要执行的一次写入 */
    sealed interface Write {
        /** 追加一条上下文行（新快照 / 首次注入） */
        data class Append(val label: String, val form: String, val text: String) : Write

        /** 就地替换某一行（展示行） */
        data class Replace(val id: Long, val text: String) : Write
    }

    /** dsh 的 user-approval setPolicy 文案（逐字，只把 approval 换成 file） */
    fun policySwitchNotice(from: String, to: String): String =
        "The DSH file policy changed from \"" + from + "\" to \"" + to + "\" (changed by the user)."

    /** 从 sandbox:policy 的正文里取模式名（我们自己装配的稳定文本，只用来判断换没换） */
    fun policyModeOf(text: String?): String? =
        Regex("Current DSH file policy: ([a-z-]+)\\.").find(text ?: "")?.groupValues?.get(1)

    /**
     * 快照消息的行标签（dsh 快照消息的 sections 归因；ADSH 把它落成 name，界面直接显示）：
     * 这一轮所有快照段（[PromptAssembler.Delivery.MESSAGE]）的 label 用「、」连起来，
     * 与指令文件链那一行的写法一致。
     *
     * **返回两种组合**：本轮这一种，以及「计划模式相反」的那一种。计划模式一开一关标签就换一个，
     * 找「上一条快照」时必须两种都看一眼 —— 换模式那时上一条恰恰是另一种组合。
     */
    fun snapshotLabels(injections: List<PromptAssembler.Injection>): List<String> {
        val labels = injections.filter { it.delivery == PromptAssembler.Delivery.MESSAGE }.map { it.label }
        if (labels.isEmpty()) return emptyList()
        val toggled = if (labels.contains(PromptAssembler.LABEL_PLAN_POLICY)) {
            labels - PromptAssembler.LABEL_PLAN_POLICY
        } else {
            labels + PromptAssembler.LABEL_PLAN_POLICY
        }
        return listOf(labels.joinToString("、"), toggled.joinToString("、"))
    }

    /**
     * 算出这次要写哪些行。
     *
     * @param latest 取某个 label 的**最新**一行（没有就是 null）；只读，调用期间不变
     * @param injections 本次装配出的运行时上下文（顺序即写入顺序）
     */
    fun plan(latest: (String) -> Row?, injections: List<PromptAssembler.Injection>): List<Write> {
        val writes = ArrayList<Write>()

        // 1) 快照：所有段拼成一条消息（dsh 的 joinContextSections）。
        //    「上一条快照」= 本轮标签与「计划模式相反」那个标签里**最新的一条**（id 最大）。
        //    不这么找的话，计划模式一开一关时会拿旧形状的行去比，模型就收不到「又开回来了」。
        val snapshots = injections.filter { it.delivery == PromptAssembler.Delivery.MESSAGE }
        if (snapshots.isNotEmpty()) {
            val labels = snapshotLabels(injections)
            val previous = labels.mapNotNull { latest(it) }.maxByOrNull { it.id }
            val currentMode = policyModeOf(snapshots.firstOrNull { it.label == PromptAssembler.LABEL_SANDBOX_POLICY }?.text)
            val previousMode = policyModeOf(previous?.text)
            val notes = ArrayList<String>()
            if (currentMode != null && previousMode != null && currentMode != previousMode) {
                notes += policySwitchNotice(previousMode, currentMode)
            }
            val planOn = snapshots.any { it.label == PromptAssembler.LABEL_PLAN_POLICY }
            if (!planOn && previous?.text?.contains(PromptAssembler.PLAN_MODE_MARKER) == true) {
                notes += PromptAssembler.PLAN_OFF_NARRATION
            }
            val body = snapshots.joinToString("\n\n") { it.text }
            // 上一条快照的**各段正文**是它的结尾（抬头与说明都排在前面），所以「结尾相同」就是
            // 「各段一个字没变」。这一条不能写成整串相等：说明只解释这一次变化，带说明的那一条
            // 与下一轮算出来的（不带说明的）整串必然不同，否则每次带说明的快照都会被再写一遍。
            val changed = previous == null || !previous.text.endsWith(body)
            if (changed || notes.isNotEmpty()) {
                writes += Write.Append(
                    labels[0],
                    PromptAssembler.FORM_SNAPSHOT,
                    PromptAssembler.snapshotText(body, notes),
                )
            }
        }

        // 2) 指令文件链：**自己一条 user 消息**（dsh 的 agent-instructions —— 整份基线包在
        //    <system-reminder> 里，source.kind = agent-instructions）。身份按 **form** 查、不按
        //    label：换工作区之后指令文件的路径整串都变了，按 label 永远找不到上一条基线，而
        //    「有没有上一条」决定的正是抬头那句「取代之前所有基线」。内容一个字没变就什么都不写。
        injections.filter { it.delivery == PromptAssembler.Delivery.INSTRUCTIONS }.forEach { injection ->
            val previous = latest(PromptAssembler.FORM_INSTRUCTIONS)
            val unchanged = PromptAssembler.sameInstructions(previous?.text, injection.text)
            // 链为空（工作区里没有 AGENTS.md）：没有上一条基线时一个字都不写；有的话要写一条
            // 「取代之前那份基线、当前没有任何指令」—— 否则模型会继续照着旧工作区的 AGENTS.md 干。
            if (!unchanged && (injection.text.isNotEmpty() || previous != null)) {
                writes += Write.Append(
                    injection.label,
                    injection.form,
                    PromptAssembler.instructionsText(injection.text, replacing = previous != null),
                )
            }
        }

        // 3) 展示行：正文已经在系统提示词里，内容变了就地换（不往时间线上堆）
        injections.filter { it.delivery == PromptAssembler.Delivery.DISPLAY }.forEach { injection ->
            val previous = latest(injection.label)
            when {
                previous == null -> writes += Write.Append(injection.label, injection.form, injection.text)
                previous.text == injection.text -> Unit
                else -> writes += Write.Replace(previous.id, injection.text)
            }
        }
        return writes
    }
}
