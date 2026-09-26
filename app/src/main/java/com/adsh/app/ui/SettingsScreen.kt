package com.adsh.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.BuildConfig
import com.adsh.app.core.data.ApiProtocol
import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.data.ProviderCatalog
import com.adsh.app.core.data.ModelDef
import com.adsh.app.core.data.ProviderDef
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.ProviderConfig
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// ------------------------------------------------------------------ 设置外壳

private data class SettingsTab(val id: String, val label: String, val icon: ImageVector)

/** dsh 的 settings.section 三段：通用设置（general）/ 模型（models）/ 插件（plugins，本客户端叫「功能」） */
private val TABS = listOf(
    SettingsTab("general", "通用设置", DshSettingIcons.Settings),
    SettingsTab("models", "模型", DshSettingIcons.Data),
    SettingsTab("plugins", "功能", DshSettingIcons.Personalization),
)

/**
 * 设置页里那些「改完立刻生效」的偏好：值从 ChatViewModel 的 state 来，
 * 写回时落盘（对应 dsh 把 ui-theme / ui-chat / ui-conversation 的字段写进 user-settings 文档）。
 */
data class SettingsBindings(
    val themePreference: String,
    val contentFontSize: Int,
    val transcriptView: String,
    val busyEnter: String,
    val permission: String,
    /** 边缘防误触的预设 id（off / narrow / medium / wide） */
    val edgeGuard: String,
    val onTheme: (String) -> Unit,
    val onFontSize: (Int) -> Unit,
    val onTranscriptView: (String) -> Unit,
    val onBusyEnter: (String) -> Unit,
    val onPermission: (String) -> Unit,
    val onEdgeGuard: (String) -> Unit = {},
)

/**
 * 设置页（对齐 dsh 的设置面板 + 手机适配）。
 *
 * dsh 的面板是「左栏 188px 的分节导航 + 右侧内容」的模态框（SettingsRoot.module.css）：
 *  - 头部 .header 高 54、padding 20 14 8 10，右侧一枚 28 的圆形关闭按钮；
 *  - 导航 .navCell 高 40、圆角 12、padding 9 16 9 12、gap 8，图标 16 + 文案 14/22，
 *    选中用 --dsw-specific-sidebar-nav-item-active，悬停用 -hover；
 *  - 内容 .options padding 0 24 24，各分节自己滚动。
 *
 * 手机只有 ~394dp 宽，188 的左栏放不下：把同一批 navCell 横过来放在标题下面（同样的尺寸与配色），
 * 内容仍是原来的分节，左右内边距从 24 收到 16。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    bindings: SettingsBindings,
    bootstrapReady: Boolean,
    bootstrapDir: String,
    /** bootstrap 最近一次失败原因（没有就显示「未安装」） */
    bootstrapError: String? = null,
    onOpenTerminal: () -> Unit,
    onBack: () -> Unit,
    /** 当前分栏（由调用方托管）：从「功能 → 终端 → 终端会话」返回时要回到离开时的那一栏 */
    tab: Int = 0,
    onTabChange: (Int) -> Unit = {},
) {
    val palette = LocalDshPalette.current
    val initialTab = tab.coerceIn(0, (TABS.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { TABS.size })
    val scope = rememberCoroutineScope()
    // 分栏状态上报给调用方：设置页被覆盖页顶掉时组合会被销毁，
    // 只靠 rememberPagerState 的话返回时会从第 0 栏（通用设置）重新开始。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onTabChange(it) }
    }
    Column(Modifier.fillMaxSize().background(palette.bgLayer2).statusBarsPadding()) {
        SettingsHeader(onClose = onBack)
        SettingsNav(pagerState.currentPage) { index -> scope.launch { pagerState.animateScrollToPage(index) } }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> GeneralSection(bindings = bindings, settings = settings)
                1 -> ModelsSection(settings)
                else -> FeaturesSection(
                    settings = settings,
                    bootstrapReady = bootstrapReady,
                    bootstrapDir = bootstrapDir,
                    bootstrapError = bootstrapError,
                    onOpenTerminal = onOpenTerminal,
                )
            }
        }
    }
}

/** dsh 的 .header：高 54、padding 20 14 8 10，标题 16/24 500 + 右侧 28 圆形关闭 */
@Composable
private fun SettingsHeader(onClose: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 10.dp, end = 14.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "设置",
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            color = palette.labelPrimary,
        )
        Spacer(Modifier.weight(1f))
        CircleIconAction(DshSettingIcons.Close, "关闭设置", 14.dp, onClose)
    }
}

