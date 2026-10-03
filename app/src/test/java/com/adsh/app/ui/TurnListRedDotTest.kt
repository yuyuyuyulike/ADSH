package com.adsh.app.ui

import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.ptc.SubCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败工具行 / 失败子调用行的**红点**（第 101 轮）。
 *
 * 用户实测：「有的工具调用失败后其左侧的红点却不存在，得这一轮对话结束后才会出现」
 * （外层工具行与 run_code 的子调用行都见过）。判据都落在 [buildChatItems] 上：
 * 界面画什么完全由它决定，所以这里直接打表。
 * 修法的两条骨架：
 *  - **失败只进不退**：库里那一行已经按工具消息判成 error 之后，一个还没收到结果的
 *    流式状态不许把它改回成功（`isError = call.isError || entry.isError`）；
 *  - **流式行按顺序认领**（第 110 轮）：这一轮第 k 条流式调用就是第 k 条工具行 —— 落库的
 *    `tool_calls` 与 harness 分配 execToken 的顺序完全一致，所以网关把 callId 重新编号
 *    也不会串行（见 [buildChatItems] / [TurnBuilder.addLive]）。
 */
class TurnListRedDotTest {

    private fun user() = MessageEntity(id = 1, conversationId = 1, role = "user", content = "u", createdAt = 1)

    private fun assistant(id: Long, calls: String?) = MessageEntity(
        id = id,
        conversationId = 1,
        role = "assistant",
        content = "",
        toolCallsJson = calls,
        createdAt = id * 10,
    )

    private fun toolResult(id: Long, callId: String, output: String, isError: Boolean, subCalls: String? = null) =
        MessageEntity(
            id = id,
            conversationId = 1,
            role = "tool",
            content = output,
            toolCallId = callId,
            name = "run_code",
            isError = isError,
            subCallsJson = subCalls,
            createdAt = id * 10,
        )

    private fun calls(id: String, name: String = "run_code", args: String = "{}") =
        """[{"id":"$id","type":"function","function":{"name":"$name","arguments":"${args.replace("\"", "\\\"")}"}}]"""

    private fun entries(
        messages: List<MessageEntity>,
        liveCalls: List<LiveCall> = emptyList(),
        liveSubCalls: List<SubCall> = emptyList(),
    ): List<ProcessEntry> = buildChatItems(
        messages = messages,
        liveCalls = liveCalls,

        liveTurnId = 1,
    ).filterIsInstance<ChatItem.TurnEntry>().first().view.entries

    private fun callsIn(entries: List<ProcessEntry>) = entries.filterIsInstance<ProcessEntry.Call>()

    /** 修法的第一步：库里已经判失败的行，不会因为流式行「还没收到结果」而丢掉红点 */
    @Test
    fun aFailedRowKeepsItsDotWhenAStaleLiveRowArrives() {
        val messages = listOf(
            user(),
            assistant(2, calls("c1")),
            toolResult(3, "c1", "EACCES: permission denied", isError = true),
        )
        // 界面这一帧看到的是「这一步刚发出去、还没回来」的那条流式行
        val live = listOf(LiveCall(callId = "c1", name = "run_code", arguments = "{}", startedAt = 5))
        val row = callsIn(entries(messages, liveCalls = live)).single()
        assertTrue("库里那行已经失败了，流式状态不许把它改回成功", row.isError)
        assertEquals("EACCES: permission denied", row.output)
    }

    /** 同 id 复用（网关按每条响应从 call_0 重新编号）时，流式行不会串到上一步那行去 */
    @Test
    fun aReusedCallIdDoesNotHijackTheEarlierStepRow() {
        val messages = listOf(
            user(),
            // 第 1 步：同一个 callId 的调用，已经失败定稿
            assistant(2, calls("c1")),
            toolResult(3, "c1", "第 1 步的失败", isError = true),
            // 第 2 步：网关又给了一个叫 c1 的调用，正在跑
            assistant(4, calls("c1")),
            toolResult(5, "c1", "第 2 步的结果", isError = false),
        )
        val live = listOf(
            LiveCall(callId = "c1", name = "run_code", arguments = "{}", startedAt = 5, output = "第 1 步的失败", isError = true, finishedAt = 6),
            LiveCall(callId = "c1", name = "run_code", arguments = "{}", startedAt = 7, output = "第 2 步的结果", finishedAt = 8),
        )
        val rows = callsIn(entries(messages, liveCalls = live))
        assertEquals(2, rows.size)
        assertTrue("第 1 步那行必须还是失败态", rows[0].isError)
        assertEquals("第 1 步的失败", rows[0].output)
        assertFalse("第 2 步那行不该被第 1 步的失败串上", rows[1].isError)
        assertEquals("第 2 步的结果", rows[1].output)
    }



    /** 子行跟着**前缀**走：第 k 条调用的子行只挂在第 k 条工具行下面 */
    @Test
    fun aTraceOnlyAppliesToTheCallItBelongsTo() {
        fun sub(id: String, running: Boolean) =
            SubCall(name = "read", args = "{}", ok = true, result = id, durationMs = 1, id = id, running = running)

        val messages = listOf(
            user(),
            assistant(2, calls("c1")),
            toolResult(3, "c1", "第 1 步结果", isError = false, subCalls = """[{"name":"read","args":"{}","ok":true,"result":"1:ptc:A","durationMs":1,"id":"1:ptc:A"}]"""),
            assistant(4, calls("c2")),
        )
        val liveCalls = listOf(
            LiveCall(
                harnessId = 1,
                callId = "c1",
                name = "run_code",
                arguments = "{}",
                startedAt = 5,
                output = "第 1 步结果",
                finishedAt = 6,
            ),
            LiveCall(callId = "c2", name = "run_code", arguments = "{}", startedAt = 7),
        )
        // 这一帧的子行只有第 1 条那几行（归属写在子行 id 的前缀里）
        val rows = callsIn(
            entries(messages, liveCalls = liveCalls, liveSubCalls = listOf(sub("1:ptc:A", running = true))),
        )
        assertEquals("1:ptc:A", rows[0].subCalls.map { it.id }.single())
        // 第 109 轮起没有「采样值 vs 定稿那份」的合并：折叠结果本身就是定稿状态（结算按 id 覆盖
        // 同一行），所以这一条只断言「按前缀归属」——第 2 条不许继承第 1 条的轨迹。
        assertTrue("第 2 条刚开始，还没有子行", rows[1].subCalls.isEmpty())
    }
}
