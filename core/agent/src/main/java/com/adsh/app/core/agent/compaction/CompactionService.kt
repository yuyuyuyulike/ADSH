package com.adsh.app.core.agent.compaction

import com.adsh.app.core.agent.contextMessageTokens
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.TurnSettings
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatMessage
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.TurnLlm
import com.adsh.app.core.llm.collectText
import com.adsh.app.core.llm.textContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 压缩的触发原因（对应 [com.adsh.app.core.llm.CompactionCause]，自动 / 手动同一条流水线）。 */
enum class CompactionTrigger {
    /** 用户显式 /compact（与迁移前的 ChatViewModel.compact 行为逐字一致） */
    Manual,

    /** 装配请求前的阈值压力（先剪枝，仍超阈值才摘要） */
    Pressure,

    /** 模型报上下文超限后的强制压缩（忽略阈值；先剪枝） */
    Overflow,
}

/**
 * 一次压缩的结果。
 *
 * @property applied 是否有替换真正落地（剪枝写了行 / 检查点替换了历史）。false = 会话一行没动。
 * @property progress 历史是否**确实变小**（剪枝落地或有检查点替换）。自动恢复只认这一项：
 *   没有 durable 进展的重试只是再撞一次同样的窗口。
 */
data class CompactionResult(val applied: Boolean, val progress: Boolean)

/**
 * 压缩服务（dsh 的 compaction-basic + tool-result-pruner 的原生移植）。
 *
 * 触发语义：
 *  - [CompactionTrigger.Manual]：与迁移前的 ChatViewModel.compact 逐字一致 —— 保留 window × 16% 的尾巴、
 *    系统节点不压、不切工具对，摘要成功才调用 [TurnStore.replaceHistory]；摘要失败抛异常、会话一行不动。
 *  - [CompactionTrigger.Pressure]：调用方已确认超过 window × 80%；先剪工具结果，仍超阈值才摘要
 *    （区间同 Manual）。剪枝落地后即便摘要失败也算 applied + progress（dsh 的 durable progress 规则）。
 *  - [CompactionTrigger.Overflow]：忽略阈值，强制压一次；区间用「只保留最后一条及其配对」的强制选择。
 *
 * @param summaryLlm 摘要那一次模型请求的通道（/compact 单独一条 LLM 通道，不经过工具循环）
 * @param systemPrompt 摘要请求的系统提示词（与现 ChatViewModel.summarize 的 buildParts 口径一致；
 *   装配要读指令文件链，由组合根决定线程）
 */