/** dsh 的 .navCell（横排版）：高 40、圆角 12、padding 9 16 9 12、gap 8、图标 16 + 文案 14/22 */
@Composable
private fun SettingsNav(selected: Int, onSelect: (Int) -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEachIndexed { index, tab ->
            val active = index == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            active -> palette.navActive
                            pressed -> palette.navHover
                            else -> Color.Transparent
                        },
                    )
                    .clickable(interactionSource = interaction, indication = null) { onSelect(index) }
                    .padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(tab.icon, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(16.dp))
                Text(
                    text = tab.label,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 分节内容：竖向滚动，左右 16（dsh 的 .options 是 0 24 24） */
@Composable
private fun SectionColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// ------------------------------------------------------------------ 通用设置（dsh 的 settings.general.item）

/**
 * dsh 的 GeneralSection 是「一列设置行」，行与行之间一条 .5px 的 border-l2 分隔：
 *   .row{border-bottom:.5px solid border-l2; padding:16px 0; gap:8px}
 *   .rowText{flex:1; gap:4px; padding-right:48px}  .title{14/22}  .desc{12/18 三级色}
 * 这里按用户要求给每行套上 dsh 卡片的边框（.5px border-l4、圆角 16）并在行首加一枚图标；
 * 行内布局与字号仍是上面这套。
 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    description: String,
    control: @Composable () -> Unit,
) {
    val palette = LocalDshPalette.current
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.labelSecondary,
                modifier = Modifier.padding(top = 3.dp).size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary)
                Text(description, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
            }
            control()
        }
    }
}

/** 边缘防误触的展示名（关闭 / 窄 / 中 / 宽）；未知值按「关闭」显示 */
private fun edgeGuardLabel(id: String): String = SettingsStore.EDGE_GUARD_PRESETS[id]?.first ?: "关闭"

@Composable
private fun GeneralSection(
    bindings: SettingsBindings,
    settings: SettingsStore,
) {
    var confirmFullAccess by remember { mutableStateOf(false) }
    SectionColumn {
        // 权限（dsh 的 PermissionRow，order -20）
        PermissionSetting(bindings) { confirmFullAccess = true }

        // 外观（dsh 的 AppearanceRow，order 10）
        AppearanceSetting(bindings.themePreference, bindings.onTheme)

        // 字号大小（dsh 的 FontSizeRow，order 11）
        FontSizeSetting(bindings.contentFontSize, bindings.onFontSize)

        // 对话显示（dsh 的 TranscriptViewRow，order 12）
        SelectorSetting(
            icon = DshSettingIcons.Browse,
            title = "对话显示",
            description = "控制已完成轮次的过程内容",
            options = listOf(
                SettingsStore.TRANSCRIPT_NORMAL to "标准",
                SettingsStore.TRANSCRIPT_COMPACT to "紧凑",
            ),
            value = bindings.transcriptView,
            onSelect = bindings.onTranscriptView,
        )

        // 繁忙时的发送行为（dsh 的 EnterBehaviorRow，order 20）
        SelectorSetting(
            icon = DshSettingIcons.Send,
            title = "繁忙时的发送行为",
            description = "智能体运行时，发送按钮与回车的行为",
            options = listOf(
                SettingsStore.BUSY_QUEUE to "排队发送",
                SettingsStore.BUSY_STEER to "插话发送",
            ),
            value = bindings.busyEnter,
            onSelect = bindings.onBusyEnter,
        )

        // 以下是这个客户端自己的项（dsh 没有对应插件）：边缘防误触 / 系统提示词附录 / 关于。
        // 「工作区」原来也在这里，但它跟着当前会话走（会话自带工作区），列出来只会误导；
        // 绑定入口留在抽屉的「工作区 +」里。
        SelectorSetting(
            icon = DshIcons.ShieldCheck,
            title = "边缘防误触",
            description = "屏幕左右边缘的一小条不响应触摸，挡掉握持时蹭到边缘的误触；" +
                "当前的「" + edgeGuardLabel(bindings.edgeGuard) + "」= 单边 " +
                SettingsStore.edgeGuardWidthDp(bindings.edgeGuard) + "dp",
            options = SettingsStore.EDGE_GUARD_PRESETS.map { (id, preset) -> id to preset.first },
            value = bindings.edgeGuard,
            onSelect = bindings.onEdgeGuard,
        )
        SystemPromptSetting(settings)
        AboutSetting()
    }

    if (confirmFullAccess) {
        FullAccessDialog(
            onDismiss = { confirmFullAccess = false },
            onConfirm = {
                confirmFullAccess = false
                bindings.onPermission(SettingsStore.PERMISSION_FULL_ACCESS)
            },
        )
    }
}

/** 权限（dsh 的 PermissionRow）：药丸选择器 + 启用完全权限前的风险确认 */
@Composable
private fun PermissionSetting(bindings: SettingsBindings, onRequestFullAccess: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val preset = permissionPreset(bindings.permission)
    SettingRow(
        icon = preset.icon,
        title = "权限",
        description = "新会话的默认权限模式；当前会话可以在输入栏的盾牌里随时切换。",
    ) {
        Box {
            SelectorPill(preset.label, open) { open = !open }
            if (open) {
                DshPopup(onDismiss = { open = false }, alignStart = false, below = true) {
                    DshMenuCard(Modifier.width(196.dp)) {
                        PERMISSION_PRESETS.forEach { item ->
                            DshMenuRow(
                                label = item.label,
                                icon = item.icon,
                                selected = item.id == bindings.permission,
                                onClick = {
                                    open = false
                                    if (item.id == SettingsStore.PERMISSION_FULL_ACCESS &&
                                        item.id != bindings.permission
                                    ) {
                                        onRequestFullAccess()
                                    } else {
                                        bindings.onPermission(item.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * dsh 的 RiskConfirmation（启用完全权限的二次确认）。
 *
 * 设置里这条走的是 settings.permission 字典：正文是「新会话将减少确认步骤…后续任务」，
 * 与输入框盾牌那条（智能体 / 当前任务）区分开。
 */
@Composable
private fun FullAccessDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    RiskConfirmationDialog(
        title = "确认启用完全权限？",
        description = "启用完全权限后，新会话将减少确认步骤，并且可以直接执行更多操作，" +
            "包括敏感操作、文件修改或外部命令。仅建议在你信任后续任务时使用。",
        onCancel = onDismiss,
        onConfirm = onConfirm,
    )
}

private data class ThemeCube(val id: String, val label: String, val icon: ImageVector)

/** dsh 的 CUBES：浅色 / 深色 / 跟随系统（顺序与图标逐字取自 ui-theme） */
private val THEME_CUBES = listOf(
    ThemeCube(SettingsStore.THEME_LIGHT, "浅色", DshSettingIcons.Light),
    ThemeCube(SettingsStore.THEME_DARK, "深色", DshSettingIcons.Dark),
    ThemeCube(SettingsStore.THEME_SYSTEM, "跟随系统", DshSettingIcons.FollowSystem),
)

/**
 * 外观（dsh 的 AppearanceRow）：.group 是「标题 + 三个立方」，
 * .themeCube 是 border .5px border-l4、圆角 20、竖排 图标 + 文案、选中 bg-module-platform
 * 且边框换成 --dsw-static-neutral-bluish-400（#adb2b8）。
 */
@Composable
private fun AppearanceSetting(active: String, onSelect: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val activeIcon = THEME_CUBES.firstOrNull { it.id == active }?.icon ?: DshSettingIcons.FollowSystem
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(activeIcon, contentDescription = null, tint = palette.labelSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("外观", fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THEME_CUBES.forEach { cube ->
                    ThemeCubeView(cube, cube.id == active) { onSelect(cube.id) }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ThemeCubeView(
    cube: ThemeCube,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    selected -> palette.bgModulePlatform
                    pressed -> palette.hover
                    else -> Color.Transparent
                },
            )
            .border(
                width = 0.5.dp,
                color = if (selected) NEUTRAL_BLUISH_400 else palette.borderL4,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(cube.icon, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(16.dp))
        Text(cube.label, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary, maxLines = 1)
    }
}

/** --dsw-static-neutral-bluish-400：主题立方选中时的边框色 */
private val NEUTRAL_BLUISH_400 = Color(0xFFADB2B8)

/**
 * 字号大小（dsh 的 FontSizeRow）：坐标 12..17、默认 14，
 * .stepper 是 bg-module-platform、圆角 18、最小宽 72、高 36 的药丸，
 * 值居中（tabular-nums），右侧一列 17x12 的上下箭头，末尾一个 px 单位。
 */
@Composable
private fun FontSizeSetting(value: Int, onChange: (Int) -> Unit) {
    val palette = LocalDshPalette.current
    SettingRow(
        icon = DshSettingIcons.Enhance,
        title = "字号大小",
        description = "仅影响会话内容的字号",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.bgModulePlatform)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = value.toString(),
                    modifier = Modifier.widthIn(min = 18.dp),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    color = palette.labelPrimary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StepArrow(DshSettingIcons.ChevronUp, "增大字号", value < SettingsStore.FONT_SIZE_MAX) {
                        onChange(value + 1)
                    }
                    StepArrow(DshSettingIcons.ChevronDown, "减小字号", value > SettingsStore.FONT_SIZE_MIN) {
                        onChange(value - 1)
                    }
                }
            }
            Text("px", fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelSecondary)
        }
    }
}

/** dsh 的 .arrow：17x12、圆角 3、bg-layer-1 的 75% 透明度，禁用时降到 caption 色 */
@Composable
private fun StepArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .size(width = 17.dp, height = 12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (enabled) palette.menu.copy(alpha = 0.75f) else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) palette.labelPrimary else palette.labelCaption,
            modifier = Modifier.size(9.dp),
        )
    }
}

/** dsh 的 selector 行（TranscriptViewRow / EnterBehaviorRow）：标题 + 说明 + 右侧药丸选择器 */
@Composable
private fun SelectorSetting(
    icon: ImageVector,
    title: String,
    description: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: options.firstOrNull()?.second.orEmpty()
    SettingRow(icon = icon, title = title, description = description) {
        Box {
            SelectorPill(label, open) { open = !open }
            if (open) {
                DshPopup(onDismiss = { open = false }, alignStart = false, below = true) {
                    DshMenuCard(Modifier.width(168.dp)) {
                        options.forEach { (id, text) ->
                            DshMenuRow(
                                label = text,
                                selected = id == value,
                                onClick = {
                                    open = false
                                    onSelect(id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 系统提示词附录：追加到装配好的系统提示词末尾 */
@Composable
private fun SystemPromptSetting(settings: SettingsStore) {
    val palette = LocalDshPalette.current
    var suffix by remember { mutableStateOf(settings.systemPromptSuffix) }
    var saved by remember { mutableStateOf("") }
    DisclosureCard(
        icon = DshSettingIcons.ListPen,
        title = "系统提示词附录",
        description = if (settings.systemPromptSuffix.isBlank()) "未设置" else "已设置 · " + settings.systemPromptSuffix.take(24),
    ) {
        Text(
            text = "追加到装配好的系统提示词末尾；工作区路径、文件策略与 AGENTS.md 链由框架自动注入。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.labelTertiary,
        )
        Spacer(Modifier.height(10.dp))
        DshInput(
            value = suffix,
            onValueChange = { suffix = it },
            placeholder = "附加说明（可留空）",
            singleLine = false,
            minHeight = 96.dp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("保存") {
                settings.systemPromptSuffix = suffix
                saved = "已保存（下一次请求生效）"
            }
            LinkButton("恢复默认") {
                suffix = ""
                settings.systemPromptSuffix = ""
                saved = "已清空"
            }
        }
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(saved, fontSize = 12.sp, lineHeight = 18.sp, color = palette.success)
        }
    }
}

/** 关于 */
@Composable
private fun AboutSetting() {
    val palette = LocalDshPalette.current
    DisclosureCard(
        icon = DshSettingIcons.Settings,
        title = "关于",
        description = "ADSH " + BuildConfig.VERSION_NAME,
    ) {
        Text("ADSH " + BuildConfig.VERSION_NAME, fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "包名：" + BuildConfig.APPLICATION_ID,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = palette.labelTertiary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本地自用构建；随包分发的第三方二进制许可见 THIRD_PARTY_NOTICES。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.labelTertiary,
        )
    }
}

// ------------------------------------------------------------------ 模型（dsh 的 settings.models）

/**
 * 模型分节 = dsh 的 ModelsSection（client/ui-settings-models）：
 *
 *  - 一个提供方一张卡片：.rowCard{border .5px border-l4; radius 16; padding 12 14; gap 12}
 *    .rowHead{gap 10} + .rowName{14/22 500} + .rowTag{自定义} + .credentialDot{8x8 绿/红}
 *    + .rowActions 里的 28 高小按钮「编辑」「删除」（danger 用 error 色）；
 *  - 展开后是编辑器 .editor{bg-module-platform; radius 12; padding 14 16; gap 14}：
 *    API 密钥 / API 地址 / 模型目录（每行 模型 ID + 显示名称 + 容量展开 + 删除），
 *    目录头有「正在使用适配器默认模型 / 已自定义模型目录」与「恢复默认模型」，
 *    底下是链接按钮「添加模型」「获取可用模型」；
 *  - 底部「添加提供方」创建自定义提供方（Provider ID / 显示名称 / API 协议 / API 地址）；
 *  - 删除提供方有二次确认（dsh 的 deleteTitle / deleteDescription[WithCredential]）。
 *
 * 文案逐字取自 dsh 的 settings.models 中文字典。
 */
@Composable
private fun ModelsSection(settings: SettingsStore) {
    val palette = LocalDshPalette.current
    var providers by remember { mutableStateOf(settings.providers) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ProviderDef?>(null) }
    /** dsh 的 adding：正在从**供应方目录**里挑一个（下面是下拉 + 编辑器） */
    var adding by remember { mutableStateOf(false) }
    /** dsh 的 declaring：正在手写一个自定义提供方（Provider ID / 协议 / 地址都要自己填） */
    var declaring by remember { mutableStateOf(false) }
    var presetId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun persist(next: List<ProviderDef>) {
        providers = next
        settings.providers = next
    }

    SectionColumn {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("模型", fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
            Text(
                text = "填入各提供方的 API 密钥即可使用其模型。",
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = palette.labelTertiary,
            )
        }

        providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                editing = editingId == provider.id,
                isCurrent = provider.id == settings.providerId,
                onToggleEdit = {
                    notice = null
                    editingId = if (editingId == provider.id) null else provider.id
                },
                onDelete = { deleting = provider },
                onSave = { updated ->
                    val next = providers.map { if (it.id == updated.id) updated else it }
                    persist(next)
                    // 当前模型被删掉了就顺手落到该提供方的第一条，别让输入框顶着一个不存在的模型
                    if (updated.id == settings.providerId && updated.models.none { it.id == settings.model }) {
                        updated.models.firstOrNull()?.let { settings.model = it.id }
                    }
                    editingId = null
                    notice = "已保存 " + updated.displayName + "。"
                },
            )
        }

        // dsh 的 .addActions：两个虚线按钮并排（「添加提供方」与「添加自定义提供方」），
        // 点下去之后按钮**被卡片替换**（dsh 的 addCard：顶部「提供方」下拉 + 该提供方的编辑器）。
        val addedIds = providers.map { it.id }.toSet()
        val addable = ProviderCatalog.presets.filterNot { it.id in addedIds }
        val saved: (ProviderDef) -> Unit = { created ->
            persist(providers + created)
            adding = false
            declaring = false
            editingId = created.id
            notice = "已保存 " + created.displayName + "。"
        }
        when {
            adding -> CustomProviderEditor(
                taken = providers.map { it.id },
                preset = addable.firstOrNull { it.id == presetId },
                presetChoices = addable,
                onPresetChange = { presetId = it },
                onCancel = { adding = false },
                onCreate = saved,
            )
            declaring -> CustomProviderEditor(
                taken = providers.map { it.id },
                onCancel = { declaring = false },
                onCreate = saved,
            )
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AddProviderButton("添加提供方", enabled = addable.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    presetId = addable.firstOrNull()?.id
                    declaring = false
                    adding = true
                }
                AddProviderButton("添加自定义提供方", enabled = true, modifier = Modifier.weight(1f)) {
                    adding = false
                    declaring = true
                }
            }
        }

        notice?.let {
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = palette.success)
        }
    }

    deleting?.let { target ->
        DeleteProviderDialog(
            provider = target,
            onDismiss = { deleting = null },
            onConfirm = {
                persist(providers.filterNot { it.id == target.id })
                deleting = null
                if (settings.providerId == target.id) settings.providerId = providers.firstOrNull()?.id.orEmpty()
            },
        )
    }
}

/** dsh 的 provider rowCard：卡片头 + 展开后的编辑器 */
@Composable
private fun ProviderCard(
    provider: ProviderDef,
    editing: Boolean,
    isCurrent: Boolean,
    onToggleEdit: () -> Unit,
    onDelete: () -> Unit,
    onSave: (ProviderDef) -> Unit,
) {
    val palette = LocalDshPalette.current
    val scope = rememberCoroutineScope()
    // 编辑中的草稿：密钥不回显（dsh 的 keyStored：已配置——输入新值可替换）
    var key by remember(provider) { mutableStateOf("") }
    var baseUrl by remember(provider) { mutableStateOf(provider.baseUrl) }
    var models by remember(provider) { mutableStateOf(provider.models) }
    var fetching by remember(provider) { mutableStateOf(false) }
    var saveError by remember(provider) { mutableStateOf<String?>(null) }

    val defaultModels = BuiltInProviders.DEEPSEEK_MODELS
    val overridden = provider.custom || models != defaultModels
    val configured = provider.apiKey.isNotBlank() || key.isNotBlank()

    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = provider.displayName,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
                if (provider.custom) DshTag("自定义", TagTone.Outline)
                CredentialDot(configured)
                if (isCurrent) DshTag("当前", TagTone.Neutral)
                Spacer(Modifier.weight(1f))
                SecondaryButton(if (editing) "收起" else "编辑", small = true) { onToggleEdit() }
                DangerSmallButton("删除") { onDelete() }
            }

            if (editing) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.bgLayer3)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("编辑 " + provider.displayName, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
                        Text(provider.id, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
                    }

                    // API 密钥
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("API 密钥", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
                        DshInput(
                            value = key,
                            onValueChange = { key = it },
                            placeholder = if (provider.apiKey.isNotBlank()) "已配置——输入新值可替换" else "输入 API 密钥",
                            secret = true,
                        )
                    }

                    // API 地址
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("API 地址", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
                        DshInput(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            placeholder = BuiltInProviders.DEEPSEEK_BASE_URL,
                        )
                    }

                    // 模型目录
                    ModelCatalogEditor(
                        models = models,
                        overridden = overridden,
                        deepSeek = !provider.custom,
                        onModels = { models = it },
                        onReset = { models = if (provider.custom) emptyList() else defaultModels },
                        onFetch = { fetching = true },
                        fetchEnabled = baseUrl.isNotBlank() || provider.baseUrl.isNotBlank(),
                    )

                    saveError?.let {
                        Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = palette.errorLabel)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        SecondaryButton("取消") { onToggleEdit() }
                        PrimaryButton("保存") {
                            val trimmedKey = key.trim()
                            val next = provider.copy(
                                baseUrl = baseUrl.trim().ifEmpty { provider.baseUrl },
                                apiKey = if (trimmedKey.isNotEmpty()) trimmedKey else provider.apiKey,
                                models = models,
                            )
                            if (next.models.isEmpty()) {
                                saveError = "自定义提供方至少需要一个模型。"
                            } else {
                                saveError = null
                                key = ""
                                onSave(next)
                            }
                        }
                    }
                }
            }
        }
    }

    if (fetching) {
        FetchModelsDialog(
            baseUrl = baseUrl.trim().ifEmpty { provider.baseUrl },
            apiKey = key.trim().ifEmpty { provider.apiKey },
            existing = models.map { it.id },
            onDismiss = { fetching = false },
            onAdopt = { adopted ->
                models = models + adopted
                fetching = false
            },
        )
    }
}

/**
 * 模型目录编辑器，逐项对齐 dsh 的 ModelListEditor（settings-models/lib/client.js 的 JSX + CSS）：
 *
 *  - .modelCatalog{border-top:.5px solid border-l2;padding-top:12px;gap:10px}
 *  - .modelListHead{justify-content:space-between}：左边标题「模型目录」12/500/18 二级色 +
 *    下面一行 12/18 三级色（正在使用适配器默认模型 / 已自定义模型目录），右边 linkButton
 *    「恢复默认模型」「获取可用模型」
 *  - 每个模型是一张 .modelEntry{border .5px border-l4;border-radius:10px;padding:6px}：
 *    .modelRow 是「模型 ID(1.4fr) / 显示名称(1fr) / 容量箭头 / 删除」四列，gap 6；
 *    展开后是 .modelAdvanced{auto-fit minmax(160px,1fr);gap:8px;padding:8px 4px 2px}，两个字段各带 12/18 标签
 *  - .addModelButton：border .5px border-l3、圆角 14、高 28、12/18 的「添加模型」药丸（左对齐）
 *  - 空目录是 .modelEmpty（虚线框、居中、padding 12）
 */
@Composable
private fun ModelCatalogEditor(
    models: List<ModelDef>,
    overridden: Boolean,
    deepSeek: Boolean,
    onModels: (List<ModelDef>) -> Unit,
    onReset: () -> Unit,
    onFetch: () -> Unit,
    fetchEnabled: Boolean = true,
) {
    val palette = LocalDshPalette.current
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    val entryShape = RoundedCornerShape(10.dp)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DshHairline()
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // dsh 的 .modelListHead 是「标题 + 右侧两个 linkButton」一行；手机宽度放不下，
            // 挤在一起会把「已自定义模型目录」折成两行，所以标题一行、按钮另起一行右对齐。
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("模型目录", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
                Text(
                    text = if (overridden) "已自定义模型目录" else "正在使用适配器默认模型",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.labelTertiary,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.weight(1f))
                if (overridden && models.isNotEmpty()) LinkButton("恢复默认模型") { onReset() }
                LinkButton("获取可用模型", enabled = fetchEnabled) { onFetch() }
            }

            if (models.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, palette.borderL3, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "模型选择器中将不显示任何模型；目录外 ID 仍可直接发送。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = palette.labelTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            models.forEachIndexed { index, model ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(entryShape)
                        .border(0.5.dp, palette.borderL4, entryShape)
                        .padding(6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.weight(1.4f)) {
                            DshInput(
                                value = model.id,
                                onValueChange = { value -> onModels(models.toMutableList().also { it[index] = model.copy(id = value) }) },
                                placeholder = "模型 ID",
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            DshInput(
                                value = model.name.orEmpty(),
                                onValueChange = { value ->
                                    onModels(models.toMutableList().also {
                                        it[index] = model.copy(name = value.ifEmpty { null })
                                    })
                                },
                                placeholder = "留空时使用模型 ID",
                            )
                        }
                        IconActionButton(
                            icon = if (index in expanded) DshSettingIcons.ChevronDown else DshIcons.ChevronRight,
                            description = "容量",
                            tint = palette.labelTertiary,
                        ) {
                            expanded = if (index in expanded) expanded - index else expanded + index
                        }
                        IconActionButton(
                            icon = DshSidebarIcons.Trash,
                            description = "删除模型",
                            tint = palette.errorLabel,
                        ) {
                            onModels(models.filterIndexed { i, _ -> i != index })
                        }
                    }
                    if (index in expanded) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CapacityField(
                                label = "上下文窗口",
                                value = model.contextWindow,
                                modifier = Modifier.weight(1f),
                            ) { capacity ->
                                onModels(models.toMutableList().also { list -> list[index] = model.copy(contextWindow = capacity) })
                            }
                            CapacityField(
                                // dsh 的 ModelListEditor 用 modelMaxTokens「最大输出 token」，
                                // DeepSeek 那个编辑器用 maxTokens「最大输出 token 数」
                                label = if (deepSeek) "最大输出 token 数" else "最大输出 token",
                                value = model.maxTokens,
                                modifier = Modifier.weight(1f),
                            ) { capacity ->
                                onModels(models.toMutableList().also { list -> list[index] = model.copy(maxTokens = capacity) })
                            }
                        }
                        // 可识别图片（dsh 的 inputModalities 含 image；这条开关是 ADSH 的扩展 ——
                        // 目录里没收录、实际能识图的模型（例如 qwen3.8-flash）在这里手动打开，默认关闭）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DshCheckbox(
                                checked = model.imageInput
                                    ?: com.adsh.app.core.data.SettingsStore.acceptsImages(model.id),
                                onCheckedChange = { on ->
                                    onModels(
                                        models.toMutableList().also { list ->
                                            list[index] = model.copy(imageInput = on)
                                        },
                                    )
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text("可识别图片", fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelPrimary)
                                Text(
                                    "这个模型能收图片输入。打开后 AI 可以用 read_image 读图，" +
                                        "你发的图片也会真的发给它。",
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = palette.labelTertiary,
                                )
                            }
                        }
                    }
                }
            }

            AddModelButton { onModels(models + ModelDef(id = "")) }
        }
    }
}

