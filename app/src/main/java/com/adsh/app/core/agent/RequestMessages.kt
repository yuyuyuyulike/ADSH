package com.adsh.app.core.agent

import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatMessage
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.textContent
import kotlinx.serialization.json.Json

/**
 * **请求历史的装配**：库里的一行 → wire 上的一条消息（原 AgentLoop.buildMessages，
 * 连同它的四个分支函数一起搬出来 —— AgentLoop 因此从 1021 行降回 800 行以下）。
 *
 * DeepSeek / OpenAI 兼容接口对历史有硬约束：
 *  - assistant 消息必须至少有 content 或 tool_calls 之一；
 *  - 带 tool_calls 的 assistant 后面必须紧跟它每个调用的 tool 结果；
 *  - tool 消息必须能对应到前一条 assistant 的某个 tool_call。
 * 取消、崩溃、断电都会在库里留下不满足约束的残行，这里统一「成组校验 + 丢弃」，
 * 否则一条脏记录会让整个会话永久 400。
 * sysprompt / command 行在装配时跳过（前者的内容就是那条 system 消息，后者不进模型）；
 * 运行时上下文快照（form = snapshot）反而**必须发**，见 [appendContextRow]。
 *
 * 依赖是 [TurnStore]（读历史）与一个 `systemPrompt` 取值函数（稳定段由 AgentLoop 连同
 * workspace / 设置一起装配）—— 于是这个类本身可以在纯 JVM 单测里直接打表。
 */
