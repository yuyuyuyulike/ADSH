package com.adsh.app.ui

import com.adsh.app.core.data.ApiProtocol
import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.data.ModelDef
import com.adsh.app.core.data.ProviderDef

/**
 * 设置页「模型」区的**纯逻辑**（本模块熵减的落脚点；原先是 [SettingsScreen] 里零测试的私有函数与局部量）。
 *
 * 为什么单独成文件：`SettingsScreen.kt` 有 2500+ 行、40 多个 composable、**0 个单测** ——
 * 里面混着不少「跟界面无关、只认数据」的判定（容量写法、添加卡片的就绪条件、提供方清单的增删改、
 * 服务端模型清单的过滤与采纳、模型行的改名/删行）。这些判定用 Compose 测不了（离线环境没有
 * Robolectric），但它们本身不需要 Compose：搬到这里就能在 JVM 单测里逐条钉死。
 *
 * 分层口径与 [com.adsh.app.ui.paletteViewOf] / [turnOpened] 一致：**无状态纯函数**，
 * `remember` / `mutableStateOf` / `settings.xxx = ` 都留在调用点；
 * 本文件里没有 Android 依赖（不调 Log，所以能在纯 JVM 单测里跑）。
 */

/** 「至少一个模型」这句文案两处都用（添加卡片底部的提示 / 编辑卡片保存时的报错），只留一份 */
internal const val PROVIDER_NEEDS_MODELS = "自定义提供方至少需要一个模型。"

// ------------------------------------------------------------------ 容量字段

/**
 * dsh 的 formatCapacity：1024 的整数倍写成 K / M。
 *
 * 容量字段显示的起点（`CapacityField`）：`value > 0` 才用这个写法，0（= 用提供方默认值）
 * 显示成空串，所以这里 `value <= 0 -> ""` 是**给调用方的兜底**，不是死分支。
 */
internal fun formatCapacity(value: Long): String = when {
    value <= 0 -> ""
    value % (1024L * 1024L) == 0L -> (value / (1024L * 1024L)).toString() + "M"
    value % 1024L == 0L -> (value / 1024L).toString() + "K"
    else -> value.toString()
}

/**
 * dsh 的 parseCapacity：纯数字或带 K / M 后缀。
 *
 * 返回值有三态，调用点 `parseCapacity(typed)?.let { onValue(it) }` 靠它们分工：
 *  - `null` = 解析不了（`1.5K` / `12x` / 只有符号）→ **不改**已存的值（正在打字时的中间态）；
 *  - `0` = 空串或 `<= 0` → 存成 0，也就是「用提供方默认值」；
 *  - 其余 = 具体容量（K = 1024、M = 1024×1024，大小写都认）。
 *
 * 边界（现状，本轮不改行为）：超过 Long 的写法（如 `9999999999999M`）会**静默回绕**成负数，
 * 于是字段显示回空串 —— 容量字段不是安全边界，要加保护得单独一轮（有测试钉住当前行为）。
 */
