package com.adsh.app.core.tools

import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import com.adsh.app.seam.model.TurnChangeTracker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 文件工具的端到端契约（临时工作区 + 真文件 IO）：回显截断不影响磁盘、
 * value.path 是执行世界里的绝对路径、read 的行口径按 dsh。
 *
 * 不需要 Robolectric：这条路径只碰 java.io 与 seam 端口，用例也不会真起进程。
 */
class ToolExecTest {

    private lateinit var root: File

    /** 只为构造 [ToolContext]：本组用例的失败分支都在起进程之前返回 */
    private class FakeShell(private val tmp: File) : Shell {
        override val bashPath = "/nonexistent/bash"
        override val ripgrepPath = "/nonexistent/rg"

        override fun tmpDir(): File = tmp
        override fun scratchDir(): File = tmp
        override fun cacheRoots(): List<File> = emptyList()
        override fun fenceEnv(mode: String, roots: List<File?>, mark: File?): Array<String> = emptyArray()

        override fun spawn(
            command: String,
            workspaceRoot: File?,
            extraEnv: Array<String>?,
            separateStreams: Boolean,
        ): ShellProcess = throw UnsupportedOperationException("unit test must not spawn")

        override fun run(
            command: String,
            workspaceRoot: File?,
            timeoutMs: Long,
            maxOutputBytes: Int,
            extraEnv: Array<String>?,
            separateStreams: Boolean,
            cancel: (() -> Boolean)?,
        ): ExecResult = throw UnsupportedOperationException("unit test must not run commands")
    }

    private fun ctx(): ToolContext = ToolContext(
        workspace = Workspace(WorkspaceForm.PRIVATE, root, "test"),
        runtime = FakeShell(root),
        permission = Permissions.FULL_ACCESS,
    )

    @Before
    fun setUp() {
        root = File.createTempFile("tool-exec", "").let { it.delete(); it.mkdirs(); it }
        TurnChangeTracker.beginTurn()
    }

    @After
    fun tearDown() {
        TurnChangeTracker.beginTurn()
        root.deleteRecursively()
    }

    /**
     * write 一个 5 MiB 文件：磁盘逐字节完整，只有回显被截。
     * 整次调用会失败（回显整段进 wire，撞 PTC 的 4 MiB 值上限），虽然文件已经写成功了。
     */
    @Test
    fun writeEchoIsTruncatedButTheFileOnDiskIsWhole() = runBlocking {
        val content = "x".repeat(5 * 1024 * 1024)
        val result = WriteTool.execute(
            buildJsonObject {
                put("file_path", "big.txt")
                put("content", content)
            },
            ctx(),
        )
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        val file = File(root, "big.txt")
        assertEquals(content.length.toLong(), file.length())
        assertEquals(content, file.readText())

        val value = ok.value!!.jsonObject
        assertEquals(file.canonicalFile.absolutePath, value["path"]!!.jsonPrimitive.content)
        assertTrue(value["before"] is JsonNull)
        assertEquals(0, value["beforeBytes"]!!.jsonPrimitive.content.toInt())
        assertEquals(content.length, value["afterBytes"]!!.jsonPrimitive.content.toInt())
        val echo = value["after"]!!.jsonPrimitive.content
        assertTrue(
            echo.takeLast(80),
            echo.endsWith("… (truncated: showing the first 262144 of " + content.length + " bytes)"),
        )
        assertEquals(256 * 1024, echo.substringBefore("\n…").toByteArray(Charsets.UTF_8).size)
    }

    /** edit 一个 3 MiB 文件：替换生效、磁盘完整，两路回显都被截并报原始大小 */
    @Test
    fun editEchoIsTruncatedButTheReplacementIsApplied() = runBlocking {
        val before = "a".repeat(1_500_000) + "TARGET" + "b".repeat(1_500_000)
        val after = before.replace("TARGET", "REPLACED")
        val file = File(root, "big.txt").apply { writeText(before) }
        val result = EditTool.execute(
            buildJsonObject {
                put("file_path", "big.txt")
                put("old_string", "TARGET")
                put("new_string", "REPLACED")
            },
            ctx(),
        )
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val value = (result as ToolResult.Ok).value!!.jsonObject
        assertEquals(after, file.readText())
        assertEquals(file.canonicalFile.absolutePath, value["path"]!!.jsonPrimitive.content)
        assertEquals(before.toByteArray(Charsets.UTF_8).size, value["beforeBytes"]!!.jsonPrimitive.content.toInt())
        assertEquals(after.toByteArray(Charsets.UTF_8).size, value["afterBytes"]!!.jsonPrimitive.content.toInt())
        assertTrue(
            value["before"]!!.jsonPrimitive.content.takeLast(80),
            value["before"]!!.jsonPrimitive.content.endsWith(
                "… (truncated: showing the first 262144 of " + before.length + " bytes)",
            ),
        )
        assertTrue(
            value["after"]!!.jsonPrimitive.content.takeLast(80),
            value["after"]!!.jsonPrimitive.content.endsWith(
                "… (truncated: showing the first 262144 of " + after.length + " bytes)",
            ),
        )
    }

