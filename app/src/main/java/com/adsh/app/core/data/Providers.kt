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

