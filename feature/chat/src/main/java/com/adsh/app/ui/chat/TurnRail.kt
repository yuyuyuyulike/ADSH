package com.adsh.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.seam.ptc.SubCall
import com.adsh.app.ui.blocks.ContextInjectionRow
import com.adsh.app.ui.blocks.DshCodeBlock
import com.adsh.app.ui.blocks.DshDisclosureRow
import com.adsh.app.ui.blocks.DshIcons
import com.adsh.app.ui.blocks.DshSidebarIcons
import com.adsh.app.ui.blocks.DshToolIcons
import com.adsh.app.ui.blocks.ImagePreviewState
import com.adsh.app.ui.blocks.MarkdownBody
import com.adsh.app.ui.blocks.RAIL_LEADING_FADE_MS
import com.adsh.app.ui.blocks.RailChevron
import com.adsh.app.ui.blocks.RailDiffBlock
import com.adsh.app.ui.blocks.RailDot
import com.adsh.app.ui.blocks.RailPresentBlock
import com.adsh.app.ui.blocks.RailReadBlock
import com.adsh.app.ui.blocks.RailTerminalBlock
import com.adsh.app.ui.blocks.RailTodoCard
import com.adsh.app.ui.blocks.copyToClipboard
import com.adsh.app.ui.blocks.formatRunDuration
import com.adsh.app.ui.blocks.parseReadEnvelope
import com.adsh.app.ui.blocks.presentRowSummary
import com.adsh.app.ui.blocks.rememberCopiedFlag
import com.adsh.app.ui.blocks.rememberImage
import com.adsh.app.ui.blocks.todoDiffOf
import com.adsh.app.ui.blocks.todoItemsOf
import com.adsh.app.ui.blocks.todoRowSummary
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction

/**
 * 一轮对话的过程区（PTC），几何与状态逐项对齐 dsh：
 *
 * - chat/TurnProcessNodeView：折叠行高 33px、下边框 .5px border-l2、标签在左（14/24 二级色）、
 *   倒角在右（16px，-90° → 0°，transition .1s）；收起时 margin-bottom 8px。
 *   标签 = 「N 次工具调用 · M 条消息」，一条都没有时是「已思考」。
 * - chat/ReasoningRow：思考行 = 思考图标 14px +「思考」(14/24, 字重 400) + 2×2px 圆点 +
 *   摘要 13/20 三级色 + 倒角；运行中的摘要取「最后一行」（dsh 的 data-follow-end），
 *   结束后取第一行；展开后是 thinkBody（13/20 三级色、左缩进 22px、pre-wrap）。
 * - tool/components/ToolRow：工具行 = 图标（error/stopped 换成状态点）+ 标题 14/24 +
 *   圆点 + 摘要 13/24 三级色 + 倒角；展开是「代码块 / ioCard（输入·输出）」。
 *   行上**不带用时**（dsh 的 ToolRow 没有用时后缀，用时只在轮尾）。
 * - tool/ToolCallTree：子调用 = 左侧 0.5px 竖线 + margin 4 0 2 22 + padding-left 8 + gap 4。
 * - 运行中的行有一道 300px 宽的扫光（transparent → bg 60% → transparent）。
 *
 * 折叠规则见 TurnList.kt 的注释：只有「本轮已结束 + 最后一步是最终回答」才折叠。
 */
/**
 * 折叠行（dsh 的 TurnProcessNodeView，.l_V-RG_*）：
 * 33px 高、下内边距 8px、下边框 .5px border-l2、**收起时再补 8px 下边距**；
 * 标签 13/24 **三级色**、倒角 14px label-caption 紧跟文字（margin-left 4px，展开时转 180°）。
 *
 * 与 dsh 的差别只有文案：dsh 的标签是「用时 12秒 / 已完成工作 / 已停止 / 处理失败」，
 * 这里保留 ADSH 更具体的「N 次工具调用 · M 条消息 / 已思考」（本轮耗时在旁边那行 TurnStatus）。
 */
