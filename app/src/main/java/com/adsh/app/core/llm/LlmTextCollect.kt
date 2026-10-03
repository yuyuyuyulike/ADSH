package com.adsh.app.core.llm

import kotlinx.coroutines.flow.collect

/**
 * 把一次「只要文本结果」的小调用收成一个字符串。
 *
 * 目前两个调用方：**会话标题**（SessionTitleRunner，首条用户消息之后跑一次）与**压缩摘要**
 * （ChatViewModel.summarize）。两处原本各抄了一遍同一段 when，连注释都一样 —— 提纯到这里之后，
 * 「掉线重连要把上一次攒下的文本作废」这条口径只有一个实现（[ChatEvent.StreamReset] 的语义见
 * LlmClient 的重连逻辑）。
 *
 * 三条口径：
 *  - [ChatEvent.Delta]：累加；
 *  - [ChatEvent.StreamReset]：**清空重来**（上一次尝试的文本作废）；
 *  - [ChatEvent.Failed]：抛出去，由调用方决定兜底（标题保留兜底标题、摘要让这一轮失败）。
 */
internal suspend fun LlmClient.collectText(request: ChatRequest): String {
    val out = StringBuilder()
    stream(request).collect { event ->
        when (event) {
            is ChatEvent.Delta -> out.append(event.text)
            is ChatEvent.StreamReset -> out.setLength(0)
            is ChatEvent.Failed -> throw IllegalStateException(event.message)
            else -> Unit
        }
    }
    return out.toString()
}
