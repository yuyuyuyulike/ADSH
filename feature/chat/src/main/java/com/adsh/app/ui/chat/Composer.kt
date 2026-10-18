package com.adsh.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.ui.blocks.AnchorRect
import com.adsh.app.ui.blocks.DshIcons
import com.adsh.app.ui.blocks.FileTypeIcon
import com.adsh.app.ui.blocks.RegisterOverlayDismiss
import com.adsh.app.ui.blocks.RiskConfirmationDialog
import com.adsh.app.ui.blocks.dshProminentShadow
import com.adsh.app.ui.blocks.dshSoftShadow
import com.adsh.app.ui.blocks.fileSizeText
import com.adsh.app.ui.blocks.isImagePath
import com.adsh.app.ui.blocks.rememberImage
import com.adsh.app.ui.blocks.toAnchorRect
import com.adsh.app.ui.blocks.unionAnchor
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import java.io.File
import kotlin.math.roundToLong
import com.adsh.app.seam.exec.Permissions

/**
 * dsh 的 leadingInput 命令（只有这条带 hint）：token 直接在输入框里着色，
 * 用户接着输入内容，回车即把内容作为参数执行该指令。
 */
private val CLAIM_TOKENS = listOf("/plan")

/** 取出草稿开头的 claim token（dsh 的 input.claim.token） */
fun claimTokenOf(draft: String): String? {
    val text = draft.trimStart()
    return CLAIM_TOKENS.firstOrNull { token ->
        text == token || (text.startsWith(token) && text.length > token.length && text[token.length].isWhitespace())
    }
}

/** dsh 的 hint.plan（token 之后还没输入内容时显示） */
private fun claimHintOf(token: String?): String? = when (token) {
    "/plan" -> COMPOSER_HINT_PLAN
    else -> null
}


/** 触发菜单窗口的高度上限（dsh 的 .menu{max-height:400px}，见 MenuView.module.css:12） */
private val PALETTE_MAX_HEIGHT = 394.dp
/**
 * 触发菜单的最小高度（比模型菜单的 160dp 小得多）：模型菜单是**独立窗口**（底边往上长），触发菜单
 * 是**同层卡片**（超出在卡片内部滚）、底边钉在输入卡片上沿 —— 下限大过「卡片上方可用空间」时多出来
 * 的部分会顶出屏幕上沿。96dp 只是兜底，真正生效的是 [popupPanelMaxHeight] 夹出来的值。
 */
private val PALETTE_MIN_HEIGHT = 96.dp
/** 触发菜单与输入卡片上沿的间距（dsh 的 .menu{bottom: calc(100% + 4px)}） */
private val PALETTE_GAP = 4.dp
/** 触发菜单卡片顶边至少要避开的距离（状态栏高度拿不到时用它） */
private val PALETTE_TOP_MARGIN = 8.dp



/** dsh 的输入框占位文案（placeholder.default） */
const val COMPOSER_HINT = "发消息或创建任务, / 调用指令, @ 文件或对话"

/** dsh 的 placeholder.plan（plan mode 生效时的占位文案） */
const val COMPOSER_HINT_PLAN = "描述你的任务以生成计划"

// 触发菜单的行与规则（PALETTE_SECTION_* / PaletteCommand / slashQueryOf …）在 Palette.kt：
// 那一层只认字符串，能在 JVM 单测里逐条钉死；这里只管把它画出来。

/** 权限预设（dsh 的 permission preset 三档，图标为 dsh 的三枚 glyph） */
data class PermissionPreset(val id: String, val label: String, val icon: ImageVector, val hint: String)

val PERMISSION_PRESETS = listOf(
    PermissionPreset(
        Permissions.READ_ONLY, "仅可查看", DshIcons.ShieldCheck,
        "只读：禁止写文件与执行命令",
    ),
    PermissionPreset(
        Permissions.WORKSPACE_WRITE, "工作区内修改", DshIcons.ShieldEdit,
        "写操作限定在工作区内，允许执行命令",
    ),
    PermissionPreset(
        Permissions.FULL_ACCESS, "完全权限", DshIcons.ShieldAlert,
        "不做额外限制，可直接执行外部命令",
    ),
)

fun permissionPreset(id: String): PermissionPreset =
    PERMISSION_PRESETS.firstOrNull { it.id == id } ?: PERMISSION_PRESETS.first()

/** 三段色与 dsh 的 ContextMeter 一致：colorSystem / colorTools(#a78bfa) / colorMessages */
@Composable
private fun contextColors(): Triple<Color, Color, Color> {
    val p = LocalDshPalette.current
    return Triple(p.system, p.tools, p.messages)
}

/** dsh 的 formatTokens：<1000 原样，<1e6 用 K（>=100 取整），否则用 M */
fun formatTokensCompact(value: Long): String {
    fun scaled(v: Double): String =
        if (v >= 100) v.roundToLong().toString() else (Math.round(v * 10) / 10.0).toString()
    return when {
        value < 1000 -> value.toString()
        value < 1_000_000 -> scaled(value / 1000.0) + "K"
        else -> scaled(value / 1_000_000.0) + "M"
    }
}

/**
 * dsh 的菜单卡片（`Menu.module.css` 的 .list/.surface/.material）：圆角 16、内边距 4px、
 * 描边走 [DshSpacing.Hairline]、光晕走 [dshProminentShadow]。**要滚动就在 content 里套一层 Column**，
 * 别把 `verticalScroll` 传给 `modifier`。
 *
 * 面色用**合成色** [com.adsh.app.ui.theme.DshPalette.menuSurface]（dsh 的半透明菜单面叠在页面底色上的
 * 结果），不透明 —— 浮层里的 Haze 背景模糊取不到身后的源（实拍透出背景文字，见 docs/LESSONS.md）。
 */
