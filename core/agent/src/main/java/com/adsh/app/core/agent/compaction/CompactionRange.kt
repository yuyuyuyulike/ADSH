package com.adsh.app.core.agent.compaction

import com.adsh.app.core.agent.estimateTokens
import com.adsh.app.core.data.MessageEntity

/**
 * 一次压缩选出的三段（dsh 的 selectCompactableRange）：
 *  - [prefix]：系统节点（`sysprompt` / `context`），**永不在压缩区间里**；
 *  - [older]：要被检查点摘要替换掉的历史；
 *  - [keep]：原样保留的尾巴。
 */
internal data class CompactionRange(
    val prefix: List<MessageEntity>,
    val older: List<MessageEntity>,
    val keep: List<MessageEntity>,
) {

    companion object {
        /** 保留尾巴 = 上下文窗口的 16%（dsh 的 retainRatio 默认 0.16）。 */
        const val RETAIN_PERCENT = 16L

        /** 保留尾巴的 token 预算；窗口未知（0 / 负数）时返回 0，表示不做。 */
        fun retainTokens(window: Long): Long =
            if (window <= 0) 0 else (window * RETAIN_PERCENT / 100).coerceAtLeast(1)

        /**
         * 手动 /compact 与 pressure 压缩共用的区间选择 —— 与 dsh 的 selectCompactableRange 同口径，
         * 也与迁移前的 ChatViewModel.compactableRange 逐字一致：
         *
         *  - 从末尾往前累计 [estimateTokens]，累计够 [retainTokens] 为止，那一段原样保留；
         *  - 系统节点（`sysprompt` / `context`）在区间之外；
         *  - 不切开「assistant 工具调用 + 它的工具结果」：保留段不能以 `tool` 行开头
         *    （边界前移到下一个非 tool 行，工具结果跟着前面那条 assistant 一起被压）。
         *
         * @return 没有可压缩内容时返回 null（窗口未知 / 全是系统节点 / 尾巴已覆盖全部历史）。
         */
        fun select(history: List<MessageEntity>, window: Long): CompactionRange? {
            if (window <= 0) return null
            if (history.isEmpty()) return null
            val floor = firstNonSystemIndex(history) ?: return null
            val retain = retainTokens(window)

            var accumulated = 0L
            var keepFrom = history.size
            for (index in history.indices.reversed()) {
                if (index < floor) break
                accumulated += estimateTokens(history[index].content)
                keepFrom = index
                if (accumulated >= retain) break
            }
            // 不切开工具对：保留段不能以 tool 行开头
            while (keepFrom < history.size && history[keepFrom].role == "tool") keepFrom++
            return slice(history, floor, keepFrom)
        }

        /**
         * overflow 强制压缩的区间选择（dsh 的 `selectCompactableRange(..., retainTokens = 0)`）：
         * 绕过阈值与「16% 保留尾巴」，只留下最后一条及其配对（不切开工具调用/结果对）。
         *
         * 最后一条若是工具结果，边界前移到它的 assistant 调用 —— 最新的工具结果必须留在模型眼前，
         * 否则重试的请求会看不到上一步刚跑出来的内容。
         */
        fun selectForced(history: List<MessageEntity>): CompactionRange? {
            if (history.isEmpty()) return null
            val floor = firstNonSystemIndex(history) ?: return null
            var keepFrom = history.size - 1
            while (keepFrom > floor && history[keepFrom].role == "tool") keepFrom--
            return slice(history, floor, keepFrom)
        }

        /** 第一个不属于系统节点的行号；全是系统节点时返回 null。 */
        private fun firstNonSystemIndex(history: List<MessageEntity>): Int? {
            val index = history.indexOfFirst { it.role != "sysprompt" && it.role != "context" }
            return if (index < 0) null else index
        }

        private fun slice(history: List<MessageEntity>, floor: Int, keepFrom: Int): CompactionRange? {
            if (keepFrom <= floor) return null
            val older = history.subList(floor, keepFrom)
            // 只剩工具结果/上下文行的区间不值得摘要（dsh 的 selectCompactableRange 也会空跑）
            if (older.none { it.role == "user" || it.role == "assistant" }) return null
            return CompactionRange(
                prefix = history.subList(0, floor).toList(),
                older = older.toList(),
                keep = history.subList(keepFrom, history.size).toList(),
            )
        }
    }
}
