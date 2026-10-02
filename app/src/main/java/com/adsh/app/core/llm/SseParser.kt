package com.adsh.app.core.llm

/**
 * 增量 SSE 解析器（Server-Sent Events 的子集）。
 *
 * 逐行喂入，遇到空行表示一个事件结束；`data:` 多行按规范用换行拼接。
 * 纯逻辑、无 I/O，便于单测。
 */
class SseParser {

    private val buffer = StringBuilder()
    private var sawData = false

    /** @return 一个完整事件的 data 负载；没有则返回 null */
    fun feed(line: String): String? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // 注释/心跳
        when {
            line.startsWith("data:") -> {
                val value = line.removePrefix("data:").let { if (it.startsWith(" ")) it.substring(1) else it }
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(value)
                sawData = true
            }
            line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:") -> Unit
            else -> Unit
        }
        return null
    }

    /** 流结束（EOF）时调用，可能仍有一个未以空行收尾的事件 */
    fun end(): String? = flush()

    private fun flush(): String? {
        if (!sawData) return null
        val out = buffer.toString()
        buffer.setLength(0)
        sawData = false
        return out
    }
}
