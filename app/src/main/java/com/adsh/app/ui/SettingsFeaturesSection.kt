package com.adsh.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.R
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 设置页「功能」区（dsh 的 settings.plugins；从 [SettingsScreen] 切出来的一簇，R54）。
 *
 * 三张插件卡（终端 / 智能体循环 / 网页搜索）+ 换搜索引擎的子窗口 + 两个品牌色常量。
 * 这里的东西只被这一簇自己用；小件（SettingsCard / PluginCard / ValueField / SecretField…）
 * 来自 [SettingsParts.kt]，外壳的 [SectionColumn] 来自 [SettingsScreen]。
 *
 * 切缝口径见 [SettingsModelsSection] 的说明（整簇搬走、正文一字不改、private → internal）。
 */

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
internal fun FeaturesSection(settings: SettingsStore) {
    val palette = LocalDshPalette.current
    SectionColumn {
        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Md)) {
            Text("功能", fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = palette.labelPrimary)
            Text(
                text = "配置这个客户端已装好的插件：终端与网页搜索。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelTertiary,
            )
        }

        TerminalPlugin(settings)
        AgentLoopPlugin(settings)
        WebSearchPlugin(settings)
        // 权限卡（用户口径：adsh 所有文件 / 电池优化 / 悬浮窗 / 通知 的快捷入口）
        PermissionsCard()
    }
}

/**
 * 「终端」插件卡（dsh 的 BashCard）：shell.timeoutMs / shell.maxTimeoutMs / shell.maxOutputBytes。
 *
 * 三个输入框共用一套「脏 / 校验 / 保存 / 放弃」状态，所以状态就住在这张卡里 ——
 * 与别的卡没有交集，抽出来之后每一块都能单独真机核对。
 */
@Composable
internal fun TerminalPlugin(settings: SettingsStore) {
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
    // 终端会话那一块第 117 轮整个搬到了抽屉（dsh 那里它就是侧栏的一个面板）：
    // 入口、「Termux 前缀位于 …」以及**安装失败的报错**都在那边 —— 报错直接顶替抽屉里
    // 那一行的「终端会话」几个字（见 AppRoot 的 DrawerPanelRow），设置页不再留终端的信息。
    }
}

/**
 * 「Agent 循环」插件卡（dsh 的 AgentLoopCard）：agent-loop.maxParallelToolCalls，默认 10、最小 1。
 */
@Composable
internal fun AgentLoopPlugin(settings: SettingsStore) {
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
}

/**
 * 「网页搜索」插件卡（dsh 的 WebSearchCard：apiKey / baseURL / maxUses）+ 搜索引擎可换。
 * 含「更换」子窗口的开关与它自己的待保存状态。
 */
