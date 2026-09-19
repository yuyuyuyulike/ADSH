package com.adsh.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.core.ptc.SubCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 对话流的节点模型 —— 对齐 dsh 的 Chat Node（dsh-client-ui-chat 的
 * conversation-nodes：user / context / system-prompt / assistant-step / tool-call /
 * compaction / turn-process / turn-tail）。
 *
 * 关键规则（dsh 的 turn-process-presentation.js）：
 *  - 「轮」= 一条用户消息到下一轮开始之间的全部 assistant/tool 节点；
 *  - 只有满足 **本轮已结束（turn closed）** 且 **最后一步是最终回答（没有工具调用）** 时，
 *    这一轮才折叠成一行「N 次工具调用 · M 条消息 / 已思考」，其余内容原样铺开；
 *  - 折叠边界是 answerAnchorSeq：放在它前面的过程节点被折叠，回答本身留在折叠行下面
 *    （compactAnswer：与折叠行的间距变成 8px）；
 *  - 因此「思考/工具调用之间冒出来的几句话」在轮次结束前一直inline 显示，
 *    不会被当成最终输出而把过程折叠掉。
 */
/**
 * 槽间距：列表不再用 Arrangement 的 spacedBy，而是每一行自己带一个上边距
 * （一轮拆成了多行，行与行之间的间距各不相同）。
 */
private val TURN_GAP = 16.dp

sealed interface ChatItem {
    /** 这一行上面要留的空隙（列表的 Arrangement 已经不带间距了） */
    val gap: Dp

    /** dsh 的 system-prompt 节点：系统提示词 / 系统提示词更新 */
    data class SystemPrompt(val key: Long, val text: String, val update: Boolean, override val gap: Dp = 0.dp) : ChatItem

    /** dsh 的 context 节点：上下文注入（指令文件 / goal / plan …） */
    data class Context(val key: Long, val label: String, val form: String, val text: String, override val gap: Dp = 0.dp) : ChatItem

    /**
     * 用户消息（dsh 的 user / steering 节点）：附件行 + 气泡 + 下方时间与复制按钮，
     * 时间取自消息落库时间。附件只存引用，界面按 dsh 的 .attachmentRow 渲染
     * （图片走画廊、文件走 fileCard），正文里不再有路径。
     */
    data class User(
        val key: Long,
        val text: String,
        val time: Long = 0L,
        val attachments: List<com.adsh.app.core.agent.UserAttachment> = emptyList(),
        override val gap: Dp = 0.dp,
    ) : ChatItem

    /** dsh 的 compaction 节点（/compact 检查点） */
    data class Compact(val key: Long, val text: String, override val gap: Dp = 0.dp) : ChatItem

    /**
     * 一轮对话拆成三种行（dsh 里一行就是一个 chat node）：
     * 折叠行 / 过程里的一条 / 轮尾。
     *
     * 拆开是因为**性能**：LazyColumn 只组合看得见的那几行。以前一轮是一个 item，
     * 展开「17 次工具调用 + 6 条消息」会把整轮（含工具行下面的子调用，上百行）
     * 全部组合 + 测量一遍，一次展开 150ms 以上，明显卡顿；
     * 拆开之后只有视口里的那几行参与组合，展开几乎是瞬间的。
     */
    data class TurnFold(val view: TurnView, val open: Boolean, override val gap: Dp) : ChatItem

    data class TurnEntry(
        val view: TurnView,
        val index: Int,
        override val gap: Dp,
    ) : ChatItem

    data class TurnTail(val view: TurnView, override val gap: Dp) : ChatItem
}