/** dsh 的 .addModelButton：border .5px border-l3、圆角 14、高 28、12/18，左对齐的药丸 */
@Composable
private fun AddModelButton(onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (pressed) palette.hover else Color.Transparent)
            .border(0.5.dp, palette.borderL3, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("添加模型", fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelPrimary)
    }
}

/** 容量字段（dsh 的 modelField）：留空用提供方默认值，支持 128K / 1M 这样的写法 */
@Composable
private fun CapacityField(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    onValue: (Long) -> Unit,
) {
    val palette = LocalDshPalette.current
    var text by remember(value) { mutableStateOf(if (value > 0) formatCapacity(value) else "") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelSecondary)
        DshInput(
            value = text,
            onValueChange = { typed ->
                text = typed
                parseCapacity(typed)?.let { onValue(it) }
            },
            placeholder = "使用提供方默认值",
        )
    }
}

/** dsh 的 formatCapacity：1024 的整数倍写成 K / M */
private fun formatCapacity(value: Long): String = when {
    value <= 0 -> ""
    value % (1024L * 1024L) == 0L -> (value / (1024L * 1024L)).toString() + "M"
    value % 1024L == 0L -> (value / 1024L).toString() + "K"
    else -> value.toString()
}

/** dsh 的 parseCapacity：纯数字或带 K / M 后缀 */
private fun parseCapacity(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0L
    val suffix = trimmed.last().uppercaseChar()
    val body = if (suffix == 'K' || suffix == 'M') trimmed.dropLast(1) else trimmed
    val number = body.trim().toLongOrNull() ?: return null
    if (number <= 0) return 0L
    return when (suffix) {
        'K' -> number * 1024L
        'M' -> number * 1024L * 1024L
        else -> number
    }
}

