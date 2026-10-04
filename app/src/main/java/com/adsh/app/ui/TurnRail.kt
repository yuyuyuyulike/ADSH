package com.adsh.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.core.ptc.SubCall
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
            EntryRow(entry, onReaderAction, onOpenFile)
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
        // 一张卡：本轮碰过的文件（改过的 + 只交付的）—— 第 86 轮并表，见 TurnFilesCard.kt
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
 * 两条踩过的坑：
 *  - 最早用的是**绝对下标**：中间插进一条工具行，后面所有行的 key 都变 →
 *    Compose 把那些行当成新行重建（展开状态、滚动位置都丢）；
 *  - 后来工具行改成用 callId，但**流式期间它与落库之后不是同一个来源**：
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
 * 第十七轮加过一版 130ms 的 opacity 淡入，理由是「dsh 的 CSS 里这类动效就是 .1s 量级的
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
    onReaderAction: (Boolean) -> Unit = {},
    onOpenFile: ((String) -> Unit)? = null,
) {
    when (entry) {
        is ProcessEntry.Reasoning -> RailReasoning(entry.text, running = entry.running, onReaderAction = onReaderAction)
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
                children = entry.subCalls,
                todoBaseline = entry.todoBefore,
            ),
            onReaderAction = onReaderAction,
            onOpenFile = onOpenFile,
        )
    }
}

// ------------------------------------------------------------------ 工具行（dsh 的 ToolRow / ToolCallTree）

/** dsh 的 ToolRow 变体（TOOL_VARIANTS）：决定图标、标题与摘要取哪个参数 */
private enum class ToolVariant { SEARCH, READ, BASH, WRITE, EDIT, CODE, OTHERS }

/** 一行工具调用的展示模型 */
internal data class RailTool(
    val name: String,
    val arguments: String,
    val output: String? = null,
    val running: Boolean = false,
    val error: Boolean = false,
    val children: List<SubCall> = emptyList(),
    /** 这次调用产出的图片（read_image）：行下面直接铺开画廊，点开是原图预览 */
    val images: List<com.adsh.app.core.agent.ToolImage> = emptyList(),
    /**
     * todo_write 用的**上一次清单**（上一次 todo_write 的参数 JSON）：dsh 的会话级
     * todoHistory 投影，由 TurnBuilder 一路带下来（见 ProcessEntry.Call.todoBefore）
     */
    val todoBaseline: String? = null,
)

private data class ToolSpec(val variant: ToolVariant, val title: String, val icon: ImageVector)

/**
 * 工具名 → 变体 + 标题 + 图标。
 * 标题逐字取自 dsh 的中文字典（tool.title.* / todo.rowTitle / ask.rowTitle），
 * 图标取自 dsh 的 VARIANT_ICONS（代码 = # 形 IconCodeOutline16）。
 */
private fun specOf(name: String): ToolSpec = when (name) {
    "bash", "pwsh" -> ToolSpec(ToolVariant.BASH, "Bash", DshToolIcons.Api)
    "read" -> ToolSpec(ToolVariant.READ, "读取", DshToolIcons.Browse)
    "read_image" -> ToolSpec(ToolVariant.READ, "读取图片", DshToolIcons.Browse)
    "web_fetch" -> ToolSpec(ToolVariant.READ, "网页获取", DshToolIcons.Browse)
    // dsh 的 web_search 行图标是 IconGlobeOutlineRegular（地球）；ADSH 的 DshToolIcons 是生成物
    // （scripts/dsh-icons2.py，不手改），这里用 Material 的地球轮廓，图形等价
    "web_search" -> ToolSpec(ToolVariant.SEARCH, "网页搜索", Icons.Outlined.Public)
    // 标题逐字取 dsh 的中文字典：tool.title.grep = 搜索文件内容 / tool.title.glob = 查找文件
    "grep" -> ToolSpec(ToolVariant.SEARCH, "搜索文件内容", DshToolIcons.Search)
    "glob" -> ToolSpec(ToolVariant.SEARCH, "查找文件", DshToolIcons.Search)
    "write" -> ToolSpec(ToolVariant.WRITE, "写入", DshSidebarIcons.Edit)
    "edit" -> ToolSpec(ToolVariant.EDIT, "编辑", DshSidebarIcons.Edit)
    "run_code" -> ToolSpec(ToolVariant.CODE, "代码", DshToolIcons.Code)
    // dsh 的工具名是 todo_write（旧会话里可能还留着老的 todo）
    // 图标：dsh 是 IconChecklistOutlineRegular / IconDeliverDocRegular（DshToolIcons 是生成物，不手改）
    "todo_write", "todo" -> ToolSpec(ToolVariant.OTHERS, "更新任务清单", Icons.Outlined.Checklist)
    "ask_user_question" -> ToolSpec(ToolVariant.OTHERS, "提问", DshToolIcons.Sparkle)
    // dsh-client-ui-deliverables 的 row.title =「交付文件」
    "present" -> ToolSpec(ToolVariant.OTHERS, "交付文件", Icons.Outlined.Description)
    else -> ToolSpec(ToolVariant.OTHERS, "工具调用", DshToolIcons.Sparkle)
}