/** 一轮对话在界面上的形态 */
data class TurnView(
    val key: Long,
    /**
     * 过程条目（思考 / 过程文本 / 工具调用），按出现顺序。
     * **最终回答也在里面**（下标见 [answerIndex]）：dsh 的 assistant-step 节点始终只有一个，
     * 只是渲染位置不同；把回答从 entries 里摘出去会让同一段文字在「过程条目」与「最终回答」
     * 两棵子树之间重建节点（Compose 的身份变了），定稿那一帧就会闪一下、Markdown 也要重解析一遍。
     */
    val entries: List<ProcessEntry>,
    /** 最终回答在 [entries] 里的下标；-1 = 这一轮还没有最终回答（未结束，或最后一步仍带工具调用） */
    val answerIndex: Int = -1,
    val interrupted: Boolean = false,
    /** 本轮是否已结束（dsh 的 turn closed） */
    val closed: Boolean = false,
    val runMillis: Long? = null,
    /** 轮尾「在新对话中分支」用的分叉点：这一轮最后一条消息的 id（dsh 的 closing.finalNode.seq） */
    val lastMessageId: Long? = null,
    /** 本轮用量（dsh 的 turn tokenUsage）：轮尾「用量」按钮展开的明细 */
    val usage: TurnUsage? = null,
) {
    /** 最终回答的正文（dsh 的 latestAnswer） */
    val answer: String? get() = (entries.getOrNull(answerIndex) as? ProcessEntry.Text)?.text

    val toolCount: Int get() = entries.count { it is ProcessEntry.Call }

    /** dsh 的 messageCount：只数过程里的正文，不含最终回答 */
    val messageCount: Int
        get() = entries.filterIndexed { index, _ -> index != answerIndex }.count { it is ProcessEntry.Text }

    /** dsh 的 foldable：除了最终回答之外还有过程内容，才需要折叠行 */
    val foldable: Boolean get() = entries.size > (if (answerIndex >= 0) 1 else 0)
}

sealed interface ProcessEntry {
    data class Reasoning(val text: String, val running: Boolean) : ProcessEntry

    /**
     * 过程里的一段正文。正在流式生成的那一段，正文已经由 ChatScreen 的
     * `rememberSampledStreaming` 在**列表外面**采样过（见那里的注释），所以这里不再带 streaming 标记。
     */
    data class Text(val text: String) : ProcessEntry
    data class Call(
        val id: String?,
        val name: String,
        val arguments: String,
        val output: String? = null,
        val isError: Boolean = false,
        val subCalls: List<SubCall> = emptyList(),
        val running: Boolean = false,
        val durationMs: Long? = null,
    ) : ProcessEntry
}

private val turnJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 消息 JSON 的解析缓存。
 *
 * 流式期间每来一个 token 都会重建整个对话流（见 buildChatItems 的调用点），
 * 没有缓存的话每一帧都要把历史里每条消息的 toolCallsJson / subCallsJson / usageJson
 * 原样重新解析一遍，会话越长每帧的固定开销越大 —— 表现就是「越聊越卡」。
 * 解析结果只依赖那段 JSON 文本本身，所以按原文做 LRU 缓存即可（dsh 是把解析结果挂在
 * 会话节点上的，只有新增节点才解析，等价效果）。
 */
private object DecodeCache {
    private const val MAX_ENTRIES = 512
    private val entries = object : LinkedHashMap<String, Any>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean =
            size > MAX_ENTRIES
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, compute: () -> T?): T? {
        synchronized(entries) { entries[key]?.let { return it as T } }
        val value = compute() ?: return null
        synchronized(entries) { entries[key] = value }
        return value
    }
}

fun MessageEntity.decodeToolCalls(): List<ToolCall> = toolCallsJson?.let { raw ->
    DecodeCache.get("tc:" + raw) {
        runCatching { turnJson.decodeFromString(ListSerializer(ToolCall.serializer()), raw) }.getOrNull()
    }
}.orEmpty()

fun MessageEntity.decodeSubCalls(): List<SubCall> = subCallsJson?.let { raw ->
    DecodeCache.get("sc:" + raw) {
        runCatching { turnJson.decodeFromString(ListSerializer(SubCall.serializer()), raw) }.getOrNull()
    }
}.orEmpty()

/** 本轮用量（写在用户消息行上，见 AgentLoop.persistTurnUsage） */
fun MessageEntity.decodeUsage(): TurnUsage? = usageJson?.let { raw ->
    DecodeCache.get("us:" + raw) {
        runCatching { turnJson.decodeFromString(TurnUsage.serializer(), raw) }.getOrNull()
    }
}

/** 被打断的助手消息（AgentLoop 会把它标在 name 上） */
val MessageEntity.interrupted: Boolean get() = name == "interrupted"

/**
 * 把库里的消息 + 正在流式的状态整形成 dsh 那样的对话流。
 *
 * @param sending 本轮是否仍在跑 —— 决定最后一轮是否 closed（dsh 的 turn.status）
 */
