package com.adsh.app.core.agent

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.Reasoning
import com.adsh.app.core.data.TurnSettings
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.ProviderConfig
import com.adsh.app.core.tools.RunCodeTool
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.seam.exec.filePolicyOf
import kotlinx.serialization.json.Json

/**
 * 一轮对话的**请求装配**（dsh 的 system-prompt assemble + deriveMessages）：
 *  - [recordContext]：在用户消息之前写系统提示词 / 运行时快照 / 指令基线（会话节点）；
 *  - [assembleRequest]：把库里的行装配成 wire 请求（历史 + 思考档位 + run_code schema）；
 *  - [assemblePrompt]：系统提示词的唯一装配点。
 *
 * 工作区是**每次 assemble 的输入**：`send` 开头用本轮工具世界的工作区覆盖它，
 * [recordContext] 用自己的参数覆盖它 —— 不能读上一次 send() 留下的字段（见 recordContext 的注释）。
 */
internal class TurnContext(
    private val store: TurnStore,
    private val settings: TurnSettings,
    json: Json,
) {

    /** 本轮装配用的工作区；由 [bindWorkspace] / [recordContext] 设置。 */
    var workspace: Workspace? = null
        private set

    /**
     * 请求历史的装配（库里的一行 → wire 上的一条消息）。协议约束与各分支口径见 [RequestMessages]；
     * 这里只把「系统提示词的稳定段」接进去 —— 策略 / 计划模式走运行时上下文注入（[recordContext]）。
     */
    private val requestMessages = RequestMessages(
        store = store,
        json = json,
        systemPrompt = { assemblePrompt(planMode = false).system },
    )

    /** 一轮开始时把工作区定下来（`send` 的第一步）。 */
    fun bindWorkspace(workspace: Workspace?) {
        this.workspace = workspace
    }

    /**
     * 在用户消息之前，把「系统提示词 / 上下文注入」按 dsh 的会话节点写进历史。
     *
     * **两条载体，别混**（按用户要求重做，见 [PromptAssembler.Delivery]）：
     *  - 系统提示词（role = sysprompt）只放**稳定**内容：身份 / PTC 规则 / 跨工具规则 / SDK。
     *    落库规则对齐 dsh 的 SystemPromptProjection：
     *    首次对话写一条；压缩之后再对话时重新注入一条（dsh 的 startsSeries）；其余情况只
     *    **就地替换**已有的那一行 —— 系统提示词是「一个节点」，不是一条流水。
     *  - 运行时上下文（role = context，form = snapshot）放**会变的事实**：当前文件策略、
     *    Android/Termux 环境、计划模式。所有段拼成**一条**快照消息（用户点名：
     *    每段一条、同一句抬头重复两三遍是错的；dsh 的 joinContextSections 就是拼成一条），
     *    内容一变就**追加一条新的快照**（dsh 的 RuntimeContextProjection：`retained?.text ===`
     *    snapshot 才不写，否则在保留历史之后追加；上一条按 **form** 找，与本轮有哪些段无关），
     *    旧快照留在历史里、由抬头那句 "This snapshot supersedes earlier runtime-context
     *    snapshots." 声明被取代 —— 追加而不是就地改写，前缀缓存才不会因为一次权限切换整段作废。
     *  - 指令文件链（role = context，form = instructions）**自己一条 user 消息**（dsh 的
     *    agent-instructions）：只有在内容变了时才**追加**一条 —— 抬头那句声明它取代了之前所有
     *    基线，旧行留在历史里。它从系统提示词搬到运行时注入。
     *  - 用户自定义后缀是 DISPLAY 行：正文已经在系统提示词里，这里只登记给界面看，
     *    内容变了就地替换（不往时间线上堆）。
     *
     * 计划模式的开关也走同一条路：开着时快照里多一段 `plan:policy`（正文就是 dsh 的 plan 段），
     * 关掉时那一段消失。**退出叙述单独一条 user 消息**（dsh plan-mode 的 narration：
     * source = plan-mode、form = notice，正文 [PromptAssembler.PLAN_OFF_NARRATION]）——
     * 快照只放各 section 正文，叙述不并进去。
     *
     * 这些节点装配请求时按各自的交付方式处理（见 [RequestMessages]）。
     */
    suspend fun recordContext(
        conversationId: Long,
        workspace: Workspace,
        planMode: Boolean = false,
    ) {
        // **先把这一轮真正要用的工作区摆正**：不能读上一次 send() 留下的字段 ——
        // 否则「进程起来之后的第一条消息」装配出来是「没有绑定工作区」那一形态：
        // 系统提示词里少了 cwd、env 段少了工作区那几条（真机实测：第一条请求的 env 快照
        // 只有 prefix/scratch/tmp 三句，第二条才补上工作区那三句）。
        this.workspace = workspace
        val parts = assemblePrompt(planMode)
        val previous = store.lastNamed(conversationId, "sysprompt")
        if (previous == null) {
            store.addMessage(conversationId, "sysprompt", parts.system)
        } else {
            // 上一次注入之后又被压缩过 → 重新注入一条（dsh 的「压缩后再对话也会注入」）
            val marker = store.lastNamed(conversationId, "user", ConversationRepository.COMPACT_MARKER)
            if (marker != null && marker.id > previous.id) {
                store.addMessage(conversationId, "sysprompt", parts.system, name = "update")
            } else if (previous.content != parts.system) {
                store.setMessageContent(previous.id, parts.system)
            }
        }

        // 计划模式退出：dsh 的 plan-mode narration 是一条独立 user 消息（source = plan-mode、
        // form = notice），只有「上一条快照里还有 plan 段、这一轮没有了」时才写 —— 写完紧接着
        // 追加的新快照不再带 plan 段，所以下一次发送不会再写一遍。
        val previousSnapshot = store.lastContextOfForm(conversationId, PromptAssembler.FORM_SNAPSHOT)
        if (!planMode && previousSnapshot?.content?.contains(PromptAssembler.PLAN_MODE_MARKER) == true) {
            store.addMessage(
                conversationId = conversationId,
                role = "user",
                content = PromptAssembler.PLAN_OFF_NARRATION,
                name = ConversationRepository.PLAN_MODE_NARRATION,
            )
        }

        // 该写哪些行交给 [ContextLedger] 算（纯函数，桌面单测直接打那张表）：
        // 快照按 form 找上一条（dsh 的 retained snapshot，与本轮有哪些段无关）、指令基线按 form 找，
        // 展示行按 name 找。plan 内部不做 IO —— 这几行先在这里读好。
        // 展示行（用户自定义后缀）的身份是 name；先把它们的最新一行读出来（一次决策只读一遍状态）
        val previousDisplay = parts.injections
            .filter { it.delivery == PromptAssembler.Delivery.DISPLAY }
            .mapNotNull { injection ->
                store.lastNamed(conversationId, "context", injection.label)?.let { row ->
                    injection.label to ContextLedger.Row(row.id, row.content, row.subCallsJson)
                }
            }
            .toMap()
        val writes = ContextLedger.plan(
            previousSnapshot = previousSnapshot?.let {
                ContextLedger.Row(it.id, it.content, it.subCallsJson)
            },
            previousInstructions = store.lastContextOfForm(conversationId, PromptAssembler.FORM_INSTRUCTIONS)
                ?.let { ContextLedger.Row(it.id, it.content, it.subCallsJson) },
            previousDisplay = previousDisplay,
            injections = parts.injections,
        )
        writes.forEach { write ->
            when (write) {
                is ContextLedger.Write.Append -> store.addMessage(
                    conversationId = conversationId,
                    role = "context",
                    content = write.text,
                    name = write.label,
                    subCallsJson = write.form,
                )
                is ContextLedger.Write.Replace -> store.setMessageContent(write.id, write.text)
            }
        }
    }

    /**
     * 装配这一步的请求：历史（[RequestMessages.build]，含系统提示词与上下文注入节点）+
     * 思考档位（[com.adsh.app.core.data.Reasoning.wireEffort] 按模型就近取档 → [thinkingWire] 出字段）。
     *
     * `ptcEnabled` = 有工具上下文（PTC 模式），决定 wire 上带不带 run_code 的 schema。
     */
    suspend fun assembleRequest(
        conversationId: Long,
        config: ProviderConfig,
        ptcEnabled: Boolean,
    ): ChatRequest {
        val messages = requestMessages.build(
            conversationId = conversationId,
            // 能不能收图由路由决定（dsh 的 catalog inputModalities）
            imageCapable = settings.modelAcceptsImages(config.model),
            // 思考模式的历史校验是 DeepSeek 特有的（reasoning_content 必须回传），所以
            // 「空 reasoning 也补一个空串」只对 DeepSeek 路由生效（判定见 [isDeepSeekRoute]）
            echoReasoning = isDeepSeekRoute(config.providerId, config.baseUrl, config.model),
        )
        // dsh 的 resolveThinking：off → thinking=disabled；low/high/max →
        // thinking=enabled + reasoning_effort；未选 → 两个字段都不发，走提供方默认。
        // 等级是**逐模型**的：换提供方/换模型之后存着的等级可能不被支持，
        // 这里按 pi-ai 的 clamp 就近取一档（不支持思考的模型一个字段都不发）。
        val effort = Reasoning.wireEffort(
            providerId = config.providerId,
            modelId = config.model,
            stored = settings.reasoningEffort,
        )
        // 等级 → 真正发出去的字段：[thinkingWire]（纯函数，表在 TurnDecisionsTest）
        val wire = thinkingWire(effort)
        return ChatRequest(
            model = config.model,
            messages = messages,
            stream = true,
            reasoningEffort = wire.reasoningEffort,
            thinking = wire.thinking,
            tools = if (ptcEnabled) listOf(RunCodeTool.wireSchema) else null,
        )
    }

    /**
     * 用装配器构建系统提示词。
     *
     * 工作区取自 [workspace]（不是调用方传的路径）：真正决定 cwd / 指令文件链 / env 形态的是这个字段。
     */
    private fun assemblePrompt(
        planMode: Boolean = false,
    ): PromptAssembler.Parts = PromptAssembler.buildParts(
        workspace = workspace,
        extraSuffix = settings.systemPromptSuffix,
        filePolicy = filePolicyOf(settings.permission),
        planMode = planMode,
        // `tools.bash` 的超时口径是**设置里的当前值**，所以进快照而不是静态段
        bashTimeoutMs = settings.bashTimeoutMs,
        bashMaxTimeoutMs = settings.bashMaxTimeoutMs,
    )
}
