package com.adsh.app.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.seam.ptc.SubCall

/**
 * 把库里的消息 + 正在流式的状态整形成 dsh 那样的对话流。
 *
 * @param liveTurnId 哪一轮是活的（见下面那条）。为 null = 没有活的轮次，最后一轮按「已结束」渲染
 * @param reasoningRunning 本步最后流出来的东西是不是思考（dsh 的 ReasoningRow 的 running）
 */
fun buildChatItems(
    messages: List<MessageEntity>,
    streaming: String = "",
    reasoning: String = "",
    liveCalls: List<LiveCall> = emptyList(),
    /**
     * 正在跑的工具调用**这一刻**的 PTC 子调用轨迹。归属写在 `SubCall.id` 的前缀里
     * （`<父 harnessId>:ptc:<n>`），所以没有单独的「归属」参数，也没有 33ms 采样状态。
     */
    liveSubCalls: List<SubCall> = emptyList(),
    /**
     * 模型**正在写参数**的调用（dsh 的 streaming tool row / preparing 阶段）。
     * 它们还没落日志，所以只能接在 [liveCalls] 之后；摘要取已经写出来的 description。
     */
    pendingCalls: List<PendingCall> = emptyList(),
    reasoningRunning: Boolean = false,
    /** 紧凑模式（设置 → 对话显示）：已完成轮次折成一行摘要 */
    compact: Boolean = true,
    /** 某一轮的折叠行是否被展开了（状态托管在界面上，列表要跟着重排） */
    foldOpen: (Long) -> Boolean = { false },
    /** 哪些思考行的正文展开着（界面状态，键见 [reasoningKey]）—— 展开才把正文块发成独立行 */
    reasoningOpen: (String) -> Boolean = { false },
    /**
     * 线上（未采样）的流式正文**此刻是否非空** —— 决定「流式尾巴」要不要挂进列表。不能只看
     * [streaming]：它是 33ms 采样值，定稿那一帧可能还停在旧值上。这个标志与 messages 出自**同一次**
     * `_state.update`，所以「库行到位」与「尾巴关门」原子发生，不存在竞速窗口。
     */
    streamingActive: Boolean = true,
    /**
     * 线上（未采样）的思考尾巴此刻是否非空。
     *
     * 与 [streamingActive] 同一个理由，只是思考那一支的载体是 `state.reasoning`：思考也走
     * 33ms 的采样节流（见 ChatScreen 的 rememberStreamReveal），而定稿那一帧的采样值可能还停在
     * 某一段前缀上。这个标志与 messages 出自同一次状态更新，原子 —— 判据不看正文内容。
     */
    reasoningActive: Boolean = true,
    /**
     * 已提交、还没被认领的插话回显（dsh 的 pendingSubmissions）。
     *
     * 它们画在**当前这一轮的流尾**（过程条目之后、「运行中」之上）—— dsh 就是这么放的
     * （[...rows, ...pendingRows]），不按到达时间夹进工具行；认领落库之后由
     * [pendingSteeringToShow] 去掉，正式那一行正好落在同一个位置，所以视觉上是「原地转正」。
     */
    pendingSteering: List<PendingSteer> = emptyList(),
    /**
     * 正在生成的那一轮（= 它的用户消息 id）。
     *
     * dsh 里「哪一轮是活的」由事件流本身决定（turn/start 起、turn/end 止）。**不能只看全局的
     * `sending`**：排队消息刚发出、用户消息还没落库的那一两帧里，全局标志会把上一轮误判成
     * 「还在跑」，于是上一轮已经定稿的回答会先缩回过程里、再弹出来 —— 用户看到的就是
     * 「AI 在工具调用间插一段输出时整体很卡」。只有真正属于这一轮时才展开它。
     *
     * **判据就是它，不再看 `sending`**：按停止之后 `sending` 立刻变 false，而这一轮的流式内容
     * 还要等 AgentLoop 落库（几百毫秒）——那时若不再挂上去，思考行与正文会先消失、再从库里长回来
     * （停止时的闪烁）。`liveTurnId` 要等 finally 才清，正好覆盖这段收尾窗口。
     */
    liveTurnId: Long? = null,
    /** 本轮模型活动行（dsh 的 RunningStatus）：sending 时挂在列表最后 */
    runningStatus: Boolean = false,
    /** 本轮开始时刻（给 RunningStatus 的用时） */
    runStartedAt: Long = 0L,
): List<ChatItem> {
    val out = ArrayList<ChatItem>()
    var turn: TurnBuilder? = null

    /** 非轮次的行（系统提示 / 上下文 / 用户消息 / 压缩卡）：自己带上与上一行之间的默认 flow gap */
    fun gap(): Dp = if (out.isEmpty()) 0.dp else FLOW_GAP

    /**
     * 一轮拆成若干行，每一行的上边距按 dsh 的 flow gap 规则算（见 [FLOW_GAP]）：
     * 折叠行与它下面那一行 16dp、回答前后 12dp、其余过程行之间 6dp；轮尾跟在回答后面 12dp、
     * 否则 6dp（TurnActions 自己还带 4dp 上边距，两者相加就是 dsh 的 16px）。
     */
    fun emitTurn(view: TurnView) {
        val folded = compact && view.answer != null && view.foldable
        val open = folded && foldOpen(view.key)
        // 上一行是哪一类：0 = 本轮还没有行，1 = 折叠行，2 = 最终回答，3 = 其它过程行
        var previous = 0
        if (folded) {
            out += ChatItem.TurnFold(view, open, if (out.isEmpty()) 0.dp else TURN_GAP)
            previous = 1
        }
        view.entries.forEachIndexed { index, entry ->
            val isAnswer = index == view.answerIndex
            // 折叠起来时只留最终回答（dsh 的 answerAnchor：它前面的过程节点被折叠）
            if (folded && !open && !isAnswer) return@forEachIndexed
            val rowGap = when {
                out.isEmpty() -> 0.dp
                // 折叠行下面那一行：dsh 的 `[data-chat-flow-kind="turn-process"] + *` 规则是 16px
                previous == 1 -> TURN_GAP
                isAnswer || previous == 2 -> ANSWER_GAP
                else -> FLOW_GAP
            }
            // 长回答切成多块（见 [ChatItem.TurnAnswer]）：一块一个 item，于是只有视口里那几块
            // 参与组合 / 测量 / 首绘 —— 一整条回答做成一个 item 时，进视口那一帧要一次性做完
            // 上百个文本节点（真机实测 ~95ms 长帧，滚动时就是「卡一下再瞬移」）。
            // 短回答切不动，仍旧是 [ChatItem.TurnEntry] 一行：键、渲染、测试全都不变。
            val chunks = if (isAnswer && entry is ProcessEntry.Text) answerChunks(entry.text) else emptyList()
            if (chunks.size > 1) {
                chunks.forEachIndexed { chunkIndex, chunk ->
                    out += ChatItem.TurnAnswer(
                        view = view,
                        chunk = chunkIndex,
                        text = chunk,
                        hasPreceding = chunkIndex > 0,
                        hasFollowing = chunkIndex < chunks.lastIndex,
                        // 块与块之间的边距由两块自己带（见 MarkdownBody 的 hasPreceding/hasFollowing），
                        // 这里只有第一块吃行距
                        gap = if (chunkIndex == 0) rowGap else 0.dp,
                    )
                }
            } else {
                out += ChatItem.TurnEntry(view, index, gap = rowGap)
                // 思考正文展开时按块发成独立行（见 ChatItem.ReasoningBody）：头一行仍是 TurnEntry，
                // 行级绘制剔除因此管得到正文 —— 只有视口里的那几块参与绘制。
                if (entry is ProcessEntry.Reasoning && reasoningOpen(reasoningKey(view, index))) {
                    var before = ReasoningBoundary.NONE
                    val pieces = ReasoningChunkCache.chunks(entry.text)
                    pieces.forEachIndexed { chunk, piece ->
                        out += ChatItem.ReasoningBody(
                            view = view,
                            index = index,
                            chunk = chunk,
                            text = piece.text,
                            before = before,
                            after = piece.after,
                            // 只有最后一块可能还在长（dsh 的 running = 本块是最后一块）
                            streaming = entry.running && chunk == pieces.lastIndex,
                        )
                        before = piece.after
                    }
                }
            }
            previous = if (isAnswer) 2 else 3
        }
        out += ChatItem.TurnTail(
            view,
            when {
                out.isEmpty() -> 0.dp
                previous == 2 -> ANSWER_GAP
                else -> FLOW_GAP
            },
        )
    }

    fun flush(closed: Boolean) {
        val current = turn ?: return
        turn = null
        emitTurn(current.build(closed))
    }

    messages.forEach { message ->
        when (message.role) {
            "sysprompt" -> {
                flush(closed = true)
                out += ChatItem.SystemPrompt(message.id, message.content, message.name == "update", gap())
            }
            "context" -> {
                flush(closed = true)
                out += ChatItem.Context(
                    key = message.id,
                    label = message.name.orEmpty(),
                    form = message.subCallsJson.orEmpty(),
                    text = message.content,
                    gap = gap(),
                )
            }
            // dsh 的 command-input 节点：/goal 这类命令在会话里单独一行（不开启一轮对话）
            "command" -> {
                flush(closed = true)
                if (message.content.isNotBlank()) {
                    out += ChatItem.User(
                        key = message.id,
                        text = message.content,
                        time = message.createdAt,
                        attachments = emptyList(),
                        gap = gap(),
                    )
                }
            }
            "user" -> {
                val attachments = com.adsh.app.seam.model.decodeAttachments(message.attachmentsJson)
                // dsh 的 steering 节点：插话进来的消息属于**它插进去的那一轮** —— 不关这一轮、
                // 也不开新的一轮（把它当成新一轮的开头，正在跑的流式状态与工具行就会整体
                // 挪到它下面：用户看到「工具调用的展示被吞掉，过一会儿又蹦出来」）。
                //
                // 判据只看 name：它在落库时就带上了 STEERING，只有「这一轮没接住它」时才会被
                // ChatViewModel.continueIfDangling 抹掉（那时它真的成了下一轮的开头）。
                // 不看 liveTurnId：一轮跑完之后（sending=false、liveTurnId=null）这条消息仍然
                // 显示在它插进去的那一轮里，翻历史、重开 App 都是同一个位置。
                val steeringTurn = turn?.takeIf { message.name == ConversationRepository.STEERING }
                // 任务完成通知有两种形态（落库时就分开，渲染必须跨重启稳定，不能只看「现在有没有在跑」）：
                //  - 唤醒形态（JOB_NOTICE）：**它就是那一轮的开头** —— 走下面的 else，先关掉上一轮，
                //    为它建一个 TurnBuilder（唤醒那一轮的 identity 就是这条通知，见 startTurn 的 turnKey）。
                //    少了这一步，这一轮的流式正文 / 工具行 / 思考全都挂不上（真机上就是
                //    「工具展示不完整、思考消失、干完了才看到完整展示」）；
                //  - 注入形态（JOB_NOTICE_INJECTED）：落在正在跑的那一轮里的一行，不关轮、不开轮。
                val noticeTurn = turn?.takeIf { message.name == ConversationRepository.JOB_NOTICE_INJECTED }
                if (steeringTurn != null) {
                    if (message.content.isNotBlank() || attachments.isNotEmpty()) {
                        steeringTurn.addSteering(message, attachments)
                    }
                } else if (noticeTurn != null) {
                    noticeTurn.addNotice(message)
                } else {
                    flush(closed = true)
                    // dsh 的 showBubble：**正文与附件至少有一个**才画这一行。
                    // **不能只看 content**：只发图片（不打字）时整行都不画，消息里看不到图片，
                    // 而且这一轮没有 TurnBuilder，后面的助手消息会被算进**上一轮**。
                    if (message.content.isNotBlank() || attachments.isNotEmpty()) {
                        if (message.name == ConversationRepository.SANDBOX_SWITCH) {
                            // 权限预设切换通知：线上是一条 user 消息（模型要看到），界面按 dsh 的
                            // message.contextInjection 那样画成一行「上下文注入」，不开启一轮对话。
                            out += ChatItem.Context(
                                key = message.id,
                                label = "sandbox:policy",
                                form = "notice",
                                text = message.content,
                                gap = gap(),
                            )
                        } else if (message.name == ConversationRepository.JOB_NOTICE) {
                            // 唤醒形态：一行上下文注入 + 为它开一轮（这一轮的 identity 就是它）
                            out += ChatItem.Context(
                                key = message.id,
                                label = ConversationRepository.JOB_NOTICE,
                                form = "notice",
                                text = message.content,
                                gap = gap(),
                            )
                            // 用量与本轮改动也读这一行（AgentLoop 把唤醒轮写回它的轮首，见
                            // ConversationRepository.lastTurnOpener）：少了它们，唤醒那一轮的轮尾
                            // 就既没有用量按钮、也没有「已编辑 N 个文件」卡片。
                            turn = TurnBuilder(
                                key = message.id,
                                startedAt = message.createdAt,
                                usage = message.decodeUsage(),
                                changes = message.decodeFileChanges(),
                            )
                        } else if (message.name == ConversationRepository.JOB_NOTICE_INJECTED) {
                            // 注入形态、但此刻没有开着的轮（例如刚切到这个会话、历史末尾就是它）：
                            // 只画一行通知，**不开轮**（开了就是一轮空对话）
                            out += ChatItem.Context(
                                key = message.id,
                                label = ConversationRepository.JOB_NOTICE,
                                form = "notice",
                                text = message.content,
                                gap = gap(),
                            )
                        } else if (message.name == ConversationRepository.PLAN_MODE_NARRATION) {
                            // dsh plan-mode 的 narration（source = plan-mode、form = notice）：一条独立
                            // user 消息，界面画成上下文注入的 notice 行；**不开轮**、不算用户发言。
                            out += ChatItem.Context(
                                key = message.id,
                                label = ConversationRepository.PLAN_MODE_NARRATION,
                                form = "notice",
                                text = message.content,
                                gap = gap(),
                            )
                        } else if (message.name == ConversationRepository.COMPACT_MARKER) {
                            out += ChatItem.Compact(message.id, message.content, gap())
                        } else {
                            out += ChatItem.User(
                                key = message.id,
                                text = message.content,
                                time = message.createdAt,
                                attachments = attachments,
                                gap = gap(),
                            )
                            turn = TurnBuilder(
                                message.id,
                                message.createdAt,
                                usage = message.decodeUsage(),
                                changes = message.decodeFileChanges(),
                            )
                        }
                    }
                }
            }
            "assistant" -> {
                val builder = turn ?: TurnBuilder(message.id, message.createdAt).also { turn = it }
                builder.addAssistant(message)
            }
            "tool" -> turn?.attachResult(message)
            // 模型请求重试行（dsh 的 model-retry 节点）：只给界面看，不进模型请求
            "retry" -> turn?.addRetry(message)
            else -> Unit
        }
    }

    // 最后一轮：只有「正在生成的那一轮」才补流式内容（dsh 的 assistant/live-chunk）
    val open = turn
    if (open != null) {
        val live = liveTurnId != null && open.turnKey == liveTurnId
        if (live) {
            open.addLive(
                streaming = streaming,
                reasoning = reasoning,
                liveCalls = liveCalls,
                liveSubCalls = liveSubCalls,
                pendingCalls = pendingCalls,

                reasoningRunning = reasoningRunning,
                streamingActive = streamingActive,
                reasoningActive = reasoningActive,
            )
        }
        // 还没被认领的插话回显：挂在**这一轮的流尾**（dsh 的 [...rows, ...pendingRows]）。
        // 认领之后库里那一行会占据同一个位置（上一工具结果之后、下一步 assistant 之前），
        // 于是气泡「原地转正」，不会先画一处、认领后再跳到另一处。
        pendingSteeringToShow(pendingSteering, messages).forEach { echo ->
            open.addPendingSteering(
                text = echo.text,
                time = echo.time,
                attachments = com.adsh.app.seam.model.decodeAttachments(echo.attachmentsJson),
            )
        }
        emitTurn(open.build(closed = !live))
    }
    // dsh 的 RunningStatus 是 flow 的最后一行（ChatFlow: [...rows, ...running ? [<RunningStatus/>] : []]）
    if (runningStatus) out += ChatItem.Running(runStartedAt, if (out.isEmpty()) 0.dp else RUNNING_ROW_GAP)
    return out
}