/** dsh 的 SUMMARY_KEYS：每个变体优先取哪个参数当摘要 */
private val SUMMARY_KEYS: Map<ToolVariant, List<String>> = mapOf(
    ToolVariant.BASH to listOf("description", "command"),
    ToolVariant.READ to listOf("path", "file_path", "url"),
    ToolVariant.SEARCH to listOf("query", "pattern", "url"),
    ToolVariant.WRITE to listOf("path", "file_path"),
    ToolVariant.EDIT to listOf("path", "file_path"),
    ToolVariant.CODE to listOf("description"),
    ToolVariant.OTHERS to emptyList(),
)

/** 只有这三种变体的摘要可能是可打开的路径（dsh 的 FILE_PATH_VARIANTS） */
private val FILE_PATH_VARIANTS = setOf(ToolVariant.READ, ToolVariant.WRITE, ToolVariant.EDIT)

private fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** 工具行的摘要：单测从这里进（[summaryOf] 本身是私有的） */
internal fun toolRowSummary(name: String, arguments: String): String = summaryOf(specOf(name), name, arguments)

private fun pickString(args: JsonObject?, keys: List<String>): String? = ToolArgs.pick(args, keys)

/**
 * 摘要（dsh 的 deriveSummary + toolRowModel）：
 * others 变体在没有专属标题时把工具名缀在前面（「present · xxx」）。
 *
 * 参数 JSON 可能**是被截断过的老数据**（见 [ToolArgs]）：那时也要能取出 path / command 这些
 * 摘要字段，而不是把一坨 JSON 原文显示在行上 —— 后者正是「同一个工具执行时一个样、执行完
 * 另一个样」的根因。真的什么都取不到时，退回一个**去掉 JSON 语法噪声**的单行短文本。
 */
private fun summaryOf(spec: ToolSpec, name: String, arguments: String): String {
    val args = ToolArgs.parse(arguments)
    if (spec.variant == ToolVariant.SEARCH) {
        val queries = (args?.get("queries") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotEmpty() }
        if (!queries.isNullOrEmpty()) return queries.joinToString(", ") { firstLine(it) }
    }
    val picked = pickString(args, SUMMARY_KEYS[spec.variant].orEmpty())
    if (picked != null) return firstLine(picked)
    val anyString = ToolArgs.firstString(args)
    if (anyString != null) return firstLine(anyString)
    val base = if (args != null) "" else readableFallback(arguments)
    return if (spec.variant == ToolVariant.OTHERS && name.isNotEmpty()) {
        listOf(name, base).filter { it.isNotEmpty() }.joinToString(" · ")
    } else {
        base
    }
}

/**
 * 连补全解析都失败时的兜底显示：去掉 `{` `}` `"key":` 这类 JSON 噪声，只留能读的第一段。
 * **绝不把原文整段（或它的 JSON 骨架）当摘要**。
 */
