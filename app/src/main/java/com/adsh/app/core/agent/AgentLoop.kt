package com.adsh.app.core.agent

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ChatMessage
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.LlmClient
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.ToolCallFunction
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.core.tools.RunCodeTool
import com.adsh.app.core.tools.ToolContext
import com.adsh.app.core.tools.ToolRegistry
import com.adsh.app.core.tools.ToolResult
import com.adsh.app.core.tools.SubCallTrace
import com.adsh.app.core.tools.ToolStepCounter
import com.adsh.app.core.ptc.SubCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.adsh.app.core.llm.textContent

/**
 * Agent 主循环（PTC 模式）：
 *   模型只看到 run_code 一个工具 → 产出程序 → 本地执行（程序内可组合调用其它工具）→
 *   把结果作为 tool 消息回填 → 继续下一轮，直到模型不再调用工具或达到轮次上限。
 *
 * 对齐 dsh：
 *  - ptc 模式下 wire 上的 tools 只有 run_code（dsh-tools 的 wireSchemas），
 *    其余工具只在系统提示词的 tools:sdk 段里声明；
 *  - 嵌套子调用只进日志与 UI，不回灌模型上下文（模型只看到 run_code 的最终返回）；
 *  - 打断时保留已生成的内容并标记 interrupted（dsh 的 message.stopped「已停止」）。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val repository: ConversationRepository,
    private val settings: SettingsStore,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    private var currentWorkspace: com.adsh.app.core.workspace.Workspace? = null

    /**
     * 在用户消息之前，把「系统提示词 / 上下文注入」按 dsh 的会话节点写进历史。
     *
     * **两条载体，别混**（第八十三轮按用户要求重做，见 [PromptAssembler.Delivery]）：
     *  - 系统提示词（role = sysprompt）只放**稳定**内容：身份 / PTC 规则 / 跨工具规则 / SDK。
     *    落库规则对齐 dsh 的 SystemPromptProjection：
     *    首次对话写一条；压缩之后再对话时重新注入一条（dsh 的 startsSeries）；其余情况只
     *    **就地替换**原来那一行 —— 系统提示词是「一个节点」，不是一条流水。
     *  - 运行时上下文（role = context，form = snapshot）放**会变的事实**：当前文件策略、
     *    Android/Termux 环境、计划模式。所有段拼成**一条**快照消息（第八十八轮用户点名：
     *    以前每段一条、同一句抬头重复两三遍；dsh 的 joinContextSections 就是拼成一条），
     *    内容一变就**追加一条新的快照**（dsh 的 RuntimeContextProjection：`retained?.text ===
     *    snapshot` 才不写，否则在保留历史之后追加），旧快照留在历史里、由抬头那句
     *    "This snapshot supersedes earlier runtime-context snapshots." 声明被取代 —— 追加而不是就地
     *    改写，前缀缓存才不会因为一次权限切换整段作废。
     *  - 指令文件链（role = context，form = instructions）**自己一条 user 消息**（dsh 的
     *    agent-instructions）：只有在内容变了时才**追加**一条 —— 抬头那句声明它取代了之前所有
     *    基线，旧行留在历史里。第 117 轮从系统提示词搬出来（用户点名改运行时注入）。
     *  - 用户自定义后缀是 DISPLAY 行：正文已经在系统提示词里，这里只登记给界面看，
     *    内容变了就地替换（不往时间线上堆）。
     *
     * 计划模式的开关也走同一条路：开着时快照里多一段 `plan:policy`（正文就是 dsh 的 plan 段），
     * 关掉时那一段消失、快照抬头下多一句 dsh 的叙述（[PromptAssembler.PLAN_OFF_NARRATION]）——
     * 系统提示词里不再有 plan 段之后，这句叙述是模型知道「计划模式已经结束」的明确来源。
     *
     * 这些节点装配请求时按各自的交付方式处理（见 [buildMessages]）。
     */
    suspend fun recordContext(
        conversationId: Long,
        workspace: com.adsh.app.core.workspace.Workspace,
        planMode: Boolean = false,
    ) {
        // **先把这一轮真正要用的工作区摆正**：以前这里读的是上一次 send() 留下的字段，
        // 于是「进程起来之后的第一条消息」装配出来是「没有绑定工作区」那一形态 ——
        // 系统提示词里少了 cwd、env 段少了工作区那几条（真机实测：第一条请求的 env 快照
        // 只有 prefix/scratch/tmp 三句，第二条才补上工作区那三句）。
        currentWorkspace = workspace
        val parts = assemblePrompt(planMode, settings.lastWorkspacePath)
        val previous = repository.lastNamed(conversationId, "sysprompt")
        if (previous == null) {
            repository.addMessage(conversationId, "sysprompt", parts.system)
        } else {
            // 上一次注入之后又被压缩过 → 重新注入一条（dsh 的「压缩后再对话也会注入」）
            val marker = repository.lastNamed(conversationId, "user", ConversationRepository.COMPACT_MARKER)
            if (marker != null && marker.id > previous.id) {
                repository.addMessage(conversationId, "sysprompt", parts.system, name = "update")
            } else if (previous.content != parts.system) {
                repository.setMessageContent(previous.id, parts.system)
            }
        }

        // 该写哪些行交给 [ContextLedger] 算（纯函数，桌面单测直接打那张表）：
        // 新快照追加 / 展示行就地替换 / 策略切换与计划模式退出的说明。
        // 先把可能要看的那几行读出来：一次决策只读一遍状态（plan 内部不做 IO）。
        // 快照行的 name 是「各段标签用、连起来」（[ContextLedger.snapshotLabels]），所以要按
        // 两种组合（计划模式开/关）各查一次 —— 只查逐段的 label 会永远查不到。
        // 指令文件链那一行的身份键是 **form**（换工作区后路径整串都变，按 label 查不到上一条基线）
        val labels = (parts.injections.map { it.label } + ContextLedger.snapshotLabels(parts.injections) +
            PromptAssembler.FORM_INSTRUCTIONS).toSet()
        val rows = labels.associateWith { label ->
            val row = if (label == PromptAssembler.FORM_INSTRUCTIONS) {
                repository.lastContextOfForm(conversationId, label)
            } else {
                repository.lastNamed(conversationId, "context", label)
            }
            row?.let { ContextLedger.Row(it.id, it.content, it.subCallsJson) }
        }
        val writes = ContextLedger.plan(latest = { rows[it] }, injections = parts.injections)
        writes.forEach { write ->
            when (write) {
                is ContextLedger.Write.Append -> repository.addMessage(
                    conversationId = conversationId,
                    role = "context",
                    content = write.text,
                    name = write.label,
                    subCallsJson = write.form,
                )
                is ContextLedger.Write.Replace -> repository.setMessageContent(write.id, write.text)
            }
        }
    }

    /**
     * 跑一轮对话。**必须 `flowOn(IO)`**：这个 flow 里全是阻塞调用 —— LlmClient 的流式读取、
     * 工具执行的 `Process.waitFor()`（bash 最长可跑到超时）、工具的文件读写。
     * 收集方是 ChatViewModel 的 `viewModelScope.launch`（Main.immediate），
     * 不切线程的话每一次工具调用都会把主线程堵住 —— 表现就是「发出去消息之后界面卡死」。
     */
    fun send(
        conversationId: Long,
        userText: String,
        toolContext: ToolContext?,
        persistUser: Boolean = true,
        /** 待发附件（已导入工作区的只读副本）：与用户消息一起落库 */
        attachments: List<UserAttachment> = emptyList(),
    ): Flow<ChatEvent> = channelFlow {
        // 单序事件总线（dsh 的 Session.append + 同步广播，spec-log §1）：日志是这一轮的唯一真相，
        // 界面拿到的是它的**投影**；子调用回调从工具线程 append 进同一份日志，于是
        // 「回调比宿主登记这次调用更早到」这种竞速在结构上不存在（不需要任何归属状态去兜）。
        val bus = Channel<ChatEvent>(Channel.UNLIMITED)
        val log = com.adsh.app.core.session.SessionLog()
        // 日志 → 界面事件：**顺序就是 append 的顺序**（同步广播 + 通道 FIFO）
        log.observe { event -> bus.trySend(ChatEvent.Appended(event)) }
        val pump = launch { for (event in bus) send(event) }
        try {
            runTurnBody(conversationId, userText, toolContext, persistUser, attachments, log, bus)
        } finally {
            // 不变量自检（spec-log §7.2）：debug 包在真机上直接报「日志自己不自洽」，
            // 而不是让用户从界面上少一行去猜
            if (com.adsh.app.BuildConfig.DEBUG) {
                val bad = com.adsh.app.core.session.SessionLog.violations(log.events)
                if (bad.isNotEmpty()) {
                    android.util.Log.w("ADSH", "会话日志不变量违规：" + bad.joinToString("；"))
                }
            }
            bus.close()
            pump.join()
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /** [send] 的实际内容。拆出来只为了把事件发进 [bus]（见那里的注释）。 */
    private suspend fun runTurnBody(
        conversationId: Long,
        userText: String,
        toolContext: ToolContext?,
        persistUser: Boolean,
        attachments: List<UserAttachment>,
        log: com.adsh.app.core.session.SessionLog,
        bus: Channel<ChatEvent>,
    ) {
        currentWorkspace = toolContext?.workspace
        // 一轮开始：清掉上一轮没来得及收的文件改动留底（dsh 的 TurnRecorder 每轮一份）
        TurnChangeTracker.beginTurn()
        val config = settings.providerConfig()
        // 思考模式的历史校验是 DeepSeek 特有的（reasoning_content 必须回传），
        // 所以「空 reasoning 也补一个空串」只对 DeepSeek 路由生效
        val deepseekRoute = config.providerId == com.adsh.app.core.data.BuiltInProviders.DEEPSEEK_ID ||
            config.baseUrl.contains("deepseek") || config.model.startsWith("deepseek")
        val ptcEnabled = toolContext != null
        // 名字非空的 user 行是系统通知（权限切换等），不是用户发的轮次
        val conversationTurns = repository.messages(conversationId).count { it.role == "user" && it.name == null }
        val previousPath = settings.lastWorkspacePath
        val turnStartedAt = System.currentTimeMillis()
        if (persistUser) {
            repository.addMessage(
                conversationId = conversationId,
                role = "user",
                content = userText,
                attachmentsJson = encodeAttachments(attachments),
            )
        }
        // 本轮累计（dsh 的 turn tokenUsage / runMs）：轮尾「用量 / 用时」两个按钮展开的明细
        var turnPrompt = 0L
        var turnCompletion = 0L
        var turnCacheHit = 0L
        var turnReasoning = 0L
        var turnLlmMillis = 0L
        var turnTtft = 0L
        suspend fun persistTurnUsage() {
            // 用量写在**轮首**那一行（开启轮次的用户消息）：插话（STEERING）与权限切换通知
            // （SANDBOX_SWITCH）也是 role = user，但它们不是轮首 —— 写错了轮尾的用量按钮就空了
            val userId = repository.lastTurnOpener(conversationId)?.id ?: return
            val decodeSeconds = turnLlmMillis / 1000.0
            val usage = TurnUsage(
                provider = settings.providerRoute,
                model = config.model,
                uncachedInputTokens = turnPrompt.coerceAtLeast(0),
                cacheReadTokens = turnCacheHit,
                // DeepSeek 只有「命中 / 未命中」两个桶，没有单独的缓存写入计费
                cacheWriteTokens = 0,
                outputTokens = turnCompletion,
                reasoningTokens = turnReasoning,
                runMillis = (System.currentTimeMillis() - turnStartedAt).coerceAtLeast(0),
                ttftMillis = turnTtft,
                tps = if (decodeSeconds > 0) turnCompletion / decodeSeconds else 0.0,
            )
            runCatching { repository.setUsage(userId, json.encodeToString(TurnUsage.serializer(), usage)) }
            // 本轮的文件改动（dsh 的 workspace/changes）：同一处收口 —— 轮尾的「已编辑 N 个文件」
            // 卡片与「用量」按钮一样，都挂在**开启这一轮的那条用户消息**上
            val changes = TurnChangeTracker.finish(currentWorkspace?.shellRoot?.absolutePath)
            runCatching { repository.setTurnChanges(userId, encodeFileChanges(changes)) }
        }

        // 本轮已经写进库的部分（打断时要用它落一条「已停止」的消息）
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var openRoundPersisted = false
        val calls = LinkedHashMap<Int, CallAccumulator>()

        try {
            var round = 0
            // **没有轮数上限**：dsh 的 agent-loop 设置里只有 maxParallelToolCalls 一项
            // （dsh-agent-loop 的 AGENT_LOOP_SETTINGS_SCHEMA 只有一个字段），
            // 它靠模型自己停下（没有工具调用就 return）+ 用户随时打断。
            // ADSH 以前有一个可配的「单条消息往返轮数」上限，已按 dsh 去掉。
            //
            // 下面两条不是次数上限的替代品，而是**死循环判据**：同一组「工具 + 参数」
            // 重复 REPEAT_CALL_LIMIT 次、或单条消息的工具调用总数超过 MAX_TOOL_CALLS_PER_TURN
            // 时立刻停下 —— 这是「模型调用失误」而不是「任务很长」，两者的区别很重要。
            var toolCallsThisTurn = 0
            val callSignatures = HashMap<String, Int>()
            while (true) {
                // 打断后立刻停在这里，不要再发起下一轮（旧实现会继续跑完整轮，
                // 出现「退出重进后多出几条工具调用与思考」的现象）
                currentCoroutineContext().ensureActive()
                round++
                calls.clear()
                answer.setLength(0)
                reasoning.setLength(0)
                openRoundPersisted = true

                // dsh 的 preStep：**先认领收件箱**（这一步期间到达的插话 / 任务通知），再装配请求。
                // 认领 = 此刻才落库，于是它们在库里的位置就是 dsh 日志里的位置（上一步的工具结果之后、
                // 这一步的 assistant 之前）。一行只写一次，界面不会再看着它换位置。
                if (repository.claimInjected(conversationId).isNotEmpty()) bus.trySend(ChatEvent.InboxClaimed)

                val messages = buildMessages(
                    conversationId = conversationId,
                    // 基线替换用的「上一个工作区」在本轮内固定：否则第一次装配就把
                    // lastWorkspacePath 改掉，记录下来的系统提示词行与实际请求不一致
                    previousPath = previousPath,
                    // 能不能收图由路由决定（dsh 的 catalog inputModalities）
                    imageCapable = settings.modelAcceptsImages(config.model),
                    echoReasoning = deepseekRoute,
                )
                // dsh 的 resolveThinking：off → thinking=disabled；low/high/max →
                // thinking=enabled + reasoning_effort；未选 → 两个字段都不发，走提供方默认。
                // 等级是**逐模型**的：换提供方/换模型之后存着的等级可能不被支持，
                // 这里按 pi-ai 的 clamp 就近取一档（不支持思考的模型一个字段都不发）。
                val effort = com.adsh.app.core.data.Reasoning.wireEffort(
                    providerId = config.providerId,
                    modelId = config.model,
                    stored = settings.reasoningEffort,
                )
                val request = ChatRequest(
                    model = config.model,
                    messages = messages,
                    stream = true,
                    reasoningEffort = effort?.takeIf { it != "off" },
                    thinking = effort?.let {
                        com.adsh.app.core.llm.ThinkingOption(if (it == "off") "disabled" else "enabled")
                    },
                    tools = if (ptcEnabled) listOf(com.adsh.app.core.tools.RunCodeTool.wireSchema) else null,
                )

                val roundStarted = System.currentTimeMillis()
                var firstTokenAt = 0L
                var roundToolMillis = 0L
                var roundSteps = 0
                var promptTokens = 0L
                var completionTokens = 0L
                var cacheHit = 0L
                var cacheMiss = 0L
                var reasoningTokens = 0L
                llm.stream(request).collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            answer.append(event.text)
                            bus.trySend(event)
                        }
                        is ChatEvent.Reasoning -> {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            reasoning.append(event.text)
                            bus.trySend(event)
                        }
                        is ChatEvent.Usage -> {
                            // 一次请求只有一份 usage（dsh 的「一次 attempt 一个采样」）：
                            // 取最后一份，而不是把多个 chunk 加起来（网关重复上报时会把数字翻倍）
                            promptTokens = event.promptTokens.toLong()
                            completionTokens = event.completionTokens.toLong()
                            cacheHit = event.cacheHitTokens.toLong()
                            cacheMiss = event.cacheMissTokens.toLong()
                            reasoningTokens = event.reasoningTokens.toLong()
                            bus.trySend(event)
                        }
                        is ChatEvent.ToolCallDelta -> {
                            calls.getOrPut(event.index) { CallAccumulator() }.apply {
                                if (event.id != null) id = event.id
                                if (event.name != null) name = event.name
                                event.argumentsChunk?.let { args.append(it) }
                            }
                            bus.trySend(event)
                        }
                        is ChatEvent.Finished -> bus.trySend(event)
                        // 掉线重连：上一次尝试攒下的正文 / 思考 / 工具调用参数全部作废
                        // （重连 = 重新生成这一步，见 ChatEvent.StreamReset 的注释）。
                        // 不清的话会把两次尝试的输出首尾相接，拼出一段模型没说过的话。
                        is ChatEvent.StreamReset -> {
                            answer.setLength(0)
                            reasoning.setLength(0)
                            calls.clear()
                            firstTokenAt = 0L
                            bus.trySend(event)
                        }
                        is ChatEvent.Failed -> bus.trySend(event)
                        else -> bus.trySend(event)
                    }
                }
                val roundLlmMillis = System.currentTimeMillis() - roundStarted
                // promptTokens 已经是「未命中缓存」的口径（LlmClient 按 dsh 的 mapUsage 扣过）
                turnPrompt += promptTokens
                turnCompletion += completionTokens
                turnCacheHit += cacheHit
                turnReasoning += reasoningTokens
                turnLlmMillis += roundLlmMillis
                if (turnTtft == 0L && firstTokenAt > 0) turnTtft = firstTokenAt - roundStarted
                // 本轮的用量与时延快照（增量）。turns/steps 语义与 dsh 状态栏一致。
                fun roundStats(): SessionStats = SessionStats(
                    turns = conversationTurns,
                    steps = roundSteps,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cacheHitTokens = cacheHit,
                    cacheMissTokens = cacheMiss,
                    llmMillis = roundLlmMillis,
                    toolMillis = roundToolMillis,
                    ttftMillis = if (firstTokenAt > 0) firstTokenAt - roundStarted else 0,
                    ttftSamples = if (firstTokenAt > 0) 1 else 0,
                )

                // 能落库的调用 = 有名字的那些（toToolCall 要求 name 非空）。
                // 网关把 tool_calls 截断、或只有参数没有名字时，这里会是空列表：
                // 那种情况下如果照样写一条「空 tool_calls 的 assistant 行」再继续循环，
                // 库里的行序就会变成 [assistant(c1)] [tool(c1)] [assistant(空)] [tool(c2)] ——
                // 装配时中间那条空 assistant 行被丢掉，两组工具结果贴到了一起，服务端 400
                // （「assistant 的 tool_calls 后面必须紧跟它的每个 tool 结果」）。
                // 所以拿不到可执行的调用就当作「这一轮结束」，而不是继续往下写。
                val persisted = calls.values.mapNotNull { it.toToolCall() }
                if (persisted.isEmpty() || !ptcEnabled) {
                    // 空回复不落库：库里出现 content 与 tool_calls 都为空的 assistant 行，
                    // 会让后续每一轮请求都被服务端以 400 拒绝
                    if (answer.isNotEmpty() || reasoning.isNotEmpty()) {
                        repository.addMessage(
                            conversationId, "assistant", answer.toString(),
                            reasoning = reasoning.toString().ifEmpty { null },
                        )
                    }
                    openRoundPersisted = false
                    // 最后一步（没有工具调用）同样进日志：界面收到它就「从库里重读 + 清流式缓冲」，
                    // 流式的正文/思考交给库里那一行 —— 与带工具调用的那一步同一条路径（dsh 的 assistant 步）
                    log.append(
                        com.adsh.app.core.session.SessionBody.Step(
                            text = answer.toString(),
                            reasoning = reasoning.toString(),
                        ),
                    )
                    bus.trySend(ChatEvent.Stats(roundStats()))
                    // dsh 的收尾判据是 `turnEnds && inbox.nextStep.length === 0` 才 break：收件箱里
                    // 还有注入行（这一步期间到达、还没被认领）就再走一步，让模型读到它 ——
                    // 于是「忙的时候注入」永远落在**这一轮**里，不需要靠下一轮的开头兜底。
                    if (repository.hasInjected(conversationId)) continue
                    settings.lastWorkspacePath = currentWorkspace?.root?.absolutePath
                    persistTurnUsage()
                    return
                }

                repository.addMessage(
                    conversationId = conversationId,
                    role = "assistant",
                    content = answer.toString(),
                    reasoning = reasoning.toString().ifEmpty { null },
                    toolCallsJson = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                        persisted,
                    ),
                )
                openRoundPersisted = false
                // 这一步（含工具调用）已经落库，**同时进日志**（dsh 的 assistant 步）：界面收到这条
                // 事件就在同一次状态更新里「从库里重读 + 清掉流式缓冲」—— 流式的正文/思考交给库里
                // 那一行，下一步的正文不会接在它们后面（旧实现里流式正文会一路把工具行挤下去）
                log.append(
                    com.adsh.app.core.session.SessionBody.Step(
                        text = answer.toString(),
                        reasoning = reasoning.toString(),
                    ),
                )

                var aborted: String? = null
                for (call in persisted) {
                    currentCoroutineContext().ensureActive()
                    val name = call.function.name ?: continue
                    val argsJson = call.function.arguments ?: "{}"
                    // 模型「调用失误」时的死循环：同一个工具 + 同一份参数反复来，
                    // 或者一轮里的工具调用总数彻底失控 —— 立刻停，不要写出一条几 MB 的记录
                    val signature = name + "\u0000" + argsJson
                    val repeat = (callSignatures[signature] ?: 0) + 1
                    callSignatures[signature] = repeat
                    toolCallsThisTurn++
                    if (repeat > REPEAT_CALL_LIMIT || toolCallsThisTurn > MAX_TOOL_CALLS_PER_TURN) {
                        val why = if (repeat > REPEAT_CALL_LIMIT) {
                            "同一个工具调用重复了 " + repeat + " 次（" + name + "），疑似陷入循环"
                        } else {
                            "单条消息的工具调用总数超过 " + MAX_TOOL_CALLS_PER_TURN + " 次"
                        }
                        aborted = why
                        // dsh 的「未开始就取消」路径：宿主补一对 call + result（spec-log §5.2），
                        // 日志里不留悬空的结果
                        val stoppedToken = EXEC_SEQ.incrementAndGet()
                        log.append(com.adsh.app.core.session.SessionBody.ToolCall(call.id, stoppedToken, name, argsJson))
                        log.append(com.adsh.app.core.session.SessionBody.ToolResult(call.id, stoppedToken, name, why, true))
                        break
                    }
                    // 顶层调用的身份：进程内单调、永不复用（dsh 的 rootCallId 由宿主生成，
                    // 模型给的 callId 不保证唯一 —— 有些网关每条响应都从 call_0 重编号）。
                    // PTC 子调用的 id 就是 "<execToken>:ptc:<n>"，界面按它归属，不再靠到达顺序。
                    val execToken = EXEC_SEQ.incrementAndGet()
                    // 顶层调用写进日志（dsh 的 tool/call 必须落在**真正执行之前**，spec-log §5）
                    log.append(com.adsh.app.core.session.SessionBody.ToolCall(call.id, execToken, name, argsJson))
                    ToolStepCounter.bump(1)
                    val toolStarted = System.currentTimeMillis()
                    // 每次调用都带上自己的 callId：审批弹窗要显示是哪一次调用申请的越权。
                    // execToken 只用于子调用轨迹的归属（见 ToolContext.onSubCall）。
                    val (output, isError) = execute(
                        name,
                        argsJson,
                        toolContext.copy(
                            callId = call.id,
                            execToken = execToken,
                            // 子调用回调：从工具线程 append 进**同一份日志**（同步提交 + 同步广播）。
                            // 归属写在 sub.id（"<execToken>:ptc:<n>"），所以不需要任何「谁先到」的判据。
                            onSubCallStart = { sub ->
                                log.append(com.adsh.app.core.session.SessionBody.PtcDispatchStart(sub))
                            },
                            onSubCall = { sub ->
                                log.append(com.adsh.app.core.session.SessionBody.PtcDispatch(sub))
                            },
                        ),
                    )
                    val toolMillis = System.currentTimeMillis() - toolStarted
                    roundToolMillis += toolMillis
                    // 子调用轨迹在这一刻定型（先把 trace 收干，下面 emit 与落库用的是同一份）
                    val subCalls = SubCallTrace.drain()
                    // 子调用产出的图片（目前只有 read_image）：落在这条工具行上。
                    // 装配请求时按 dsh 的 tools-ptc deferContext 作为一条 user 消息回灌模型 ——
                    // 图片不进 run_code 的返回值（dsh 的原话：attached after the run）。
                    val images = subCalls.flatMap { it.images }
                    // present 声明的交付物（dsh 的 deliverables/presented）：同样落在工具行上，
                    // 界面按轮把它们画成轮尾的文件卡片。**只有成功的子调用才有**
                    // （dsh：Blocked results publish none；这里 ok=false 的子调用不收集）。
                    val deliverables = collectDeliverables(
                        subCalls.filter { it.ok }.map { it.deliverables },
                    )
                    // 先写日志、再落库（第 98 轮把顺序换过来）：工具早就返回了，红点/结果不该
                    // 等一次 SQLite 写入（还要序列化子调用与图片）才出现 —— 用户第 98 轮观察到的
                    // 「报错的红点比正文慢半拍」就出在这段空档上。库里那一行仍在同一步内写完
                    // （这一步的 Step 事件之前），落库失败也不会改变程序行为。

                    log.append(com.adsh.app.core.session.SessionBody.ToolResult(call.id, execToken, name, output, isError))
                    repository.addMessage(
                        conversationId = conversationId,
                        role = "tool",
                        // 单行必须有上限：CursorWindow 是 2MB，超了这一行就再也读不出来
                        content = output.take(MAX_TOOL_CONTENT_CHARS),
                        toolCallId = call.id,
                        name = name,
                        subCallsJson = if (subCalls.isEmpty()) null else json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(SubCall.serializer()),
                            cappedSubCalls(subCalls),
                        ),
                        imagesJson = com.adsh.app.core.agent.encodeToolImages(images),
                        deliverablesJson = encodeDeliverables(deliverables),
                        isError = isError,
                        durationMs = toolMillis,
                    )
                }
                val stopReason = aborted
                if (stopReason != null) {
                    // 停下并把原因说清楚：不能让界面停在「看起来还在跑」的状态
                    persistTurnUsage()
                    val stoppedText = "（已停下：" + stopReason + "。可以换个说法让我继续，或先检查工作区状态）"
                    repository.addMessage(
                        conversationId = conversationId,
                        role = "assistant",
                        content = stoppedText,
                    )
                    log.append(
                        com.adsh.app.core.session.SessionBody.Step(
                            text = stoppedText,
                            reasoning = "",
                            interrupted = true,
                        ),
                    )
                    bus.trySend(ChatEvent.Stats(roundStats()))
                    return
                }
                roundSteps = ToolStepCounter.drain()
                bus.trySend(ChatEvent.Stats(roundStats()))
            }

        } catch (cancel: CancellationException) {
            // 打断：把已经生成的内容按 dsh 的 interrupted 语义落库（message.stopped「已停止」）。
            // 必须用 NonCancellable —— 协程已经被取消，普通挂起点会立刻再抛一次取消，
            // 结果是「按了停止但什么都没保存」（旧实现的 bug）。
            withContext(NonCancellable) {
                settings.lastWorkspacePath = currentWorkspace?.root?.absolutePath
                persistTurnUsage()
                if (openRoundPersisted) {
                    val partial = answer.toString()
                    val partialReasoning = reasoning.toString()
                    val toolCalls = calls.values.mapNotNull { it.toToolCall() }
                    if (partial.isNotBlank() || partialReasoning.isNotBlank() || toolCalls.isNotEmpty()) {
                        repository.addMessage(
                            conversationId = conversationId,
                            role = "assistant",
                            content = partial,
                            reasoning = partialReasoning.ifEmpty { null },
                            toolCallsJson = if (toolCalls.isEmpty()) null else json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                                toolCalls,
                            ),
                            name = INTERRUPTED,
                        )
                    }
                }
            }
            throw cancel
        }
    }

    /**
     * 装配请求历史。DeepSeek / OpenAI 兼容接口对历史有硬约束：
     *  - assistant 消息必须至少有 content 或 tool_calls 之一；
     *  - 带 tool_calls 的 assistant 后面必须紧跟它每个调用的 tool 结果；
     *  - tool 消息必须能对应到前一条 assistant 的某个 tool_call。
     * 取消、崩溃、断电都会在库里留下不满足约束的残行，这里统一「成组校验 + 丢弃」，
     * 否则一条脏记录会让整个会话永久 400。
     * sysprompt / command 行在装配时跳过（前者的内容就是上面那条 system 消息，后者不进模型）；
     * 运行时上下文快照（form = snapshot）反而**必须发**，见下面那一支。
     *
     */
    private suspend fun buildMessages(
        conversationId: Long,
        previousPath: String? = null,
        imageCapable: Boolean = false,
        /**
         * 当前路由是不是 DeepSeek。是的话，带 tool_calls 的 assistant 消息即使没有存下
         * reasoning，也要回一个空的 reasoning_content（思考模式的硬要求，见 ChatMessage 注释）；
         * 其它提供方不认识这个字段，就不发。
         */
        echoReasoning: Boolean = false,
    ): List<ChatMessage> {
        // 库里的顺序就是请求里的顺序：注入行（插话 / 任务通知）只在**认领那一刻**写进库
        // （见 ConversationRepository.claimInjected），位置天然落在上一步的工具结果之后 ——
        // 所以不需要再为「工具结果必须紧跟 assistant」做任何重排（第 120 轮删掉了那次重排）。
        val history = repository.messages(conversationId)
        val messages = ArrayList<ChatMessage>(history.size + 1)
        messages += ChatMessage(
            role = "system",
            // 这里只要**稳定段**：当前策略 / 计划模式走运行时上下文注入（recordContext 写下的
            // context 行），并且在下面按 form = snapshot 作为 user 消息发出去
            content = textContent(assemblePrompt(planMode = false, previousPath = previousPath).system),
        )

        var index = 0
        while (index < history.size) {
            val entity = history[index]
            when (entity.role) {
                "tool" -> index++ // 孤儿工具结果：丢弃

                // 运行时上下文快照（当前策略 / Android 环境 / 计划模式，各段拼成一条）：**要发给模型** ——
                // dsh 里它就是一条 source = runtime-context 的 user 消息（agent-loop 的
                // RuntimeContextProjection），不是系统提示词的一部分。旧的快照留在历史里，
                // 由最新那条的抬头声明被取代。
                "context" -> {
                    // form = snapshot（策略 / 环境 / 计划）与 form = instructions（指令文件链，
                    // dsh 的 agent-instructions）都是**真的会发出去的 user 消息**；其余形态的
                    // context 行只是展示节点。
                    if ((entity.subCallsJson == PromptAssembler.FORM_SNAPSHOT ||
                            entity.subCallsJson == PromptAssembler.FORM_INSTRUCTIONS) &&
                        entity.content.isNotBlank()
                    ) {
                        messages += ChatMessage(role = "user", content = textContent(entity.content))
                    }
                    index++
                }

                // 展示用的会话节点，不进请求（sysprompt 的内容在系统提示词那一条里；
                // command = 用户敲的斜杠命令行；其余 context 行是自定义后缀这类 DISPLAY 行）
                "sysprompt", "command" -> index++

                "assistant" -> {
                    val toolCalls = entity.decodeToolCalls()
                    if (toolCalls.isNullOrEmpty()) {
                        if (entity.content.isNotBlank() || !entity.reasoning.isNullOrEmpty()) {
                            messages += ChatMessage(
                                role = "assistant",
                                content = textContent(entity.content),
                                reasoningContent = entity.reasoning?.takeIf { it.isNotEmpty() },
                            )
                        }
                        index++
                    } else {
                        // 一次把后面连续的工具结果行全部收走 —— 之后**只发这一组里的**结果。
                        // 旧实现是「收集全部相邻 tool 行，再判断 expected ⊆ actual 就整组发出」：
                        // 一旦库里出现「上一组的 tool 结果和下一组的 tool 结果相邻」的残局
                        // （崩溃/打断/模型给出无法执行的 tool_calls），多出来的那条结果会被
                        // 挂在别人的 assistant 消息下面，服务端直接 400：
                        // 「An assistant message with 'tool_calls' must be followed by tool messages
                        //   responding to each 'tool_call_id'」。
                        var cursor = index + 1
                        val results = ArrayList<com.adsh.app.core.data.MessageEntity>()
                        while (cursor < history.size && history[cursor].role == "tool") {
                            results += history[cursor]
                            cursor++
                        }
                        // 每个 tool_call 都要有 id：dsh 的 tool-call 块 id 一定有（serializeAssistant
                        // 直接写 block.id），ADSH 的历史里可能有残缺行 —— 补一个稳定的合成 id，
                        // 而不是把 "id": null 发出去（服务端会 invalid type: null）。
                        val calls = toolCalls.mapIndexed { position, call ->
                            if (call.id.isNullOrEmpty()) {
                                call.copy(id = "call_" + entity.id + "_" + position)
                            } else {
                                call
                            }
                        }
                        val paired = pairToolResults(calls, results)
                        // 思考模式的历史要求：带 tool_calls 的 assistant 消息必须把 reasoning_content
                        // 回传（实测缺失就是 400）。库里的 reasoning 为空时，DeepSeek 路由下补一个空串
                        // （实测空串在 thinking=enabled / disabled 两种模式下都合法）。
                        val storedReasoning = entity.reasoning?.takeIf { it.isNotEmpty() }
                        val reasoningContent = storedReasoning ?: if (echoReasoning) "" else null
                        messages += ChatMessage(
                            role = "assistant",
                            // dsh 的 serializeAssistant 恒写 content（空串也写），不省略
                            content = textContent(entity.content),
                            reasoningContent = reasoningContent,
                            toolCalls = calls,
                        )
                        calls.forEachIndexed { position, call ->
                            val result = paired[position]
                            if (result == null) {
                                // dsh 的崩溃修复：assistant 请求了调用、但没有持久化的结果 ——
                                // 补一条「结果未知」的工具结果，让这段历史在协议上完整
                                // （dsh-session 的 TOOL_OUTCOME_UNKNOWN 文案，逐字）。
                                messages += ChatMessage(
                                    role = "tool",
                                    content = textContent(TOOL_OUTCOME_UNKNOWN),
                                    toolCallId = call.id,
                                    name = call.function.name,
                                )
                            } else {
                                messages += ChatMessage(
                                    role = "tool",
                                    // dsh 的 serializeMessages：空结果写 "(no output)"，别发空串
                                    content = textContent(result.content.ifEmpty { NO_OUTPUT }),
                                    toolCallId = call.id,
                                    name = result.name,
                                )
                                // 这条结果带图片（read_image）：按 dsh 的 tools-ptc deferContext，
                                // 把 [信封文本, 图片块] 作为一条 **user** 消息追加在这次 run 之后 ——
                                // 图片不能塞进 tool 消息（提供方只认 user 消息里的图片块），
                                // 所以模型在下一步才看到图（dsh 的 SDK 说明也是这么写的）。
                                val images = com.adsh.app.core.agent.decodeToolImages(result.imagesJson)
                                if (images.isNotEmpty()) {
                                    val content = com.adsh.app.core.agent.toolImageContentPart(images, imageCapable)
                                    if (content != null) messages += ChatMessage(role = "user", content = content)
                                }
                            }
                        }
                        index = cursor
                    }
                }

                else -> {
                    // 用户消息：带附件时按 dsh 的 contentParts 组装（正文 + 文件句柄 / 图片块）；
                    // 其余角色（user 之外的脏数据）按纯文本带过去
                    val attachments = decodeAttachments(entity.attachmentsJson)
                    if (attachments.isEmpty()) {
                        if (entity.content.isNotBlank()) {
                            messages += ChatMessage(role = entity.role, content = textContent(entity.content))
                        }
                    } else {
                        messages += ChatMessage(
                            role = entity.role,
                            content = userContentPart(entity.content, attachments, imageCapable),
                        )
                    }
                    index++
                }
            }
        }
        return messages
    }

    private fun com.adsh.app.core.data.MessageEntity.decodeToolCalls(): List<ToolCall>? =
        toolCallsJson?.let { raw ->
            runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                    raw,
                )
            }.getOrNull()
        }

    /**
     * 子调用轨迹的落库上限（细节与「为什么不能按字符数砍参数」见 [SubCallTrimmer]）：
     * 只留最近 [MAX_SUB_CALLS_PER_STEP] 条，并压进 CursorWindow 的预算内。
     */
    private fun cappedSubCalls(all: List<SubCall>): List<SubCall> =
        SubCallTrimmer.cap(all, MAX_SUB_CALLS_PER_STEP)

    private suspend fun execute(name: String, argsJson: String, toolContext: ToolContext): Pair<String, Boolean> {
        // PTC 模式：**只有 run_code 能直接调用**（dsh 的 tools:ptc-only 规则，提示词里那条
        // PTC_ONLY_INSTRUCTION 就是它）。模型直接点名别的工具时不能替它跑 —— 那正是
        // 「其他模型不用 run_code 就直接调用实际工具」的来源：有些 OpenAI 兼容网关不校验
        // tools 数组，模型照着提示词里的工具目录发一个原生调用，我们拿注册表里同名的工具一跑，
        // 就绕过了 run_code（子调用轨迹、并行闸门、SDK 那一层全都不在）。
        // dsh 在这里抛 ToolNotFoundError（code：UNKNOWN_TOOL），文案逐字：
        //   unknown tool "web_search": only `run_code` is callable directly — call
        //   `web_search` from inside a `run_code` program instead
        if (name != RunCodeTool.name) {
            return if (ToolRegistry.byName(name) == null) {
                "unknown tool \"" + name + "\"" to true
            } else {
                "unknown tool \"" + name + "\": only `" + RunCodeTool.name +
                    "` is callable directly — call `" + name + "` from inside a `" +
                    RunCodeTool.name + "` program instead" to true
            }
        }
        val tool = ToolRegistry.byName(name) ?: return "unknown tool \"" + name + "\"" to true
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }
            .getOrElse { return "工具参数不是 JSON 对象：" + argsJson.take(200) to true }
        return runCatching { tool.execute(args, toolContext) }
            .fold(
                onSuccess = { result ->
                    when (result) {
                        // 工具输出层兜底脱敏（第 108 轮）：结果要进会话记录、还要发给模型，
                        // 命中的凭据在这里换成「前缀 + ***redacted***」（见 SecretRedaction）
                        is ToolResult.Ok -> com.adsh.app.core.tools.SecretRedaction.redact(result.text) to false
                        is ToolResult.Error -> com.adsh.app.core.tools.SecretRedaction.redact(result.message) to true
                    }
                },
                // 取消**不能**被吞成一个工具错误：那会让「停止」变成「工具失败 + 继续下一轮」
                // （旧实现的 stop 要等工具自然结束、而且还是接着跑），必须原样抛出去让整轮收尾。
                onFailure = { t ->
                    if (t is CancellationException) throw t
                    (t.message ?: t::class.java.simpleName) to true
                },
            )
    }

    /**
     * 用装配器构建系统提示词；工作区变化时自动触发基线替换。
     *
     * 工作区取自 [currentWorkspace]（不是调用方传的路径）：以前那个 `workspaceRoot` 参数
     * 从头到尾没被用过，真正决定 cwd / 指令文件链 / env 形态的是这个字段。
     */
    private fun assemblePrompt(
        planMode: Boolean = false,
        previousPath: String? = null,
    ): PromptAssembler.Parts = PromptAssembler.buildParts(
        workspace = currentWorkspace,
        extraSuffix = settings.systemPromptSuffix,
        filePolicy = PromptAssembler.filePolicyOf(settings.permission),
        planMode = planMode,
        previousWorkspacePath = previousPath,
        // 第九十二轮：`tools.bash` 的超时口径是**设置里的当前值**，所以进快照而不是静态段
        bashTimeoutMs = settings.bashTimeoutMs,
        bashMaxTimeoutMs = settings.bashMaxTimeoutMs,
    )


    private class CallAccumulator {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
        fun toToolCall(): ToolCall? {
            val toolName = name ?: return null
            return ToolCall(
                // **id 一定要有**：有些 OpenAI 兼容网关（实测 qwen 的 token-plan 端点）流式返回的
                // tool_calls 压根不带 id（delta 里没有这个字段，或是一个空串）。id 是配对键 ——
                // assistant 行与它的 tool 结果行靠它一一对应，装配历史时也靠它判断「这次调用有没有
                // 结果」。空 id 会让「有调用、没结果」的崩溃修复误判：工具明明跑完了，模型下一轮
                // 却被告知 "The tool call was interrupted after it was recorded..."（实测就是这样）。
                // 所以缺 id 时补一个合成 id（OpenAI 的 id 本来就是客户端回显用的不透明串）。
                id = id?.trim()?.takeIf { it.isNotEmpty() } ?: syntheticCallId(),
                type = "function",
                function = ToolCallFunction(name = toolName, arguments = args.toString().ifEmpty { "{}" }),
            )
        }

        /** 合成 id：形状与 OpenAI 的 call_… 一致，只要求「同一次调用在历史里唯一」 */
        private fun syntheticCallId(): String =
            "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
    }

    companion object {
        /**
         * 顶层工具调用的身份发生器（dsh 的 rootCallId 由宿主生成，spec-log §3）。
         *
         * 进程内单调、**不按轮重置**：PTC 子调用的 id 是 `<execToken>:ptc:<n>`，跨轮重号会让
         * 「这条子行属于哪一次顶层调用」这种问题在换轮时重新冒出来（第 108 轮的归属状态机就是
         * 为了绕开重号才写的）。模型给的 `callId` 不能当身份 —— 有些 OpenAI 兼容网关每条响应
         * 都从 `call_0` 重新编号。
         */
        private val EXEC_SEQ = java.util.concurrent.atomic.AtomicLong(0)

        /** assistant 行上的标记：这一轮的输出是被打断的（dsh 的 interrupted） */
        const val INTERRUPTED = "interrupted"

        /** 单条消息里允许的工具调用总数（死循环判据：模型调用失误时不会无限刷下去） */
        const val MAX_TOOL_CALLS_PER_TURN = 300

        /** 同一组「工具 + 参数」重复多少次就判定为死循环并停下 */
        const val REPEAT_CALL_LIMIT = 3

        /** 一条工具结果消息最多落库多少字符（默认 bash 输出上限是 128KiB，这里再兜一层） */
        const val MAX_TOOL_CONTENT_CHARS = 262_144

        /**
         * dsh 的崩溃修复文案（dsh-session/lib/index.js 的 interruptedTurnClosers，逐字）：
         * assistant 已经请求了工具、但没有持久化的结果时，用它补一条工具结果，
         * 让这段历史在协议上保持完整（否则整个会话会永久 400）。
         */
        const val TOOL_OUTCOME_UNKNOWN =
            "The tool call was interrupted after it was recorded, but no result was durably " +
                "recorded. Its outcome is unknown. Decide whether to retry from the tool " +
                "semantics: retry only if the operation is read-only or idempotent; if it may " +
                "have side effects, first verify external state or ask the user. Do not retry blindly."

        /** dsh 的 serializeMessages：工具结果为空时写这个（逐字） */
        const val NO_OUTPUT = "(no output)"

        /** 一步的子调用轨迹最多落库多少条（每条参数 / 结果的字符上限见 [SubCallTrimmer]） */
        const val MAX_SUB_CALLS_PER_STEP = 60
    }
}