@Composable
fun TurnFoldRow(
    view: TurnView,
    open: Boolean,
    /** 参数 = 这一下之后的开合状态（true = 展开），调用方据此决定要不要「向下展示」 */
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDshPalette.current
    val labels = buildList {
        if (view.toolCount > 0) add(view.toolCount.toString() + " 次工具调用")
        if (view.messageCount > 0) add(view.messageCount.toString() + " 条消息")
    }
    // 收起时下方还留 8px（dsh 的 .l_V-RG_root:not([data-open]){margin-bottom:8px}）
    Column(modifier.fillMaxWidth().padding(bottom = if (open) 0.dp else 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(33.dp)
                .dshClickable(interactionSource = dshInteraction()) { onToggle(!open) }
                .padding(bottom = DshSpacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // dsh 的 .l_V-RG_label 是 flex:none：标签占自己需要的宽度，倒角紧跟文字后面，
            // 而不是被推到行的最右边。fill = false 对应 CSS 的 flex:0 1 auto。
            Text(
                text = if (labels.isEmpty()) "已思考" else labels.joinToString(" · "),
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 13.sp,
                lineHeight = 24.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RailChevron(open = open)
        }
        HorizontalDivider(thickness = DshSpacing.Hairline, color = palette.borderL2)
    }
}

/**
 * 过程里的一条（思考 / 过程文本 / 工具调用）—— 一行就是一个 LazyColumn item。
 *
 * 这样展开一轮时只有视口里的那几行会被组合 / 测量（见 ChatItem 的注释）。
 * 回答本身也在这些行里（index == answerIndex）：它的下标不会变 ⇒ item key 不变 ⇒
 * 流式那一行定稿时不会被重建、Markdown 也不会重解析。
 */
@Composable
fun TurnEntryRow(
    view: TurnView,
    index: Int,
    modifier: Modifier = Modifier,
    /** 思考行是否展开（正文块是独立行，见 [ChatItem.ReasoningBody]；键见 [reasoningKey]） */
    reasoningOpen: Boolean = false,
    /** 思考行被点开 / 收起：参数 = 之后是不是展开态（调用方负责交出自动跟随并记状态） */
    onReasoningToggle: (Boolean) -> Unit = {},
    /**
     * 读者展开/收起这一行时的回调（自动滚动优先级最低，见 ChatScreen 的 readerAction）。
     * 参数 = 这一下之后的开合状态（true = 展开）。
     */
    onReaderAction: (Boolean) -> Unit = {},
    /** 点工具行摘要里的文件路径打开预览（dsh 的 onOpenFile） */
    onOpenFile: ((String) -> Unit)? = null,
) {
    val entry = view.entries.getOrNull(index) ?: return
    val isAnswer = index == view.answerIndex
    Box(modifier.fillMaxWidth()) {
        if (isAnswer && entry is ProcessEntry.Text) {
            AssistantText(entry.text)
        } else {
            EntryRow(entry, reasoningOpen, onReasoningToggle, onReaderAction, onOpenFile)
        }
    }
}

/**
 * 轮尾（dsh 的 message.stopped + TurnTailNodeView）：
 * 被打断的标记 + **本轮碰过的文件卡片** + 复制 / 分支 / 用量 / 用时。
 *
 * 卡片只在**这一轮结束之后**才出现（dsh 的 `conversation.chat.turnTail` 槽位本来就只挂给
 * 已结束的轮次 —— 用户点名：「这个交付的文件是在 AI 结束这一轮对话才出现的，不是立马出现」）。
 * 形状照 dsh 的 ChangedFiles；卡片的全部说明见 `ui/TurnFilesCard.kt`。
 */
@Composable
fun TurnTailRow(
    view: TurnView,
    branchable: Boolean,
    onBranch: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 当前会话的工作区根：文件卡片用它裁掉路径前缀（见 deliverableDisplayPath） */
    workspacePath: String? = null,
    onOpenDeliverable: (String) -> Unit = {},
    /** 卡片展开 / 收起是**读者动作**（与工具行同规矩：向下展开、上方不动、不补偿滚动） */
    onCardToggle: () -> Unit = {},
) {
    val palette = LocalDshPalette.current
    Column(modifier.fillMaxWidth()) {
        if (view.interrupted) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已停止",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.selector)
                    .padding(horizontal = DshSpacing.Lg),
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
            )
        }
        // 一张卡：本轮碰过的文件（改过的 + 只交付的），见 TurnFilesCard.kt
        if (view.closed && (view.changes.isNotEmpty() || view.deliverables.isNotEmpty())) {
            Spacer(Modifier.height(if (view.interrupted) 6.dp else 4.dp))
            TurnFilesCard(
                changes = view.changes,
                deliverables = view.deliverables,
                workspacePath = workspacePath,
                onOpen = onOpenDeliverable,
                onToggle = onCardToggle,
            )
        }
        if (view.closed) {
            TurnActions(
                text = view.answer ?: view.entries.filterIsInstance<ProcessEntry.Text>()
                    .joinToString("") { it.text },
                usage = view.usage,
                runMillis = view.runMillis,
                branchable = branchable,
                onBranch = { view.lastMessageId?.let(onBranch) },
            )
        }
    }
}

/**
 * 一条过程条目的稳定标识：用来给 compose 的 key() 分组，
 * 也让「刚出现的这一行」有稳定的记忆位置（工具行优先用 callId，其余按下标）。
 */
internal fun turnEntryKey(view: TurnView, index: Int): String =
    view.entries.getOrNull(index)?.let { entryKey(view, it, index) } ?: ("i" + index)

/**
 * 稳定的行标识：**同类里的第几个**（只数它前面的同类条目）。
 *
 * 过程条目只会往后追加、不会重排，所以这个编号在一次对话里恒定。
 *
 * 两条不能用别的标识的理由：
 * - **绝对下标不行**：中间插进一条工具行，后面所有行的 key 都变 →
 * Compose 把那些行当成新行重建（展开状态、滚动位置都丢）；
 *  - - **callId 不行**：**流式期间它与落库之后不是同一个来源**：
 *    流式行来自 liveCalls（网关没给 id 时 callId 为 null），落库行来自消息里的
 *    tool_calls（id 一定有）。同一个 id 从 null 变成 "call_abc"、或者同一批里
 *    另一条先拿到了 id 导致「第几个匿名调用」整体错位，key 都会在飞行中改变 ——
 *    表现同样是一次重建。位置编号不吃这个亏：id 来不来，行的位置都没变。
 */
private fun entryKey(view: TurnView, entry: ProcessEntry, index: Int): String = when (entry) {
    is ProcessEntry.Call -> "call:" + sameKindOrdinal(view, index) { it is ProcessEntry.Call }
    is ProcessEntry.Reasoning -> "reasoning:" + sameKindOrdinal(view, index) { it is ProcessEntry.Reasoning }
    is ProcessEntry.Text -> "text:" + sameKindOrdinal(view, index) { it is ProcessEntry.Text }
    is ProcessEntry.Steering -> "steering:" + sameKindOrdinal(view, index) { it is ProcessEntry.Steering }
    is ProcessEntry.Notice -> "notice:" + sameKindOrdinal(view, index) { it is ProcessEntry.Notice }
}

/** 这个条目在它「同类」里排第几（只数它前面的） */
private inline fun sameKindOrdinal(view: TurnView, index: Int, predicate: (ProcessEntry) -> Boolean): Int =
    view.entries.take(index).count(predicate)

/*
 * 这里**故意没有**「新行淡入」。
 *
 * 试过一版 130ms 的 opacity 淡入，理由是「dsh 的 CSS 里这类动效就是 .1s 量级的
 * opacity」—— 那个判断是错的。把 dsh 全部客户端包里的 @keyframes / animation 列一遍，
 * 会话行（chat / tool / reasoning / bash / command）**一个出现动效都没有**：
 * 只有 running 态的扫光（dsh-tool-row-sweep / dsh-reasoning-row-sweep / dsh-bash-row-sweep）、
 * 轮轨标记与预览的 enter（dsh-turn-mark-enter / dsh-turn-preview-enter）、
 * 以及工作区侧栏 sessionRow 的 row-in。会话里的行就是**直接出现**的。
 *
 * 那一版淡入在真机上表现为「新工具调用出现时闪一下」：
 * 行先以 alpha=0 占好位置（一帧完全看不见），随后 130ms 才浮出来；只要这一行在动画跑完前
 * 被重组/重建一次（流式期间每帧都在重建），remember 出来的 Animatable 又从 0 开始 ——
 * 于是同一行会重播若干次淡入。删掉动效之后，行是「瞬间到位」的，与 dsh 的观感一致。
 */

