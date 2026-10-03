package com.adsh.app.core.data

import kotlinx.serialization.Serializable

/**
 * 一个模型档案（dsh 的 pi-ai modelProfile / deepseek catalog model）：
 * 聊天只需要 id；显示名、容量与图片能力给设置页、上下文占用与请求装配用。
 */
@Serializable
data class ModelDef(
    val id: String,
    val name: String? = null,
    /** 上下文窗口（token），0 = 用提供方默认 */
    val contextWindow: Long = 0,
    /** 最大输出 token，0 = 用提供方默认 */
    val maxTokens: Long = 0,
    /** dsh 的 inputModalities 含 image */
    val acceptsImages: Boolean = false,
    /**
     * 模型编辑器里那个「可识别图片」开关的**显式取值**（三态）：
     *  - null（缺省，老数据也是这个）：按目录（[acceptsImages] / 内置 catalog）判断 ——
     *    deepseek-flash 这类目录里写着能收图的模型默认就是开；
     *  - true / false：用户在设置里手动拍过板，**以它为准**（目录说能收图也能被关掉）。
     *
     * dsh 的 inputModalities 来自 pi-ai 的模型目录，没有 UI 开关；这条是 ADSH 的扩展
     * （用户点名要：qwen3.8-flash 这类目录里没有、实际能识图的模型要能手动打开，默认关闭）。
     */
    val imageInput: Boolean? = null,
) {
    /** 设置页与模型菜单里显示的名字（dsh 的 modelNamePlaceholder：留空时使用模型 ID） */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

/** dsh 的 api 字段（pi-ai 支持三种协议） */
object ApiProtocol {
    const val OPENAI_COMPLETIONS = "openai-completions"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    val ALL = listOf(OPENAI_COMPLETIONS, ANTHROPIC_MESSAGES)

    fun label(api: String): String = when (api) {
        OPENAI_COMPLETIONS -> "OpenAI 兼容（chat completions）"
        ANTHROPIC_MESSAGES -> "Anthropic（messages）"
        else -> api
    }
}

/**
 * 一个提供方（dsh 的提供方配置）：
 * 内置的 deepseek-official 由 dsh-llm-deepseek 提供目录，其余由 dsh-llm-pi-ai 以自定义提供方的形式添加。
 */
@Serializable
data class ProviderDef(
    val id: String,
    val displayName: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val api: String = ApiProtocol.OPENAI_COMPLETIONS,
    val models: List<ModelDef> = emptyList(),
    /** 自定义提供方（dsh 的 customTag「自定义」） */
    val custom: Boolean = false,
)

/** dsh 的内置提供方与目录（dsh-llm-deepseek 的 PROVIDER / DEFAULT_MODELS） */
object BuiltInProviders {

    const val DEEPSEEK_ID = "deepseek-official"
    const val DEEPSEEK_NAME = "DeepSeek"
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"

    /**
     * dsh 的 deepseek-official 目录（DEFAULT_MODELS）：id / 名称 / 上下文窗口 / 图片能力。
     * 其中视觉能力按实测标注（见 HANDOFF.md 的『不要破的硬规矩』一节）。
     */
    val DEEPSEEK_MODELS: List<ModelDef> = listOf(
        ModelDef(id = "deepseek-flash", name = "DeepSeek-V4.1-Flash", contextWindow = 1_000_000, acceptsImages = true),
        ModelDef(id = "deepseek-v4-pro", name = "DeepSeek-V4-Pro", contextWindow = 1_000_000),
    )

    /**
     * 内置目录里已知的模型档案；未知返回 null。
     * 「获取可用模型」只能从服务端拿到 id，用它把内置档案（名称 / 容量 / **图片能力**）补齐：
     * 之前直接 `ModelDef(id)`，acceptsImages 默认 false，于是从服务端加回来的
     * deepseek-flash 会把用户发的图片替换成「image omitted」占位。
     */
    fun knownModel(id: String): ModelDef? {
        // 退役的旧 id（deepseek-v4-flash 等）现在由 deepseek-flash 承接，元数据也按它补
        val canonical = SettingsStore.canonicalModelId(id)
        return DEEPSEEK_MODELS.firstOrNull { it.id == canonical }
    }

    fun deepseek(baseUrl: String = DEEPSEEK_BASE_URL, apiKey: String = ""): ProviderDef = ProviderDef(
        id = DEEPSEEK_ID,
        displayName = DEEPSEEK_NAME,
        baseUrl = baseUrl.ifBlank { DEEPSEEK_BASE_URL },
        apiKey = apiKey,
        api = ApiProtocol.OPENAI_COMPLETIONS,
        models = DEEPSEEK_MODELS,
        custom = false,
    )

    /**
     * dsh 的 customRoute 规则：以小写字母开头，之后可用小写字母、数字和短横线
     * （pi-ai：provider id 是「小写连字符标识」，会用于派生凭据名）。
     */
    fun validProviderId(id: String): Boolean =
        id.isNotEmpty() && id[0] in 'a'..'z' && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /** 请求里用的主机名（dsh 的 provider route 展示用） */
    fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("")
}

/**
 * 供应方目录（dsh 的 `llm/listConfigurableProviders` + `settings-models` 的「提供方」下拉）。
 *
 * dsh 的目录来自安装的 pi-ai 包（`providers/` 目录下每个 provider 一个工厂文件），
 * 设置页把「目录里还没有配置过的」那些列进下拉。ADSH 只实现了 OpenAI 兼容
 * （chat completions），所以这里收的是**能用这一套协议直连**的那些：
 * id / 显示名 / API 地址取自 pi-ai 0.87.1 的 `dist/providers/data` 目录下每个 route 的 JSON
 * （每条模型自己的 baseUrl 字段就是那个 factory 的地址），模型取该 route 目录里**最新的几个**
 * （目录是按发布时间排的，这里从后往前挑）。
 *
 * **顺序就是用户看到的顺序**（第 103 轮用户点名「把常用的放在上方」）：
 *  1) 聚合 / 中转站（OpenCode Go、OpenCode Zen、OhMyGPT、OpenRouter）——
 *     OpenCode 两条来自 pi-ai 目录；OhMyGPT 是 ADSH 按用户要求加的（dsh 里没有这个 route）；
 *  2) 大厂官方（OpenAI、Anthropic、Google Gemini、xAI、Moonshot、Z.AI、Qwen、MiniMax、
 *     Xiaomi、Mistral、DeepSeek 官方是内置提供方，不在这里）；
 *  3) 云 / 推理平台（Groq、Together、Fireworks、NVIDIA、Hugging Face、Baseten、Cerebras）。
 *
 * Anthropic / Google / MiniMax 在 pi-ai 里走的是各自的专有协议（anthropic-messages /
 * google-generative-ai），这里用的是它们**官方提供的 OpenAI 兼容端点**（两家的文档里都有
 * 这一节），所以能直连；模型 id 仍然取 pi-ai 目录里的官方名字。
 *
 * 没有收录的（原因写在括号里）：google-vertex / amazon-bedrock / azure-*（各自专有协议或需要
 * 账号 ID）、github-copilot / openai-codex（OAuth 登录）、kimi-coding（anthropic-messages）、
 * vercel-ai-gateway（anthropic-messages 目录）、cloudflare-*（地址里有账号占位符）、
 * radius / faux（内部网关）、ant-ling（第 103 轮按用户口径去掉的小厂）。
 *
 * 模型 id 会随服务端更新而过期 —— 下拉只是「填好一个能用的初值」，
 * 目录里随时可以点「获取可用模型」从服务端拉真实清单。
 */
object ProviderCatalog {

    data class Preset(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val models: List<String> = emptyList(),
    )

    /** pi-ai 目录里 OpenAI 兼容、且「按条数/官方名字」都能直接用的那些 route 的 id */
    private const val OPENCODE_GO_BASE_URL = "https://opencode.ai/zen/go/v1"
    private const val OPENCODE_BASE_URL = "https://opencode.ai/zen/v1"

    /** dsh 没有 OhMyGPT 这个 route：地址取自它自己的文档（api 是 /v1 那一套） */
    private const val OHMYGPT_BASE_URL = "https://api.ohmygpt.com/v1"

    val presets: List<Preset> = listOf(
        // ---------------------------------------------------------- 聚合 / 中转站
        Preset(
            "opencode-go", "OpenCode Go", OPENCODE_GO_BASE_URL,
            listOf("qwen3.8-max", "deepseek-v4-pro", "kimi-k3"),
        ),
        Preset(
            "opencode", "OpenCode Zen", OPENCODE_BASE_URL,
            listOf("glm-5.3", "deepseek-v4-pro", "kimi-k3"),
        ),
        Preset("ohmygpt", "OhMyGPT", OHMYGPT_BASE_URL),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),

        // ---------------------------------------------------------- 大厂官方
        Preset("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol")),
        Preset(
            "anthropic", "Anthropic", "https://api.anthropic.com/v1",
            listOf("claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1"),
        ),
        Preset(
            "google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash"),
        ),
        Preset("xai", "xAI", "https://api.x.ai/v1", listOf("grok-4.7", "grok-4.6", "grok-4.5")),
        Preset(
            "moonshotai-cn", "Moonshot AI CN", "https://api.moonshot.cn/v1",
            listOf("kimi-k3", "kimi-k2.7-code", "kimi-k2.6"),
        ),
        Preset(
            "moonshotai", "Moonshot AI", "https://api.moonshot.ai/v1",
            listOf("kimi-k3", "kimi-k2.7-code", "kimi-k2.6"),
        ),
        Preset(
            "zai-coding-cn", "Z.AI Coding CN", "https://open.bigmodel.cn/api/coding/paas/v4",
            listOf("glm-5.3-highspeed", "glm-5.3", "glm-5.3-flash"),
        ),
        Preset(
            "zai", "Z.AI", "https://api.z.ai/api/coding/paas/v4",
            listOf("glm-5.3-highspeed", "glm-5.3", "glm-5.2"),
        ),
        Preset(
            "qwen-token-plan-cn", "Qwen Token Plan CN",
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            listOf("qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus"),
        ),
        Preset("minimax", "MiniMax", "https://api.minimax.io/v1", listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.5")),
        Preset("xiaomi", "Xiaomi", "https://api.xiaomimimo.com/v1", listOf("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.5-pro")),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1", listOf("mistral-large-latest", "mistral-medium-3.5", "devstral-latest")),

        // ---------------------------------------------------------- 云 / 推理平台
        Preset(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "qwen/qwen3.6-27b"),
        ),
        Preset(
            "together", "Together", "https://api.together.ai/v1",
            listOf("zai-org/GLM-5.3", "moonshotai/Kimi-K3", "deepseek-ai/DeepSeek-V4-Pro"),
        ),
        Preset(
            "fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1",
            listOf("accounts/fireworks/models/kimi-k3", "accounts/fireworks/models/glm-5p3", "accounts/fireworks/models/glm-5p3-flash"),
        ),
        Preset(
            "nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1",
            listOf("z-ai/glm-5.3", "moonshotai/kimi-k3", "nvidia/nemotron-3-ultra-550b-a55b"),
        ),
        Preset(
            "huggingface", "Hugging Face", "https://router.huggingface.co/v1",
            listOf("zai-org/GLM-5.3", "moonshotai/Kimi-K3", "deepseek-ai/DeepSeek-V4-Pro"),
        ),
        Preset(
            "baseten", "Baseten", "https://inference.baseten.co/v1",
            listOf("deepseek-ai/DeepSeek-V4-Pro", "moonshotai/Kimi-K3", "zai-org/GLM-5.3"),
        ),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", listOf("qwen-3.8-27b", "gpt-oss-120b")),
    )
}

