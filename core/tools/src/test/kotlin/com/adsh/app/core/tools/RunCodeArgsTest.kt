package com.adsh.app.core.tools

import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * run_code 的参数契约与成功输出边界（dsh 的 dsh-tools）：
 *  - 参数在 execute 之前整条按 schema 拒绝：code/description 必填、必须是 string，
 *    violation 文案逐字；program 是 code 的旧别名；
 *  - 全空白 description 走 dsh 的 execute 文案（schema 层只查类型）；
 *  - 成功正文：显式 return null 渲染 `null`，没有 return 才是 no-output 文案。
 */
class RunCodeArgsTest {

    /** 只为构造 [ToolContext]：参数校验与空白 description 在起运行时之前返回 */
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
        workspace = Workspace(WorkspaceForm.PRIVATE, File("unused"), "test"),
        runtime = FakeShell(File("unused")),
        permission = Permissions.FULL_ACCESS,
    )

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun failureMessage(raw: String): String {
        val result = runBlocking { RunCodeTool.execute(json(raw), ctx()) }
        assertTrue("expected failure, got " + result, result is ToolResult.Error)
        return (result as ToolResult.Error).message
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    fun bothRequiredArgumentsAreReportedInSchemaOrder() {
        assertEquals(
            "invalid arguments: missing required property \"code\"; missing required property \"description\"",
            failureMessage("{}"),
        )
    }

    @Test
    fun eachMissingRequiredArgumentIsNamed() {
        assertEquals(
            "invalid arguments: missing required property \"description\"",
            failureMessage("""{"code":"return 1"}"""),
        )
        assertEquals(
            "invalid arguments: missing required property \"code\"",
            failureMessage("""{"description":"run it"}"""),
        )
    }

    @Test
    fun wrongTypesAreReportedWithTheDshWording() {
        assertEquals(
            "invalid arguments: \"code\" must be a string",
            failureMessage("""{"code":1,"description":"run it"}"""),
        )
        assertEquals(
            "invalid arguments: \"description\" must be a string",
            failureMessage("""{"code":"return 1","description":2}"""),
        )
        assertEquals(
            "invalid arguments: \"code\" must be a string",
            failureMessage("""{"code":null,"description":"run it"}"""),
        )
    }

    @Test
    fun requiredAndTypeViolationsAreJoined() {
        // dsh：required 段先收集，再接类型段，用 "; " 连接
        assertEquals(
            "invalid arguments: missing required property \"code\"; \"description\" must be a string",
            failureMessage("""{"description":7}"""),
        )
    }

    @Test
    fun blankDescriptionIsRejectedWithTheExecuteWording() {
        assertEquals("invalid description: expected a non-empty string", failureMessage("""{"code":"return 1","description":"   "}"""))
    }

    // ---------------------------------------------------------------- 旧别名 program

    @Test
    fun theLegacyProgramAliasStillParses() {
        val parsed = RunCodeTool.parseRunCodeArgs(json("""{"program":"return 2","description":"run it"}"""))
        assertTrue("unexpected rejection: " + parsed, parsed is RunCodeTool.ParsedRunCodeArgs.Accepted)
        assertEquals("return 2", (parsed as RunCodeTool.ParsedRunCodeArgs.Accepted).program)
    }

    @Test
    fun codeWinsOverTheLegacyProgramAlias() {
        val parsed = RunCodeTool.parseRunCodeArgs(json("""{"code":"return 1","program":"return 2","description":"run it"}"""))
        assertEquals("return 1", (parsed as RunCodeTool.ParsedRunCodeArgs.Accepted).program)
    }

    @Test
    fun aWrongTypedProgramAliasIsReportedUnderItsOwnName() {
        assertEquals(
            "invalid arguments: \"program\" must be a string",
            failureMessage("""{"program":5,"description":"run it"}"""),
        )
    }

    /** 旧别名通过校验之后会走到「缺 appContext」，说明它没有被参数层拒掉 */
    @Test
    fun aValidCallReachesTheRuntimeSetup() {
        assertEquals(
            "run_code 需要应用上下文才能把程序放进独立进程（ToolContext.appContext 为空）",
            failureMessage("""{"code":"return 1","description":"run it"}"""),
        )
        assertEquals(
            "run_code 需要应用上下文才能把程序放进独立进程（ToolContext.appContext 为空）",
            failureMessage("""{"program":"return 1","description":"run it"}"""),
        )
    }

    // ---------------------------------------------------------------- 成功正文

    @Test
    fun noReturnRendersTheNoOutputLine() {
        assertEquals("(run_code completed with no output)", RunCodeTool.renderRunCodeOutput("", null))
    }

    @Test
    fun anExplicitNullReturnIsAResult() {
        assertEquals("null", RunCodeTool.renderRunCodeOutput("", "null"))
    }

    @Test
    fun logsAndTheResultValueAreJoined() {
        assertEquals("logged", RunCodeTool.renderRunCodeOutput("logged", null))
        assertEquals("logged\n{\"ok\":true}", RunCodeTool.renderRunCodeOutput("logged", """{"ok":true}"""))
    }
}
