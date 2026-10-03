package com.adsh.app.ui

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数 JSON 的容错解析（第 72 轮）。
 *
 * 库里存的老数据是被**按字符数砍断**的参数 JSON（`AgentLoop` 旧写法 `args.take(2048)`），
 * 界面当时只能把原文当摘要显示 —— 用户看到的就是「同一个工具执行时显示路径、执行完成后
 * 显示一坨 JSON」。这里钉住：截断的参数也要能取出 `path` / `command` 这些摘要字段。
 */
class ToolArgsTest {

    @Test
    fun truncatedStringValueIsRepaired() {
        // 真实的截断形态：JSON 从 content 的中间断掉
        val raw = """{"file_path":"/data/user/0/com.termux/files/home/报告.md","content":"# 标题
正文……"""
        val args = ToolArgs.parse(raw)
        assertNotNull(args)
        assertEquals(
            "/data/user/0/com.termux/files/home/报告.md",
            ToolArgs.pick(args, listOf("file_path")),
        )
        assertTrue(args!!["content"]!!.jsonPrimitive.content.startsWith("# 标题"))
    }

    @Test
    fun truncatedNestedStructuresAreRepaired() {
        val raw = """{"queries":["one","two"],"options":{"deep":true,"note":"cut"""
        val args = ToolArgs.parse(raw)
        assertNotNull(args)
        assertEquals(2, args!!["queries"]!!.jsonArray.size)
        assertEquals("cut", args["options"]!!.jsonObject["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun trailingCommaAndColonAreDropped() {
        val args = ToolArgs.parse("""{"pattern":"TODO","limit":""")
        assertEquals("TODO", ToolArgs.pick(args, listOf("pattern")))
    }

    @Test
    fun unbalancedBracketsAreClosed() {
        val args = ToolArgs.parse("""{"todos":[{"content":"a","status":"pending"}""")
        assertNotNull(args)
        assertEquals("a", args!!["todos"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun unrepairableJsonStillYieldsSummaryKeys() {
        // 缺逗号这种坏法补不回来 → 走最后的字段扫描
        val args = ToolArgs.parse("""{"command": "echo hi" "note": }""")
        assertEquals("echo hi", ToolArgs.pick(args, listOf("command")))
    }

    @Test
    fun emptyAndGarbageYieldNothing() {
        assertNull(ToolArgs.parse(""))
        assertNull(ToolArgs.parse("not json at all"))
    }

    @Test
    fun validJsonIsUntouched() {
        val args = ToolArgs.parse("""{"path":"/a/b","n":3}""")
        assertEquals("/a/b", args!!["path"]!!.jsonPrimitive.content)
    }
}
