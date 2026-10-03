package com.adsh.app.ui

import com.adsh.app.core.agent.AgentLoop
import com.adsh.app.core.agent.UserAttachment
import com.adsh.app.core.agent.encodeAttachments
import com.adsh.app.core.agent.estimateTokens
import com.adsh.app.core.agent.imageRouteHintText
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TurnStreamState] 的全部判定（从 ChatViewModel.startTurn 搬出来的那 104 行）。
 *
 * 这里钉的是**帧级不变量**，不是实现细节：
 *  - 开轮一次换掉 12 个字段、收尾一次收口 10 个字段（同帧，用户看到的才「不闪」）；
 *  - Appended(Step) 是流式尾巴的交班点：**同一次 update** 里换库行 + 清尾巴，
 *    所以「同一份正文既在库行里、又当流式尾巴挂着」这一帧在结构上不存在；
 *  - 事件分支的判据只看该看的东西（ToolCallDelta 不看 index/id/name/argumentsChunk）；
 *  - 除 Failed 之外没有任何分支读模型名（modelLabel 是惰性的：Delta 是每 token 一次）。
 */
class TurnStreamStateTest {

    private var seq = 0L

    private fun event(body: SessionBody, at: Long = 1000L + seq): SessionEvent =
        SessionEvent(seq = seq++, at = at, body = body)

    private fun message(
        id: Long,
        role: String,
        content: String,
        name: String? = null,
        attachmentsJson: String? = null,
    ): MessageEntity = MessageEntity(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        name = name,
        attachmentsJson = attachmentsJson,
        createdAt = 1_700_000_000_000L + id,
    )

    /** 一张图附件落库后的 JSON —— 实际值由实跑打印确认（见 [imageRouteHintTable] 的第一条断言）。 */
    private val imageJson = encodeAttachments(
        listOf(
            UserAttachment(
                path = "/ws/.adsh/attachments/1/a.png",
                name = "a.png",
                bytes = 2048L,
                mediaType = "image/png",
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                width = 1200,
                height = 800,
            ),
        ),
    )

    /** 一个**非图片**附件（文件）：带了它也不该出现「识图」指路。 */
    private val fileJson = encodeAttachments(
        listOf(
            UserAttachment(
                path = "/ws/.adsh/attachments/1/notes.md",
                name = "notes.md",
                bytes = 1234L,
                sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            ),
        ),
    )

    private fun fullStats() = SessionStats(
        turns = 3,
        steps = 7,
        promptTokens = 11L,
        completionTokens = 13L,
        cacheHitTokens = 17L,
        cacheMissTokens = 19L,
        llmMillis = 23L,
        toolMillis = 29L,
        ttftMillis = 31L,
        ttftSamples = 2,
    )

    /** 定稿前的活状态：每个字段都是「脏」的，这样复位漏了哪个都能看出来。 */
    private fun dirtyState(stats: SessionStats = fullStats()) = ChatUiState(
        conversationId = 5L,
        messages = listOf(message(1L, "user", "上一轮")),
        streaming = "上一轮的尾巴",
        reasoning = "上一轮的思考",
        reasoningRunning = true,
        toolArgsFlowing = true,
        sending = false,
        liveTurnId = 42L,
        error = "上一次的错",
        turnEvents = listOf(event(SessionBody.Step("正文", "思考"))),
        liveTurn = LiveTurn(steps = 1),
        stats = stats,
        pendingAttachments = listOf("/ws/.adsh/attachments/9.png"),
        runStartedAt = 111L,
        connection = ConnectionState.Reconnecting(3, "boom"),
    )

    @Test
    fun turnOpenedResetsTwelveFieldsAndKeepsTheSessionStats() {
        val stats = fullStats()
        val before = dirtyState(stats)
        val after = turnOpened(before, now = 999L)

        assertTrue("开轮：发送中", after.sending)
        assertNull("新一轮还没落库，这一瞬间没有活的轮", after.liveTurnId)
        assertNull(after.error)
        assertEquals("", after.streaming)
        assertEquals("", after.reasoning)
        assertFalse(after.reasoningRunning)
        assertFalse(after.toolArgsFlowing)
        assertEquals(emptyList<SessionEvent>(), after.turnEvents)
        assertEquals(LiveTurn(), after.liveTurn)
        assertEquals(emptyList<String>(), after.pendingAttachments)
        assertEquals(999L, after.runStartedAt)
        assertEquals("新的一轮：重连条从零开始", ConnectionState.Idle, after.connection)

        assertEquals("stats 是会话级累计，开轮绝不复位", stats, after.stats)
        // 开轮只动上面那 12 个字段
        assertEquals(5L, after.conversationId)
        assertEquals(before.messages, after.messages)
    }

