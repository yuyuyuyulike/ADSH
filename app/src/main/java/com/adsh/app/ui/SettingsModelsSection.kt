package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.data.ApiProtocol
import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.data.ProviderCatalog
import com.adsh.app.core.data.ModelDef
import com.adsh.app.core.data.ProviderDef
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.ProviderConfig
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置页「模型」区（从 [SettingsScreen] 切出来的一簇，R52）。
 *
 * 为什么切：`SettingsScreen.kt` 曾是全库最大的文件（2543 行、40 多个 composable），
 * 里面挤着四块互不引用的东西：外壳 + 通用设置 / 模型区 / 功能区 / 共用小件。切缝口径与
 * R13（ChatScreen 六刀）、R16（抽屉整簇搬 Drawer.kt）一致：**整簇搬走、正文一字不改**，
 * 只把顶层声明的 `private` 改成 `internal`（同包跨文件的最低可见性）。
 *
 * 这一簇里还留着 `MODE_CATALOG` / `MODE_CUSTOM` / 两句 Hint 常量 —— 它们只被本文件用到，
 * 所以仍是 `private const val`（文件私有）。
 */

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
 *  - 底部**一个**「添加模型提供商」按钮打开添加卡片（dsh 的 addCard）：顶部的「添加方式」
 *    分段控件在「第三方模型提供商」（目录 route + 提供方下拉）与「自定义模型 API」
 *    （Provider ID / 显示名称 / API 协议 / API 地址都自己填）之间切换；
 *  - 删除提供方有二次确认（dsh 的 deleteTitle / deleteDescription[WithCredential]）。
 *
 * 文案逐字取自 dsh 的 settings.models 中文字典。
 */
@Composable
internal fun ModelsSection(settings: SettingsStore) {
    val palette = LocalDshPalette.current
    var providers by remember { mutableStateOf(settings.providers) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ProviderDef?>(null) }
    /**
     * dsh 的 adding / declaring 合并成**一个入口 + 卡片里的「添加方式」开关**（第 117 轮，
     * 用户点名把两个按钮与两张卡片融合）：null = 没在添加，否则就是 dsh 的 AddMode
     * （[MODE_CATALOG] / [MODE_CUSTOM]）。
     */
    var addMode by remember { mutableStateOf<String?>(null) }
    var presetId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun persist(next: List<ProviderDef>) {
        providers = next
        settings.providers = next
    }

    /** 清单转移的结果：两个选中值只在该写的时候写（见 [ProviderSelection] 的说明） */
    fun applySelection(selection: ProviderSelection) {
        persist(selection.providers)
        selection.providerId?.let { settings.providerId = it }
        selection.model?.let { settings.model = it }
    }

    SectionColumn {
        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Md)) {
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
                    // 替换同 id 的那一条 + 当前模型被删掉时的兜底，都在 providersSaved 里
                    applySelection(providersSaved(providers, updated, settings.providerId, settings.model))
                    editingId = null
                    notice = "已保存 " + updated.displayName + "。"
                },
            )
        }

        // dsh 的 .addActions + addCard：**一个**贯穿整行的虚线按钮（「添加模型提供商」），
        // 点下去之后按钮**被卡片替换** —— 卡片里再用「添加方式」开关分「第三方模型提供商 /
        // 自定义模型 API」（第 117 轮融合；dsh 现在也是这一个入口 + 一张卡）。
        val addedIds = providers.map { it.id }.toSet()
        val addable = ProviderCatalog.presets.filterNot { it.id in addedIds }
        val saved: (ProviderDef) -> Unit = { created ->
            applySelection(providersAdded(providers, created))
            addMode = null
            editingId = created.id
            notice = "已保存 " + created.displayName + "。"
        }
        val currentMode = addMode
        if (currentMode == null) {
            AddProviderButton("添加模型提供商", modifier = Modifier.fillMaxWidth()) {
                presetId = addable.firstOrNull()?.id
                // 目录里一条都不剩时直接落到自定义（dsh 的 initial = catalogEnabled ? catalog : custom）
                addMode = if (addable.isEmpty()) MODE_CUSTOM else MODE_CATALOG
            }
        } else {
            CustomProviderEditor(
                taken = providers.map { it.id },
                preset = if (currentMode == MODE_CATALOG) addable.firstOrNull { it.id == presetId } else null,
                presetChoices = if (currentMode == MODE_CATALOG) addable else emptyList(),
                onPresetChange = { presetId = it },
                mode = currentMode,
                onModeChange = { next ->
                    addMode = next
                    presetId = addable.firstOrNull()?.id
                },
                onCancel = { addMode = null },
                onCreate = saved,
            )
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
                // 删掉当前提供方时落到剩下的第一条（一条不剩就写空串），在 providersDeleted 里
                applySelection(providersDeleted(providers, target.id, settings.providerId))
                deleting = null
            },
        )
    }
}