/** dsh 的 .dangerButton（行内小号）：28 高、圆角 14、12/18、error 色 */
@Composable
private fun DangerSmallButton(text: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = palette.errorLabel)
    }
}

/** dsh 的 fetchDialog：选择要添加的模型（搜索 + 全选/取消全选 + 勾选清单） */
@Composable
private fun FetchModelsDialog(
    baseUrl: String,
    apiKey: String,
    existing: List<String>,
    onDismiss: () -> Unit,
    onAdopt: (List<ModelDef>) -> Unit,
) {
    val palette = LocalDshPalette.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    // 服务端一共列了几个（candidates + 已经在目录里的）：全被过滤掉时要说清楚，
    // 否则「获取模型」明明成功（HTTP 200），界面却只说「该提供方没有列出任何模型」
    var fetched by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(baseUrl, apiKey) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                com.adsh.app.core.llm.LlmClient(
                    { com.adsh.app.core.llm.ProviderConfig(baseUrl = baseUrl, apiKey = apiKey, model = "") },
                ).listModels()
            }
        }
        result.fold(
            onSuccess = { ids ->
                fetched = ids.size
                candidates = ids.filterNot { it in existing }
            },
            onFailure = { error = it.message ?: it::class.java.simpleName },
        )
        loading = false
    }

    val filtered = remember(candidates, query) {
        if (query.isBlank()) candidates else candidates.filter { it.contains(query, ignoreCase = true) }
    }

    val visible = !loading && error == null && candidates.isNotEmpty()
    val allPicked = filtered.isNotEmpty() && filtered.all { it in selected }

    DshModal(
        onDismiss = onDismiss,
        title = "选择要添加的模型",
        description = "以下是模型提供方的可用模型，勾选要添加的模型。",
        // dsh 的 .fetchDialog{max-width:520px}
        width = 520.dp,
        footer = {
            DshButton(text = "取消", onClick = onDismiss)
            DshButton(
                text = "添加所选",
                onClick = {
                    onAdopt(
                        selected.sorted().map { id ->
                            com.adsh.app.core.data.BuiltInProviders.knownModel(id) ?: ModelDef(id)
                        },
                    )
                },
                enabled = visible,
            )
        },
    ) {
        when {
            loading -> Text("正在询问提供方…", fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelSecondary)
            error != null -> Text("获取失败：" + error, fontSize = 13.sp, lineHeight = 20.sp, color = palette.errorLabel)
            candidates.isEmpty() -> Text(
                text = if (fetched > 0) {
                    "服务端返回 " + fetched + " 个模型，全部已经在目录里了（无需添加）。"
                } else {
                    "该提供方没有列出任何模型，请手动添加。"
                },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelSecondary,
            )
            else -> {
                // .candidateToolbar{align-items:center;gap:8px;margin-bottom:6px}
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        DshInput(value = query, onValueChange = { query = it }, placeholder = "搜索模型")
                    }
                    DshButton(
                        text = if (allPicked) "取消全选" else "全选",
                        onClick = { selected = if (allPicked) emptySet() else filtered.toSet() },
                        kind = DshButtonKind.Ghost,
                        small = true,
                        enabled = filtered.isNotEmpty(),
                    )
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = "没有匹配的模型。",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = palette.labelSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { id ->
                        val checked = id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    selected = if (checked) selected - id else selected + id
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CheckBoxMark(checked)
                            Text(
                                text = id,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                fontFamily = FontFamily.Monospace,
                                color = palette.labelPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * dsh 的 .addButton：虚线描边、44 高、圆角 16、内容居中（图标 + 文案）。
 * dsh 的两个按钮放在 .addActions（flex-wrap、gap 10）里并排，宽度 flex:1 1 0。
 */
@Composable
private fun AddProviderButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (pressed && enabled) palette.hover else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            // dsh 的 border 是 dashed：Compose 的 Modifier.border 画不了虚线，这里手画一圈。
            // **必须画在 Row 自己身上**：之前是一个 Canvas(Modifier.fillMaxSize()) 子项 ——
            // 那时外层是 Box（子项叠着放）没问题，换成 Row（子项横着排）之后 Canvas 会先把
            // 整行宽度吃掉，图标和文字被挤成 0 宽，于是只剩一个隐约的空方框。
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                drawRoundRect(
                    color = if (enabled) palette.borderL3 else palette.borderL2,
                    style = Stroke(width = 1f, pathEffect = dash),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            DshIcons.Plus,
            contentDescription = null,
            tint = if (enabled) palette.labelPrimary else palette.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = if (enabled) palette.labelPrimary else palette.labelTertiary,
        )
    }
}

/**
 * dsh 的 CustomProviderCard：创建自定义提供方的编辑器（不是弹窗，就在列表里原地展开）。
 *
 * 字段顺序与 dsh 一致：Provider ID（+ 说明 / 报错）、显示名称、API 地址（+ 报错）、API 协议、
 * API 密钥、模型目录，最后右下角「取消 / 创建提供方」。dsh 的 ready 条件同样要求
 * 至少有一个模型（customNeedsModels），所以没填模型时创建按钮是禁用的并给出提示。
 */
@Composable
private fun CustomProviderEditor(
    taken: List<String>,
    /**
     * 从**供应方目录**里挑中的那一条（dsh 的 catalog route）：给了就隐藏身份字段
     * （Provider ID / 显示名称 / API 协议 —— dsh 的 ownsIdentity 只对手写 route 开），
     * 并把 API 地址与模型目录预填成目录里的值。null = 手写自定义提供方。
     */
    preset: ProviderCatalog.Preset? = null,
    /** 非空时在卡片顶部渲染 dsh 的「提供方」下拉（选择要添加哪一条目录 route） */
    presetChoices: List<ProviderCatalog.Preset> = emptyList(),
    onPresetChange: (String) -> Unit = {},
    onCancel: () -> Unit,
    onCreate: (ProviderDef) -> Unit,
) {
    val palette = LocalDshPalette.current
    val catalogRoute = preset != null
    var route by remember(preset?.id) { mutableStateOf(preset?.id ?: "") }
    var displayName by remember(preset?.id) { mutableStateOf(preset?.displayName ?: "") }
    var baseUrl by remember(preset?.id) { mutableStateOf(preset?.baseUrl ?: "") }
    var api by remember { mutableStateOf(ApiProtocol.OPENAI_COMPLETIONS) }
    var apiOpen by remember { mutableStateOf(false) }
    var providerOpen by remember { mutableStateOf(false) }
    var key by remember(preset?.id) { mutableStateOf("") }
    var models by remember(preset?.id) {
        mutableStateOf(preset?.models?.map { ModelDef(it) } ?: emptyList<ModelDef>())
    }
    var fetching by remember { mutableStateOf(false) }

    val routeInvalid = route.isNotEmpty() && !BuiltInProviders.validProviderId(route)
    val routeTaken = route.isNotEmpty() && route in taken
    val normalizedBaseUrl = baseUrl.trim()
    val baseInvalid = baseUrl.isNotEmpty() &&
        !(normalizedBaseUrl.startsWith("http://") || normalizedBaseUrl.startsWith("https://"))
    val hasModels = models.any { it.id.isNotBlank() }
    val ready = route.isNotEmpty() && !routeInvalid && !routeTaken &&
        normalizedBaseUrl.isNotEmpty() && !baseInvalid && hasModels
    val hint = when {
        ready || route.isEmpty() || routeInvalid || routeTaken || baseInvalid -> null
        normalizedBaseUrl.isEmpty() -> "自定义提供方需要填写 API 地址。"
        !hasModels -> "自定义提供方至少需要一个模型。"
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bgModulePlatform)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = preset?.displayName ?: "自定义提供方",
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = palette.labelPrimary,
        )

        // dsh 的 addCard 顶部就是这一行「提供方」下拉（原生 select，选项 = 目录里还没配置过的 route）
        if (presetChoices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("提供方", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
                Box {
                    SelectorPill(preset?.displayName ?: "选择提供方", providerOpen) { providerOpen = !providerOpen }
                    if (providerOpen) {
                        DshPopup(onDismiss = { providerOpen = false }, alignStart = true, below = true) {
                            DshMenuCard(
                                Modifier
                                    .width(260.dp)
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                presetChoices.forEach { choice ->
                                    DshMenuRow(
                                        label = choice.displayName,
                                        selected = choice.id == preset?.id,
                                        onClick = {
                                            providerOpen = false
                                            onPresetChange(choice.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!catalogRoute) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Provider ID", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = route,
                onValueChange = { route = it },
                placeholder = "acme-gateway",
                invalid = routeInvalid || routeTaken,
            )
            Text(
                text = when {
                    routeInvalid -> "需以小写字母开头，之后可用小写字母、数字和短横线。"
                    routeTaken -> "已有提供方使用了这个 ID。"
                    else -> "以小写字母开头的标识，在请求中唯一标识该提供方，并用于派生凭据名。"
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (routeInvalid || routeTaken) palette.errorLabel else palette.labelTertiary,
            )
        }

        if (!catalogRoute) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("显示名称", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = route.ifEmpty { "显示名称" },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("API 地址", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = "https://gateway.example/v1",
                invalid = baseInvalid,
            )
            if (baseInvalid) {
                Text("请输入有效的 HTTP 或 HTTPS 地址。", fontSize = 12.sp, lineHeight = 18.sp, color = palette.errorLabel)
            }
        }

        // 目录 route 的协议由它自己的目录条目定死（dsh 的 ownsIdentity 只对手写 route 开）
        if (!catalogRoute) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("API 协议", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            Box {
                SelectorPill(ApiProtocol.label(api), apiOpen) { apiOpen = !apiOpen }
                if (apiOpen) {
                    DshPopup(onDismiss = { apiOpen = false }, alignStart = true, below = true) {
                        DshMenuCard(Modifier.width(240.dp)) {
                            ApiProtocol.ALL.forEach { item ->
                                DshMenuRow(
                                    label = ApiProtocol.label(item),
                                    selected = item == api,
                                    onClick = {
                                        api = item
                                        apiOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "只有 OpenAI 兼容（chat completions）协议可以直接使用；Anthropic 协议会在请求时回退到 chat completions。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("API 密钥", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = key,
                onValueChange = { key = it },
                placeholder = "输入 API 密钥",
                secret = true,
            )
        }

        ModelCatalogEditor(
            models = models,
            overridden = false,
            deepSeek = false,
            onModels = { models = it },
            onReset = { models = emptyList() },
            onFetch = { fetching = true },
            fetchEnabled = normalizedBaseUrl.isNotEmpty(),
        )

        hint?.let {
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SecondaryButton("取消") { onCancel() }
            PrimaryButton("创建提供方", enabled = ready) {
                onCreate(
                    ProviderDef(
                        id = route,
                        displayName = displayName.ifBlank { route },
                        baseUrl = normalizedBaseUrl,
                        apiKey = key.trim(),
                        api = api,
                        models = models.filter { it.id.isNotBlank() },
                        // dsh 的 declared：目录里没有的 route 才打「自定义」标签
                        custom = !catalogRoute,
                    ),
                )
            }
        }
    }

    if (fetching) {
        FetchModelsDialog(
            baseUrl = normalizedBaseUrl,
            apiKey = key.trim(),
            existing = models.map { it.id },
            onDismiss = { fetching = false },
            onAdopt = { adopted ->
                models = models + adopted
                fetching = false
            },
        )
    }
}

/** dsh 的删除提供方确认（deleteTitle / deleteDescription[WithCredential]） */
@Composable
private fun DeleteProviderDialog(
    provider: ProviderDef,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalDshPalette.current
    DshModal(
        onDismiss = onDismiss,
        title = "删除 " + provider.displayName + "？",
        footer = {
            DshButton(text = "取消", onClick = onDismiss)
            DshButton(text = "删除 " + provider.displayName, onClick = onConfirm, kind = DshButtonKind.Primary)
        },
    ) {
        Text(
            text = if (provider.apiKey.isNotBlank()) {
                "删除 " + provider.displayName + " 会移除其配置和存储的 API 密钥。"
            } else {
                "删除 " + provider.displayName + " 会移除其配置；其使用的凭证（如有）由其他位置管理，将会保留。"
            },
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = palette.labelPrimary,
        )
    }
}


// ------------------------------------------------------------------ 功能（dsh 的 settings.plugins）

/**
 * 功能分节 = dsh 的「插件」分节（PluginsSettingsSection + PluginCard）：
 *   .heading{18/600}  .intro{13 三级色}
 *   .card{border:.5px solid border-l4; background:bg-layer-3; border-radius:16px}
 *   .cardOpen{background:bg-layer-2}  .header{padding:14px 16px; gap:12px}
 *   .name{15/1.4 600}  .description{13/1.5 三级色}  .chevron 展开时转 180°
 *   .body{border-top:.5px solid border-l2; margin:0 16px; padding-bottom:8px}
 *   .footer{border-top:.5px solid border-l2; padding:12px 0 4px; 右对齐的 放弃修改 / 保存}
 * 折叠状态是卡片自己的阅读状态；保存成功后自动收起（dsh 的 PluginCard 就是这样）。
 */
@Composable
private fun FeaturesSection(
    settings: SettingsStore,
    bootstrapReady: Boolean,
    bootstrapDir: String,
    bootstrapError: String?,
    onOpenTerminal: () -> Unit,
) {
    val palette = LocalDshPalette.current
    SectionColumn {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("功能", fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = palette.labelPrimary)
            Text(
                text = "配置和查看这个客户端已装好的插件：终端与网页搜索。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelTertiary,
            )
        }

        // 终端（dsh 的 BashCard：shell.timeoutMs / shell.maxTimeoutMs / shell.maxOutputBytes）
        var timeout by remember { mutableStateOf(settings.bashTimeoutMs.toString()) }
        var maxTimeout by remember { mutableStateOf(settings.bashMaxTimeoutMs.toString()) }
        var maxOutput by remember { mutableStateOf(settings.bashMaxOutputBytes.toString()) }
        var bashSaving by remember { mutableStateOf(false) }
        var bashFailed by remember { mutableStateOf<String?>(null) }
        val timeoutValid = (timeout.trim().toLongOrNull() ?: 0L) > 0L
        val maxTimeoutValid = (maxTimeout.trim().toLongOrNull() ?: 0L) > 0L
        val outputValid = (maxOutput.trim().toIntOrNull() ?: 0) > 0
        val bashDirty = timeout.trim() != settings.bashTimeoutMs.toString() ||
            maxTimeout.trim() != settings.bashMaxTimeoutMs.toString() ||
            maxOutput.trim() != settings.bashMaxOutputBytes.toString()
        PluginCard(
            icon = DshSettingIcons.Api,
            title = "终端",
            description = "限制 agent 运行的每一条命令。",
            dirty = bashDirty,
            saving = bashSaving,
            failed = bashFailed,
            onSave = {
                if (!timeoutValid || !maxTimeoutValid || !outputValid) {
                    bashFailed = "三个字段都要填正整数。"
                } else if (maxTimeout.trim().toLong() < timeout.trim().toLong()) {
                    bashFailed = "超时上限不能小于默认超时。"
                } else {
                    bashSaving = true
                    settings.bashTimeoutMs = timeout.trim().toLong()
                    settings.bashMaxTimeoutMs = maxTimeout.trim().toLong()
                    settings.bashMaxOutputBytes = maxOutput.trim().toInt()
                    timeout = settings.bashTimeoutMs.toString()
                    maxTimeout = settings.bashMaxTimeoutMs.toString()
                    maxOutput = settings.bashMaxOutputBytes.toString()
                    bashFailed = null
                    bashSaving = false
                }
            },
            onDiscard = {
                timeout = settings.bashTimeoutMs.toString()
                maxTimeout = settings.bashMaxTimeoutMs.toString()
                maxOutput = settings.bashMaxOutputBytes.toString()
                bashFailed = null
            },
        ) {
            ValueField(
                label = "命令超时（毫秒）",
                hint = "模型没有自己指定超时时用这个值；超时会终止整棵进程树。",
                value = timeout,
                onValueChange = { timeout = it },
                numeric = true,
                invalid = !timeoutValid,
                first = true,
            )
            ValueField(
                label = "超时上限（毫秒）",
                hint = "模型自己给的 timeoutMs 最多放宽到这里（dsh 的 shell.maxTimeoutMs）。",
                value = maxTimeout,
                onValueChange = { maxTimeout = it },
                numeric = true,
                invalid = !maxTimeoutValid,
            )
            ValueField(
                label = "单流输出上限（字节）",
                hint = "stdout 与 stderr 各留这么多，超出只保留尾部并标记 truncated。",
                value = maxOutput,
                onValueChange = { maxOutput = it },
                numeric = true,
                invalid = !outputValid,
            )
            BoxedNavRow(
                icon = DshSettingIcons.Api,
                label = "终端会话",
                detail = when {
                    bootstrapReady -> "bootstrap 已安装"
                    bootstrapError != null -> "bootstrap 安装失败"
                    else -> "bootstrap 未安装"
                },
                onClick = onOpenTerminal,
            )
            Text(
                text = "Termux 前缀位于 " + bootstrapDir,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.labelTertiary,
                modifier = Modifier.padding(bottom = if (bootstrapError == null) 10.dp else 4.dp),
            )
            // 失败原因必须露出来：只写「未安装」的话，用户没有任何线索，
            // 只能靠 adb logcat（第一次踩到的 ELOOP 就是这么被埋掉的）
            if (bootstrapError != null) {
                Text(
                    text = "上次安装失败：" + bootstrapError,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = palette.errorLabel,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        // Agent 循环（dsh 的 AgentLoopCard：agent-loop.maxParallelToolCalls，默认 10、最小 1）
        var maxParallel by remember { mutableStateOf(settings.agentMaxParallel.toString()) }
        var loopSaving by remember { mutableStateOf(false) }
        var loopFailed by remember { mutableStateOf<String?>(null) }
        val parallelValid = (maxParallel.trim().toIntOrNull() ?: 0) > 0
        val loopDirty = maxParallel.trim() != settings.agentMaxParallel.toString()
        PluginCard(
            icon = DshSidebarIcons.Branch,
            title = "Agent 循环",
            description = "Agent 如何派发工具调用。",
            dirty = loopDirty,
            saving = loopSaving,
            failed = loopFailed,
            onSave = {
                if (!parallelValid) {
                    loopFailed = "并行工具调用数要填正整数。"
                } else {
                    loopSaving = true
                    settings.agentMaxParallel = maxParallel.trim().toInt()
                    // 立刻生效：闸门不用等到下一轮才换上限
                    com.adsh.app.core.tools.ToolConcurrency.configure(settings.agentMaxParallel)
                    maxParallel = settings.agentMaxParallel.toString()
                    loopFailed = null
                    loopSaving = false
                }
            },
            onDiscard = {
                maxParallel = settings.agentMaxParallel.toString()
                loopFailed = null
            },
        ) {
            // dsh 的 agent-loop 设置里只有这一项（AGENT_LOOP_SETTINGS_SCHEMA）。
            // ADSH 以前多一个「单条消息往返轮数上限」，已按 dsh 去掉：
            // 长任务不该被次数截断，真正要防的是「模型调用失误」——
            // 那由同一组参数重复 3 次 / 单条消息工具调用超过 300 次这两条死循环判据兜住。
            ValueField(
                label = "并行工具调用数",
                hint = "同一个 run_code 程序里最多同时运行多少个 tools.x() 调用；用 Promise.all 包起来的一批按这个上限并发（dsh 的 agent-loop.maxParallelToolCalls）。",
                value = maxParallel,
                onValueChange = { maxParallel = it },
                numeric = true,
                invalid = !parallelValid,
                first = true,
            )
        }

        // 网页搜索（dsh 的 WebSearchCard：apiKey / baseURL / maxUses）+ 搜索引擎可换
        // （dsh 的 ctx.web 搜索提供方注册表：内置 deepseek-official 或 exa）。
        // backendId 是**待保存**的后端：在「更换」里选了之后卡片整页换成新后端的那一套字段，
        // 点保存才真正切过去（这时另一个搜索引擎就关掉了）；点放弃修改则连选择一起还原。
        var backendId by remember { mutableStateOf(settings.webSearchProvider) }
        var pickingBackend by remember { mutableStateOf(false) }
        var webKey by remember(backendId) { mutableStateOf("") }
        var webBase by remember(backendId) { mutableStateOf(settings.webSearchBaseUrlOf(backendId)) }
        var webMaxUses by remember(backendId) { mutableStateOf(settings.webSearchMaxUsesOf(backendId).toString()) }
        var webSaving by remember { mutableStateOf(false) }
        var webFailed by remember { mutableStateOf<String?>(null) }
        val backend = SettingsStore.WEB_SEARCH_BACKENDS.firstOrNull { it.id == backendId }
            ?: SettingsStore.WEB_SEARCH_BACKENDS.first()
        val savedBackend = settings.webSearchProvider
        val webConfigured = settings.webSearchApiKeyOf(backendId).isNotBlank() || webKey.isNotBlank()
        val maxUsesValid = (webMaxUses.trim().toIntOrNull() ?: 0) > 0
        // Exa 的 /search 一条 query 一次请求，所以「单次搜索上限」在那边的含义是 numResults
        val exa = backendId == SettingsStore.WEB_SEARCH_PROVIDER_EXA
        val webDirty = webKey.isNotBlank() ||
            webBase.trim() != settings.webSearchBaseUrlOf(backendId) ||
            webMaxUses.trim() != settings.webSearchMaxUsesOf(backendId).toString() ||
            backendId != savedBackend
        PluginCard(
            icon = DshSettingIcons.Globe,
            title = "网页搜索",
            description = backend.name + " 搜索提供方" + if (backendId == savedBackend) "。" else "（保存后生效）。",
            dirty = webDirty,
            saving = webSaving,
            failed = webFailed,
            extraAction = { SecondaryButton("更换") { pickingBackend = true } },
            onSave = {
                if (!maxUsesValid) {
                    webFailed = "单次搜索上限要填正整数。"
                } else {
                    webSaving = true
                    // 先切后端，再写它自己那一份配置（地址与密钥按后端分开存）
                    settings.webSearchProvider = backendId
                    if (webKey.isNotBlank()) settings.setWebSearchApiKey(backendId, webKey.trim())
                    settings.setWebSearchBaseUrl(
                        backendId,
                        webBase.trim().ifEmpty { SettingsStore.defaultWebSearchBaseUrl(backendId) },
                    )
                    settings.setWebSearchMaxUses(backendId, webMaxUses.trim().toInt())
                    webKey = ""
                    webBase = settings.webSearchBaseUrlOf(backendId)
                    webMaxUses = settings.webSearchMaxUsesOf(backendId).toString()
                    webFailed = null
                    webSaving = false
                }
            },
            onDiscard = {
                backendId = savedBackend
                webKey = ""
                webBase = settings.webSearchBaseUrlOf(backendId)
                webMaxUses = settings.webSearchMaxUsesOf(backendId).toString()
                webFailed = null
            },
        ) {
            SecretField(
                label = "API Key",
                stateLabel = if (webConfigured) "已配置密钥。" else "未配置密钥；配置之前搜索不可用。",
                configured = webConfigured,
                hint = "只存在本机。留空表示保持当前密钥。",
                value = webKey,
                onValueChange = { webKey = it },
                first = true,
            )
            ValueField(
                label = "接口地址",
                hint = "请求时拼 " + backend.endpoint + "：" + SettingsStore.defaultWebSearchBaseUrl(backendId),
                value = webBase,
                onValueChange = { webBase = it },
                placeholder = SettingsStore.defaultWebSearchBaseUrl(backendId),
            )
            ValueField(
                label = if (exa) "一次搜索取多少条" else "单次请求最多搜索次数",
                hint = if (exa) {
                    "每条 query 向 Exa 取多少条候选（Exa 的 numResults，默认 10）；" +
                        "最终最多给模型 7 条来源，超出的截断并标注。没有片段的结果会被丢掉。"
                } else {
                    "一次请求在必须作答前最多可以搜索多少次（Anthropic 的 max_uses）。"
                },
                value = webMaxUses,
                onValueChange = { webMaxUses = it },
                numeric = true,
                invalid = !maxUsesValid,
            )
        }
        if (pickingBackend) {
            WebSearchBackendDialog(
                selected = backendId,
                active = savedBackend,
                onDismiss = { pickingBackend = false },
                onPick = { picked ->
                    // 只换卡片显示的那一套；保存时才真正切换
                    backendId = picked
                    pickingBackend = false
                },
            )
        }
    }
}

/**
 * 「更换搜索引擎」子窗口（dsh 里没有这一层 UI：dsh 的搜索提供方由配置文件的 provider id
 * 决定，这里把它做成设置页里的选择器）。列出的就是 ctx.web 里装着的两个提供方；
 * 选中只改卡片正在显示的那一套字段，真正切换发生在「保存」。
 */
@Composable
private fun WebSearchBackendDialog(
    selected: String,
    active: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val palette = LocalDshPalette.current
    DshModal(
        onDismiss = onDismiss,
        title = "选择搜索引擎",
        footer = { DshButton(text = "取消", onClick = onDismiss) },
    ) {
        // 一行一个引擎：名字（+「当前生效」标签）/ 一句说明 / 最右边是「卡片正在显示的那一个」的对勾。
        // 对勾位置固定占位（没选中的用透明图标），切来切去不会整行抖动。
        Column(Modifier.fillMaxWidth()) {
            SettingsStore.WEB_SEARCH_BACKENDS.forEachIndexed { index, backend ->
                if (index > 0) Spacer(Modifier.height(2.dp))
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val picked = backend.id == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (pressed) palette.hover else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null) { onPick(backend.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = backend.name,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.labelPrimary,
                            )
                            if (backend.id == active) DshTag(text = "当前生效", tone = TagTone.Outline)
                        }
                        Text(
                            text = backend.description,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = palette.labelTertiary,
                        )
                    }
                    Icon(
                        imageVector = DshSettingIcons.Check,
                        contentDescription = if (picked) "已选择" else null,
                        tint = palette.labelPrimary,
                        modifier = Modifier.size(16.dp).alpha(if (picked) 1f else 0f),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 设置页共用的小件（尺寸都来自 dsh 的 CSS）

/** dsh 的卡片边框：.5px border-l4、圆角 16（rowCard / PluginCard 都是这一套） */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    Surface(
        color = background ?: Color.Transparent,
        contentColor = palette.labelPrimary,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, palette.borderL4),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** 一条 .5px 的分隔线（dsh 的 border-l2） */
@Composable
private fun DshHairline(color: Color? = null) {
    val palette = LocalDshPalette.current
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(color ?: palette.borderL2))
}

/** 可展开的卡片：只有头部 + 主体，没有保存脚（工作区 / 系统提示词 / 关于用它） */
@Composable
private fun DisclosureCard(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // 底色与同一页的设置行一致（透明）：深色下 bg-layer-3 会比其它卡片亮一档，
    // 最下面这三张卡之前就是因此看着「气泡颜色不一样」
    SettingsCard {
        CardFrame(icon = icon, title = title, description = description, open = open, dirty = false) { open = !open }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                DshHairline()
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 卡片的头部（dsh 的 .header）：图标 + 名称 + 说明 + 未保存标签 + 倒角 */
@Composable
private fun CardFrame(
    icon: ImageVector,
    title: String,
    description: String,
    open: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (open) palette.bgLayer2 else palette.bgLayer3)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = palette.labelSecondary, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.labelPrimary)
            Text(description, fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelTertiary)
        }
        if (dirty) DshTag("未保存")
        Icon(
            imageVector = DshSettingIcons.ChevronDown,
            contentDescription = if (open) "收起设置" else "展开设置",
            tint = palette.labelTertiary,
            modifier = Modifier.size(14.dp).rotate(if (open) 180f else 0f),
        )
    }
}

/** dsh 的 PluginCard：头部（名称 + 说明 + 未保存标签 + 倒角）+ 主体 + 保存脚 */
@Composable
private fun PluginCard(
    icon: ImageVector,
    title: String,
    description: String,
    dirty: Boolean,
    saving: Boolean,
    failed: String?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    /**
     * 脚部「放弃修改」左边多出来的动作（dsh 的 PluginCard 里没有这一格；
     * 网页搜索的「更换」用它，样式与「放弃修改」同一枚 SecondaryButton）。
     */
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    var open by remember { mutableStateOf(false) }
    // dsh：保存成功后自动收起。本地保存是同步的（saving 在同一帧里 true→false），
    // 所以盯的是「脏 → 不脏」这一跳，而不是 saving（dsh 那边 saving 要等一次远端往返）。
    var wasDirty by remember { mutableStateOf(dirty) }
    LaunchedEffect(dirty, failed) {
        when {
            dirty -> wasDirty = true
            wasDirty && failed == null -> {
                wasDirty = false
                open = false
            }
        }
    }
    SettingsCard(background = if (open) palette.bgLayer2 else palette.bgLayer3) {
        CardFrame(icon = icon, title = title, description = description, open = open, dirty = dirty) { open = !open }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                DshHairline()
                content()
                DshHairline()
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (failed != null) {
                        Text(
                            text = failed,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = palette.errorLabel,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    extraAction?.invoke()
                    SecondaryButton("放弃修改", enabled = dirty && !saving) { onDiscard() }
                    PrimaryButton(if (saving) "保存中…" else "保存", enabled = dirty && !saving) { onSave() }
                }
            }
        }
    }
}

private enum class TagTone { Neutral, Quiet, Outline }

/** dsh 的 Tag：圆角 999、padding 1 8、11/17 500；tone 取 neutral / quiet / outline */
@Composable
private fun DshTag(text: String, tone: TagTone = TagTone.Neutral) {
    val palette = LocalDshPalette.current
    val background = when (tone) {
        TagTone.Neutral -> palette.bgModulePlatform
        TagTone.Quiet -> Color.Transparent
        TagTone.Outline -> Color.Transparent
    }
    val border = if (tone == TagTone.Outline) BorderStroke(0.5.dp, palette.borderL4) else null
    val color = when (tone) {
        TagTone.Neutral -> palette.labelSecondary
        TagTone.Quiet -> palette.labelTertiary
        TagTone.Outline -> palette.labelTertiary
    }
    Surface(color = background, contentColor = color, shape = RoundedCornerShape(999.dp), border = border) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            fontSize = 11.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
    }
}

/** dsh 的 .selector：bg-module-platform、高 36、圆角 18、padding 0 14、gap 12 + 倒角 */
@Composable
private fun SelectorPill(label: String, expanded: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) palette.bgLayer3 else palette.bgModulePlatform)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary, maxLines = 1)
        Icon(
            imageVector = DshSettingIcons.ChevronDown,
            contentDescription = if (expanded) "收起选项" else "展开选项",
            tint = palette.labelPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** dsh 的 .credentialDot：8x8 圆点，已配置绿、缺失红 */
@Composable
private fun CredentialDot(configured: Boolean) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (configured) palette.success else palette.errorLabel),
    )
}

/** dsh 的 .input：border .5px border-l4、bg-layer-1、高 32~34、圆角 8、padding 0 12 */
@Composable
private fun DshInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    numeric: Boolean = false,
    secret: Boolean = false,
    invalid: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 34.dp,
) {
    val palette = LocalDshPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 21.sp, color = palette.labelPrimary),
        cursorBrush = SolidColor(palette.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.bgLayer1)
            .border(0.5.dp, if (invalid) palette.errorLabel else palette.borderL4, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = palette.labelDimmed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** dsh 的 ValueField：标签 13/1.5 500 + 输入框 + 一行 hint，字段之间一条 .5px 分隔线 */
@Composable
private fun ValueField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    placeholder: String = "",
    invalid: Boolean = false,
    first: Boolean = false,
) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) DshHairline()
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
            }
            DshInput(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                numeric = numeric,
                invalid = invalid,
            )
            Text(
                text = if (invalid) "请填数字；留空表示使用默认值。" else hint,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (invalid) palette.errorLabel else palette.labelTertiary,
            )
        }
    }
}

/** dsh 的 SecretField：标签 + 状态标签 + 密码框 + hint（值不回显） */
@Composable
private fun SecretField(
    label: String,
    stateLabel: String,
    configured: Boolean,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    first: Boolean = false,
) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) DshHairline()
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
                DshTag(stateLabel, if (configured) TagTone.Neutral else TagTone.Quiet)
            }
            DshInput(value = value, onValueChange = onValueChange, secret = true)
            Text(hint, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
        }
    }
}