internal class RequestMessages(
    private val store: TurnStore,
    private val json: Json,
    /** 系统提示词的**稳定段**：策略 / 计划模式走运行时上下文注入，不在这里。 */
    private val systemPrompt: (previousPath: String?) -> String,
) {

    /**
     * 装配请求历史。DeepSeek / OpenAI 兼容接口对历史有硬约束：
     *  - assistant 消息必须至少有 content 或 tool_calls 之一；
     *  - 带 tool_calls 的 assistant 后面必须紧跟它每个调用的 tool 结果；
     *  - tool 消息必须能对应到前一条 assistant 的某个 tool_call。
     * 取消、崩溃、断电都会在库里留下不满足约束的残行，这里统一「成组校验 + 丢弃」，
     * 否则一条脏记录会让整个会话永久 400。
     * sysprompt / command 行在装配时跳过（前者的内容就是上面那条 system 消息，后者不进模型）；
     * 运行时上下文快照（form = snapshot）反而**必须发**，见下面那一支。
     *
     */
    suspend fun build(
        conversationId: Long,
        previousPath: String? = null,
        imageCapable: Boolean = false,
        /**
         * 当前路由是不是 DeepSeek。是的话，带 tool_calls 的 assistant 消息即使没有存下
         * reasoning，也要回一个空的 reasoning_content（思考模式的硬要求，见 ChatMessage 注释）；
         * 其它提供方不认识这个字段，就不发。
         */
        echoReasoning: Boolean = false,
    ): List<ChatMessage> {
        // 库里的顺序就是请求里的顺序：注入行（插话 / 任务通知）只在**认领那一刻**写进库
        // （见 ConversationRepository.claimInjected），位置天然落在上一步的工具结果之后 ——
        // 所以不需要再为「工具结果必须紧跟 assistant」做任何重排（第 120 轮删掉了那次重排）。
        val history = store.messages(conversationId)
        val messages = ArrayList<ChatMessage>(history.size + 1)
        messages += ChatMessage(
            role = "system",
            // 这里只要**稳定段**：当前策略 / 计划模式走运行时上下文注入（recordContext 写下的
            // context 行），并且在下面按 form = snapshot 作为 user 消息发出去
            content = textContent(systemPrompt(previousPath)),
        )

        var index = 0
        while (index < history.size) {
            val entity = history[index]
            index = when (entity.role) {
                // 孤儿工具结果：对应不上任何 assistant 的 tool_call，丢弃
                "tool" -> index + 1

                "context" -> appendContextRow(messages, entity, index)

                // 展示用的会话节点，不进请求（sysprompt 的内容在系统提示词那一条里；
                // command = 用户敲的斜杠命令行）
                "sysprompt", "command" -> index + 1

                "assistant" -> appendAssistantGroup(messages, history, index, echoReasoning, imageCapable)

                else -> appendUserRow(messages, entity, index, imageCapable)
            }
        }
        return messages
    }
    /**
     * 运行时上下文行 → 请求里的消息（dsh 的 RuntimeContextProjection）。
     *
     * form = snapshot（策略 / Android 环境 / 计划模式，各段拼成一条）与 form = instructions
     * （指令文件链，dsh 的 agent-instructions）都是**真的会发出去的 user 消息** —— dsh 里它就是一条
     * source = runtime-context 的 user 消息，不是系统提示词的一部分；旧快照留在历史里，
     * 由最新那条的抬头声明被取代。其余形态的 context 行只是展示节点，不进请求。
     *
     * @return 下一个待处理的下标（本行已消费）
     */
    private fun appendContextRow(
        messages: MutableList<ChatMessage>,
        entity: MessageEntity,
        index: Int,
    ): Int {
        // form = snapshot（策略 / 环境 / 计划）与 form = instructions（指令文件链，
        // dsh 的 agent-instructions）都是**真的会发出去的 user 消息**；其余形态的
        // context 行只是展示节点。
        if ((entity.subCallsJson == PromptAssembler.FORM_SNAPSHOT ||
                entity.subCallsJson == PromptAssembler.FORM_INSTRUCTIONS) &&
            entity.content.isNotBlank()
        ) {
            messages += ChatMessage(role = "user", content = textContent(entity.content))
        }
        return index + 1
    }
    /**
     * assistant 行 → 请求里的消息（历史协议的硬约束都在这）。
     *
     * 没有 tool_calls：正文或思考有一个非空就发一条（两个都空的行是残行，丢掉）。
     * 有 tool_calls：把**紧跟其后的连续 tool 行**一次收走，与本次调用逐个配对后发出
     * （配对规则见 [pairToolResults]），并给带图片的结果追加一条 user 消息
     * （dsh 的 tools-ptc deferContext：图片不能塞进 tool 消息，模型下一步才看到图）。
     *
     * @return 下一个待处理的下标（整组工具结果一起跳过）
     */
    private suspend fun appendAssistantGroup(
        messages: MutableList<ChatMessage>,
        history: List<MessageEntity>,
        index: Int,
        echoReasoning: Boolean,
        imageCapable: Boolean,
    ): Int {
        val entity = history[index]
        val toolCalls = entity.decodeToolCalls()
        if (toolCalls.isNullOrEmpty()) {
            if (entity.content.isNotBlank() || !entity.reasoning.isNullOrEmpty()) {
                messages += ChatMessage(
                    role = "assistant",
                    content = textContent(entity.content),
                    reasoningContent = entity.reasoning?.takeIf { it.isNotEmpty() },
                )
            }
            return index + 1
        } else {
            // 一次把后面连续的工具结果行全部收走 —— 之后**只发这一组里的**结果。
            // 旧实现是「收集全部相邻 tool 行，再判断 expected ⊆ actual 就整组发出」：
            // 一旦库里出现「上一组的 tool 结果和下一组的 tool 结果相邻」的残局
            // （崩溃/打断/模型给出无法执行的 tool_calls），多出来的那条结果会被
            // 挂在别人的 assistant 消息下面，服务端直接 400：
            // 「An assistant message with 'tool_calls' must be followed by tool messages
            //   responding to each 'tool_call_id'」。
            var cursor = index + 1
            val results = ArrayList<MessageEntity>()
            while (cursor < history.size && history[cursor].role == "tool") {
                results += history[cursor]
                cursor++
            }
            // 每个 tool_call 都要有 id：dsh 的 tool-call 块 id 一定有（serializeAssistant
            // 直接写 block.id），ADSH 的历史里可能有残缺行 —— 补一个稳定的合成 id，
            // 而不是把 "id": null 发出去（服务端会 invalid type: null）。
            val calls = toolCalls.mapIndexed { position, call ->
                if (call.id.isNullOrEmpty()) {
                    call.copy(id = "call_" + entity.id + "_" + position)
                } else {
                    call
                }
            }
            val paired = pairToolResults(calls, results)
            // 思考模式的历史要求：带 tool_calls 的 assistant 消息必须把 reasoning_content
            // 回传（实测缺失就是 400）。库里的 reasoning 为空时，DeepSeek 路由下补一个空串
            // （实测空串在 thinking=enabled / disabled 两种模式下都合法）。
            val storedReasoning = entity.reasoning?.takeIf { it.isNotEmpty() }
            val reasoningContent = storedReasoning ?: if (echoReasoning) "" else null
            messages += ChatMessage(
                role = "assistant",
                // dsh 的 serializeAssistant 恒写 content（空串也写），不省略
                content = textContent(entity.content),
                reasoningContent = reasoningContent,
                toolCalls = calls,
            )
            appendToolResults(messages, calls, paired, imageCapable)
            return cursor
        }
    }
    /**
     * 把这一组工具结果写进请求：每个调用一条 tool 消息，调用没有结果时补一条「结果未知」
     * （dsh 的崩溃修复，文案逐字见 [TOOL_OUTCOME_UNKNOWN]），结果带图片时再追加一条 user 消息
     * （图片不能塞进 tool 消息，见上面 deferContext 的说明）。
     */
    private suspend fun appendToolResults(
        messages: MutableList<ChatMessage>,
        calls: List<ToolCall>,
        paired: List<MessageEntity?>,
        imageCapable: Boolean,
    ) {
        calls.forEachIndexed { position, call ->
            val result = paired[position]
            if (result == null) {
                // dsh 的崩溃修复：assistant 请求了调用、但没有持久化的结果 ——
                // 补一条「结果未知」的工具结果，让这段历史在协议上完整
                // （dsh-session 的 TOOL_OUTCOME_UNKNOWN 文案，逐字）。
                messages += ChatMessage(
                    role = "tool",
                    content = textContent(TOOL_OUTCOME_UNKNOWN),
                    toolCallId = call.id,
                    name = call.function.name,
                )
            } else {
                messages += ChatMessage(
                    role = "tool",
                    // dsh 的 serializeMessages：空结果写 "(no output)"，别发空串
                    content = textContent(result.content.ifEmpty { NO_OUTPUT }),
                    toolCallId = call.id,
                    name = result.name,
                )
                // 这条结果带图片（read_image）：按 dsh 的 tools-ptc deferContext，
                // 把 [信封文本, 图片块] 作为一条 **user** 消息追加在这次 run 之后 ——
                // 图片不能塞进 tool 消息（提供方只认 user 消息里的图片块），
                // 所以模型在下一步才看到图（dsh 的 SDK 说明也是这么写的）。
                val images = com.adsh.app.core.agent.decodeToolImages(result.imagesJson)
                if (images.isNotEmpty()) {
                    val content = com.adsh.app.core.agent.toolImageContentPart(images, imageCapable)
                    if (content != null) messages += ChatMessage(role = "user", content = content)
                }
            }
        }
    }
    /**
     * 用户行（以及 user 之外的脏角色）→ 请求里的消息。
     *
     * 带附件时按 dsh 的 contentParts 组装（正文 + 文件句柄 / 图片块）；没有附件就发纯文本。
     *
     * @return 下一个待处理的下标（本行已消费）
     */
    private suspend fun appendUserRow(
        messages: MutableList<ChatMessage>,
        entity: MessageEntity,
        index: Int,
        imageCapable: Boolean,
    ): Int {
        // 用户消息：带附件时按 dsh 的 contentParts 组装（正文 + 文件句柄 / 图片块）；
        // 其余角色（user 之外的脏数据）按纯文本带过去
        val attachments = decodeAttachments(entity.attachmentsJson)
        if (attachments.isEmpty()) {
            if (entity.content.isNotBlank()) {
                messages += ChatMessage(role = entity.role, content = textContent(entity.content))
            }
        } else {
            messages += ChatMessage(
                role = entity.role,
                content = userContentPart(entity.content, attachments, imageCapable),
            )
        }
        return index + 1
    }
    private fun MessageEntity.decodeToolCalls(): List<ToolCall>? =
        toolCallsJson?.let { raw ->
            runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                    raw,
                )
            }.getOrNull()
        }
}