/** 一条过程条目：思考 / 过程文本 / 工具调用 / 插话 */
@Composable
private fun EntryRow(
    entry: ProcessEntry,
    reasoningOpen: Boolean,
    onReasoningToggle: (Boolean) -> Unit,
    onReaderAction: (Boolean) -> Unit = {},
    onOpenFile: ((String) -> Unit)? = null,
) {
    when (entry) {
        // 正文不在这一行：展开时按块拆成独立行（[ChatItem.ReasoningBody]），这里只画头
        is ProcessEntry.Reasoning -> ReasoningHeadRow(
            text = entry.text,
            running = entry.running,
            open = reasoningOpen,
            onToggle = onReasoningToggle,
        )
        is ProcessEntry.Text -> AssistantText(entry.text)
        // 插话：与普通用户消息同一个气泡（dsh 的 UserStyleBubble 由 user / steering 两行共用）
        is ProcessEntry.Steering -> UserMessage(entry.text, entry.time, entry.attachments)
        // 任务完成通知：与沙箱策略通知同一个形态（dsh 的 message.contextInjection）
        is ProcessEntry.Notice -> ContextInjectionRow(
            label = "tool-jobs",
            form = "notice",
            text = entry.text,
            onReaderAction = { onReaderAction(true) },
        )
        is ProcessEntry.Call -> RailToolRow(
            RailTool(
                name = entry.name,
                arguments = entry.arguments,
                output = entry.output,
                running = entry.running,
                error = entry.isError,
                preparing = entry.preparing,
                children = entry.subCalls,
                todoBaseline = entry.todoBefore,
            ),
            onReaderAction = onReaderAction,
            onOpenFile = onOpenFile,
        )
    }
}

/**
 * 一行工具调用（dsh 的 ToolRow）。
 *
 * @param compact 子调用用的小一号标题（dsh 的 BashRow 等子调用视图标题是 13px）
 */
