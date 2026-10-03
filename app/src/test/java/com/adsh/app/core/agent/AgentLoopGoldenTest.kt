package com.adsh.app.core.agent

import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ChatMessage
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.StreamOptions
import com.adsh.app.core.llm.TurnUsage
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionLog
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoop 的**黄金基线**（甲方案第 1 步）：整条回合在纯 JVM、无网络、无 Android 运行时下
 * 跑完，把「今天的真实行为」逐项钉住 —— 后面拆 `runTurnBody` 那个 391 行的函数时，
 * 这些断言就是「拆完还是同一件事」的判据。
 *
 * 断言值全部来自**先跑一遍打印实际行为**（探针），不是照着代码推的；任何一条与今天的行为不符
 * 都说明重构改了语义，而不是「测试写错了」。
 *
 * 读法（三个替身见 [FakeTurnLlm] / [FakeTurnStore] / [FakeTurnSettings]）：
 *  - ① = 模型收到的请求；② = 库里的行；③ = 助手正文；④ = 返回的事件流。
 *
 * **时间字段（runMillis / ttftMillis / tps / SessionEvent.at）是墙钟**，只能断言 `>= 0` /
 * 不存在的字段 —— 这是仅有的「不精确」处，别的地方都是逐字相等。
 *
 * 一律 `toolContext = null`：PTC 没开（请求里没有 tools）、也不构造 [com.adsh.app.core.tools.ToolContext]
 * （它要 Android 类，纯 JVM 单测里会 "not mocked"）。
 */
class AgentLoopGoldenTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 轮首那一行上的用量 JSON → TurnUsage（时间字段以外都是定值） */
    private fun usageOf(row: MessageEntity): TurnUsage =
        json.decodeFromString(TurnUsage.serializer(), row.usageJson ?: "")

    /** wire 消息的正文（member 属性，取 JsonPrimitive 的字符串值） */
    private fun texts(request: ChatRequest): List<String> = request.messages.map { it.textContent }

    /**
     * 会话日志不变量（spec-log §7.2）：真机上是 debug 包的 build 自检（它走 android.util.Log，
     * 纯 JVM 单测里调不得 —— 见 AgentLoop.send 的注释），这里改成直接断言。
     * 四条黄金路径的日志都必须自洽，否则界面会「少一行」而这种缺失没有任何别的测试会拦。
     */
    private fun assertLogInvariants(events: List<ChatEvent>) {
        val sessionEvents = events.filterIsInstance<ChatEvent.Appended>().map { it.event }
        assertEquals(emptyList<String>(), SessionLog.violations(sessionEvents))
    }

    // ------------------------------------------------------------------ 用例 A

    /**
     * 用例 A（黄金路径：纯文本一轮）。
     *
     * 脚本 = 思考一段 + 正文两段 + 一份 usage；模型不再调用工具（`toolContext = null` 时
     * [turnEndsAfterStep] 恒为 true），于是这一轮只有一次请求、一条 assistant 行。
     */
    @Test
    fun plainTextTurnIsTheGoldenBaseline() = runBlocking {
        val llm = FakeTurnLlm(
            listOf(
                ChatEvent.Reasoning("先想一下。"),
                ChatEvent.Delta("你好，"),
                ChatEvent.Delta("我是 ADSH。"),
                ChatEvent.Usage(
                    promptTokens = 12,
                    completionTokens = 5,
                    cacheHitTokens = 4,
                    cacheMissTokens = 8,
                    reasoningTokens = 2,
                ),
            ),
        )
        val store = FakeTurnStore()
        val settings = FakeTurnSettings()

        val events = AgentLoop(llm, store, settings).send(1L, "你好", toolContext = null).toList()
        assertLogInvariants(events)

        // ---------------- ① 模型收到的请求 ----------------

        assertEquals("只发了一次请求", 1, llm.callCount)
        val request = llm.lastRequest
        assertEquals("deepseek-flash", request.model)
        assertTrue(request.stream)
        // FakeTurnSettings.reasoningEffort = "" → dsh 的 resolveThinking：两个字段都不发
        assertNull(request.reasoningEffort)
        assertNull(request.thinking)
        // PTC 没开（toolContext = null）→ wire 上没有 tools 数组
        assertNull(request.tools)
        assertEquals(StreamOptions(includeUsage = true), request.streamOptions)
        // 顺序与 role：系统提示词一条 + 用户消息一条（这一轮没调 recordContext，库里没有 context 节点）
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertEquals(
            "系统提示词 = 装配器当场重建的稳定段（没有自定义后缀时逐字等于 STATIC_SYSTEM_PROMPT）",
            PromptAssembler.STATIC_SYSTEM_PROMPT,
            request.messages[0].textContent,
        )
        assertTrue(
            "系统提示词以身份句开头",
            request.messages[0].textContent.startsWith(PromptAssembler.HARNESS_IDENTITY),
        )
        // 用户消息：wire 上只有 role + 正文（name / tool_calls / reasoning_content 都不带）
        assertEquals(ChatMessage(role = "user", content = JsonPrimitive("你好")), request.messages[1])

        // ---------------- ④ 返回的事件流（顺序 = 日志 append 顺序）----------------

        assertEquals("事件数：Reasoning + 2×Delta + Usage + Appended + Stats", 6, events.size)
        assertEquals(ChatEvent.Reasoning("先想一下。"), events[0])
        assertEquals(ChatEvent.Delta("你好，"), events[1])
        assertEquals(ChatEvent.Delta("我是 ADSH。"), events[2])
        assertEquals(
            ChatEvent.Usage(
                promptTokens = 12,
                completionTokens = 5,
                cacheHitTokens = 4,
                cacheMissTokens = 8,
                reasoningTokens = 2,
            ),
            events[3],
        )
        // 一步定稿进日志（界面的流式尾巴在这里交班给库里那一行）
        val appended = events[4] as ChatEvent.Appended
        assertEquals(0L, appended.event.seq)
        assertEquals(
            SessionBody.Step(text = "你好，我是 ADSH。", reasoning = "先想一下。", interrupted = false),
            appended.event.body,
        )
        val stats = (events[5] as ChatEvent.Stats).stats
        assertEquals("轮数在写用户行之前数：这一轮还没算进去", 0, stats.turns)
        assertEquals("这一轮没有工具调用", 0, stats.steps)
        assertEquals(12L, stats.promptTokens)
        assertEquals(5L, stats.completionTokens)
        assertEquals(4L, stats.cacheHitTokens)
        assertEquals(8L, stats.cacheMissTokens)
        assertEquals(0L, stats.toolMillis)
        assertEquals("出现过一次首 token", 1, stats.ttftSamples)
        assertTrue("llmMillis 是墙钟", stats.llmMillis >= 0)
        assertTrue("ttftMillis 是墙钟", stats.ttftMillis >= 0)

        // ---------------- ② 库里的行 ----------------

        assertEquals(listOf("user|-|你好", "assistant|-|你好，我是 ADSH。"), store.shape(1L))
        val rows = store.rows(1L)
        assertEquals(2, rows.size)

        val user = rows[0]
        assertEquals(1L, user.id)
        assertEquals("user", user.role)
        assertNull("人类发言不带 name", user.name)
        assertEquals("你好", user.content)
        assertNull("没有附件时 attachmentsJson 不写", user.attachmentsJson)
        assertNull("本轮没有文件改动 → encodeFileChanges([]) = null", user.changesJson)
        assertTrue("轮首那一行拿到了用量", user.usageJson != null)

        val assistant = rows[1]
        assertEquals(2L, assistant.id)
        assertEquals("assistant", assistant.role)
        assertNull(assistant.name)
        // ③ 助手正文 = 脚本里两段 Delta 拼起来，思考 = Reasoning 拼起来
        assertEquals("你好，我是 ADSH。", assistant.content)
        assertEquals("先想一下。", assistant.reasoning)
        assertNull("没有工具调用就不写 toolCallsJson", assistant.toolCallsJson)
        assertNull(assistant.toolCallId)
        assertNull(assistant.subCallsJson)
        assertNull("助手行上没有用量（用量在轮首那一行）", assistant.usageJson)
        assertEquals(0L, assistant.durationMs)

        // 本轮的用量落在**轮首**那一行（dsh 的 turn tokenUsage）
        val usage = usageOf(user)
        assertEquals(BuiltInProviders.DEEPSEEK_NAME, usage.provider)
        assertEquals("deepseek-flash", usage.model)
        assertEquals(12L, usage.uncachedInputTokens)
        assertEquals(4L, usage.cacheReadTokens)
        assertEquals("DeepSeek 没有单独的缓存写入计费", 0L, usage.cacheWriteTokens)
        assertEquals(5L, usage.outputTokens)
        assertEquals(2L, usage.reasoningTokens)
        assertTrue("runMillis 是墙钟", usage.runMillis >= 0)
        assertTrue("ttftMillis 是墙钟", usage.ttftMillis >= 0)
        assertTrue("tps 由墙钟算出", usage.tps >= 0.0)

        // toolContext = null → currentWorkspace 为 null → 写回的也是 null
        assertNull(settings.lastWorkspacePath)
        assertEquals("用量 / 改动都打在真实存在的那一行上", emptyList<Long>(), store.missedUpdateIds)
    }

    // ------------------------------------------------------------------ 用例 B

    /**
     * 用例 B（失败轮）：脚本只吐一条 [ChatEvent.Failed]。
     *
     * 今天的行为：失败**只走事件、不落行**（空回复被 [hasAssistantContent] 挡住），流结束之后
     * 循环照常收尾 —— 所以库里只剩用户那一行，且轮首那一行会被写上一份「全 0」的用量。
     * 失败文案去哪了：它在事件流里，由界面显示；下一次请求的历史里没有它。
     */
    @Test
    fun failedRoundLeavesOnlyTheUserRow() = runBlocking {
        val llm = FakeTurnLlm(listOf(ChatEvent.Failed("测试失败")))
        val store = FakeTurnStore()
        val settings = FakeTurnSettings()

        val events = AgentLoop(llm, store, settings).send(1L, "你好", toolContext = null).toList()
        assertLogInvariants(events)

        // 失败这一步不重发（可重试的失败走 LlmClient 的 Reconnecting 那条路，不经过这里）
        assertEquals(1, llm.callCount)
        assertEquals(listOf("system", "user"), llm.lastRequest.messages.map { it.role })
        assertEquals(listOf(PromptAssembler.STATIC_SYSTEM_PROMPT, "你好"), texts(llm.lastRequest))

        // 事件流：Failed → 一条**空**的 Step → Stats
        assertEquals(3, events.size)
        assertEquals(ChatEvent.Failed("测试失败"), events[0])
        val appended = events[1] as ChatEvent.Appended
        assertEquals(0L, appended.event.seq)
        assertEquals(
            "空回复也进日志（被挡住的只是落库）",
            SessionBody.Step(text = "", reasoning = "", interrupted = false),
            appended.event.body,
        )
        val stats = (events[2] as ChatEvent.Stats).stats
        assertEquals(0, stats.turns)
        assertEquals(0, stats.steps)
        assertEquals(0L, stats.promptTokens)
        assertEquals(0L, stats.completionTokens)
        assertEquals(0L, stats.cacheHitTokens)
        assertEquals(0L, stats.cacheMissTokens)
        assertEquals(0L, stats.toolMillis)
        assertEquals(0L, stats.ttftMillis)
        assertEquals("一个 token 都没来", 0, stats.ttftSamples)
        assertTrue("llmMillis 是墙钟", stats.llmMillis >= 0)

        // 库里只有用户那一行：失败没有被落成任何一行
        assertEquals(listOf("user|-|你好"), store.shape(1L))
        val user = store.rows(1L).single()
        assertEquals(1L, user.id)
        assertNull(user.attachmentsJson)
        assertNull(user.changesJson)
        // 轮尾照样收口：用量写在轮首那一行上，但每个 token 字段都是 0
        val usage = usageOf(user)
        assertEquals(BuiltInProviders.DEEPSEEK_NAME, usage.provider)
        assertEquals("deepseek-flash", usage.model)
        assertEquals(0L, usage.uncachedInputTokens)
        assertEquals(0L, usage.cacheReadTokens)
        assertEquals(0L, usage.outputTokens)
        assertEquals(0L, usage.reasoningTokens)
        assertTrue(usage.runMillis >= 0)
        assertEquals(emptyList<Long>(), store.missedUpdateIds)
    }

    // ------------------------------------------------------------------ 用例 C

    /**
     * 用例 C（历史节点的取舍）：[RequestMessages.build] 对每一种行的态度。
     *
     * 预置一段「每种行都有」的历史，再发一条消息，看它怎么进请求：
     *  - sysprompt / command / form = notice 的 context 行 → **展示节点，不进请求**；
     *  - form = snapshot 的 context 行 → 作为一条 **user** 消息进请求（dsh 的 runtime-context）；
     *  - 纯文本 assistant 行 → 进请求，带 reasoning_content；
     *  - 孤儿 tool 行（前面没有带 tool_calls 的 assistant）→ **丢弃**；
     *  - 带 name 的 user 行（插话）→ 进请求，但**不是轮首**（用量不落在它上面）。
     */
    @Test
    fun displayNodesAreSkippedAndContextNodesGoToTheWire() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(1L, "sysprompt", "SYS-PROMPT-ROW")
        store.addMessage(1L, "context", "SNAP-ROW", subCallsJson = PromptAssembler.FORM_SNAPSHOT)
        store.addMessage(1L, "context", "DISPLAY-ROW", subCallsJson = PromptAssembler.FORM_NOTICE)
        store.addMessage(1L, "command", "/plan")
        store.addMessage(1L, "assistant", "旧回答", reasoning = "旧思考")
        store.addMessage(1L, "tool", "孤儿工具结果", name = "run_code", toolCallId = "call_x")
        store.addMessage(1L, "user", "插话", name = ConversationRepository.STEERING)
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("好的")))
        val settings = FakeTurnSettings()

        val events = AgentLoop(llm, store, settings).send(1L, "第二条", toolContext = null).toList()
        assertLogInvariants(events)

        val request = llm.lastRequest
        assertEquals(
            listOf("system", "user", "assistant", "user", "user"),
            request.messages.map { it.role },
        )
        assertEquals(
            listOf(PromptAssembler.STATIC_SYSTEM_PROMPT, "SNAP-ROW", "旧回答", "插话", "第二条"),
            texts(request),
        )
        assertEquals("assistant 行上的 reasoning 原样回传", "旧思考", request.messages[2].reasoningContent)
        assertNull("没有 tool_calls 的 assistant 后面不补 tool 结果", request.messages[2].toolCalls)

        // 落库：预置 7 行 + 这一条用户消息 + 这一轮助手行
        val rows = store.rows(1L)
        assertEquals(9, rows.size)
        assertEquals(listOf(8L, 9L), rows.takeLast(2).map { it.id })
        assertEquals("第二条", rows[7].content)
        assertNotNull("用量落在真正的轮首（name = null 的 user 行）", rows[7].usageJson)
        assertNull("插话行（id = 7）不是轮首，不写用量", rows[6].usageJson)
        assertEquals("好的", rows[8].content)
        assertNull(rows[8].reasoning)

        assertEquals("Delta + 一条定稿的 Step + Stats", 3, events.size)
        assertEquals(ChatEvent.Delta("好的"), events[0])
        val appended = events[1] as ChatEvent.Appended
        assertEquals(SessionBody.Step(text = "好的", reasoning = "", interrupted = false), appended.event.body)
        val stats = (events[2] as ChatEvent.Stats).stats
        assertEquals("预置历史里没有 name = null 的 user 行，这一轮还没算进去", 0, stats.turns)
        assertEquals(emptyList<Long>(), store.missedUpdateIds)
    }

    // ------------------------------------------------------------------ 用例 D

    /**
     * 用例 D（收件箱）：待注入的行在**第一步开头**被认领。
     *
     * 今天的行为：认领发生在装配请求之前，所以插话**第一次请求就带上了**，不需要为此多跑一步
     * （收尾处那句 `hasInjected → continue` 管的是「这一步期间才到达」的行）；
     * 认领事件排在所有正文增量之前。
     */
    @Test
    fun aPendingInjectionIsClaimedIntoTheFirstStep() = runBlocking {
        val store = FakeTurnStore()
        store.enqueueInjected(1L, "插话内容", name = ConversationRepository.STEERING)
        val llm = FakeTurnLlm(listOf(ChatEvent.Delta("第一段")))
        val settings = FakeTurnSettings()

        val events = AgentLoop(llm, store, settings).send(1L, "你好", toolContext = null).toList()
        assertLogInvariants(events)

        assertEquals("认领在第一步开头，所以只发一次请求", 1, llm.callCount)
        assertEquals(listOf("system", "user", "user"), llm.lastRequest.messages.map { it.role })
        assertEquals(
            listOf(PromptAssembler.STATIC_SYSTEM_PROMPT, "你好", "插话内容"),
            texts(llm.lastRequest),
        )
        assertEquals(ChatEvent.InboxClaimed, events[0])
        assertEquals(
            listOf(
                "user|-|你好",
                "user|steering|插话内容",
                "assistant|-|第一段",
            ),
            store.shape(1L),
        )
        assertTrue("认领即消费，收件箱空了", !store.hasInjected(1L))
    }

    // ------------------------------------------------------------------ 用例 E

    /**
     * 用例 E（掉线重连）：[ChatEvent.StreamReset] 要把上一次尝试攒下的正文与思考**全部作废**。
     *
     * 补这条是因为审查时做了一次变异测试：把 collectRound 里 StreamReset 的 reasoning 清空删掉，
     * **全套 491 个用例一个都没响** —— 网有这个洞。重连 = 重新生成这一步，不清就会把两次尝试的
     * 输出首尾相接，拼出一段模型没说过的话。
     */
    @Test
    fun streamResetDiscardsTheAbandonedAttempt() = runBlocking {
        val llm = FakeTurnLlm(
            listOf(
                ChatEvent.Delta("半句"),
                ChatEvent.Reasoning("想一半"),
                ChatEvent.StreamReset,
                ChatEvent.Delta("完整回答"),
                ChatEvent.Reasoning("想完了"),
            ),
        )
        val store = FakeTurnStore()

        val events = AgentLoop(llm, store, FakeTurnSettings()).send(1L, "你好", toolContext = null).toList()
        assertLogInvariants(events)

        // 库里那一行只留重连之后的正文与思考：两次尝试**不许**首尾相接
        assertEquals(listOf("user|-|你好", "assistant|-|完整回答"), store.shape(1L))
        assertEquals("想完了", store.rows(1L).last().reasoning)
    }
}
