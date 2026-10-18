package com.adsh.app.core.tools

import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import com.adsh.app.seam.tool.PresentedFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
 * present 的路径口径（dsh 的 processPath）：value.files[].path 与正文 Presented <path> 都是解析后的
 * 绝对路径；轮尾交付卡片（deliverables）仍用工作区相对的展示路径（Args.display）。
 */
class PresentPathTest {

    private lateinit var root: File
    private lateinit var outside: File

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
        turn = 7,
    )

    @Before
    fun setUp() {
        root = tempDir("present-ws")
        outside = tempDir("present-outside")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    private fun tempDir(prefix: String): File = File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    private fun present(vararg files: Pair<String, String?>): ToolResult = runBlocking {
        val args = buildJsonObject {
            put("files", buildJsonArray {
                files.forEach { (path, description) ->
                    add(
                        buildJsonObject {
                            put("path", path)
                            if (description != null) put("description", description)
                        },
                    )
                }
            })
        }
        PresentTool.execute(args, ctx())
    }

    private fun ok(result: ToolResult): ToolResult.Ok {
        assertTrue("unexpected error: " + (result as? ToolResult.Error)?.message, result is ToolResult.Ok)
        return result as ToolResult.Ok
    }

    private fun fileValue(result: ToolResult.Ok, index: Int = 0): JsonObject =
        result.value!!.jsonObject["files"]!!.jsonArray[index].jsonObject

    @Test
    fun relativePathIsReportedAsResolvedAbsolutePath() {
        val file = File(root, "out/report.md").apply { parentFile!!.mkdirs(); writeText("# report") }
        val absolute = file.canonicalFile.path

        val result = ok(present("out/report.md" to "报告"))

        assertEquals(absolute, fileValue(result)["path"]!!.jsonPrimitive.content)
        assertEquals("Presented " + absolute, result.text)
        assertEquals(7, result.value!!.jsonObject["turn"]!!.jsonPrimitive.int)
        assertEquals(listOf(PresentedFile(path = "out/report.md", description = "报告")), result.deliverables)
    }

    @Test
    fun absolutePathInsideWorkspaceIsCanonicalized() {
        val file = File(root, "nested/plan.md").apply { parentFile!!.mkdirs(); writeText("# plan") }
        val absolute = file.canonicalFile.path
        val nonCanonical = File(root, "nested/../nested/plan.md").path

        val result = ok(present(nonCanonical to null))

        assertEquals(absolute, fileValue(result)["path"]!!.jsonPrimitive.content)
        assertEquals("Presented " + absolute, result.text)
        assertEquals(listOf(PresentedFile(path = "nested/plan.md")), result.deliverables)
    }

    @Test
    fun pathOutsideWorkspaceIsReportedAbsoluteAndStaysAbsoluteInDeliverables() {
        val file = File(outside, "clip.txt").apply { writeText("x") }
        val absolute = file.canonicalFile.path

        val result = ok(present(absolute to "外部"))

        assertEquals(absolute, fileValue(result)["path"]!!.jsonPrimitive.content)
        assertEquals("Presented " + absolute, result.text)
        assertEquals(listOf(PresentedFile(path = absolute, description = "外部")), result.deliverables)
    }
}