@Composable
private fun RailToolRow(
    tool: RailTool,
    compact: Boolean = false,
    onReaderAction: (Boolean) -> Unit = {},
    /** 点摘要里的文件路径打开预览（dsh 的 onOpenFile）；null = 不可点（子调用没接预览时） */
    onOpenFile: ((String) -> Unit)? = null,
) {
    val palette = LocalDshPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val spec = specOf(tool.name)
    // 参数 JSON 的解析结果按参数原文缓存：流式期间这一行会因为「运行中/输出」不停重组，
    // 不缓存的话每一帧都要重新解析一遍参数并重算摘要
    val derived = remember(tool.name, tool.arguments) {
        val parsed = parseArgs(tool.arguments)
        Triple(summaryOf(spec, tool.name, tool.arguments), inputBodyOf(spec, tool.name, tool.arguments), parsed)
    }
    // ask_user_question 的问答卡（dsh 的 AskQuestionCard）：题目取参数、答案取结果正文。
    // 参数解析不出题目时是 null —— 那一行退回普通的 ioCard，而不是画一张空卡；
    // 行摘要与展开体**共用这一个值**（摘要数答案、展开画答案，不能各算一份）。
    val askCard = remember(tool.name, tool.arguments, tool.output, tool.running, tool.error) {
        if (tool.name == "ask_user_question") {
            askCardOf(tool.arguments, tool.output, tool.running, tool.error)
        } else {
            null
        }
    }
    // todo_write（dsh 的 todo-row）、present（dsh 的 PresentRow）与 ask_user_question
    // （dsh 的 AskQuestionRow）的行摘要是专门的形状：前者是「N/M 已完成 · 当前任务 · 差异摘要」，
    // present 是「已交付 · 文件名, …」，提问是「等待回答 / N/M 已回答 / 已结束」
    val cardSummary = remember(askCard, tool.name, tool.arguments, tool.running, tool.error, tool.todoBaseline) {
        when (tool.name) {
            "todo_write", "todo" -> todoRowSummary(tool.arguments, tool.todoBaseline)
            "present" -> presentRowSummary(tool.arguments, tool.running, tool.error)
            "ask_user_question" -> askRowSummary(askCard, tool.running, tool.error)
            else -> null
        }
    }
    // dsh 的 preparing 阶段（参数还在流式生成）：摘要取 description **已经写出来的那一段**
    // （PartialArguments.text），不是等调用落日志才整段蹦出来；文件类工具要路径完整才显示。
    val preparingSummary = remember(tool.name, tool.arguments) {
        if (tool.preparing) preparingSummaryOf(tool.name, tool.arguments) else null
    }
    val summary = preparingSummary ?: cardSummary ?: derived.first
    val input = derived.second
    // 文件路径（可点）与 write/edit「能不能画 diff」都只依赖参数原文，跟摘要一起缓存。
    // **diff 本身不算**：它是最贵的一个推导（行级 Myers diff），收起时根本用不到 ——
    // **不能无条件算**：模型一次发来 5 个 edit 时同一帧要算 5 份（用户实测的「一次性调用六个
    // 工具，明显卡顿，像是加载」）。等到真的展开那一行再算（见下面的 open 分支）。
    val filePath = remember(tool.name, tool.arguments) { filePathOf(spec, tool.arguments) }
    val canDiff = remember(tool.name, tool.arguments) {
        !tool.error && hasDiffArgs(tool.name, tool.arguments)
    }
    // read 的展开卡：从输出信封解析（解析不出来 = 报错行 / web_fetch / 截断老数据 → 退回 ioCard）
    val readCard = remember(tool.name, tool.output, tool.error) {
        if (spec.variant == ToolVariant.READ && !tool.error) parseReadEnvelope(tool.output) else null
    }
    // grep / glob 的搜索卡（dsh 的 SearchBlock）：从结果正文还原分组/路径（解析不出来退回 ioCard）
    val searchCard = remember(tool.name, tool.output, tool.error) {
        if (tool.error) {
            null
        } else {
            when (tool.name) {
                "grep" -> parseGrepCard(tool.output)
                "glob" -> parseGlobCard(tool.output)
                else -> null
            }
        }
    }
    // web_search / web_fetch 的网页卡（dsh 的 WebBlock），同样是从正文解析回来的
    val webSearchCard = remember(tool.name, tool.output, tool.error) {
        if (tool.name == "web_search" && !tool.error) parseWebSearchCard(tool.output) else null
    }
    val webFetchCard = remember(tool.name, tool.output, tool.error) {
        if (tool.name == "web_fetch" && !tool.error) parseWebFetchCard(tool.output) else null
    }
    // todo_write 的清单卡（dsh 的 ToolDetails + todoDiffModel）：与上一次清单比出差异
    val todoCard = remember(tool.name, tool.arguments, tool.todoBaseline, tool.error) {
        if (tool.error || (tool.name != "todo_write" && tool.name != "todo")) {
            null
        } else {
            todoItemsOf(tool.arguments)?.let { todoDiffOf(it, tool.todoBaseline).second }
        }
    }
    // present 的结果原文（dsh 的 PresentRow 的 <pre>）
    val presentOutput = remember(tool.name, tool.output, tool.error) {
        if (tool.name == "present" && !tool.error) tool.output?.takeIf { it.isNotBlank() } else null
    }
    // error 时摘要换成输出的第一行（dsh 的 errorSummary）
    val failure = if (tool.error) tool.output?.let { firstLine(it) } else null
    val shown = failure ?: summary
    val code = if (spec.variant == ToolVariant.CODE) codeBodyOf(tool.arguments) else null
    // 图片行（read_image）的展开体就是图：dsh 的 readFamilyRow 给 ToolRow 传的是 image card，
    // card 不为 null 时 bodyText/ioCard 整条不画（只画图与一行 meta）
    val hasImages = tool.images.isNotEmpty()
    // dsh 的 expandable = state !== 'preparing'：参数还没写完的行不能展开（展开体也还没有意义）
    val expandable = !tool.preparing && (hasImages || code != null || canDiff || searchCard != null ||
        webSearchCard != null || webFetchCard != null || todoCard != null || presentOutput != null ||
        askCard != null || input != null || tool.output != null)
    // 摘要的颜色**过渡**而不是硬切（dsh 的 `.summary{transition:color .1s}`）：失败那一帧
    // 「红点 + 红字 + 换成错误摘要」是同时发生的三处变化，硬切看着就是闪一下
    val summaryColor by androidx.compose.animation.animateColorAsState(
        if (tool.error) palette.errorLabel else palette.labelTertiary,
        androidx.compose.animation.core.tween(RAIL_LEADING_FADE_MS),
        label = "toolSummaryColor",
    )
    // diff 只在展开时算（收起时用不到；它是这一行最贵的推导，见 canDiff 的注释）
    val diff = if (open && canDiff) {
        remember(tool.name, tool.arguments) { diffRowsOf(tool.name, tool.arguments) }
    } else {
        null
    }
    // 子调用（compact）与主行的差别只有行高 / 标题字号（dsh 的子调用视图是 13px 小一号）
    val rowHeight = if (compact) 22.dp else 24.dp
    // **必须包一层 Column**：本函数会发射两个兄弟节点（自己的行 + 下面的子调用树），而调用方
    // TurnEntryRow 把每一行放进一个 Box —— Box 会把兄弟节点**叠在同一个位置**，于是 run_code
    // 的第一条子调用正好压在 run_code 那一行上。删掉这层 Column 就会重现这个重叠。
    Column(Modifier.fillMaxWidth()) {
    DshDisclosureRow(
        icon = spec.icon,
        title = spec.title,
        open = open,
        expandable = expandable,
        running = tool.running,
        // error 时前置换成状态点（dsh 的 leadingFor），带 100ms 交叉淡出
        failed = tool.error,
        rowHeight = rowHeight,
        onToggle = {
            // 读者动作：自动滚动的优先级最低（见 ChatScreen 的 readerAction）——
            // 展开向下长、上方不动，不再做补偿滚动
            val expanded = !open
            onReaderAction(expanded)
            open = expanded
        },
        trailing = {
            if (shown.isNotEmpty()) {
                RailDot()
                // 文件路径摘要画成可点的链接（dsh 的 .fileLink）：下划线 + 点开预览。
                // error 行不挂链接（dsh 的 settledWithCue），此时 shown 是失败首行、按普通摘要画。
                val linkPath = filePath?.takeIf { !tool.error && onOpenFile != null }
                // **同一个 Text 节点**：「链接」与「普通摘要」不能是两个 composable 分支 —— 参数一变
                // （行从流式换成库里的、或 `write` / `edit` 的正文被 SubCallTrimmer 截断过）就会**整棵
                // 替换**，节点身份一变就闪。现在只有一个 Text 调用点，差别只在修饰符与颜色
                // （链接分支只属于 [FILE_PATH_VARIANTS] 的 read / write / edit 三种变体）。
                val summaryModifier = if (linkPath == null) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                        // 链接**收缩到文字宽度**（dsh 的 flex:0 1 auto），文字短时右边空出来的地方
                        // 仍归整行的点击（展开/收起），不会被链接吃掉。
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp))
                        .dshClickable(interactionSource = dshInteraction()) { onOpenFile?.invoke(linkPath) }
                }
                Text(
                    text = shown,
                    modifier = summaryModifier,
                    fontSize = 13.sp,
                    lineHeight = if (compact) 22.sp else 24.sp,
                    // 链接用二级色（比普通摘要深一档）；普通摘要用动画色（失败时淡入 error 色，
                    // dsh 的 `.summary{transition:color .1s}`），失败那一帧看着才不是硬切
                    color = if (linkPath != null) palette.labelSecondary else summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (linkPath != null) TextDecoration.Underline else null,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        },
        body = if (expandable) {
            {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = DshSpacing.Md, bottom = DshSpacing.Md),
            ) {
                if (hasImages) {
                    // dsh 的 read_image 行：展开是图本身，**不展示输出正文**
                    // （dsh 还会在下面挂一行信封 meta，用户点名不要，所以这里只给图）
                    RailImageGallery(tool.images, compact)
                } else if (code != null) {
                    // 代码行：高亮后的源码（dsh 的 CodeBlock，max-height 260px），
                    // 代码本身不再进 ioCard 的「输入」（dsh 的 cardBody 在 code 变体下是 null）
                    DshCodeBlock(
                        code = code,
                        lang = CODE_HIGHLIGHT_LANG,
                        maxHeight = 260.dp,
                        modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md),
                    )
                    if (tool.output != null) {
                        RailIoCard(input = null, output = tool.output, error = tool.error)
                    }
                } else if (diff != null && !tool.error) {
                    // write / edit：改动 diff（dsh 的 DiffBlock）。error 行不画 diff（dsh 的
                    // diffCardModel 在 isError 时返回 null），退回下面 ioCard 显示失败输出。
                    RailDiffBlock(diff, modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md))
                } else if (readCard != null) {
                    // read：行号卡（dsh 的 ReadBlock，readCard 只在非 error 时解析）
                    RailReadBlock(readCard, modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md))
                } else if (searchCard != null) {
                    // grep / glob：搜索卡（dsh 的 SearchBlock）—— 横幅摘要 + 复制，正文按文件分组或
                    // 一行一条路径，超过 8 行折中；截断时卡片下面挂一行恢复说明
                    RailSearchBlock(searchCard, modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md))
                } else if (webSearchCard != null) {
                    // web_search：网页搜索卡（dsh 的 WebSearchBlock）—— answer + 有序来源清单
                    RailWebSearchBlock(
                        webSearchCard,
                        modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md),
                    )
                } else if (webFetchCard != null) {
                    // web_fetch：抓取卡（dsh 的 WebFetchBlock）—— URL + HTTP 状态 + 截断提示，
                    // 正文挂在下面默认收起的「正文」折叠里（ADSH 没有 dsh 的 inspect 面板）
                    RailWebFetchBlock(
                        webFetchCard,
                        modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md),
                    )
                } else if (spec.variant == ToolVariant.BASH && input != null) {
                    // bash：终端卡（dsh 的 TerminalBlock）—— 单行命令横幅（状态点 + $ + 省略 +
                    // 退出码胶囊 + 复制），其余全是输出区
                    RailTerminalBlock(
                        command = input,
                        output = tool.output,
                        error = tool.error,
                        running = tool.running,
                        modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md),
                    )
                } else if (todoCard != null) {
                    // todo_write：清单卡（dsh 的 ToolDetails）—— 状态字形 + 差异 + 「N 项未变化」
                    RailTodoCard(todoCard, modifier = Modifier.padding(start = DshSpacing.Md, bottom = DshSpacing.Md))
                } else if (presentOutput != null) {
                    // present：结果原文（dsh 的 PresentRow 的 <pre>）
                    RailPresentBlock(presentOutput, modifier = Modifier.padding(start = DshSpacing.Md, bottom = DshSpacing.Md))
                } else if (askCard != null) {
                    // ask_user_question：问答卡（dsh 的 AskQuestionCard）—— 题目 + 答案 / 未回答，
                    // 没有问答可画时是 null、落到下面的 ioCard（参数原文 + 结果正文）
                    RailAskQuestionCard(askCard)
                } else if (input != null || tool.output != null) {
                    RailIoCard(input = input, output = tool.output, error = tool.error)
                }
            }
            }
        } else {
            null
        },
    )
    // 子调用：dsh 的 ToolCallTree（左侧竖线 + 缩进）
    if (tool.children.isNotEmpty()) {
        RailSubCalls(tool.children, onReaderAction, onOpenFile, todoBaseline = tool.todoBaseline)
    }
    }
}

