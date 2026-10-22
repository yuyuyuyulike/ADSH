package com.adsh.app.core.agent

/**
 * 运行时上下文的**落库计划**（纯函数，桌面单测直接打这张表）。
 *
 * 把「会变的事实」从系统提示词里搬出来之后，`recordContext` 每次发送前要做一堆
 * 「写哪一条、追加还是就地换」的判断。这些判断最容易错、也最难在真机上复现
 * （错了的表现是「模型看到的策略是旧的」或者「界面上同一条注入堆了一屏」），所以单独测。
 *
 * 规则（逐条对齐 dsh）：
 *  - **快照段**（[PromptAssembler.Delivery.MESSAGE]）：所有段拼成**一条**消息（dsh 的
 *    `joinContextSections`：前言一次 + 各段空行相连），与**上一条快照**的正文不同才**追加**
 *    一条新的。上一条快照按 **form 查**（dsh 的 `RuntimeContextProjection.retained` 就是
 *    「最近一条 runtime-context 快照」，与本次有哪些段无关）；比较的是整串文本 ——
 *    快照里没有任何说明 / 叙述，所以整串相等就是内容没变。旧行留在历史里，由前言声明被取代。
 *  - **指令文件链**（[PromptAssembler.Delivery.INSTRUCTIONS]）：自己一条 user 消息
 *    （dsh 的 agent-instructions），内容变了**追加**一条新的；身份按 form 查（换工作区后路径全变）。
 *  - **展示行**（[PromptAssembler.Delivery.DISPLAY]）：正文已经在系统提示词里，就地换掉那一行。
 *
 * **不在本表里**：计划模式退出的叙述。dsh 把它作为独立的 user 消息注入
 * （source = plan-mode、form = notice），ADSH 由 [AgentLoop.recordContext] 直接写一条
 * [com.adsh.app.core.data.ConversationRepository.PLAN_MODE_NARRATION] 行 —— 不进快照。
 * 策略切换**没有**叙述（dsh 的沙箱策略只有「当前策略」正文，变化 = 追加一条新快照）。
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

    /**
     * 算出这次要写哪些行。
     *
     * @param previousSnapshot 会话里最近一条快照行（按 form = snapshot 查；没有就是 null）
     * @param previousInstructions 最近一条指令文件链基线（按 form = instructions 查）
     * @param previousDisplay 展示行 name → 会话里最新一行（目前只有用户自定义后缀）
     * @param injections 本次装配出的运行时上下文（顺序即写入顺序）
     */
    fun plan(
        previousSnapshot: Row?,
        previousInstructions: Row?,
        previousDisplay: Map<String, Row>,
        injections: List<PromptAssembler.Injection>,
    ): List<Write> {
        val writes = ArrayList<Write>()

        // 1) 快照：所有段拼成一条消息（dsh 的 joinContextSections），正文与上一条不同才追加。
        val snapshots = injections.filter { it.delivery == PromptAssembler.Delivery.MESSAGE }
        if (snapshots.isNotEmpty()) {
            val text = PromptAssembler.snapshotText(snapshots.joinToString("\n\n") { it.text })
            if (previousSnapshot?.text != text) {
                // 界面上的动态注入名只用「环境细则」：计划模式开着时在后面加 "·plan"。
                // sandbox:policy 仍是正文里的一段，但不进这个名字（用户口径）。
                val planOn = snapshots.any { it.label == PromptAssembler.LABEL_PLAN_POLICY }
                writes += Write.Append(
                    PromptAssembler.LABEL_ENV + if (planOn) "·plan" else "",
                    PromptAssembler.FORM_SNAPSHOT,
                    text,
                )
            }
        }

        // 2) 指令文件链：**自己一条 user 消息**（dsh 的 agent-instructions —— 整份基线包在
        //    <system-reminder> 里，source.kind = agent-instructions）。身份按 **form** 查、不按
        //    label：换工作区之后指令文件的路径整串都变了，按 label 永远找不到上一条基线，而
        //    「有没有上一条」决定的正是抬头那句「取代之前所有基线」。内容一个字没变就什么都不写。
        injections.filter { it.delivery == PromptAssembler.Delivery.INSTRUCTIONS }.forEach { injection ->
            val unchanged = PromptAssembler.sameInstructions(previousInstructions?.text, injection.text)
            // 链为空（工作区里没有 AGENTS.md）：没有上一条基线时一个字都不写；有的话要写一条
            // 「取代之前那份基线、当前没有任何指令」—— 否则模型会继续照着旧工作区的 AGENTS.md 干。
            if (!unchanged && (injection.text.isNotEmpty() || previousInstructions != null)) {
                writes += Write.Append(
                    injection.label,
                    injection.form,
                    PromptAssembler.instructionsText(injection.text, replacing = previousInstructions != null),
                )
            }
        }

        // 3) 展示行：正文已经在系统提示词里，内容变了就地换（不往时间线上堆）
        injections.filter { it.delivery == PromptAssembler.Delivery.DISPLAY }.forEach { injection ->
            val previous = previousDisplay[injection.label]
            when {
                previous == null -> writes += Write.Append(injection.label, injection.form, injection.text)
                previous.text == injection.text -> Unit
                else -> writes += Write.Replace(previous.id, injection.text)
            }
        }
        return writes
    }
}
