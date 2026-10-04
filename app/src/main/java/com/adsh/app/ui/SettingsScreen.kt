package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.BuildConfig
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.launch


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
    val onTheme: (String) -> Unit,
    val onFontSize: (Int) -> Unit,
    val onTranscriptView: (String) -> Unit,
    val onBusyEnter: (String) -> Unit,
    val onPermission: (String) -> Unit,
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
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bgLayer2)
            .statusBarsPadding()
            // 键盘与导航栏：**必须显式让出**（MainActivity 调了 enableEdgeToEdge()，
            // 窗口不再随输入法 resize）。少了这一层，设置页的下半部分（系统提示词附录、
            // 展开卡片里的输入框）会被键盘盖住 —— 用户第 99 轮报的就是这个。
            // 与 ChatScreen 同一套（ime ∪ navigationBars，只取底边）。
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
            ),
    ) {
        SettingsHeader(onClose = onBack)
        SettingsNav(pagerState.currentPage) { index -> scope.launch { pagerState.animateScrollToPage(index) } }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> GeneralSection(bindings = bindings, settings = settings)
                1 -> ModelsSection(settings)
                else -> FeaturesSection(settings = settings)
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
            .padding(start = DshSpacing.Xxl, end = DshSpacing.Section, top = DshSpacing.Page, bottom = DshSpacing.Xl),
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
            .padding(horizontal = DshSpacing.Card),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
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
                    .dshClickable(interactionSource = interaction) { onSelect(index) }
                    .padding(start = DshSpacing.Xxxl, end = DshSpacing.Card),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
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
internal fun SectionColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = DshSpacing.Card, end = DshSpacing.Card, top = DshSpacing.Xxxl, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
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
            Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.labelSecondary,
                modifier = Modifier.padding(top = DshSpacing.Sm).size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier.weight(1f).padding(end = DshSpacing.Xl),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Md),
            ) {
                Text(title, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary)
                Text(description, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
            }
            control()
        }
    }
}

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

        // 以下是这个客户端自己的项（dsh 没有对应插件）：系统提示词附录 / 关于。
        // 「工作区」原来也在这里，但它跟着当前会话走（会话自带工作区），列出来只会误导；
        // 绑定入口留在抽屉的「工作区 +」里。
        // 「边缘防误触」第 99 轮按用户要求撤掉选项：宽度固定 12dp（见 AppRoot）——
        // 它本来也只有「关闭 / 窄 / 中 / 宽」四档，用户口径是「别让我配，直接默认」。
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
        Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(activeIcon, contentDescription = null, tint = palette.labelSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("外观", fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
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
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(vertical = DshSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Md),
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
            Row(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.bgModulePlatform)
                    .padding(start = DshSpacing.Card, end = DshSpacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
            ) {
                Text(
                    text = value.toString(),
                    modifier = Modifier.widthIn(min = 18.dp),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    color = palette.labelPrimary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Xs)) {
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
            .dshClickable(enabled = enabled, interactionSource = dshInteraction(), onClick = onClick),
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
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