/**
 * 老版本的**扁平配置**（一组 base_url / api_key / fetched_models，见 SettingsStore 里那几个
 * legacy 键）→ 提供方清单。这是升级路径上最要紧的一步：判错会让老用户的地址、密钥或勾过的模型
 * **消失**（甚至把用户顶到一个空的提供方上）。
 *
 * 三条规则（与迁移前的 `SettingsStore.migrateProviders` 逐字一致，只是把 prefs 读取留在调用点）：
 *  - 地址与官方默认**等价**（比较时只忽略尾斜杠）→ 只留内置提供方 deepseek-official：
 *    地址沿用老值（尾斜杠**原样保留** —— 那是用户自己存的写法）、密钥照搬；
 *    老配置里勾过的模型按内置目录补齐档案（认识的模型连 name / 容量 / **图片能力**一起回来，
 *    见 [BuiltInProviders.DEEPSEEK_MODELS]），不认识的退化成只有 id 的档案；
 *  - 地址不是官方默认 → 老配置其实是一个**自定义网关**：内置提供方 + 一个 id = "custom" 的
 *    自定义提供方，显示名取网关主机名（解析不出来时用「自定义提供方」），
 *    模型直接用 id 造（网关的模型目录与内置目录无关）；
 *  - 顺序恒是「内置在前、自定义在后」：`providerId` 与 `currentProvider()` 都以第一条为兜底。
 *
 * 两个**现状**（都有用例钉住，不是这一轮要改的东西）：比较只 trim 尾斜杠、不 trim 空格
 * （地址前后带空格会被当成自定义网关）；自定义分支不做目录补齐。
 */
