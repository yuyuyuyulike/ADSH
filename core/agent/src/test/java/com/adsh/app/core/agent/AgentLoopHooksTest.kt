package com.adsh.app.core.agent

import com.adsh.app.core.agent.compaction.CompactionHooks
import com.adsh.app.core.agent.compaction.CompactionService
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.CONTEXT_WINDOW_EXCEEDED
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.CompactionCause
import com.adsh.app.core.session.SessionLog
import com.adsh.app.core.tools.ToolOutcome
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.ptc.SubCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 决策点接进主循环之后的**行为矩阵**（自动压缩的事件流与重试预算）：
 *  - 非 overflow 失败：决策点看到失败但无权接管，Failed 原样透传、本轮按空 step 收尾；
 *  - overflow + 有 durable progress：同一 step 内重试一次，失败事件不透传；
 *  - overflow 无 progress：不重试，保留原始失败；
 *  - 每轮最多重试一次；
 *  - pressure 在装配请求**之前**触发，请求里看到的是剪枝后的历史；
 *  - [AgentHooks.beforeStep] 返回 false 时本步不开；
 *  - 工具轮的 subCalls / steps 从 [ToolOutcome] 落库（不再有全局通道），取消照旧原样抛。
 */
class AgentLoopHooksTest {


    private val workspace = Workspace(WorkspaceForm.SAF_REFERENCE, File("/nonexistent"), "测试工作区")

    private fun row(role: String, content: String, name: String? = null): MessageEntity =
        MessageEntity(conversationId = 1L, role = role, content = content, name = name, createdAt = 0)

    private suspend fun seed(store: FakeTurnStore, vararg rows: MessageEntity) {
        rows.forEach { row ->
            store.addMessage(
                conversationId = row.conversationId,
                role = row.role,
                content = row.content,
                reasoning = row.reasoning,
                toolCallsJson = row.toolCallsJson,
                toolCallId = row.toolCallId,
                name = row.name,
            )
        }
    }

    /** 一条带 tool_calls 的 assistant 行：工具结果必须挂在它的调用 id 上才会进请求。 */
    private fun assistantToolCall(callId: String, argsJson: String = "{}"): MessageEntity =
        MessageEntity(
            conversationId = 1L,
            role = "assistant",
            content = "",
            toolCallsJson = "[{\"id\":\"" + callId + "\",\"type\":\"function\"," +
                "\"function\":{\"name\":\"run_code\",\"arguments\":\"" + argsJson.replace("\"", "\\\"") + "\"}}]",
            createdAt = 0,
        )

    private fun settings(window: Long = 1_000_000L): FakeTurnSettings =
        FakeTurnSettings().apply { this.window = window }

    private fun hooks(
        store: FakeTurnStore,
        settings: FakeTurnSettings,
        summaryLlm: FakeTurnLlm,
    ): CompactionHooks = CompactionHooks(
        CompactionService(
            store = store,
            settings = settings,
            summaryLlm = summaryLlm,
            systemPrompt = { "SYS-PROMPT" },
        ),
    )

    private fun emptySummaryLlm(): FakeTurnLlm = FakeTurnLlm(listOf(emptyList<ChatEvent>()))

    private fun assertLogInvariants(events: List<ChatEvent>) {
        val sessionEvents = events.filterIsInstance<ChatEvent.Appended>().map { it.event }
        assertEquals(emptyList<String>(), SessionLog.violations(sessionEvents))
    }

    @Test
    fun `非 overflow 失败：决策点看到失败但无权接管、事件原样透传`() = runBlocking {
        val store = FakeTurnStore()
        val seen = ArrayList<ChatEvent.Failed>()
        val recording = object : AgentHooks {
            override suspend fun onRequestError(
                conversationId: Long,
                failure: ChatEvent.Failed,
                emit: (ChatEvent) -> Unit,
            ): Boolean {
                seen += failure
                return false
            }
        }
        val failure = ChatEvent.Failed("HTTP 500：boom", "SERVER")
        val llm = FakeTurnLlm(listOf(failure))

        val events = AgentLoop(llm, store, FakeTurnSettings(), false, recording)
            .send(1L, "你好", toolContext = null)
            .toList()
        assertLogInvariants(events)

        assertEquals(listOf(failure), seen)
        assertEquals("失败事件只透传一次", 1, events.filterIsInstance<ChatEvent.Failed>().size)
        assertEquals(1, llm.callCount)
        // 空 step 收尾：只有用户行，没有 assistant 行
        assertEquals(listOf("user"), store.rows(1L).map { it.role })
    }

