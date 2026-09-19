package com.adsh.app.ui

import android.content.ClipData
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.core.ptc.SubCall
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
 * 折叠行（dsh 的 TurnProcessNodeView）：
 * 33px 高、标签在左（14/24 二级色）、倒角在右（16px，-90° → 0°）、下边框 .5px border-l2。
 */
@Composable
fun TurnFoldRow(
    view: TurnView,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDshPalette.current
    val labels = buildList {
        if (view.toolCount > 0) add(view.toolCount.toString() + " 次工具调用")
        if (view.messageCount > 0) add(view.messageCount.toString() + " 条消息")
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(33.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggle() }
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // dsh 的 .l_V-RG_label 是 flex:none（没有 flex-grow）：标签占自己需要的宽度，
            // 倒角紧跟文字后面（margin-left 6px），而不是被推到行的最右边。
            // fill = false 对应 CSS 的 flex:0 1 auto —— 需要时仍可收缩，但不会撑满整行。
            Text(
                text = if (labels.isEmpty()) "已思考" else labels.joinToString(" · "),
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RailChevronInternal(open = open, tint = palette.labelTertiary, leadingGap = 6.dp)
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.borderL2)
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
) {
    val entry = view.entries.getOrNull(index) ?: return
    val isAnswer = index == view.answerIndex
    Box(modifier.fillMaxWidth()) {
        if (isAnswer && entry is ProcessEntry.Text) {
            AssistantText(entry.text)
        } else {
            EntryRow(entry)
        }
    }
}

/**
 * 轮尾（dsh 的 message.stopped + TurnTailNodeView）：被打断的标记 + 复制 / 分支 / 用量 / 用时。
 */
@Composable
fun TurnTailRow(
    view: TurnView,
    branchable: Boolean,
    onBranch: (Long) -> Unit,
    modifier: Modifier = Modifier,
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
                    .padding(horizontal = 6.dp),
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
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

/** 一条过程条目：思考 / 过程文本 / 工具调用 */
@Composable
private fun EntryRow(entry: ProcessEntry) {
    when (entry) {
        is ProcessEntry.Reasoning -> RailReasoning(entry.text, running = entry.running)
        is ProcessEntry.Text -> AssistantText(entry.text)
        is ProcessEntry.Call -> RailToolRow(
            RailTool(
                name = entry.name,
                arguments = entry.arguments,
                output = entry.output,
                running = entry.running,
                error = entry.isError,
                children = entry.subCalls,
            ),
        )
    }
}

// ------------------------------------------------------------------ 工具行（dsh 的 ToolRow / ToolCallTree）

/** dsh 的 ToolRow 变体（TOOL_VARIANTS）：决定图标、标题与摘要取哪个参数 */
internal enum class ToolVariant { SEARCH, READ, BASH, WRITE, EDIT, CODE, OTHERS }

/** 一行工具调用的展示模型 */
internal data class RailTool(
    val name: String,
    val arguments: String,
    val output: String? = null,
    val running: Boolean = false,
    val error: Boolean = false,
    val children: List<SubCall> = emptyList(),
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
    "web_search" -> ToolSpec(ToolVariant.SEARCH, "网页搜索", DshToolIcons.Search)
    "grep" -> ToolSpec(ToolVariant.SEARCH, "Grep", DshToolIcons.Search)
    "glob" -> ToolSpec(ToolVariant.SEARCH, "Glob", DshToolIcons.Search)
    "write" -> ToolSpec(ToolVariant.WRITE, "写入", DshSidebarIcons.Edit)
    "edit" -> ToolSpec(ToolVariant.EDIT, "编辑", DshSidebarIcons.Edit)
    "run_code" -> ToolSpec(ToolVariant.CODE, "代码", DshToolIcons.Code)
    // dsh 的工具名是 todo_write（旧会话里可能还留着老的 todo）
    "todo_write", "todo" -> ToolSpec(ToolVariant.OTHERS, "更新任务清单", DshToolIcons.Sparkle)
    "ask_user_question" -> ToolSpec(ToolVariant.OTHERS, "提问", DshToolIcons.Sparkle)
    // dsh-client-ui-deliverables 的 row.title =「交付文件」
    "present" -> ToolSpec(ToolVariant.OTHERS, "交付文件", DshToolIcons.Sparkle)
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

private fun pickString(args: JsonObject?, keys: List<String>): String? {
    if (args == null) return null
    keys.forEach { key ->
        val value = (args[key] as? JsonPrimitive)?.contentOrNull
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

/**
 * 摘要（dsh 的 deriveSummary + toolRowModel）：
 * others 变体在没有专属标题时把工具名缀在前面（「present · xxx」）。
 */
private fun summaryOf(spec: ToolSpec, name: String, arguments: String): String {
    val args = parseArgs(arguments)
    if (args == null) return firstLine(arguments)
    if (spec.variant == ToolVariant.SEARCH) {
        val queries = (args["queries"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotEmpty() }
        if (!queries.isNullOrEmpty()) return queries.joinToString(", ") { firstLine(it) }
    }
    val picked = pickString(args, SUMMARY_KEYS[spec.variant].orEmpty())
    if (picked != null) return firstLine(picked)
    args.values.forEach { value ->
        val text = (value as? JsonPrimitive)?.contentOrNull
        if (!text.isNullOrEmpty()) return firstLine(text)
    }
    val base = firstLine(arguments)
    return if (spec.variant == ToolVariant.OTHERS && name.isNotEmpty()) name + " · " + base else base
}

/**
 * 展开后的「输入」体（dsh 的 formatToolBody）：
 * 代码行取 code 原文，其余是格式化后的 JSON；带路径的文件类工具不铺输入（dsh 的 singleFile）。
 */
private fun inputBodyOf(spec: ToolSpec, arguments: String): String? {
    if (arguments.isBlank()) return null
    val args = parseArgs(arguments)
    if (args == null) return arguments
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
    val args = parseArgs(arguments) ?: return null
    return (args["code"] as? JsonPrimitive)?.contentOrNull
        ?: (args["program"] as? JsonPrimitive)?.contentOrNull
}

private fun parseArgs(raw: String): JsonObject? =
    runCatching { railJson.parseToJsonElement(raw).jsonObject }.getOrNull()

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
private fun RailToolRow(tool: RailTool, compact: Boolean = false) {
    val palette = LocalDshPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val spec = specOf(tool.name)
    // 参数 JSON 的解析结果按参数原文缓存：流式期间这一行会因为「运行中/输出」不停重组，
    // 不缓存的话每一帧都要重新解析一遍参数并重算摘要
    val derived = remember(tool.name, tool.arguments) {
        val parsed = parseArgs(tool.arguments)
        Triple(summaryOf(spec, tool.name, tool.arguments), inputBodyOf(spec, tool.arguments), parsed)
    }
    val summary = derived.first
    val input = derived.second
    // error 时摘要换成输出的第一行（dsh 的 errorSummary）
    val failure = if (tool.error) tool.output?.let { firstLine(it) } else null
    val shown = failure ?: summary
    val code = if (spec.variant == ToolVariant.CODE) codeBodyOf(tool.arguments) else null
    val expandable = code != null || input != null || tool.output != null
    val titleSize = if (compact) 13.sp else 14.sp
    val lineHeight = if (compact) 22.sp else 24.sp
    val rowHeight = if (compact) 22.dp else 24.dp
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = expandable,
                    ) { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // error / stopped 时前置换成状态点（dsh 的 leadingFor）
                if (tool.error) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(palette.errorLabel),
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    RailLeading(
                        icon = spec.icon,
                        open = open && expandable,
                        size = if (compact) 13.dp else 14.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(spec.title, fontSize = titleSize, lineHeight = lineHeight, color = palette.labelSecondary)
                if (shown.isNotEmpty()) {
                    RailDotInternal()
                    Text(
                        text = shown,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        lineHeight = lineHeight,
                        color = if (tool.error) palette.errorLabel else palette.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (tool.running) RunningSweep(Modifier.matchParentSize())
        }
        if (open && expandable) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                if (code != null) {
                    // 代码行：高亮后的源码（dsh 的 CodeBlock lang=typescript，max-height 260px），
                    // 代码本身不再进 ioCard 的「输入」（dsh 的 cardBody 在 code 变体下是 null）
                    RailCodeBody(code)
                    if (tool.output != null) {
                        RailIoCard(input = null, output = tool.output, error = tool.error)
                    }
                } else if (spec.variant == ToolVariant.BASH && input != null) {
                    RailTerminalBody(command = input, output = tool.output, error = tool.error)
                } else if (input != null || tool.output != null) {
                    RailIoCard(input = input, output = tool.output, error = tool.error)
                }
            }
        }
        // 子调用：dsh 的 ToolCallTree（左侧竖线 + 缩进）
        if (tool.children.isNotEmpty()) RailSubCalls(tool.children)
    }
}

/**
 * 子调用树（dsh 的 .ztWv_q_subCalls）：
 * border-left .5px border-l2 / margin 4px 0 2px 22px / padding-left 8px / gap 4px。
 * 每个子调用本身也是一整行工具调用，可以展开看输入与输出。
 */
@Composable
private fun RailSubCalls(children: List<SubCall>) {
    val palette = LocalDshPalette.current
    // 竖线用 drawBehind 画在自己身上，不用 height(IntrinsicSize.Min)：
    // 固有测量会把整棵子树先量一遍（每个子行两趟），子调用一多就是它最慢。
    val border = palette.borderL2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 4.dp, bottom = 2.dp)
            .drawBehind {
                // dsh 的 .subCalls 是 border-left: .5px + margin-left: 22px + padding-left: 8px ——
                // 竖线画在 margin 的边界上（= 本 Column 的 0 点），**不是** padding 之后。
                // 这里原先写的是 Offset(-8dp)：drawBehind 的 0 点已经在外层 padding(start=22dp)
                // 之内，再左移 8dp 就把线画到了 14dp 处，比 dsh 的 22px 偏左了整整 8px。
                val width = 0.5.dp.toPx()
                drawRect(
                    color = border,
                    topLeft = Offset(0f, 0f),
                    size = Size(width, size.height),
                )
            }
            .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        children.forEach { child ->
            RailToolRow(
                RailTool(
                    name = child.name,
                    arguments = child.args,
                    output = child.result.ifBlank { null },
                    // 正在跑的子调用也要扫光（dsh 的 ToolRow[data-state=running]：
                    // 组合工具下面的子行各自处在运行态时同样有那一道 300px 扫光）
                    running = child.running,
                    error = !child.ok && !child.running,
                ),
                compact = true,
            )
        }
    }
}

/**
 * dsh 的 .CY-8Ka_leading：图标位在展开时换成向下的倒角（悬停态在触屏上的等价做法）。
 * 收起时就是工具/思考本来的图标，所以行尾不需要再放一个倒角。
 */
@Composable
private fun RailLeading(icon: ImageVector, open: Boolean, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val palette = LocalDshPalette.current
    if (open) {
        Icon(
            DshIcons.ChevronDown,
            contentDescription = null,
            tint = palette.labelSecondary,
            modifier = Modifier.size(size),
        )
    } else {
        Icon(icon, contentDescription = null, tint = palette.labelTertiary, modifier = Modifier.size(size))
    }
}

/** dsh 的 ioCard：.5px border-l1 + code-block 底 + 圆角 12，两栏（标签 caption / 内容 secondary） */
@Composable
private fun RailIoCard(input: String?, output: String?, error: Boolean) {
    val palette = LocalDshPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp)),
    ) {
        if (input != null) RailIoSection("输入", input, error = false)
        if (input != null && output != null) {
            HorizontalDivider(thickness = 0.5.dp, color = palette.borderL2)
        }
        output?.let { RailIoSection("输出", it, error = error) }
    }
}

