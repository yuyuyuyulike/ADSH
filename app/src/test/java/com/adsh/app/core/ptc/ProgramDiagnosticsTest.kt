package com.adsh.app.core.ptc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run_code 失败时的定位（第 81 轮）：把 QuickJS 的行号还原成模型自己写的那一行，
 * 并在语法失败时指出第一处 TypeScript 写法 —— 用户实测「报错比 dsh 多」的根因就在这一层。
 */
class ProgramDiagnosticsTest {

    @Test
    fun `从调用栈里取 program_js 的行号`() {
        val stack = """
            Error: boom
                at inner (program.js:7:12)
                at outer (program.js:3:1)
        """.trimIndent()
        assertEquals(7, ProgramDiagnostics.fileLineOf(stack))
    }

    @Test
    fun `栈里没有时也从错误消息里找`() {
        assertEquals(5, ProgramDiagnostics.fileLineOf(null, "SyntaxError: x (program.js:5)"))
        assertNull(ProgramDiagnostics.fileLineOf(null, "SyntaxError: x"))
    }

    @Test
    fun `文件行号减掉包装器的偏移`() {
        // 程序体嵌在包装器第 4 行：文件第 4 行 = 程序第 1 行
        assertEquals(1, ProgramDiagnostics.programLineOf(4, 10))
        assertEquals(10, ProgramDiagnostics.programLineOf(13, 10))
        // 落在包装器自己那几行 / 超出程序行数：宁可不报
        assertNull(ProgramDiagnostics.programLineOf(3, 10))
        assertNull(ProgramDiagnostics.programLineOf(14, 10))
    }

    @Test
    fun `语法失败时找出第一处 TypeScript 写法`() {
        val program = """
            const files = await tools.glob({ pattern: '**/*.ts' });
            const total: number = files.paths.length;
            return total;
        """.trimIndent()
        val found = ProgramDiagnostics.firstTypeScriptLine(program)
        assertEquals(2, found?.first)
        assertTrue(found?.second?.contains("total: number") == true)
    }

    @Test
    fun `interface_enum_as_泛型与参数标注都能认出来`() {
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("interface Row { id: string }") != null)
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("enum Color { Red }") != null)
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("const x = value as string;") != null)
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("function f(a: string) { return a; }") != null)
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("const xs: Array<string> = [];") != null)
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("const map = new Map<string, number>();") != null)
        assertTrue(ProgramDiagnostics.firstTypeScriptLine("return value!;") != null)
    }

    @Test
    fun `纯 JavaScript 不会被误判`() {
        assertNull(ProgramDiagnostics.firstTypeScriptLine("const opts = { timeout: 5, deep: { a: 1 } };"))
        assertNull(ProgramDiagnostics.firstTypeScriptLine("if (a < b && c > d) { return b; }"))
        assertNull(ProgramDiagnostics.firstTypeScriptLine("const xs = new Map();"))
        // 注释行不算
        assertNull(ProgramDiagnostics.firstTypeScriptLine("// const total: number = 1;"))
    }

    @Test
    fun `语法失败的判据`() {
        assertTrue(ProgramDiagnostics.looksLikeSyntaxFailure("SyntaxError: unexpected token ':'"))
        assertTrue(ProgramDiagnostics.looksLikeSyntaxFailure("unexpected end of input"))
        assertFalse(ProgramDiagnostics.looksLikeSyntaxFailure("ToolCallError: file not found"))
        assertFalse(ProgramDiagnostics.looksLikeSyntaxFailure(null))
    }

    @Test
    fun `运行时错误报出程序第几行`() {
        val program = "const a = 1;\nawait tools.read({ file_path: 'x' });\nreturn a;"
        val text = ProgramDiagnostics.describe(
            program = program,
            errorText = "工具 read 调用失败",
            stack = "ToolCallError: 工具 read 调用失败\n    at program.js:5:7",
            parserFailure = false,
        )
        assertEquals("Program line 2: await tools.read({ file_path: 'x' });", text)
    }

    @Test
    fun `语法失败补一句 QuickJS 只认纯 JavaScript`() {
        val program = "const n: number = 1;\nreturn n;"
        val text = ProgramDiagnostics.describe(
            program = program,
            errorText = "SyntaxError: unexpected token ':'",
            stack = null,
            parserFailure = true,
        )
        assertTrue(text != null)
        assertTrue(text!!.contains("TypeScript syntax on line 1"))
        assertTrue(text.contains("QuickJS"))
        assertTrue(text.contains("do not re-send the same code"))
    }

    @Test
    fun `没有任何可用信息时不给位置`() {
        assertNull(
            ProgramDiagnostics.describe(
                program = "return await tools.bash({ command: 'pwd', description: 'pwd' });",
                errorText = "程序在 120000ms 内未结束（可能死循环或工具未返回）",
                stack = null,
                parserFailure = false,
            )
        )
    }
}
