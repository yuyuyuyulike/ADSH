package com.adsh.app.core.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具入参读取必须 total：模型把参数写成对象/数组时返回 null，不许把 kotlinx 的异常与
 * 实现类名泄漏进程序可见的错误（dsh 的 schema 层在 execute 之前整条拒绝）。
 */
class ArgsTest {

    private fun args(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    @Test
    fun strReadsOnlyPrimitiveValues() {
        assertEquals("v", Args.str(args { put("k", "v") }, "k"))
        // dsh 的 schema 层只接受字符串：数字/布尔原语不再被重字符串化（write content=42 曾静默写 "42"）
        assertNull(Args.str(args { put("k", 7) }, "k"))
        assertNull(Args.str(args { put("k", true) }, "k"))
        assertNull(Args.str(args { put("k", JsonNull) }, "k"))
        assertNull(Args.str(args { put("k", buildJsonObject { put("x", 1) }) }, "k"))
        assertNull(Args.str(args { put("k", buildJsonArray { add(1) }) }, "k"))
        assertNull(Args.str(args {}, "k"))
    }

    @Test
    fun intReadsOnlyPrimitiveNumbers() {
        assertEquals(7, Args.int(args { put("k", 7) }, "k"))
        assertEquals(7, Args.int(args { put("k", "7") }, "k"))
        assertNull(Args.int(args { put("k", "x") }, "k"))
        assertNull(Args.int(args { put("k", JsonNull) }, "k"))
        assertNull(Args.int(args { put("k", buildJsonObject { put("x", 1) }) }, "k"))
        assertNull(Args.int(args { put("k", buildJsonArray { add(1) }) }, "k"))
    }

    @Test
    fun boolReadsOnlyBooleanStrings() {
        assertEquals(true, Args.bool(args { put("k", "true") }, "k"))
        assertEquals(false, Args.bool(args { put("k", "false") }, "k"))
        assertNull(Args.bool(args { put("k", "yes") }, "k"))
        assertNull(Args.bool(args { put("k", buildJsonObject { put("x", 1) }) }, "k"))
        assertNull(Args.bool(args { put("k", buildJsonArray { add(true) }) }, "k"))
    }

    /** 严格整数（dsh 的 read 参数校验）：字符串 / 浮点 / null 都是「给了但不合法」，不能退化成缺省 */
    @Test
    fun intArgDistinguishesAbsentFromInvalid() {
        assertTrue(!Args.intArg(args {}, "k").present)
        assertNull(Args.intArg(args {}, "k").value)

        assertEquals(2, Args.intArg(args { put("k", 2) }, "k").value)
        assertTrue(Args.intArg(args { put("k", 2) }, "k").present)

        val fromString = Args.intArg(args { put("k", "2") }, "k")
        assertTrue("string number is present but invalid", fromString.present)
        assertNull(fromString.value)
        val fromFloat = Args.intArg(args { put("k", 1.5) }, "k")
        assertTrue("fraction is present but invalid", fromFloat.present)
        assertNull(fromFloat.value)
        assertTrue("null is present but invalid", Args.intArg(args { put("k", JsonNull) }, "k").present)
        assertNull(Args.intArg(args { put("k", JsonNull) }, "k").value)
        assertNull(Args.intArg(args { put("k", buildJsonObject { put("x", 1) }) }, "k").value)
    }

    /** file_path 的旧名 path 也走同一套 total 读取（历史会话的调用不能因为参数写成对象就泄漏实现） */
    @Test
    fun legacyPathFallbackIsAlsoTotal() {
        assertEquals("a.txt", Args.filePath(args { put("path", "a.txt") }))
        assertNull(Args.filePath(args { put("path", buildJsonObject { put("x", 1) }) }))
        assertEquals("b.txt", Args.filePath(args { put("file_path", "b.txt") }))
    }
}