/** dsh 的 ioSection：max-content 标签列 + 内容列，gap 14，max-height 150px 且可滚（不截断） */
@Composable
private fun RailIoSection(label: String, text: String, error: Boolean) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 150.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
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

/** dsh 的 TerminalBlock（bash 的展开体）：命令 + 输出，最多 224px 且可滚 */
@Composable
private fun RailTerminalBody(command: String, output: String?, error: Boolean) {
    val palette = LocalDshPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp))
            .heightIn(max = 150.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = command,
            modifier = Modifier.scrollableBody(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.labelPrimary,
        )
        if (!output.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = output,
                modifier = Modifier.scrollableBody(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (error) palette.errorLabel else palette.labelSecondary,
            )
        }
    }
}

/**
 * 代码块的「标签」与「高亮语言」是两件事，之前一个是常量、另一个硬编码在下面，看着像不一致：
 *  - 标签写 javascript —— 我们的 PTC 跑的是纯 JavaScript（dsh 的可擦除 TypeScript 没有搬过来）；
 *  - 高亮仍喂 typescript —— highlightCode 实现的是 TS 规则，而 JS 是它的子集，
 *    关键字 / 字符串 / 注释 / 数字都能覆盖到。
 */
private const val CODE_BLOCK_LABEL = "javascript"
private const val CODE_HIGHLIGHT_LANG = "typescript"

