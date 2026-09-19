import io, sys
R = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/'

def load(p): return io.open(R + p, encoding='utf-8').read()
def save(p, s): io.open(R + p, 'w', encoding='utf-8').write(s)
def rep(s, old, new):
    if old not in s:
        print('!! 未匹配: ' + old[:70].replace('\n', ' | ')); sys.exit(1)
    return s.replace(old, new, 1)

# ---------------- 1) ChatRequest：补 dsh 的 wire 字段（stream_options / thinking）
p = 'core/llm/ChatModels.kt'
s = load(p)
s = rep(s, '''@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** dsh 的 reasoning_effort：null 表示不指定，走提供方默认 */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val tools: List<JsonObject>? = null,
)''',
'''/** dsh 的 thinking 字段：DeepSeek 用 enabled / disabled 两态，不用 reasoning_effort=off */
@Serializable
data class ThinkingOption(val type: String)

/** dsh 的 stream_options：流式最后一个 chunk 带上 usage */
@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** dsh 的 reasoning_effort：off / low / high / max；null = 走提供方默认 */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** dsh 的 thinking：off → disabled，low/high/max → enabled */
    val thinking: ThinkingOption? = null,
    /** dsh 的 stream_options：让流式响应带 usage（token 统计靠它） */
    @SerialName("stream_options") val streamOptions: StreamOptions? = StreamOptions(),
    val tools: List<JsonObject>? = null,
)''')
save(p, s)

# ---------------- 2) AgentLoop：按 dsh 的 resolveThinking 映射
p = 'core/agent/AgentLoop.kt'
s = load(p)
s = rep(s, '''            val request = ChatRequest(
                model = config.model,
                messages = messages,
                stream = true,
                reasoningEffort = settings.reasoningEffort.ifBlank { null },
                tools = if (ptcEnabled) listOf(RUN_CODE_SCHEMA) else null,
            )''',
'''            // dsh 的 resolveThinking：off → thinking=disabled；low/high/max →
            // thinking=enabled + reasoning_effort；未选 → 两个字段都不发，走提供方默认
            val effort = settings.reasoningEffort.ifBlank { null }
            val request = ChatRequest(
                model = config.model,
                messages = messages,
                stream = true,
                reasoningEffort = effort?.takeIf { it != "off" },
                thinking = effort?.let {
                    com.adsh.app.core.llm.ThinkingOption(if (it == "off") "disabled" else "enabled")
                },
                tools = if (ptcEnabled) listOf(RUN_CODE_SCHEMA) else null,
            )''')
save(p, s)

# ---------------- 3) SettingsStore：dsh 的模型目录 + 默认值
p = 'core/data/SettingsStore.kt'
s = load(p)
s = rep(s, '''        const val DEFAULT_MODEL = "deepseek-chat"''',
'''        /**
         * 模型目录逐字取自 dsh 的 dsh-llm-deepseek（provider = deepseek-official 的 DEFAULT_MODELS）：
         * id / 名称 / 说明都按 dsh 抄，不再用 deepseek-chat 这类旧 id。
         */
        val MODEL_CATALOG: List<DshModel> = listOf(
            DshModel(
                id = "deepseek-v4-flash",
                name = "DeepSeek-V4-Flash",
                description = "快速、高效且经济；适合目标明确、常规或并行任务。",
            ),
            DshModel(
                id = "deepseek-v4-pro",
                name = "DeepSeek-V4-Pro",
                description = "更强的自主编码、知识与复杂推理能力；适合复杂或质量优先的任务，但成本更高。",
            ),
            DshModel(
                id = "deepseek-flash",
                name = "DeepSeek-V41-Flash",
                description = "上一代 Flash（兼容保留）。",
            ),
            DshModel(
                id = "deepseek-v4-flash-vision-exp",
                name = "DeepSeek-V4-Flash-Vision-Exp",
                description = "带视觉输入的 Flash 实验型号（可读图片）。",
            ),
        )

        /** 默认模型：新建会话的那一档（快、便宜） */
        const val DEFAULT_MODEL = "deepseek-v4-flash"''')

s = rep(s, '''        const val DEFAULT_CONTEXT_WINDOW = 128_000L''',
'''        /** 上下文窗口：dsh 的 DEFAULT_CONTEXT_WINDOW = 1e6 */
        const val DEFAULT_CONTEXT_WINDOW = 1_000_000L''')
save(p, s)

print('wire + catalog 完成')
