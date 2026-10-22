package com.adsh.app.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.seam.model.decodeFileChanges
import com.adsh.app.seam.ptc.SubCall
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
 * 会话流的行距，逐条照 dsh 的 ChatView.module.css（列表不用 Arrangement.spacedBy：一轮拆成多行，
 * 每行的上边距各不相同，间距由「后一行自己的样式」决定）：
 *  - [FLOW_GAP] 6px：默认值 —— 过程行之间、用户消息 / 系统提示 / 上下文 / 轮尾之间；
 *  - [ANSWER_GAP] 12px：最终回答（`data-chat-group-part="response"`）与它后面那一行；
 *  - [TURN_GAP] 16px：轮次折叠行（`data-chat-flow-kind="turn-process"`）与它下面那一行。
 */
internal val FLOW_GAP = 6.dp
internal val ANSWER_GAP = 12.dp
internal val TURN_GAP = 16.dp

/** dsh 的 RunningStatus 与上面一行的间距：ChatView.module.css 的 `.running { --dsh-chat-flow-gap: 12px }` */
internal val RUNNING_ROW_GAP = 12.dp

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
        val attachments: List<com.adsh.app.seam.model.UserAttachment> = emptyList(),
        override val gap: Dp = 0.dp,
    ) : ChatItem

    /** dsh 的 compaction 节点（/compact 检查点） */
    data class Compact(val key: Long, val text: String, override val gap: Dp = 0.dp) : ChatItem

    /**
     * 一轮对话拆成三种行（dsh 里一行就是一个 chat node）：
     * 折叠行 / 过程里的一条 / 轮尾。
     *
     * 拆开是因为**性能**：LazyColumn 只组合看得见的那几行。**不能把整轮做成一个 item**：
     * 展开「17 次工具调用 + 6 条消息」会把整轮（含工具行下面的子调用，上百行）
     * 全部组合 + 测量一遍，一次展开 150ms 以上，明显卡顿；
     * 只有视口里的那几行参与组合，展开几乎是瞬间的。
     */
    data class TurnFold(val view: TurnView, val open: Boolean, override val gap: Dp) : ChatItem

    data class TurnEntry(
        val view: TurnView,
        val index: Int,
        override val gap: Dp,
    ) : ChatItem

    /**
     * 最终回答的**一块**（[answerChunks] 的切片）：一块一个会话流行。长回答必须切 —— 整条
     * 当一个 item 时，进视口那一帧要一次性组合 + 测量 + 首绘上百个文本节点，真机一帧 ≈ 95ms
     * （60Hz 只有 16.7ms）。切法见 [answerChunks]：只在「上下都是普通段落」的空行上切，视觉一致。
     */
    data class TurnAnswer(
        val view: TurnView,
        /** 回答里的第几块（0 起） */
        val chunk: Int,
        /** 这一块的正文 */
        val text: String,
        /** 上面还有内容（首块保留自己的上边距） */
        val hasPreceding: Boolean,
        /** 下面还有内容（末块保留自己的下边距） */
        val hasFollowing: Boolean,
        override val gap: Dp,
    ) : ChatItem

    /**
     * 思考正文的**一块**（[reasoningChunks] 的切片）：思考的头仍是 [TurnEntry] 一行，正文按块拆成
     * 独立行。
     *
     * 为什么必须拆：可见性剔除（ChatTranscript 的 `CulledRow`）是**行**粒度的 —— 整段正文塞在头
     * 那一行里时，只要露出一像素就整段参与绘制；而滚动 / 键盘 / 抽屉每一帧都要重画可见内容，
     * 帧成本因此正比于整段文本量（真机上大段思考展开后掉帧）。
     */
    data class ReasoningBody(
        val view: TurnView,
        /** 哪一条思考（[TurnView.entries] 的下标） */
        val index: Int,
        /** 正文里的第几块（0 起） */
        val chunk: Int,
        /** 这一块的正文 */
        val text: String,
        /** 这一块的上边界（与上一块之间的切点） */
        val before: ReasoningBoundary,
        /** 这一块的下边界（与下一块之间的切点；末块是 [ReasoningBoundary.NONE]） */
        val after: ReasoningBoundary,
        /** 这一块还在流式生成（思考行 running 且这是最后一块）：MarkdownBody 走增量解析 */
        val streaming: Boolean = false,
        override val gap: Dp = 0.dp,
    ) : ChatItem

    data class TurnTail(val view: TurnView, override val gap: Dp) : ChatItem

    /**
     * dsh 的 RunningStatus（chat/RunningStatus.tsx）：**会话流末尾的最后一行**，跟着内容一起滚 ——
     * 不是输入框上方的浮层（浮层会落在 footer 顶部 36dp 的渐变带里，文字看着是透明的）。
     * 只在 sending 时挂出来；[startedAt] 给右侧用时。
     */
    data class Running(val startedAt: Long, override val gap: Dp) : ChatItem
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
    /** 这一轮最后一条消息行的 id（assistant / steering / notice / tool result 都会推进它） */
    val lastMessageId: Long? = null,
    /**
     * 本轮最后一条**带正文**的 assistant 消息 id（dsh 的 closing.finalNode.messageId）。
     * 分支按钮的可见判据：null = 这一轮没有可收尾的回答，dsh 连动作行都不画。
     */
    val closingMessageId: Long? = null,
    /** 最后一步 assistant 是否还带 tool_calls（同一行里 text + tool_calls 时不算收尾回答） */
    val lastStepHasCalls: Boolean = false,
    /** 本轮用量（dsh 的 turn tokenUsage）：轮尾「用量」按钮展开的明细 */
    val usage: TurnUsage? = null,
    /**
     * 本轮的文件改动（dsh 的 `workspace/changes`）：轮尾「已编辑 N 个文件 +A -B」那张卡片。
     * 落在轮首那条用户消息行上（与 [usage] 同处），一轮一条。
     */
    val changes: List<com.adsh.app.seam.model.FileChange> = emptyList(),
) {
    /**
     * 本轮声明过的交付物（dsh 的 ui-deliverables：presentedForClosing(turn)）。
     * 按工具行在轮内的顺序收集，同一个路径只留第一次；界面把它画在**这一轮的最下方**
     * （dsh 的 conversation.chat.turnTail 槽），点一张卡片进文件预览。
     * 从 [entries] 现算：交付物落在工具行上，没有单独的状态要维护。
     */
    val deliverables: List<com.adsh.app.seam.tool.PresentedFile>
        get() = deliverablesOf(entries.filterIsInstance<ProcessEntry.Call>()) { it.deliverables }

    /** 最终回答的正文（dsh 的 latestAnswer） */
    val answer: String? get() = (entries.getOrNull(answerIndex) as? ProcessEntry.Text)?.text

    /**
     * 轮尾分支按钮的可见性（dsh 的 TurnTailNodeView：closing === null 时整行 actions 不画）。
     * 只有已结束、且本轮存在一条带正文的 assistant 收尾回答时才可见。
     */
    val branchVisible: Boolean get() = closed && closingMessageId != null

    /**
     * 分支是否可用 —— dsh 的 `branchUnavailable` 取反再加上视图层的 `hasLaterChatNode`：
     * closing 必须**同时是本轮 transcript 的最后一条内容**。回答之后还有工具结果 / 插话 /
     * 通知行（[lastMessageId] 被推后），或最后一步还带 tool_calls，都不允许从这个轮尾分叉。
     */
    val branchEnabled: Boolean
        get() = branchVisible && closingMessageId == lastMessageId && !lastStepHasCalls

    /** 分叉点：收尾回答那一行（dsh 传 turn/end 的 seq；ADSH 没有事件日志，只能按消息行截断） */
    val branchMessageId: Long? get() = closingMessageId?.takeIf { branchEnabled }

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
     * `rememberSampledStreaming` 在**列表外面**采样过（见那里的注释）；[streaming] 只表示
     * 「这条尾巴还在长」—— MarkdownBody 据此走增量块解析（定稿的库行是 false、走一次全量解析）。
     */
    data class Text(val text: String, val streaming: Boolean = false) : ProcessEntry

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
        val attachments: List<com.adsh.app.seam.model.UserAttachment> = emptyList(),
    ) : ProcessEntry

    /**
     * 后台任务的完成通知（dsh-tool-jobs 的 notice）：落在**正在跑的那一轮**里，画成一行上下文注入。
     *
     * dsh 里它本来就是 owner 的 next-step inbox 里一条消息、属于当前轨迹；不单独开一轮、也不关轮。
     */
    data class Notice(val text: String, val time: Long) : ProcessEntry

    /**
     * 一条模型请求重试行（dsh 的 model-retry 节点）：它也是本轮过程里的一条，
     * 位置由落库顺序决定 —— 在失败的那一步之后、重试成功的那一步之前。
     */
    data class Retry(val info: RetryInfo) : ProcessEntry

    data class Call(
        val id: String?,
        val name: String,
        val arguments: String,
        val output: String? = null,
        val isError: Boolean = false,
        val subCalls: List<SubCall> = emptyList(),
        val running: Boolean = false,
        /**
         * dsh 的 preparing 阶段：模型还在写这次调用的参数（这一行只有标题 + 已写出的 description）。
         * 不可展开、没有子调用；摘要见 TurnRail 的 preparingSummaryOf。
         */
        val preparing: Boolean = false,
        val durationMs: Long? = null,
        /**
         * 这次调用声明的交付物（present 会有）—— 落在工具行上（dsh 的 deliverables/presented）。
         * 轮尾的文件卡片就是从这一轮的这些列表里收出来的（见 [TurnView.deliverables]）。
         */
        val deliverables: List<com.adsh.app.seam.tool.PresentedFile> = emptyList(),
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

internal fun MessageEntity.decodeSubCalls(): List<SubCall> = subCallsJson?.let { raw ->
    DecodeCache.get("sc:" + raw) {
        runCatching { turnJson.decodeFromString(ListSerializer(SubCall.serializer()), raw) }.getOrNull()
    }
}.orEmpty()

/**
 * 工具行上的交付物（present 声明，dsh 的 deliverables/presented 事件）。
 * 与别的 JSON 列一样走 [DecodeCache]：流式期间每帧都会重建对话流。
 */
fun MessageEntity.decodeDeliverables(): List<com.adsh.app.seam.tool.PresentedFile> =
    deliverablesJson?.let { raw ->
        DecodeCache.get("dl:" + raw) {
            runCatching {
                turnJson.decodeFromString(
                    ListSerializer(com.adsh.app.seam.tool.PresentedFile.serializer()),
                    raw,
                )
            }.getOrNull()
        }
    }.orEmpty()

/** 本轮的文件改动（写在用户消息行上，见 TurnMeter.persist） */
fun MessageEntity.decodeFileChanges(): List<com.adsh.app.seam.model.FileChange> =
    com.adsh.app.seam.model.decodeFileChanges(changesJson)

/** 本轮用量（写在用户消息行上，见 TurnMeter.persist） */
fun MessageEntity.decodeUsage(): TurnUsage? = usageJson?.let { raw ->
    DecodeCache.get("us:" + raw) {
        runCatching { turnJson.decodeFromString(TurnUsage.serializer(), raw) }.getOrNull()
    }
}

/** 被打断的助手消息（AgentLoop 会把它标在 name 上） */
val MessageEntity.interrupted: Boolean get() = name == "interrupted"

/**
 * 一条长回答切成几块的目标字符数（下限：切点还要落在下一个「安全的空行」上，所以实际块通常更大）。
 *
 * 取值依据：一次组合 + 测量 + 首绘的代价随这一块里的文本节点数增长，60Hz 一帧的预算是 16.7ms。
 */
internal const val ANSWER_CHUNK_CHARS = 320

/**
 * 把一条长回答切成若干块，一块一个 LazyColumn item（见 [ChatItem.TurnAnswer]）。
 *
 * 三条口径：
 *  - **前缀稳定**：从开头贪心累加，追加正文（流式）只会让最后一块变长，前面的块逐字不变 ——
 *    否则流式期间每 33ms 重算一次切点，行 key 一抖就会整块重建；
 *  - **不切进围栏代码块**：``` / ~~~ 之间的空行不算切点（切开会把代码块劈成两半）；
 *  - **只在「上下都是普通段落」的空行上切**：切点两侧的块各自带上自己的上/下边距
 *    （[ChatItem.TurnAnswer.hasPreceding] / [ChatItem.TurnAnswer.hasFollowing]），间距与整段渲染
 *    逐像素一致。标题 / 列表 / 引用 / 表格 / 缩进代码这些块的上边距各不相同，索性不在它们旁边切。
 *
 * 切不动（没找到安全的空行、或本身就短）时返回单元素列表 —— 调用方据此退回
 * [ChatItem.TurnEntry] 一行。
 */
internal fun answerChunks(text: String, budget: Int = ANSWER_CHUNK_CHARS): List<String> {
    if (text.length <= budget * 2) return listOf(text)
    val lines = text.split('\n')
    // nextNonBlank[i] = i 之后第一条非空行的下标（-1 = 没有）
    val nextNonBlank = IntArray(lines.size)
    var next = -1
    for (i in lines.indices.reversed()) {
        nextNonBlank[i] = next
        if (lines[i].isNotBlank()) next = i
    }
    val out = ArrayList<String>()
    var start = 0
    var fence = false
    var lastNonBlank = -1
    var chars = 0
    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) fence = !fence
        chars += line.length + 1
        if (line.isNotBlank()) {
            lastNonBlank = i
            continue
        }
        if (fence || chars < budget) continue
        if (!isPlainParagraphLine(lines.getOrNull(lastNonBlank))) continue
        val after = nextNonBlank[i]
        if (after < 0 || !isPlainParagraphLine(lines[after])) continue
        out += lines.subList(start, i).joinToString("\n")
        start = i + 1
        chars = 0
    }
    if (out.isEmpty()) return listOf(text)
    out += lines.subList(start, lines.size).joinToString("\n")
    return out
}

