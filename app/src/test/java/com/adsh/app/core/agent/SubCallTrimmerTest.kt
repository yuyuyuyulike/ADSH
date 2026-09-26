package com.adsh.app.core.agent

import com.adsh.app.core.ptc.SubCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子调用轨迹的落库裁剪（第 72 轮）：**参数按字段值截断，存下来的仍然是合法 JSON**。
 *
 * 旧写法 `args.take(2048)` 把 JSON 从中间砍断，界面解析失败后退化成显示原文 ——
 * 同一个工具「执行时显示路径、执行完成后显示一坨 JSON」就是它造成的。
 */
class SubCallTrimmerTest {

    private fun sub(id: String, args: String, result: String = "ok") =
        SubCall(name = "write", args = args, ok = true, result = result, durationMs = 1, id = id)

    @Test
    fun longWriteArgsStayParsableAndKeepTheirPath() {
        val content = "行\n".repeat(4_000)                       // 远超 2048/4096 的预算
        val args = """{"file_path":"/w/报告.md","content":"${content.replace("\n", "\\n")}"}"""
        val capped = SubCallTrimmer.cap(listOf(sub("1", args)), keep = 60).single().args

        assertTrue("截断后应当更短", capped.length <= SubCallTrimmer.ARGS_LIMIT)
        val parsed = Json.parseToJsonElement(capped).jsonObject       // 必须仍然是合法 JSON
        assertEquals("/w/报告.md", parsed["file_path"]!!.jsonPrimitive.content)
        assertTrue(
            "被砍过的值要留下标记",
            parsed["content"]!!.jsonPrimitive.content.endsWith(SubCallTrimmer.TRUNCATION_MARK),
        )
    }

    @Test
    fun shortArgsAreUntouched() {
        val args = """{"file_path":"/w/a.md","content":"短"}"""
        assertEquals(args, SubCallTrimmer.cap(listOf(sub("1", args)), keep = 60).single().args)
    }

    @Test
    fun onlyTheLastCallsAreKept() {
        val all = (1..5).map { sub(it.toString(), """{"file_path":"/w/$it.md"}""") }
        val kept = SubCallTrimmer.cap(all, keep = 2)
        assertEquals(listOf("/w/4.md", "/w/5.md"), kept.map { it.id }.map { "/w/$it.md" })
    }

    @Test
    fun longResultsAreCapped() {
        val kept = SubCallTrimmer.cap(listOf(sub("1", "{}", result = "x".repeat(50_000))), keep = 60)
        assertEquals(SubCallTrimmer.RESULT_LIMIT, kept.single().result.length)
    }
}
