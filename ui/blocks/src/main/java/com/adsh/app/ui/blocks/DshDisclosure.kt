package com.adsh.app.ui.blocks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import kotlinx.coroutines.delay

/*
 * 会话里「一行」用到的全部基元：行骨架、图标位、倒角、分隔点、运行流光（文字微光）。
 *
 * 行几何只在这里定义一次：TurnRail（工具行 / 思考行）与 DshDisclosure（系统提示行 / 上下文行）
 * 都读这一份，不许各写一套 leading / 倒角 / 流光（dsh 的 primitives/DisclosureRow.module.css
 * 就是这样一个共享构件）。
 *
 * 运行态的流光 = TextShimmer.kt 的 [DshTextShimmer]：文字本体上掠过一条颜色带（dsh 的
 * primitives/TextShimmer）。**不要**改回整行扫过一道底色 —— 那是旧实现，既不是 dsh 的样子，
 * 也会让每一行每帧都重绘整条行。
 */

// ------------------------------------------------------------------ 行骨架

/** 行标题字号（dsh 的 `.title` 是 13px，secondary 档）；尺寸写死，调用方不可覆盖。 */
private val TITLE_SIZE = 13.sp

/**
 * 会话行里的**一段文字**（标题 / 摘要 / 后缀）：单行、超出省略。
 * 这类文字只有这一个定义 —— 字号 / 行高 / 省略行为不许在这里之外再写一份。
 */
@Composable
fun DshRowTitle(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * dsh 的 DisclosureRow（primitives/lib/DisclosureRow.module.css）——**会话里所有可展开行的唯一骨架**：
 * 思考行、工具行、系统提示行、上下文注入行都用它，行几何只在这里定义一次。
 *
 * ```
 * .row      height calc(24px + D)、position:relative、overflow:hidden、flex 居中
 * .leading  16×16 的图标格（margin-right 6px、色 label-tertiary），里面 svg 14×14
 * .title    13px（secondary 档）/ calc(24px + D)、颜色继承行色 label-tertiary、字重 400
 * .iconIdle / .chevronHover  opacity 100ms ease 交叉淡出
 * ```
 *
 * dsh 是「悬停时图标换成倒角」；触屏没有悬停，这里用「**展开时**换成向上倒角、收起时是本体图标」
 * 表达同一件事（收起态也确实需要倒角来提示可展开）。
 *
 * @param running 运行态（preparing / running）：**标题 + 摘要整块文字**上掠过 dsh 的流光带
 *   （见 [DshTextShimmer]）。图标不在流光里 —— 与 dsh 的 DisclosureRow 一致。
 * @param failed 这一行失败了：图标格换成一个 6dp 的状态点。dsh 的工具行错误态**不换** leading
 *   （图标始终是业务图标，状态只由摘要红色与 data-state 表达）；这是 ADSH 自有的失败标记，
 *   尺寸与 dsh 的 StateDot 圆核一致（10px 槽里的 6px 核）。
 * @param trailing 标题之后的内容（分隔点 + 摘要 + 后缀），通常自带 weight(1f)
 * @param expandable 不可展开的行没有点击手势（dsh 的 preparing 阶段）
 */
@Composable
fun DshDisclosureRow(
    icon: ImageVector?,
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandable: Boolean = true,
    running: Boolean = false,
    failed: Boolean = false,
    rowHeight: Dp = 24.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDshPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .dshClickable(interactionSource = dshInteraction(), enabled = expandable) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                RailLeading(icon = icon, open = open && expandable, failed = failed)
            }
            // dsh 的 TextShimmer 只包「标题 + 折叠内容」这一块，图标留在外面：
            // 同一条带子因此连续扫过标题、分隔点与摘要（不是一个字一个字数地各扫各的）
            DshTextShimmer(active = running, modifier = Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DshRowTitle(
                        text = title,
                        // dsh 的 .title 是 13px（secondary 档），行高按内容字号的 1.85 倍；
                        // 颜色继承行色（.row 是 label-tertiary），不是二级色
                        fontSize = TITLE_SIZE,
                        lineHeight = TITLE_SIZE * 1.85f,
                        color = palette.labelTertiary,
                    )
                    if (trailing != null) trailing()
                }
            }
        }
        if (open && body != null) {
            Box(Modifier.fillMaxWidth()) { body() }
        }
    }
}

/**
 * 图标格里画什么。**纯函数**（单测直接打 `RailLeadingTest`）：**失败优先** —— 失败了画状态点，
 * 否则展开画倒角、收起画本体图标。判定只看状态、不看动画中间值（见 [railLeadingState]）。
 */