/**
 * 子调用树（dsh 的 .ztWv_q_subCalls）：border-left .5px border-l2 / margin 4px 0 2px 22px /
 * padding-left 8px / gap 4px；每个子调用本身也是一整行工具调用。[onReaderAction] **必须一路传进来**：
 * PTC 下所有工具都是 run_code 的子调用，子行不接这个回调时「读者动作先交出跟随」就落不到它身上。
 */
@Composable
private fun RailSubCalls(
    children: List<SubCall>,
    onReaderAction: (Boolean) -> Unit = {},
    onOpenFile: ((String) -> Unit)? = null,
    /** 上一次 todo_write 的参数（父行带来的基准）：子行里的 todo_write 拿它算「与上次清单相比」 */
    todoBaseline: String? = null,
) {
    val palette = LocalDshPalette.current
    // 竖线用 drawBehind 画在自己身上，不用 height(IntrinsicSize.Min)：
    // 固有测量会把整棵子树先量一遍（每个子行两趟），子调用一多就是它最慢。
    val border = palette.borderL2
    // 一路往下带：同一条 run_code 行里出现多次 todo_write 时，后一条的基准是前一条的参数
    var lastTodoArgs = todoBaseline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = DshSpacing.Md, bottom = DshSpacing.Xs)
            .drawBehind {
                // dsh 的 .subCalls 是 border-left: .5px + margin-left: 22px + padding-left: 8px ——
                // 竖线画在 margin 的边界上（= 本 Column 的 0 点，drawBehind 已在外层 22dp padding
                // 之内），**不是** padding 之后；再左移会把线画到 14dp 处。
                val width = DshSpacing.Hairline.toPx()
                drawRect(
                    color = border,
                    topLeft = Offset(0f, 0f),
                    size = Size(width, size.height),
                )
            }
            .padding(start = DshSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        children.forEach { child ->
            val baseline = lastTodoArgs
            if (child.name == "todo_write" || child.name == "todo") lastTodoArgs = child.args
            RailToolRow(
                RailTool(
                    name = child.name,
                    arguments = child.args,
                    output = child.result.ifBlank { null },
                    // 正在跑的子调用也要扫光（dsh 的 ToolRow[data-state=running]：
                    // 组合工具下面的子行各自处在运行态时同样有那一道 300px 扫光）
                    running = child.running,
                    error = !child.ok && !child.running,
                    images = child.images,
                    todoBaseline = baseline,
                ),
                compact = true,
                onReaderAction = onReaderAction,
                onOpenFile = onOpenFile,
            )
        }
    }
}