fun buildChatItems(
    messages: List<MessageEntity>,
    streaming: String = "",
    reasoning: String = "",
    liveCalls: List<LiveCall> = emptyList(),
    liveSubCalls: List<SubCall> = emptyList(),
    sending: Boolean = false,
    /** 紧凑模式（设置 → 对话显示）：已完成轮次折成一行摘要 */
    compact: Boolean = true,
    /** 某一轮的折叠行是否被展开了（状态托管在界面上，列表要跟着重排） */
    foldOpen: (Long) -> Boolean = { false },
    /**
     * 正在生成的那一轮（= 它的用户消息 id）。
     *
     * dsh 里「哪一轮是活的」由事件流本身决定（turn/start 起、turn/end 止）。这里原来只看全局的
     * [sending]：排队消息刚发出、用户消息还没落库的那一两帧里，全局标志会把**上一轮**误判成
     * 「还在跑」，于是上一轮已经定稿的回答会先缩回过程里、再弹出来 —— 用户看到的就是
     * 「AI 在工具调用间插一段输出时整体很卡」。现在只有真正属于这一轮时才展开它。
     */
    liveTurnId: Long? = null,
): List<ChatItem> {
    val out = ArrayList<ChatItem>()
    var turn: TurnBuilder? = null

    /** 非轮次的行：列表已经不带间距了，自己带上与上一行之间的 16dp */
    fun gap(): Dp = if (out.isEmpty()) 0.dp else TURN_GAP

    /**
     * 一轮拆成若干行。间距逐条对齐原来的 TurnBody：
     * 折叠行 → 0、过程行（展开时 2dp / 平时 10dp）、回答（折叠时 8dp）、轮尾 0（TurnActions 自带 4dp 上边距）。
     */
    fun emitTurn(view: TurnView) {
        val folded = compact && view.answer != null && view.foldable
        val open = folded && foldOpen(view.key)
        var first = true
        if (folded) {
            out += ChatItem.TurnFold(view, open, if (first) TURN_GAP else 0.dp)
            first = false
        }
        view.entries.forEachIndexed { index, _ ->
            val isAnswer = index == view.answerIndex
            // 折叠起来时只留最终回答（dsh 的 answerAnchor：它前面的过程节点被折叠）
            if (folded && !open && !isAnswer) return@forEachIndexed
            val rowGap = when {
                first -> TURN_GAP
                folded && open -> 2.dp
                folded -> 8.dp
                else -> 10.dp
            }
            out += ChatItem.TurnEntry(view, index, gap = rowGap)
            first = false
        }
        out += ChatItem.TurnTail(view, if (first) TURN_GAP else 0.dp)
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
                flush(closed = true)
                val attachments = com.adsh.app.core.agent.decodeAttachments(message.attachmentsJson)
                // dsh 的 showBubble：**正文与附件至少有一个**才画这一行。
                // 这里以前只看 content —— 只发图片（不打字）时整行都不画：消息里看不到图片，
                // 而且这一轮没有 TurnBuilder，后面的助手消息会被算进**上一轮**。
                if (message.content.isNotBlank() || attachments.isNotEmpty()) {
                    if (message.name == ConversationRepository.COMPACT_MARKER) {
                        out += ChatItem.Compact(message.id, message.content, gap())
                    } else {
                        out += ChatItem.User(
                            key = message.id,
                            text = message.content,
                            time = message.createdAt,
                            attachments = attachments,
                            gap = gap(),
                        )
                        turn = TurnBuilder(message.id, message.createdAt, usage = message.decodeUsage())
                    }
                }
            }
            "assistant" -> {
                val builder = turn ?: TurnBuilder(message.id, message.createdAt).also { turn = it }
                builder.addAssistant(message)
            }
            "tool" -> turn?.attachResult(message)
            else -> Unit
        }
    }

    // 最后一轮：只有「正在生成的那一轮」才补流式内容（dsh 的 assistant/live-chunk）
    val open = turn
    if (open != null) {
        if (sending && liveTurnId != null && open.turnKey == liveTurnId) {
            open.addLive(
                streaming = streaming,
                reasoning = reasoning,
                liveCalls = liveCalls,
                liveSubCalls = liveSubCalls,
            )
            emitTurn(open.build(closed = false))
        } else {
            emitTurn(open.build(closed = true))
        }
    }
    return out
}

private class TurnBuilder(val key: Long, val startedAt: Long, val usage: TurnUsage? = null) {
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