enum class RailLeadingState { DOT, CHEVRON, ICON }

fun railLeadingState(open: Boolean, failed: Boolean): RailLeadingState = when {
    failed -> RailLeadingState.DOT
    open -> RailLeadingState.CHEVRON
    else -> RailLeadingState.ICON
}

/**
 * dsh 的 .leading（16×16）：收起 = 本体图标（14px、label-tertiary），展开 = 倒角（label-secondary）；
 * 失败 = ADSH 自有的 6dp 状态点（error 色，与 dsh 的 StateDot 圆核同尺寸）。
 * **红点不挂动画**：`failed` 为真就满不透明画出来 —— 否则流式期间失败那一格会整个空着
 * （见 [railLeadingState]）。
 */
@Composable
private fun RailLeading(icon: ImageVector, open: Boolean, failed: Boolean = false) {
    val palette = LocalDshPalette.current
    // dsh 的 .leading 是「16×16 内容盒 + margin-right 6px」= 22px 步进。
    // **不能写成 .size(16.dp).padding(end = 6.dp)**：padding 在 size 之后，会把内容压到 10dp，
    // 14dp 的图标被约束成 10dp（比 dsh 瘦一圈）。
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (railLeadingState(open = open, failed = failed)) {
                // 失败：状态点（6px、error 色）—— **不透明的实心点，不参与任何淡入淡出**，见上面的注释
                RailLeadingState.DOT -> Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(palette.errorLabel),
                )
                // 展开时朝下（0°）—— 与折叠行的倒角同一套角度：收起朝右、展开朝下（用户口径）
                RailLeadingState.CHEVRON -> Icon(
                    imageVector = DshIcons.ChevronDown,
                    contentDescription = null,
                    tint = palette.labelSecondary,
                    modifier = Modifier.size(14.dp),
                )
                RailLeadingState.ICON -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(DshSpacing.Lg))
    }
}

/** 图标格交叉淡出的时长（dsh 的 `transition: … .1s`） */
const val RAIL_LEADING_FADE_MS = 100

/** 行内来源标签（dsh 的 .XrJvXW_source）：13/24 三级色，前置 2×2px 圆点 */
@Composable
private fun RailSourceLabel(text: String, summary: String? = null) {
    val palette = LocalDshPalette.current
    RailDot()
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 24.sp,
        color = palette.labelTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
    if (!summary.isNullOrBlank()) {
        RailDot()
        Text(
            text = summary,
            fontSize = 13.sp,
            lineHeight = 24.sp,
            color = palette.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 2×2px 圆点，左右各 8px（dsh 的 .lcKema_separator / .o3BgMG_sep） */
@Composable
fun RailDot() {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .padding(horizontal = DshSpacing.Xl)
            .size(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(palette.labelCaption),
    )
}

/**
 * 轮次控制行的倒角（`.l_V-RG_chevron`：14×14、label-caption、margin-left 4px、
 * `transition: transform .1s`）。角度与 dsh 相反，按用户口径：**收起朝右（-90°）、展开朝下（0°）**。
 */
@Composable
fun RailChevron(open: Boolean) {
    val palette = LocalDshPalette.current
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(100), label = "railChevron")
    Spacer(Modifier.width(4.dp))
    Icon(
        DshIcons.ChevronDown,
        contentDescription = null,
        tint = palette.labelCaption,
        modifier = Modifier.size(14.dp).rotate(rotation),
    )
}

// ------------------------------------------------------------------ 具体行

/** dsh 的 context 行正文框：等宽 11/16、圆角 8、code-block 底、最多 141px */
@Composable
private fun DshCodeBody(text: String) {
    val palette = LocalDshPalette.current
    val scroll = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = DshSpacing.Md, bottom = DshSpacing.Md)
            .heightIn(max = 141.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.codeBlock)
            .dshVerticalScrollbar(scroll, elevated = true, topInset = 4.dp, bottomInset = 4.dp)
            .verticalScroll(scroll)
            .padding(start = DshSpacing.Xxxl, top = DshSpacing.Xxl, end = DshSpacing.Card, bottom = DshSpacing.Xxxl),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = palette.labelTertiary,
        )
    }
}

// 卡片**不许吃滚动链**：手指落在卡片上继续滑，内层滚到头之后要带动会话上下滑（dsh / 浏览器的
// 默认行为）。别在这里挂 ScrollEdgeEater 那类拦截件。


/**
 * dsh 的 SystemPromptRow（message.systemPrompt / message.systemPromptUpdate）：
 * 浏览图标 14 + 标题 + 倒角；展开是系统提示词全文。
 *
 * [onReaderAction]：会话里**任何一行**的展开/收起都要停自动跟随（HANDOFF.md 的『不要破的硬规矩』）。
 */