/**
 * 从一组东西里收交付物：按顺序、同一个路径只留第一次（dsh 的 presentedForClosing 同语义）。
 * 两个重载分别对应「实时的子调用」与「已经落库的过程条目」。
 */
internal fun deliverablesOf(subCalls: List<SubCall>): List<com.adsh.app.seam.tool.PresentedFile> =
    com.adsh.app.seam.tool.collectDeliverables(
        subCalls.filter { it.ok }.map { it.deliverables },
    )

internal fun <T> deliverablesOf(
    items: List<T>,
    pick: (T) -> List<com.adsh.app.seam.tool.PresentedFile>,
): List<com.adsh.app.seam.tool.PresentedFile> =
    com.adsh.app.seam.tool.collectDeliverables(items.map(pick))

private class TurnBuilder(
    val key: Long,
    val startedAt: Long,
    val usage: TurnUsage? = null,
    val changes: List<com.adsh.app.seam.model.FileChange> = emptyList(),
) {
    /** 这一轮的身份 = 起始用户消息 id（与 buildChatItems 的 liveTurnId 比对） */
    val turnKey: Long get() = key

    private val entries = ArrayList<ProcessEntry>()
    private var lastMessageId: Long = key
    private var lastStepHasCalls = false
    private var lastStepText = ""
    private var lastStepInterrupted = false
    private var lastAt = startedAt
    /** 最终回答对应的条目下标（= -1 表示还没有回答） */
    private var answerIndex = -1
    /** dsh 的 closing：本轮最后一条**带正文**的 assistant 消息 id（分支按钮的可见 / 可用判据） */
    private var closingMessageId: Long? = null
    /**
     * 本会话**上一次** todo_write 的参数（dsh 的 todoHistory 投影）：每建一条 Call 就把它
     * 当作这一条的基准发下去，再用这条 Call 的子调用里的 todo_write 更新它。
     */
    private var lastTodoArgs: String? = null

    fun addAssistant(message: MessageEntity) {
        lastAt = message.createdAt
        lastMessageId = message.id
        message.reasoning?.takeIf { it.isNotBlank() }?.let { entries += ProcessEntry.Reasoning(it, running = false) }
        val calls = message.decodeToolCalls()
        val text = message.content
        if (text.isNotBlank()) {
            entries += ProcessEntry.Text(text)
            answerIndex = entries.lastIndex
            closingMessageId = message.id
        }
        lastStepHasCalls = calls.isNotEmpty()
        lastStepText = text
        lastStepInterrupted = message.interrupted
        calls.forEachIndexed { index, call ->
            val subCalls = if (index == calls.lastIndex) message.decodeSubCalls() else emptyList()
            entries += ProcessEntry.Call(
                id = call.id,
                name = call.function.name ?: "tool",
                arguments = call.function.arguments ?: "{}",
                subCalls = subCalls,
                todoBefore = lastTodoArgs,
            )
            trackTodo(subCalls)
        }
    }

    /** 把这次调用（父行 + 子调用）里最后的 todo_write 参数记成下一次的基准 */
    private fun trackTodo(subCalls: List<SubCall>) {
        subCalls.lastOrNull { it.name == "todo_write" || it.name == "todo" }?.let { lastTodoArgs = it.args }
    }

    /**
     * 插话（dsh 的 steering 节点）：作为本轮过程里的一行插进 [entries]。
     *
     * 它出现在「模型还没看到它」的那个位置 —— 落库的顺序就是它在会话流里的位置，
     * 所以直接追加到当前过程条目末尾即可（dsh 也一样：节点按 seq 排在过程节点之间）。
     */
    fun addSteering(message: MessageEntity, attachments: List<com.adsh.app.seam.model.UserAttachment>) {
        entries += ProcessEntry.Steering(
            text = message.content,
            time = message.createdAt,
            attachments = attachments,
        )
        lastAt = maxOf(lastAt, message.createdAt)
        lastMessageId = maxOf(lastMessageId, message.id)
    }

    /**
     * 一条**还没被认领**的插话回显（dsh 的 pendingSubmissions）。
     *
     * 与正式那一行（[addSteering]）画的是同一个气泡、同一个位置 —— 唯一的区别是它没有库里那一行
     * 的 id，所以**不动 [lastMessageId]**（那是轮次身份用的，不能被一个还没落库的 id 污染）。
     */
    fun addPendingSteering(
        text: String,
        time: Long,
        attachments: List<com.adsh.app.seam.model.UserAttachment>,
    ) {
        entries += ProcessEntry.Steering(text = text, time = time, attachments = attachments)
        lastAt = maxOf(lastAt, time)
    }

    /** 一条任务完成通知：与插话同一层（属于这一轮的过程条目），但渲染成上下文注入行 */
    fun addNotice(message: MessageEntity) {
        entries += ProcessEntry.Notice(message.content, message.createdAt)
        lastAt = maxOf(lastAt, message.createdAt)
        lastMessageId = maxOf(lastMessageId, message.id)
    }

    /**
     * 一条模型请求重试行（dsh 的 llm/retry 是会话日志里的事件，UI 按 seq 排进过程）。
     *
     * 它推进 [lastMessageId]：回答之后出现重试行会让「closing 是最后一条内容」不成立 ——
     * dsh 的 turn-tail 也把 `llm/retry` 的 seq 计入 latestTranscriptSeq（分支因此不可用）。
     */
    fun addRetry(message: MessageEntity) {
        lastAt = maxOf(lastAt, message.createdAt)
        lastMessageId = maxOf(lastMessageId, message.id)
        RetryInfoCodec.decode(message.content)?.let { entries += ProcessEntry.Retry(it) }
    }

    fun attachResult(message: MessageEntity) {
        lastAt = maxOf(lastAt, message.createdAt)
        lastMessageId = maxOf(lastMessageId, message.id)
        val id = message.toolCallId
        val index = entries.indexOfLast { it is ProcessEntry.Call && (id == null || it.id == id) }
        if (index < 0) return
        val call = entries[index] as ProcessEntry.Call
        val updated = call.copy(
            output = message.content,
            isError = message.isError,
            subCalls = call.subCalls.ifEmpty { message.decodeSubCalls() },
            durationMs = message.durationMs.takeIf { it > 0 },
            deliverables = message.decodeDeliverables(),
        )
        entries[index] = updated
        // 子调用（里面可能有 todo_write）是随工具结果一起到的，基准在这里补记
        trackTodo(updated.subCalls)
    }

    /**
     * 正在流式的这一步（dsh 的 assistant/live-chunk）。
     *
     * 关键：**只有本步的尾巴才有动效**。
     *  - 思考行的 running 由 [reasoningRunning] 决定（ChatViewModel 按「本步最后流出来的是不是
     *    思考」维护）：模型一开始写正文或工具调用，思考行立刻停 —— 摘要从「最后一行」变回
     *    「第一行」、扫光停掉。dsh 的规则就是 `running = streaming && i === last`：
     *    它看的是**块的位置**，不是「工具行出现没出现」；
     *  - 工具行按**顺序**认领已落库的那一行（见下面的注释），落库还没跟上时才追加；
     *    这样流式正文不会把工具行越挤越远，也不会出现「同一次调用两行」。
     */
    fun addLive(
        streaming: String,
        reasoning: String,
        liveCalls: List<LiveCall>,
        liveSubCalls: List<SubCall> = emptyList(),
        /** 还在写参数的调用（dsh 的 preparing 行）：接在已落日志的那几条后面 */
        pendingCalls: List<PendingCall> = emptyList(),

        reasoningRunning: Boolean = false,
        streamingActive: Boolean = true,
        reasoningActive: Boolean = true,
    ) {
        // 流式尾巴（dsh 的 live-chunk）：这一步还没定稿时才挂。
        // 判据只有一道门 —— [streamingActive] / [reasoningActive]：它们与 `messages` 出自**同一次**
        // 状态更新（收到 `SessionBody.Step` 时一边重读库、一边把流式缓冲清零），所以「库行到位」与
        // 「尾巴关门」是同一帧的事，判据**不需要看正文内容**。传进来的是采样过的文本（见 sampledStreaming）。
        if (reasoningActive && reasoning.isNotBlank()) {
            entries += ProcessEntry.Reasoning(reasoning, running = reasoningRunning)
        }
        if (streamingActive && streaming.isNotBlank()) {
            entries += ProcessEntry.Text(streaming, streaming = true)
        }
        // 身份 = **顺序**（dsh 的 cell 模型：日志里的顺序就是行的顺序）。落库的 assistant 消息里
        // 那串 tool_calls 与 harness 分配 execToken 的顺序一致（AgentLoop 先落库、再按同一列表执行），
        // 所以「第 k 条流式调用」就是「第 k 条工具行」。按顺序认领，不需要按 callId / 名字去猜，
        // 网关按每条响应从 call_0 重新编号也不会贴错行。
        val persistedCalls = entries.indices.filter { entries[it] is ProcessEntry.Call }
        liveCalls.forEachIndexed { nth, call ->
            val index = persistedCalls.getOrElse(nth) { -1 }
            // 这条调用自己的子行：id 前缀 = 它的宿主身份（`<harnessId>:ptc:`）。没有「采样值 vs
            // 定稿那份」的合并 —— 折叠结果本身就是定稿状态（结算事件按 id 覆盖同一行）；
            // 这一轮还没有它的子行时，退回库里那一份。
            val mine = liveSubCalls.filter { it.id.startsWith(call.prefix()) }
            val children = if (mine.isNotEmpty()) {
                mine
            } else {
                (entries.getOrNull(index) as? ProcessEntry.Call)?.subCalls.orEmpty()
            }
            // 这一轮正在进行时，present 的交付物来自**实时的子调用**（落库要等这一步结束）——
            // 卡片因此在模型还在这轮里跑的时候就已经出现在轮尾了
            val liveDeliverables = deliverablesOf(children)
            if (index >= 0) {
                // 已经落库的那一行：只把「运行中 / 输出 / 用时」贴上去
                val entry = entries[index] as ProcessEntry.Call
                entries[index] = entry.copy(
                    output = call.output ?: entry.output,
                    // **失败只进不退**：库里那一行已经按工具消息判成 error 之后，再拿一个还没收到结果
                    // 的折叠状态去覆盖就会把红点抹掉（要等这一轮收尾重读整表才回来）。判据取「或」：
                    // 库里已经定过稿的失败不会被改回成功。
                    isError = call.isError || entry.isError,
                    subCalls = children,
                    running = call.running,
                    durationMs = call.durationMs ?: entry.durationMs,
                    deliverables = entry.deliverables.ifEmpty { liveDeliverables },
                )
            } else {
                entries += ProcessEntry.Call(
                    id = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                    output = call.output,
                    isError = call.isError,
                    subCalls = children,
                    running = call.running,
                    durationMs = call.durationMs,
                    deliverables = liveDeliverables,
                    todoBefore = lastTodoArgs,
                )
            }
            // 实时的子调用里出现 todo_write 时，基准跟着往后走（本轮后面还可能再有一步）
            trackTodo(children)
        }
        // 参数还在流式生成的调用（dsh 的 preparing 行）：只有标题 + 已写出来的 description，
        // 不展开、没有子调用；落到日志之后由上面那一段接管（同一 ordinal 的行原地变成 running）
        pendingCalls.forEach { pending ->
            entries += ProcessEntry.Call(
                id = pending.id,
                name = if (pending.name.isEmpty()) "tool" else pending.name,
                arguments = pending.arguments,
                running = true,
                preparing = true,
                todoBefore = lastTodoArgs,
            )
        }
    }

    fun build(closed: Boolean): TurnView {
        // dsh 的 latestAnswer：最后一步必须「已定稿」且没有工具调用，才算最终回答。
        // 回答本身留在 entries 里，这里只记下标（渲染端用同一个 key 渲染它）。
        val finalAnswer = closed && !lastStepHasCalls && lastStepText.isNotBlank()
        return TurnView(
            key = key,
            entries = entries.toList(),
            answerIndex = if (finalAnswer) answerIndex else -1,
            interrupted = lastStepInterrupted,
            closed = closed,
            runMillis = if (closed) (lastAt - startedAt).coerceAtLeast(0) else null,
            lastMessageId = lastMessageId,
            closingMessageId = closingMessageId,
            lastStepHasCalls = lastStepHasCalls,
            usage = usage,
            changes = changes,
        )
    }
}