    @Test
    fun `overflow 有进展时同一 step 内重试、失败事件不透传`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYS"), row("user", "old task"), row("assistant", "old answer"))
        val llm = FakeTurnLlm(
            listOf(ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED)),
            listOf(ChatEvent.Delta("done")),
        )
        val summaryLlm = FakeTurnLlm(listOf(ChatEvent.Delta("SUM")))
        val settings = settings()

        val events = AgentLoop(llm, store, settings, false, hooks(store, settings, summaryLlm))
            .send(1L, "new task", toolContext = null)
            .toList()
        assertLogInvariants(events)

        assertEquals("压缩后重发一次请求", 2, llm.callCount)
        assertEquals("终态失败不出现", emptyList<ChatEvent.Failed>(), events.filterIsInstance<ChatEvent.Failed>())
        assertTrue(events.contains(ChatEvent.CompactionStarted(CompactionCause.OVERFLOW)))
        assertTrue(events.contains(ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = true)))
        // 重试请求看到的是压缩后的历史（检查点行 + 保留的新用户行）
        assertTrue(
            llm.requests[1].messages.any {
                it.textContent.startsWith("This is an automatically generated checkpoint")
            },
        )
        assertEquals("done", store.rows(1L).last().content)
    }

    @Test
    fun `overflow 没有 durable progress 时不重试、保留原始失败`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYS"))
        val failure = ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED)
        val llm = FakeTurnLlm(listOf(failure))
        val summaryLlm = emptySummaryLlm()
        val settings = settings()

        val events = AgentLoop(llm, store, settings, false, hooks(store, settings, summaryLlm))
            .send(1L, "task", toolContext = null)
            .toList()

        assertEquals(1, llm.callCount)
        assertEquals(listOf(failure), events.filterIsInstance<ChatEvent.Failed>())
        assertTrue(events.contains(ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = false)))
        assertEquals("没有可压内容就不调用摘要模型", 0, summaryLlm.callCount)
    }

    @Test
    fun `每轮最多重试一次`() = runBlocking {
        val store = FakeTurnStore()
        seed(store, row("sysprompt", "SYS"), row("user", "old"), row("assistant", "old answer"))
        val llm = FakeTurnLlm(
            listOf(ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED)),
            listOf(ChatEvent.Failed("still too long", CONTEXT_WINDOW_EXCEEDED)),
            listOf(ChatEvent.Delta("never")),
        )
        val summaryLlm = FakeTurnLlm(listOf(ChatEvent.Delta("SUM")))
        val settings = settings()

        val events = AgentLoop(llm, store, settings, false, hooks(store, settings, summaryLlm))
            .send(1L, "task", toolContext = null)
            .toList()

        assertEquals("第二次失败不再重试", 2, llm.callCount)
        assertEquals("压缩只跑一次", 1, summaryLlm.callCount)
        assertEquals("still too long", events.filterIsInstance<ChatEvent.Failed>().single().message)
    }

    @Test
    fun `overflow 剪枝有进展但摘要失败时仍然重试`() = runBlocking {
        val store = FakeTurnStore()
        seed(
            store,
            row("sysprompt", "SYS"),
            row("user", "old task"),
            row("tool", "T".repeat(40_000), name = "run_code"),
            row("assistant", "last assistant"),
        )
        val llm = FakeTurnLlm(
            listOf(ChatEvent.Failed("too long", CONTEXT_WINDOW_EXCEEDED)),
            listOf(ChatEvent.Delta("recovered")),
        )
        val summaryLlm = FakeTurnLlm(listOf(ChatEvent.Failed("summary model died")))
        val settings = settings()

        val events = AgentLoop(llm, store, settings, false, hooks(store, settings, summaryLlm))
            .send(1L, "task", toolContext = null)
            .toList()

        assertEquals("剪枝落地 = durable progress，重试", 2, llm.callCount)
        assertTrue(events.contains(ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = true)))
        assertEquals("recovered", store.rows(1L).last().content)
    }

    @Test
    fun `pressure 在装配请求之前压缩、请求里是剪枝后的历史`() = runBlocking {
        val store = FakeTurnStore()
        val big = "T".repeat(40_000)
        seed(
            store,
            row("user", "task"),
            assistantToolCall("call_1"),
            MessageEntity(
                conversationId = 1L,
                role = "tool",
                content = big,
                toolCallId = "call_1",
                name = "run_code",
                createdAt = 0,
            ),
            row("user", "u".repeat(4000)),
            row("assistant", "a".repeat(4000)),
            row("assistant", "b".repeat(4000)),
        )
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("ok")))
        val summaryLlm = emptySummaryLlm()
        // window = 10000 → 阈值 8000 token；剪枝后约 4300，不再摘要
        val settings = settings(window = 10_000L)

        val events = AgentLoop(llm, store, settings, false, hooks(store, settings, summaryLlm))
            .send(1L, "继续", toolContext = null)
            .toList()

        val started = events.indexOfFirst { it is ChatEvent.CompactionStarted }
        val requestHeader = events.indexOfFirst { it is ChatEvent.RequestHeader }
        assertTrue("压缩中事件在请求之前", started >= 0 && requestHeader > started)
        assertEquals(CompactionCause.PRESSURE, (events[started] as ChatEvent.CompactionStarted).cause)
        assertTrue(events.contains(ChatEvent.CompactionFinished(CompactionCause.PRESSURE, applied = true)))
        assertEquals("剪枝不需要模型", 0, summaryLlm.callCount)
        val toolMessage = llm.lastRequest.messages.first { it.role == "tool" }
        assertTrue(toolMessage.textContent.contains("[... tool result middle pruned ...]"))
    }

    @Test
    fun `beforeStep 返回 false 时本步不开、本轮直接收尾`() = runBlocking {
        val store = FakeTurnStore()
        val closer = object : AgentHooks {
            override suspend fun beforeStep(conversationId: Long, emit: (ChatEvent) -> Unit): Boolean = false
        }
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("never")))

        val events = AgentLoop(llm, store, FakeTurnSettings(), false, closer)
            .send(1L, "你好", toolContext = null)
            .toList()

        assertEquals("没有发请求", 0, llm.callCount)
        assertEquals(emptyList<ChatEvent>(), events)
        assertEquals(listOf("user"), store.rows(1L).map { it.role })
    }

    @Test
    fun `工具轮的 subCalls 与 steps 从 outcome 落库、不再有全局通道`() = runBlocking {
        val store = FakeTurnStore()
        val sub = SubCall(
            name = "bash",
            args = "{}",
            ok = true,
            result = "ok",
            durationMs = 3,
            id = "1:ptc:1",
        )
        val tools = object : TurnTools {
            override val workspace: Workspace get() = this@AgentLoopHooksTest.workspace
            override suspend fun run(
                name: String,
                argsJson: String,
                callId: String?,
                execToken: Long,
            ): ToolOutcome = ToolOutcome(
                ok = true,
                text = "out",
                value = null,
                subCalls = listOf(sub),
                steps = 3,
            )
        }
        val llm = FakeTurnLlm(
            listOf(ChatEvent.ToolCallDelta(0, "call_1", "run_code", "{\"code\":\"1\"}")),
            listOf(ChatEvent.Delta("done")),
        )

        val events = AgentLoop(llm, store, FakeTurnSettings(), false)
            .sendWithTools(1L, "算一下", tools)
            .toList()
        assertLogInvariants(events)

        val toolRow = store.rows(1L).single { it.role == "tool" }
        assertNotNull("子调用轨迹落库", toolRow.subCallsJson)
        assertTrue(toolRow.subCallsJson!!.contains("\"name\":\"bash\""))
        // steps = outcome.steps（顶层 1 + 程序内子调用，由 ToolExecutor 折算好）
        assertEquals(3, events.filterIsInstance<ChatEvent.Stats>().first().stats.steps)
    }

    @Test
    fun `工具取消原样抛、不落工具行`() = runBlocking {
        val store = FakeTurnStore()
        val tools = object : TurnTools {
            override val workspace: Workspace get() = this@AgentLoopHooksTest.workspace
            override suspend fun run(
                name: String,
                argsJson: String,
                callId: String?,
                execToken: Long,
            ): ToolOutcome = throw CancellationException("stopped")
        }
        val llm = FakeTurnLlm(
            listOf(ChatEvent.ToolCallDelta(0, "call_1", "run_code", "{\"code\":\"1\"}")),
        )

        try {
            AgentLoop(llm, store, FakeTurnSettings(), false)
                .sendWithTools(1L, "算一下", tools)
                .toList()
            fail("取消必须原样抛")
        } catch (cancel: CancellationException) {
            assertEquals("stopped", cancel.message)
        }
        // 这一步的 assistant（带 tool_calls）已经落库；工具行没有写
        assertEquals(listOf("user", "assistant"), store.rows(1L).map { it.role })
    }
}
