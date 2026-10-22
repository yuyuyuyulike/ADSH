package com.adsh.app.core.agent

import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.ToolCallFunction
import com.adsh.app.core.llm.TurnLlm
import kotlinx.coroutines.channels.Channel

/**
 * 一步（round）的流收集：正文 / 思考 / 工具调用参数分别进各自的累积器，事件**原样转发**给界面
 * （顺序就是到达顺序）；首字时延与 usage 记进 [TurnMeter]。
 *
 * [ChatEvent.StreamReset]（掉线重连）要把上一次尝试攒下的三样**全部作废** —— 重连 = 重新生成
 * 这一步，不清就会把两次尝试的输出首尾相接，拼出一段模型没说过的话。
 *
 * 终止性失败（[ChatEvent.Failed]）**不在这里转发**：先交给调用方的决策点判断能不能在同一 step
 * 内恢复；终态时再由调用方按原位置（流的末尾）转发 —— 界面上失败行与后续事件的先后因此不变。
 */
internal class StepStream(private val llm: TurnLlm) {

    suspend fun collect(
        request: ChatRequest,
        bus: Channel<ChatEvent>,
        answer: StringBuilder,
        reasoning: StringBuilder,
        calls: LinkedHashMap<Int, CallAccumulator>,
        meter: TurnMeter,
    ): ChatEvent.Failed? {
        var failure: ChatEvent.Failed? = null
        llm.stream(request).collect { event ->
            when (event) {
                is ChatEvent.Delta -> {
                    meter.markFirstToken(System.currentTimeMillis())
                    answer.append(event.text)
                    bus.trySend(event)
                }
                is ChatEvent.Reasoning -> {
                    meter.markFirstToken(System.currentTimeMillis())
                    reasoning.append(event.text)
                    bus.trySend(event)
                }
                is ChatEvent.Usage -> {
                    // 一次请求只有一份 usage（dsh 的「一次 attempt 一个采样」）：
                    // 取最后一份，而不是把多个 chunk 加起来（网关重复上报时会把数字翻倍）
                    meter.onUsage(event)
                    bus.trySend(event)
                }
                is ChatEvent.ToolCallDelta -> {
                    // dsh 的 isTokenDelta：工具调用的参数分片（或带名字的那一片）**也算出字**
                    // （dsh-llm/lib/types/assistant-stream.js:185-195）。只认正文/思考的话，
                    // 「这一步只吐工具调用」的步在账本里没有首 token —— 首字时延显示为缺失，
                    // 解码窗口还会把整步都算进去（TPS 偏低）。
                    if (!event.argumentsChunk.isNullOrEmpty() || event.name != null) {
                        meter.markFirstToken(System.currentTimeMillis())
                    }
                    calls.getOrPut(event.index) { CallAccumulator() }.apply {
                        if (event.id != null) id = event.id
                        if (event.name != null) name = event.name
                        event.argumentsChunk?.let { args.append(it) }
                    }
                    bus.trySend(event)
                }
                is ChatEvent.StreamReset -> {
                    answer.setLength(0)
                    reasoning.setLength(0)
                    calls.clear()
                    meter.resetFirstToken()
                    bus.trySend(event)
                }
                // 终止性失败由调用方决定去留（见类注释），这里只捕获、不发
                is ChatEvent.Failed -> failure = failure ?: event
                else -> bus.trySend(event)
            }
        }
        return failure
    }
}

/** 一步里按 delta.index 合并的一次工具调用。 */
internal class CallAccumulator {
    var id: String? = null
    var name: String? = null
    val args = StringBuilder()

    fun toToolCall(): ToolCall? {
        val toolName = name ?: return null
        return ToolCall(
            // **id 一定要有**：有些 OpenAI 兼容网关（实测 qwen 的 token-plan 端点）流式返回的
            // tool_calls 压根不带 id（delta 里没有这个字段，或是一个空串）。id 是配对键 ——
            // assistant 行与它的 tool 结果行靠它一一对应，装配历史时也靠它判断「这次调用有没有
            // 结果」。空 id 会让「有调用、没结果」的崩溃修复误判：工具明明跑完了，模型下一轮
            // 却被告知 "The tool call was interrupted after it was recorded..."（实测就是这样）。
            // 所以缺 id 时补一个合成 id（OpenAI 的 id 本来就是客户端回显用的不透明串）。
            id = id?.trim()?.takeIf { it.isNotEmpty() } ?: syntheticCallId(),
            type = "function",
            function = ToolCallFunction(name = toolName, arguments = args.toString().ifEmpty { "{}" }),
        )
    }

    /** 合成 id：形状与 OpenAI 的 call_… 一致，只要求「同一次调用在历史里唯一」 */
    private fun syntheticCallId(): String =
        "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
}

/** 能落库的调用 = 有名字的那些（[CallAccumulator.toToolCall] 要求 name 非空）。 */
internal fun persistedToolCalls(calls: Collection<CallAccumulator>): List<ToolCall> =
    calls.mapNotNull { it.toToolCall() }
