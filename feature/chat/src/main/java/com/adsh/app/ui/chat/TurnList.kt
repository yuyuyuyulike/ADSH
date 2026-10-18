package com.adsh.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * 会话流的 **items 装配**（dsh 的 Chat Node 列表：库里的消息 + 正在流式的这一轮）：判定体
 * [buildChatItems] 已提纯，这里只是接线 —— 用 derivedStateOf（折叠状态是 SnapshotStateMap，读它会被
 * 记账、自动重算）；[streamingActive] 与 `state.messages` 同源自一次更新，「尾巴关门」与「库行到位」原子发生。
 */
@Composable
internal fun rememberChatItems(
    state: ChatUiState,
    /** 流式正文的采样值（[StreamReveal.text]）—— 采样状态挂在 ChatScreen 那一层，这里只读 */
    streaming: String,
    /** 流式思考的采样值（[StreamReveal.thinking]） */
    reasoning: String,
    /** dsh 的 compactTranscript：紧凑 = 已完成轮次折成一行摘要 */
    compact: Boolean,
    /** 折叠状态查询（哪些轮次的过程窗口被展开） */
    foldOpen: (Long) -> Boolean,
    /** 思考行的展开状态查询（键见 [reasoningKey]） */
    reasoningOpen: (String) -> Boolean = { false },
): List<ChatItem> {
    val streamingActive = state.streaming.isNotEmpty()
    /** 思考尾巴的原子门（与 [streamingActive] 同一个理由，见 addLive 的注释）：与 messages 同一次更新。 */
    val reasoningActive = state.reasoning.isNotEmpty()
    val items by remember(
        state.messages,
        streaming,
        streamingActive,
        reasoning,
        reasoningActive,
        state.reasoningRunning,
        state.liveTurn.calls,
        state.pendingCalls,
        state.liveTurn.subCalls,
        state.liveTurnId,
        state.pendingSteering,
        compact,
        // dsh 的 RunningStatus 挂在流末尾：sending 与轮首时刻变了就要重排
        state.sending,
        state.runStartedAt,
    ) {
        derivedStateOf {
            buildChatItems(
                messages = state.messages,
                streaming = streaming,
                reasoning = reasoning,
                liveCalls = state.liveTurn.calls,
                liveSubCalls = state.liveTurn.subCalls,
                // 「正在写参数」的那几行接在 live 那几条之后（dsh 的 preparing 行）：
                // 只有标题 + 已写出来的 description，不可展开
                pendingCalls = state.pendingCalls,
                reasoningRunning = state.reasoningRunning,
                liveTurnId = state.liveTurnId,
                pendingSteering = state.pendingSteering,
                compact = compact,
                foldOpen = foldOpen,
                reasoningOpen = reasoningOpen,
                streamingActive = streamingActive,
                reasoningActive = reasoningActive,
                runningStatus = state.sending,
                runStartedAt = state.runStartedAt,
            )
        }
    }
    return items
}