    /** read 的行口径（末尾 \n 不多一行）与 value.path（绝对路径；信封正文仍是相对路径） */
    @Test
    fun readCountsDshLinesAndReportsTheAbsoluteValuePath() = runBlocking {
        val file = File(root, "two.txt").apply { writeText("a\nb\n") }
        val result = ReadTool.execute(buildJsonObject { put("file_path", "two.txt") }, ctx())
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        val value = ok.value!!.jsonObject
        assertEquals(file.canonicalFile.absolutePath, value["path"]!!.jsonPrimitive.content)
        assertEquals(2, value["totalLines"]!!.jsonPrimitive.content.toInt())
        val lines = value["lines"]!!.jsonArray
        assertEquals(2, lines.size)
        assertEquals("a", lines[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("b", lines[1].jsonObject["text"]!!.jsonPrimitive.content)
        // 信封正文仍用 display：界面卡片靠它保持简短
        assertTrue(ok.text, ok.text.startsWith("<path>two.txt</path>"))
        assertTrue(ok.text, ok.text.contains("(End of file - total 2 lines)"))
    }

    /** workdir 指向普通文件与不存在，必须是两条不同的话 */
    @Test
    fun bashTellsAFileWorkdirApartFromAMissingOne() = runBlocking {
        val file = File(root, "not-a-dir.txt").apply { writeText("x") }
        fun args(workdir: String) = buildJsonObject {
            put("command", "pwd")
            put("description", "print the working directory")
            put("workdir", workdir)
        }
        assertEquals(
            "working directory is not a directory: " + file.canonicalFile.absolutePath,
            (BashTool.execute(args("not-a-dir.txt"), ctx()) as ToolResult.Error).message,
        )
        assertEquals(
            "working directory does not exist: " + File(root, "missing").canonicalFile.absolutePath,
            (BashTool.execute(args("missing"), ctx()) as ToolResult.Error).message,
        )
    }

    /** 回显截断落在字符边界（3 字节的汉字在 262144 处被退回 262143） */
    @Test
    fun echoTruncationNeverSplitsACharacter() {
        val text = "中".repeat(200_000)
        val echo = echoText(text)
        assertEquals(600_000, echo.bytes)
        val body = echo.text.substringBefore("\n…")
        assertEquals("中".repeat(87_381), body)
        assertEquals(262_143, body.toByteArray(Charsets.UTF_8).size)
        assertTrue(echo.text.endsWith("… (truncated: showing the first 262143 of 600000 bytes)"))

        // 没超限：原样返回、不打标记
        val short = echoText("hello")
        assertEquals("hello", short.text)
        assertEquals(5, short.bytes)
    }

    // ---------------------------------------------------------------- read 流式窗口（dsh buildWindow）

    /** 500k 行 ≈ 6 MB：超过 4 MiB 的文件照样能读 —— 流式出首屏并给出精确总行数 */
    @Test
    fun readStreamsAFileLargerThanTheOldFourMegabyteGate() = runBlocking {
        val count = 500_000
        val file = File(root, "big.log").apply {
            bufferedWriter(Charsets.UTF_8).use { out -> for (i in 1..count) out.write("line " + i + "\n") }
        }
        assertTrue("fixture must exceed the old 4 MiB gate: " + file.length(), file.length() > 4L * 1024 * 1024)

        val result = ReadTool.execute(buildJsonObject { put("file_path", "big.log") }, ctx())
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        val value = ok.value!!.jsonObject
        assertEquals(count, value["totalLines"]!!.jsonPrimitive.content.toInt())
        val lines = value["lines"]!!.jsonArray
        assertEquals(2000, lines.size)
        assertEquals("line 1", lines[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(ok.text, ok.text.contains("(Showing lines 1-2000 of 500000. Use offset=2001 to continue.)"))
    }

    @Test
    fun readPagesIntoTheTailOfALargeFile() = runBlocking {
        val count = 500_000
        File(root, "big.log").bufferedWriter(Charsets.UTF_8).use { out ->
            for (i in 1..count) out.write("line " + i + "\n")
        }
        val offset = count - 4
        val result = ReadTool.execute(
            buildJsonObject {
                put("file_path", "big.log")
                put("offset", offset)
                put("limit", 10)
            },
            ctx(),
        )
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        val lines = ok.value!!.jsonObject["lines"]!!.jsonArray
        assertEquals(5, lines.size)
        assertEquals(offset, lines[0].jsonObject["number"]!!.jsonPrimitive.content.toInt())
        assertEquals("line 500000", lines[4].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(ok.text, ok.text.contains("(End of file - total 500000 lines)"))
    }

    /** 无换行的 4.5 MiB 单行：行缓冲有界，绝不整行进内存；正文按 2000 字符截断 */
    @Test
    fun readCapsAGiantSingleLineWithoutLoadingItWhole() = runBlocking {
        val file = File(root, "one-line.txt").apply { writeText("x".repeat(4_718_592)) }
        assertTrue(file.length() > 4L * 1024 * 1024)
        val result = ReadTool.execute(buildJsonObject { put("file_path", "one-line.txt") }, ctx())
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val value = (result as ToolResult.Ok).value!!.jsonObject
        assertEquals(1, value["totalLines"]!!.jsonPrimitive.content.toInt())
        val lines = value["lines"]!!.jsonArray
        assertEquals(1, lines.size)
        val text = lines[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.endsWith("... (line truncated to 2000 chars)"))
        assertEquals(2000 + "... (line truncated to 2000 chars)".length, text.length)
    }

    /** 严格 UTF-8 是**全文**判据（dsh 的 buildWindow 扫完整文件）：非法字节在窗口之外也要拒绝 */
    @Test
    fun readRejectsInvalidUtf8AnywhereInTheFile() = runBlocking {
        val head = "line\n".repeat(200_000).toByteArray(Charsets.UTF_8)
        File(root, "bad.dat").writeBytes(head + byteArrayOf(0xC3.toByte(), 0x28))
        val result = ReadTool.execute(
            buildJsonObject {
                put("file_path", "bad.dat")
                put("limit", 1)
            },
            ctx(),
        )
        assertEquals("cannot read \"bad.dat\": invalid UTF-8 text", (result as ToolResult.Error).message)
    }

    /** 二进制采样只看前 8192 字节（dsh 的 BINARY_SAMPLE_BYTES）：更靠后的 NUL 不否决读取 */
    @Test
    fun readSamplesOnlyTheFirstEightKilobytesForBinary() = runBlocking {
        val early = ByteArray(9_000) { 'a'.code.toByte() }.also { it[100] = 0 }
        File(root, "nul-early.dat").writeBytes(early)
        assertEquals(
            "cannot read \"nul-early.dat\": binary file",
            (ReadTool.execute(buildJsonObject { put("file_path", "nul-early.dat") }, ctx()) as ToolResult.Error).message,
        )

        val late = ByteArray(9_000) { 'a'.code.toByte() }.also { it[8_999] = 0 }
        File(root, "nul-late.dat").writeBytes(late)
        val ok = ReadTool.execute(buildJsonObject { put("file_path", "nul-late.dat") }, ctx())
        val failure = (ok as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, ok is ToolResult.Ok)
        assertEquals(1, (ok as ToolResult.Ok).value!!.jsonObject["totalLines"]!!.jsonPrimitive.content.toInt())
    }

    /** 参数形状非法：各工具报自己的入参错误，不许把 kotlinx 的异常/类名交给程序 */
    @Test
    fun shapedArgumentsReturnTheToolsOwnErrors() = runBlocking {
        val readResult = ReadTool.execute(
            buildJsonObject { put("file_path", buildJsonObject { put("x", 1) }) },
            ctx(),
        )
        assertEquals("file_path must be a non-empty string", (readResult as ToolResult.Error).message)

        val bashResult = BashTool.execute(
            buildJsonObject {
                put("command", buildJsonArray { add(1) })
                put("description", "x")
            },
            ctx(),
        )
        assertEquals("invalid command: expected a non-empty string", (bashResult as ToolResult.Error).message)

        val editResult = EditTool.execute(
            buildJsonObject {
                put("file_path", "x.txt")
                put("old_string", buildJsonObject { put("x", 1) })
                put("new_string", "y")
            },
            ctx(),
        )
        assertEquals("old_string must be a non-empty string", (editResult as ToolResult.Error).message)
    }

    /** 空文件与越界 offset 的判据不变（dsh：空文件 offset=1 合法，其它越界报错） */
    @Test
    fun readKeepsEmptyFileAndOffsetOutOfRangeSemantics() = runBlocking {
        File(root, "empty.txt").writeText("")
        val empty = ReadTool.execute(buildJsonObject { put("file_path", "empty.txt") }, ctx())
        val emptyOk = empty as ToolResult.Ok
        val emptyValue = emptyOk.value!!.jsonObject
        assertEquals(0, emptyValue["totalLines"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, emptyValue["lines"]!!.jsonArray.size)
        assertTrue(emptyOk.text, emptyOk.text.contains("(End of file - total 0 lines)"))

        File(root, "two.txt").writeText("a\nb\n")
        val beyond = ReadTool.execute(
            buildJsonObject {
                put("file_path", "two.txt")
                put("offset", 3)
            },
            ctx(),
        )
        assertEquals(
            "offset 3 is out of range for \"two.txt\" (2 lines)",
            (beyond as ToolResult.Error).message,
        )
    }

    /** grep 的 include 是「一个 glob」：顶层逗号是列表（dsh 原文拒绝），{} 内的逗号是 alternation */
    @Test
    fun grepRejectsACommaSeparatedInclude() = runBlocking {
        val comma = GrepTool.execute(
            buildJsonObject {
                put("pattern", "x")
                put("include", "*.ts,*.js")
            },
            ctx(),
        )
        assertEquals(
            "include must be one glob, not a comma-separated list (use {a,b} alternation instead)",
            (comma as ToolResult.Error).message,
        )

        // {} 内的逗号合法：校验通过后因为假 Shell 没有 rg 才失败
        val alternation = GrepTool.execute(
            buildJsonObject {
                put("pattern", "x")
                put("include", "*.{ts,js}")
            },
            ctx(),
        )
        assertEquals(searchLaunchFailed("grep"), (alternation as ToolResult.Error).message)
    }

    // ---------------------------------------------------------------- edit 的行尾与规模

    /** dsh 的 edit 在 LF 归一化后匹配、写回时恢复 CRLF：old_string 里写 \n 是常态 */
    @Test
    fun editMatchesCrlfContentWithLfOldStringAndRestoresCrlfOnWrite() = runBlocking {
        val file = File(root, "crlf.txt").apply { writeText("one\r\ntwo\r\nthree\r\n") }
        val result = EditTool.execute(
            buildJsonObject {
                put("file_path", "crlf.txt")
                put("old_string", "two\nthree")
                put("new_string", "TWO\nTHREE")
            },
            ctx(),
        )
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        assertEquals("one\r\nTWO\r\nTHREE\r\n", file.readText())
    }

    /**
     * 5 MB 单行 + replace_all：单趟字面替换。旧实现用 Regex 物化上百万个匹配，真机上把主进程
     * 拖进 GC/内存抖动，一次 edit 挂死整个工具网关（深度测试 P0）；这里同时钉住结果与耗时。
     */
    @Test(timeout = 30_000)
    fun editReplaceAllOnAFiveMegabyteSingleLineStaysSinglePass() = runBlocking {
        val file = File(root, "big5m.txt").apply { writeText("z".repeat(5 * 1024 * 1024)) }
        val result = EditTool.execute(
            buildJsonObject {
                put("file_path", "big5m.txt")
                put("old_string", "zzz")
                put("new_string", "YYY")
                put("replace_all", true)
            },
            ctx(),
        )
        val failure = (result as? ToolResult.Error)?.message
        assertTrue("unexpected error: " + failure, result is ToolResult.Ok)
        val edited = file.readText()
        assertEquals(5 * 1024 * 1024, edited.length)
        assertTrue(edited.take(6), edited.startsWith("YYYYYY"))
        assertTrue(edited.takeLast(2), edited.endsWith("zz"))
        assertEquals(1_747_626, countOccurrences(edited, "YYY"))
    }

    /** 孤立代理项不许被静默写成 '?'，也不能落盘 */
    @Test
    fun writeRejectsAnUnpairedSurrogate() = runBlocking {
        val result = WriteTool.execute(
            buildJsonObject {
                put("file_path", "bad.txt")
                put("content", "a\uD800b")
            },
            ctx(),
        )
        assertEquals(
            "content contains an unpaired UTF-16 surrogate; send valid UTF-8 text",
            (result as ToolResult.Error).message,
        )
        assertTrue("孤立代理项的正文不该落盘", !File(root, "bad.txt").exists())
    }
}