/**
 * 「普通段落行」：不是块标记（标题 / 引用 / 列表 / 表格 / 围栏 / 缩进代码 / 图片 / 分隔线 / 公式）
 * 开头的行。只有这种行**旁边**的空行才是安全的切点（见 [answerChunks]）。
 */
private fun isPlainParagraphLine(line: String?): Boolean {
    if (line.isNullOrBlank()) return false
    if (line != line.trimStart()) return false
    if (line[0] in "#>-*+|!<`~=") return false
    if (line[0].isDigit()) {
        var i = 0
        while (i < line.length && line[i].isDigit()) i++
        if (i < line.length && (line[i] == '.' || line[i] == ')') &&
            (i + 1 >= line.length || line[i + 1] == ' ')
        ) {
            return false
        }
    }
    return true
}

/** 思考正文一块的边界类型：决定相邻两块之间的块间距口径（见 [ChatItem.ReasoningBody]）。 */
enum class ReasoningBoundary {
    /** 没有相邻块（首块的上边界 / 末块的下边界） */
    NONE,

    /** 块边界（空行）：两侧各自保留块间距 = 整段渲染时的间距 */
    BLOCK,

    /** 行边界（兜底硬切）：两侧边距归零，只留行高 */
    LINE,
}

/** 思考正文的一块：正文 + 它的下边界。 */
internal data class ReasoningChunk(val text: String, val after: ReasoningBoundary)