private fun readableFallback(arguments: String): String {
    val cleaned = arguments
        .replace(Regex("\"[A-Za-z_][A-Za-z0-9_]*\"\\s*:"), " ")
        .replace(Regex("[{}\\[\\]\"]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.take(120)
}

/**
 * 展开后的「输入」体（dsh 的 formatToolBody）：
 * 代码行取 code 原文，其余是格式化后的 JSON；带路径的文件类工具不铺输入（dsh 的 singleFile）。
 */
private fun inputBodyOf(spec: ToolSpec, arguments: String): String? {
    if (arguments.isBlank()) return null
    val args = ToolArgs.parse(arguments) ?: return arguments
    if (spec.variant == ToolVariant.CODE) {
        val code = (args["code"] as? JsonPrimitive)?.contentOrNull
            ?: (args["program"] as? JsonPrimitive)?.contentOrNull
        if (!code.isNullOrEmpty()) return code
    }
    if (spec.variant == ToolVariant.BASH) {
        return pickString(args, listOf("command")) ?: jsonPretty(args)
    }
    if (spec.variant in FILE_PATH_VARIANTS && pickString(args, listOf("path", "file_path")) != null) return null
    return jsonPretty(args)
}

/** 代码行要渲染的源码（dsh 的 variant === "code" 走 CodeBlock，lang = typescript） */
private fun codeBodyOf(arguments: String): String? {
    val args = ToolArgs.parse(arguments) ?: return null
    return (args["code"] as? JsonPrimitive)?.contentOrNull
        ?: (args["program"] as? JsonPrimitive)?.contentOrNull
}

/**
 * 文件类工具（read / write / edit）摘要里那条**可打开的路径**（dsh 的 deriveFilePath）：
 * 只认 `path` / `file_path`，绝不认 `url`（web_fetch 也归 read 变体，但它的摘要是网址、不是文件）。
 * 返回 null 时摘要就按普通文字画（不加下划线、不可点）。
 */
private fun filePathOf(spec: ToolSpec, arguments: String): String? {
    if (spec.variant !in FILE_PATH_VARIANTS) return null
    val args = ToolArgs.parse(arguments) ?: return null
    return pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }?.takeIf { it.isNotEmpty() }
}

/**
 * 「展开之后有没有 diff 可画」的**便宜**判据：只看参数里那几个键在不在，
 * 不真的算 diff（[diffRowsOf] 才是贵的那个 —— 行级 Myers diff，`write` 还要按整篇正文铺行）。
 *
 * 它必须与 [diffRowsOf] 的判据**逐条一致**，否则会出现「徽标说有内容、展开却是空的」：
 * 两者都要求 path 非空，write 要求 content 非 null，edit 要求 old_string / new_string 都在。
 */
private fun hasDiffArgs(name: String, arguments: String): Boolean {
    if (name != "write" && name != "edit") return false
    val args = ToolArgs.parse(arguments) ?: return false
    if (pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }?.isEmpty() != false) return false
    return when (name) {
        "write" -> (args["content"] as? JsonPrimitive)?.contentOrNull != null
        else -> (args["old_string"] as? JsonPrimitive)?.contentOrNull != null &&
            (args["new_string"] as? JsonPrimitive)?.contentOrNull != null
    }
}

/**
 * write / edit 展开体的 diff 行（dsh 的 diffCardModel 的 **intended diff**：直接从参数推）。
 *
 * ADSH 的工具行只拿得到参数与输出正文（结构化的 before/after 没有单独落库），所以走 dsh 在
 * 「结果没有 diffs 元数据」时的同一条退路 —— 用参数现推：
 *  - write：oldText = null、newText = content（整篇都是新增）；
 *  - edit：oldText = old_string、newText = new_string。
 * 返回 null（不是 write/edit、或参数被截断取不到字段）时退回普通的 ioCard。
 */
private fun diffRowsOf(name: String, arguments: String): List<DiffRow>? {
    val args = ToolArgs.parse(arguments) ?: return null
    val path = pickString(args, listOf("path", "file_path"))?.let { firstLine(it) }?.takeIf { it.isNotEmpty() }
        ?: return null
    return when (name) {
        "write" -> {
            val content = (args["content"] as? JsonPrimitive)?.contentOrNull ?: return null
            diffRows(path, oldText = null, newText = content)
        }
        "edit" -> {
            val old = (args["old_string"] as? JsonPrimitive)?.contentOrNull ?: return null
            val new = (args["new_string"] as? JsonPrimitive)?.contentOrNull ?: return null
            diffRows(path, oldText = old.ifEmpty { null }, newText = new)
        }
        else -> null
    }
}

