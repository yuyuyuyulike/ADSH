package com.adsh.app.ui

import com.adsh.app.core.agent.PresentedFile
import com.adsh.app.core.agent.encodeDeliverables
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.llm.ToolCallFunction
import com.adsh.app.core.ptc.SubCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮尾的交付物卡片（dsh 的 ui-deliverables 挂在 `conversation.chat.turnTail`，第八十三轮补上）。
 *
 * 数据有两条来路，两条都要能画出来：
 *  - **已经落库**：run_code 那一行上的 deliverablesJson（present 是它的子调用）；
 *  - **正在跑**：实时的子调用轨迹（落库要等这一步结束，卡片不该等）。
 */
class TurnDeliverablesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun toolCalls() = json.encodeToString(
        ListSerializer(ToolCall.serializer()),
        listOf(
            ToolCall(
                id = "call_1",
                type = "function",
                function = ToolCallFunction(name = "run_code", arguments = "{\"code\":\"...\"}"),
            ),
        ),
    )

    private fun subCalls(files: List<PresentedFile>): String = json.encodeToString(
        ListSerializer(SubCall.serializer()),
        listOf(
            SubCall(
                name = "present",
                args = "{}",
                ok = true,
                result = "Presented out/report.pdf",
                durationMs = 3,
                id = "1:ptc:1",
                deliverables = files,
            ),
        ),
    )

    private fun turnOf(messages: List<MessageEntity>): TurnView =
        buildChatItems(messages = messages)
            .filterIsInstance<ChatItem.TurnTail>()
            .first()
            .view

    private fun conversation(toolRow: MessageEntity): List<MessageEntity> = listOf(
        MessageEntity(id = 1, conversationId = 1, role = "user", content = "做份报告", createdAt = 1),
        MessageEntity(
            id = 2,
            conversationId = 1,
            role = "assistant",
            content = "",
            toolCallsJson = toolCalls(),
            createdAt = 2,
        ),
        toolRow,
    )

    /** 交付物路径的解析（dsh 的 resolveWorkspacePath(cwd, path)）：相对路径挂到工作区根上 */
    @Test
    fun deliverablePathResolvesAgainstTheWorkspace() {
        // 分隔符按平台（Windows 上的 JVM 单测是 \，Android 上是 /）—— 拼法一致即可
        assertEquals(java.io.File("/ws", "out/report.pdf").path, resolveDeliverablePath("/ws", "out/report.pdf"))
        assertEquals("out/report.pdf", resolveDeliverablePath(null, "out/report.pdf"))
        // 绝对路径原样返回。只在 POSIX 语义下断言：Windows 的 JVM 不认 /sdcard 这种没有盘符的绝对路径，
        // 而这段代码只跑在 Android 上
        if (java.io.File.separatorChar == '/') {
            assertEquals("/sdcard/x.csv", resolveDeliverablePath("/ws", "/sdcard/x.csv"))
        }
    }

    @Test
    fun deliverablesFromTheStoredToolRowReachTheTurnTail() {
        val files = listOf(PresentedFile("out/report.pdf", "季度报告"), PresentedFile("/sdcard/x.csv"))
        val view = turnOf(
            conversation(
                MessageEntity(
                    id = 3,
                    conversationId = 1,
                    role = "tool",
                    content = "Presented out/report.pdf",
                    toolCallId = "call_1",
                    name = "run_code",
                    deliverablesJson = encodeDeliverables(files),
                    createdAt = 3,
                ),
            ),
        )
        assertEquals(files, view.deliverables)
    }

    /** 同一个文件被声明两次（模型复制了一遍）只画一张卡片 */
    @Test
    fun duplicatesCollapseToOneCard() {
        val view = turnOf(
            conversation(
                MessageEntity(
                    id = 3,
                    conversationId = 1,
                    role = "tool",
                    content = "ok",
                    toolCallId = "call_1",
                    name = "run_code",
                    deliverablesJson = encodeDeliverables(
                        listOf(PresentedFile("a.md"), PresentedFile("a.md", "again")),
                    ),
                    createdAt = 3,
                ),
            ),
        )
        assertEquals(listOf("a.md"), view.deliverables.map { it.path })
    }

    /** 正在跑的一轮：交付物来自实时子调用（库里那一行还没有成果） */
    @Test
    fun liveSubCallsShowDeliverablesBeforeTheStepIsPersisted() {
        val live = listOf(
            SubCall(
                name = "present",
                args = "{}",
                ok = true,
                result = "Presented out/report.pdf",
                durationMs = 3,
                id = "1:ptc:1",
                deliverables = listOf(PresentedFile("out/report.pdf", "季度报告")),
            ),
        )
        val items = buildChatItems(
            messages = conversation(
                MessageEntity(
                    id = 3,
                    conversationId = 1,
                    role = "tool",
                    content = "",
                    toolCallId = "call_1",
                    name = "run_code",
                    createdAt = 3,
                ),
            ),
            liveCalls = listOf(
                LiveCall(
                    harnessId = 1,
                    name = "run_code",
                    arguments = "{\"code\":\"...\"}",
                    startedAt = 1,
                ),
            ),
            liveSubCalls = live,

            liveTurnId = 1,
        )
        val tail = items.filterIsInstance<ChatItem.TurnTail>().first()
        assertEquals(listOf("out/report.pdf"), tail.view.deliverables.map { it.path })
    }

    /** 失败/被拒的子调用不算交付（dsh：Blocked results publish none） */
    @Test
    fun failedSubCallsPublishNothing() {
        val view = turnOf(
            conversation(
                MessageEntity(
                    id = 3,
                    conversationId = 1,
                    role = "tool",
                    content = "unknown tool",
                    toolCallId = "call_1",
                    name = "run_code",
                    subCallsJson = subCalls(listOf(PresentedFile("nope.txt"))).replace("\"ok\":true", "\"ok\":false"),
                    createdAt = 3,
                ),
            ),
        )
        assertTrue(view.deliverables.isEmpty())
    }
}