/**
 * 代码行的展开体（dsh 的 CodeBlock），逐项对齐 CodeBlock.module.css：
 *
 *  - **横幅**：padding 9px 14px、space-between、banner 底（code-block-banner）、字体 11/18；
 *    左边是语言标签（等宽、label-primary），右边是「复制」按钮（无边框纯文字，点一下变
 *    「复制成功」，1 秒后还原）—— 这就是之前少掉的那一块；
 *  - **正文**：等宽 12/18、code-block 底、整体圆角 12、内边距 16px、max-height 260px 可滚；
 *    dsh 的 pre 是 white-space:pre-wrap + word-break:break-all，所以长行**折行**而不是横向滚；
 *  - 横幅在 dsh 里是 position:sticky（贴在滚动区上沿），这里把滚动只放在正文那一层，等价。
 */
@Composable
private fun RailCodeBody(code: String) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }
    val highlighted = remember(code, palette) {
        CodeHighlightCache.get(code, palette === com.adsh.app.ui.theme.DarkDshPalette) {
            buildAnnotatedString {
                code.lineSequence().forEachIndexed { index, line ->
                    if (index > 0) append("\n")
                    append(highlightCode(line, CODE_HIGHLIGHT_LANG, palette))
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.codeBlockBanner)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = CODE_BLOCK_LABEL,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (copied) "复制成功" else "复制",
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("adsh", code))
                    copied = true
                },
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelPrimary,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/**
 * dsh 的 [data-follow-end]（ReasoningRow 运行中的摘要）：等价于 CSS 的
 * width: max-content + min-width: 100% + justify-content: flex-end。
 *
 * 也就是：**比行宽短时仍然从左开始**（min-width 撑满整行，文字按 start 排），
 * 只有比行宽更长时才整块贴到行尾、把左边溢出裁掉 —— 一直看得到最新写出来的字。
 * 直接右对齐的写法会让短摘要也贴到右边，看着变成「从右往左长」。
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
internal fun RailReasoning(text: String, running: Boolean) {
    val palette = LocalDshPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val summary = (if (running) latestLine(text) else firstLine(text)).replace("**", "")
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // dsh 的 .lcKema_leading：悬停/展开时图标位置换成倒角（触屏没有悬停，
                // 所以用「展开时变成向下的倒角」来表达）。行尾不再放第二个倒角。
                RailLeading(icon = DshIcons.Think, open = open)
                Spacer(Modifier.width(6.dp))
                Text("思考", fontSize = 14.sp, lineHeight = 24.sp, color = palette.labelSecondary)
                if (!open) {
                    // 展开时圆点与摘要一起消失（dsh 展开后 row 里只剩 leading + title）
                    RailDotInternal()
                    if (running) {
                        Text(
                            text = summary,
                            modifier = Modifier.weight(1f).clipToBounds().followEnd(),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = palette.labelTertiary,
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
                            color = palette.labelTertiary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (running) RunningSweep(Modifier.matchParentSize())
        }
        if (open) {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelTertiary,
            )
        }
    }
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
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }
    Row(
        // 注意顺序：padding 在外、height 在内。反过来会把 28dp 的胶囊压进 24dp 的盒子里，
        // 文字上下各被裁掉一截（截图里「用量 2.8K tok」只剩上半截就是这么来的）
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailIconAction(
            icon = if (copied) DshIcons.Check else DshToolIcons.Copy,
            label = if (copied) "已复制" else "复制",
        ) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("adsh", text))
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
 * dsh 的运行扫光（ReasoningRow / ToolRow 的 [data-state=running] 伪元素）：
 * @keyframes{0%{left:-300px} 90%,to{left:100%}} + 2.6s ease-out infinite ——
 * 前 90%（2.34s）用 ease-out 从行首外扫到行尾外，最后 10%（260ms）停住不动。
 */
@Composable
internal fun RunningSweep(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val sweepPx = with(density) { 300.dp.toPx() }
    val transition = rememberInfiniteTransition(label = "sweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepProgress",
    )
    Canvas(modifier.clipToBounds()) {
        // CSS ease-out = cubic-bezier(0, 0, 0.58, 1)
        val t = (progress / 0.9f).coerceAtMost(1f)
        val eased = easeOutCubic(t)
        val x = -sweepPx + eased * (size.width + sweepPx)
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    // dsh 的峰值是 bg 的 60%；这里按需求加强到 90%，扫光才看得出来
                    0.55f to bg.copy(alpha = 0.9f),
                    1f to Color.Transparent,
                ),
                startX = x,
                endX = x + sweepPx,
            ),
            topLeft = Offset(x, 0f),
            size = Size(sweepPx, size.height),
        )
    }
}

/** 近似 CSS 的 ease-out（cubic-bezier(0,0,.58,1)） */
private fun easeOutCubic(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}

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

/**
 * 代码高亮的小 LRU 缓存。
 *
 * 展开一轮会把里面每个代码块重新组合一遍，高亮是逐行正则 + 拼 AnnotatedString；
 * 同一段代码（同一轮反复展开、或者回到会话再展开）没必要重算。
 */
private object CodeHighlightCache {
    private const val MAX_ENTRIES = 64
    private val entries = object : LinkedHashMap<String, AnnotatedString>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>): Boolean =
            size > MAX_ENTRIES
    }

    fun get(code: String, dark: Boolean, compute: () -> AnnotatedString): AnnotatedString {
        val key = (if (dark) "d:" else "l:") + code
        synchronized(entries) { entries[key] }?.let { return it }
        val value = compute()
        synchronized(entries) { entries[key] = value }
        return value
    }
}