class CompactionService(
    private val store: TurnStore,
    private val settings: TurnSettings,
    private val summaryLlm: TurnLlm,
    private val systemPrompt: suspend (planMode: Boolean) -> String,
) {

    companion object {
        /** 压力阈值 = 窗口 × 80%（dsh 的 thresholdRatio 默认 0.8）。 */
        const val PRESSURE_PERCENT = 80L

        /** 摘要模型返回空文本时的失败文案（/compact 的界面错误在它前面加 "compaction failed: "）。 */
        const val NO_SUMMARY_MESSAGE = "the model returned no summary; the session was left unchanged"
    }

    /** 当前窗口（token）：压力阈值与保留尾巴都按它算。 */
    val contextWindow: Long get() = settings.contextWindow

    /** 压力阈值（token）；窗口未知时 0。 */
    fun pressureThreshold(window: Long): Long =
        if (window <= 0) 0 else (window * PRESSURE_PERCENT / 100).coerceAtLeast(1)

    /**
     * 装配请求前是否已到压力阈值：只读一次库、只算「真的会上 wire 的消息」那一栏
     * （[contextMessageTokens]）。未达阈值时调用方一个事件都不发。
     */
    suspend fun overPressure(conversationId: Long, window: Long = contextWindow): Boolean {
        if (window <= 0) return false
        return contextMessageTokens(store.messages(conversationId)) > pressureThreshold(window)
    }

    /**
     * 跑一次压缩。
     *
     * @param window 上下文窗口（token，调用方传入；<= 0 表示未知，直接不做事）
     * @throws IllegalStateException 摘要为空（/compact 的原文案见 [NO_SUMMARY_MESSAGE]）；
     *   摘要请求本身失败时原样抛出模型的失败
     */
    suspend fun compact(
        conversationId: Long,
        window: Long,
        planMode: Boolean,
        trigger: CompactionTrigger,
    ): CompactionResult {
        if (window <= 0) return CompactionResult(applied = false, progress = false)
        var messages = store.messages(conversationId)
        if (trigger == CompactionTrigger.Pressure &&
            contextMessageTokens(messages) <= pressureThreshold(window)
        ) {
            return CompactionResult(applied = false, progress = false)
        }
        val selected = select(messages, trigger, window)
            ?: return CompactionResult(applied = false, progress = false)

        // 第一步：确定性的工具结果剪枝。Manual 不剪 —— 它必须与迁移前的 /compact 逐字一致。
        var applied = false
        var prunedCount = 0
        if (trigger != CompactionTrigger.Manual) {
            val pruned = ToolResultPruner.prune(selected)
            if (pruned.applied) {
                pruned.replacements.forEach { store.setMessageContent(it.id, it.content) }
                messages = ToolResultPruner.apply(messages, pruned.replacements)
                applied = true
                prunedCount = pruned.replacements.size
            }
        }
        // Pressure：剪枝后已经低于阈值就不再摘要（dsh 的「剪枝后重新测量」）
        if (trigger == CompactionTrigger.Pressure && prunedCount > 0 &&
            contextMessageTokens(messages) <= pressureThreshold(window)
        ) {
            return CompactionResult(applied = true, progress = true)
        }

        // 第二步：摘要。区间在剪枝之后的同一份历史上重新选（内容变了，token 估算跟着变）。
        val current = select(messages, trigger, window)
            ?: return CompactionResult(applied = applied, progress = applied)
        val summary = try {
            summarize(CompactionPrompts.renderTranscript(current.older), planMode)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            // 剪枝已经落地 = durable progress：dsh 不因第二阶段失败丢掉它（overflow 恢复据此重试）
            if (prunedCount > 0) return CompactionResult(applied = true, progress = true)
            throw t
        }
        if (summary.isBlank()) {
            if (prunedCount > 0) return CompactionResult(applied = true, progress = true)
            // 与迁移前 /compact 的失败路径同文案：界面加 "compaction failed: " 前缀
            throw IllegalStateException(NO_SUMMARY_MESSAGE)
        }
        store.replaceHistory(
            conversationId = conversationId,
            prefix = current.prefix,
            keep = current.keep,
            checkpoint = CompactionPrompts.checkpoint(summary),
        )
        return CompactionResult(applied = true, progress = true)
    }

    private fun select(messages: List<MessageEntity>, trigger: CompactionTrigger, window: Long): CompactionRange? =
        when (trigger) {
            CompactionTrigger.Overflow -> CompactionRange.selectForced(messages)
            CompactionTrigger.Manual, CompactionTrigger.Pressure -> CompactionRange.select(messages, window)
        }

    /**
     * 一次性摘要调用（不经过工具循环）。
     *
     * dsh 的 summarizer 复用会话自己的系统提示词，只把摘要指令作为**最后一条 user 消息**
     * 跟在对话后面（这样这次调用是上一个请求的真前缀，能吃到 KV 缓存），
     * 而不是另起一个「摘要引擎」人格。
     */
    private suspend fun summarize(transcript: String, planMode: Boolean): String {
        val config = settings.providerConfig()
        // 与迁移前一致：装配系统提示词要读指令文件链，不能在主线程上做
        val system = withContext(Dispatchers.IO) { systemPrompt(planMode) }
        val request = ChatRequest(
            model = config.model,
            messages = buildList {
                if (system.isNotBlank()) {
                    add(ChatMessage(role = "system", content = textContent(system)))
                }
                add(
                    ChatMessage(
                        role = "user",
                        content = textContent(transcript + "\n---\n\n" + CompactionPrompts.COMPACTION_INSTRUCTION),
                    ),
                )
            },
            stream = true,
        )
        return summaryLlm.collectText(request)
    }
}
