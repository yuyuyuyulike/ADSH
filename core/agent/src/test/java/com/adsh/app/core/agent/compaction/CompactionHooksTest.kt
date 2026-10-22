package com.adsh.app.core.agent.compaction

import com.adsh.app.core.agent.FakeTurnLlm
import com.adsh.app.core.agent.FakeTurnSettings
import com.adsh.app.core.agent.FakeTurnStore
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.CONTEXT_WINDOW_EXCEEDED
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.CompactionCause
import com.adsh.app.core.llm.TurnLlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 两个决策点的行为矩阵：pressure 只在超阈值时压缩并发事件；overflow 只在
 * [CONTEXT_WINDOW_EXCEEDED] 且**有 durable progress** 时要求重试；其余失败一律不接管。
 */
class CompactionHooksTest {


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

    private fun hooks(
        store: FakeTurnStore,
        llm: FakeTurnLlm,
        window: Long,
    ): CompactionHooks {
        val settings = FakeTurnSettings().apply { this.window = window }
        val service = CompactionService(
            store = store,
            settings = settings,
            summaryLlm = llm,
            systemPrompt = { "SYS" },
        )
        return CompactionHooks(service)
    }

    @Test
    fun `pressure 未达阈值时不开压缩、不发事件`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYSTEM"), row("user", "你好"), row("assistant", "在"))
        val events = ArrayList<ChatEvent>()

        val proceed = hooks(store, FakeTurnLlm(listOf(emptyList<ChatEvent>())), window = 1000L)
            .beforeStep(1L) { events += it }

        assertTrue(proceed)
        assertEquals(emptyList<ChatEvent>(), events)
        assertEquals(3, store.rows(1L).size)
    }

    @Test
    fun `pressure 超阈值时压缩并发 started finished`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "task"),
            row("assistant", "ok"),
            row("tool", "T".repeat(40_000), name = "run_code"),
            row("user", "u".repeat(4000)),
            row("assistant", "a".repeat(4000)),
            row("assistant", "b".repeat(4000)),
        )
        val events = ArrayList<ChatEvent>()

        val proceed = hooks(store, FakeTurnLlm(listOf(emptyList<ChatEvent>())), window = 10_000L)
            .beforeStep(1L) { events += it }

        assertTrue(proceed)
        assertEquals(
            listOf(
                ChatEvent.CompactionStarted(CompactionCause.PRESSURE),
                ChatEvent.CompactionFinished(CompactionCause.PRESSURE, applied = true),
            ),
            events,
        )
        assertTrue(store.rows(1L)[3].content.contains(ToolResultPruner.PRUNE_MARKER))
    }

    @Test
    fun `overflow 有进展时要求同一 step 内重试`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "a"),
            row("assistant", "b"),
            row("user", "c"),
            row("assistant", "last"),
        )
        val events = ArrayList<ChatEvent>()
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("SUM")))

        val retry = hooks(store, llm, window = 1000L).onRequestError(
            conversationId = 1L,
            failure = ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED),
        ) { events += it }

        assertTrue("有 progress 才重试", retry)
        assertEquals(
            listOf(
                ChatEvent.CompactionStarted(CompactionCause.OVERFLOW),
                ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = true),
            ),
            events,
        )
        assertTrue(store.rows(1L).any { it.name == ConversationRepository.COMPACT_MARKER })
    }

    @Test
    fun `overflow 没有可压内容时不重试、仍然发结束事件`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYSTEM"), row("user", "only"))
        val events = ArrayList<ChatEvent>()

        val retry = hooks(store, FakeTurnLlm(listOf(emptyList<ChatEvent>())), window = 1000L).onRequestError(
            conversationId = 1L,
            failure = ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED),
        ) { events += it }

        assertFalse(retry)
        assertEquals(
            listOf(
                ChatEvent.CompactionStarted(CompactionCause.OVERFLOW),
                ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = false),
            ),
            events,
        )
    }

    @Test
    fun `非 overflow 失败不接管`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYSTEM"), row("user", "a"), row("assistant", "b"))
        val before = store.rows(1L).map { it.role to it.content }
        val events = ArrayList<ChatEvent>()

        val retry = hooks(store, FakeTurnLlm(listOf(emptyList<ChatEvent>())), window = 1000L).onRequestError(
            conversationId = 1L,
            failure = ChatEvent.Failed("HTTP 500", "SERVER"),
        ) { events += it }

        assertFalse(retry)
        assertEquals(emptyList<ChatEvent>(), events)
        assertEquals(before, store.rows(1L).map { it.role to it.content })
    }

    @Test
    fun `溢出压缩失败不打断本轮、返回不重试`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "task"),
            row("assistant", "last"),
        )
        val events = ArrayList<ChatEvent>()
        val llm = FakeTurnLlm(listOf(ChatEvent.Failed("boom")))

        val retry = hooks(store, llm, window = 1000L).onRequestError(
            conversationId = 1L,
            failure = ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED),
        ) { events += it }

        assertFalse(retry)
        assertEquals(
            listOf(
                ChatEvent.CompactionStarted(CompactionCause.OVERFLOW),
                ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = false),
            ),
            events,
        )
        // 会话没有被摘要替换（失败回滚）
        assertTrue(store.rows(1L).none { it.name == ConversationRepository.COMPACT_MARKER })
    }

    @Test
    fun `窗口未知时两个决策点都不做事`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYSTEM"), row("user", "a"), row("assistant", "b"))
        val events = ArrayList<ChatEvent>()
        val hooks = hooks(store, FakeTurnLlm(listOf(emptyList<ChatEvent>())), window = 0L)

        assertTrue(hooks.beforeStep(1L) { events += it })
        assertFalse(
            hooks.onRequestError(1L, ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED)) { events += it },
        )
        assertEquals(emptyList<ChatEvent>(), events)
    }

    @Test
    fun `取消原样抛、不变成压缩失败`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYSTEM"),
            row("user", "a"),
            row("assistant", "b"),
            row("user", "c"),
            row("assistant", "last"),
        )
        val events = ArrayList<ChatEvent>()
        val cancelled = object : TurnLlm {
            override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
                throw CancellationException("stopped")
            }
        }
        val service = CompactionService(
            store = store,
            settings = FakeTurnSettings().apply { window = 1000L },
            summaryLlm = cancelled,
            systemPrompt = { "SYS" },
        )

        try {
            CompactionHooks(service).onRequestError(
                1L,
                ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED),
            ) { events += it }
            fail("取消必须原样抛")
        } catch (cancel: CancellationException) {
            assertEquals("stopped", cancel.message)
        }
    }
}