internal fun parseCapacity(text: String): Long? {
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

// ------------------------------------------------------------------ 添加提供方卡片

/** 添加卡片（`CustomProviderEditor`）的字段草稿：卡片里那六个输入框各自一个字段 */
internal data class ProviderDraft(
    val route: String = "",
    val displayName: String = "",
    val baseUrl: String = "",
    val api: String = ApiProtocol.OPENAI_COMPLETIONS,
    val apiKey: String = "",
    val models: List<ModelDef> = emptyList(),
)

/**
 * 添加卡片的**就绪算式**与那唯一一句提示（原先是一坨局部量 + 一个 `when` 长在 composable 里）。
 *
 * 规则（dsh 的 addCard / ready / advancedHint / customNeedsModels）：
 *  - route 只要写了就得合法（[BuiltInProviders.validProviderId]）且没被别的提供方占用；
 *  - 地址写了就必须是 http(s)，判的是 **trim 之后**的：所以 `" https://x/v1 "` 算合法，
 *    而「只有空格」的地址 `baseInvalid` 为真 —— 那是「写错了」，不是「还没写」；
 *  - [catalogRoute]（从供应方目录里挑的那一条）**允许先不写模型**：像 OpenRouter / OhMyGPT
 *    这种聚合站的模型 id 全是账号侧决定的，目录里给不出可靠初值，之后可以点「获取可用模型」拉；
 *    手写的自定义提供方至少要一个模型（ID 全是空白的项不算数）；
 *  - [hint] 只在「够不着就绪、但用户明显已经在填」时说一句，且**地址优先于模型**；
 *    字段自己的错误（route / 地址）由字段那一行显示，这里返回 null 不重复说。
 */
internal data class ProviderFormState(
    val routeInvalid: Boolean,
    val routeTaken: Boolean,
    /** 去掉前后空格的地址 —— 创建时用的就是它，不是输入框里的原文 */
    val baseUrl: String,
    val baseInvalid: Boolean,
    val ready: Boolean,
    /** 卡片底部那句提示；null = 不说 */
    val hint: String?,
)

internal fun providerFormState(
    draft: ProviderDraft,
    taken: List<String>,
    catalogRoute: Boolean,
): ProviderFormState {
    val routeInvalid = draft.route.isNotEmpty() && !BuiltInProviders.validProviderId(draft.route)
    val routeTaken = draft.route.isNotEmpty() && draft.route in taken
    val baseUrl = draft.baseUrl.trim()
    val baseInvalid = draft.baseUrl.isNotEmpty() &&
        !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
    val invalid = firstInvalidModel(draft.models)
    // 空目录：目录 route 允许（ADSH 的既有偏差，见本文件头上那段说明），手写的要有至少一行；
    // 有行但行不合法（ID 空 / 重复）**一律不放行** —— 与 dsh 的 ready 同口径
    // （dsh：models.length > 0 && modelFailure === void 0）。
    val modelsOk = if (draft.models.isEmpty()) catalogRoute else invalid == null
    val ready = draft.route.isNotEmpty() && !routeInvalid && !routeTaken &&
        baseUrl.isNotEmpty() && !baseInvalid && modelsOk
    // 提示的优先级与 dsh 的 hint 逐条对齐：地址 → 不合法的行（说清是第几行）→ 还没有模型
    val hint = when {
        ready || draft.route.isEmpty() || routeInvalid || routeTaken || baseInvalid -> null
        baseUrl.isEmpty() -> "自定义提供方需要填写 API 地址。"
        invalid != null -> modelProblemText(invalid)
        else -> PROVIDER_NEEDS_MODELS
    }
    return ProviderFormState(routeInvalid, routeTaken, baseUrl, baseInvalid, ready, hint)
}

/**
 * 「创建提供方」按钮真正写进设置的那一条（dsh 的 addCard 的 onCreate）。
 *
 * 三个规范化动作与 dsh 一致：显示名称留空就用 route；地址与密钥去掉前后空格；
 * 目录里没填完的模型行（ID 全空白）被丢掉 —— 第二道防线：保存键在 [firstInvalidModel]
 * 不为 null 时就已经是暗的（dsh 的 ready 同样要求 `modelFailure === void 0`），
 * 这里兜的是「不从按钮进来的调用」。
 * `custom` 是 dsh 的 declared：**目录里没有的 route 才打「自定义」标签**。
 */
internal fun providerCreated(draft: ProviderDraft, catalogRoute: Boolean): ProviderDef = ProviderDef(
    id = draft.route,
    displayName = draft.displayName.ifBlank { draft.route },
    baseUrl = draft.baseUrl.trim(),
    apiKey = draft.apiKey.trim(),
    api = draft.api,
    models = draft.models.filter { it.id.isNotBlank() },
    custom = !catalogRoute,
)

// ------------------------------------------------------------------ 模型目录的逐行校验

/** 一行模型哪里不合法（dsh 的 validateDeepSeekModels 两个 key） */
internal enum class ModelProblem { ID_REQUIRED, ID_DUPLICATE }

/** 第一处不合法的位置（0 基下标，与 dsh 一致）与原因 */
internal data class InvalidModel(val index: Int, val problem: ModelProblem)

/**
 * 逐行校验模型目录，返回**第一处**不合法；全都合法（含空目录）返回 null。
 * 逐字照 dsh 的 `validateDeepSeekModels`：
 *
 *  - ID 去掉前后空格后为空 → `modelIdRequired`；
 *  - 去掉空格后与前面某一行相同 → `modelIdDuplicate`（比较的是 trim 后的值，
 *    所以 `"gpt-x"` 与 `" gpt-x "` 算重复）。
 *
 * dsh 那个函数还校验 name（非空字符串）与 contextWindow / maxTokens（正整数）—— 这三条
 * **在 ADSH 不可达**：`ModelDef` 里 0 就是「用提供方默认值」（[CapacityField] 只会写出
 * 0 或正数），显示名留空时编辑器写的是 null 而不是 ""（见 `ModelCatalogEditor`），
 * 所以不实现（要加得先把「0 = 未设置」这条语义改掉）。
 *
 * 调用点：编辑卡的保存键（不合法就变暗，见 [providerSaved]）与添加卡片的就绪算式
 * （[providerFormState]），以及两者脚下那句 `模型 N: 原因`（[modelProblemText]）。
 */
internal fun firstInvalidModel(models: List<ModelDef>): InvalidModel? {
    val seen = HashSet<String>()
    for ((index, model) in models.withIndex()) {
        val id = model.id.trim()
        if (id.isEmpty()) return InvalidModel(index, ModelProblem.ID_REQUIRED)
        if (!seen.add(id)) return InvalidModel(index, ModelProblem.ID_DUPLICATE)
    }
    return null
}

/**
 * dsh 的 `${t("model")} ${index + 1}: ${t(key)}`：**行号从 1 数**，
 * 分隔符是半角冒号加空格（zh 字典里 model / modelIdRequired / modelIdDuplicate 逐字）。
 */
internal fun modelProblemText(invalid: InvalidModel): String {
    val reason = when (invalid.problem) {
        ModelProblem.ID_REQUIRED -> "模型 ID 不能为空。"
        ModelProblem.ID_DUPLICATE -> "模型 ID 不能重复。"
    }
    return "模型 " + (invalid.index + 1) + ": " + reason
}

// ------------------------------------------------------------------ 编辑提供方卡片

/**
 * 编辑卡的「保存」结果：要么给出规范化后的新档案，要么给出那句报错。
 *
 * 用 sealed 而不是 `ProviderDef?` + String?：调用点是一个穷尽 `when`，
 * 「报错时不写库、也不清密钥框」这两件事因此是**编译期**保证的。
 */
internal sealed interface ProviderSave {
    data class Ok(val provider: ProviderDef) : ProviderSave
    data class Failed(val message: String) : ProviderSave
}

/**
 * 编辑卡按「保存」时那三个字段的规范化与校验（原先写在按钮的 onClick 里）。
 *
 * 与 dsh 的 keyStored 语义一致 —— **密钥框不回显**，所以：
 *  - 密钥留空 = 保持原密钥（用户没打算换）；
 *  - 密钥填了 = 用它（这就是「已配置——输入新值可替换」那条占位符的含义），前后空格去掉；
 *  - 地址留空 = 保持原地址，填了就用新的（同样去空格）。
 *
 * 校验两条（与 dsh 的编辑卡同口径）：
 *  - 目录里第一处不合法的行（ID 空 / trim 后重复）→ **整条不保存**，报文是 dsh 的
 *    `模型 N: 原因`（[modelProblemText]）。dsh 那边保存键在 `modelFailure !== undefined`
 *    时就是暗的（见 ProviderCard 的 `enabled`），这里再兜一道：旧版本可能已经往设置里
 *    存进去一条 id = "" 的模型；
 *  - 目录为空 → **整条不保存**（[PROVIDER_NEEDS_MODELS]）。这一条是 ADSH 自己的口径
 *    （dsh 的编辑卡允许存一份空目录，靠 ModelCatalogEditor 那句「模型选择器中将不显示
 *    任何模型」的空态提示），保留是因为「恢复默认模型」与那句空态都建立在这条守卫上；
 *    要与 dsh 完全对齐是单独一轮的决定。
 */
internal fun providerSaved(
    provider: ProviderDef,
    keyInput: String,
    baseUrlInput: String,
    models: List<ModelDef>,
): ProviderSave {
    firstInvalidModel(models)?.let { return ProviderSave.Failed(modelProblemText(it)) }
    val trimmedKey = keyInput.trim()
    val next = provider.copy(
        baseUrl = baseUrlInput.trim().ifEmpty { provider.baseUrl },
        apiKey = if (trimmedKey.isNotEmpty()) trimmedKey else provider.apiKey,
        models = models,
    )
    return if (next.models.isEmpty()) ProviderSave.Failed(PROVIDER_NEEDS_MODELS) else ProviderSave.Ok(next)
}

/**
 * 模型目录编辑器头上那句「正在使用适配器默认模型 / 已自定义模型目录」的判据。
 *
 * 口径与 dsh 一致：**自定义提供方永远是「已自定义」**（它没有适配器默认目录可谈），
 * 其余按「目录是否等于内置默认目录」比。注意这里比的是调用点传进来的那份默认目录
 * （ProviderCard 传的是 [BuiltInProviders.DEEPSEEK_MODELS]）：从目录里加进来的提供方
 * （OpenAI 等）模型与它不同，所以显示「已自定义模型目录」—— 现状如此，不是本轮改的。
 */
internal fun providerOverridden(
    provider: ProviderDef,
    models: List<ModelDef>,
    defaultModels: List<ModelDef>,
): Boolean = provider.custom || models != defaultModels

// ------------------------------------------------------------------ 提供方清单的增删改

/**
 * 一次清单转移之后要写回设置的三样东西。
 *
 * [providerId] / [model] 可空是**「要不要写」的哨兵**，不是「可能为空的取值」：
 * `null` = 保持原样（这一刀不动它），非 null = 写这个值 —— **包括空串**
 * （删掉最后一个提供方时，当前提供方就是 `""`）。
 *
 * 为什么不让纯函数直接返回「写完之后的值」：设置里的这两个选中值各有自己的语义
 * （`settings.providerId` 在保存时**从来不写**、`settings.model` 在删除时**从来不写**），
 * 统一成「都返回最终值」会凭空制造两次多余的 SharedPreferences 写入。
 */
internal data class ProviderSelection(
    val providers: List<ProviderDef>,
    val providerId: String?,
    val model: String?,
)

/**
 * 保存一张提供方卡片（[ModelsSection] 的 onSave）。
 *
 * 两件事（原先写在 onSave 的 lambda 里）：
 *  - 同 id 的那一条**原地**换成新档案（顺序 = 用户在列表里看到的顺序，不能变）；
 *  - 如果改的正好是**当前提供方**、而当前模型被删掉了 —— 落到该提供方的第一条
 *    （「别让输入框顶着一个不存在的模型」）。目录为空时什么都不写（保持原值），
 *    因为空目录在保存那一步就被 [providerSaved] 拦下了。
 */
internal fun providersSaved(
    providers: List<ProviderDef>,
    updated: ProviderDef,
    currentProviderId: String,
    currentModel: String,
): ProviderSelection {
    val next = providers.map { if (it.id == updated.id) updated else it }
    val fallback = if (updated.id == currentProviderId && updated.models.none { it.id == currentModel }) {
        updated.models.firstOrNull()?.id
    } else {
        null
    }
    return ProviderSelection(next, providerId = null, model = fallback)
}

/**
 * 删掉一张提供方卡片（[DeleteProviderDialog] 确认之后）。
 *
 * 两件事：从清单里去掉那一条；如果删的正是**当前提供方**，当前提供方落到剩下的第一条
 * （一条不剩就写空串）。注意**当前模型不动** —— 哪怕它属于刚被删掉的这个提供方
 * （现状：模型选择器会显示它的 id，用户下次自己挑一个）。这条有用例钉住。
 */
internal fun providersDeleted(
    providers: List<ProviderDef>,
    targetId: String,
    currentProviderId: String,
): ProviderSelection {
    val next = providers.filterNot { it.id == targetId }
    val providerId = if (currentProviderId == targetId) next.firstOrNull()?.id.orEmpty() else null
    return ProviderSelection(next, providerId = providerId, model = null)
}

/** 新增一张提供方卡片：**追加到末尾**（其余顺序与内容都不动） */
internal fun providersAdded(providers: List<ProviderDef>, created: ProviderDef): ProviderSelection =
    ProviderSelection(providers + created, providerId = null, model = null)

// ------------------------------------------------------------------ 拉取可用模型（弹窗）

/**
 * 「获取可用模型」拿回来的服务端清单 → 弹窗里的候选。
 *
 * **ADSH 的偏差**（写清楚免得被当成漏改）：目录里已经有的**不列出来**。dsh 会把服务端给的
 * 全部列出来、只把「目录里还没有的」预先勾上（勾一条已有的等于什么都不做），ADSH 直接不列。
 * 这个偏差从第 60 几轮就在，[FetchModelsDialog] 的两句空态文案（「服务端返回 N 个模型，
 * 全部已经在目录里了」）也建立在它上面。
 */
internal fun fetchCandidates(fetchedIds: List<String>, existing: List<String>): List<String> =
    fetchedIds.filterNot { it in existing }

/**
 * 搜索框过滤（dsh 的 `normalizedCandidateQuery`）：**先去前后空格**，再大小写不敏感地包含。
 * 以前不去空格 —— 输入「 gpt」一个都匹配不到，而 dsh 会匹配。
 * dsh 还会匹配模型的显示名，ADSH 的 `LlmClient.listModels()` 只回 id，没有名字可匹配。
 */
internal fun candidateFilter(candidates: List<String>, query: String): List<String> {
    val needle = query.trim()
    if (needle.isEmpty()) return candidates
    return candidates.filter { it.contains(needle, ignoreCase = true) }
}

/** 「全选」那一枚按钮的文案判据（dsh 的 allVisibleCandidatesPicked）：可见的都被选过 */
internal fun allVisiblePicked(selected: Set<String>, visible: List<String>): Boolean =
    visible.isNotEmpty() && visible.all { it in selected }

/**
 * 全选 / 取消全选（dsh 的 toggleVisibleCandidates）：
 *  - 可见的**全部**已选 → 清空（dsh 也是 `new Set()`，连当前看不见的一起清）；
 *  - 否则 → **并集**（保留看不见的那些已选）。
 *
 * 以前写的是「替换成可见的那些」，于是「先勾 A → 再搜出 B → 点全选」会把 A 悄悄丢掉
 * （接着点「添加所选」就少加一个模型）—— dsh 是并集，这里照它。
 * 可见为空时那枚按钮本来就是禁用的，[allVisiblePicked] 返回 false 也就不会走清空那一支。
 */
internal fun toggleAllVisible(selected: Set<String>, visible: List<String>): Set<String> =
    if (allVisiblePicked(selected, visible)) emptySet() else selected + visible

/**
 * 「添加所选」真正加进目录的那些行：按**候选的顺序**（= 服务端给的顺序，dsh 遍历的就是
 * `candidates` 而不是勾选集合），并用内置目录把档案补齐 —— [BuiltInProviders.knownModel]
 * 决定 acceptsImages / contextWindow，图片能力就靠它（否则从服务端加回来的 deepseek-flash
 * 会把用户发的图片换成「image omitted」占位）。
 * 以前是 `selected.sorted()` —— 按字母序，与弹窗里看到的顺序不一致。
 */
internal fun adoptedModels(candidates: List<String>, selected: Set<String>): List<ModelDef> =
    candidates.filter { it in selected }.map { BuiltInProviders.knownModel(it) ?: ModelDef(it) }

// ------------------------------------------------------------------ 模型目录的行内编辑

/**
 * 改一行（dsh 的 DeepSeekModelsEditor.update：只动那一行，其余行原样）。
 *
 * **下标越界天然是空操作**：`mapIndexed` 只枚举 0..size-1，越界的下标命中不了任何一行，
 * 结果就是「原样返回」。旧写法 `models.toMutableList().also { it[index] = … }` 会直接抛
 * IndexOutOfBounds，而组合里删行与重组之间确实可能差一帧 —— 所以这个形状本身就是保护。
 * （R51 的变异测试试过给它加一道 `if (index !in models.indices) return models`：
 * 拿掉那道 guard 之后 567 个用例仍全绿 —— 说明它是死代码，已删。）
 */
internal fun modelEdited(models: List<ModelDef>, index: Int, edit: (ModelDef) -> ModelDef): List<ModelDef> =
    models.mapIndexed { at, model -> if (at == index) edit(model) else model }

/** 删一行（dsh 的 remove 里的 `models.filter((_model, at) => at !== index)`） */
internal fun modelsRemoved(models: List<ModelDef>, index: Int): List<ModelDef> =
    models.filterIndexed { at, _ -> at != index }

/**
 * 删掉第 [removed] 行之后，**展开集合**怎么跟着挪（dsh 的 remove 对 `expanded` 的处理）：
 *  - 正好是被删掉的那一行 → 丢掉（那一行都不在了）；
 *  - 在它后面的 → 减一；
 *  - 在它前面的 → 不动。
 *
 * 为什么要这一步：`expanded` 装的是**下标**，删行会让后面的行整体前移。不挪的话，
 * 「展开第 3 行 → 删掉第 1 行」会变成展开第 2 行（内容对不上，看起来像展开了错的行）。
 * dsh 的 `remove` 里就是这么写的（client.js:396-410）。
 */
internal fun reindexOnRemove(expanded: Set<Int>, removed: Int): Set<Int> {
    val next = HashSet<Int>(expanded.size)
    for (at in expanded) {
        if (at == removed) continue
        next.add(if (at > removed) at - 1 else at)
    }
    return next
}

/** 展开 / 收起一行（dsh 的 toggleExpanded） */
internal fun toggleExpanded(expanded: Set<Int>, index: Int): Set<Int> =
    if (index in expanded) expanded - index else expanded + index
