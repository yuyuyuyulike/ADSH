package com.adsh.app.ui.chat

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
 * 流式正文与思考的**重绘节流**（dsh 的流式语义：收到的正文原样上屏，不做逐字平滑）。
 *
 * 一拍（[tick]）只有一件事：把两条尾巴对齐到线上值 —— 采样节拍 [STREAM_TICK_MS] ≈ 33ms 是
 * 重绘节流（每个 token 都重组整屏会把主线程吃满），**不是**「慢慢滴出来」的动画。dsh 的
 * MarkdownText 也是按到达的正文渲染，只在「定稿」时把最后一帧补齐。
 *
 * 变短 / 换段（[shouldLatch]）不走节拍、立刻跟上：AgentLoop 是一步一落库的，库里那一行与
 * 还没清掉的流式尾巴可能在同一次更新里并存，晚一拍就会把同一段正文画两次。
 */
internal class StreamReveal(text: String, thinking: String) {
    var text by mutableStateOf(text)
        private set

    /**
     * 思考的采样值（与正文同一套节拍）：思考也按到达的正文上屏 —— 逐 token 直接进列表时
     * 每吐一个字就重组整屏，采样节拍把重绘压到 ~30fps。
     */
    var thinking by mutableStateOf(thinking)
        private set

    /** 正文尾巴被清零 / 换了一段时立刻跟上（不等节拍），判据见 [shouldLatch]。 */
    fun latchText(live: String) {
        if (shouldLatch(text, live)) text = live
    }

    /** 思考尾巴同上。 */
    fun latchThinking(live: String) {
        if (shouldLatch(thinking, live)) thinking = live
    }

    /** 一拍：两条尾巴直接对齐到线上值。 */
    fun tick(liveText: String, liveThinking: String) {
        if (text != liveText) text = liveText
        if (thinking != liveThinking) thinking = liveThinking
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
): StreamReveal {
    val reveal = remember(conversationId, liveTurnId) { StreamReveal(streaming, reasoning) }
    val latestStreaming = rememberUpdatedState(streaming)
    val latestReasoning = rememberUpdatedState(reasoning)
    val latestSending = rememberUpdatedState(sending)

    // 两条尾巴的「清零 / 换段」立刻跟上（判据 [shouldLatch]）
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
            )
        }
        reveal.settle(latestStreaming.value, latestReasoning.value)
    }
    return reveal
}