/**
 * 思考正文的切块目标字符数（下限：块边界还要落在下一个安全的空行上）。
 *
 * 取值依据：切块粒度 = 绘制剔除的粒度。太小会让每块多一个 [com.adsh.app.ui.blocks.MarkdownBody]
 * （组合节点数量上去），太大则一块超过一屏、剔除省不下绘制。紧凑档 13/20 下一屏约 24 行，
 * 480 字约一块不超过 1.5 屏。
 */
internal const val REASONING_CHUNK_CHARS = 480

/**
 * 单块超过这个长度就在**行边界**兜底硬切（约 5 屏）。
 *
 * 为什么需要兜底：模型常把思考写成「一行一个硬换行、没有空行」的一整段 —— [answerChunks] 只在
 * 空行切，这种文本切不动，仍会是一整块。ADSH 的行内文本原样保留换行（段落里的换行就是硬换行）、
 * 行切时两侧边距归零，所以视觉等于整段渲染。已知例外：跨切的行内标记（`**` / 行内公式 / 反引号）
 * 会按字面显示 —— 只有把标记跨行写才会遇到。
 */
internal const val REASONING_HARD_CHUNK_CHARS = 2400

/**
 * 把一段思考正文切成若干块（一块一个 [ChatItem.ReasoningBody]）：先按 [answerChunks] 在块边界切，
 * 单块仍超过 [hardLimit] 时再按行边界兜底。
 *
 * **前缀稳定**：整段正文只会在尾部增长，已经切出来的块逐字不变 —— 流式期间前面那些行的
 * `MarkdownBody.remember(text)` 一直命中，不重解析、不重测量。
 */
