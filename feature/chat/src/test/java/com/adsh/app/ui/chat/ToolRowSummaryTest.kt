package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工具行的摘要必须**与参数完整与否无关**：「执行时和执行完成后展示的不同」的根因是库里那条子调用的
 * 参数被按字符数砍到 2048（截断在字符串中间），界面解析失败后退化成显示参数原文 —— 于是同一个写入
 * 工具从「写入 · /w/报告.md」变成「写入 · {\\"file_path\\":\\"/w/报告.md\\",…」。
 */
class ToolRowSummaryTest {

    private val path = "/w/报告.md"
    private val content = "正文行\n".repeat(2_000)
    private val full = """{"file_path":"$path","content":"${content.replace("\n", "\\n")}"}"""

    @Test
    fun completeAndTruncatedArgsShowTheSameSummary() {
        assertEquals(path, toolRowSummary("write", full))
        assertEquals(path, toolRowSummary("write", full.take(2_048)))
    }

    @Test
    fun summaryNeverLeaksRawJsonSyntax() {
        // 连字段都扫不出来时（截断得只剩一段文本），也不能把 JSON 骨架显示出来
        val broken = """{"file_path":"abc"""
        val summary = toolRowSummary("write", broken)
        assertFalse("摘要里不该出现 JSON 括号：$summary", summary.contains("{"))
    }

    @Test
    fun bashSummaryUsesTheCommand() {
        assertEquals("ls -la /w", toolRowSummary("bash", """{"command":"ls -la /w","timeoutMs":5000}"""))
    }
    @Test
    fun jobToolSummaryIsTheJobId() {
        // dsh-tool-jobs 的 presentCall：rawInput 就是 job_id（wait / timeout_ms 不进摘要）
        assertEquals("bash-30", toolRowSummary("job_output", """{"job_id":"bash-30","wait":true,"timeout_ms":20000}"""))
        assertEquals("bash-1", toolRowSummary("job_kill", """{"job_id":"bash-1"}"""))
        // job_list 没有 rawInput：行上只留标题，不拿工具名当摘要（不许显示「工具调用 · job_list」）
        assertEquals("", toolRowSummary("job_list", "{}"))
    }

    @Test
    fun resultOnlyRowsHaveNoInputSection() {
        // dsh 的 SearchRow / WebRow / readFamilyRow / FileMutationRow 不传 rawInput；
        // todo_write / present 报错时也只留错误输出。这些行的详情卡永远没有「输入」段。
        for (name in listOf(
            "grep", "glob", "web_search", "web_fetch",
            "read", "read_image", "write", "edit",
            "todo_write", "todo", "present",
        )) {
            assertNull(name, toolRowInputBody(name, """{"pattern":"x","path":".","todos":[],"files":[]}"""))
        }
    }

    @Test
    fun otherRowsKeepTheirInputBody() {
        // 通用工具（generic 行）仍然是参数 JSON，两空格缩进（dsh 的 JSON.stringify(…, null, 2)）
        assertEquals(
            "{\n  \"agent\": \"x\"\n}",
            toolRowInputBody("list_agents", """{"agent":"x"}"""),
        )
        assertEquals("ls -la", toolRowInputBody("bash", """{"command":"ls -la"}"""))
    }
}