@Composable
fun DshMenuCard(
    modifier: Modifier = Modifier,
    /** 统一 16dp（--dsw-radius-lg）：菜单、「上下文占用」窗口（._2WTFBq_panel）、统计卡（.Xt1eiG_panel）都是它 */
    radius: Dp = 16.dp,
    padding: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    val shape = RoundedCornerShape(radius)
    Surface(
        color = palette.menuSurface,
        // Surface 只在底色命中配色方案时才推导内容色；这里是 dsh 的自定义合成色，必须显式给
        contentColor = palette.labelPrimary,
        shape = shape,
        border = BorderStroke(DshSpacing.Hairline, palette.borderL1),
        modifier = modifier.dshProminentShadow(shape),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** dsh 的菜单行：高 34、圆角 12、内边距 6/8、gap 8、13/20；hover -> --dsw-alias-interactive-bg-hover */
@Composable
fun DshMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconSize: Dp = 14.dp,
    value: String? = null,
    chevronRight: Boolean = false,
    selected: Boolean = false,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(iconSize))
        }
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
            maxLines = 1,
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (chevronRight) {
            Icon(
                DshIcons.ChevronRight,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        if (selected) {
            Icon(
                DshIcons.Check,
                contentDescription = null,
                tint = palette.labelPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 上下文占用

/**
 * 上下文占用（dsh 的 ContextMeter，._2WTFBq_trigger）：
 * **圆环 + 百分比**组成的胶囊（14px 圆环 / 2px 线宽、8px 圆角底、内边距 1px 8px、gap 6px、
 * 13/20 三级字色），点开是 264dp 宽的气泡 —— 「上下文已用 X% … ~已用 / 窗口」+ 4px 分段条 +
 * 系统提示词 / 工具定义 / 对话消息。
 *
 * 它在输入框下方那一排里（见 [ChatStatsDock]），所以气泡一律往**上**弹
 * （dsh 的 side: "top"、gap 8、margin 12 —— 贴着屏幕底边弹也不会被切掉）。
 */
@Composable
fun ContextMeter(
    usage: ContextUsage,
    open: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val palette = LocalDshPalette.current
    // dsh 的 ._2WTFBq_fill / trigger 都是 label-tertiary；展开时换二级色 + hover 底
    val ink = if (open) palette.labelSecondary else palette.labelTertiary
    val anchor = remember { OverlayAnchorState() }
    Box(Modifier.overlayAnchor(anchor)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (open) palette.hover else Color.Transparent)
                .dshClickable(interactionSource = dshInteraction(), onClick = onToggle)
                .semantics { contentDescription = "上下文已用 " + usage.percent + "%" }
                .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
        ) {
            // dsh 的圆环：viewBox 14、r=5.5、线宽 2、从 -90° 起画
            Canvas(Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                val radius = 5.5f / 14f * size.minDimension
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(palette.borderL3, radius = radius, center = center, style = Stroke(stroke))
                if (usage.percent > 0) {
                    drawArc(
                        color = ink,
                        startAngle = -90f,
                        sweepAngle = 360f * usage.percent.coerceIn(0, 100) / 100f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = usage.percent.toString() + "%",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (open) {
            DshPopup(onDismiss = onToggle, margin = DshSpacing.Xxxl, anchor = anchor) { ContextPanel(usage) }
        }
    }
}

/** dsh 的 ._2WTFBq_panel：264px 宽、16px 圆角（--dsw-radius-lg）、12px 内边距、12/20 字号 */
@Composable
private fun ContextPanel(usage: ContextUsage) {
    val palette = LocalDshPalette.current
    val (systemColor, toolsColor, messagesColor) = contextColors()
    val total = (usage.system + usage.tools + usage.messages).coerceAtLeast(1L)
    DshMenuCard(modifier = Modifier.width(264.dp), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("上下文已用", fontSize = 12.sp, lineHeight = 20.sp, color = palette.labelTertiary)
            Spacer(Modifier.width(6.dp))
            Text(
                usage.percent.toString() + "%",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "~" + formatTokensCompact(usage.used) + " / " + formatTokensCompact(usage.window),
                fontSize = 12.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
            )
        }
        // 三段条按「已用百分比 × 各段占比」绝对定宽；用 weight 会被拉伸成整条（dsh 用的是 width:%）
        Canvas(
            Modifier
                .padding(top = DshSpacing.Xxl, bottom = DshSpacing.Xxxl)
                .fillMaxWidth()
                .height(4.dp),
        ) {
            drawRoundRect(
                color = palette.hover,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
            val gap = 1.dp.toPx()
            val minWidth = 2.dp.toPx()
            var x = 0f
            listOf(
                usage.system to systemColor,
                usage.tools to toolsColor,
                usage.messages to messagesColor,
            ).forEach { (tokens, color) ->
                val span = size.width * (usage.percent / 100f) * (tokens.toFloat() / total)
                if (span > 0f) {
                    val width = span.coerceAtLeast(minWidth).coerceAtMost(size.width - x)
                    if (width > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, 0f),
                            size = Size(width, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                        )
                        x += width + gap
                    }
                }
            }
        }
        ContextRow(systemColor, "系统提示词", usage.system)
        ContextRow(toolsColor, "工具定义", usage.tools)
        ContextRow(messagesColor, "对话消息", usage.messages)
    }
}

@Composable
private fun ContextRow(dot: Color, label: String, tokens: Long) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, lineHeight = 20.sp, color = palette.labelSecondary)
        }
        Text(
            "~" + formatTokensCompact(tokens),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
        )
    }
}

// ---------------------------------------------------------------------- 输入框

/**
 * 输入卡片的几何，逐项取自 dsh 的 `InputBar.module.css`（CSS px 与 dp 1:1）。
 * 布局直接读这些值；[ComposerSpecTest] 把它们钉在 dsh 的规格上，改动会当场变红。
 */
internal object ComposerSpec {
    /** `--dsh-composer-side-clearance`：卡片离屏幕两侧的距离（.root 的左右 padding） */
    val SideClearance = 16.dp
    /** `--dsh-composer-card-max-width` 的上界 = 会话内容宽 920 + 32；手机上不会生效 */
    val CardMaxWidth = 952.dp
    /** `--dsw-radius-panel`：.card 的圆角 */
    val CardRadius = 28.dp
    /** .card 的 padding-top（卡片本身不设左右内边距，横向由附件区 / 输入区 / 工具行各自带） */
    val CardPaddingTop = 8.dp
    /** .card 的 gap：附件区 / 文字区 / 工具行之间 */
    val CardGap = 12.dp
    /** .input 的 min-height：一行 24 + 顶部 4，余 8 是卡片内的呼吸 */
    val InputMinHeight = 36.dp
    val InputPaddingTop = 4.dp
    /** .input 的左侧 14 */
    val InputPaddingStart = 14.dp
    /** .input 的右侧 8 + .scroll 的滚动条让位 4 */
    val InputPaddingEnd = 12.dp
    val RowPaddingTop = 2.dp
    val RowPaddingSide = 8.dp
    val RowPaddingBottom = 6.dp
    /** .card 容器窄于 560px 时的工具行 gap（手机命中这一档；宽屏才是 12） */
    val RowGap = 8.dp
    /** .primary 发送圆 */
    val SendSize = 34.dp
    /** .primary 的 translateY(-2px)：工具行整体下移 2 后发送键保持原位 */
    val SendLift = 2.dp
    /** 附件区（dsh 的 .accessory）：padding 10 12 0 */
    val AccessoryPaddingTop = 10.dp
    val AccessoryPaddingSide = 12.dp
    /** 工作区 chip 在 hero 阶段的左侧缩进（dsh 的 .heroWorkspaceRow：padding-left 20，右侧 16） */
    val HeroWorkspaceStartInset = 20.dp
}

/**
 * 输入框（逐项对齐 dsh 的 InputBar）：
 * 左：＋（=触发菜单，与输入 `/` 同一个菜单，28dp 圆形 selector 底；「文件」是菜单里的一行）、
 * 权限预设（只留图标 + 可转动倒角）、Plan chip；
 * 右：模型（**模型名** + 倒角，内含 模型 / 推理等级 两个子菜单）、发送/停止（34dp 圆形）。
 * 上下文占用不在这一行（在卡片**下方**那一排，见 [dock]），所以模型按钮的位置不随会话状态变化。
 */
@Composable
fun DshComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    /**
     * 繁忙时的发送行为（dsh 的 ui-conversation.busyEnter）：
     * queue = 排队发送（默认），steer = 插话发送。运行中且草稿非空时，
     * 主按钮发的是这条消息（而不是停止），文案也跟着换 —— dsh 的 primaryStops
     * 就是「运行中且草稿为空」才把主按钮变成停止。
     */
    busyEnter: String,
    /** 模型菜单的数据：按提供方分组（dsh 的 ModelSelect groups） */
    modelGroups: List<ChatViewModel.ModelGroup>,
    currentModel: String,
    /** 当前提供方 id：选中态要「提供方 + 模型」都对上 */
    currentProviderId: String,
    /** 每个提供方的余额（DeepSeek 专有接口；显示在分组标题的最右边） */
    balances: Map<String, ChatViewModel.BalanceState>,
    /** 打开模型菜单时刷新余额（每次点开自动刷新一次） */
    onRefreshBalance: () -> Unit,
    /** 推理等级菜单的行：Default + 当前**模型**支持的等级（dsh 的 ModelSelect 子页；空表 = 该模型没有等级） */
    efforts: List<Pair<String, String>>,
    currentEffort: String,
    onSelectModel: (String, String) -> Unit,
    onSelectEffort: (String) -> Unit,
    permission: String,
    onSelectPermission: (String) -> Unit,
    /** 触发菜单里**要显示的**行（父层已按 dsh 的规则过滤：可用性 / 查询命中，见 Palette.kt） */
    paletteCommands: List<PaletteCommand>,
    paletteVisible: Boolean,
    onPaletteVisibleChange: (Boolean) -> Unit,
    /** 菜单被「点别处 / 返回键」关掉时的收尾（把当前草稿标记为已忽略，别立刻又弹出来） */
    onPaletteDismiss: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    planMode: Boolean,
    onTogglePlan: () -> Unit,
    /** 待发附件（会话私有目录里的绝对路径）：显示在输入框内部、文字输入区上方 */
    attachments: List<String>,
    onRemoveAttachment: (String) -> Unit,
    /** 命令面板里的 /permission 直接展开权限菜单 */
    requestPermission: Boolean,
    onRequestHandled: () -> Unit,
    /** 是否显示输入框外的工作区 chip（dsh 只在 hero 阶段显示） */
    showWorkspace: Boolean,
    /** 已绑定的工作区（dsh 侧栏的树）与当前会话所属工作区 */
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity>,
    workspaceId: Long?,
    onPickWorkspace: (Long) -> Unit,
    onAddWorkspace: () -> Unit,
    /**
     * 父层主动改写草稿的次数（发送后清空、指令面板写入 token）。
     * 只有这个计数变化时输入框才会被外部回灌，见 fieldValue 的注释。
     */
    draftRevision: Int,
    /** 当前打开的那一个浮层（[ChatOverlays.open]；本组件只认 [OPEN_PERMISSION] / [OPEN_MODEL] / [OPEN_WORKSPACE]） */
    menu: String?,
    onMenuChange: (String?) -> Unit,
    /**
     * 掉线重连条的状态（dsh 的 ConnectionIndicator）：挂在输入框那一行的**右侧**
     * （模型选择器与发送键之间 = dsh 的 conversation.input.activity 槽）。
     */
    connection: ConnectionState,
    onRetryConnection: () -> Unit,
    /**
     * 输入框**下方**那一排（dsh 的 InputBar dock：会话统计 / Token 用量 / 上下文占用）。
     *
     * 由调用方给内容：这一排要读会话统计与上下文占用，而它们不属于输入框的状态。
     * 位置在这里（而不是调用点）是因为间距属于输入框那一摞：dsh 的 dock 就是卡片后面的
     * 下一个兄弟节点，间距 4px 由它自己带（.RlGAzG_dock 的 padding-top）。
     */
    dock: @Composable () -> Unit = {},
) {
    val palette = LocalDshPalette.current
    // 三个弹层互斥；状态托管给 ChatScreen（弹层自己登记进全局登记处，不用再铺拦截层）
    // 指令面板贴着「整张输入卡片的上沿」而不是某颗按钮的上沿：先记下卡片的顶边与宽度
    var composerCardTop by remember { mutableFloatStateOf(0f) }
    /** 输入卡片的宽度（px）：触发菜单与卡片同宽（dsh 的 .menu 是 left:0;right:0） */
    var composerCardWidth by remember { mutableIntStateOf(0) }
    /** 输入框那一摞自己的窗口矩形（**不含**浮在它上面的指令面板） */
    val composerBounds = remember { AnchorRect() }
    /** 指令面板自己的窗口矩形：面板打开时由它自己写（同层布局后它落在父层矩形之外） */
    val paletteBounds = remember { AnchorRect() }
    // 指令面板的高度 = 卡片上方夹出来的可用空间（口径见 [popupPanelMaxHeight]）。
    // 它由面板自己带着往上画（[Modifier.outOfFlowAbove]），输入框那一摞一个像素都不动。
    val paletteDensity = LocalDensity.current
    val paletteHeight = if (paletteVisible) {
        popupPanelMaxHeight(
            density = paletteDensity,
            // 高度按**卡片顶边**上方的空间夹（不是本摞顶边）：换工作区 chip 只在 hero 阶段出现，
            // 那时这样夹出来的高度保守一点，永远不会把面板顶出屏幕。
            anchorTopPx = composerCardTop,
            safeTopPx = WindowInsets.statusBars.getTop(paletteDensity),
            screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp,
            maxHeight = PALETTE_MAX_HEIGHT,
            minHeight = PALETTE_MIN_HEIGHT,
            gap = PALETTE_GAP,
            topMargin = PALETTE_TOP_MARGIN,
        )
    } else {
        0.dp
    }
    val permissionOpen = menu == OPEN_PERMISSION
    val modelOpen = menu == OPEN_MODEL
    var confirmFullAccess by remember { mutableStateOf(false) }

    LaunchedEffect(requestPermission) {
        if (requestPermission) {
            onMenuChange(OPEN_PERMISSION)
            onRequestHandled()
        }
    }

    // 「每次点开时自动刷新」余额：菜单一打开就拉一次（拉取在 VM 的 IO 线程上）
    LaunchedEffect(modelOpen) {
        if (modelOpen) onRefreshBalance()
    }

    // 输入 "/" 与点 ＋ 打开的是同一个菜单（dsh 的 input.commands）。
    // 这里只负责「消费掉触发词」：行有哪些、菜单显不显示，全由父层按 dsh 的三条规则算好
    // （可用性过滤 / 精确命中即完整 / 一条候选都不剩就关，见 Palette.kt）。
    val slashQuery = remember(draft) { slashQueryOf(draft) }

    // dsh 的 leadingInput 命令：/goal、/plan 的 token 在输入框里着色，用户直接在后面输入内容
    val claim = remember(draft) { claimTokenOf(draft) }
    val claimRest = remember(draft, claim) {
        if (claim == null) "" else draft.trimStart().removePrefix(claim).trim()
    }
    val claimHint = claimHintOf(claim)
    val inputStyle = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, color = palette.labelPrimary)
    val measurer = rememberTextMeasurer()
    val hintOffset = remember(claim, inputStyle) {
        claim?.let { measurer.measure(AnnotatedString(it + " "), inputStyle).size.width } ?: 0
    }
    // 选完指令后把焦点交回输入框：/goal、/plan 之后可以直接打字
    val inputFocus = remember { FocusRequester() }
    // 用 TextFieldValue 托管选区：面板写入 "/goal " 这类文本时光标自动落到末尾（否则会停在开头）
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft)) }
    /**
     * 输入框自己就是文本的唯一真相：父层**只在主动改输入框时**（指令面板写入 "/goal "、
     * 发送后清空）把 [draftRevision] 加一，通知这里回灌一次。
     * 按键产生的回显不碰 fieldValue —— 回灌会丢掉输入法的组合区（拼音变字母）。
     */
    var appliedRevision by remember { mutableStateOf(draftRevision) }
    LaunchedEffect(draftRevision) {
        if (draftRevision != appliedRevision) {
            appliedRevision = draftRevision
            fieldValue = TextFieldValue(draft, selection = TextRange(draft.length))
        }
    }
    val claimColor = palette.warnLabel
    val claimTransform = remember(claim, claimColor) {
        VisualTransformation { text ->
            val value = text.text
            if (claim == null || !value.startsWith(claim)) {
                TransformedText(text, OffsetMapping.Identity)
            } else {
                TransformedText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = claimColor)) { append(value.substring(0, claim.length)) }
                        append(value.substring(claim.length))
                    },
                    OffsetMapping.Identity,
                )
            }
        }
    }

    // 底部留 4dp：整摞（输入框 + 下面那排胶囊）往下靠。全面屏手势区由 ChatScreen 的
    // navigationBars 内边距让出来，这里只收掉自己这一份余量，别再往下压 —— 手势线不能碰。
    // **指令面板浮在本摞上方**：在本 Column 里但不占本摞高度（[Modifier.outOfFlowAbove]），不能走
    // 独立窗口（DshPopup）—— 键盘动画期间窗口逐帧重摆会「下滑收起时特别卡」；在同一次布局里才逐帧
    // 同步，且输入框那一摞的高度与位置与没开面板时完全一致（dsh 的 .menu 同理）。
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ComposerSpec.SideClearance)
            .padding(bottom = DshSpacing.Md)
            // 输入框那一摞自己的窗口矩形：与面板的矩形拼成「按下别处即关闭」的锚点
            .onGloballyPositioned { composerBounds.value = it.boundsInWindow().toAnchorRect() },
    ) {
        // 触发菜单（dsh 的 .menu）：宽度与卡片一致、底边贴本摞上沿 4px，画在本 Column 的
        // 上沿之外（-height，即本 Column 的 y=0 之上）。
        CommandPalette(
            visible = paletteVisible,
            commands = paletteCommands,
            widthPx = composerCardWidth,
            height = paletteHeight,
            bounds = paletteBounds,
            onDismiss = onPaletteDismiss,
            onPick = { command ->
                onPaletteVisibleChange(false)
                // dsh 的 pick 会先按 span 消费掉触发词那一段（ui-commands/src/client/service.ts:270-273
                // `consumeVia(span)`），再执行命令：键入的 `/comp` 不会留在输入框里。
                // 「+」打开的菜单没有触发词，草稿原样不动 —— 用户打的字不该被菜单吃掉。
                val slash = slashQuery
                if (slash != null) {
                    val rest = draft.removeRange(0, 1 + slash.length)
                    if (rest != draft) onDraftChange(rest)
                }
                command.run()
                // 选完指令把焦点交回输入框：/plan 这类后面可以直接打字
                runCatching { inputFocus.requestFocus() }
            },
        )
        // 「按下别处即关闭」：面板打开期间登记自己（登记处的口径见 OverlayDismiss.kt）。
        // 锚点是**两块区域的并集** —— 落在面板里（选一行）或输入框那一摞里（再按一次「＋」）都不关，
        // 否则按下的那一下就把面板拆了，行根本等不到抬手那一次点击。
        if (paletteVisible) {
            RegisterOverlayDismiss(onPaletteDismiss) {
                unionAnchor(composerBounds.value, paletteBounds.value)
            }
        }

        // dsh 的 heroWorkspaceRow：输入框「外」左上角的工作区入口（文件夹图标 + 名称 + 倒角）
        // 只在「新对话且还没有内容」时出现（dsh 的 hero 阶段），开始对话后自动隐藏
        if (showWorkspace) WorkspaceChipRow(
            workspaces = workspaces,
            workspaceId = workspaceId,
            open = menu == OPEN_WORKSPACE,
            onOpenChange = { open -> onMenuChange(if (open) OPEN_WORKSPACE else null) },
            onPick = { id ->
                onMenuChange(null)
                onPickWorkspace(id)
            },
            onAdd = {
                onMenuChange(null)
                onAddWorkspace()
            },
        )

        // 触发菜单贴的是**整张输入卡片**（dsh 的 `.menu` 就是卡片里的绝对定位节点）：
        // 卡片宽度与顶边在这里量；外层 Box 只负责在宽屏上把卡片居中。
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val cardShape = RoundedCornerShape(ComposerSpec.CardRadius)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = ComposerSpec.CardMaxWidth)
                .dshSoftShadow(cardShape)
                .onGloballyPositioned { coordinates ->
                    composerCardTop = coordinates.positionInRoot().y
                    composerCardWidth = coordinates.size.width
                },
            shape = cardShape,
            color = palette.inputMajor,
            contentColor = palette.labelPrimary,
            // dsh 的输入卡片描边画在 --dsw-elevation-soft 里（0.5px、颜色重绑到 border-l2），不占布局。
            border = BorderStroke(DshSpacing.Hairline, palette.borderL2),
        ) {
            Column(
                Modifier.padding(top = ComposerSpec.CardPaddingTop),
                verticalArrangement = Arrangement.spacedBy(ComposerSpec.CardGap),
            ) {
                // 附件在输入框内部：卡片自动变高，文字输入区留在下方
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(
                                start = ComposerSpec.AccessoryPaddingSide,
                                end = ComposerSpec.AccessoryPaddingSide,
                                top = ComposerSpec.AccessoryPaddingTop,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                    ) {
                        attachments.forEach { path -> AttachmentCard(path = path, onRemove = onRemoveAttachment) }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ComposerSpec.InputMinHeight)
                        .padding(
                            start = ComposerSpec.InputPaddingStart,
                            end = ComposerSpec.InputPaddingEnd,
                            top = ComposerSpec.InputPaddingTop,
                        ),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (planMode) COMPOSER_HINT_PLAN else COMPOSER_HINT,
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                            // dsh：停靠态占位是一行省略，hero（空会话）才允许折两行
                            maxLines = if (showWorkspace) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.labelCaption,
                        )
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { updated ->
                            fieldValue = updated
                            onDraftChange(updated.text)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                        textStyle = inputStyle,
                        cursorBrush = SolidColor(palette.accent),
                        visualTransformation = claimTransform,
                        // dsh 的 --dsh-composer-text-max-height:336px = 14 行（超出在输入区内部滚）
                        maxLines = 14,
                    )
                    // claim 生效且还没输入内容时，在 token 后面显示 dsh 的 hint（如「输入目标，智能体将持续执行」）
                    if (claim != null && claimHint != null && claimRest.isEmpty()) {
                        Text(
                            text = claimHint,
                            modifier = Modifier.padding(
                                start = with(LocalDensity.current) { hintOffset.toDp() },
                            ),
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.labelCaption,
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = ComposerSpec.RowPaddingSide,
                            end = ComposerSpec.RowPaddingSide,
                            top = ComposerSpec.RowPaddingTop,
                            bottom = ComposerSpec.RowPaddingBottom,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ComposerSpec.RowGap),
                ) {
                    // ＋：触发菜单（与键入 "/" 同一个菜单，含「文件」那一行）。
                    // dsh 的 .uV2eYG_tools：28px 圆形、--dsw-specific-selector 底、gap 8px（窄容器）。
                    CircleIconButton(DshIcons.Plus, "添加文件或调用指令", active = paletteVisible) {
                        onMenuChange(null)
                        onPaletteVisibleChange(!paletteVisible)
                    }

                    // 权限预设（弹层在 ComposerMenus.kt）
                    PermissionMenuTrigger(
                        permission = permission,
                        open = permissionOpen,
                        cardWidthPx = composerCardWidth,
                        onOpenChange = { want -> onMenuChange(if (want) OPEN_PERMISSION else null) },
                        onSelect = onSelectPermission,
                        onSelectNeedsConfirm = { confirmFullAccess = true },
                    )

                    if (planMode) PlanChip(onExit = onTogglePlan)

                    Spacer(Modifier.weight(1f))

                    // 掉线重连条（dsh 的 conversation.input.activity 槽）：位置在模型选择器与
                    // 发送键之间 = 输入框的右侧。它出现时**模型选择器让位** —— dsh 在 activity
                    // 非空时把 standardControls（模型那一组）整个 hidden，这一行才放得下。
                    //
                    // 「让位」的判据是**退场动画演完**（currentState 与 targetState 都为 false），
                    // 不是「状态已经不是 Reconnecting」：后者会让重连条在淡出的 150ms 里和模型选择器
                    // 同时占着这一行（两个加起来 ~340dp，比卡片还宽），发送键会被顶出卡片右边缘。
                    val activityState = remember { MutableTransitionState(false) }
                    activityState.targetState = connection !is ConnectionState.Idle
                    AnimatedVisibility(
                        visibleState = activityState,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(150)),
                    ) {
                        ConnectionBar(state = connection, onRetry = onRetryConnection)
                    }

                    // 模型 / 推理等级两级菜单（弹层在 ComposerMenus.kt）
                    if (!activityState.currentState && !activityState.targetState) {
                        ModelMenuTrigger(
                            open = modelOpen,
                            modelGroups = modelGroups,
                            balances = balances,
                            currentModel = currentModel,
                            currentProviderId = currentProviderId,
                            efforts = efforts,
                            currentEffort = currentEffort,
                            onOpenChange = { want -> onMenuChange(if (want) OPEN_MODEL else null) },
                            onSelectModel = onSelectModel,
                            onSelectEffort = onSelectEffort,
                        )
                    }

                    // 上下文占用不在这一行里：dsh 把它放在输入卡片**下方**的 dock 里
                    // （见 dock 参数与 ChatStatsDock）。**不要放回这一行的最右边**：它出现在会话开始之后，一出现就把左边的模型按钮顶走一格。
                    SendButton(
                        sending = sending,
                        // 只有附件没有文字也能发（dsh 的 canSubmit = 文本非空或附件非空）：
                        // 发送键不能只看正文：只发一张图也必须点得动
                        enabled = draft.isNotBlank() || attachments.isNotEmpty(),
                        busyEnter = busyEnter,
                        onSend = onSend,
                        onCancel = onCancel,
                    )
                }
            }
        }
        }

        // 输入框下方那一排（会话统计 / Token 用量 / 上下文占用）：dsh 的 .RlGAzG_dock，
        // 在卡片**后面**、间距 4px；没有会话时调用方传空的 lambda，这一排整个不存在。
        dock()

    }

    if (confirmFullAccess) {
        // dsh 的 RiskConfirmation：正文 + 勾选框 + 「取消 / 启用完全权限」两个按钮。
        // 「我已了解风险，并愿意继续」是**勾选框的文案**，不是确认按钮 —— 没勾选时确认键必须禁用。
        RiskConfirmationDialog(
            title = "确认启用完全权限？",
            description = "启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，" +
                "包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。",
            onCancel = { confirmFullAccess = false },
            onConfirm = {
                confirmFullAccess = false
                onSelectPermission(Permissions.FULL_ACCESS)
            },
        )
    }
}