/** dsh 的 .save：底色 label-primary、文字 bg-layer-3；禁用降到 40% */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) palette.labelPrimary else palette.labelPrimary.copy(alpha = 0.4f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = palette.bgLayer3, maxLines = 1)
    }
}

/** dsh 的 .secondaryButton / .discard：border .5px border-l3、文字 label-primary；small = 28 高、圆角 14、12/18 */
@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (small) 14.dp else 18.dp)
    Box(
        Modifier
            .height(if (small) 28.dp else 36.dp)
            .clip(shape)
            .background(if (pressed && enabled) palette.hover else Color.Transparent)
            .border(0.5.dp, palette.borderL3, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (small) 12.sp else 14.sp,
            lineHeight = if (small) 18.sp else 22.sp,
            color = if (enabled) palette.labelPrimary else palette.labelCaption,
            maxLines = 1,
        )
    }
}

/** dsh 的 .linkButton：高 28、圆角 14、12/18、三级色 */
@Composable
private fun LinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed && enabled) palette.hover else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (enabled) palette.labelTertiary else palette.labelCaption,
            maxLines = 1,
        )
    }
}

/** dsh 的 .iconButton：28x28、圆角 6、三级色 */
@Composable
private fun IconActionButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** dsh 的 .close：28 圆形按钮 */
@Composable
private fun CircleIconAction(icon: ImageVector, description: String, iconSize: Dp, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.labelPrimary, modifier = Modifier.size(iconSize))
    }
}

/**
 * 卡片里的一行「入口」：行首一枚图标，整行套 dsh 的 secondaryButton 边框
 * （.5px border-l3、圆角 10），右侧一句状态 + 倒角 —— 与卡片里其它内容同一套视觉。
 */
@Composable
private fun BoxedNavRow(icon: ImageVector, label: String, detail: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    // 结构与 SecondaryButton / LinkButton 完全一致（Box + clickable 在 padding 之内）：
    // Row + clickable + heightIn(min=…) 那一版在真机上收不到点击（点它没有任何反应），
    // 换成这一个已知可用的形状。
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(44.dp)
            .clip(shape)
            .background(if (pressed) palette.hover else Color.Transparent)
            .border(0.5.dp, palette.borderL3, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = palette.labelSecondary, modifier = Modifier.size(14.dp))
            Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelPrimary)
            Text(detail, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary, maxLines = 1)
            Icon(
                imageVector = DshIcons.ChevronRight,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
/** 候选清单前的勾选框 */
@Composable
private fun CheckBoxMark(checked: Boolean) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) palette.accent else Color.Transparent)
            .border(1.5.dp, if (checked) palette.accent else palette.labelCaption, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = DshSettingIcons.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