/**
 * 工具读到的图片画廊（dsh 的 read_image 卡）：
 * 一行一张缩略图 —— 单张按宽高比铺开、多张排成 96dp 的方格；点任意一张打开原图预览。
 *
 * 与用户附件那套（UserImageAttachment）共用同一份解码缓存：同一张图在两个地方
 * 出现时不会解码两遍。
 */
@Composable
private fun RailImageGallery(images: List<com.adsh.app.seam.tool.ToolImage>, compact: Boolean) {
    val palette = LocalDshPalette.current
    val multiple = images.size > 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (compact) 19.dp else 20.dp, top = DshSpacing.Md, bottom = DshSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        images.forEach { image ->
            val attachment = image.attachment
            val targetPx = if (multiple) 192 else 960
            val bitmap by rememberImage(attachment.path, targetPx)
            val frame = Modifier
                .clip(RoundedCornerShape(16.dp))
                .border(DshSpacing.Hairline, palette.borderL2, RoundedCornerShape(16.dp))
                .background(palette.selector)
                .dshClickable(interactionSource = dshInteraction()) { ImagePreviewState.open(attachment.path) }
            if (multiple) {
                Box(frame.size(96.dp), contentAlignment = Alignment.Center) {
                    AttachmentImageContent(bitmap, attachment, compact = true)
                }
            } else {
                androidx.compose.foundation.layout.BoxWithConstraints(frame) {
                    val size = fitImageSize(
                        naturalWidthPx = attachment.width.takeIf { it > 0 } ?: (bitmap?.width ?: 0),
                        naturalHeightPx = attachment.height.takeIf { it > 0 } ?: (bitmap?.height ?: 0),
                        maxWidth = maxWidth,
                        maxHeight = GALLERY_IMAGE_MAX_HEIGHT,
                    )
                    Box(Modifier.size(size.first, size.second), contentAlignment = Alignment.Center) {
                        AttachmentImageContent(bitmap, attachment, compact = false)
                    }
                }
            }
        }
    }
}

/** 工具行里单张图的高度上限（比用户消息的 320dp 矮一点：它在缩进的子行下面） */
private val GALLERY_IMAGE_MAX_HEIGHT = 260.dp

/**
 * ioCard 一段（输入/输出）的高度上限（手机侧放宽，见 RailIoSection 的注释）。
 * dsh 桌面端是 150px；竖屏手机上太矮，输出常被挤得只剩一两行、又难滑。
 * bash 的展开体走 TerminalBlock（输出区 224dp，dsh 同值），不走这里。
 */
private val IO_SECTION_MAX_HEIGHT = 200.dp

/** ioCard 的字号与行高：dsh 的 `--dsw-font-markdown-code-block-small`（等宽 11/16） */
private val IO_TEXT_SIZE = 11.sp
private val IO_TEXT_LINE_HEIGHT = 16.sp

/** dsh 的 ioCard：.5px border-l1 + code-block 底 + 圆角 16（--dsw-radius-lg），两栏（标签 caption / 内容 secondary） */
@Composable
private fun RailIoCard(input: String?, output: String?, error: Boolean) {
    val palette = LocalDshPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.Md)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.codeBlock)
            .border(DshSpacing.Hairline, palette.borderL1, RoundedCornerShape(16.dp)),
    ) {
        if (input != null) RailIoSection("输入", input, error = false)
        if (input != null && output != null) {
            HorizontalDivider(thickness = DshSpacing.Hairline, color = palette.borderL2)
        }
        output?.let { RailIoSection("输出", it, error = error) }
    }
}

/**
 * dsh 的 ioSection：max-content 标签列 + 内容列，gap 14，可滚（不截断）。
 *
 * 文字（标签与内容都是）走 dsh 的 `--dsw-font-markdown-code-block-small`：**等宽 11/16** ——
 * ioCard 继承这个字体，所以「输入 / 输出」两个标签也是等宽小字。参数 JSON 与错误正文都是代码
 * 形状的东西，等宽才和 dsh 一致。
 *
 * 高度上限是**手机侧的有意偏离**：dsh 桌面端是 150px，这里放到 [IO_SECTION_MAX_HEIGHT]（200dp）——
 * 用户点名「展开的窗口不够长，输出被挤成一行」，桌面上 150px 够看，竖屏手机上太矮。
 */
@Composable
private fun RailIoSection(label: String, text: String, error: Boolean) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(max = IO_SECTION_MAX_HEIGHT)
            .padding(horizontal = DshSpacing.Card, vertical = DshSpacing.Xxxl),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Section),
    ) {
        Text(
            text = label,
            fontSize = IO_TEXT_SIZE,
            lineHeight = IO_TEXT_LINE_HEIGHT,
            fontFamily = FontFamily.Monospace,
            color = palette.labelCaption,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f).scrollableBody(),
            fontSize = IO_TEXT_SIZE,
            lineHeight = IO_TEXT_LINE_HEIGHT,
            fontFamily = FontFamily.Monospace,
            color = if (error) palette.errorLabel else palette.labelSecondary,
        )
    }
}

/**
 * run_code 展开体的高亮语言：**标签**写 javascript —— 我们的 PTC 跑的是纯 JavaScript
 * （dsh 的可擦除 TypeScript 没有搬过来）；**高亮**仍喂 typescript —— highlightCode 实现的是
 * TS 规则，而 JS 是它的子集，关键字 / 字符串 / 注释 / 数字都能覆盖到。
 */
private const val CODE_HIGHLIGHT_LANG = "javascript"

/**
 * dsh 的 [data-follow-end]（ReasoningRow 运行中的摘要），等价于 CSS 的 width: max-content +
 * min-width: 100% + justify-content: flex-end：**比行宽短时仍然从左开始**，只有更长时才整块贴到
 * 行尾、把左边溢出裁掉（一直看得到最新写出来的字）。直接用右对齐会让短摘要看着「从右往左长」。
 */

/*
 * 右端渐隐**不做**（dsh 的 `[data-streaming]` 用 `mask-image`）：Compose 没有 mask-image，
 * 用「右端叠一层透明→底色的渐变」顶替时那一层底色会盖住字，而 follow-end 正好把最新写出来的
 * 几个字贴在右端 —— 看起来就是被白雾糊住。只 `clipToBounds`：最新的字完整可读，左边照旧裁掉。
 */

