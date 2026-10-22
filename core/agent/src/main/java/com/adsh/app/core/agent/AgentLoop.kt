package com.adsh.app.core.agent

import com.adsh.app.core.data.TurnSettings
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.TurnLlm
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionLog
import com.adsh.app.core.tools.ToolContext
import com.adsh.app.core.tools.ToolExecutor
import com.adsh.app.core.tools.ToolOutcome
import com.adsh.app.seam.model.TurnChangeTracker
import com.adsh.app.seam.model.UserAttachment
import com.adsh.app.seam.model.encodeAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
 *
 * 本文件只留**循环骨架**：请求装配在 [TurnContext]，流收集在 [StepStream]，
 * 工具轮在 [ToolRound]，assistant 行的形状在 [TurnRecords]。
 */
class AgentLoop(
    private val llm: TurnLlm,
    private val repository: TurnStore,
    private val settings: TurnSettings,
    /**
     * 会话日志不变量自检开关：由组合根传进去，**不能用库自己的 BuildConfig.DEBUG**
     * （side 变体对库的 matchingFallbacks 是 debug，库的 DEBUG 在 side 包里是 true，不等价）。
     */
    private val logDebug: Boolean,
    /**
     * 决策点（dsh 的 `agent/pre-step` / `agent/request-error`）。缺省 [AgentHooks.None]：
     * 不拦步、不接管错误 —— 主循环行为与没有决策点时逐字一致。
     */
    private val hooks: AgentHooks = AgentHooks.None,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** 工具执行流水线：PTC collapse / unknown tool / 参数解析 / 脱敏 / 异常归一化都在它里面做一次。 */
    private val toolExecutor = ToolExecutor(json = json)

    /** 请求装配（系统提示词 / 运行时上下文 / 历史 → wire 请求）。 */
    private val turnContext = TurnContext(store = repository, settings = settings, json = json)

    /** 一步的流收集。 */
    private val stepStream = StepStream(llm)

    /** 这一步的顶层工具轮（执行 + 日志 + 落库 + 记账）。 */
    private val toolRound = ToolRound(
        store = repository,
        json = json,
        nextExecToken = { EXEC_SEQ.incrementAndGet() },
    )

    /**
     * 在用户消息之前，把「系统提示词 / 上下文注入」按 dsh 的会话节点写进历史。
     * 落库规则见 [TurnContext.recordContext]。
     */
    suspend fun recordContext(
        conversationId: Long,
        workspace: com.adsh.app.core.workspace.Workspace,
        planMode: Boolean = false,
    ) = turnContext.recordContext(conversationId, workspace, planMode)

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
        // 单序事件总线（dsh 的 Session.append + 同步广播，见 packages/core/session/src/index.ts）：
        // 日志是这一轮的唯一真相，
        // 界面拿到的是它的**投影**；子调用回调从工具线程 append 进同一份日志，于是
        // 「回调比宿主登记这次调用更早到」这种竞速在结构上不存在（不需要任何归属状态去兜）。
        val bus = Channel<ChatEvent>(Channel.UNLIMITED)
        val log = SessionLog()
        // 日志 → 界面事件：**顺序就是 append 的顺序**（同步广播 + 通道 FIFO）
        log.observe { event -> bus.trySend(ChatEvent.Appended(event)) }
        val pump = launch { for (event in bus) send(event) }
        // 工具世界：测试给的替身优先；否则把 ToolContext 包成生产实现（子调用回调挂在同一份日志上）
        val activeTools = tools ?: toolContext?.let { ToolContextTools(it, log) }
        try {
            runTurnBody(conversationId, userText, activeTools, persistUser, attachments, log, bus)
        } finally {
            // 不变量自检（dsh 的会话日志不变式在 packages/core/session）：debug 包在真机上直接报「日志自己不自洽」，
            // 而不是让用户从界面上少一行去猜
            if (logDebug) {
                val bad = SessionLog.violations(log.events)
                if (bad.isNotEmpty()) {
                    // 纯 JVM 单测里 android.util.Log 是空壳（调用会抛「not mocked」），
                    // 而这里在 finally —— 抛出来会把真正的行为差异盖掉。单测侧另有一条
                    // 断言直接查 violations（AgentLoopGoldenTest.assertLogInvariants），
                    // 所以这里吞掉异常不等于「日志不自洽」没人发现。
                    runCatching { android.util.Log.w("ADSH", "session log invariant violation: " + bad.joinToString("; ")) }
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
        private val log: SessionLog,
    ) : TurnTools {
        override val workspace: com.adsh.app.core.workspace.Workspace get() = context.workspace

        override suspend fun run(name: String, argsJson: String, callId: String?, execToken: Long): ToolOutcome =
            toolExecutor.runDirect(
                name = name,
                rawArgs = argsJson,
                ctx = context.copy(
                    callId = callId,
                    execToken = execToken,
                    onSubCallStart = { sub -> log.append(SessionBody.PtcDispatchStart(sub)) },
                    onSubCall = { sub -> log.append(SessionBody.PtcDispatch(sub)) },
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
        log: SessionLog,
        bus: Channel<ChatEvent>,
    ) {
        turnContext.bindWorkspace(tools?.workspace)
        // 一轮开始：清掉上一轮没来得及收的文件改动留底（dsh 的 TurnRecorder 每轮一份）
        TurnChangeTracker.beginTurn()
        val config = settings.providerConfig()
        val ptcEnabled = tools != null
        // 名字非空的 user 行是系统通知（权限切换等），不是用户发的轮次
        val conversationTurns = repository.messages(conversationId).count { it.role == "user" && it.name == null }
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
            // **没有轮数上限**：dsh 的 agent-loop 设置里只有 maxParallelToolCalls 一项
            // （dsh-agent-loop 的 AGENT_LOOP_SETTINGS_SCHEMA 只有一个字段），
            // 它靠模型自己停下（没有工具调用就 return）+ 用户随时打断。
            // 不要加「单条消息往返轮数」上限（dsh 没有）：长任务不该被次数截断。
            //
            // 下面两条不是次数上限的替代品，而是**死循环判据**：同一组「工具 + 参数」
            // 重复 REPEAT_CALL_LIMIT 次、或单条消息的工具调用总数超过 MAX_TOOL_CALLS_PER_TURN
            // 时立刻停下 —— 这是「模型调用失误」而不是「任务很长」，两者的区别很重要。
            val guard = ToolCallGuard()
            // 每轮最多接受一次终止性失败恢复（dsh 的 maxOverflowRetries 默认 1）
            var requestErrorRetries = 0
            while (true) {
                // 打断后立刻停在这里，不要再发起下一轮
                // （继续跑完整轮会出现「退出重进后多出几条工具调用与思考」的现象）
                currentCoroutineContext().ensureActive()
                calls.clear()
                answer.setLength(0)
                reasoning.setLength(0)
                openRoundPersisted = true

                // dsh 的 preStep（决策点）：装配请求之前的最后一次改历史机会（自动压缩就在这里改）。
                // 放在认领收件箱之前：刚刚认领的插话必须原样进这一步的请求，不能被同一轮压缩带走。
                if (!hooks.beforeStep(conversationId) { event -> bus.trySend(event) }) {
                    // 本步不开：与正常收尾同一条路（保存用量、清理运行态），不再发请求
                    openRoundPersisted = false
                    meter.persist(turnContext.workspace?.shellRoot?.absolutePath)
                    return
                }

                // dsh 的 preStep：**先认领收件箱**（这一步期间到达的插话 / 任务通知），再装配请求。
                // 认领 = 此刻才落库，于是它们在库里的位置就是 dsh 日志里的位置（上一步的工具结果之后、
                // 这一步的 assistant 之前）。一行只写一次，界面不会再看着它换位置。
                if (repository.claimInjected(conversationId).isNotEmpty()) bus.trySend(ChatEvent.InboxClaimed)

                var request = turnContext.assembleRequest(
                    conversationId = conversationId,
                    config = config,
                    ptcEnabled = ptcEnabled,
                )
                var failure: ChatEvent.Failed?
                while (true) {
                    meter.beginRound(System.currentTimeMillis())
                    failure = stepStream.collect(request, bus, answer, reasoning, calls, meter)
                    meter.endRound(System.currentTimeMillis())
                    if (failure == null) break
                    // 终止性失败：先问决策点（dsh 的 request-error；压缩的 overflow 恢复挂在这里）。
                    // 只有它说「同一 step 内再试」才重做请求；每轮最多接受一次。
                    val retry = requestErrorRetries < MAX_REQUEST_ERROR_RETRIES &&
                        hooks.onRequestError(conversationId, failure) { event -> bus.trySend(event) }
                    if (!retry) break
                    requestErrorRetries++
                    // 重试 = 重新生成这一步：上一次尝试的增量作废；历史可能已被压缩，请求要重新装配
                    answer.setLength(0)
                    reasoning.setLength(0)
                    calls.clear()
                    meter.resetFirstToken()
                    request = turnContext.assembleRequest(
                        conversationId = conversationId,
                        config = config,
                        ptcEnabled = ptcEnabled,
                    )
                }
                // 终态失败原样透传（与没有决策点时同一位置：流的末尾），本轮按空 step 收尾
                if (failure != null) bus.trySend(failure)

                // 能落库的调用 = 有名字的那些（toToolCall 要求 name 非空）；网关截断出来的
                // 空列表不能照样往下写 —— 理由见 [turnEndsAfterStep]。
                val persisted = persistedToolCalls(calls.values)
                if (turnEndsAfterStep(persisted.size, ptcEnabled)) {
                    // 空回复不落库 —— 理由见 [hasAssistantContent]
                    if (hasAssistantContent(answer, reasoning)) {
                        TurnRecords.addAssistantRow(repository, conversationId, answer, reasoning)
                    }
                    openRoundPersisted = false
                    // 最后一步（没有工具调用）同样进日志：界面收到它就「从库里重读 + 清流式缓冲」，
                    // 流式的正文/思考交给库里那一行 —— 与带工具调用的那一步同一条路径（dsh 的 assistant 步）
                    TurnRecords.appendAssistantStep(log, answer, reasoning)
                    bus.trySend(ChatEvent.Stats(meter.stats()))
                    // dsh 的收尾判据是 `turnEnds && inbox.nextStep.length === 0` 才 break：收件箱里
                    // 还有注入行（这一步期间到达、还没被认领）就再走一步，让模型读到它 ——
                    // 于是「忙的时候注入」永远落在**这一轮**里，不需要靠下一轮的开头兜底。
                    if (repository.hasInjected(conversationId)) continue
                    meter.persist(turnContext.workspace?.shellRoot?.absolutePath)
                    return
                }

                // 走到这里 = 有可执行的调用、且 PTC 开着（见 [turnEndsAfterStep]），后者等价于
                // toolContext 非空 —— 取一次非空引用，别依赖编译器顺着 ptcEnabled 做智能转换
                val activeTools = checkNotNull(tools)

                TurnRecords.addAssistantRow(
                    store = repository,
                    conversationId = conversationId,
                    answer = answer,
                    reasoning = reasoning,
                    toolCallsJson = json.encodeToString(
                        ListSerializer(ToolCall.serializer()),
                        persisted,
                    ),
                )
                openRoundPersisted = false
                // 这一步（含工具调用）已经落库，**同时进日志**（dsh 的 assistant 步）：界面收到这条
                // 事件就在同一次状态更新里「从库里重读 + 清掉流式缓冲」—— 流式的正文/思考交给库里那一行，
                // 下一步的正文不会接在它们后面（流式正文不能一路把工具行挤下去）
                TurnRecords.appendAssistantStep(log, answer, reasoning)

                // 执行这一步的调用。stopReason 非空 = 死循环判据命中（模型调用失误），原因直接进日志与界面
                val toolResult = toolRound.run(conversationId, persisted, activeTools, log, guard, meter)
                if (toolResult.stopReason != null) {
                    // 停下并把原因说清楚：不能让界面停在「看起来还在跑」的状态
                    meter.persist(turnContext.workspace?.shellRoot?.absolutePath)
                    val stopped = stoppedText(toolResult.stopReason)
                    TurnRecords.addAssistantRow(repository, conversationId, stopped, "")
                    TurnRecords.appendAssistantStep(log, stopped, "", interrupted = true)
                    bus.trySend(ChatEvent.Stats(meter.stats()))
                    return
                }
                meter.setSteps(toolResult.steps)
                bus.trySend(ChatEvent.Stats(meter.stats()))
            }

        } catch (cancel: CancellationException) {
            // 打断：把已经生成的内容按 dsh 的 interrupted 语义落库（message.stopped「已停止」）。
            // 必须用 NonCancellable —— 协程已经被取消，普通挂起点会立刻再抛一次取消，
            // 结果是「按了停止但什么都没保存」。
            withContext(NonCancellable) {
                meter.persist(turnContext.workspace?.shellRoot?.absolutePath)
                if (openRoundPersisted) {
                    val partial = answer.toString()
                    val partialReasoning = reasoning.toString()
                    val toolCalls = persistedToolCalls(calls.values)
                    if (hasInterruptedContent(partial, partialReasoning, toolCalls.size)) {
                        repository.addMessage(
                            conversationId = conversationId,
                            role = "assistant",
                            content = partial,
                            reasoning = partialReasoning.ifEmpty { null },
                            toolCallsJson = if (toolCalls.isEmpty()) null else json.encodeToString(
                                ListSerializer(ToolCall.serializer()),
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

    companion object {
        /**
         * 顶层工具调用的身份发生器（dsh 里缺 id 的 tool_call 由宿主补 `call-<index>`，
         * 见 `packages/llm/llm/src/assembler.ts`）。
         *
         * 进程内单调、**不按轮重置**：PTC 子调用的 id 是 `<execToken>:ptc:<n>`，跨轮重号会让
         * 「这条子行属于哪一次顶层调用」这种问题在换轮时重新冒出来。模型给的 `callId` 不能当
         * 身份 —— 有些 OpenAI 兼容网关每条响应
         * 都从 `call_0` 重新编号。
         */
        private val EXEC_SEQ = java.util.concurrent.atomic.AtomicLong(0)

        /** assistant 行上的标记：这一轮的输出是被打断的（dsh 的 interrupted） */
        const val INTERRUPTED = "interrupted"

        /** 一条工具结果消息最多落库多少字符（默认 bash 输出上限是 128KiB，这里再兜一层） */
        const val MAX_TOOL_CONTENT_CHARS = 262_144

        /** 一步的子调用轨迹最多落库多少条（每条参数 / 结果的字符上限见 [SubCallTrimmer]） */
        const val MAX_SUB_CALLS_PER_STEP = 60

        /** 每轮最多接受几次终止性失败的恢复重试（dsh 的 maxOverflowRetries 默认 1）。 */
        const val MAX_REQUEST_ERROR_RETRIES = 1
    }
}