    @Test
    fun turnIdentityPrefersTheExplicitTurnKey() {
        val before = ChatUiState(messages = listOf(message(1L, "user", "上一轮")))
        val messages = listOf(
            message(1L, "user", "上一轮"),
            message(2L, "assistant", "上一轮的答复"),
            message(3L, "user", "这一轮"),
        )
        val after = turnIdentityResolved(before, messages, turnKey = 77L)

        assertEquals("调用方点名的这一轮优先", 77L, after.liveTurnId)
        assertSame("messages 整份换新（不是并进去）", messages, after.messages)
    }

    @Test
    fun turnIdentityFallsBackToTheLastUnnamedUserRow() {
        val before = ChatUiState(messages = listOf(message(9L, "user", "上一轮")))
        val messages = listOf(
            message(1L, "user", "这一轮"),
            message(2L, "assistant", "答复"),
            message(3L, "user", "插话", name = "steer"),
            message(4L, "user", "又一句"),
            message(5L, "tool", "工具结果"),
        )
        val after = turnIdentityResolved(before, messages, turnKey = null)

        assertEquals("带 name 的 user 行（插话 / 通知）不是轮首", 4L, after.liveTurnId)
        assertSame(messages, after.messages)
        assertEquals(5, after.messages.size)
    }

    @Test
    fun turnIdentityIsNullWhenEveryUserRowIsNamed() {
        val messages = listOf(
            message(1L, "user", "插话", name = "steer"),
            message(2L, "assistant", "答复"),
            message(3L, "user", "任务完成通知", name = "notify"),
        )
        val after = turnIdentityResolved(ChatUiState(), messages, turnKey = null)

        assertNull("全是带 name 的行：没有轮首可认", after.liveTurnId)
        assertSame(messages, after.messages)
    }

    @Test
    fun turnStatsAreAbsoluteTurnAndStepCounts() {
        // current 里的数字全是干扰项：口径是绝对值，只由 messages 决定
        val current = SessionStats(
            turns = 99,
            steps = 99,
            promptTokens = 5L,
            completionTokens = 6L,
            cacheHitTokens = 7L,
            cacheMissTokens = 8L,
            llmMillis = 9L,
            toolMillis = 10L,
            ttftMillis = 11L,
            ttftSamples = 12,
        )
        val messages = listOf(
            message(1L, "user", "第一轮"),
            message(2L, "assistant", "答复"),
            message(3L, "user", "插话", name = "steer"),
            message(4L, "tool", "结果一"),
            message(5L, "tool", "结果二"),
            message(6L, "user", "第二轮"),
            message(7L, "tool", "结果三"),
        )
        val after = turnStatsOf(current, messages)

        assertEquals(2, after.turns)
        assertEquals(3, after.steps)
        assertEquals("只有 turns / steps 是重算的", current.copy(turns = 2, steps = 3), after)
        assertEquals(5L, after.promptTokens)
        assertEquals(12, after.ttftSamples)
    }

    @Test
    fun turnFinishedClosesTenFieldsInOneFrame() {
        val before = dirtyState()
        val fresh = listOf(
            message(1L, "user", "你好"),
            message(2L, "assistant", "答复"),
        )
        val freshStats = SessionStats(turns = 4, steps = 9, promptTokens = 100L, completionTokens = 200L)
        val after = turnFinished(before, fresh, freshStats)

        assertFalse(after.sending)
        assertNull(after.liveTurnId)
        assertEquals("", after.streaming)
        assertEquals("", after.reasoning)
        assertFalse(after.reasoningRunning)
        assertFalse(after.toolArgsFlowing)
        assertEquals(emptyList<SessionEvent>(), after.turnEvents)
        assertEquals(LiveTurn(), after.liveTurn)
        assertEquals(fresh, after.messages)
        assertEquals(freshStats, after.stats)
        // 收尾不管的字段照旧
        assertEquals("收尾不动 error", "上一次的错", after.error)
        assertEquals(5L, after.conversationId)
    }