internal fun reasoningChunks(
    text: String,
    budget: Int = REASONING_CHUNK_CHARS,
    hardLimit: Int = REASONING_HARD_CHUNK_CHARS,
): List<ReasoningChunk> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<ReasoningChunk>()
    answerChunks(text, budget).forEach { block ->
        if (block.length > hardLimit) {
            out += reasoningLineChunks(block, budget)
        } else {
            out += ReasoningChunk(block, ReasoningBoundary.BLOCK)
        }
    }
    out[out.lastIndex] = out.last().copy(after = ReasoningBoundary.NONE)
    return out
}

/**
 * 行边界硬切：只在「两条普通段落行之间」下刀 —— 切进列表 / 表格 / 引用 / 缩进代码会改变渲染。
 * 与 [answerChunks] 一样不切进围栏代码块；巨型围栏块切不动，保持一块（那种残余仍整块绘制）。
 */
private fun reasoningLineChunks(text: String, budget: Int): List<ReasoningChunk> {
    val lines = text.split('\n')
    val out = ArrayList<ReasoningChunk>()
    var start = 0
    var chars = 0
    var fence = false
    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) fence = !fence
        chars += line.length + 1
        if (fence || chars < budget) continue
        if (!isPlainParagraphLine(line) || !isPlainParagraphLine(lines.getOrNull(i + 1))) continue
        out += ReasoningChunk(lines.subList(start, i + 1).joinToString("\n"), ReasoningBoundary.LINE)
        start = i + 1
        chars = 0
    }
    if (out.isEmpty()) return listOf(ReasoningChunk(text, ReasoningBoundary.BLOCK))
    out += ReasoningChunk(lines.subList(start, lines.size).joinToString("\n"), ReasoningBoundary.BLOCK)
    return out
}

/**
 * 切块结果的 LRU（口径同 [DecodeCache]）：流式期间每一拍都会重建整份会话流，已定稿轮次里的
 * 思考正文不该被反复切分。键是正文本身 —— 未变的正文是同一个 String 实例，命中很便宜。
 */
internal object ReasoningChunkCache {
    private const val MAX_ENTRIES = 16
    private val entries = object : LinkedHashMap<String, List<ReasoningChunk>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReasoningChunk>>) = size > MAX_ENTRIES
    }

    fun chunks(text: String): List<ReasoningChunk> {
        synchronized(entries) { entries[text]?.let { return it } }
        val value = reasoningChunks(text)
        synchronized(entries) { entries[text] = value }
        return value
    }
}