/**
 * 老版本的**扁平配置**（一组 base_url / api_key / fetched_models，见 SettingsStore 里那几个
 * legacy 键）→ 升级后的提供方清单。
 *
 * 这是升级路径上最要紧的一步：判错会让老用户的地址、密钥或勾过的模型**消失**，
 * 或者（R59 挖到的那个）让用户顶着**内置提供方 + 别人的密钥**去请求。
 *
 * 三条规则：
 *  - 地址与官方默认**等价**（比较时只忽略尾斜杠）→ 只留内置提供方 deepseek-official：
 *    地址沿用老值（尾斜杠**原样保留** —— 那是用户自己存的写法）、密钥照搬；
 *    老配置里勾过的模型按内置目录补齐档案（认识的模型连 name / 容量 / **图片能力**一起回来，
 *    见 [BuiltInProviders.DEEPSEEK_MODELS]），不认识的退化成只有 id 的档案；
 *  - 地址不是官方默认 → 老配置其实是一个**自定义网关**：内置提供方 + 一个 [CUSTOM_PROVIDER_ID]
 *    的自定义提供方，显示名取网关主机名（解析不出来时用「自定义提供方」），
 *    模型直接用 id 造（网关的模型目录与内置目录无关）。
 *    **老的密钥只归这个自定义提供方**：按 dsh 的凭据模型，每个提供方路由各自一份凭据
 *    （`deriveKeyRef(provider)` = `<PROVIDER>_API_KEY`，见 dsh-settings-models 的
 *    `refFor`/1549-1503）—— 把网关的密钥也挂到 deepseek-official 上，用户就会被顶到
 *    「当前提供方是 api.deepseek.com、手里拿着网关密钥」的状态（R59 挖到的就是它）；
 *  - 顺序恒是「内置在前、自定义在后」：`providerId` 与 `currentProvider()` 都以第一条为兜底，
 *    所以**迁移出自定义网关时必须顺手把当前提供方指过去**（否则老配置等于没用上）——
 *    这就是返回的 [MigratedProviders.providerId]。
 *
 * 两个**现状**（有用例钉住）：比较只 trim 尾斜杠、不 trim 空格（地址前后带空格会被当成自定义网关）；
 * 自定义分支不做目录补齐。
 */
