package com.adsh.app.core.tools

import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 只为构造 ToolContext 的 Shell 替身：用例里的假工具不碰进程与文件。 */
internal class StubShell(private val tmp: File) : Shell {
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

internal fun testToolContext(execToken: Long = 7L): ToolContext {
    val root = File(System.getProperty("java.io.tmpdir"), "adsh-tools-unit-test")
    return ToolContext(
        workspace = Workspace(WorkspaceForm.PRIVATE, root, "test"),
        runtime = StubShell(root),
        permission = Permissions.FULL_ACCESS,
        execToken = execToken,
    )
}

/** 一次工具调用的替身：spec 与行为由用例给（行为里可以抛异常/挂起）。 */
internal class StubTool(
    name: String,
    override val parallelSafe: Boolean = false,
    private val body: suspend (JsonObject) -> ToolResult,
) : Tool {
    override val spec = ToolSpec(name = name, description = "stub " + name, args = "{}", output = "{}")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = body(args)
}
