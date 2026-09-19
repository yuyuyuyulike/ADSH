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
     * 其中视觉能力按实测标注（见 docs/UI-v6-report.md 第二十二轮的追加一节）。
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
 * dsh 的目录来自安装的 pi-ai 包（`providers/` 目录下每个 provider 一个工厂文件，共 39 条 route），
 * 设置页把「目录里还没有配置过的」那些列进下拉。ADSH 只实现了 OpenAI 兼容
 * （chat completions）这一种协议，所以这里收的是 pi-ai 目录里**用同一套协议就能直连**的那些：
 * id / 显示名 / API 地址都逐字取自 pi-ai 的 provider 工厂，模型取该 route 目录里的前几个。
 *
 * 没有收录的（原因写在括号里）：anthropic / minimax / kimi-coding / vercel-ai-gateway
 * （anthropic-messages 协议）、google / google-vertex / amazon-bedrock / azure-openai-responses
 * （各自专有协议或需要账号 ID）、github-copilot / openai-codex（OAuth 登录）、
 * opencode / opencode-go / cloudflare-*（endpoint 由模型条目或账号决定，工厂里没有 baseUrl）、
 * radius / faux（内部网关）。
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

    val presets: List<Preset> = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-5.4", "gpt-5.4-mini", "gpt-5.2")),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
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
            listOf("glm-5.3", "glm-5.2", "glm-4.7"),
        ),
        Preset("zai", "Z.AI", "https://api.z.ai/api/coding/paas/v4", listOf("glm-5.3", "glm-5.2", "glm-4.7")),
        Preset(
            "qwen-token-plan-cn", "Qwen Token Plan CN",
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            listOf("qwen3.8-max", "qwen3.7-max", "qwen3.6-plus"),
        ),
        Preset("xiaomi", "Xiaomi", "https://api.xiaomimimo.com/v1", listOf("mimo-v2.5-pro", "mimo-v2.5")),
        Preset("xai", "xAI", "https://api.x.ai/v1", listOf("grok-4.6", "grok-4.5", "grok-4.3")),
        Preset(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "qwen/qwen3.6-27b"),
        ),
        Preset("mistral", "Mistral", "https://api.mistral.ai", listOf("mistral-large-latest", "devstral-latest")),
        Preset(
            "together", "Together", "https://api.together.ai/v1",
            listOf("deepseek-ai/DeepSeek-V4-Pro", "moonshotai/Kimi-K3", "zai-org/GLM-5.3"),
        ),
        Preset(
            "fireworks", "Fireworks", "https://api.fireworks.ai/inference",
            listOf("accounts/fireworks/models/kimi-k3", "accounts/fireworks/models/glm-5p3"),
        ),
        Preset(
            "nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1",
            listOf("deepseek-ai/deepseek-v4-pro-0813", "moonshotai/kimi-k3"),
        ),
        Preset(
            "huggingface", "Hugging Face", "https://router.huggingface.co/v1",
            listOf("deepseek-ai/DeepSeek-V4-Pro", "moonshotai/Kimi-K3"),
        ),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", listOf("gpt-oss-120b")),
        Preset(
            "baseten", "Baseten", "https://inference.baseten.co/v1",
            listOf("deepseek-ai/DeepSeek-V4-Pro", "moonshotai/Kimi-K3"),
        ),
        Preset("ant-ling", "Ant Ling", "https://api.ant-ling.com/v1", listOf("Ling-2.6-1T", "Ring-2.6-1T")),
    )

    fun byId(id: String): Preset? = presets.firstOrNull { it.id == id }
}