internal data class MigratedProviders(
    val providers: List<ProviderDef>,
    /** 非 null = 迁移之后要把「当前提供方」写成的值（官方分支不动它，保持原来的兜底） */
    val providerId: String?,
)

internal fun migratedProviders(
    legacyBase: String,
    legacyKey: String,
    legacyModels: List<String>,
): MigratedProviders {
    val officialBase = legacyBase.trimEnd('/') == BuiltInProviders.DEEPSEEK_BASE_URL
    val official = BuiltInProviders.deepseek(
        baseUrl = if (officialBase) legacyBase else BuiltInProviders.DEEPSEEK_BASE_URL,
        apiKey = if (officialBase) legacyKey else "",
    )
    if (officialBase) {
        val providers = listOf(
            if (legacyModels.isEmpty()) {
                official
            } else {
                official.copy(
                    models = legacyModels.map { id -> official.models.firstOrNull { it.id == id } ?: ModelDef(id) },
                )
            },
        )
        return MigratedProviders(providers, providerId = null)
    }
    // 自定义网关：老配置整个归它（含密钥），并把当前提供方指过来
    val custom = ProviderDef(
        id = CUSTOM_PROVIDER_ID,
        displayName = BuiltInProviders.hostOf(legacyBase).ifBlank { "自定义提供方" },
        baseUrl = legacyBase,
        apiKey = legacyKey,
        models = legacyModels.map { ModelDef(it) },
        custom = true,
    )
    return MigratedProviders(listOf(official, custom), providerId = CUSTOM_PROVIDER_ID)
}

