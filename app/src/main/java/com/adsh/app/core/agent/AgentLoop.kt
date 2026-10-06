package com.adsh.app.core.agent

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.TurnSettings
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.ProviderConfig
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.ToolCallFunction
import com.adsh.app.core.llm.TurnLlm
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
    private val llm: TurnLlm,
    private val repository: TurnStore,
    private val settings: TurnSettings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    private var currentWorkspace: com.adsh.app.core.workspace.Workspace? = null

    /**
     * 请求历史的装配（库里的一行 → wire 上的一条消息）。协议约束与各分支口径见 [RequestMessages]；
     * 这里只把「系统提示词的稳定段」接进去 —— 策略 / 计划模式走运行时上下文注入（[recordContext]）。
     */
    private val requestMessages = RequestMessages(
        store = repository,
        json = json,
        systemPrompt = { previousPath -> assemblePrompt(planMode = false, previousPath = previousPath).system },
    )

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
     * 这些节点装配请求时按各自的交付方式处理（见 [RequestMessages]）。
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
     * 跑一轮对话（生产入口）。**必须 `flowOn(IO)`**：这个 flow 里全是阻塞调用 —— LlmClient 的
     * 流式读取、工具执行的 `Process.waitFor()`（bash 最长可跑到超时）、工具的文件读写。
     * 收集方是 ChatViewModel 的 `viewModelScope.launch`（Main.immediate），不切线程的话每一次
     * 工具调用都会把主线程堵住 —— 表现就是「发出去消息之后界面卡死」。
     *
     * [toolContext] 为 null = 这一轮没有工具世界（纯文本会话，PTC 关）。
     */
    fun send(
        conversationId: Long,
        userText: String,
        toolContext: ToolContext?,
        persistUser: Boolean = true,
        /** 待发附件（已导入工作区的只读副本）：与用户消息一起落库 */
        attachments: List<UserAttachment> = emptyList(),
    ): Flow<ChatEvent> = sendFlow(conversationId, userText, toolContext, null, persistUser, attachments)

    /**
     * 跑一轮对话（**测试口**）：直接喂 [TurnTools] 替身，于是工具轮也能在纯 JVM 单测里跑
     * （生产走 [send] 的 [ToolContext]，见 [TurnTools] 顶上那段"为什么需要它"）。
     */
    internal fun sendWithTools(
        conversationId: Long,
        userText: String,
        tools: TurnTools?,
        persistUser: Boolean = true,
        attachments: List<UserAttachment> = emptyList(),
    ): Flow<ChatEvent> = sendFlow(conversationId, userText, null, tools, persistUser, attachments)

    /** [send] 与 [sendWithTools] 的同一具身体。事件总线与日志不变量自检见下面的注释。 */
    private fun sendFlow(
        conversationId: Long,
        userText: String,
        toolContext: ToolContext?,
        tools: TurnTools?,
        persistUser: Boolean,
        attachments: List<UserAttachment>,
    ): Flow<ChatEvent> = channelFlow {
        // 单序事件总线（dsh 的 Session.append + 同步广播，spec-log §1）：日志是这一轮的唯一真相，
        // 界面拿到的是它的**投影**；子调用回调从工具线程 append 进同一份日志，于是
        // 「回调比宿主登记这次调用更早到」这种竞速在结构上不存在（不需要任何归属状态去兜）。
        val bus = Channel<ChatEvent>(Channel.UNLIMITED)
        val log = com.adsh.app.core.session.SessionLog()
        // 日志 → 界面事件：**顺序就是 append 的顺序**（同步广播 + 通道 FIFO）
        log.observe { event -> bus.trySend(ChatEvent.Appended(event)) }
        val pump = launch { for (event in bus) send(event) }
        // 工具世界：测试给的替身优先；否则把 ToolContext 包成生产实现（子调用回调挂在同一份日志上）
        val activeTools = tools ?: toolContext?.let { ToolContextTools(it, log) }
        try {
            runTurnBody(conversationId, userText, activeTools, persistUser, attachments, log, bus)
        } finally {
            // 不变量自检（spec-log §7.2）：debug 包在真机上直接报「日志自己不自洽」，
            // 而不是让用户从界面上少一行去猜
            if (com.adsh.app.BuildConfig.DEBUG) {
                val bad = com.adsh.app.core.session.SessionLog.violations(log.events)
                if (bad.isNotEmpty()) {
                    // 纯 JVM 单测里 android.util.Log 是空壳（调用会抛「not mocked」），
                    // 而这里在 finally —— 抛出来会把真正的行为差异盖掉。单测侧另有一条
                    // 断言直接查 violations（AgentLoopGoldenTest.assertLogInvariants），
                    // 所以这里吞掉异常不等于「日志不自洽」没人发现。
                    runCatching { android.util.Log.w("ADSH", "会话日志不变量违规：" + bad.joinToString("；")) }
                }
            }
            bus.close()
            pump.join()
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * 生产侧的 [TurnTools]：把 [ToolContext] 包成端口。
     *
     * 每次调用 copy 一份**带自己身份**的上下文（callId / execToken）再交给真实工具 ——
     * 审批弹窗要显示是哪一次调用申请的越权；子调用回调把轨迹 append 进**同一份日志**
     * （归属写在 sub.id = "<execToken>:ptc:<n>"，所以不需要任何「谁先到」的判据）。
     */
    private inner class ToolContextTools(
        private val context: ToolContext,
        private val log: com.adsh.app.core.session.SessionLog,
    ) : TurnTools {
        override val workspace: com.adsh.app.core.workspace.Workspace get() = context.workspace
        override suspend fun run(name: String, argsJson: String, callId: String?, execToken: Long): Pair<String, Boolean> =
            execute(
                name,
                argsJson,
                context.copy(
                    callId = callId,
                    execToken = execToken,
                    onSubCallStart = { sub ->
                        log.append(com.adsh.app.core.session.SessionBody.PtcDispatchStart(sub))
                    },
                    onSubCall = { sub ->
                        log.append(com.adsh.app.core.session.SessionBody.PtcDispatch(sub))
                    },
                ),
            )
    }

    /** [send] 的实际内容。拆出来只为了把事件发进 [bus]（见那里的注释）。 */
    private suspend fun runTurnBody(
        conversationId: Long,
        userText: String,
        tools: TurnTools?,
        persistUser: Boolean,
        attachments: List<UserAttachment>,
        log: com.adsh.app.core.session.SessionLog,
        bus: Channel<ChatEvent>,
    ) {
        currentWorkspace = tools?.workspace
        // 一轮开始：清掉上一轮没来得及收的文件改动留底（dsh 的 TurnRecorder 每轮一份）
        TurnChangeTracker.beginTurn()
        val config = settings.providerConfig()
        val ptcEnabled = tools != null
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
        // 本轮记账（dsh 的 turn tokenUsage / runMs）：轮尾「用量 / 用时」两个按钮展开的明细。
        // 口径（首字时延 / usage 取最后一份 / 轮首那一行）都在 TurnMeter 里 —— 那个类是纯 Kotlin，有单测。
        val meter = TurnMeter(
            store = repository,
            settings = settings,
            json = json,
            conversationId = conversationId,
            conversationTurns = conversationTurns,
            model = config.model,
            turnStartedAt = turnStartedAt,
        )

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
            val guard = ToolCallGuard()
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

                val request = assembleRoundRequest(
                    conversationId = conversationId,
                    previousPath = previousPath,
                    config = config,
                    ptcEnabled = ptcEnabled,
                )

                meter.beginRound(System.currentTimeMillis())
                collectRound(request, bus, answer, reasoning, calls, meter)
                meter.endRound(System.currentTimeMillis())

                // 能落库的调用 = 有名字的那些（toToolCall 要求 name 非空）；网关截断出来的
                // 空列表不能照样往下写 —— 理由见 [turnEndsAfterStep]。
                val persisted = calls.values.mapNotNull { it.toToolCall() }
                if (turnEndsAfterStep(persisted.size, ptcEnabled)) {
                    // 空回复不落库 —— 理由见 [hasAssistantContent]
                    if (hasAssistantContent(answer, reasoning)) {
                        addAssistantRow(conversationId, answer, reasoning)
                    }
                    openRoundPersisted = false
                    // 最后一步（没有工具调用）同样进日志：界面收到它就「从库里重读 + 清流式缓冲」，
                    // 流式的正文/思考交给库里那一行 —— 与带工具调用的那一步同一条路径（dsh 的 assistant 步）
                    appendAssistantStep(log, answer, reasoning)
                    bus.trySend(ChatEvent.Stats(meter.stats()))
                    // dsh 的收尾判据是 `turnEnds && inbox.nextStep.length === 0` 才 break：收件箱里
                    // 还有注入行（这一步期间到达、还没被认领）就再走一步，让模型读到它 ——
                    // 于是「忙的时候注入」永远落在**这一轮**里，不需要靠下一轮的开头兜底。
                    if (repository.hasInjected(conversationId)) continue
                    settings.lastWorkspacePath = currentWorkspace?.root?.absolutePath
                    meter.persist(currentWorkspace?.shellRoot?.absolutePath)
                    return
                }

                // 走到这里 = 有可执行的调用、且 PTC 开着（见 [turnEndsAfterStep]），后者等价于
                // toolContext 非空 —— 取一次非空引用，别依赖编译器顺着 ptcEnabled 做智能转换
                val activeTools = checkNotNull(tools)

                addAssistantRow(
                    conversationId = conversationId,
                    answer = answer,
                    reasoning = reasoning,
                    toolCallsJson = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                        persisted,
                    ),
                )
                openRoundPersisted = false
                // 这一步（含工具调用）已经落库，**同时进日志**（dsh 的 assistant 步）：界面收到这条
                // 事件就在同一次状态更新里「从库里重读 + 清掉流式缓冲」—— 流式的正文/思考交给库里
                // 那一行，下一步的正文不会接在它们后面（旧实现里流式正文会一路把工具行挤下去）
                appendAssistantStep(log, answer, reasoning)

                // 执行这一步的调用。返回非空 = 死循环判据命中（模型调用失误），原因直接进日志与界面
                val stopReason = runToolCalls(conversationId, persisted, activeTools, log, guard, meter)
                if (stopReason != null) {
                    // 停下并把原因说清楚：不能让界面停在「看起来还在跑」的状态
                    meter.persist(currentWorkspace?.shellRoot?.absolutePath)
                    val stopped = stoppedText(stopReason)
                    addAssistantRow(conversationId, stopped, "")
                    appendAssistantStep(log, stopped, "", interrupted = true)
                    bus.trySend(ChatEvent.Stats(meter.stats()))
                    return
                }
                meter.setSteps(ToolStepCounter.drain())
                bus.trySend(ChatEvent.Stats(meter.stats()))
            }

        } catch (cancel: CancellationException) {
            // 打断：把已经生成的内容按 dsh 的 interrupted 语义落库（message.stopped「已停止」）。
            // 必须用 NonCancellable —— 协程已经被取消，普通挂起点会立刻再抛一次取消，
            // 结果是「按了停止但什么都没保存」（旧实现的 bug）。
            withContext(NonCancellable) {
                settings.lastWorkspacePath = currentWorkspace?.root?.absolutePath
                meter.persist(currentWorkspace?.shellRoot?.absolutePath)
                if (openRoundPersisted) {
                    val partial = answer.toString()
                    val partialReasoning = reasoning.toString()
                    val toolCalls = calls.values.mapNotNull { it.toToolCall() }
                    if (hasInterruptedContent(partial, partialReasoning, toolCalls.size)) {
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
     * 收这一步的流：正文 / 思考 / 工具调用参数分别进各自的累积器，事件**原样转发**给界面
     * （顺序就是到达顺序）；首字时延与 usage 记进 [meter]。
     *
     * [ChatEvent.StreamReset]（掉线重连）要把上一次尝试攒下的三样**全部作废** —— 重连 = 重新生成
     * 这一步，不清就会把两次尝试的输出首尾相接，拼出一段模型没说过的话。
     */
    private suspend fun collectRound(
        request: ChatRequest,
        bus: Channel<ChatEvent>,
        answer: StringBuilder,
        reasoning: StringBuilder,
        calls: LinkedHashMap<Int, CallAccumulator>,
        meter: TurnMeter,
    ) {
        llm.stream(request).collect { event ->
            when (event) {
                is ChatEvent.Delta -> {
                    meter.markFirstToken(System.currentTimeMillis())
                    answer.append(event.text)
                    bus.trySend(event)
                }
                is ChatEvent.Reasoning -> {
                    meter.markFirstToken(System.currentTimeMillis())
                    reasoning.append(event.text)
                    bus.trySend(event)
                }
                is ChatEvent.Usage -> {
                    // 一次请求只有一份 usage（dsh 的「一次 attempt 一个采样」）：
                    // 取最后一份，而不是把多个 chunk 加起来（网关重复上报时会把数字翻倍）
                    meter.onUsage(event)
                    bus.trySend(event)
                }
                is ChatEvent.ToolCallDelta -> {
                    // dsh 的 isTokenDelta：工具调用的参数分片（或带名字的那一片）**也算出字**
                    // （dsh-llm/lib/types/assistant-stream.js:185-195）。只认正文/思考的话，
                    // 「这一步只吐工具调用」的步在账本里没有首 token —— 首字时延显示为缺失，
                    // 解码窗口还会把整步都算进去（TPS 偏低）。
                    if (!event.argumentsChunk.isNullOrEmpty() || event.name != null) {
                        meter.markFirstToken(System.currentTimeMillis())
                    }
                    calls.getOrPut(event.index) { CallAccumulator() }.apply {
                        if (event.id != null) id = event.id
                        if (event.name != null) name = event.name
                        event.argumentsChunk?.let { args.append(it) }
                    }
                    bus.trySend(event)
                }
                // 掉线重连：上一次尝试攒下的正文 / 思考 / 工具调用参数全部作废
                // （重连 = 重新生成这一步，见 ChatEvent.StreamReset 的注释）。
                // 不清的话会把两次尝试的输出首尾相接，拼出一段模型没说过的话。
                is ChatEvent.StreamReset -> {
                    answer.setLength(0)
                    reasoning.setLength(0)
                    calls.clear()
                    meter.resetFirstToken()
                    bus.trySend(event)
                }
                is ChatEvent.Failed -> bus.trySend(event)
                else -> bus.trySend(event)
            }
        }
    }

    /**
     * 执行这一步模型要求的工具调用：每个调用先补一对日志（tool/call 必须在**真正执行之前**，
     * spec-log §5），再执行、再写 tool 结果与库行（先日志后落库：红点/结果不该等一次 SQLite 写入）。
     *
     * 返回非空 = **死循环判据命中**（模型调用失误，不是「任务很长」，见 [ToolCallGuard]）：
     * 调用方据此落一条「已停止」的消息收尾。判据命中时未开始的调用也要补一对 call + result，
     * 日志里不留悬空结果。
     */
    private suspend fun runToolCalls(
        conversationId: Long,
        calls: List<ToolCall>,
        tools: TurnTools,
        log: com.adsh.app.core.session.SessionLog,
        guard: ToolCallGuard,
        meter: TurnMeter,
    ): String? {
        for (call in calls) {
            currentCoroutineContext().ensureActive()
            val name = call.function.name ?: continue
            val argsJson = call.function.arguments ?: "{}"
                // 模型「调用失误」时的死循环：同一个工具 + 同一份参数反复来，或者这一步里的
                // 工具调用总数彻底失控 —— 立刻停，不要写出一条几 MB 的记录。
                // 签名与阈值见 [callSignature] / [loopGuardReason]（纯函数，有表）
                val why = guard.record(name, argsJson)
                if (why != null) {
                    // dsh 的「未开始就取消」路径：宿主补一对 call + result（spec-log §5.2），
                    // 日志里不留悬空的结果
                    val stoppedToken = EXEC_SEQ.incrementAndGet()
                    log.append(com.adsh.app.core.session.SessionBody.ToolCall(call.id, stoppedToken, name, argsJson))
                    log.append(com.adsh.app.core.session.SessionBody.ToolResult(call.id, stoppedToken, name, why, true))
                    return why
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
            // 端口：生产实现补身份与子调用回调后交给真实工具（见 ToolContextTools）
            val (output, isError) = tools.run(name, argsJson, call.id, execToken)
            val toolMillis = System.currentTimeMillis() - toolStarted
            meter.addToolMillis(toolMillis)
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
        return null
    }
    /**
     * 装配这一步的请求：历史（[RequestMessages.build]，含系统提示词与上下文注入节点）+
     * 思考档位（[com.adsh.app.core.data.Reasoning.wireEffort] 按模型就近取档 → [thinkingWire] 出字段）。
     *
     * `previousPath` 由调用方在**本轮开头**固定后传进来：装配会把 lastWorkspacePath 写回，
     * 不固定的话记录下来的系统提示词行会与实际请求不一致。
     * `ptcEnabled` = 有工具上下文（PTC 模式），决定 wire 上带不带 run_code 的 schema。
     */
    private suspend fun assembleRoundRequest(
        conversationId: Long,
        previousPath: String?,
        config: ProviderConfig,
        ptcEnabled: Boolean,
    ): ChatRequest {
        val messages = requestMessages.build(
            conversationId = conversationId,
            previousPath = previousPath,
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
        val effort = com.adsh.app.core.data.Reasoning.wireEffort(
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
            tools = if (ptcEnabled) listOf(com.adsh.app.core.tools.RunCodeTool.wireSchema) else null,
        )
    }

    /**
     * assistant 这一步**落库**：正文 + 思考（空思考写成 null），带工具调用时一并写 tool_calls。
     *
     * 「没内容就不写」的判据在调用方（[hasAssistantContent] / 打断路径的 [hasInterruptedContent]）——
     * 这里只负责**一条 assistant 行长什么样**，免得四个调用点各写一遍还各自走样。
     */
    private suspend fun addAssistantRow(
        conversationId: Long,
        answer: CharSequence,
        reasoning: CharSequence,
        toolCallsJson: String? = null,
    ) {
        repository.addMessage(
            conversationId = conversationId,
            role = "assistant",
            content = answer.toString(),
            reasoning = reasoning.toString().ifEmpty { null },
            toolCallsJson = toolCallsJson,
        )
    }

    /**
     * assistant 这一步**进日志**（dsh 的 assistant 步）：界面收到这条事件就在同一次状态更新里
     * 「从库里重读 + 清掉流式缓冲」—— 流式的正文/思考交给库里那一行，下一步的正文不会接在
     * 它们后面（旧实现里流式正文会一路把工具行挤下去）。
     *
     * 没有工具调用的最后一步同样要进（哪怕正文是空的）；打断文案是 `interrupted = true` 的同一形状。
     */
    private fun appendAssistantStep(
        log: com.adsh.app.core.session.SessionLog,
        text: CharSequence,
        reasoning: CharSequence,
        interrupted: Boolean = false,
    ) {
        log.append(
            com.adsh.app.core.session.SessionBody.Step(
                text = text.toString(),
                reasoning = reasoning.toString(),
                interrupted = interrupted,
            ),
        )
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

        /** 一条工具结果消息最多落库多少字符（默认 bash 输出上限是 128KiB，这里再兜一层） */
        const val MAX_TOOL_CONTENT_CHARS = 262_144


        /** 一步的子调用轨迹最多落库多少条（每条参数 / 结果的字符上限见 [SubCallTrimmer]） */
        const val MAX_SUB_CALLS_PER_STEP = 60
    }
}