/**
 * 把一组工具结果按 id 配对到这次调用的每一个 tool_call 上（返回与 calls 等长的列表，
 * 没配上的位置是 null —— 调用方会给它补一条「结果未知」）。
 *
 * **没有 id 的结果要按顺序兜底**：有些 OpenAI 兼容网关（实测 qwen 的 token-plan 端点）
 * 流式返回的 tool_calls 不带 id，于是 assistant 行与结果行的 id 都是空串；只按 id 配对的话
 * 每一次调用都会被判成「有调用、没结果」，工具明明跑完了，模型下一轮却收到
 * "The tool call was interrupted after it was recorded..."（实测的「qwen 一用就中断」）。
 * 一组结果本来就是按调用顺序落库的，所以位置就是它的配对依据。
 */
internal fun pairToolResults(
    calls: List<ToolCall>,
    results: List<com.adsh.app.core.data.MessageEntity>,
): List<com.adsh.app.core.data.MessageEntity?> {
    val byId = HashMap<String, com.adsh.app.core.data.MessageEntity>()
    val unkeyed = ArrayList<com.adsh.app.core.data.MessageEntity>()
    results.forEach { result ->
        val id = result.toolCallId
        if (!id.isNullOrEmpty() && !byId.containsKey(id)) byId[id] = result else unkeyed += result
    }
    var cursor = 0
    return calls.map { call ->
        byId.remove(call.id) ?: unkeyed.getOrNull(cursor)?.also { cursor++ }
    }
}