@Composable
internal fun WebSearchPlugin(settings: SettingsStore) {
    // backendId 是**待保存**的后端（dsh 的 ctx.web 搜索提供方注册表里内置 deepseek-official 或 exa）：
    // 在「更换」里选了之后卡片整页换成新后端的那一套字段，
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
    // Exa / Tavily / 秘塔的 /search 一条 query 一次请求，所以「单次搜索上限」在那边
    // 的含义是「取多少条候选」（Exa 的 numResults、Tavily 的 max_results、秘塔的 size）
    val perQuery = SettingsStore.isCandidateBackend(backendId)
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
            stateLabel = when {
                webKey.isNotBlank() -> "已配置密钥。"
                // 第 103 轮：DeepSeek 后端的密钥跟提供方走（dsh 的搜索提供方复用会话凭据）
                settings.webSearchKeyFollowsProvider(backendId) -> "已跟随 DeepSeek 提供方的密钥。"
                webConfigured -> "已配置密钥。"
                else -> "未配置密钥；配置之前搜索不可用。"
            },
            configured = webConfigured,
            hint = if (settings.webSearchKeyFollowsProvider(backendId)) {
                "只存在本机。现在是 DeepSeek 提供方那把密钥；在这里填一把就改用这一把。"
            } else {
                "只存在本机。留空表示保持当前密钥。"
            },
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
            label = if (perQuery) "一次搜索取多少条" else "单次请求最多搜索次数",
            hint = if (perQuery) {
                "每条 query 向 " + backend.name + " 取多少条候选（默认 10）；" +
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


/**
 * 「更换搜索引擎」子窗口（dsh 里没有这一层 UI：dsh 的搜索提供方由配置文件的 provider id
 * 决定，这里把它做成设置页里的选择器）。列出的就是 ctx.web 里装着的两个提供方；
 * 选中只改卡片正在显示的那一套字段，真正切换发生在「保存」。
 *
 * 第 99 轮按用户口径改形状：**去掉提供方名字下面那句讲解**、每个提供方一枚图标、每一项套上
 * 与设置页卡片同款的方框（[SettingsCard]：.5px border-l4 + 圆角 16），选中态用底色 + 对勾表达。
 */
@Composable
internal fun WebSearchBackendDialog(
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
        // 一行一个引擎：图标 + 名字（+「当前生效」标签）/ 最右边是「卡片正在显示的那一个」的对勾。
        // 对勾位置固定占位（没选中的用透明图标），切来切去不会整行抖动。
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
            SettingsStore.WEB_SEARCH_BACKENDS.forEach { backend ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val picked = backend.id == selected
                SettingsCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .background(
                                when {
                                    pressed -> palette.hover
                                    picked -> palette.bgLayer3
                                    else -> Color.Transparent
                                },
                            )
                            .dshClickable(interactionSource = interaction) { onPick(backend.id) }
                            .padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxl),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
                    ) {
                        WebBackendMark(backend.id)
                        Text(
                            text = backend.name,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.labelPrimary,
                        )
                        if (backend.id == active) DshTag(text = "当前生效", tone = TagTone.Outline)
                        Spacer(Modifier.weight(1f))
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
}

/**
 * 搜索后端的**品牌标识**：
 *  - DeepSeek：官方鲸鱼 mark（`ic_dsh_whale`，路径取自 dsh 前端自身的 FISH_LOGO_PATH），
 *    用 DeepSeek 的品牌蓝；
 *  - Exa：官方 logomark（`ic_exa_mark`，取自 exa.ai 的 wordmark SVG 里那条沙漏形 path），
 *    用 Exa 品牌页 Figure 4 的 Exa blue；
 *  - Tavily（第 103 轮加的）：官方 brand mark 的**符号部分**（`ic_tavily_mark`：三个箭头，
 *    取自 tavily.com/logos/tavily-mark-black.svg 的后三条子路径 —— 那条 path 的第一条子路径是
 *    「应用图块」的实心底，会把 18dp 的格子填成一个黑方块；用户第 104 轮点名「图标不对，我记得
 *    不是这样的」）。官方指引是「浅色底用黑、深色底用米白」，所以这一个**跟着主题走**，
 *    与另外几个固定品牌色不同；
 *  - 秘塔搜索（第 103 轮加的）：官方应用图标（`ic_metaso_mark`，metaso.cn 的 apple-touch-icon），
 *    本身就是蓝色圆角方块 + 白色标记的**彩色**图形，所以不 tint。
 */
@Composable
internal fun WebBackendMark(id: String) {
    val palette = LocalDshPalette.current
    when (id) {
        SettingsStore.WEB_SEARCH_PROVIDER_TAVILY -> Image(
            painter = painterResource(R.drawable.ic_tavily_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(palette.labelPrimary),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(18.dp),
        )
        SettingsStore.WEB_SEARCH_PROVIDER_METASO -> Image(
            painter = painterResource(R.drawable.ic_metaso_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(18.dp),
        )
        else -> {
            val exa = id == SettingsStore.WEB_SEARCH_PROVIDER_EXA
            Image(
                painter = painterResource(if (exa) R.drawable.ic_exa_mark else R.drawable.ic_dsh_whale),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (exa) ExaBrandBlue else DeepseekBlue500),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Exa 的品牌蓝（exa.ai/brand 的 Figure 4：Exa blue #1840ED） */
internal val ExaBrandBlue = Color(0xFF1840ED)