    fun addAssistant(message: MessageEntity) {
        lastAt = message.createdAt
        lastMessageId = message.id
        message.reasoning?.takeIf { it.isNotBlank() }?.let { entries += ProcessEntry.Reasoning(it, running = false) }
        val calls = message.decodeToolCalls()
        val text = message.content
        if (text.isNotBlank()) {
            entries += ProcessEntry.Text(text)
            answerIndex = entries.lastIndex
        }
        lastStepHasCalls = calls.isNotEmpty()
        lastStepText = text
        lastStepInterrupted = message.interrupted
        calls.forEachIndexed { index, call ->
            entries += ProcessEntry.Call(
                id = call.id,
                name = call.function.name ?: "tool",
                arguments = call.function.arguments ?: "{}",
                subCalls = if (index == calls.lastIndex) message.decodeSubCalls() else emptyList(),
            )
        }
    }

    fun attachResult(message: MessageEntity) {
        lastAt = maxOf(lastAt, message.createdAt)
        lastMessageId = maxOf(lastMessageId, message.id)
        val id = message.toolCallId
        val index = entries.indexOfLast { it is ProcessEntry.Call && (id == null || it.id == id) }
        if (index < 0) return
        val call = entries[index] as ProcessEntry.Call
        entries[index] = call.copy(
            output = message.content,
            isError = message.isError,
            subCalls = call.subCalls.ifEmpty { message.decodeSubCalls() },
            durationMs = message.durationMs.takeIf { it > 0 },
        )
    }

    /**
     * 正在流式的这一步（dsh 的 assistant/live-chunk）。
     *
     * 关键：**只有本步的尾巴才有动效**。
     *  - 思考行的 running = 本步还没有正文、也没有工具调用
     *    （dsh 的 ReasoningRow：running = streaming && i === last，一旦后面接了正文或工具调用就停）；
     *  - 工具行先按 callId 在已落库的条目里找同一条来「贴上去」（AgentLoop 先把 assistant 步落库、
     *    再执行工具，所以同一轮里工具行本来就已经在消息里了），找不到才追加；
     *    这样流式正文不会把工具行越挤越远，也不会出现「同一次调用两行」。
     */
    fun addLive(
        streaming: String,
        reasoning: String,
        liveCalls: List<LiveCall>,
        liveSubCalls: List<SubCall> = emptyList(),
    ) {
        if (reasoning.isNotBlank()) {
            val tail = liveCalls.isEmpty() && streaming.isBlank()
            entries += ProcessEntry.Reasoning(reasoning, running = tail)
        }
        // 正在流式的正文（传进来的已经是采样过的文本，见 ChatScreen.rememberSampledStreaming）
        if (streaming.isNotBlank()) entries += ProcessEntry.Text(streaming)
        liveCalls.forEach { call ->
            val index = liveCallIndex(call)
            if (index >= 0) {
                // 已经落库的那一行：只把「运行中 / 输出 / 用时」贴上去
                val entry = entries[index] as ProcessEntry.Call
                entries[index] = entry.copy(
                    output = call.output ?: entry.output,
                    isError = call.isError,
                    subCalls = if (call.running) liveSubCalls else entry.subCalls,
                    running = call.running,
                    durationMs = call.durationMs ?: entry.durationMs,
                )
            } else {
                entries += ProcessEntry.Call(
                    id = call.callId,
                    name = call.name,
                    arguments = call.arguments,
                    output = call.output,
                    isError = call.isError,
                    subCalls = if (call.running || call.finishedAt != null) liveSubCalls else emptyList(),
                    running = call.running,
                    durationMs = call.durationMs,
                )
            }
        }
    }

    /**
     * 这条流式调用在**已落库条目**里对应的那一行。
     *
     * 先按 callId 精确匹配；网关没给 id 时退回「最后一条还没有结果、名字相同的匿名工具行」。
     * 没有这条退路的话，同一次调用会先以流式行的身份出现、落库后又多出一行，
     * 两帧之间多出来的行会让下面所有内容跳一下（也正是用户看到的「闪一下、像跳到该在的位置」）。
     */
    private fun liveCallIndex(call: LiveCall): Int {
        call.callId?.let { id ->
            val exact = entries.indexOfLast { it is ProcessEntry.Call && it.id == id }
            if (exact >= 0) return exact
        }
        return entries.indexOfLast {
            it is ProcessEntry.Call && it.id == null && it.name == call.name && it.output == null
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
            usage = usage,
        )
    }
}
