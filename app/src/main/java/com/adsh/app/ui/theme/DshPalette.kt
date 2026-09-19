package com.adsh.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * dsh 的设计令牌，逐项取自 dsh-client-ui-theme（浅色 / 深色两套）。
 *
 * 关键一条：弹层底色是 --dsw-specific-menu = --dsw-alias-bg-layer-3 = 纯白，
 * 不是 Material 的 surfaceContainerHigh（#EFF0F6，会发灰）。
 */
@Immutable
data class DshPalette(
    /** --dsw-specific-menu */
    val menu: Color,
    /** --dsw-specific-selector（＋ / 📎 的圆形底） */
    val selector: Color,
    /** --dsw-specific-input-major（输入框卡片底） */
    val inputMajor: Color,
    /** --dsw-specific-tip（输入框上方的横窗：任务 / 目标 / 队列卡片的底） */
    val tip: Color,
    /** --dsw-alias-bg-mask-1（对话框背后的遮罩） */
    val mask: Color,
    /** --dsw-alias-label-primary-foreground（主按钮上的文字） */
    val onPrimary: Color,
    /** --dsw-specific-sidebar-fill（抽屉底） */
    val sidebar: Color,
    /** --dsw-specific-sidebar-nav-item-hover（设置左栏 cell 悬停） */
    val navHover: Color,
    /** --dsw-specific-sidebar-nav-item-active（设置左栏 cell 选中） */
    val navActive: Color,
    /** --dsw-alias-bg-layer-1（深色下最底的一层） */
    val bgLayer1: Color,
    /** --dsw-alias-bg-layer-2（dsh 设置面板的底） */
    val bgLayer2: Color,
    /** --dsw-alias-bg-layer-3（插件卡 / 输入底） */
    val bgLayer3: Color,
    /** --dsw-alias-bg-module-platform（选择器药丸 / 主题立方的底） */
    val bgModulePlatform: Color,
    /** --dsw-alias-markdown-code-block（代码块 / 终端 / ioCard 的底） */
    val codeBlock: Color,
    /** --dsw-alias-markdown-code-block-banner（代码块顶部横幅的底） */
    val codeBlockBanner: Color,
    /** --dsw-specific-bubble（用户消息气泡底：浅色 deepseek-50，深色 neutral-bluish-850） */
    val userBubble: Color,
    /** --dsw-alias-label-dimmed */
    val labelDimmed: Color,
    /** --dsw-alias-state-success-primary（凭据已配置的绿点） */
    val success: Color,
    /** --dsw-alias-interactive-bg-hover-danger（删除按钮悬停） */
    val dangerHover: Color,
    /** --dsw-alias-button-elevated-fill（侧栏「新会话」按钮底） */
    val buttonElevated: Color,
    /** --dsw-alias-state-business-primary（dsh 的品牌蓝：选中文件夹等） */
    val business: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    /** --dsw-alias-interactive-bg-hover (#2631480f) */
    val hover: Color,
    val hoverSolid: Color,
    /** --dsw-alias-button-info-fill / --dsw-alias-state-business-primary */
    val accent: Color,
    val accentHover: Color,
    /** ContextMeter 三段色 */
    val system: Color,
    val tools: Color,
    val messages: Color,
    val warnBg: Color,
    val warnLabel: Color,
    val errorLabel: Color,
    /** 代码高亮（dsh 的代码块走 highlight.js 主题，深浅两套；否则深色模式下整块代码发暗看不清） */
    val codeString: Color,
    val codeNumber: Color,
    val codeKeyword: Color,
    val codeType: Color,
)

