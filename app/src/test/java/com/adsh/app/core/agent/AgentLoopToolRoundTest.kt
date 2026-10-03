package com.adsh.app.core.agent

import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.session.SessionLog
import com.adsh.app.core.tools.RunCodeTool
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **工具轮**的黄金基线（甲方案的第二步：R27 把 ToolContext 收成端口 [TurnTools]）。
 *
 * 这一轮以前完全测不到：AgentLoop 直接拿 ToolContext，而它要 `TermuxRuntime(Context)`，
 * 纯 JVM 单测里构造不出来。现在用假的 [TurnTools] 就能钉住这条路上真正容易改坏的东西：
 * 请求里带不带 run_code 的 schema、日志里 call/result 成不成对、工具行怎么落库、
 * 死循环判据命中之后落什么。
 *
 * 与 [AgentLoopGoldenTest] 同样的口径：断言值来自先跑一遍打印真实行为；墙钟字段只能 >= 0。
 * 工作区用 SAF 形态 —— 它的 `shellRoot` 是 null，于是轮尾的文件改动统计不会去扫真实目录，
 * 测试是确定的。
 */
class AgentLoopToolRoundTest {

    /** 假的工作区：SAF 形态 ⇒ shellRoot = null（不扫盘）。 */
    private val workspace = Workspace(WorkspaceForm.SAF_REFERENCE, File("/nonexistent"), "测试工作区")

    private class Recorded(val name: String, val argsJson: String, val callId: String?, val execToken: Long)

    /** 假的工具世界：记下每次调用，按脚本吐输出。 */
    private class FakeTurnTools(
        override val workspace: Workspace,
        private val outputs: List<Pair<String, Boolean>> = listOf("ok-output" to false),
    ) : TurnTools {
        val calls = ArrayList<Recorded>()
        override suspend fun run(name: String, argsJson: String, callId: String?, execToken: Long): Pair<String, Boolean> {
            calls.add(Recorded(name, argsJson, callId, execToken))
            return outputs.getOrElse(calls.size - 1) { outputs.last() }
        }
    }

    /** 一步的脚本：模型要求跑一段 run_code（参数逐字一样，好让死循环判据认得出来）。 */
    private fun toolCallRound(argsJson: String = "{\"code\":\"1\"}") = listOf(
        ChatEvent.ToolCallDelta(index = 0, id = "call_1", name = "run_code", argumentsChunk = argsJson),
    )

    private fun assertLogInvariants(events: List<ChatEvent>) {
        val sessionEvents = events.filterIsInstance<ChatEvent.Appended>().map { it.event }
        assertEquals(emptyList<String>(), SessionLog.violations(sessionEvents))
    }

    /** 一轮工具调用：请求带 schema → 工具执行 → 工具行落库 → 下一步读回结果 → 收尾。 */
    @Test
    fun toolRoundIsTheGoldenBaseline() = runBlocking {
        val llm = FakeTurnLlm(toolCallRound(), listOf(ChatEvent.Delta("跑完了。")))
        val store = FakeTurnStore()
        val tools = FakeTurnTools(workspace)

        val events = AgentLoop(llm, store, FakeTurnSettings())
            .sendWithTools(1L, "算一下", tools)
            .toList()
        assertLogInvariants(events)

        // ① 两次请求：第一次带 run_code 的 schema，第二次把工具结果读回去
        assertEquals(2, llm.callCount)
        assertEquals(listOf(RunCodeTool.wireSchema), llm.requests[0].tools)
        assertEquals(listOf(RunCodeTool.wireSchema), llm.requests[1].tools)
        // 第二次请求 = 系统提示词 + 用户消息 + 上一步的 assistant（带 tool_calls）+ 工具结果
        assertEquals(
            listOf("system", "user", "assistant", "tool"),
            llm.requests[1].messages.map { it.role },
        )
        val toolMessage = llm.requests[1].messages[3]
        assertEquals("ok-output", toolMessage.textContent)
        assertNotNull("带 tool_calls 的 assistant 行必须在库里", llm.requests[1].messages[2].toolCalls)

        // ② 库里的行：用户 → （assistant + tool）→ 收尾 assistant
        assertEquals(listOf("user|-|算一下"), store.shape(1L).take(1))
        assertEquals(
            listOf(
                "user|-|算一下",
                "assistant|-|",
                "tool|run_code|ok-output",
                "assistant|-|跑完了。",
            ),
            store.shape(1L),
        )

        // ③ 工具真的被调了一次，身份是调用方给的
        val call = tools.calls.single()
        assertEquals("run_code", call.name)
        assertEquals("{\"code\":\"1\"}", call.argsJson)
        assertEquals("call_1", call.callId)
        assertTrue("execToken 由宿主生成，必须 > 0", call.execToken > 0L)

        // ④ 界面事件里能看到这一步的统计。注意 steps 是**这一步**的增量：
        //    跑完工具那一步（第一条 Stats）是 1，收尾那一步（最后一条）又回到 0。
        val allStats = events.filterIsInstance<ChatEvent.Stats>().map { it.stats }
        assertEquals(2, allStats.size)
        assertEquals(1, allStats.first().steps)
        assertEquals(0, allStats.last().steps)
        assertTrue(allStats.first().toolMillis >= 0L)
    }

    /** 同一组「工具 + 参数」重复到判据上限：立刻停，并落一条说明原因的 assistant 行。 */
    @Test
    fun repeatedIdenticalCallsHitTheLoopGuard() = runBlocking {
        // 判据是「重复次数 > REPEAT_CALL_LIMIT(3)」：第 4 次同样的调用必须停下
        val llm = FakeTurnLlm(toolCallRound(), toolCallRound(), toolCallRound(), toolCallRound())
        val store = FakeTurnStore()
        val tools = FakeTurnTools(workspace)

        val events = AgentLoop(llm, store, FakeTurnSettings())
            .sendWithTools(1L, "算一下", tools)
            .toList()
        assertLogInvariants(events)

        assertEquals("第 4 次调用不该被执行", 3, tools.calls.size)
        val rows = store.rows(1L)
        val reason = loopGuardReason("run_code", 4, 4)
        assertNotNull(reason)
        assertEquals(stoppedText(reason!!), rows.last().content)
        assertEquals("assistant", rows.last().role)
        // 1 条用户 + 3 步（每步 assistant(tool_calls) + tool）+ 第 4 步的 assistant 行 + 1 条已停止
        assertEquals(9, rows.size)
    }

    /**
     * 空工具输出回灌模型时写 `(no output)`（dsh 的 serializeMessages）。
     *
     * 补这条是因为审查时做了一次变异测试：把 ifEmpty { NO_OUTPUT } 去掉，**全套用例一个都没响**。
     * 库里那一行仍然是空的（终端命令本来就可能没有输出），只有**发给模型的那一条**要占位 ——
     * 空串会让部分提供方直接判这段历史非法。
     */
    @Test
    fun anEmptyToolResultIsSentAsNoOutput() = runBlocking {
        val llm = FakeTurnLlm(toolCallRound(), listOf(ChatEvent.Delta("跑完了。")))
        val store = FakeTurnStore()
        val tools = FakeTurnTools(workspace, outputs = listOf("" to false))

        val events = AgentLoop(llm, store, FakeTurnSettings())
            .sendWithTools(1L, "算一下", tools)
            .toList()
        assertLogInvariants(events)

        assertEquals("库里那一行是空的（工具本来就可能没有输出）", "", store.rows(1L)[2].content)
        assertEquals("(no output)", llm.requests[1].messages[3].textContent)
    }
}