@Composable
fun SystemPromptRow(text: String, update: Boolean, onReaderAction: () -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(false) }
    DshDisclosureRow(
        // dsh 的 SystemPromptRow 用的是 IconBrowseOutline16（不是文件图标）
        icon = DshToolIcons.Browse,
        title = if (update) "系统提示词更新" else "系统提示词",
        open = open,
        onToggle = {
            onReaderAction()
            open = !open
        },
        body = { DshCodeBody(text) },
    )
}

/**
 * dsh 的 ContextInjectionRow（message.contextInjection / message.contextRecall）：
 * 上下文注入图标 14 + 标题 + 圆点 + 来源标签（+ 摘要）+ 倒角；展开是注入原文。
 */
@Composable
fun ContextInjectionRow(
    label: String,
    form: String,
    text: String,
    onReaderAction: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    DshDisclosureRow(
        // dsh 的 ContextInjectionRow 用的是 IconContextInjectionOutline16
        icon = DshToolIcons.ContextInjection,
        title = "上下文注入",
        open = open,
        onToggle = {
            onReaderAction()
            open = !open
        },
        trailing = if (label.isBlank()) null else {
            { RailSourceLabel(label, summary = formSummary(form)) }
        },
        body = { DshCodeBody(text) },
    )
}

private fun formSummary(form: String): String? = when (form) {
    "instructions" -> "已载入"
    else -> null
}

/** dsh 的 --dsw-static-deepseek-500（品牌蓝）：DeepSeek 鲸鱼 mark 的 tint（= dsh RunningStatus 的 currentColor） */
val DeepseekBlue500 = Color(0xFF4176E6)

/** dsh 的 --dsw-alias-label-deep-diving（浅色）：deepseek-500 70% + blue-950 = #345EBA */
val DeepseekDiving = Color(0xFF345EBA)

/** dsh 的 --dsw-alias-label-deep-diving-shimmer（浅色）：deepseek-500 30% + blue-950 = #243D80 */
private val DeepseekDivingShimmer = Color(0xFF243D80)

/** 深色档的同一对令牌：deep-diving = blue-300 55% + neutral-bluish-400；shimmer = blue-300 65% + deepseek-400 */
private val DeepseekDivingDark = Color(0xFF9FBCDE)
private val DeepseekDivingShimmerDark = Color(0xFF8ABCFF)

/**
 * dsh 的 RunningStatus（chat/RunningStatus.tsx + ChatView.module.css .running）：
 * 会话流**末尾的最后一行**「本轮模型活动」—— 跟着内容一起滚，不是输入框上方的一条浮层
 * （dsh 的 ChatFlow 把它挂在 rows 之后）。
 *
 * 几何照 dsh：鲸鱼尾 16dp、间隔 6dp、行高 26dp，右侧 8dp 之后是 13sp 用时；正文 13sp（用户口径：
 * 固定 13，不按 dsh 的「内容字号减 2」推导）。文字**只做流光带**（[DshTextShimmer]）：dsh 的
 * RunningStatus 是 text-shimmer 里的深蓝，不是亮蓝扫过 —— 亮蓝在浅色底上会淡到像整行消失。
 *
 * @param startedAt 本轮开始时刻（0 = 自己取当前时间）
 */
@Composable
fun TurnStatusRow(startedAt: Long, modifier: Modifier = Modifier, label: String = "吃白饭中...") {
    val palette = LocalDshPalette.current
    val anchor = if (startedAt > 0) startedAt else remember { System.currentTimeMillis() }
    var elapsed by remember { mutableLongStateOf((System.currentTimeMillis() - anchor).coerceAtLeast(0)) }
    LaunchedEffect(anchor) {
        while (true) {
            elapsed = (System.currentTimeMillis() - anchor).coerceAtLeast(0)
            delay(1000)
        }
    }
    val base = if (palette.dark) DeepseekDivingDark else DeepseekDiving
    val shimmer = if (palette.dark) DeepseekDivingShimmerDark else DeepseekDivingShimmer
    Row(
        modifier = modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RunningWhaleTail(modifier = Modifier.size(WHALE_TAIL_SIZE_DP.dp), tint = base)
        Spacer(Modifier.width(WHALE_TAIL_GAP_DP.dp))
        DshTextShimmer(active = true, tint = shimmer) {
            Text(
                text = label,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                color = base,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatRunDuration(elapsed),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelCaption,
        )
    }
}