val LightDshPalette = DshPalette(
    menu = Color(0xFFFFFFFF),
    selector = Color(0xFFF5F6F7),
    inputMajor = Color(0xFFFFFFFF),
    // --dsw-specific-tip = --dsw-static-neutral-bluish-60
    tip = Color(0xFFF5F6F7),
    mask = Color(0x3D000000),
    onPrimary = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF9FAFB),
    navHover = Color(0xFFF1F3F5),
    navActive = Color(0xFFEBEEF2),
    bgLayer1 = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    bgModulePlatform = Color(0xFFF5F6F7),
    // --dsw-alias-markdown-code-block / -banner = --dsw-static-neutral-bluish-50
    codeBlock = Color(0xFFF9FAFB),
    codeBlockBanner = Color(0xFFF9FAFB),
    userBubble = Color(0xFFEDF3FE),
    labelDimmed = Color(0xFFE1E5EE),
    success = Color(0xFF22C55E),
    dangerHover = Color(0x0DEC1313),
    buttonElevated = Color(0xFFFFFFFF),
    business = Color(0xFF4176E6),
    labelPrimary = Color(0xFF0F1115),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    labelCaption = Color(0xFFADB2B8),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    borderL4 = Color(0x29000000),
    hover = Color(0x0F263148),
    hoverSolid = Color(0xFFF1F3F5),
    accent = Color(0xFF4176E6),
    accentHover = Color(0xFF679EFE),
    system = Color(0xFFADB2B8),
    tools = Color(0xFFA78BFA),
    messages = Color(0xFF4D93F8),
    warnBg = Color(0xFFFEF5E7),
    warnLabel = Color(0xFFDD8629),
    errorLabel = Color(0xFFEC1313),
    codeString = Color(0xFF3F9142),
    codeNumber = Color(0xFFB26A00),
    codeKeyword = Color(0xFF7F52FF),
    codeType = Color(0xFF1F6FEB),
)

val DarkDshPalette = DshPalette(
    menu = Color(0xFF232324),
    selector = Color(0xFF2C2C2E),
    inputMajor = Color(0xFF1B1B1C),
    // --dsw-specific-tip = --dsw-static-neutral-bluish-800
    tip = Color(0xFF353638),
    mask = Color(0x80000000),
    onPrimary = Color(0xFF0F1115),
    sidebar = Color(0xFF1B1B1C),
    navHover = Color(0xFF2C2C2E),
    navActive = Color(0xFF43454A),
    bgLayer1 = Color(0xFF232324),
    bgLayer2 = Color(0xFF2C2C2E),
    bgLayer3 = Color(0xFF353638),
    bgModulePlatform = Color(0xFF353638),
    // code-block = neutral-bluish-900 (#1b1b1c)、banner = neutral-bluish-850 (#2c2c2e)
    codeBlock = Color(0xFF1B1B1C),
    codeBlockBanner = Color(0xFF2C2C2E),
    userBubble = Color(0xFF2C2C2E),
    labelDimmed = Color(0xFF43454A),
    success = Color(0xFF22C55E),
    dangerHover = Color(0x26F25A5A),
    buttonElevated = Color(0xFF43454A),
    business = Color(0xFF679EFE),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    borderL1 = Color(0x0AFFFFFF),
    borderL2 = Color(0x1AFFFFFF),
    borderL3 = Color(0x1FFFFFFF),
    borderL4 = Color(0x29FFFFFF),
    hover = Color(0x14FFFFFF),
    hoverSolid = Color(0xFF353638),
    accent = Color(0xFF4176E6),
    accentHover = Color(0xFF5686FE),
    system = Color(0xFF81858C),
    tools = Color(0xFFA78BFA),
    messages = Color(0xFF4D93F8),
    warnBg = Color(0xFF3A2E1B),
    warnLabel = Color(0xFFF7AD31),
    errorLabel = Color(0xFFF25A5A),
    codeString = Color(0xFF7EE787),
    codeNumber = Color(0xFFFFA657),
    codeKeyword = Color(0xFFD2A8FF),
    codeType = Color(0xFF79C0FF),
)

val LocalDshPalette = staticCompositionLocalOf { LightDshPalette }
