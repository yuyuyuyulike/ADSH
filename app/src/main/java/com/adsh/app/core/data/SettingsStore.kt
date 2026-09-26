package com.adsh.app.core.data

import android.content.Context
import com.adsh.app.core.llm.ProviderConfig

/** 设置存储（M2 阶段用 SharedPreferences；设置页在 M4 接入） */
/**
 * dsh 模型目录里的一条：id / 展示名 / 一句说明（说明来自 dsh 的中文字典）。
 *
 * acceptsImages = dsh 的 catalog inputModalities 含 "image"：
 * 只有它为真时才会把用户附的图片作为图片内容块发出去，否则退化成
 * textOnlyImageText 占位（dsh 的 LlmRuntime.projectImagesForTextModel）。
 */
data class DshModel(
    val id: String,
    val name: String,
    val description: String,
    val acceptsImages: Boolean = false,
)

/**
 * 一个网页搜索后端（dsh 的搜索提供方插件）：
 *  - id：dsh 注册进 ctx.web 的稳定 id（deepseek-official / exa）
 *  - endpoint：请求时拼在接口地址后面的那一段（/messages 与 /search）
 *  - apiKeyEnv：dsh 那边读的环境变量名（ADSH 存在本机设置里，只用它写错误文案）
 */
data class WebSearchBackend(
    val id: String,
    val name: String,
    val description: String,
    val endpoint: String,
    val apiKeyEnv: String,
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("adsh_settings", Context.MODE_PRIVATE)

    private val providerJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * 当前模型 id。读的时候顺手把**已退役的旧 id** 归一化（`deepseek-v4-flash` →
     * `deepseek-flash`）：老会话里存的旧 id 服务端仍然接受，但模型菜单里已经没有它了，
     * 不归一化的话输入框会顶着一个菜单里不存在的名字。
     */
    var model: String
        get() = canonicalModelId(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * 提供方路由 id（dsh 的 provider id）：内置的官方路由是 "deepseek-official"，
     * 自定义提供方用它自己的 id（dsh 的 pi-ai 提供方就是这个语义）。
     */
    val providerRoute: String
        get() = currentProvider().id.ifBlank { PROVIDER_DEEPSEEK }

    /**
     * 当前模型是否接受图片输入（dsh 的 catalog inputModalities）：
     * 先看当前提供方目录里这一条；目录里没有的按纯文本处理（dsh 的 inputModalities ?? ["text"]）。
     */
    fun modelAcceptsImages(modelId: String = model): Boolean {
        val provider = currentProvider()
        // 1) 模型编辑器里手动拍过板 → 以它为准（关也能关掉目录里写着能收图的模型）
        provider.models.firstOrNull { it.id == modelId }?.imageInput?.let { return it }
        // 2) 目录条目说「能收图」就直接放行；说「不能」时还要问一次内置档案 ——
        //    「获取可用模型」曾经把 acceptsImages 丢成 false，用户的目录里可能留着这种条目
        if (provider.models.firstOrNull { it.id == modelId }?.acceptsImages == true) return true
        return acceptsImages(modelId)
    }

    /**
     * 未配置密钥时默认走本地回环演示，保证链路可验证。
     *
     * 默认值必须看**当前提供方**的密钥：密钥现在存在 providers_json 里
     * （多提供方之后 apiKey 这条扁平字段只在老数据迁移时才有值），
     * 以前按扁平的 apiKey 判断，于是「全新安装 + 只在提供方里填了密钥」会被判成
     * 未配置 → 永远走回环演示，真实模型一次都不会被调用。
     */
    var mockMode: Boolean
        get() = prefs.getBoolean(KEY_MOCK, currentProvider().apiKey.isBlank())
        set(value) = prefs.edit().putBoolean(KEY_MOCK, value).apply()

    var systemPromptSuffix: String
        get() = prefs.getString(KEY_SYS_SUFFIX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SYS_SUFFIX, value).apply()

    /** 终端：单条命令超时（毫秒），对齐 dsh 的 shell.timeoutMs */
    var bashTimeoutMs: Long
        get() = prefs.getLong(KEY_BASH_TIMEOUT, DEFAULT_BASH_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_BASH_TIMEOUT, value).apply()

    /** 终端：单流输出上限（字节），对齐 dsh 的 shell.maxOutputBytes */
    var bashMaxOutputBytes: Int
        get() = prefs.getInt(KEY_BASH_MAX_OUTPUT, DEFAULT_BASH_MAX_OUTPUT_BYTES)
        set(value) = prefs.edit().putInt(KEY_BASH_MAX_OUTPUT, value).apply()

    /**
     * 终端：模型自己给的 timeoutMs 的上限（毫秒），对齐 dsh 的 shell.maxTimeoutMs。
     * dsh 的 bash-local 是 `clampTimeout(request.timeoutMs, config.timeoutMs, config.maxTimeoutMs)`：
     * 命令不带 timeoutMs 时用 [bashTimeoutMs]，带了就夹在它和这一项之间。
     */
    var bashMaxTimeoutMs: Long
        get() = prefs.getLong(KEY_BASH_MAX_TIMEOUT, DEFAULT_BASH_MAX_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_BASH_MAX_TIMEOUT, value).apply()

    /**
     * 网页搜索的后端（dsh 的 ctx.web 搜索提供方注册表里的 id）：
     * 内置的 deepseek-official（dsh-web-search-deepseek）或 exa（dsh-web-search-exa）。
     *
     * 同一时刻只有一个生效 —— 换到另一个就等于把它关掉（dsh 的 seam 一次只选一个提供方）。
     * 两个后端的地址与密钥**分开存**：换回来的时候原来的配置还在。
     */
    var webSearchProvider: String
        get() {
            val stored = prefs.getString(KEY_WEB_PROVIDER, WEB_SEARCH_PROVIDER_DEEPSEEK)
                ?: WEB_SEARCH_PROVIDER_DEEPSEEK
            return if (WEB_SEARCH_BACKENDS.any { it.id == stored }) stored else WEB_SEARCH_PROVIDER_DEEPSEEK
        }
        set(value) = prefs.edit().putString(KEY_WEB_PROVIDER, value).apply()

    /** 某个后端的接口地址（每个后端一份；没设置过就用它自己的默认值） */
    fun webSearchBaseUrlOf(provider: String): String {
        val stored = prefs.getString(webKey(KEY_WEB_BASE_URL, provider), null)
            ?: return defaultWebSearchBaseUrl(provider)
        // DeepSeek 的旧默认值指向 chat 的 base（dsh 的注释里专门强调过两者不同）：当作没设置过
        if (provider == WEB_SEARCH_PROVIDER_DEEPSEEK && stored.trimEnd('/') == LEGACY_WEB_SEARCH_BASE_URL) {
            return DEFAULT_WEB_SEARCH_BASE_URL
        }
        return stored
    }

    fun setWebSearchBaseUrl(provider: String, value: String) =
        prefs.edit().putString(webKey(KEY_WEB_BASE_URL, provider), value).apply()

    /** 某个后端的 API Key（每个后端一份） */
    fun webSearchApiKeyOf(provider: String): String =
        prefs.getString(webKey(KEY_WEB_API_KEY, provider), "") ?: ""

    fun setWebSearchApiKey(provider: String, value: String) =
        prefs.edit().putString(webKey(KEY_WEB_API_KEY, provider), value).apply()

    /** 当前后端的接口地址：web_search 与设置页读的都是它 */
    var webSearchBaseUrl: String
        get() = webSearchBaseUrlOf(webSearchProvider)
        set(value) = setWebSearchBaseUrl(webSearchProvider, value)

    /** 当前后端的 API Key；未配置时 web_search 返回结构化错误 */
    var webSearchApiKey: String
        get() = webSearchApiKeyOf(webSearchProvider)
        set(value) = setWebSearchApiKey(webSearchProvider, value)

    /**
     * 某个后端「一次搜索的上限」，每个后端一份（没设置过就用它自己的默认值）：
     *  - DeepSeek：dsh 的 maxUses（服务端工具在必须作答前最多搜几次），默认 5；
     *  - Exa：每条 query 的 numResults（取出多少条候选），默认 10 —— Exa 自己的默认值就是 10。
     */
    fun webSearchMaxUsesOf(provider: String): Int =
        prefs.getInt(webKey(KEY_WEB_MAX_USES, provider), defaultWebSearchMaxUses(provider)).coerceAtLeast(1)

    fun setWebSearchMaxUses(provider: String, value: Int) =
        prefs.edit().putInt(webKey(KEY_WEB_MAX_USES, provider), value.coerceAtLeast(1)).apply()

    /** 当前后端的一次搜索上限 */
    var webSearchMaxUses: Int
        get() = webSearchMaxUsesOf(webSearchProvider)
        set(value) = setWebSearchMaxUses(webSearchProvider, value)

    /**
     * 后端自己的偏好键。内置的 DeepSeek 沿用历史键（升级上来的配置就是它的），
     * 其余后端在键尾接提供方 id。
     */
    private fun webKey(base: String, provider: String): String =
        if (provider == WEB_SEARCH_PROVIDER_DEEPSEEK) base else base + "_" + provider

    /** 权限预设（dsh 的 permission preset）：read_only / workspace_write / full_access */
    var permission: String
        get() = prefs.getString(KEY_PERMISSION, PERMISSION_FULL_ACCESS) ?: PERMISSION_FULL_ACCESS
        set(value) = prefs.edit().putString(KEY_PERMISSION, value).apply()

    /**
     * 推理等级（dsh 的 reasoning_effort）。空串 = 下发时一个字段都不带（提供方默认）。
     *
     * **DeepSeek 模型没有「提供方默认」这一档**：dsh 的 deepseek 适配器给它配了默认等级 high
     * （connection.defaults.reasoningEffort 的缺省值），客户端一选中 DeepSeek 模型就写成 high。
     * 这里做同一件事：存的是空串、而当前模型的默认档非空时（换到 DeepSeek 模型就是这种情况）
     * 读出来就是那个默认值。其余模型照旧返回空串。
     */
    var reasoningEffort: String
        get() {
            val stored = prefs.getString(KEY_EFFORT, "") ?: ""
            if (stored.isNotEmpty()) return stored
            return Reasoning.defaultEffortFor(providerRoute, model).orEmpty()
        }
        set(value) = prefs.edit().putString(KEY_EFFORT, value).apply()

    /**
     * 外观（dsh 的 ui-theme.preference：light / dark / system）。
     * dsh 的外观行是「浅色 / 深色 / 跟随系统」三个立方，默认跟随系统。
     */
    var themePreference: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /**
     * 会话内容字号（dsh 的 ui-theme.fontSize，12..17，默认 14）。
     * dsh 的说明是「仅影响会话内容的字号」——这里按同一个口径缩放会话内容区。
     */
    var contentFontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_CONTENT_FONT_SIZE).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)
        set(value) = prefs.edit()
            .putInt(KEY_FONT_SIZE, value.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX))
            .apply()

    /**
     * 对话显示（dsh 的 ui-chat.transcriptView：normal / compact），控制已完成轮次的过程内容。
     * dsh 的 DEFAULT_TRANSCRIPT_VIEW_MODE 就是 compact：已完成轮次的过程折成一行摘要，
     * 「标准」则把过程全部铺开。ADSH 现在的样子正是 compact。
     */
    var transcriptView: String
        get() = prefs.getString(KEY_TRANSCRIPT, TRANSCRIPT_COMPACT) ?: TRANSCRIPT_COMPACT
        set(value) = prefs.edit().putString(KEY_TRANSCRIPT, value).apply()

    /** 繁忙时的发送行为（dsh 的 ui-conversation.busyEnter：queue / steer） */
    var busyEnter: String
        get() = prefs.getString(KEY_BUSY_ENTER, BUSY_QUEUE) ?: BUSY_QUEUE
        set(value) = prefs.edit().putString(KEY_BUSY_ENTER, value).apply()

    /**
     * 边缘防误触（这个客户端自己的项，dsh 没有对应插件）。
     *
     * 落在屏幕**左右边缘带**里的手指整根都不参与手势（点击 / 长按 / 滚动 / 拖拽）：
     * 握持时手掌、拇指根部蹭到（尤其是曲面屏的）边缘会被触摸屏报成一次真实的按下，
     * 表现就是「没碰却点开了什么 / 列表自己滚了一下」。
     * 取值为预设 id（off / narrow / medium / wide），具体带宽见 [edgeGuardWidthDp]。
     */
    var edgeGuard: String
        get() {
            val stored = prefs.getString(KEY_EDGE_GUARD, EDGE_GUARD_NARROW) ?: EDGE_GUARD_NARROW
            return if (EDGE_GUARD_PRESETS.containsKey(stored)) stored else EDGE_GUARD_NARROW
        }
        set(value) = prefs.edit().putString(KEY_EDGE_GUARD, value).apply()

    /**
     * Agent 循环：同一步内最多同时运行多少个可并行的工具调用
     * （dsh 的 agent-loop.maxParallelToolCalls，默认 10、最小 1）。
     * 在 ADSH 的 PTC 里它管的是「一个 run_code 程序里 Promise.all 包起来的子调用能并发几个」。
     */
    var agentMaxParallel: Int
        get() = prefs.getInt(KEY_AGENT_MAX_PARALLEL, DEFAULT_AGENT_MAX_PARALLEL).coerceAtLeast(1)
        set(value) = prefs.edit().putInt(KEY_AGENT_MAX_PARALLEL, value.coerceAtLeast(1)).apply()

    /** 上下文窗口（token），供「上下文已用」按比例显示 */
    var contextWindow: Long
        get() = prefs.getLong(KEY_CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW)
        set(value) = prefs.edit().putLong(KEY_CONTEXT_WINDOW, value).apply()

    /**
     * 从服务端 GET /models 拉到的可用模型清单（换行分隔）。
     * dsh 用的是内置目录，但账号权限不同、目录也会变（本机账号实际只提供
     * deepseek-flash / deepseek-v4-pro），所以拉取结果优先于内置目录。
     */
    var fetchedModels: List<String>
        get() = (prefs.getString(KEY_FETCHED_MODELS, "") ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_FETCHED_MODELS, value.joinToString("\n")).apply()

    /** 上一次装配提示词时使用的工作区；变化时输出「基线替换」 */
    var lastWorkspacePath: String?
        get() = prefs.getString(KEY_LAST_WORKSPACE, null)
        set(value) = prefs.edit().putString(KEY_LAST_WORKSPACE, value).apply()

    // ---------------------------------------------------------------- 提供方（dsh 的 llm 适配器目录）

    /**
     * 提供方清单（dsh 的 deepseek-official 内置目录 + llm-pi-ai 的自定义提供方）。
     *
     * 老版本只有一组扁平的 baseUrl / apiKey / fetchedModels：第一次读取时把它们迁进来 ——
     * 地址还是官方默认就是 deepseek-official 的密钥，否则包一个「自定义提供方」，
     * 这样升级后原来的配置与勾选的模型都还在。
     */
    var providers: List<ProviderDef>
        get() {
            val raw = prefs.getString(KEY_PROVIDERS, null)
            if (raw != null) {
                return runCatching {
                    providerJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ProviderDef.serializer()),
                        raw,
                    )
                }.getOrElse { listOf(BuiltInProviders.deepseek()) }
            }
            val migrated = migrateProviders()
            prefs.edit().putString(KEY_PROVIDERS, encodeProviders(migrated)).apply()
            return migrated
        }
        set(value) = prefs.edit().putString(KEY_PROVIDERS, encodeProviders(value)).apply()

    private fun encodeProviders(value: List<ProviderDef>): String =
        providerJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProviderDef.serializer()), value)

    /** 扁平配置 → 提供方清单（只跑一次，见 providers 的注释） */
    private fun migrateProviders(): List<ProviderDef> {
        val legacyBase = prefs.getString(KEY_BASE_URL, BuiltInProviders.DEEPSEEK_BASE_URL)
            ?: BuiltInProviders.DEEPSEEK_BASE_URL
        val legacyKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val legacyModels = fetchedModels
        val official = BuiltInProviders.deepseek(
            baseUrl = if (legacyBase.trimEnd('/') == BuiltInProviders.DEEPSEEK_BASE_URL) legacyBase else BuiltInProviders.DEEPSEEK_BASE_URL,
            apiKey = legacyKey,
        )
        if (legacyBase.trimEnd('/') == BuiltInProviders.DEEPSEEK_BASE_URL) {
            return listOf(
                if (legacyModels.isEmpty()) official else official.copy(
                    models = legacyModels.map { id -> official.models.firstOrNull { it.id == id } ?: ModelDef(id) },
                ),
            )
        }
        // 自定义网关：包成一个自定义提供方，模型用拉取到的清单（没有就给一个占位，用户可再编辑）
        val custom = ProviderDef(
            id = "custom",
            displayName = BuiltInProviders.hostOf(legacyBase).ifBlank { "自定义提供方" },
            baseUrl = legacyBase,
            apiKey = legacyKey,
            models = legacyModels.map { ModelDef(it) },
            custom = true,
        )
        return listOf(official, custom)
    }

    /** 当前提供方（dsh 的 agent-default-model.provider） */
    var providerId: String
        get() {
            val stored = prefs.getString(KEY_PROVIDER_ID, "") ?: ""
            val list = providers
            return if (list.any { it.id == stored }) stored else (list.firstOrNull()?.id ?: BuiltInProviders.DEEPSEEK_ID)
        }
        set(value) = prefs.edit().putString(KEY_PROVIDER_ID, value).apply()

    /** 当前提供方的定义 */
    fun currentProvider(): ProviderDef {
        val list = providers
        return list.firstOrNull { it.id == providerId }
            ?: list.firstOrNull()
            ?: BuiltInProviders.deepseek()
    }

    fun providerConfig(): ProviderConfig {
        val provider = currentProvider()
        return ProviderConfig(
            baseUrl = provider.baseUrl.ifBlank { BuiltInProviders.DEEPSEEK_BASE_URL },
            apiKey = provider.apiKey,
            model = model,
            mock = mockMode || provider.apiKey.isBlank(),
            api = provider.api,
            providerId = provider.id,
            providerName = provider.displayName,
        )
    }

    // 系统提示词的装配已经全部搬去 core/agent/PromptAssembler（含 Android 运行环境段）；
    // 这里原来的 systemPrompt() 是最早期的简化版，早已没有调用方，删掉以免两处文本漂移。

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        /**
         * 官方 deepseek-official 现在**只有两档**（api-docs.deepseek.com/quick_start/pricing）：
         *
         * | model | 版本 | 上下文 | 视觉 |
         * |---|---|---|---|
         * | `deepseek-flash` | DeepSeek-V4.1-Flash | 1M | ✓ |
         * | `deepseek-v4-pro` | DeepSeek-V4-Pro-0813 | 1M | 不支持 |
         *
         * 官网原文的脚注：「Use `deepseek-flash` as the model name. The legacy names
         * `deepseek-v4-flash` and `deepseek-v4-flash-vision-exp` are still accepted, but the
         * corresponding models have been retired, their requests are served by the
         * DeepSeek-V4.1-Flash model」—— 旧 id 是**已退役的别名**，实际由 V4.1-Flash 承接。
         */
        val MODEL_CATALOG: List<DshModel> = listOf(
            DshModel(
                id = "deepseek-flash",
                name = "DeepSeek-V4.1-Flash",
                description = "快速、高效且经济，原生多模态（可读图）；适合目标明确、常规或并行任务。",
                acceptsImages = true,
            ),
            DshModel(
                id = "deepseek-v4-pro",
                name = "DeepSeek-V4-Pro",
                description = "更强的自主编码、知识与复杂推理能力；适合复杂或质量优先的任务，但成本更高。不支持图片输入。",
            ),
        )

        /**
         * 已退役但服务端仍然接受的旧 id（官网脚注里的那两个）→ 现在实际承接它们的型号。
         * 映射到 `deepseek-flash` 之后，用户目录里留着的旧 id 也按「可读图」处理 ——
         * 否则会退回纯文本占位、图片被丢掉（正是用户实测到的那条）。
         */
        private val LEGACY_MODEL_IDS = mapOf(
            "deepseek-v4-flash" to "deepseek-flash",
            "deepseek-v4-flash-vision-exp" to "deepseek-flash",
        )

        /** 旧 id → 现在的 id（不是旧 id 就原样返回） */
        fun canonicalModelId(modelId: String): String = LEGACY_MODEL_IDS[modelId] ?: modelId

        /**
         * 这个模型 id 能不能收图（dsh 的 catalog inputModalities）。
         * 目录里没有的 id 按纯文本处理 —— 与其把图片塞给可能拒收的模型，不如按 dsh 的占位降级。
         */
        fun acceptsImages(modelId: String): Boolean {
            val canonical = canonicalModelId(modelId)
            return MODEL_CATALOG.firstOrNull { it.id == canonical }?.acceptsImages == true
        }

        /** dsh-llm-deepseek 的 provider route id */
        const val PROVIDER_DEEPSEEK = "deepseek-official"

        /** 默认模型：新建会话的那一档（快、便宜、可读图）。旧值 `deepseek-v4-flash` 已是退役别名 */
        const val DEFAULT_MODEL = "deepseek-flash"
        private const val KEY_PROVIDERS = "providers_json"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        const val DEFAULT_BASH_TIMEOUT_MS = 120_000L
        const val DEFAULT_BASH_MAX_OUTPUT_BYTES = 64_000

        /** dsh 的 bash-local.maxTimeoutMs 默认值（600000 = 10 分钟） */
        const val DEFAULT_BASH_MAX_TIMEOUT_MS = 600_000L

        /**
         * 网页搜索的默认端点。逐字取自 dsh-web-search-deepseek：
         * 这是 DeepSeek 的 Anthropic 兼容地址（请求时再拼 /messages），
         * 与 chat 的 base（https://api.deepseek.com）不是同一个。
         */
        const val DEFAULT_WEB_SEARCH_BASE_URL = "https://api.deepseek.com/anthropic/v1"
        private const val LEGACY_WEB_SEARCH_BASE_URL = "https://api.deepseek.com"

        /** dsh-web-search-exa 的 EXA_DEFAULT_BASE_URL（请求时再拼 /search） */
        const val DEFAULT_WEB_SEARCH_BASE_URL_EXA = "https://api.exa.ai"

        /** 网页搜索的后端 id（dsh 的搜索提供方插件注册进 ctx.web 用的稳定 id） */
        const val WEB_SEARCH_PROVIDER_DEEPSEEK = "deepseek-official"
        const val WEB_SEARCH_PROVIDER_EXA = "exa"

        /**
         * 设置页「更换搜索引擎」里列的后端（就是 dsh 的两个搜索提供方插件）：
         * endpoint 是「请求时拼在接口地址后面的那一段」，hint 跟着后端走。
         */
        val WEB_SEARCH_BACKENDS: List<WebSearchBackend> = listOf(
            WebSearchBackend(
                id = WEB_SEARCH_PROVIDER_DEEPSEEK,
                name = "DeepSeek",
                description = "DeepSeek 原生的 web_search_20250305 服务端工具，每次搜索算一轮模型调用。",
                endpoint = "/messages",
                apiKeyEnv = "DEEPSEEK_API_KEY",
            ),
            WebSearchBackend(
                id = WEB_SEARCH_PROVIDER_EXA,
                name = "Exa",
                description = "Exa 的 /search 接口，直接返回排序好的网页与相关片段，不额外花模型调用。",
                endpoint = "/search",
                apiKeyEnv = "EXA_API_KEY",
            ),
        )

        /** 后端没配置过地址时的默认值 */
        fun defaultWebSearchBaseUrl(provider: String): String = when (provider) {
            WEB_SEARCH_PROVIDER_EXA -> DEFAULT_WEB_SEARCH_BASE_URL_EXA
            else -> DEFAULT_WEB_SEARCH_BASE_URL
        }
        /** dsh 的 DEEPSEEK_DEFAULT_MAX_USES */
        const val DEFAULT_WEB_SEARCH_MAX_USES = 5

        /**
         * Exa 侧的默认候选条数。dsh 的 Exa 提供方把请求里的 maxResults 当 numResults 发、
         * 自己不设默认值；这里取 Exa 文档里的默认值 10，让「截断到 7 条」有得可截。
         */
        const val DEFAULT_WEB_SEARCH_NUM_RESULTS_EXA = 10

        /** 后端没设置过「一次搜索上限」时的默认值 */
        fun defaultWebSearchMaxUses(provider: String): Int =
            if (provider == WEB_SEARCH_PROVIDER_EXA) DEFAULT_WEB_SEARCH_NUM_RESULTS_EXA else DEFAULT_WEB_SEARCH_MAX_USES
        /** dsh 的 agent-loop.maxParallelToolCalls 默认值（z.number().step(1).min(1).default(10)） */
        const val DEFAULT_AGENT_MAX_PARALLEL = 10
        /** dsh 的 DEEPSEEK_DEFAULT_API_VERSION */
        const val WEB_SEARCH_API_VERSION = "2023-06-01"
        /** dsh 的 DEEPSEEK_DEFAULT_MAX_TOKENS */
        const val WEB_SEARCH_MAX_TOKENS = 4096
        /** dsh 的 DEEPSEEK_DEFAULT_MODEL：搜索用的 Anthropic 格式模型名 */
        const val WEB_SEARCH_MODEL = "deepseek-flash"

        /** 外观（dsh 的 THEME_PREFERENCES） */
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        /** 会话内容字号（dsh 的 fontSize：12..17，默认 14） */
        const val FONT_SIZE_MIN = 12
        const val FONT_SIZE_MAX = 17
        const val DEFAULT_CONTENT_FONT_SIZE = 14

        /** 对话显示（dsh 的 TRANSCRIPT_VIEW_MODES） */
        const val TRANSCRIPT_NORMAL = "normal"
        const val TRANSCRIPT_COMPACT = "compact"

        /** 繁忙时的发送行为（dsh 的 BUSY_ENTER_BEHAVIORS） */
        const val BUSY_QUEUE = "queue"
        const val BUSY_STEER = "steer"

        /**
         * 边缘防误触的预设：id → （展示名, 单边带宽 dp）。
         *
         * 带宽取值的依据：
         *  - 12dp 是**系统自己的「边缘」定义**（`ViewConfiguration.getScaledEdgeSlop()`，
         *    AOSP 里 EDGE_SLOP = 12dp）——「窄」这一档跟系统对齐，最保守；
         *  - 曲面屏上真正会被蹭到的区域通常到 5～10dp 量级，但手指「按住边上一块」的
         *    落点会往里偏，所以再给 20dp / 28dp 两档；
         *  - 默认「窄」：它只会吃掉贴边的 12dp，App 里所有控件离边都≥14dp（设置页 16、
         *    顶栏 14、输入框卡片 10），默认值不会误伤常规点击；不够用可以在设置里加宽。
         */
        val EDGE_GUARD_PRESETS: Map<String, Pair<String, Int>> = linkedMapOf(
            EDGE_GUARD_OFF to ("关闭" to 0),
            EDGE_GUARD_NARROW to ("窄" to 12),
            EDGE_GUARD_MEDIUM to ("中" to 20),
            EDGE_GUARD_WIDE to ("宽" to 28),
        )
        const val EDGE_GUARD_OFF = "off"
        const val EDGE_GUARD_NARROW = "narrow"
        const val EDGE_GUARD_MEDIUM = "medium"
        const val EDGE_GUARD_WIDE = "wide"

        /** 预设 id → 单边带宽（dp）；「关闭」与未知值一律 0（= 不拦任何触摸） */
        fun edgeGuardWidthDp(id: String): Int = EDGE_GUARD_PRESETS[id]?.second ?: 0

        /** 上下文窗口：dsh 的 DEFAULT_CONTEXT_WINDOW = 1e6 */
        const val DEFAULT_CONTEXT_WINDOW = 1_000_000L
        const val PERMISSION_READ_ONLY = "read_only"
        const val PERMISSION_WORKSPACE_WRITE = "workspace_write"
        const val PERMISSION_FULL_ACCESS = "full_access"
        private const val KEY_PERMISSION = "permission"
        private const val KEY_EFFORT = "reasoning_effort"
        private const val KEY_CONTEXT_WINDOW = "context_window"
        private const val KEY_MOCK = "mock_mode"
        private const val KEY_BASH_TIMEOUT = "bash_timeout_ms"
        private const val KEY_BASH_MAX_OUTPUT = "bash_max_output_bytes"
        private const val KEY_BASH_MAX_TIMEOUT = "bash_max_timeout_ms"
        private const val KEY_WEB_BASE_URL = "web_search_base_url"
        private const val KEY_WEB_API_KEY = "web_search_api_key"
        private const val KEY_WEB_PROVIDER = "web_search_provider"
        private const val KEY_THEME = "theme_preference"
        private const val KEY_FONT_SIZE = "content_font_size"
        private const val KEY_TRANSCRIPT = "transcript_view"
        private const val KEY_BUSY_ENTER = "busy_enter"
        private const val KEY_EDGE_GUARD = "edge_guard"
        private const val KEY_WEB_MAX_USES = "web_search_max_uses"
        private const val KEY_AGENT_MAX_PARALLEL = "agent_max_parallel"
        private const val KEY_SYS_SUFFIX = "system_prompt_suffix"
        private const val KEY_LAST_WORKSPACE = "last_workspace_path"
        private const val KEY_FETCHED_MODELS = "fetched_models"
    }
}