/** dsh 的 provider rowCard：卡片头 + 展开后的编辑器 */
@Composable
internal fun ProviderCard(
    provider: ProviderDef,
    editing: Boolean,
    isCurrent: Boolean,
    onToggleEdit: () -> Unit,
    onDelete: () -> Unit,
    onSave: (ProviderDef) -> Unit,
) {
    val palette = LocalDshPalette.current
    val scope = rememberCoroutineScope()
    /**
     * 编辑中的草稿：密钥不回显（dsh 的 keyStored：已配置——输入新值可替换）。
     *
     * **key 里必须带 [editing]**：这张卡片在收起（editing == false）时**没有离开组合**
     * （只是里面的 if (editing) 分支不画了），所以只按 provider 记忆的话，「取消」丢不掉草稿 ——
     * 用户点「添加模型」添了一行空 ID、再点取消、再点开，那一行还在（用户实测报的）。
     * dsh 里这件事由**组件边界**保证：编辑器是独立的 ProviderEditor（client.js:1509），
     * 草稿是它自己的 useState（1511），收起即卸载、状态跟着丢。这里用 [editing] 当 key
     * 达到同一个效果：收起即回到已存档案，再点开重新从已存档案起一份草稿。
     */
    var key by remember(provider, editing) { mutableStateOf("") }
    var baseUrl by remember(provider, editing) { mutableStateOf(provider.baseUrl) }
    var models by remember(provider, editing) { mutableStateOf(provider.models) }
    var fetching by remember(provider, editing) { mutableStateOf(false) }
    var saveError by remember(provider, editing) { mutableStateOf<String?>(null) }

    val defaultModels = BuiltInProviders.DEEPSEEK_MODELS
    val overridden = providerOverridden(provider, models, defaultModels)
    val configured = provider.apiKey.isNotBlank() || key.isNotBlank()

    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl)) {
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
                // dsh 的 `row.removable`：**随包内置的提供方右侧没有删除按钮**（ModelsSection 只在
                // removable 为真时渲染 dangerButton）。dsh 的判据是「用户设置里有、base 里没有」——
                // base 就是随包发的那一个，ADSH 里等于 [BuiltInProviders.deepseek]（用户第 99 轮点名：
                // 「取消掉设置-模型里的 deepseek 供应方右侧的删除按钮」）。目录里加进来的（openai 等）
                // 仍然可删，与 dsh 一致。
                if (provider.id != BuiltInProviders.DEEPSEEK_ID) {
                    DangerSmallButton("删除") { onDelete() }
                }
            }

            if (editing) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.bgLayer3)
                        .padding(horizontal = DshSpacing.Card, vertical = DshSpacing.Section),
                    verticalArrangement = Arrangement.spacedBy(DshSpacing.Section),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
                        Text("编辑 " + provider.displayName, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
                        Text(provider.id, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
                    }

                    // API 密钥
                    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
                        Text("API 密钥", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
                        DshInput(
                            value = key,
                            onValueChange = { key = it },
                            placeholder = if (provider.apiKey.isNotBlank()) "已配置——输入新值可替换" else "输入 API 密钥",
                            secret = true,
                        )
                    }

                    // API 地址
                    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
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

                    // 模型目录里第一处不合法的行（ID 空 / 重复）：照 dsh 的画法 ——
                    // 三级色的 advancedHint 说明是哪一行，同时把保存键变暗
                    val modelProblem = firstInvalidModel(models)
                    if (modelProblem != null) {
                        Text(
                            text = modelProblemText(modelProblem),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = palette.labelTertiary,
                        )
                    }
                    saveError?.let {
                        Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = palette.errorLabel)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
                        Spacer(Modifier.weight(1f))
                        SecondaryButton("取消") { onToggleEdit() }
                        // dsh 的 EditorFooter：modelFailure 不为 undefined 时 submitDisabled（变暗、点了没反应）
                        PrimaryButton("保存", enabled = modelProblem == null) {
                            // 规范化与校验都在 SettingsModels.kt 的 providerSaved 里
                            when (val saved = providerSaved(provider, key, baseUrl, models)) {
                                is ProviderSave.Failed -> saveError = saved.message
                                is ProviderSave.Ok -> {
                                    saveError = null
                                    key = ""
                                    onSave(saved.provider)
                                }
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
internal fun ModelCatalogEditor(
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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DshSpacing.Xxl)) {
        DshHairline()
        Column(Modifier.fillMaxWidth().padding(top = DshSpacing.Xxxl), verticalArrangement = Arrangement.spacedBy(DshSpacing.Xxl)) {
            // dsh 的 .modelListHead 是「标题 + 右侧两个 linkButton」一行；手机宽度放不下，
            // 挤在一起会把「已自定义模型目录」折成两行，所以标题一行、按钮另起一行右对齐。
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DshSpacing.Xs)) {
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
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
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
                        .padding(DshSpacing.Xxxl),
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
                        .padding(DshSpacing.Lg),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
                    ) {
                        Box(Modifier.weight(1.4f)) {
                            DshInput(
                                value = model.id,
                                onValueChange = { value -> onModels(modelEdited(models, index) { it.copy(id = value) }) },
                                placeholder = "模型 ID",
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            DshInput(
                                value = model.name.orEmpty(),
                                onValueChange = { value ->
                                    onModels(modelEdited(models, index) { it.copy(name = value.ifEmpty { null }) })
                                },
                                placeholder = "留空时使用模型 ID",
                            )
                        }
                        IconActionButton(
                            icon = if (index in expanded) DshSettingIcons.ChevronDown else DshIcons.ChevronRight,
                            description = "容量",
                            tint = palette.labelTertiary,
                        ) {
                            expanded = toggleExpanded(expanded, index)
                        }
                        IconActionButton(
                            icon = DshSidebarIcons.Trash,
                            description = "删除模型",
                            tint = palette.errorLabel,
                        ) {
                            onModels(modelsRemoved(models, index))
                            expanded = reindexOnRemove(expanded, index)
                        }
                    }
                    if (index in expanded) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = DshSpacing.Md, end = DshSpacing.Md, top = DshSpacing.Xl, bottom = DshSpacing.Xs),
                            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                        ) {
                            CapacityField(
                                label = "上下文窗口",
                                value = model.contextWindow,
                                modifier = Modifier.weight(1f),
                            ) { capacity ->
                                onModels(modelEdited(models, index) { it.copy(contextWindow = capacity) })
                            }
                            CapacityField(
                                // dsh 的 ModelListEditor 用 modelMaxTokens「最大输出 token」，
                                // DeepSeek 那个编辑器用 maxTokens「最大输出 token 数」
                                label = if (deepSeek) "最大输出 token 数" else "最大输出 token",
                                value = model.maxTokens,
                                modifier = Modifier.weight(1f),
                            ) { capacity ->
                                onModels(modelEdited(models, index) { it.copy(maxTokens = capacity) })
                            }
                        }
                        // 可识别图片（dsh 的 inputModalities 含 image；这条开关是 ADSH 的扩展 ——
                        // 目录里没收录的模型默认**开**：先按能收图发，收不了会在请求时报错，
                        // 报错文案直接指路回来取消勾选。默认值与请求装配读同一个函数）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = DshSpacing.Md, end = DshSpacing.Md, top = DshSpacing.Xxl),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                        ) {
                            DshCheckbox(
                                checked = model.imageInput
                                    ?: com.adsh.app.core.data.SettingsStore.defaultImageInput(model.id),
                                onCheckedChange = { on ->
                                    onModels(
                                        modelEdited(models, index) { it.copy(imageInput = on) },
                                    )
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text("可识别图片", fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelPrimary)
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
internal fun AddModelButton(onClick: () -> Unit) {
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
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xxl),
        contentAlignment = Alignment.Center,
    ) {
        Text("添加模型", fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelPrimary)
    }
}

/** 容量字段（dsh 的 modelField）：留空用提供方默认值，支持 128K / 1M 这样的写法 */
@Composable
internal fun CapacityField(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    onValue: (Long) -> Unit,
) {
    val palette = LocalDshPalette.current
    var text by remember(value) { mutableStateOf(if (value > 0) formatCapacity(value) else "") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
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

/** dsh 的 .dangerButton（行内小号）：28 高、圆角 14、12/18、error 色 */
@Composable
internal fun DangerSmallButton(text: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xxl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = palette.errorLabel)
    }
}

/** dsh 的 fetchDialog：选择要添加的模型（搜索 + 全选/取消全选 + 勾选清单） */
@Composable
internal fun FetchModelsDialog(
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
                val next = fetchCandidates(ids, existing)
                candidates = next
                // 照 dsh：picked 的初值就是「目录里还没有的那些」= 这里的全部候选（默认全选）
                selected = next.toSet()
            },
            onFailure = { error = it.message ?: it::class.java.simpleName },
        )
        loading = false
    }

    val filtered = remember(candidates, query) { candidateFilter(candidates, query) }

    val visible = !loading && error == null && candidates.isNotEmpty()
    val allPicked = allVisiblePicked(selected, filtered)

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
                    // 按候选（服务端）顺序补档案，与弹窗里看到的顺序一致
                    onAdopt(adoptedModels(candidates, selected))
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
                    Modifier.fillMaxWidth().padding(bottom = DshSpacing.Lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                ) {
                    Box(Modifier.weight(1f)) {
                        DshInput(value = query, onValueChange = { query = it }, placeholder = "搜索模型")
                    }
                    DshButton(
                        text = if (allPicked) "取消全选" else "全选",
                        onClick = { selected = toggleAllVisible(selected, filtered) },
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
                                .dshClickable(interactionSource = dshInteraction()) {
                                    selected = if (checked) selected - id else selected + id
                                }
                                .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
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
 * dsh 的 .addButton：**一个**贯穿整行的虚线按钮（.addActions 里只有它一个，flex:1 1 0），
 * 44 高、圆角 lg、内容居中（图标 + 文案）。点下去按钮被「添加卡片」替换（见 ModelsSection）。
 *
 * 虚线的粗细与颜色按用户第 117 轮的要求调过：dsh 的 0.5px 在手机的高密度屏上只剩不到一个
 * 物理像素，加上 border-l3 只有 12% 不透明度，整圈框几乎看不见 —— 现在按 1dp 画、用
 * border-l4（面板上真正看得见的那一级边框，与提供方卡片同一档）。
 */
@Composable
internal fun AddProviderButton(
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
            .dshClickable(interactionSource = interaction, enabled = enabled, onClick = onClick)
            // dsh 的 border 是 dashed：Compose 的 Modifier.border 画不了虚线，这里手画一圈。
            // **必须画在 Row 自己身上**：之前是一个 Canvas(Modifier.fillMaxSize()) 子项 ——
            // 那时外层是 Box（子项叠着放）没问题，换成 Row（子项横着排）之后 Canvas 会先把
            // 整行宽度吃掉，图标和文字被挤成 0 宽，于是只剩一个隐约的空方框。
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                drawRoundRect(
                    color = if (enabled) palette.borderL4 else palette.borderL2,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                )
            }
            .padding(horizontal = DshSpacing.Xxxl),
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

/** dsh 的两种添加方式（AddMode）与各自的一句话说明（locales 的 addCatalog / addCustom / *Hint） */
private const val MODE_CATALOG = "catalog"
private const val MODE_CUSTOM = "custom"
private const val CATALOG_HINT = "从内置目录中选择 OpenAI、Anthropic、Kimi 等提供商，填入其 API 密钥即可使用。"
private const val CUSTOM_HINT = "连接中转站、自部署服务或其他兼容 OpenAI / Anthropic 协议的接口，需填写 API 地址、协议和模型。"

/**
 * dsh 的 SegmentedControl（.control 轨道 + .indicator 浮起的胶囊 + .tab）：
 * 轨道就是悬停底色（读起来是「一块地方」而不是第二个按钮），选中的那一段是一枚 bg-layer-1 的
 * 胶囊、文字转一级色，标签 13/20 500、每段 28 高、左右 16 内边距。
 *
 * 它是「添加卡片」顶部的添加方式开关（dsh 的 addModes）：一个入口、两种方式。
 */
@Composable
internal fun AddProviderModeSwitch(mode: String, onModeChange: (String) -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.hover)
            .padding(DshSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xs),
    ) {
        listOf(MODE_CATALOG to "第三方模型提供商", MODE_CUSTOM to "自定义模型 API").forEach { (value, label) ->
            val selected = value == mode
            Box(
                Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) palette.bgLayer1 else Color.Transparent)
                    .dshClickable(interactionSource = dshInteraction()) { onModeChange(value) }
                    .padding(horizontal = DshSpacing.Card),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) palette.labelPrimary else palette.labelSecondary,
                )
            }
        }
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
internal fun CustomProviderEditor(
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
    /** dsh 的 AddMode：catalog = 从供应方目录里挑，custom = 手写一个（卡片顶部那句说明由它决定） */
    mode: String = MODE_CUSTOM,
    onModeChange: (String) -> Unit = {},
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

    // 就绪算式与卡片底部那句提示都在 SettingsModels.kt 的 providerFormState 里
    // （含「目录 route 允许先不写模型」「字段自己的错误不在这里重复说」两条理由）
    val draft = ProviderDraft(route, displayName, baseUrl, api, key, models)
    val form = providerFormState(draft, taken, catalogRoute)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bgModulePlatform)
            .padding(horizontal = DshSpacing.Card, vertical = DshSpacing.Section),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Section),
    ) {
        // dsh 的 .addModes：分段控件 + 当前方式的一句话说明（.advancedHint，12/18 三级色）。
        // 这里是两个按钮 / 两张卡片融合之后的那张「添加卡片」的抬头（第 117 轮，用户点名）。
        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
            AddProviderModeSwitch(mode, onModeChange)
            Text(
                text = if (mode == MODE_CATALOG) CATALOG_HINT else CUSTOM_HINT,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
            )
        }

        // dsh 的 addCard 顶部就是这一行「提供方」下拉（原生 select，选项 = 目录里还没配置过的 route）
        if (presetChoices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
                Text("提供方", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
                Box {
                    SelectorPill(preset?.displayName ?: "选择提供方", providerOpen) { providerOpen = !providerOpen }
                    if (providerOpen) {
                        DshPopup(onDismiss = { providerOpen = false }, alignStart = true, below = true) {
                            DshMenuCard(Modifier.width(260.dp)) {
                                // **滚动必须发生在卡片内部**（第 99 轮）：把这个 Modifier 交给
                                // DshMenuCard 自己，Surface 的 shape/background 就挂在滚动节点**里面**，
                                // 一滑背景与圆角跟着滚走 —— 用户看到的是「小卡片上方消失了一点、圆角也没了」。
                                // 与 Composer 的模型菜单同一个写法。
                                Column(
                                    Modifier
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
        }

        if (!catalogRoute) Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
            Text("Provider ID", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = route,
                onValueChange = { route = it },
                placeholder = "acme-gateway",
                invalid = form.routeInvalid || form.routeTaken,
            )
            Text(
                text = when {
                    form.routeInvalid -> "需以小写字母开头，之后可用小写字母、数字和短横线。"
                    form.routeTaken -> "已有提供方使用了这个 ID。"
                    else -> "以小写字母开头的标识，在请求中唯一标识该提供方，并用于派生凭据名。"
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (form.routeInvalid || form.routeTaken) palette.errorLabel else palette.labelTertiary,
            )
        }

        if (!catalogRoute) Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
            Text("显示名称", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = route.ifEmpty { "显示名称" },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
            Text("API 地址", fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = palette.labelSecondary)
            DshInput(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = "https://gateway.example/v1",
                invalid = form.baseInvalid,
            )
            if (form.baseInvalid) {
                Text("请输入有效的 HTTP 或 HTTPS 地址。", fontSize = 12.sp, lineHeight = 18.sp, color = palette.errorLabel)
            }
        }

        // 目录 route 的协议由它自己的目录条目定死（dsh 的 ownsIdentity 只对手写 route 开）
        if (!catalogRoute) Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
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

        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
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
            fetchEnabled = form.baseUrl.isNotEmpty(),
        )

        form.hint?.let {
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl, Alignment.End)) {
            SecondaryButton("取消") { onCancel() }
            PrimaryButton("创建提供方", enabled = form.ready) {
                // 这一条的规范化（显示名兜底 / trim / 丢掉空模型行 / declared 标签）在
                // SettingsModels.kt 的 providerCreated 里
                onCreate(providerCreated(draft, catalogRoute))
            }
        }
    }

    if (fetching) {
        FetchModelsDialog(
            baseUrl = form.baseUrl,
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
internal fun DeleteProviderDialog(
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

