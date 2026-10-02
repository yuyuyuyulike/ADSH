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

    // -------------------------------------------- 缺少宿主 API / 堆用完（第 98 轮，报告 2.1 / 2.2）

    /**
     * 真机实测：setTimeout / fetch / crypto / URL / TextEncoder / atob / structuredClone / Intl /
     * performance / require / process / Buffer… 在 QuickJS 里全是 `ReferenceError: X is not defined`。
     * 原始报错只说了「没有这个名字」，不说「该改成什么」—— 模型于是换个写法再试一次。
     */
    @Test
    fun `缺定时器时说清楚改用 tools_bash 的 sleep`() {
        val hint = ProgramDiagnostics.missingHostApiHint("ReferenceError: setTimeout is not defined")
        assertTrue(hint != null)
        assertTrue(hint!!.contains("`setTimeout`"))
        assertTrue(hint.contains("sleep"))
        assertTrue(hint.contains("tools.bash"))
    }

    @Test
    fun `缺网络与编码时指到 web_fetch 与 bash`() {
        assertTrue(
            ProgramDiagnostics.missingHostApiHint("ReferenceError: fetch is not defined")!!.contains("web_fetch"),
        )
        assertTrue(
            ProgramDiagnostics.missingHostApiHint("ReferenceError: atob is not defined")!!.contains("base64"),
        )
        assertTrue(
            ProgramDiagnostics.missingHostApiHint("ReferenceError: TextDecoder is not defined")!!.contains("tools.bash"),
        )
    }

    @Test
    fun `缺 Node 内置时说明只有 ECMAScript 与 tools`() {
        val hint = ProgramDiagnostics.missingHostApiHint("ReferenceError: require is not defined")
        assertTrue(hint!!.contains("Node.js"))
        assertTrue(hint.contains("tools.bash"))
    }

    /** 拼写错误不该被这段说明盖掉（那不是「本运行时没有宿主 API」） */
    @Test
    fun `不认识的未定义名字不给提示`() {
        assertNull(ProgramDiagnostics.missingHostApiHint("ReferenceError: fliePath is not defined"))
        assertNull(ProgramDiagnostics.missingHostApiHint("TypeError: x is not a function"))
        assertNull(ProgramDiagnostics.missingHostApiHint(null))
    }

    @Test
    fun `堆用完时说明 60MB 与分块处理`() {
        val hint = ProgramDiagnostics.outOfMemoryHint("InternalError: out of memory")
        assertTrue(hint!!.contains("60 MB"))
        assertTrue(hint.contains("offset"))
        assertNull(ProgramDiagnostics.outOfMemoryHint("TypeError: nope"))
    }

    /** 两类新说明要能接在原有的位置说明后面（同一段文本里，不挤掉行号） */
    @Test
    fun `缺宿主 API 与行号可以同时出现`() {
        val text = ProgramDiagnostics.describe(
            program = "await new Promise(r => setTimeout(r, 100));\nreturn 1;",
            errorText = "ReferenceError: setTimeout is not defined",
            stack = "ReferenceError: setTimeout is not defined\n    at program.js:4:24",
            parserFailure = false,
        )
        assertTrue(text!!.contains("Program line 1:"))
        assertTrue(text.contains("`setTimeout`"))
    }
}