/** 迁移出来的自定义网关用的固定 id（历史值，不能改：老数据里存的就是它） */
private const val CUSTOM_PROVIDER_ID = "custom"

/**
 * 「当前提供方」的取值规则（原先写在 `SettingsStore.providerId` 的 getter 里）：
 * 存过的 id 还在清单里就用它，否则落到**清单第一条**，清单为空才回落到内置 DeepSeek。
 *
 * 这条兜底是升级路径的另一半：老用户从来没存过 providerId，于是「当前提供方」= 清单第一条 ——
 * [migratedProviders] 因此必须在迁移出自定义网关时显式指过去（见它的 KDoc）。
 */
internal fun currentProviderIdOf(stored: String, providers: List<ProviderDef>): String =
    if (providers.any { it.id == stored }) stored else (providers.firstOrNull()?.id ?: BuiltInProviders.DEEPSEEK_ID)

/**
 * 当前提供方的模型目录 + 模型 id → 这个模型收不收图（三岔，原先写在
 * `SettingsStore.modelAcceptsImages` 里、零用例）：
 *  ① 模型编辑器里**手动拍过板**（[ModelDef.imageInput] 非空）→ 以它为准：
 *     目录说能收图的也能被关掉，目录说不支持的也能被打开；
 *  ② 目录条目自己写着「能收图」（[ModelDef.acceptsImages]）→ 放行；
 *  ③ 都不认识 → [SettingsStore.defaultImageInput]：**未知模型默认能收图**（第 83 轮用户口径）——
 *     先按能收图发，收不了会在请求时报错，报错文案直接指路回设置页取消勾选；
 *     以前静默换成「image omitted」占位等于把用户的图丢了。
 *
 * [models] 传**当前提供方**的目录；调用点那边是一次 `currentProvider().models`。
 *
 * dsh 的对应实现是模型行里的「输入类型」勾选（`ModelInputTypes`，
 * dsh-settings-models/client.js:171-201）：行上有 `inputModalities` 用它、没有就用**目录给的
 * `fallback`**、再没有才退到 `["text"]`（只文本）。ADSH 把这一组模态压成一个布尔
 * `imageInput`：前两层与 dsh 一致（行上的值优先于目录），最后那层按用户口径**反过来** ——
 * 未知模型默认**能收图**（上面 ③），宁可发出去报错指路，也不静默把图换成占位。
 */
internal fun acceptsImagesFrom(models: List<ModelDef>, modelId: String): Boolean {
    val row = models.firstOrNull { it.id == modelId }
    row?.imageInput?.let { return it }
    if (row?.acceptsImages == true) return true
    return SettingsStore.defaultImageInput(modelId)
}