private fun Modifier.followEnd(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity),
    )
    val width = constraints.maxWidth
    layout(width, placeable.height) {
        // 比行宽短：x = 0（左对齐）；比行宽长：x < 0，左边被父级的 clipToBounds 裁掉
        placeable.place((width - placeable.width).coerceAtMost(0), 0)
    }
}

// ------------------------------------------------------------------ 思考行

/**
 * dsh 的 ReasoningRow（ReasoningRow.module.css + DisclosureRow 逐条对齐）的**头**：
 *
 *  - 收起时：图标 + 「思考」+ 2×2 圆点 + 摘要；**展开时摘要整行不渲染**
 *    （dsh 的 DisclosureRow 只在 !open 时渲染 collapsedContent，这里没有 keepContentWhenOpen），
 *    行上只剩倒角 + 标题。
 *  - 摘要：运行中取最后一行（latestLine），结束后取第一行（firstLine），都去掉 ** 标记。
 *  - 运行中的摘要按 dsh 的 [data-follow-end] 渲染：容器右对齐 + 裁剪左边，
 *    也就是**永远显示最新的那一段**。按左对齐截尾的话，模型写一大段没有换行的思考时，
 *    可见的永远是开头那几个字，看起来就像「半天不动」（这就是「思考变化太慢」的根因）。
 *  - 文字本身始终从左往右排，只是整块贴在行尾；只有比行宽更长时才裁掉左边。
 *
 * 正文不在这里：展开时按块拆成独立行（[ChatItem.ReasoningBody]）—— 行级绘制剔除才管得到它。
 * 展开态由界面持有（[reasoningKey]），不在行内 rememberSaveable：折叠整轮让这一行离开组合时
 * 展开态不该丢。
 */
@Composable
private fun ReasoningHeadRow(
    text: String,
    running: Boolean,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    // 运行中的摘要取**最后一行**（dsh 的 data-follow-end：整块贴到行尾、左边被裁掉，
    // 于是永远看得到最新写出来的字）；结束后取第一行。
    val summary = (if (running) latestLine(text) else firstLine(text)).replace("**", "")
    DshDisclosureRow(
        icon = DshIcons.Think,
        title = "思考",
        open = open,
        running = running,
        onToggle = { onToggle(!open) },
        trailing = if (open) {
            { Spacer(Modifier.weight(1f)) }
        } else {
            {
                // 展开时圆点与摘要一起消失（dsh 展开后行里只剩 leading + title）
                RailDot()
                if (running) {
                    // 流式时摘要只裁左边（[followEnd]），**右端不叠渐隐** —— 那层底色正好盖住
                    // 最新写出来的字（看着像「字的右侧被白雾遮挡」）。
                    Text(
                        text = summary,
                        modifier = Modifier
                            .weight(1f)
                            .clipToBounds()
                            .followEnd(),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = LocalDshPalette.current.labelTertiary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                } else {
                    Text(
                        text = summary,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = LocalDshPalette.current.labelTertiary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        },
        body = null,
    )
}

/**
 * 思考正文的一块（一块一个会话流行，见 [ChatItem.ReasoningBody]）。
 *
 * 缩进与上下留白照 dsh 的 thinkBody：左缩进 22（与头部图标列对齐）、首块上 4、末块下 4。
 * 块与块之间的间距由 [before] / [after] 决定：
 *  - [ReasoningBoundary.BLOCK]：空行切点，两侧各自保留块间距 ⇒ 与整段渲染逐像素一致；
 *  - [ReasoningBoundary.LINE]：行边界兜底硬切，两侧边距归零、只留行高；
 *  - [ReasoningBoundary.NONE]：首块的上边界 / 末块的下边界，不保留外侧边距。
 */
@Composable
internal fun ReasoningBodyRow(text: String, before: ReasoningBoundary, after: ReasoningBoundary) {
    Box(
        Modifier.padding(
            start = 22.dp,
            top = if (before == ReasoningBoundary.NONE) DshSpacing.Md else 0.dp,
            bottom = if (after == ReasoningBoundary.NONE) DshSpacing.Md else 0.dp,
        ),
    ) {
        MarkdownBody(
            text = text,
            compact = true,
            hasPreceding = before == ReasoningBoundary.BLOCK,
            hasFollowing = after == ReasoningBoundary.BLOCK,
        )
    }
}

// ------------------------------------------------------------------ 轮尾动作（dsh 的 MessageIconActions）

/** dsh 的 TurnTailNodeView .actions 左边距 -6px：动作行左移 6，首个图标与正文左缘对齐 */
internal val TurnActionsLeftMargin = (-6).dp

/** dsh 的 TurnUsagePanel 两粒相邻 -6px：外层 gap 8 之上叠成 2 */
internal val TurnStatPillGap = 2.dp

/** 轮尾两粒胶囊共用**一个**展开槽：同一时刻只允许一张明细卡开着（dsh 的统计 / 用量面板同此）。 */
private enum class TurnDetail { Usage, Time }

/**
 * dsh 的 TurnTailNodeView：轮结束后一排 28px 的动作。
 * 顺序（dsh 的 MessageIconActions）：复制 → 分支 → 用量 → 用时。
 * 几何取自 CSS：actions = 28px 高 / gap 8px / margin-top 4px / margin-left -6px、action = 28×28 圆角 28 / 图标 15px、
 * pill = 28px 高 / 圆角 28 / 内边距 6px 8px / gap 4px / 13px 文字 / tabular-nums；用量与用时两粒之间 2。
 */
@Composable
private fun TurnActions(
    text: String,
    usage: TurnUsage?,
    runMillis: Long?,
    branchable: Boolean,
    onBranch: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    var copied by rememberCopiedFlag()
    var openDetail by remember { mutableStateOf<TurnDetail?>(null) }
    Row(
        // 注意顺序：padding 在外、height 在内。反过来会把 28dp 的胶囊压进 24dp 的盒子里，
        // 文字上下各被裁掉一截（截图里「用量 2.8K tok」只剩上半截就是这么来的）
        modifier = Modifier.fillMaxWidth().offset(x = TurnActionsLeftMargin).padding(top = DshSpacing.Md).height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        RailIconAction(
            icon = if (copied) DshIcons.Check else DshToolIcons.Copy,
            label = if (copied) "已复制" else "复制",
        ) {
            copyToClipboard(context, text)
            copied = true
        }
        if (branchable) {
            RailIconAction(icon = DshSidebarIcons.Branch, label = "在新对话中分支", onClick = onBranch)
        }
        if (usage != null || runMillis != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TurnStatPillGap),
            ) {
                usage?.let {
                    RailUsagePill(
                        usage = it,
                        open = openDetail == TurnDetail.Usage,
                        onToggle = {
                            openDetail = if (openDetail == TurnDetail.Usage) null else TurnDetail.Usage
                        },
                    )
                }
                runMillis?.let {
                    RailTimePill(
                        runMillis = it,
                        usage = usage,
                        open = openDetail == TurnDetail.Time,
                        onToggle = {
                            openDetail = if (openDetail == TurnDetail.Time) null else TurnDetail.Time
                        },
                    )
                }
            }
        }
    }
}

/** dsh 的 .xzv4MW_action：28×28 的圆形图标按钮（图标 15px，hover 时换底色） */
@Composable
internal fun RailIconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(28.dp))
            .dshClickable(interactionSource = dshInteraction()) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = palette.labelTertiary, modifier = Modifier.size(15.dp))
    }
}

