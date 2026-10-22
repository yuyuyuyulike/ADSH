package com.adsh.app.core.agent.compaction

import com.adsh.app.core.data.MessageEntity

/**
 * 工具结果的确定性剪枝（dsh 的 compaction-tool-result-pruner）：把过长的正文换成
 * 「头 + 省略标记 + 尾」，不调模型、不改行身份（id / toolCallId / 配对关系全保留）。
 *
 * 预算逐字取自 dsh 默认配置：总长超过 8192 码点才剪；保留头 4096、尾 1024，中间用
 * [PRUNE_MARKER] 标记。只剪 `older`（可压区间）里的 `tool` 行：保留尾巴是模型最近看到的
 * 原文，动了它等于凭空改写最近的结果；系统节点更不在语义范围里。
 */
internal object ToolResultPruner {

    /** dsh 的 DEFAULTS.thresholdChars */
    const val THRESHOLD_CODE_POINTS = 8192

    /** dsh 的 DEFAULTS.headChars */
    const val HEAD_CODE_POINTS = 4096

    /** dsh 的 DEFAULTS.tailChars */
    const val TAIL_CODE_POINTS = 1024

    /** dsh 的 PRUNE_MARKER（逐字）。 */
    const val PRUNE_MARKER = "\n\n[... tool result middle pruned ...]\n\n"

    /** 一条确定性的内容替换：行 id 不变，只有 content 变短。 */
    data class Replacement(val id: Long, val content: String)

    /** 一次剪枝的结果；[replacements] 为空 = 没有需要剪的行（历史不动）。 */
    data class Result(val replacements: List<Replacement>) {
        val applied: Boolean get() = replacements.isNotEmpty()
    }

    /** 剪掉可压区间里所有超预算的工具结果（按原顺序返回替换）。 */
    fun prune(range: CompactionRange): Result {
        val replacements = ArrayList<Replacement>()
        range.older.forEach { row ->
            if (row.role != "tool") return@forEach
            val pruned = pruneContent(row.content) ?: return@forEach
            replacements += Replacement(row.id, pruned)
        }
        return Result(replacements)
    }

    /**
     * 单条正文的剪枝：不超过 [THRESHOLD_CODE_POINTS] 码点时返回 null（不动）。
     *
     * 按**码点**切（不是 UTF-16 code unit）：保留边界不会切开代理对。
     */
    fun pruneContent(text: String): String? {
        val total = text.codePointCount(0, text.length)
        if (total <= THRESHOLD_CODE_POINTS) return null
        val headEnd = text.offsetByCodePoints(0, HEAD_CODE_POINTS)
        val tailStart = text.offsetByCodePoints(text.length, -TAIL_CODE_POINTS)
        val pruned = text.substring(0, headEnd) + PRUNE_MARKER + text.substring(tailStart)
        // 替换必须更短：progress 判据靠它成立，宁可不剪也不许写出更长的内容
        return pruned.takeIf { it.codePointCount(0, it.length) < total }
    }

    /** 把一批替换应用到内存里的历史副本（行 id → 新正文）。 */
    fun apply(messages: List<MessageEntity>, replacements: List<Replacement>): List<MessageEntity> {
        if (replacements.isEmpty()) return messages
        val byId = replacements.associateBy { it.id }
        return messages.map { row -> byId[row.id]?.let { row.copy(content = it.content) } ?: row }
    }
}
