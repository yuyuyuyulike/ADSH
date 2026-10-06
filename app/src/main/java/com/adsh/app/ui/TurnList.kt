package com.adsh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    /**
     * 本轮的文件改动（dsh 的 `workspace/changes`）：轮尾「已编辑 N 个文件 +A -B」那张卡片。
     * 落在轮首那条用户消息行上（与 [usage] 同处），一轮一条。
     */
    val changes: List<com.adsh.app.core.agent.FileChange> = emptyList(),
) {
    /**
     * 本轮声明过的交付物（dsh 的 ui-deliverables：presentedForClosing(turn)）。
     * 按工具行在轮内的顺序收集，同一个路径只留第一次；界面把它画在**这一轮的最下方**
     * （dsh 的 conversation.chat.turnTail 槽），点一张卡片进文件预览。
     * 从 [entries] 现算：交付物落在工具行上，没有单独的状态要维护。
     */
    val deliverables: List<com.adsh.app.core.agent.PresentedFile>
        get() = deliverablesOf(entries.filterIsInstance<ProcessEntry.Call>()) { it.deliverables }

    /** 最终回答的正文（dsh 的 latestAnswer） */
    val answer: String? get() = (entries.getOrNull(answerIndex) as? ProcessEntry.Text)?.text

    val toolCount: Int get() = entries.count { it is ProcessEntry.Call }

    /** dsh 的 messageCount：只数过程里的正文，不含最终回答 */
    val messageCount: Int
        get() = entries.filterIndexed { index, _ -> index != answerIndex }.count { it is ProcessEntry.Text }

    /** 这一轮里有没有插话（dsh 的 steering 节点） */
    val hasSteering: Boolean get() = entries.any { it is ProcessEntry.Steering }

    /**
     * dsh 的 foldable：除了最终回答之外还有过程内容，才需要折叠行。
     *
     * **有插话的一轮不折叠**：插话是用户自己说的话，折进「N 次工具调用」那一行里就看不见了。
     * dsh 对照的是 turn-process-presentation 的 `compactAnswer`：轮内出现 human 节点
     * （user / steering，且晚于本轮开头、早于回答锚点）时它被置 false —— 过程与回答都平铺展示。
     */
    val foldable: Boolean
        get() = !hasSteering && entries.size > (if (answerIndex >= 0) 1 else 0)
}

sealed interface ProcessEntry {
    data class Reasoning(val text: String, val running: Boolean) : ProcessEntry

    /**
     * 过程里的一段正文。正在流式生成的那一段，正文已经由 ChatScreen 的
     * `rememberSampledStreaming` 在**列表外面**采样过（见那里的注释），所以这里不再带 streaming 标记。
     */
    data class Text(val text: String) : ProcessEntry

    /**
     * 插话进来的用户消息（dsh 的 `steering` 节点）。
     *
     * 它**不是新一轮的开头**，而是正在跑的这一轮里的一行：dsh 的 next-step inbox 认领了这条
     * 消息之后，节点 kind 就是 steering，会话流里它夹在本轮的过程节点之间，这一轮的 turn
     * 状态不变。渲染与普通用户消息同一个气泡（dsh 里两者共用 UserStyleBubble）。
     */
    data class Steering(
        val text: String,
        val time: Long,
        val attachments: List<com.adsh.app.core.agent.UserAttachment> = emptyList(),
    ) : ProcessEntry

    /**
     * 后台任务的完成通知（dsh-tool-jobs 的 notice）：落在**正在跑的那一轮**里，画成一行上下文注入。
     *
     * dsh 里它本来就是 owner 的 next-step inbox 里一条消息、属于当前轨迹；不单独开一轮、也不关轮。
     */
    data class Notice(val text: String, val time: Long) : ProcessEntry

