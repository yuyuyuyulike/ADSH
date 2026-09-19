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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
     * 系统提示词的落库规则（对齐 dsh 的 SystemPromptProjection）：
     *  - **首次对话**（还没有系统提示词节点）写一条「系统提示词」；
     *  - **压缩上下文之后**再对话时重新注入一条（dsh 的 startsSeries：压缩会开启新的一段请求序列）；
     *  - 其余情况（换了工作区 / goal / plan / 工具…）只**就地替换**原来那一行，
     *    不再追加「系统提示词更新」——之前每变一次就多一行，看着像在反复注入。
     * 上下文注入（指令文件 / goal / plan）各写一条 context 节点；内容没变就不重复写。
     * 这些节点只用于展示，装配请求时会被跳过。
     */
    suspend fun recordContext(
        conversationId: Long,
        workspaceRoot: String?,
        planMode: Boolean = false,
    ) {
        val parts = assemblePrompt(workspaceRoot, planMode, settings.lastWorkspacePath)
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
        parts.injections.forEach { injection ->
            val previousRow = repository.lastNamed(conversationId, "context", injection.label)
            if (previousRow?.content != injection.text) {
                repository.addMessage(
                    conversationId = conversationId,
                    role = "context",
                    content = injection.text,
                    name = injection.label,
                    subCallsJson = injection.form,
                )
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
        workspaceRoot: String?,
        toolContext: ToolContext?,
        persistUser: Boolean = true,
        /** 待发附件（已导入工作区的只读副本）：与用户消息一起落库 */
        attachments: List<UserAttachment> = emptyList(),
    ): Flow<ChatEvent> = flow {
        currentWorkspace = toolContext?.workspace
        val config = settings.providerConfig()
        // 思考模式的历史校验是 DeepSeek 特有的（reasoning_content 必须回传），
        // 所以「空 reasoning 也补一个空串」只对 DeepSeek 路由生效
        val deepseekRoute = config.providerId == com.adsh.app.core.data.BuiltInProviders.DEEPSEEK_ID ||
            config.baseUrl.contains("deepseek") || config.model.startsWith("deepseek")
        val ptcEnabled = toolContext != null
        val conversationTurns = repository.messages(conversationId).count { it.role == "user" }
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
            val userId = repository.lastNamed(conversationId, "user")?.id ?: return
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

                val messages = buildMessages(
                    conversationId = conversationId,
                    workspaceRoot = workspaceRoot,
                    planMode = repository.byId(conversationId)?.planMode == true,
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
                            emit(event)
                        }
                        is ChatEvent.Reasoning -> {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            reasoning.append(event.text)
                            emit(event)
                        }
                        is ChatEvent.Usage -> {
                            // 一次请求只有一份 usage（dsh 的「一次 attempt 一个采样」）：
                            // 取最后一份，而不是把多个 chunk 加起来（网关重复上报时会把数字翻倍）
                            promptTokens = event.promptTokens.toLong()
                            completionTokens = event.completionTokens.toLong()
                            cacheHit = event.cacheHitTokens.toLong()
                            cacheMiss = event.cacheMissTokens.toLong()
                            reasoningTokens = event.reasoningTokens.toLong()
                            emit(event)
                        }
                        is ChatEvent.ToolCallDelta -> {
                            calls.getOrPut(event.index) { CallAccumulator() }.apply {
                                if (event.id != null) id = event.id
                                if (event.name != null) name = event.name
                                event.argumentsChunk?.let { args.append(it) }
                            }
                            emit(event)
                        }
                        is ChatEvent.Finished -> emit(event)
                        is ChatEvent.Failed -> emit(event)
                        else -> emit(event)
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
                    emit(ChatEvent.Stats(roundStats()))
                    settings.lastWorkspacePath = currentWorkspace?.root?.absolutePath
                    persistTurnUsage()
                    return@flow
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
                // 这一步（含工具调用）已经落库：界面立刻从库里重读，流式的正文/思考交给库里的行，
                // 下一步的正文不会接在它们后面（旧实现里流式正文会一路把工具行挤下去）
                emit(ChatEvent.StepCommitted("step"))

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
                        emit(ChatEvent.ToolFinished(call.id, name, why, true))
                        break
                    }
                    emit(ChatEvent.ToolStarted(call.id, name, argsJson))
                    ToolStepCounter.bump(1)
                    val toolStarted = System.currentTimeMillis()
                    // 每次调用都带上自己的 callId：审批弹窗要显示是哪一次调用申请的越权
                    val (output, isError) = execute(name, argsJson, toolContext.copy(callId = call.id))
                    val toolMillis = System.currentTimeMillis() - toolStarted
                    roundToolMillis += toolMillis
                    // 先落库、再通知界面。顺序反了的话，界面在 ToolFinished 里立刻重读库时，
                    // 这一行还是「没有输出、没有子调用、没有报错」的旧状态 —— 表现就是 run_code
                    // 下面的子调用先出现、紧接着整片消失，要等下一轮重读才一起回来。
                    val subCalls = SubCallTrace.drain()
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
                        isError = isError,
                        durationMs = toolMillis,
                    )
                    emit(ChatEvent.ToolFinished(call.id, name, output, isError))
                }
                val stopReason = aborted
                if (stopReason != null) {
                    // 停下并把原因说清楚：不能让界面停在「看起来还在跑」的状态
                    persistTurnUsage()
                    repository.addMessage(
                        conversationId = conversationId,
                        role = "assistant",
                        content = "（已停下：" + stopReason + "。可以换个说法让我继续，或先检查工作区状态）",
                    )
                    emit(ChatEvent.StepCommitted("step"))
                    emit(ChatEvent.Stats(roundStats()))
                    return@flow
                }
                roundSteps = ToolStepCounter.drain()
                emit(ChatEvent.Stats(roundStats()))
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
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * 装配请求历史。DeepSeek / OpenAI 兼容接口对历史有硬约束：
     *  - assistant 消息必须至少有 content 或 tool_calls 之一；
     *  - 带 tool_calls 的 assistant 后面必须紧跟它每个调用的 tool 结果；
     *  - tool 消息必须能对应到前一条 assistant 的某个 tool_call。
     * 取消、崩溃、断电都会在库里留下不满足约束的残行，这里统一「成组校验 + 丢弃」，
     * 否则一条脏记录会让整个会话永久 400。
     * 展示专用的行（sysprompt / context）在装配时跳过：它们的内容已经在系统提示词里。
     */
    private suspend fun buildMessages(
        conversationId: Long,
        workspaceRoot: String?,
        planMode: Boolean = false,
        previousPath: String? = null,
        imageCapable: Boolean = false,
        /**
         * 当前路由是不是 DeepSeek。是的话，带 tool_calls 的 assistant 消息即使没有存下
         * reasoning，也要回一个空的 reasoning_content（思考模式的硬要求，见 ChatMessage 注释）；
         * 其它提供方不认识这个字段，就不发。
         */
        echoReasoning: Boolean = false,
    ): List<ChatMessage> {
        val history = repository.messages(conversationId)
        val messages = ArrayList<ChatMessage>(history.size + 1)
        messages += ChatMessage(
            role = "system",
            content = textContent(assemblePrompt(workspaceRoot, planMode, previousPath).system),
        )

        var index = 0
        while (index < history.size) {
            val entity = history[index]
            when (entity.role) {
                "tool" -> index++ // 孤儿工具结果：丢弃

                // 展示用的会话节点，不进请求（command = 用户敲的斜杠命令行）
                "sysprompt", "context", "command" -> index++

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
     * 子调用轨迹的落库上限。一个 run_code 程序可以 await 上千次工具，
     * 全塞进一行会让这一行超过 SQLite CursorWindow 的 2MB —— 之后每次进这条会话
     * 都在读库时闪退（SQLiteBlobTooBigException，实测 dropbox 里的崩溃）。
     *
     * dsh 的做法是「每条事实一条小日志」（会话 JSONL 逐条追加、工具输出按 maxBytes 截断），
     * ADSH 把一步的所有子调用放在一行里，所以至少要保证这一行不超预算：
     * 只留最近 [MAX_SUB_CALLS_PER_STEP] 条，每条结果截断到 [SUB_CALL_RESULT_LIMIT]。
     */
    private fun cappedSubCalls(all: List<SubCall>): List<SubCall> =
        all.takeLast(MAX_SUB_CALLS_PER_STEP).map {
            it.copy(
                args = it.args.take(SUB_CALL_ARGS_LIMIT),
                result = it.result.take(SUB_CALL_RESULT_LIMIT),
            )
        }

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
                        is ToolResult.Ok -> result.text to false
                        is ToolResult.Error -> result.message to true
                    }
                },
                onFailure = { (it.message ?: it::class.java.simpleName) to true },
            )
    }

    /** 用装配器构建系统提示词；工作区变化时自动触发基线替换 */
    private fun assemblePrompt(
        workspaceRoot: String?,
        planMode: Boolean = false,
        previousPath: String? = null,
    ): PromptAssembler.Parts {
        val policy = PromptAssembler.filePolicyOf(settings.permission)
        val workspace = currentWorkspace
        if (workspace == null) {
            // 没有 shell 工作区（SAF 引用形态）：段落顺序与有工作区时一致，
            // 只是没有 cwd 与指令文件链；请求里带上会话级的目标 / 计划模式与工具 SDK 段
            return PromptAssembler.buildWithoutWorkspace(
                model = settings.model,
                extraSuffix = settings.systemPromptSuffix,
                planMode = planMode,
                filePolicy = policy,
                // dsh 的 todo 说明里「并行策略」那一段由 agent-loop.maxParallelToolCalls 决定
                allowParallel = settings.agentMaxParallel > 1,
            )
        }
        return PromptAssembler.buildParts(
            workspace = workspace,
            extraSuffix = settings.systemPromptSuffix,
            previousWorkspacePath = previousPath,
            filePolicy = policy,
            planMode = planMode,
            // dsh 的 persona.prefix 里 {{model}} 就是当前路由的模型 id
            model = settings.model,
            allowParallel = settings.agentMaxParallel > 1,
        )
    }


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

        /** 一步的子调用轨迹最多落库多少条 / 每条参数与结果最多多少字符 */
        const val MAX_SUB_CALLS_PER_STEP = 60
        const val SUB_CALL_ARGS_LIMIT = 2_048
        const val SUB_CALL_RESULT_LIMIT = 8_192


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

