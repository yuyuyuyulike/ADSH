package com.adsh.app.core.data

/**
 * 推理等级目录（dsh 的 reasoning effort）。
 *
 * dsh 里这张表是**逐模型**的，不是全局固定的：
 *  - `dsh-llm-deepseek`：任何 DeepSeek 模型都是固定的 Off / Low / High / Max
 *    （lib/index.js 的 REASONING_EFFORTS，名称与描述逐字）；连接级 `thinking: disabled`
 *    时才只剩 Off（OFF_ONLY_REASONING_EFFORTS）。
 *  - `dsh-llm-pi-ai`：`reasoningInfo(model)` = pi-ai 的 `getSupportedThinkingLevels(model)`，
 *    名称是「首字母大写」的等级 id（xhigh -> Xhigh）；模型没有 reasoning 元数据时
 *    整个 `reasoning` 字段都不下发，界面只剩「提供方默认」。
 *  - 客户端的「提供方默认」那一行叫 **Default**（dsh 的中英文字典里都是这一个词），
 *    并且只在模型自己没有 defaultEffort 时才出现。
 *
 * ADSH 的存法是一个全局的 reasoningEffort（空串 = 下发时一个字段都不带）。
 *  - DeepSeek 模型**没有** Default 这一行：它有默认档 high，菜单就是四行，换过去时等级就是 high
 *    （见 [defaultEffortFor] 与 SettingsStore.reasoningEffort 的读取口径）。
 *  - 其余模型的 Default 行保留 —— 它们没有默认档，不留这一行选了具体等级就回不去了。
 *
 * 逐模型的等级表由 [ModelThinkingLevels] 提供（构建期从 pi-ai 的提供方目录生成）；
 * 目录里没有的模型按约定退回 DeepSeek 的默认等级名。
 */
object Reasoning {

    /** 一个可选等级：id 是线上值，name 是菜单里显示的名字（dsh 的 efforts[].name） */
    data class Level(val id: String, val name: String, val description: String? = null)

    /** dsh-llm-deepseek 的 REASONING_EFFORTS（名称与描述逐字取自该包的 lib/index.js） */
    val DEEPSEEK_LEVELS: List<Level> = listOf(
        Level("off", "Off", "Use for simple tasks that do not need reasoning."),
        Level("low", "Low", "Prefer for routine or latency-sensitive tasks."),
        Level("high", "High", "The default balance for most tasks."),
        Level("max", "Max", "Reserve for the hardest quality-first tasks."),
    )

    /** dsh-llm-deepseek 的默认等级（connection.defaults.reasoningEffort 未配置时就是 high） */
    const val DEEPSEEK_DEFAULT = "high"

    /** dsh 的 effort.providerDefault：「提供方默认」这一行的名字 */
    const val PROVIDER_DEFAULT_NAME = "Default"

    /** 当前提供方是不是内置的官方 DeepSeek 路由（dsh-llm-deepseek 的 PROVIDER） */
    fun isDeepSeek(providerId: String): Boolean = providerId == BuiltInProviders.DEEPSEEK_ID

    /** pi-ai reasoningInfo 的命名规则：首字母大写、其余原样（xhigh -> Xhigh） */
    private fun levelName(id: String): String =
        id.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }

    /**
     * 这个模型在**它的提供方目录**里的等级；null = 目录里没有它（不猜，交给调用方兜底）。
     * DeepSeek 官方路由不查表：它的等级是连接级的常量。
     */
    fun catalogLevels(providerId: String, modelId: String): List<Level>? {
        if (isDeepSeek(providerId)) return DEEPSEEK_LEVELS
        val key = providerId + "/" + modelId
        val mask = ModelThinkingLevels.BY_KEY[key]
            ?: ModelThinkingLevels.BY_BARE_ID[SettingsStore.canonicalModelId(modelId)]
            ?: return null
        return ModelThinkingLevels.EXTENDED
            .filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
            .map { Level(it, levelName(it)) }
    }

    /** 目录里没有这个模型时用的等级名：DeepSeek 的默认四个（用户约定的兜底） */
    fun levelsFor(providerId: String, modelId: String): List<Level> =
        catalogLevels(providerId, modelId) ?: DEEPSEEK_LEVELS

    /**
     * 该模型的默认等级（dsh 的 `model.reasoning.defaultEffort`）：
     * DeepSeek 是 **high**（dsh-llm-deepseek 的 `connection.defaults.reasoningEffort` 缺省值就是它，
     * 所以 dsh 一选中 DeepSeek 模型就把等级写成 high）；其余模型没有默认档 —— null。
     */
    fun defaultEffortFor(providerId: String, modelId: String): String? =
        if (isDeepSeek(providerId)) DEEPSEEK_DEFAULT else null

    /**
     * 输入框模型菜单里的行：**该模型的等级**，模型没有默认档时前面再加一行 Default。
     *
     * DeepSeek 侧没有 Default —— 它有默认档（high），菜单就是 Off / Low / High / Max 四行，
     * 当前生效的那一行会被勾上（换到 DeepSeek 模型时默认就是 High）。
     * 已知但「不支持思考」的模型（pi-ai 的 reasoning 元数据缺失）没有等级可选 ——
     * 与 dsh 一样返回空表，菜单里显示「当前模型未提供推理等级」。
     */
    fun menuOptions(providerId: String, modelId: String): List<Pair<String, String>> {
        val levels = catalogLevels(providerId, modelId) ?: DEEPSEEK_LEVELS
        if (levels.isEmpty()) return emptyList()
        val rows = levels.map { it.id to it.name }
        return if (defaultEffortFor(providerId, modelId) == null) {
            listOf("" to PROVIDER_DEFAULT_NAME) + rows
        } else {
            rows
        }
    }

    /**
     * 真正要发给提供方的等级：null = thinking 与 reasoning_effort 两个字段都不发（走提供方默认）。
     *
     *  - 目录里没有的模型：原样放行（菜单给的选项就来自兜底表）。
     *  - 已知模型：只放行它支持的等级；存的值是别的提供方留下的（比如给 GPT 选了 medium
     *    之后换回 DeepSeek）就按 pi-ai 的 clampThinkingLevel 就近取一档 —— 往上找最近的，
     *    找不到再往下找。dsh 的 pi-ai 适配器就是这么夹的。
     *  - 明确不支持思考的模型：一个字段都不发，免得提供方直接 400。
     */
    fun wireEffort(providerId: String, modelId: String, stored: String): String? {
        if (stored.isBlank()) return null
        val levels = catalogLevels(providerId, modelId) ?: return stored
        if (levels.isEmpty()) return null
        if (levels.any { it.id == stored }) return stored
        val requested = ModelThinkingLevels.EXTENDED.indexOf(stored)
        if (requested < 0) return levels.first().id
        for (index in requested until ModelThinkingLevels.EXTENDED.size) {
            val candidate = ModelThinkingLevels.EXTENDED[index]
            levels.firstOrNull { it.id == candidate }?.let { return it.id }
        }
        for (index in requested - 1 downTo 0) {
            val candidate = ModelThinkingLevels.EXTENDED[index]
            levels.firstOrNull { it.id == candidate }?.let { return it.id }
        }
        return levels.first().id
    }
}