    data class Call(
        val id: String?,
        val name: String,
        val arguments: String,
        val output: String? = null,
        val isError: Boolean = false,
        val subCalls: List<SubCall> = emptyList(),
        val running: Boolean = false,
        val durationMs: Long? = null,
        /**
         * 这次调用声明的交付物（present 会有）—— 落在工具行上（dsh 的 deliverables/presented）。
         * 轮尾的文件卡片就是从这一轮的这些列表里收出来的（见 [TurnView.deliverables]）。
         */
        val deliverables: List<com.adsh.app.core.agent.PresentedFile> = emptyList(),
        /**
         * 本会话**上一次** todo_write 的参数：这条调用（或它下面的子调用）里的 todo_write
         * 拿它当差异基准（dsh 的 todoHistory 投影）。TurnBuilder 一路带着它走。
         */
        val todoBefore: String? = null,
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

private fun MessageEntity.decodeSubCalls(): List<SubCall> = subCallsJson?.let { raw ->
    DecodeCache.get("sc:" + raw) {
        runCatching { turnJson.decodeFromString(ListSerializer(SubCall.serializer()), raw) }.getOrNull()
    }
}.orEmpty()

/**
 * 工具行上的交付物（present 声明，dsh 的 deliverables/presented 事件）。
 * 与别的 JSON 列一样走 [DecodeCache]：流式期间每帧都会重建对话流。
 */
fun MessageEntity.decodeDeliverables(): List<com.adsh.app.core.agent.PresentedFile> =
    deliverablesJson?.let { raw ->
        DecodeCache.get("dl:" + raw) {
            runCatching {
                turnJson.decodeFromString(
                    ListSerializer(com.adsh.app.core.agent.PresentedFile.serializer()),
                    raw,
                )
            }.getOrNull()
        }
    }.orEmpty()

/** 本轮的文件改动（写在用户消息行上，见 TurnMeter.persist） */
fun MessageEntity.decodeFileChanges(): List<com.adsh.app.core.agent.FileChange> =
    com.adsh.app.core.agent.decodeFileChanges(changesJson)

/** 本轮用量（写在用户消息行上，见 TurnMeter.persist） */
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
     * （`<父 harnessId>:ptc:<n>`），所以没有单独的「归属」参数 —— 第 110 轮把它那份
     * 33ms 采样状态（StepTrace）连同读数一起删掉了。
     */
    liveSubCalls: List<SubCall> = emptyList(),
    reasoningRunning: Boolean = false,
    /** 紧凑模式（设置 → 对话显示）：已完成轮次折成一行摘要 */
    compact: Boolean = true,
    /** 某一轮的折叠行是否被展开了（状态托管在界面上，列表要跟着重排） */
    foldOpen: (Long) -> Boolean = { false },
    /**
     * 线上（未采样）的流式正文**此刻是否非空** —— 决定「流式尾巴」要不要挂进列表。
     *
     * 为什么不能只看 [streaming]（第 87 轮，用户实测的「报错的工具调用出现时闪一下、很大」）：
     * [streaming] 是 33ms 采样值，这一步定稿的那一帧它可能还停在旧值上（清零要等 snapshotFlow 的
     * 收集器醒来，与重组同帧竞速）。这个标志与 messages 出自**同一次** `_state.update`
     * （ChatViewModel 收到 `SessionBody.Step` 时一边重读库、一边清缓冲），重组时天然原子：
     * 库行到位的同一帧尾巴必定消失，不存在竞速窗口 —— 第 111 轮因此删掉了「拿正文逐字比对」
     * 那道补丁（它挡不住「采样值落后最后几个 token」）。
     */
    streamingActive: Boolean = true,
    /**
     * 线上（未采样）的思考尾巴此刻是否非空。
     *
     * 与 [streamingActive] 同一个理由，只是思考那一支的载体是 `state.reasoning`：思考也走
     * 33ms 的采样 + 平滑显现（见 ChatScreen 的 sampledReasoning），而定稿那一帧的采样值可能还停在
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
     * dsh 里「哪一轮是活的」由事件流本身决定（turn/start 起、turn/end 止）。这里原来只看全局的
     * `sending`：排队消息刚发出、用户消息还没落库的那一两帧里，全局标志会把**上一轮**误判成
     * 「还在跑」，于是上一轮已经定稿的回答会先缩回过程里、再弹出来 —— 用户看到的就是
     * 「AI 在工具调用间插一段输出时整体很卡」。现在只有真正属于这一轮时才展开它。
     *
     * **判据就是它，不再看 `sending`**：按停止之后 `sending` 立刻变 false，而这一轮的流式内容
     * 还要等 AgentLoop 落库（几百毫秒）——那时若不再挂上去，思考行与正文会先消失、再从库里长回来
     * （停止时的闪烁）。`liveTurnId` 要等 finally 才清，正好覆盖这段收尾窗口。
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
                val attachments = com.adsh.app.core.agent.decodeAttachments(message.attachmentsJson)
                // dsh 的 steering 节点：插话进来的消息属于**它插进去的那一轮** —— 不关这一轮、
                // 也不开新的一轮（旧实现把它当成新一轮的开头，正在跑的流式状态与工具行就会整体
                // 挪到它下面：用户看到「工具调用的展示被吞掉，过一会儿又蹦出来」）。
                //
                // 判据只看 name：它在落库时就带上了 STEERING，只有「这一轮没接住它」时才会被
                // ChatViewModel.continueIfDangling 抹掉（那时它真的成了下一轮的开头）。
                // 不看 liveTurnId：一轮跑完之后（sending=false、liveTurnId=null）这条消息仍然
                // 显示在原来那一轮里，翻历史、重开 App 都是同一个位置。
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
                    // 这里以前只看 content —— 只发图片（不打字）时整行都不画：消息里看不到图片，
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
                attachments = com.adsh.app.core.agent.decodeAttachments(echo.attachmentsJson),
            )
        }
        emitTurn(open.build(closed = !live))
    }
    return out
}

/**
 * 从一组东西里收交付物：按顺序、同一个路径只留第一次（dsh 的 presentedForClosing 同语义）。
 * 两个重载分别对应「实时的子调用」与「已经落库的过程条目」。
 */
private fun deliverablesOf(subCalls: List<SubCall>): List<com.adsh.app.core.agent.PresentedFile> =
    com.adsh.app.core.agent.collectDeliverables(
        subCalls.filter { it.ok }.map { it.deliverables },
    )

private fun <T> deliverablesOf(
    items: List<T>,
    pick: (T) -> List<com.adsh.app.core.agent.PresentedFile>,
): List<com.adsh.app.core.agent.PresentedFile> =
    com.adsh.app.core.agent.collectDeliverables(items.map(pick))

private class TurnBuilder(
    val key: Long,
    val startedAt: Long,
    val usage: TurnUsage? = null,
    val changes: List<com.adsh.app.core.agent.FileChange> = emptyList(),
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
    fun addSteering(message: MessageEntity, attachments: List<com.adsh.app.core.agent.UserAttachment>) {
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
        attachments: List<com.adsh.app.core.agent.UserAttachment>,
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

        reasoningRunning: Boolean = false,
        streamingActive: Boolean = true,
        reasoningActive: Boolean = true,
    ) {
        // 流式尾巴（dsh 的 live-chunk）：这一步还没定稿时才挂。
        //
        // 判据只有一道门 —— [streamingActive] / [reasoningActive]：它们与 `messages` 出自**同一次**
        // 状态更新（ChatViewModel 收到 `SessionBody.Step` 时，一边从库里重读、一边把流式缓冲清零），
        // 所以「这一步的库行到位」与「尾巴关门」是同一帧的事。
        //
        // 第 81 / 87 轮那两套「拿流式正文与库里那行逐字比对」（只看末尾两条 + 相等判据）在第 111 轮
        // 删掉了：它们是给「尾巴清零慢半拍」那个窗口打的补丁（采样节拍最多 33ms，采样值落后最后几个
        // token 时逐字判据也判不中）。现在窗口在结构上不存在，判据不需要看正文内容。
        // 传进来的已经是采样过的文本（见 ChatScreen 的 sampledStreaming）。
        if (reasoningActive && reasoning.isNotBlank()) {
            entries += ProcessEntry.Reasoning(reasoning, running = reasoningRunning)
        }
        if (streamingActive && streaming.isNotBlank()) {
            entries += ProcessEntry.Text(streaming)
        }
        // 身份 = **顺序**（dsh 的 cell 模型：日志里的顺序就是行的顺序）。
        //
        // 这一步落库的 assistant 消息里那串 tool_calls 与 harness 分配 execToken 的顺序完全一致
        // （AgentLoop 先落库、再按同一个列表执行），于是「这一轮第 k 条流式调用」就是「这一轮
        // 第 k 条工具行」。按顺序认领之后不再需要按 callId / 名字去猜的退路 —— 网关按每条响应
        // 从 call_0 重新编号时（第 101 轮「红点迟到」那个 bug）也不会贴错行。
        val persistedCalls = entries.indices.filter { entries[it] is ProcessEntry.Call }
        liveCalls.forEachIndexed { nth, call ->
            val index = persistedCalls.getOrElse(nth) { -1 }
            // 这条调用自己的子行：id 前缀 = 它的宿主身份（`<harnessId>:ptc:`）。
            // 第 109 轮起没有「采样值 vs 定稿那份」的合并 —— 折叠结果本身就是定稿状态
            // （结算事件按 id 覆盖同一行）；这一轮还没有它的子行时，退回库里那一份。
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
                    // **失败只进不退**（第 101 轮，用户实测「有的失败工具行左侧的红点要等这一轮
                    // 结束才出现」）：库里那一行已经按工具消息判成 error 之后，再拿一个还没收到
                    // 结果的折叠状态去覆盖就会把红点抹掉，而它要等这一轮收尾重读整表才回来。
                    // 判据取「或」：库里已经定过稿的失败不会被改回成功。
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
            changes = changes,
        )
    }
}

/**
 * 会话流的 **items 装配**（dsh 的 Chat Node 列表：库里的消息 + 正在流式的这一轮）。
 *
 * R20 从 `ChatScreen` 主函数搬出来 —— 判定体 [buildChatItems] 本来就已提纯，留下的是**接线**：
 * 十个 remember key、两个「尾巴原子门」、以及给它的折叠状态查询。三条口径要留着：
 *
 *  - 用 derivedStateOf（而不是 remember(各种 key)）：折叠状态是 SnapshotStateMap，读它就会被记账 ——
 *    某一轮展开/收起时这里自动重算，不需要再维护一个「修订号」手动把它踢醒；
 *  - [streamingActive] 读的是**线上**（未采样）的 `state.streaming`：它与 `state.messages` 出自同一次
 *    状态更新（ChatViewModel 收到 `SessionBody.Step` 时「从库里重读 + 清流式缓冲」一起做），于是
 *    「库行到位」与「尾巴关门」原子发生 —— 采样值落后最后几个 token 也不会把同一段正文挂两行
 *    （第 87 轮报错工具行「出现时很大」的根因，详见 addLive 的注释）；
 *  - 它只是个布尔，每 token 翻转不了几次，放进 key 不会破坏采样节流。
 */
@Composable
internal fun rememberChatItems(
    state: ChatUiState,
    /** 流式正文的采样值（[StreamReveal.text]）—— 采样状态挂在 ChatScreen 那一层，这里只读 */
    streaming: String,
    /** 流式思考的采样值（[StreamReveal.thinking]） */
    reasoning: String,
    /** dsh 的 compactTranscript：紧凑 = 已完成轮次折成一行摘要 */
    compact: Boolean,
    /** 折叠状态查询（哪些轮次的过程窗口被展开） */
    foldOpen: (Long) -> Boolean,
): List<ChatItem> {
    val streamingActive = state.streaming.isNotEmpty()
    /** 思考尾巴的原子门（与 [streamingActive] 同一个理由，见 addLive 的注释）：与 messages 同一次更新。 */
    val reasoningActive = state.reasoning.isNotEmpty()
    val items by remember(
        state.messages,
        streaming,
        streamingActive,
        reasoning,
        reasoningActive,
        state.reasoningRunning,
        state.liveTurn.calls,
        state.liveTurn.subCalls,
        state.liveTurnId,
        state.pendingSteering,
        compact,
    ) {
        derivedStateOf {
            buildChatItems(
                messages = state.messages,
                streaming = streaming,
                reasoning = reasoning,
                liveCalls = state.liveTurn.calls,
                liveSubCalls = state.liveTurn.subCalls,
                reasoningRunning = state.reasoningRunning,
                liveTurnId = state.liveTurnId,
                pendingSteering = state.pendingSteering,
                compact = compact,
                foldOpen = foldOpen,
                streamingActive = streamingActive,
                reasoningActive = reasoningActive,
            )
        }
    }
    return items
}

