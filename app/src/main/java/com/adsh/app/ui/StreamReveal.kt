package com.adsh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay

/**
 * 流式正文与思考的**采样 + 平滑显现**（R14 第五步：从 ChatScreen 主函数搬出来）。
 *
 * 一拍的规矩（[tick]）：正文按 [revealFrom] 平滑显现，但**模型开始写工具参数**时一次补齐；
 * 思考同理，本步的思考块一结束就补齐。定稿那一帧由 [settle] 立刻补齐（那时采样循环已经退出）。
 * 采样节拍 [STREAM_TICK_MS] ≈ 33ms —— 它同时是「重绘节流」与「尾巴有界」的那条线。
 */
internal class StreamReveal(text: String, thinking: String) {
    var text by mutableStateOf(text)
        private set

    /**
     * 思考的采样值（与正文同一套节拍）。
     *
     * 以前思考是**逐 token 直接进列表**的：模型每吐一个字就重组整屏、重排一次思考行 ——
     * 思考慢的时候（一次只来几个字）看起来就是一顿一顿的（用户第 95 轮第 1 条）。
     * 现在与正文一样先采样再显现，两条尾巴的节拍也一致。
     */
    var thinking by mutableStateOf(thinking)
        private set

    /** 正文尾巴被清零 / 换了一段时立刻跟上（不等节拍）—— 第 81 轮修，判据见 [shouldLatch]。 */
    fun latchText(live: String) {
        if (shouldLatch(text, live)) text = live
    }

    /** 思考尾巴同上。 */
    fun latchThinking(live: String) {
        if (shouldLatch(thinking, live)) thinking = live
    }

    /** 一拍：两条尾巴各自按「补齐 / 显现」推进，条件见文件注释与下面两段。 */
    fun tick(liveText: String, liveThinking: String, toolArgsFlowing: Boolean, reasoningRunning: Boolean) {
        // **正文**：模型开始写工具调用的参数（这一段正文已经写完，模型不会再往它后面加字）
        // ⇒ 剩下的尾巴一次性补齐，不再按拍滴完。少了这一条，模型吐一段 run_code 程序的参数
        // 可能要好几秒，而界面这段时间只是在滴正文尾巴 —— 用户看到的就是「agent 都开始调用
        // 工具了，输出才结束」（第 105 轮第 1 条）。
        text = if (toolArgsFlowing) liveText else revealFrom(text, liveText)
        // **思考**：本步的思考块结束（模型开始写正文或工具参数，见 ChatUiState.reasoningRunning）
        // ⇒ 同样补齐：思考行的形态在这一刻已经要切回「第一行摘要」，尾巴不该继续滴
        // （旧写法要等这一步定稿把采样值清零，中间那几百毫秒是白等的）。
        thinking = if (!reasoningRunning) liveThinking else revealFrom(thinking, liveThinking)
    }

    /** 定稿时立刻补齐最后一帧，不能等下一次采样（那时循环已经退出了）。 */
    fun settle(liveText: String, liveThinking: String) {
        if (text != liveText) text = liveText
        if (thinking != liveThinking) thinking = liveThinking
    }
}

/**
 * 流式文本「变短 / 换段」的判据：`live` 不再以**当前已显现的** `shown` 开头（或更短）⇒ 立刻跟上。
 *
 * 少这一条就会看到「同一个正文出现两次、下面的工具行被顶下去再弹回来」（用户实测的
 * 「工具调用展示闪烁/撕裂」，而且只在模型**在工具调用之间说话**时出现）。机制：
 * AgentLoop 是一步一落库的 —— assistant 行先写库，再往日志里追加 `SessionBody.Step`；轮到界面
 * 处理时 `state.streaming` 已经清零、`state.messages` 已经带上这一步的那一行，**但采样值要等
 * 下一次采样（最多 33ms ≈ 两帧）才清**。这几帧里 [buildChatItems] 会同时把「库里那一步的正文」
 * 与「还没清掉的流式尾巴」放进同一轮 —— 于是正文重复一行，排在它下面的工具行位置整个跳一下。
 * 增长仍走 33ms 节拍（那是重绘节流），只有「变短 / 换段」这一种变化立刻生效。
 */
internal fun shouldLatch(shown: String, live: String): Boolean =
    live.length < shown.length || !live.startsWith(shown)

/**
 * 把 [StreamReveal] 挂到**调用方那一层**：采样状态必须被调用方（ChatScreen）读。
 *
 * 不能抽成一个「返回采样值」的子 composable 让子作用域自己持有：那种写法下写入只让子作用域失效，
 * 而 items 的 remember 与贴底的 SideEffect 都在 ChatScreen 那一层 —— 采样落地后没人重组那一层，
 * 表现就是流式正文一直不动、直到这一轮定稿才整段出现（用户实测的「全文一次展示」）。
 * 这里返回同一个对象、由 ChatScreen 读 [StreamReveal.text] / [StreamReveal.thinking]，那条就成立。
 */
@Composable
internal fun rememberStreamReveal(
    conversationId: Long?,
    liveTurnId: Long?,
    streaming: String,
    reasoning: String,
    sending: Boolean,
    reasoningRunning: Boolean,
    toolArgsFlowing: Boolean,
): StreamReveal {
    val reveal = remember(conversationId, liveTurnId) { StreamReveal(streaming, reasoning) }
    val latestStreaming = rememberUpdatedState(streaming)
    val latestReasoning = rememberUpdatedState(reasoning)
    val latestSending = rememberUpdatedState(sending)
    /** 本步的思考块是不是已经结束（见 [StreamReveal.tick]：结束就一次性补齐，不再滴） */
    val latestReasoningRunning = rememberUpdatedState(reasoningRunning)
    /** 模型是不是已经在写工具调用的参数了（= 这一段的正文已经写完，见 [ChatUiState.toolArgsFlowing]） */
    val latestToolArgs = rememberUpdatedState(toolArgsFlowing)

    // 两条尾巴的「清零 / 换段」立刻跟上（第 81 轮，判据 [shouldLatch]）
    LaunchedEffect(conversationId, liveTurnId) {
        snapshotFlow { latestStreaming.value }.collect { reveal.latchText(it) }
    }
    LaunchedEffect(conversationId, liveTurnId) {
        snapshotFlow { latestReasoning.value }.collect { reveal.latchThinking(it) }
    }
    LaunchedEffect(sending, conversationId, liveTurnId) {
        while (latestSending.value) {
            delay(STREAM_TICK_MS)
            reveal.tick(
                liveText = latestStreaming.value,
                liveThinking = latestReasoning.value,
                toolArgsFlowing = latestToolArgs.value,
                reasoningRunning = latestReasoningRunning.value,
            )
        }
        reveal.settle(latestStreaming.value, latestReasoning.value)
    }
    return reveal
}
