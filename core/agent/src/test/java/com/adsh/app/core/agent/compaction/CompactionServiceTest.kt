package com.adsh.app.core.agent.compaction

import com.adsh.app.core.agent.FakeTurnLlm
import com.adsh.app.core.agent.FakeTurnSettings
import com.adsh.app.core.agent.FakeTurnStore
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ChatEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 压缩服务的三条触发语义：
 *  - Manual：与迁移前的 /compact 逐字一致（保留 16%、检查点格式、摘要请求形状），失败回滚；
 *  - Pressure：先剪枝、仍超阈值才摘要；剪枝落地就是 applied + progress；
 *  - Overflow：忽略阈值强制压一次；剪枝落地后摘要失败也返回 progress（durable progress 规则）。
 */
class CompactionServiceTest {


    private fun row(role: String, content: String, name: String? = null): MessageEntity =
        MessageEntity(conversationId = 1L, role = role, content = content, name = name, createdAt = 0)

    private suspend fun seed(store: FakeTurnStore, vararg rows: MessageEntity) {
        rows.forEach { row ->
            store.addMessage(
                conversationId = row.conversationId,
                role = row.role,
                content = row.content,
                name = row.name,
            )
        }
    }

    private fun service(
        store: FakeTurnStore,
        llm: FakeTurnLlm,
        settings: FakeTurnSettings = FakeTurnSettings(),
    ): CompactionService = CompactionService(
        store = store,
        settings = settings,
        summaryLlm = llm,
        systemPrompt = { "SYS-PROMPT" },
    )

    private fun prunedText(content: String): String =
        content.substring(0, ToolResultPruner.HEAD_CODE_POINTS) +
            ToolResultPruner.PRUNE_MARKER +
            content.substring(content.length - ToolResultPruner.TAIL_CODE_POINTS)

    /** 手动压缩的黄金数据：window = 1000 → 保留尾巴 160 token = 最后两行（各 100 token）。 */
    private suspend fun manualSeed(store: FakeTurnStore) = seed(
        store,
        row("sysprompt", "SYSTEM PROMPT"),
        row("context", "SNAPSHOT"),
        row("user", "first task"),
        row("assistant", "did it"),
        row("tool", "output here", name = "run_code"),
        row("user", "x".repeat(400)),
        row("assistant", "y".repeat(400)),
    )

