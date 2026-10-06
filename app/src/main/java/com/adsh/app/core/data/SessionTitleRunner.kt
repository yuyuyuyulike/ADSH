package com.adsh.app.core.data

import com.adsh.app.core.llm.collectText
import com.adsh.app.core.llm.ChatMessage
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.LlmClient
import com.adsh.app.core.llm.ThinkingOption
import com.adsh.app.core.llm.textContent

/** 已经为哪些会话跑过标题模型（本进程内一次；dsh 那边靠标题事件里的 source.kind 判断）。 */
private val titleAttempted: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

/**
 * dsh 的 SessionTitleService.onUserMessage 那一支：**首条用户消息**之后跑一次标题模型。
 *
 * 触发条件（与 dsh 一致）：① 这个会话本进程内还没跑过；② 库里真正的用户消息（role=user 且没有
 * name，通知类的不算）**恰好一条**；③ 兜底标题非空。dsh 的第四个条件「当前无标题 / 用户没改过名」
 * 在这里由 [ConversationRepository.renameAutoTitle] 的「标题仍然是「新会话」」兜住 —— 用户改过名就写不进去。
 *
 * **调用时机是「一轮跑完」**（用户第 189 轮口径）：追加消息时不再写兜底标题（见
 * `ConversationRepository.appendMessage`），所以这条会话在标题生成之前一直显示「新会话」。
 * 模型失败才退回兜底 —— 顺序上兜底永远在模型之后。
 *
 * @return 真正写进去的标题；没跑 / 没覆盖时返回 null（调用方不需要关心，它只是异步的一发）
 */
internal suspend fun generateSessionTitleIfNeeded(
    client: LlmClient,
    repository: ConversationRepository,
    model: String,
    conversationId: Long,
    /**
     * 这次小调用要发的 thinking 字段（调用点按路由算：见 [com.adsh.app.core.agent.titleNoThink]，
     * 默认就是 disabled）。null = 不发。
     *
     * 为什么必须显式关掉：这次调用只给 [TITLE_MAX_OUTPUT_TOKENS] 个输出 token，而会推理的模型
     * （DeepSeek V4 / qwen 系）默认先把预算花在推理上 → content 为空 → 标题永远出不来（用户报的 bug）。
     */
    thinking: ThinkingOption? = null,
    /**
     * DashScope 兼容模式（qwen 系）的 enable_thinking；null = 不发。
     * 见 [com.adsh.app.core.agent.titleNoThink]：那条路由认识的是这个字段，发 thinking 反而要 4-10 秒。
     */
    enableThinking: Boolean? = null,
): String? {
    if (!titleAttempted.add(conversationId)) return null
    val firstUser = repository.messages(conversationId)
        .filter { it.role == "user" && it.name == null }
        .singleOrNull()
        ?: return null
    val fallback = fallbackSessionTitle(firstUser.content)
    if (fallback.isBlank()) return null
    // 失败 / 超时 / 空标题：保留兜底（dsh 同样只在 provider 成功时才覆盖 fallback）
    val title = runCatching {
        requestSessionTitle(client, model, firstUser.content, thinking, enableThinking)
    }.getOrNull()
    if (title.isNullOrBlank()) {
        // 模型没给出标题：**退回兜底值**（用户口径是一轮跑完才出现标题，但也不能让这条会话永远停在
        // 「新会话」上）。兜底写在模型之后 —— 正常路径上用户看不到它，也就不会出现「先是一个、
        // 过一会儿又变」。
        repository.renameAutoTitle(conversationId, NEW_SESSION_TITLE, fallback)
        // dsh 在这里是抛错的（"title model produced no text"）：空标题说明**这次调用没按预期工作**
        // （模型只回了推理、被截断、或者提供方吞了输出）。以前这里静默 return null，
        // 于是「标题一直不生成」在日志里一点痕迹都没有 —— 用户报的那次就是这么查了半天。
        throw IllegalStateException("session title model produced no text")
    }
    // 只在标题**仍是「新会话」**时才写：用户手改过名，或者标题已经生成过，都不覆盖
    return if (repository.renameAutoTitle(conversationId, NEW_SESSION_TITLE, title)) title else null
}

/** 一次小调用：system 用 [TITLE_SYSTEM_PROMPT]，user 是包成 JSON 数组的首条消息，max_tokens=64。 */
private suspend fun requestSessionTitle(
    client: LlmClient,
    model: String,
    firstUserText: String,
    thinking: ThinkingOption?,
    enableThinking: Boolean?,
): String =
    // 收文本的三条口径与压缩摘要共用一份实现（core/llm/LlmTextCollect.kt）
    normalizeSessionTitle(client.collectText(titleRequest(model, firstUserText, thinking, enableThinking)))

/**
 * 这次小调用的请求体（单独成函数是为了能在纯 JVM 单测里钉住**发出去的那两个字段**：
 * `max_tokens = 64` 与 `thinking = disabled` —— 少了后者，DeepSeek 的推理会把 64 个 token 吃光）。
 */
internal fun titleRequest(
    model: String,
    firstUserText: String,
    thinking: ThinkingOption?,
    /** DashScope 兼容模式（qwen 系）的思考开关：false = 这次不思考；别的路由传 null */
    enableThinking: Boolean? = null,
): ChatRequest = ChatRequest(
    model = model,
    messages = listOf(
        ChatMessage(role = "system", content = textContent(TITLE_SYSTEM_PROMPT)),
        ChatMessage(role = "user", content = textContent(titleUserPrompt(firstUserText))),
    ),
    stream = true,
    maxTokens = TITLE_MAX_OUTPUT_TOKENS,
    thinking = thinking,
    enableThinking = enableThinking,
)