    @Test
    fun deltaAppendsTheTextAndEndsReasoningAndArguments() {
        val before = ChatUiState(streaming = "AB", reasoning = "想", reasoningRunning = true, toolArgsFlowing = true)
        // Delta 是每 token 一次的事件：这条路径绝不许读模型名
        val after = turnProjected(before, ChatEvent.Delta("C")) { error("Delta 分支不许读 modelLabel") }

        assertEquals("ABC", after.streaming)
        assertFalse("正文一开始，思考就结束了", after.reasoningRunning)
        assertFalse(after.toolArgsFlowing)
        assertEquals("思考正文是累积的，不被清", "想", after.reasoning)
        assertEquals(before.messages, after.messages)
    }

    @Test
    fun reasoningAppendsAndKeepsTheReasoningRowRunning() {
        val before = ChatUiState(streaming = "正文", reasoning = "想", reasoningRunning = false, toolArgsFlowing = true)
        val after = turnProjected(before, ChatEvent.Reasoning("了一"))

        assertEquals("想了一", after.reasoning)
        assertTrue("本步最后流出来的是思考 → 思考行回到运行中", after.reasoningRunning)
        assertEquals("正文", after.streaming)
        assertTrue("Reasoning 分支不碰参数判据", after.toolArgsFlowing)
    }

    @Test
    fun toolCallDeltaOnlyFlipsTheTwoFlags() {
        val before = ChatUiState(streaming = "正文", reasoning = "想", reasoningRunning = true, toolArgsFlowing = false)
        val first = turnProjected(
            before,
            ChatEvent.ToolCallDelta(index = 0, id = null, name = null, argumentsChunk = null),
        )
        assertFalse("模型开始写参数：思考在这一刻结束", first.reasoningRunning)
        assertTrue(first.toolArgsFlowing)
        assertEquals("正文", first.streaming)
        assertEquals("想", first.reasoning)

        // 判据不读 index / id / name / argumentsChunk：换一组值结果必须一模一样
        val second = turnProjected(
            before,
            ChatEvent.ToolCallDelta(index = 3, id = "call_1", name = "run_code", argumentsChunk = "{\"code\":"),
        )
        assertEquals(first, second)
    }

    @Test
    fun theStepEventHandsTheStreamingTailOverInOneFrame() {
        val stepEvent = event(SessionBody.Step("第一步的正文", "想了一下"))
        val msgs = listOf(
            message(1L, "user", "你好"),
            message(2L, "assistant", "第一步的正文", name = null),
            message(3L, "tool", "工具结果"),
        )
        val initial = turnOpened(ChatUiState(conversationId = 1L), now = 500L)
        val events = listOf(
            ChatEvent.Delta("第一步的正文"),
            ChatEvent.Reasoning("想了一下"),
            ChatEvent.ToolCallDelta(index = 0, id = "call_1", name = "run_code", argumentsChunk = "{}"),
            ChatEvent.Appended(stepEvent),
        )
        val frames = ArrayList<ChatUiState>()
        var state = initial
        for (e in events) {
            state = turnProjected(state, e, msgs) { "label" }
            frames += state
        }

        // 定稿前一帧：正文只在流式尾巴上，库行快照里还没有它（所以也没有「双份」）
        val tail = frames[2]
        assertEquals("第一步的正文", tail.streaming)
        assertEquals("想了一下", tail.reasoning)
        assertFalse(tail.reasoningRunning)
        assertTrue(tail.toolArgsFlowing)
        assertEquals(initial.messages, tail.messages)
        assertTrue("还在流式阶段：库里没有这段正文", tail.messages.none { it.content == tail.streaming })

        // 定稿那一帧：库行、流式尾巴、日志折叠三件事同帧换掉
        val settled = frames[3]
        assertEquals("Step 之后正文只能有一份：库行里", "", settled.streaming)
        assertEquals("", settled.reasoning)
        assertFalse(settled.reasoningRunning)
        assertFalse(settled.toolArgsFlowing)
        assertEquals(1, settled.turnEvents.size)
        assertSame(stepEvent, settled.turnEvents.single())
        assertEquals(1, settled.liveTurn.steps)
        assertEquals(msgs, settled.messages)
        val duplicated = settled.streaming.isNotEmpty() && settled.messages.any { it.content == settled.streaming }
        assertFalse("同一份正文不许既在库行里、又当流式尾巴挂着", duplicated)
    }

