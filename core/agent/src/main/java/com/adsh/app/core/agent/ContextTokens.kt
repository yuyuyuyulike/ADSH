package com.adsh.app.core.agent

import com.adsh.app.core.data.MessageEntity

/**
 * 上下文占用（dsh 的 ContextMeter）里三栏各算多少 —— 纯函数，桌面单测直接打。
 *
 * 一个必须避免的算法错误：「新会话发了句你好，回复也就几十个字，会话统计里的上下文
 * 直接冲到一万」。根因就在这一栏：**系统提示词被算了两遍**。
 *
 * 会话里 role = sysprompt 的那一行是**展示节点**（dsh 的 message.systemPrompt），
 * 它的正文就是请求里那条 `role: "system"` 的内容 —— [RequestMessages.build] 装配时
 * 明确把它跳过（"sysprompt", "command" -> index++）。按「所有消息的正文 + 思考」求和会把五千多
 * token 的系统提示词又算成一次「对话消息」，加上两条运行时快照与那一句你好，屏幕上就是 ~1 万。
 *
 * 现在只算**真的会上 wire 的行**，与 AgentLoop 那一段的分支一一对应：
 *  - `sysprompt`：跳过（内容在 system 那一条里，已经算在「系统提示词」那一栏）；
 *  - `command`：跳过（用户敲的斜杠命令行，不进模型）；
 *  - `retry`：跳过（模型请求重试行，log-only，不进模型请求）；
 *  - `context`：form = snapshot（dsh 的 RuntimeContextProjection）与 form = instructions
 *    （dsh 的 agent-instructions）都作为 user 消息发出去；自定义后缀那种 DISPLAY 行不发；
 *  - `tool` / 其它：发（孤儿工具结果由装配阶段丢弃，属于残局，不在这里纠）。
 */
internal fun countsTowardContext(message: MessageEntity): Boolean = when (message.role) {
    "sysprompt", "command", "retry" -> false
    "context" -> (message.subCallsJson == PromptAssembler.FORM_SNAPSHOT ||
        message.subCallsJson == PromptAssembler.FORM_INSTRUCTIONS) && message.content.isNotBlank()
    else -> true
}

/** 「对话消息」那一栏：只求和真的会上 wire 的那些行（正文 + 思考）。消费者：ChatViewModel 的上下文占用计算。 */
fun contextMessageTokens(messages: List<MessageEntity>): Long =
    messages.filter { countsTowardContext(it) }
        .sumOf { estimateTokens(it.content) + estimateTokens(it.reasoning.orEmpty()) }

/** token 估算：CJK 按 1 token/字，其余按 4 字符/token（够用且可解释）。消费者：ChatViewModel / TurnStreamState 的估算（:app 的既有测试原地不动）。 */
fun estimateTokens(text: String): Long {
    var cjk = 0
    var other = 0
    text.forEach { ch -> if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF) cjk++ else other++ }
    return cjk.toLong() + (other / 4).toLong()
}
