package com.adsh.app.core.agent

import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionLog

/** 一步 assistant 输出的两种落点：落库（[addAssistantRow]）与进日志（[appendAssistantStep]）。 */
internal object TurnRecords {

    /**
     * assistant 这一步**落库**：正文 + 思考（空思考写成 null），带工具调用时一并写 tool_calls。
     *
     * 「没内容就不写」的判据在调用方（[hasAssistantContent] / 打断路径的 [hasInterruptedContent]）——
     * 这里只负责**一条 assistant 行长什么样**，免得四个调用点各写一遍还各自走样。
     */
    suspend fun addAssistantRow(
        store: TurnStore,
        conversationId: Long,
        answer: CharSequence,
        reasoning: CharSequence,
        toolCallsJson: String? = null,
    ) {
        store.addMessage(
            conversationId = conversationId,
            role = "assistant",
            content = answer.toString(),
            reasoning = reasoning.toString().ifEmpty { null },
            toolCallsJson = toolCallsJson,
        )
    }

    /**
     * assistant 这一步**进日志**（dsh 的 assistant 步）：界面收到这条事件就在同一次状态更新里
     * 「从库里重读 + 清掉流式缓冲」—— 流式的正文/思考交给库里那一行，下一步的正文不会接在
     * 它们后面（流式正文不能一路把工具行挤下去）。
     *
     * 没有工具调用的最后一步同样要进（哪怕正文是空的）；打断文案是 `interrupted = true` 的同一形状。
     */
    fun appendAssistantStep(
        log: SessionLog,
        text: CharSequence,
        reasoning: CharSequence,
        interrupted: Boolean = false,
    ) {
        log.append(
            SessionBody.Step(
                text = text.toString(),
                reasoning = reasoning.toString(),
                interrupted = interrupted,
            ),
        )
    }
}