    @Test
    fun aToolResultOnlyEstimatesTheContextAndLeavesTheSnapshotAlone() {
        val snapshot = listOf(
            message(1L, "user", "你好"),
            message(2L, "tool", "旧结果"),
        )
        val before = ChatUiState(
            messages = listOf(message(1L, "user", "你好")),
            context = ContextUsage(system = 100L, tools = 200L, messages = 10L, window = 1000L),
        )
        val resultEvent = event(SessionBody.ToolResult("call_1", 7L, "bash", "abcdefgh", false))
        val after = turnProjected(before, ChatEvent.Appended(resultEvent), snapshot)

        assertEquals("实跑口径：8 个非 CJK 字符 = 2 token", 2L, estimateTokens("abcdefgh"))
        assertEquals("10 + estimateTokens(output.take(MAX_TOOL_CONTENT_CHARS))", 12L, after.context.messages)
        assertEquals(before.context.copy(messages = 12L), after.context)
        assertEquals(100L, after.context.system)
        assertEquals(200L, after.context.tools)
        assertEquals("库行快照不因为工具结果而换", before.messages, after.messages)
        assertEquals(1, after.turnEvents.size)
        assertSame(resultEvent, after.turnEvents.single())
    }

    @Test
    fun aToolResultEstimatesWithTheSameTruncationAsTheDatabaseRow() {
        // AgentLoop 落库时先 take(MAX_TOOL_CONTENT_CHARS)，估算必须同口径
        val output = "a".repeat(AgentLoop.MAX_TOOL_CONTENT_CHARS + 1000)
        val before = ChatUiState(context = ContextUsage(messages = 10L))
        val after = turnProjected(
            before,
            ChatEvent.Appended(event(SessionBody.ToolResult("call_1", 7L, "bash", output, false))),
        )

        assertEquals(65_536L, estimateTokens(output.take(AgentLoop.MAX_TOOL_CONTENT_CHARS)))
        assertEquals(65_546L, after.context.messages)
    }

    @Test
    fun aToolCallAppendClearsTheArgumentsFlowingFlag() {
        val before = ChatUiState(streaming = "正文", reasoning = "想", reasoningRunning = true, toolArgsFlowing = true)
        val callEvent = event(SessionBody.ToolCall("call_1", 7L, "run_code", "{}"))
        val after = turnProjected(before, ChatEvent.Appended(callEvent))

        assertFalse("参数已经流完、工具开始跑了", after.toolArgsFlowing)
        assertEquals("正文", after.streaming)
        assertTrue("正文的尾巴不归这条判据管", after.reasoningRunning)
        assertEquals(1, after.turnEvents.size)
        assertSame(callEvent, after.turnEvents.single())
        assertEquals(1, after.liveTurn.calls.size)
        assertEquals(7L, after.liveTurn.calls.single().harnessId)
    }

    @Test
    fun statsEventsAccumulateOnTheSessionTotals() {
        val before = ChatUiState(stats = SessionStats(turns = 2, steps = 3, promptTokens = 10L, completionTokens = 20L))

        // turns：plus 的口径是 maxOf（会话级轮数不能被更小的绝对值拉回去）——实测：
        // 2 + 5 = 5，再 + 1 还是 5（不是 2，也不是 6）
        val turnsUp = turnProjected(before, ChatEvent.Stats(SessionStats(turns = 5)))
        assertEquals(5, turnsUp.stats.turns)
        assertEquals(5, turnProjected(turnsUp, ChatEvent.Stats(SessionStats(turns = 1))).stats.turns)
        assertEquals(2, turnProjected(before, ChatEvent.Stats(SessionStats(turns = 1))).stats.turns)

        // steps：累加
        val stepsUp = turnProjected(before, ChatEvent.Stats(SessionStats(steps = 4)))
        assertEquals("3 + 4", 7, stepsUp.stats.steps)

        // tokens：累加
        val tokensUp = turnProjected(before, ChatEvent.Stats(SessionStats(promptTokens = 5L, completionTokens = 7L)))
        assertEquals("10 + 5", 15L, tokensUp.stats.promptTokens)
        assertEquals("20 + 7", 27L, tokensUp.stats.completionTokens)
        assertEquals(42L, tokensUp.stats.totalTokens)
        assertEquals(before.messages, tokensUp.messages)
    }

    @Test
    fun streamResetDropsTheDisconnectedAttemptAndNothingElse() {
        val before = dirtyState()
        val after = turnProjected(before, ChatEvent.StreamReset)

        assertEquals("", after.streaming)
        assertEquals("", after.reasoning)
        assertFalse(after.reasoningRunning)
        assertTrue("参数判据不归它管", after.toolArgsFlowing)
        assertEquals(
            "除了这三个字段，其余原样",
            before.copy(streaming = "", reasoning = "", reasoningRunning = false),
            after,
        )
    }

