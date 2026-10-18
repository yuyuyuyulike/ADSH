package com.adsh.app.ui.chat

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh 的 preparing 行（参数还在流式生成的那一次工具调用）：
 *  - 摘要取 description **已经写出来的那一段**（PartialArguments.text），不是等落日志才蹦出来；
 *  - 文件类工具只认完整路径（半截路径不显示）；
 *  - 这一行不可展开、不挂展开体；
 *  - 它接在已落日志的那几条调用之后（顺序 = 模型的调用顺序）。
 */
class PreparingRowTest {

    private fun user(id: Long = 1) =
        MessageEntity(id = id, conversationId = 1, role = "user", content = "u$id", createdAt = id * 10)

    /** 只在第一行取一次 view：每个 TurnEntry 都持有整份 TurnView，逐行取会把同一条调用数好几遍 */
    private fun calls(items: List<ChatItem>): List<ProcessEntry.Call> =
        items.filterIsInstance<ChatItem.TurnEntry>().firstOrNull()?.view?.entries
            ?.filterIsInstance<ProcessEntry.Call>().orEmpty()

    @Test
    fun descriptionStreamsWhileTheArgumentsAreStillIncomplete() {
        // run_code 的 description 先于 code 写完 / 或写在 code 之后：两种中间态都要能显示已经写出的部分
        assertEquals("Slee", preparingSummaryOf("bash", "{\"description\":\"Slee"))
        assertEquals("Sleep then echo", preparingSummaryOf("run_code", "{\"description\":\"Sleep then echo"))
        assertEquals(
            "line1",
            preparingSummaryOf("bash", "{\"description\":\"line1\\nline2"),
        )
        // 键还没出现 / 冒号还没到：这一行只有标题（dsh 的 PartialArguments.text 也返回空）
        assertEquals("", preparingSummaryOf("bash", "{\"comm"))
        assertEquals("", preparingSummaryOf("bash", ""))
    }

    @Test
    fun fileToolsOnlyShowACompletePath() {
        assertEquals("", preparingSummaryOf("read", "{\"file_path\":\"/w/rep"))
        assertEquals("/w/report.md", preparingSummaryOf("read", "{\"file_path\":\"/w/report.md\"}"))
    }

    @Test
    fun pendingCallBecomesAPreparingRowWithStreamingArguments() {
        val items = buildChatItems(
            messages = listOf(user()),
            liveTurnId = 1,
            pendingCalls = listOf(
                PendingCall(index = 0, id = "call_1", name = "run_code", arguments = "{\"description\":\"Slee"),
            ),
        )
        val call = calls(items).single()
        assertTrue("preparing 行是运行态（有流光）", call.running)
        assertTrue("preparing 行不可展开", call.preparing)
        assertFalse("preparing 行还没有结果", call.isError)
        // 参数原文留在行上：摘要从它现算（description 已写出来的部分）
        assertEquals("{\"description\":\"Slee", call.arguments)
    }

    @Test
    fun preparingRowsFollowTheCallsThatAlreadyStarted() {
        val live = LiveCall(harnessId = 7L, callId = "call_0", name = "run_code", arguments = "{}", startedAt = 1L)
        val items = buildChatItems(
            messages = listOf(user()),
            liveTurnId = 1,
            liveCalls = listOf(live),
            pendingCalls = listOf(PendingCall(index = 1, id = "call_1", name = "bash", arguments = "{}")),
        )
        val all = calls(items)
        assertEquals(2, all.size)
        assertEquals("先在跑的调用在前", "call_0", all[0].id)
        assertFalse("已开始的那条不是 preparing", all[0].preparing)
        assertTrue("还在写参数的那条是 preparing", all[1].preparing)
    }

    @Test
    fun runningStatusIsTheLastFlowRow() {
        val items = buildChatItems(
            messages = listOf(user()),
            liveTurnId = 1,
            runningStatus = true,
            runStartedAt = 1234L,
        )
        val running = items.lastOrNull() as? ChatItem.Running
        assertNotNull("sending 时流末尾要有 RunningStatus", running)
        assertEquals(1234L, running!!.startedAt)
        assertTrue("与上面一行的间距是 dsh 的 12dp", running.gap.value > 0f)
    }

    @Test
    fun noRunningStatusAfterTheTurnFinished() {
        val items = buildChatItems(messages = listOf(user()), liveTurnId = null)
        assertTrue(items.none { it is ChatItem.Running })
    }
}