/**
 * dsh 的工作区 chip（HeroShell.module.css 的 .workspace/.workspaceRow）：
 * min-height 28px、圆角 16px、gap 4px、左右 8px 内边距、13/20 Medium；
 * 文件夹图标 16px（未绑定用闭合文件夹），名称超长省略，右侧 12px 倒角；
 * hover / 展开时底色 --dsw-alias-interactive-bg-hover。
 *
 * 点开是 dsh 的工作区菜单：已有工作区列表（当前项打勾）+「添加工作区…」；
 * 一个工作区都没有时直接进目录选择（对应 dsh 的 addIsTheOnlyEntry）。
 */
@Composable
private fun WorkspaceChipRow(
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity>,
    workspaceId: Long?,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onPick: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val current = workspaces.firstOrNull { it.id == workspaceId }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 倒角跟着展开态翻转（与权限 / 模型那两个 TriggerPill 同一套：120ms 转 180°），展开后朝上 ——
    // 必须跟着状态走，不能写死成 ChevronDown。
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(120), label = "workspaceChevron")
    val anchor = remember { OverlayAnchorState() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = ComposerSpec.HeroWorkspaceStartInset, end = DshSpacing.Card)
            .overlayAnchor(anchor),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (pressed || open) palette.hover else Color.Transparent)
                .dshClickable(interactionSource = interaction) {
                    if (workspaces.isEmpty()) onAdd() else onOpenChange(!open)
                }
                .padding(horizontal = DshSpacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
        ) {
            Icon(
                imageVector = if (current == null) DshIcons.FolderClose else DshIcons.FolderOpen,
                contentDescription = null,
                tint = palette.labelPrimary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = current?.name ?: "选择工作区",
                modifier = Modifier.widthIn(max = 220.dp),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = DshIcons.ChevronDown,
                contentDescription = "选择工作区",
                tint = palette.labelCaption,
                modifier = Modifier.size(12.dp).rotate(chevron),
            )
        }
        if (open) {
            DshPopup(onDismiss = { onOpenChange(false) }, alignStart = true, anchor = anchor) {
                DshMenuCard(Modifier.width(200.dp)) {
                    workspaces.forEach { workspace ->
                        DshMenuRow(
                            label = workspace.name,
                            icon = DshIcons.FolderClose,
                            selected = workspace.id == workspaceId,
                            onClick = { onPick(workspace.id) },
                        )
                    }
                    DshMenuRow(
                        label = "添加工作区…",
                        icon = DshIcons.Plus,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

/**
 * 这条路径是不是图片：按**魔数**判断，与发送时（describeAttachments）和消息里用的是同一个判定。
 * **不能只看扩展名**：那样「输入框显示图片缩略图、发出去变成文件卡片」—— 缩略图按扩展名
 * 能解出来就显示成图，而发出去的 mediaType 是按字节嗅探的。
 */
// isImagePath 已搬到 ui/ImageDecode.kt（与缩略图解码同一个家：图片这条路上的共用件只有一处）

/**
 * 待发附件：**图片**与**文件**是两种形状（各自向参考实现看齐）。
 *
 *  - 图片：只显示缩略图，**不带文件名**，移除的叉压在图的右上角（dsh 的图片附件）；
 *  - 文件：类型图标（与文件浏览里同一个 [FileTypeIcon]）+ 文件名 + 大小，
 *    右上角一枚深色圆形叉徽标 —— 形状照 DeepSeek app 的附件卡片。
 *
 * 是不是图片用**魔数**判定（与发出去之后消息里那一行同一套），不看扩展名：
 * 否则会出现「输入框里是图片缩略图、发出去变成文件卡片」这种前后不一致。
 */
@Composable
private fun AttachmentCard(path: String, onRemove: (String) -> Unit) {
    if (remember(path) { isImagePath(path) }) {
        ImageAttachment(path, onRemove)
    } else {
        FileAttachment(path, onRemove)
    }
}

/** 图片附件：缩略图 + 压在右上角的叉（没有文件名 —— dsh 那边也没有） */
@Composable
private fun ImageAttachment(path: String, onRemove: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val thumbnail by rememberImage(path, THUMB_TARGET_PX)
    Box(Modifier.size(64.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.selector),
            contentAlignment = Alignment.Center,
        ) {
            val image = thumbnail
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        RemoveBadge(
            onRemove = { onRemove(path) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** 文件附件：类型图标 + 文件名 + 大小（右上角同样是那枚叉徽标） */
@Composable
private fun FileAttachment(path: String, onRemove: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val file = remember(path) { File(path) }
    val size = remember(path) { runCatching { file.length() }.getOrDefault(0L) }
    Box(Modifier.widthIn(max = 240.dp).height(64.dp)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.inputMajor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 与工作区文件浏览同一个图标（按类型给角标：PDF / MD / PY …）
            FileTypeIcon(name = file.name, size = 40.dp)
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = file.name,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = fileSizeText(size),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                )
            }
        }
        RemoveBadge(
            onRemove = { onRemove(path) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * 移除徽标：一枚深色圆底 + 白色叉，压在卡片的右上角上（DeepSeek app 的附件卡片就这么画的）。
 *
 * 深色用「黑 55%」而不是某个主题令牌：它在浅色卡片和深色卡片上都得是**深底白叉**
 * （用 labelPrimary 这类跟着主题反转的令牌，深色主题下会变成白圆底）。
 */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .offset(x = 5.dp, y = (-5).dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(REMOVE_BADGE_BACKGROUND)
            .dshClickable(interactionSource = dshInteraction()) { onRemove() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            DshIcons.CloseFill,
            contentDescription = "移除附件",
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 移除徽标的底色：黑 55%（深浅两套主题下都是「深底白叉」） */
private val REMOVE_BADGE_BACKGROUND = Color(0x8C000000)

/** 待发附件缩略图的目标长边（解码与缓存只有 ui/ImageDecode.kt 那一份实现） */
private const val THUMB_TARGET_PX = 144

/** dsh 的 .uV2eYG_add：28dp 圆形、--dsw-specific-selector 底、图标 14px */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (pressed || active) palette.hoverSolid else palette.selector)
            .dshClickable(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = palette.labelPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** dsh 的 PermissionSelect trigger：28dp 高、圆角 8（--dsw-radius-sm）、图标 + 可转动倒角 */
@Composable
internal fun TriggerPill(
    icon: ImageVector,
    contentDescription: String,
    open: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(120), label = "chevron")
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed || open) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(start = DshSpacing.Xl, end = DshSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = palette.labelSecondary, modifier = Modifier.size(14.dp))
        Icon(
            DshIcons.ChevronDown,
            contentDescription = null,
            tint = palette.labelCaption,
            modifier = Modifier.size(14.dp).rotate(rotation),
        )
    }
}

/**
 * dsh 的 ModelSelect 触发器（.wq12jW_trigger）：**模型名 + 倒角**，高 28、圆角 8（--dsw-radius-sm）、
 * 内边距 0 4 0 8、gap 4、13/20 字、二级色。
 *
 * 两处刻意与 dsh 保持一致：
 *  - 图标（IconDataOutline16）在 dsh 里是**窄容器专用的备用形态** ——
 *    --dsh-composer-model-icon-display 默认 none，只有一行放不下时才换出来，所以这里不画；
 *  - 推理等级是触发器里的另一个 span（dsh 的 triggerEffort），用户口径是**不显示**，
 *    它仍然留在菜单的「推理等级」那一页里。
 */
@Composable
internal fun ModelTriggerPill(label: String, open: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(120), label = "chevron")
    // dsh 的 max-width 是 min(360px, 45cqw)，cqw 是**输入框那一行**的宽度（手机上是窗口宽减两侧留白）
    val maxWidth = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.width * 0.45f).toDp()
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(if (open) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
            .padding(start = DshSpacing.Xl, end = DshSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            DshIcons.ChevronDown,
            contentDescription = null,
            tint = palette.labelCaption,
            modifier = Modifier.size(12.dp).rotate(rotation),
        )
    }
}

/**
 * dsh 的 PlanModeControl chip：高 28、radius-sm=8、state-business-tertiary 底、
 * state-business-primary 字、padding 0 8、gap 4、14px glyph。
 */
@Composable
private fun PlanChip(onExit: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.businessSurface.copy(alpha = 0.94f) else palette.businessSurface)
            .dshClickable(interactionSource = interaction, onClick = onExit)
            .padding(horizontal = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        Text("Plan", fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = palette.business)
        Icon(DshIcons.CloseFill, contentDescription = "退出计划模式", tint = palette.business, modifier = Modifier.size(14.dp))
    }
}

/**
 * 发送/停止：dsh 的 .uV2eYG_primary（34dp 圆形、--dsw-alias-button-info-fill 底、白色箭头，无涟漪）。
 *
 * dsh 的 primaryStops = running && (empty || blocked)：运行中只要草稿还是空的，主按钮才是停止；
 * 草稿一有内容，主按钮就变成「排队发送 / 插话发送」（走 busyEnter 那一档）。
 */
@Composable
private fun SendButton(
    sending: Boolean,
    enabled: Boolean,
    busyEnter: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val stops = sending && !enabled
    val active = sending || enabled
    // 触屏没有 hover 指针：按下态用 dsh 的 button-info-hover 那一档
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = when {
            !active -> palette.sendFill.copy(alpha = 0.4f)
            pressed -> palette.sendFillHover
            else -> palette.sendFill
        },
        animationSpec = tween(180),
        label = "sendBg",
    )
    Box(
        modifier = Modifier
            .offset(y = -ComposerSpec.SendLift)
            .size(ComposerSpec.SendSize)
            .clip(CircleShape)
            .background(background)
            .dshClickable(enabled = active, interactionSource = interaction) { if (stops) onCancel() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = stops,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f)) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.7f))
            },
            label = "sendIcon",
        ) { isSending ->
            Icon(
                imageVector = if (isSending) DshIcons.Stop else DshIcons.ArrowUp,
                contentDescription = when {
                    isSending -> "停止生成"
                    sending -> if (busyEnter == com.adsh.app.core.data.SettingsStore.BUSY_STEER) "插话发送" else "排队发送"
                    else -> "发送消息"
                },
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 触发菜单（＋ / ）

/**
 * dsh 的触发菜单（MenuView）：`+` 与 `/` 打开的是**同一个菜单**，尺寸与排版逐项对齐 dsh 的
 * `.menu` / `.sectionTitle` / `.item`。**几何恒定**：高度 = 调用方按上方空间夹出来的 [height]，
 * 底边钉在本摞上沿 4px。@param bounds 与输入框那一摞拼成「按下别处即关闭」的锚点（[unionAnchor]）
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    commands: List<PaletteCommand>,
    widthPx: Int,
    height: Dp,
    bounds: AnchorRect,
    onDismiss: () -> Unit,
    onPick: (PaletteCommand) -> Unit,
) {
    val palette = LocalDshPalette.current
    if (!visible) return
    val width = with(LocalDensity.current) { widthPx.toDp() }
    // 空查询才分节（dsh：查询非空时列表按匹配度排，不显示小节标题）
    val sectioned = commands.any { it.section.isNotEmpty() } &&
        commands.map { it.section }.distinct().size > 1
    // 高度 = 卡片高度上限 + 那 4px 间距（底边让出 [PALETTE_GAP]）。
    Box(
        // 「不占布局」由面板自己带（见 [outOfFlowAbove]）：报 0 高、整块画到锚点上方。
        // 这件事必须落在面板自己身上 —— 交给调用方传，忘一次就回到「面板挤扁输入卡片」。
        modifier = Modifier
            .outOfFlowAbove()
            .then(if (widthPx > 0) Modifier.width(width) else Modifier)
            .height(height + PALETTE_GAP)
            .padding(bottom = PALETTE_GAP)
            .onGloballyPositioned { bounds.value = it.boundsInWindow().toAnchorRect() },
        contentAlignment = Alignment.BottomStart,
    ) {
        // 卡片上方那一片空白是**同层**布局（不在弹窗窗口里），落在里面、卡片外的点击
        // 要自己接住：否则会漏给下面的消息区 —— 既不关菜单、还顺手把消息滚了。
        // 卡片自己排在后面，命中优先。
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        )
        DshMenuCard(
            modifier = Modifier.fillMaxWidth(),
            padding = 3.dp,
        ) {
            BoxWithConstraints {
                // dsh 的两条宽度上限（.itemName 40% / .itemAlias 20%）：在同一个约束里算一次，
                // 免得每一行各做一次子组合
                val nameMax = maxWidth * 0.4f
                val aliasMax = maxWidth * 0.2f
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 卡片自己的高度上限是「窗口高度减去卡片内边距」：超出就在卡片内部滚，
                        // 绝不让卡片长过窗口（长过去就会被窗口裁掉，也会把底边顶离锚点）
                        .heightIn(max = height - 6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    var lastSection: String? = null
                    commands.forEach { command ->
                        if (sectioned && command.section != lastSection) {
                            // dsh 的 .sectionTitle：padding 5px 8px 2px（非首节再 margin-top 3px）、
                            // 11/16 Medium 三级色
                            Text(
                                text = command.section,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = DshSpacing.Xl,
                                        end = DshSpacing.Xl,
                                        top = if (lastSection == null) 5.dp else 8.dp,
                                        bottom = DshSpacing.Xs,
                                    ),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.labelTertiary,
                            )
                            lastSection = command.section
                        }
                        PaletteRow(
                            command = command,
                            nameMax = nameMax,
                            aliasMax = aliasMax,
                            onPick = { onPick(command) },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** 触发菜单的一行（dsh 的 .item，含图标 / 标题 / 别名 / 右对齐说明） */
@Composable
private fun PaletteRow(
    command: PaletteCommand,
    nameMax: Dp,
    aliasMax: Dp,
    onPick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onPick)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
    ) {
        Icon(
            imageVector = command.icon,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = command.label,
            modifier = Modifier.widthIn(max = nameMax),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 别名：本地化标题与命令名不同时显示（dsh 的 item.label !== item.name）
        if (!command.label.equals(command.name, ignoreCase = true)) {
            Text(
                text = command.name,
                modifier = Modifier.widthIn(max = aliasMax),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (command.description.isNotEmpty()) {
            Text(
                text = command.description,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 附件选择器：系统文件选择器（多选）→ [onImport] 把选中的文件复制进「工作区/.adsh/attachments/<会话>/」。
 *
 * 导入成功是「看得见的结果」（附件卡片会
 * 出现在输入框里），所以**不弹提示**；只有失败才提示，因为那是用户看不出来的。
 *
 * @return 一个「打开选择器」的动作，接给触发菜单的「文件」那一行。
 */
@Composable
internal fun rememberAttachmentPicker(onImport: (List<android.net.Uri>) -> ImportResult): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val result = onImport(uris)
        if (result.failures.isNotEmpty()) {
            android.widget.Toast.makeText(context, "导入失败：" + result.failures.first(), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
