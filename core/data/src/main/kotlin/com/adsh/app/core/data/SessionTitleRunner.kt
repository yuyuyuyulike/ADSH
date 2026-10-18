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
 * name，通知类的不算）**恰好一条**；③ 兜底标题非空。
 *
 * **调用时机是「主请求写到连接上那一刻」**（`ChatEvent.RequestHeader`，就是 dsh 的
 * `SessionTitleService.onRequestHeader`）：第一轮进行当中并发跑。兜底标题在此之前就已经写好了
 * （ChatViewModel.writeFallbackTitle，dsh 的 ensureFallback），所以这里只负责**覆盖**它。
 * 覆盖的判据是「标题仍等于那条兜底值」= 用户没手改过名（dsh 用 `source.kind === 'user'` 判定）。
 *
 * @return 真正写进去的标题；没跑 / 没覆盖时返回 null（调用方不需要关心，它只是异步的一发）
 *
 * 跨模块可见：调用点是 `ui/ChatViewModel`，所以它是 public；`client` 是本模块的 `LlmClient`（`core.llm` 已并入 `:core:data`）。
 */
suspend fun generateSessionTitleIfNeeded(
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
        // 模型没给出标题：兜底值**在首条消息落库那一刻就已经写好了**（ChatViewModel.writeFallbackTitle），
        // 所以正常路径上这里什么都不用做；这一句只是给「兜底还没写进去」的老会话 / 早退路径兜底。
        repository.renameAutoTitle(conversationId, NEW_SESSION_TITLE, fallback)
        // dsh 在这里是抛错的（"title model produced no text"）：空标题说明**这次调用没按预期工作**
        // （模型只回了推理、被截断、或者提供方吞了输出）。静默 return null 会让「标题一直不生成」
        // 在日志里一点痕迹都没有 —— 这个空标题错误必须抛出来。
        throw IllegalStateException("session title model produced no text")
    }
    // 只在标题**仍是我们自己写的**时候才覆盖：正常路径上是那条兜底值，兜底为空（首条消息清洗后
    // 什么都不剩）/ 老会话时是占位值。用户手改过名 → 两个都不是 → 写不进去（dsh 靠
    // `session/title` 事件的 source.kind 判定，ADSH 用字符串比对近似）
    if (repository.renameAutoTitle(conversationId, fallback, title)) return title
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