/**
 * dsh 的崩溃修复文案（dsh-session/lib/index.js 的 interruptedTurnClosers，逐字）：
 * assistant 已经请求了工具、但没有持久化的结果时，用它补一条工具结果，
 * 让这段历史在协议上保持完整（否则整个会话会永久 400）。
 */
internal const val TOOL_OUTCOME_UNKNOWN =
    "The tool call was interrupted after it was recorded, but no result was durably " +
        "recorded. Its outcome is unknown. Decide whether to retry from the tool " +
        "semantics: retry only if the operation is read-only or idempotent; if it may " +
        "have side effects, first verify external state or ask the user. Do not retry blindly."

/** dsh 的 serializeMessages：工具结果为空时写这个（逐字） */
internal const val NO_OUTPUT = "(no output)"

/**
 * 把一组工具结果按 id 配对到这次调用的每一个 tool_call 上（返回与 calls 等长的列表，
 * 没配上的位置是 null —— 调用方会给它补一条「结果未知」）。
 *
 * **没有 id 的结果要按顺序兜底**：有些 OpenAI 兼容网关（实测 qwen 的 token-plan 端点）
 * 流式返回的 tool_calls 不带 id，于是 assistant 行与结果行的 id 都是空串；只按 id 配对的话
 * 每一次调用都会被判成「有调用、没结果」，工具明明跑完了，模型下一轮却收到
 * "The tool call was interrupted after it was recorded..."（实测的「qwen 一用就中断」）。
 * 一组结果本来就是按调用顺序落库的，所以位置就是它的配对依据。
 */
internal fun pairToolResults(
    calls: List<ToolCall>,
    results: List<MessageEntity>,
): List<MessageEntity?> {
    val byId = HashMap<String, MessageEntity>()
    val unkeyed = ArrayList<MessageEntity>()
    results.forEach { result ->
        val id = result.toolCallId
        if (!id.isNullOrEmpty() && !byId.containsKey(id)) byId[id] = result else unkeyed += result
    }
    var cursor = 0
    return calls.map { call ->
        byId.remove(call.id) ?: unkeyed.getOrNull(cursor)?.also { cursor++ }
    }
}
