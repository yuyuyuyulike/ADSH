package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 工具行的摘要必须**与参数完整与否无关**（第 72 轮用户实测的那条）。
 *
 * 「执行时和执行完成后展示的不同、有明显变化动效」的根因：库里那条子调用的参数被按字符数
 * 砍到 2048（截断在字符串中间），界面解析失败后退化成显示参数原文 —— 于是同一个写入工具
 * 从「写入 · /w/报告.md」变成「写入 · {"file_path":"/w/报告.md","content":"# …」。
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
}