/**
 * 参数原文 → 对象（**容错**：库里的老数据可能是被截断的 JSON，见 [ToolArgs]）。
 * 只在 RailToolRow 的 remember 里用一次，解析结果按参数原文缓存。
 */
private fun parseArgs(raw: String): JsonObject? = ToolArgs.parse(raw)

private fun jsonPretty(args: JsonObject): String =
    runCatching {
        railJson.encodeToString(JsonObject.serializer(), args)
    }.getOrElse { args.toString() }

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
        Triple(summaryOf(spec, tool.name, tool.arguments), inputBodyOf(spec, tool.arguments), parsed)
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
    val summary = cardSummary ?: derived.first
    val input = derived.second
    // 文件路径（可点）与 write/edit「能不能画 diff」都只依赖参数原文，跟摘要一起缓存。
    // **diff 本身不算**：它是最贵的一个推导（行级 Myers diff），收起时根本用不到 ——
    // 以前无条件算，模型一次发来 5 个 edit 时同一帧要算 5 份（用户实测的「一次性调用六个
    // 工具，明显卡顿，像是加载」）。现在等到真的展开那一行再算（见下面的 open 分支）。
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
    val expandable = hasImages || code != null || canDiff || searchCard != null ||
        webSearchCard != null || webFetchCard != null || todoCard != null || presentOutput != null ||
        askCard != null || input != null || tool.output != null
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
    // 这一行的扫光时钟起点：被组合的子行要跟它同相（第 90 轮用户实测），所以整棵子树共用它
    val sweepEpoch = rememberSweepEpoch()
    // **必须包一层 Column**：本函数会发射两个兄弟节点（自己的行 + 下面的子调用树），而调用方
    // TurnEntryRow 把每一行放进一个 Box —— Box 会把兄弟节点**叠在同一个位置**，
    // 于是 run_code 的第一条子调用正好压在 run_code 那一行上（第 76 轮用户实测：
    // 「第一个被组合的工具和 run-code 重叠，下面几个位置是对的」—— 下面的之所以看着对，
    // 只是因为它们本来就在父行下方）。第 74 轮把外层 Column 删掉时漏了这一点。
    CompositionLocalProvider(LocalSweepEpoch provides sweepEpoch) {
    Column(Modifier.fillMaxWidth()) {
    DshDisclosureRow(
        icon = spec.icon,
        title = spec.title,
        open = open,
        expandable = expandable,
        running = tool.running,
        // error 时前置换成状态点（dsh 的 leadingFor），带 100ms 交叉淡出
        failed = tool.error,
        sweepEpoch = sweepEpoch,
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
                // **同一个 Text 节点**（第 101 轮，用户报「写入 / 编辑 / 读取一起出现时偶尔闪一下」）：
                // 以前「链接」与「普通摘要」是两个不同的 composable 分支，参数一变（行从流式行换成
                // 库里那行、或 `write` / `edit` 的正文被 SubCallTrimmer 截断过）就在两个分支之间
                // **整棵替换** —— 而 read / write / edit 正是唯一会走链接分支的三个变体
                // （[FILE_PATH_VARIANTS]），所以「一起出现时闪一下」总是落在这三种行上。
                // 现在只有一个 Text 调用点：差别落在修饰符与颜色上，节点身份不变。
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
                    // 卡片正文滚到头之后剩下的位移/惯性留在这里，不再交给会话的 LazyColumn
                    // （第 115 轮用户实测：手指还在卡片里、主界面却跟着动）。挂在这一层 =
                    // 卡片里所有滚动容器一次覆盖；见 [ScrollEdgeEater] 的注释。
                    .nestedScroll(ScrollEdgeEater)
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
}

/**
 * 子调用树（dsh 的 .ztWv_q_subCalls）：
 * border-left .5px border-l2 / margin 4px 0 2px 22px / padding-left 8px / gap 4px。
 * 每个子调用本身也是一整行工具调用，可以展开看输入与输出。
 *
 * [onReaderAction] 必须一路传进来（第 87 轮用户实测：agent 思考中展开**已执行完的子调用**，
 * 展开体刚长出来视口就被钉回底部）：PTC 下所有工具都是 run_code 的子调用，子行不接这个回调时
 * 第 122 轮那套「读者动作先交出跟随」就落不到它身上（跟随还是真，下一次内容变化又把它钉回底部）。
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
                // 竖线画在 margin 的边界上（= 本 Column 的 0 点），**不是** padding 之后。
                // 这里原先写的是 Offset(-8dp)：drawBehind 的 0 点已经在外层 padding(start=22dp)
                // 之内，再左移 8dp 就把线画到了 14dp 处，比 dsh 的 22px 偏左了整整 8px。
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
private fun RailImageGallery(images: List<com.adsh.app.core.agent.ToolImage>, compact: Boolean) {
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
            val bitmap by androidx.compose.runtime.produceState<ImageBitmap?>(
                initialValue = null,
                attachment.path,
                targetPx,
            ) {
                value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cachedImage(attachment.path, targetPx)
                }
            }
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
 * bash 的展开体已经换成 TerminalBlock（输出区 224dp，dsh 同值），不再走这里。
 */
private val IO_SECTION_MAX_HEIGHT = 200.dp

/** dsh 的 ioCard：.5px border-l1 + code-block 底 + 圆角 12，两栏（标签 caption / 内容 secondary） */
@Composable
private fun RailIoCard(input: String?, output: String?, error: Boolean) {
    val palette = LocalDshPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.Md)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .border(DshSpacing.Hairline, palette.borderL1, RoundedCornerShape(12.dp)),
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
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelCaption)
        Text(
            text = text,
            modifier = Modifier.weight(1f).scrollableBody(),
            fontSize = 12.sp,
            lineHeight = 18.sp,
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
 * dsh 的 [data-follow-end]（ReasoningRow 运行中的摘要）：等价于 CSS 的
 * width: max-content + min-width: 100% + justify-content: flex-end。
 *
 * 也就是：**比行宽短时仍然从左开始**（min-width 撑满整行，文字按 start 排），
 * 只有比行宽更长时才整块贴到行尾、把左边溢出裁掉 —— 一直看得到最新写出来的字。
 * 直接右对齐的写法会让短摘要也贴到右边，看着变成「从右往左长」。
 */
/*
 * 思考行流式摘要的右端渐隐（dsh 的 `[data-streaming]`：`mask-image: linear-gradient(90deg,
 * #000 calc(100% - 48px), #0000)`）**第 95 轮去掉了**（用户点名「思考时一个字或者词的右侧
 * 像是被白雾遮挡了一块」）。
 *
 * Compose 没有 mask-image，这里原先是用「内容画完后在右端叠一层透明→底色的渐变」顶替的 ——
 * 那是一层**盖在字上面**的底色，而 follow-end 让最新写出来的几个字一直贴在右端，正好落在
 * 渐隐带里：看起来就是被一团白雾糊住。既然最右端就是最新内容，宁可不做渐隐 ——
 * 现在只 `clipToBounds`，最新的字完整可读，左边的旧内容照旧被裁掉。
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
 * dsh 的 ReasoningRow（ReasoningRow.module.css + DisclosureRow 逐条对齐）：
 *
 *  - 收起时：图标 + 「思考」+ 2×2 圆点 + 摘要；**展开时摘要整行不渲染**
 *    （dsh 的 DisclosureRow 只在 !open 时渲染 collapsedContent，这里没有 keepContentWhenOpen），
 *    行上只剩倒角 + 标题，正文在下面（thinkBody：左缩进 22、上下 4、pre-wrap）。
 *  - 摘要：运行中取最后一行（latestLine），结束后取第一行（firstLine），都去掉 ** 标记。
 *  - 运行中的摘要按 dsh 的 [data-follow-end] 渲染：容器右对齐 + 裁剪左边，
 *    也就是**永远显示最新的那一段**。按左对齐截尾的话，模型写一大段没有换行的思考时，
 *    可见的永远是开头那几个字，看起来就像「半天不动」（这就是「思考变化太慢」的根因）。
 *  - 文字本身始终从左往右排，只是整块贴在行尾；只有比行宽更长时才裁掉左边。
 */
@Composable
private fun RailReasoning(text: String, running: Boolean, onReaderAction: (Boolean) -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(false) }
    // 运行中的摘要取**最后一行**（dsh 的 data-follow-end：整块贴到行尾、左边被裁掉，
    // 于是永远看得到最新写出来的字）；结束后取第一行。
    val summary = (if (running) latestLine(text) else firstLine(text)).replace("**", "")
    DshDisclosureRow(
        icon = DshIcons.Think,
        title = "思考",
        open = open,
        running = running,
        onToggle = {
            val expanded = !open
            onReaderAction(expanded)
            open = expanded
        },
        trailing = if (open) {
            { Spacer(Modifier.weight(1f)) }
        } else {
            {
                // 展开时圆点与摘要一起消失（dsh 展开后行里只剩 leading + title）
                RailDot()
                if (running) {
                    // 流式时摘要只裁左边（[followEnd]），**右端不再叠渐隐**（第 95 轮：
                    // 那层底色正好盖住最新写出来的字，用户看到的是「字的右侧被白雾遮挡」）
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
        // 展开体：dsh 的 thinkBody —— 左缩进 22px（与头部图标列对齐）、上下 4px、**无高度上限**，
        // 内容按紧凑 Markdown 渲染（13/20 三级色）
        body = {
            MarkdownBody(
                text = text,
                compact = true,
                modifier = Modifier.padding(start = 22.dp, top = DshSpacing.Md, bottom = DshSpacing.Md),
            )
        },
    )
}

// ------------------------------------------------------------------ 轮尾动作（dsh 的 MessageIconActions）

/**
 * dsh 的 TurnTailNodeView：轮结束后一排 28px 的动作。
 * 顺序（dsh 的 MessageIconActions）：复制 → 分支 → 用量 → 用时。
 * 几何取自 CSS：actions = 28px 高 / gap 8px / margin-top 4px、action = 28×28 圆角 28 / 图标 15px、
 * pill = 28px 高 / 圆角 28 / 内边距 6px 8px / gap 4px / 13px 文字 / tabular-nums。
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
    Row(
        // 注意顺序：padding 在外、height 在内。反过来会把 28dp 的胶囊压进 24dp 的盒子里，
        // 文字上下各被裁掉一截（截图里「用量 2.8K tok」只剩上半截就是这么来的）
        modifier = Modifier.fillMaxWidth().padding(top = DshSpacing.Md).height(28.dp),
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
        usage?.let { RailUsagePill(it) }
        runMillis?.let { RailTimePill(runMillis, usage) }
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
private fun RailUsagePill(usage: TurnUsage) {
    var open by remember { mutableStateOf(false) }
    Box {
        RailStatPill(
            icon = DshIcons.Database,
            label = "用量 " + formatTokensCompact(usage.totalTokens) + " tok",
            open = open,
            onClick = { open = !open },
        )
        if (open) {
            DshPopup(onDismiss = { open = false }) {
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
private fun RailTimePill(runMillis: Long, usage: TurnUsage?) {
    var open by remember { mutableStateOf(false) }
    Box {
        RailStatPill(
            icon = DshToolIcons.Clock,
            label = "用时 " + formatRunDuration(runMillis),
            open = open,
            onClick = { open = !open },
        )
        if (open) {
            DshPopup(onDismiss = { open = false }) {
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

/** dsh 的 .Q51KRG_trigger：28px 高 / 圆角 28 / 内边距 6px 8px / 图标 15px + 4px + 13px 文字 */
@Composable
private fun RailStatPill(icon: ImageVector, label: String, open: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(28.dp))
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
 * 也就是说长行是折行的。之前这里还挂了 horizontalScroll：文字会按「一行到底」去排版
 * （宽度不受约束），排版量随内容长度暴涨，展开一大段输出时又慢又难读。
 */
@Composable
private fun Modifier.scrollableBody(): Modifier =
    this.verticalScroll(rememberScrollState())

private fun latestLine(text: String): String {
    val trimmed = text.trimEnd()
    return trimmed.substringAfterLast('\n').trim()
}

/** dsh 的 duration.seconds / compactMinutes：「1分20秒」「12秒」 */
internal fun formatRunDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    // dsh 的 duration.minutes =「{minutes}分{seconds:02}秒」：秒数补零（1分05秒）
    return if (minutes > 0) {
        minutes.toString() + "分" + (seconds % 60).toString().padStart(2, '0') + "秒"
    } else {
        seconds.toString() + "秒"
    }
}

private val railJson = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
