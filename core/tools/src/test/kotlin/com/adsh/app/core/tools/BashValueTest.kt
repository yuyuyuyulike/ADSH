package com.adsh.app.core.tools

import com.adsh.app.core.jobs.Jobs
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * bash 输出联合的三臂都必须带 cwd（dsh tool-bash 的输出 schema 里 required），
 * 前台另带可选 stopped（外部停止原因）。值只在出结果处组装，所以用假 ShellProcess 真跑
 * wait/job 两条路把它钉在这里。
 */
class BashValueTest {

    private lateinit var root: File
    private lateinit var shell: FakeShell

    /** 可控的假进程：gate 非空时阻塞到 terminate/放行，退出码固定 */
    private class FakeProcess(
        out: ByteArray = ByteArray(0),
        err: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val gate: CountDownLatch? = null,
    ) : ShellProcess {
        override val stdout = ByteArrayInputStream(out)
        override val stderr = ByteArrayInputStream(err)

        override fun waitFor(): Int {
            gate?.await()
            return exit
        }

        override fun exitValue(): Int = exit

        override fun dispose() = Unit

        override fun terminate() {
            gate?.countDown()
        }
    }

    private class FakeShell(private val tmp: File) : Shell {
        var next: ShellProcess = FakeProcess()

        override val bashPath = "/nonexistent/bash"
        override val ripgrepPath = "/nonexistent/rg"

        override fun tmpDir(): File = tmp
        override fun scratchDir(): File = tmp
        override fun cacheRoots(): List<File> = emptyList()

        override fun fenceEnv(mode: String, roots: List<File?>, mark: File?): Array<String> =
            arrayOf("ADSH_TEST_FENCE=1")

        override fun spawn(
            command: String,
            workspaceRoot: File?,
            extraEnv: Array<String>?,
            separateStreams: Boolean,
        ): ShellProcess {
            // 模拟 shim 加载：围栏哨兵出现，BashTool 才不走 Log 分支（纯 JVM 单测里 android.util.Log 不可用）
            File(tmp, ".adsh-fence-alive").writeText("")
            return next
        }

        override fun run(
            command: String,
            workspaceRoot: File?,
            timeoutMs: Long,
            maxOutputBytes: Int,
            extraEnv: Array<String>?,
            separateStreams: Boolean,
            cancel: (() -> Boolean)?,
        ): ExecResult = throw UnsupportedOperationException("bash value tests use the job path only")
    }

    @Before
    fun setUp() {
        root = File.createTempFile("bash-value", "").let { it.delete(); it.mkdirs(); it }
        shell = FakeShell(root)
    }

    @After
    fun tearDown() {
        runBlocking {
            Jobs.list(null).filter { it.kind == "bash" }.forEach { view ->
                runCatching { Jobs.kill(view.id, null, "test cleanup") }
                runCatching { Jobs.wait(view.id, 5_000, null) }
                runCatching { Jobs.remove(view.id, null) }
            }
        }
        root.deleteRecursively()
    }

    private fun ctx(): ToolContext = ToolContext(
        workspace = Workspace(WorkspaceForm.PRIVATE, root, "test"),
        runtime = shell,
        permission = Permissions.FULL_ACCESS,
    )

    private fun args(
        command: String = "true",
        workdir: String? = null,
        background: Boolean = false,
        timeoutMs: Int? = null,
    ) = buildJsonObject {
        put("command", command)
        put("description", "test command")
        if (workdir != null) put("workdir", workdir)
        if (background) put("run_in_background", true)
        if (timeoutMs != null) put("timeoutMs", timeoutMs)
    }

    private fun okValue(result: ToolResult) = (result as ToolResult.Ok).value!!.jsonObject

    private suspend fun awaitRunningBashJob(): String {
        repeat(300) {
            Jobs.list(null).firstOrNull { it.kind == "bash" && it.status == Jobs.Status.RUNNING }?.let { return it.id }
            delay(10)
        }
        throw AssertionError("no running bash job appeared")
    }

    @Test
    fun foregroundValueCarriesTheAbsoluteDefaultCwd() = runBlocking {
        shell.next = FakeProcess(out = "hi\n".toByteArray())

        val value = okValue(BashTool.execute(args(), ctx()))

        assertEquals("foreground", value["kind"]!!.jsonPrimitive.content)
        assertEquals(root.canonicalFile.absolutePath, value["cwd"]!!.jsonPrimitive.content)
        assertEquals("hi\n", value["stdout"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse("没有外部停止时不写 stopped 字段", value.containsKey("stopped"))
    }

    @Test
    fun foregroundValueCarriesAnExplicitWorkdirAsAbsolutePath() = runBlocking {
        val sub = File(root, "sub").apply { mkdirs() }
        shell.next = FakeProcess()

        val value = okValue(BashTool.execute(args(workdir = "sub"), ctx()))

        assertEquals(sub.canonicalFile.absolutePath, value["cwd"]!!.jsonPrimitive.content)
    }

    @Test
    fun promotedValueCarriesTheAbsoluteCwd() = runBlocking {
        shell.next = FakeProcess(gate = CountDownLatch(1))

        val value = okValue(BashTool.execute(args(timeoutMs = 100), ctx()))

        assertEquals("promoted", value["kind"]!!.jsonPrimitive.content)
        assertEquals(root.canonicalFile.absolutePath, value["cwd"]!!.jsonPrimitive.content)
        assertNotNull(value["jobId"]!!.jsonPrimitive.content)
        assertNotNull(value["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun backgroundValueCarriesTheAbsoluteCwd() = runBlocking {
        shell.next = FakeProcess(out = "bg\n".toByteArray())

        val value = okValue(BashTool.execute(args(background = true), ctx()))

        assertEquals("background", value["kind"]!!.jsonPrimitive.content)
        assertEquals(root.canonicalFile.absolutePath, value["cwd"]!!.jsonPrimitive.content)
        val id = value["jobId"]!!.jsonPrimitive.content
        Jobs.wait(id, 5_000, null)
        Jobs.remove(id, null)
    }

    @Test
    fun foregroundValueCarriesTheStopReason() = runBlocking {
        shell.next = FakeProcess(gate = CountDownLatch(1))
        val call = async(Dispatchers.Default) { BashTool.execute(args(timeoutMs = 60_000), ctx()) }

        Jobs.kill(awaitRunningBashJob(), null, "user pressed stop")
        val value = okValue(call.await())

        assertEquals("foreground", value["kind"]!!.jsonPrimitive.content)
        assertEquals(root.canonicalFile.absolutePath, value["cwd"]!!.jsonPrimitive.content)
        assertEquals("user pressed stop", value["stopped"]!!.jsonPrimitive.content)
        assertTrue(Jobs.list(null).none { it.kind == "bash" })
    }
}