/** dsh 的「用量 {total} tok」（IconDatabaseOutline16 + 可点开的明细） */
@Composable
private fun RailUsagePill(usage: TurnUsage, open: Boolean, onToggle: () -> Unit) {
    val anchor = remember { OverlayAnchorState() }
    Box(Modifier.overlayAnchor(anchor)) {
        RailStatPill(
            icon = DshIcons.Database,
            label = "用量 " + formatTokensCompact(usage.totalTokens) + " tok",
            open = open,
            onClick = onToggle,
        )
        if (open) {
            // 气泡上留 8dp、离屏幕边 12dp（dsh 的 useAnchoredPosition：side:top / gap:8 / margin:12）。
            // 横向按 dsh 的 align:end 挂（气泡右边缘贴触发器右边缘）—— 面板比触发器宽得多，于是整块
            // 往**左**铺；这两张卡必须偏左：按 align:start 挂会被「夹进视口」推到屏幕右边缘。
            DshPopup(onDismiss = onToggle, margin = DshSpacing.Xxxl, anchor = anchor, freezeAnchor = true) {
                StatPanel(
                    title = "本轮用量",
                    icon = DshIcons.Database,
                    titleValue = formatExactTokens(usage.totalTokens) + " tok",
                    rows = buildList {
                        // dsh 的 route 串：provider/model（例如 deepseek-official/deepseek-flash）
                        val route = listOf(usage.provider, usage.model).filter { it.isNotBlank() }.joinToString("/")
                        if (route.isNotBlank()) add("提供方 / 模型" to route)
                        add("缓存命中" to (formatCacheHitPercent(usage.cacheReadTokens, usage.billedInputTokens) + "%"))
                        add("未缓存输入" to (formatExactTokens(usage.uncachedInputTokens) + " tok"))
                        add("缓存读取" to (formatExactTokens(usage.cacheReadTokens) + " tok"))
                        add(
                            "输出" to (formatExactTokens(usage.outputTokens) + " tok" +
                                if (usage.reasoningTokens > 0) {
                                    "（其中推理 " + formatExactTokens(usage.reasoningTokens) + " tok）"
                                } else {
                                    ""
                                }),
                        )
                    },
                )
            }
        }
    }
}

/** dsh 的「用时 {duration}」（IconClockOutline16 + 可点开的明细） */
@Composable
private fun RailTimePill(runMillis: Long, usage: TurnUsage?, open: Boolean, onToggle: () -> Unit) {
    val anchor = remember { OverlayAnchorState() }
    Box(Modifier.overlayAnchor(anchor)) {
        RailStatPill(
            icon = DshToolIcons.Clock,
            label = "用时 " + formatRunDuration(runMillis),
            open = open,
            onClick = onToggle,
        )
        if (open) {
            DshPopup(onDismiss = onToggle, margin = DshSpacing.Xxxl, anchor = anchor, freezeAnchor = true) {
                StatPanel(
                    title = "本轮用时和速度",
                    icon = DshToolIcons.Clock,
                    rows = buildList {
                        add("本轮总用时" to formatRunDuration(runMillis))
                        if (usage != null && usage.tps > 0) add("输出速度（TPS）" to (formatTps(usage.tps) + " tok/s"))
                        if (usage != null && usage.ttftMillis > 0) {
                            add("首 token 用时（TTFT）" to formatCompactDuration(usage.ttftMillis))
                        }
                    },
                )
            }
        }
    }
}

/**
 * dsh 的 .Rd79tq_trigger：28px 高 / 内边距 6px 8px / 图标 15px + 4px + 12px 文字，
 * **展开时给自己一层 hover 底**（`[aria-expanded=true]`）—— 少了它，点开明细后触发器没有任何
 * 「我正开着」的反馈。
 */
@Composable
private fun RailStatPill(icon: ImageVector, label: String, open: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (open) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = dshInteraction()) { onClick() }
            .padding(horizontal = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        Icon(icon, contentDescription = null, tint = palette.labelTertiary, modifier = Modifier.size(15.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = if (open) palette.labelSecondary else palette.labelTertiary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ------------------------------------------------------------------ 公共小件

/**
 * 代码块 / IO 段 / 终端正文的滚动容器：**只纵向滚**，宽度交给父级约束、文字折行。
 *
 * dsh 的 ioText 是 `white-space:pre-wrap; word-break:break-word`，ioSection 也只 overflow-y ——
 * 长行必须折行。**不能挂 horizontalScroll**：文字会按「一行到底」排版（宽度不受约束），
 * 排版量随内容长度暴涨，展开一大段输出时又慢又难读。
 */
@Composable
private fun Modifier.scrollableBody(): Modifier =
    this.verticalScroll(rememberScrollState())

private fun latestLine(text: String): String {
    val trimmed = text.trimEnd()
    return trimmed.substringAfterLast('\n').trim()
}