    @Test
    fun `manual 与迁移前 compact 逐字一致`() = runBlocking {
        val store = FakeTurnStore()
        manualSeed(store)
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("  SUM"), ChatEvent.Delta("MARY  ")))

        val result = service(store, llm).compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Manual)

        assertEquals(CompactionResult(applied = true, progress = true), result)
        assertEquals(1, llm.callCount)
        // 摘要请求 = 系统提示词 + 「转录 + --- + 摘要指令」一条 user 消息（旧 ChatViewModel.summarize 的形状）
        val request = llm.lastRequest
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertEquals("SYS-PROMPT", request.messages[0].textContent)
        val expectedTranscript =
            "### USER\nfirst task\n\n" +
                "### ASSISTANT\ndid it\n\n" +
                "### TOOL RESULT (run_code)\noutput here\n\n"
        assertEquals(
            expectedTranscript + "\n---\n\n" + CompactionPrompts.COMPACTION_INSTRUCTION,
            request.messages[1].textContent,
        )
        // 落库形状：系统节点前缀 + 一条检查点 + 保留尾巴
        val rows = store.rows(1L)
        assertEquals(listOf("sysprompt", "context", "user", "user", "assistant"), rows.map { it.role })
        assertEquals("SYSTEM PROMPT", rows[0].content)
        assertEquals("SNAPSHOT", rows[1].content)
        assertEquals(ConversationRepository.COMPACT_MARKER, rows[2].name)
        assertEquals(CompactionPrompts.checkpoint("SUMMARY"), rows[2].content)
        assertEquals("x".repeat(400), rows[3].content)
        assertEquals("y".repeat(400), rows[4].content)
    }

    @Test
    fun `manual 空摘要抛原文案、会话一行不动`() = runBlocking {
        val store = FakeTurnStore()
        manualSeed(store)
        val before = store.rows(1L).map { it.role to it.content }
        val llm = FakeTurnLlm(listOf(emptyList<ChatEvent>()))

        try {
            service(store, llm).compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Manual)
            fail("空摘要必须抛异常")
        } catch (e: IllegalStateException) {
            assertEquals(CompactionService.NO_SUMMARY_MESSAGE, e.message)
        }
        assertEquals(before, store.rows(1L).map { it.role to it.content })
    }

    @Test
    fun `manual 摘要请求失败抛模型错误、会话一行不动`() = runBlocking {
        val store = FakeTurnStore()
        manualSeed(store)
        val before = store.rows(1L).map { it.role to it.content }
        val llm = FakeTurnLlm(listOf(ChatEvent.Failed("HTTP 500：boom")))

        try {
            service(store, llm).compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Manual)
            fail("摘要失败必须抛异常")
        } catch (e: IllegalStateException) {
            assertEquals("HTTP 500：boom", e.message)
        }
        assertEquals(before, store.rows(1L).map { it.role to it.content })
    }

    @Test
    fun `窗口未知或没有可压区间时什么都不做`() = runBlocking {
        val llm = FakeTurnLlm(listOf(emptyList<ChatEvent>()))
        val empty = FakeTurnStore()
        assertEquals(
            CompactionResult(applied = false, progress = false),
            service(empty, llm).compact(1L, window = 0L, planMode = false, trigger = CompactionTrigger.Manual),
        )
        assertEquals(0, llm.callCount)

        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYSTEM"), row("user", "only one"))
        assertEquals(
            CompactionResult(applied = false, progress = false),
            service(store, llm).compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Manual),
        )
        assertEquals(0, llm.callCount)
    }

    @Test
    fun `pressure 未到阈值不读模型、不动历史`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "x".repeat(400)),
            row("assistant", "y".repeat(400)),
        )
        val settings = FakeTurnSettings().apply { window = 1000L }
        val before = store.rows(1L).map { it.role to it.content }
        val llm = FakeTurnLlm(listOf(emptyList<ChatEvent>()))

        val result = service(store, llm, settings)
            .compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Pressure)

        assertEquals(CompactionResult(applied = false, progress = false), result)
        assertEquals(0, llm.callCount)
        assertEquals(before, store.rows(1L).map { it.role to it.content })
    }

    @Test
    fun `pressure 剪枝后低于阈值就不再摘要`() = runBlocking {
        val store = FakeTurnStore()
        val big = "T".repeat(40_000)
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "task"),
            row("assistant", "ok"),
            row("tool", big, name = "run_code"),
            row("user", "u".repeat(4000)),
            row("assistant", "a".repeat(4000)),
            row("assistant", "b".repeat(4000)),
        )
        val idsBefore = store.rows(1L).map { it.id }
        val llm = FakeTurnLlm(listOf(emptyList<ChatEvent>()))
        // window = 10000 → 阈值 8000 token：剪枝前约 13000，剪枝后约 4300
        val result = service(store, llm)
            .compact(1L, window = 10_000L, planMode = false, trigger = CompactionTrigger.Pressure)

        assertEquals(CompactionResult(applied = true, progress = true), result)
        assertEquals("剪枝不需要模型", 0, llm.callCount)
        val rows = store.rows(1L)
        assertEquals("行数与 id 都不变（就地替换、不重排）", idsBefore, rows.map { it.id })
        assertEquals(prunedText(big), rows[3].content)
        assertEquals("u".repeat(4000), rows[4].content)
    }

    @Test
    fun `pressure 剪枝后仍超阈值才摘要`() = runBlocking {
        val store = FakeTurnStore()
        val big = "T".repeat(40_000)
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "task"),
            row("assistant", "A".repeat(24_000)),
            row("tool", big, name = "run_code"),
            row("user", "u".repeat(4000)),
            row("assistant", "a".repeat(4000)),
            row("assistant", "b".repeat(4000)),
        )
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("SUM")))

        val result = service(store, llm)
            .compact(1L, window = 10_000L, planMode = false, trigger = CompactionTrigger.Pressure)

        assertEquals(CompactionResult(applied = true, progress = true), result)
        assertEquals(1, llm.callCount)
        // 摘要请求看到的是剪枝后的内容（确定性 head/tail + 标记）
        val transcript = llm.lastRequest.messages[1].textContent
        assertTrue(transcript.contains(ToolResultPruner.PRUNE_MARKER))
        assertFalse(transcript.contains("T".repeat(20_000)))
        val rows = store.rows(1L)
        assertEquals(listOf("sysprompt", "user", "assistant", "assistant"), rows.map { it.role })
        assertEquals(ConversationRepository.COMPACT_MARKER, rows[1].name)
    }

    @Test
    fun `overflow 忽略阈值强制压一次`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "a"),
            row("assistant", "b"),
            row("user", "c"),
            row("assistant", "last"),
        )
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("SUM")))
        // 远低于阈值也照样压（模型已经报过上下文超限）
        val result = service(store, llm)
            .compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Overflow)

        assertEquals(CompactionResult(applied = true, progress = true), result)
        assertEquals(1, llm.callCount)
        val rows = store.rows(1L)
        assertEquals(listOf("sysprompt", "user", "assistant"), rows.map { it.role })
        assertEquals(ConversationRepository.COMPACT_MARKER, rows[1].name)
        assertEquals("last", rows[2].content)
    }

    @Test
    fun `overflow 剪枝落地后摘要失败仍算 progress`() = runBlocking {
        val store = FakeTurnStore()
        val big = "T".repeat(40_000)
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "task"),
            row("tool", big, name = "run_code"),
            row("assistant", "last"),
        )
        val llm = FakeTurnLlm(listOf(ChatEvent.Failed("boom")))

        val result = service(store, llm)
            .compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Overflow)

        // dsh：摘要阶段失败但剪枝已经落地 = durable progress，不丢这次重试机会
        assertEquals(CompactionResult(applied = true, progress = true), result)
        val rows = store.rows(1L)
        assertEquals(4, rows.size)
        assertEquals(prunedText(big), rows[2].content)
        assertTrue(rows.none { it.name == ConversationRepository.COMPACT_MARKER })
    }

    @Test
    fun `overflow 没有剪枝进展时摘要失败原样抛出`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "task"),
            row("assistant", "last"),
        )
        val llm = FakeTurnLlm(listOf(ChatEvent.Failed("boom")))

        try {
            service(store, llm)
                .compact(1L, window = 1000L, planMode = false, trigger = CompactionTrigger.Overflow)
            fail("没有 progress 时必须保留原始失败")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `压力阈值与保留位数按窗口比例算`() {
        val store = FakeTurnStore()
        val llm = FakeTurnLlm(listOf(emptyList<ChatEvent>()))
        val service = service(store, llm)
        assertEquals(800L, service.pressureThreshold(1000L))
        assertEquals(16L, CompactionRange.retainTokens(100L))
        assertEquals(1L, CompactionRange.retainTokens(1L))
        assertEquals(0L, CompactionRange.retainTokens(0L))
    }
}