    @Test
    fun aFailureOnlyMentionsImageRoutingWhenTheTurnCarriedAnImage() {
        val label = "qwen-test"

        val withImage = ChatUiState(messages = listOf(message(1L, "user", "看这张图", attachmentsJson = imageJson)))
        val hinted = turnProjected(withImage, ChatEvent.Failed("HTTP 400")) { label }
        assertEquals("HTTP 400" + "\n\n" + imageRouteHintText(label), hinted.error)
        assertEquals(
            "HTTP 400\n\n如果这个模型不能识图：请到「设置 → 模型 → qwen-test → 展开」取消勾选「可识别图片」，再重新发送。",
            hinted.error,
        )
        assertEquals(withImage.messages, hinted.messages)

        val withoutImage = ChatUiState(messages = listOf(message(1L, "user", "你好")))
        val plain = turnProjected(withoutImage, ChatEvent.Failed("HTTP 400")) { label }
        assertEquals("没带图：一个字都不加", "HTTP 400", plain.error)

        // 只有带 name 的行（插话 / 通知）：不算这一轮的身份 → 不加
        val namedOnly = ChatUiState(
            messages = listOf(message(1L, "user", "通知", name = "notify", attachmentsJson = imageJson)),
        )
        assertEquals("HTTP 400", turnProjected(namedOnly, ChatEvent.Failed("HTTP 400")) { label }.error)

        // 只有文件附件（不是图）→ 不加
        val fileOnly = ChatUiState(messages = listOf(message(1L, "user", "看文件", attachmentsJson = fileJson)))
        assertEquals("HTTP 400", turnProjected(fileOnly, ChatEvent.Failed("HTTP 400")) { label }.error)
    }

    @Test
    fun reconnectingReconnectedAndUsageComeBackUntouched() {
        val current = dirtyState().copy(connection = ConnectionState.Reconnecting(1, "boom"))

        assertSame(
            "重连归 setConnection 的计时器",
            current,
            turnProjected(current, ChatEvent.Reconnecting(attempt = 2, message = "又断了", delayMs = 1500L)),
        )
        assertSame(current, turnProjected(current, ChatEvent.Reconnected))
        assertSame(
            "Usage 归 AgentLoop 的 token 账本：stats 走 Stats 事件",
            current,
            turnProjected(
                current,
                ChatEvent.Usage(
                    promptTokens = 10,
                    completionTokens = 20,
                    cacheHitTokens = 3,
                    cacheMissTokens = 7,
                    reasoningTokens = 5,
                ),
            ),
        )
    }

    @Test
    fun imageRouteHintTable() {
        // 实跑打印出来的附件 JSON（encodeAttachments 的口径）
        assertEquals(
            """[{"path":"/ws/.adsh/attachments/1/a.png","name":"a.png","bytes":2048,"mediaType":"image/png","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","width":1200,"height":800}]""",
            imageJson,
        )

        val label = "qwen-test"
        val withImage = ChatUiState(messages = listOf(message(1L, "user", "见图", attachmentsJson = imageJson)))
        val hint = imageRouteHint(withImage, label)
        assertTrue("带图：非空且以两个换行开头", hint.startsWith("\n\n"))
        assertEquals("\n\n" + imageRouteHintText(label), hint)
        assertEquals(
            "\n\n如果这个模型不能识图：请到「设置 → 模型 → qwen-test → 展开」取消勾选「可识别图片」，再重新发送。",
            hint,
        )

        // 不带图 → 空串
        assertEquals(
            "",
            imageRouteHint(ChatUiState(messages = listOf(message(1L, "user", "你好"))), label),
        )
        // 没有人类消息（空会话 / 只有 assistant 与 tool 行）→ 空串
        assertEquals("", imageRouteHint(ChatUiState(), label))
        assertEquals(
            "",
            imageRouteHint(
                ChatUiState(messages = listOf(message(1L, "assistant", "你好"), message(2L, "tool", "输出"))),
                label,
            ),
        )
        // 最后一条人类消息是更早那条没带图的（插话不算轮首）→ 空串
        assertEquals(
            "",
            imageRouteHint(
                ChatUiState(
                    messages = listOf(
                        message(1L, "user", "这一轮没带图"),
                        message(2L, "user", "插话带了图", name = "steer", attachmentsJson = imageJson),
                    ),
                ),
                label,
            ),
        )
    }
}
