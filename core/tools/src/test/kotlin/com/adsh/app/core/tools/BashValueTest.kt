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
import kotlinx.serialization.json.long
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

    /**
     * 截断提示只属于正文渲染（dsh 的 render.ts）；结构值必须是纯文本尾部（canonicalBashResult），
     * 否则程序解析合法 JSON 输出时会被追加的那行提示直接打断。
     */
    @Test
    fun foregroundTruncationNoticeStaysOutOfTheStructuredValue() = runBlocking {
        shell.next = FakeProcess(out = ByteArray(200_000) { 'a'.code.toByte() })

        val result = BashTool.execute(args(), ctx())
        val stdout = okValue(result)["stdout"]!!.jsonObject

        val text = stdout["text"]!!.jsonPrimitive.content
        assertEquals("value 里只留 bashMaxOutputBytes 的纯尾部", 64_000, text.length)
        assertFalse("value 里不许出现展示提示", text.contains("[output truncated"))
        assertEquals("true", stdout["truncated"]!!.jsonPrimitive.content)
        assertNotNull("真的丢字节时必须给出 spillPath：", stdout["spillPath"])

        val rendered = (result as ToolResult.Ok).text
        assertTrue(rendered, rendered.contains("[output truncated; full output: "))
    }

    /**
     * 真机 agent 复测：早期在结算裁剪**之后**算 droppedChannels，把「已经完整读到」的小输出
     * 误标成 truncated 并砍掉首行（2000 行 / 20 KB、6000 行 / 60 KB、单行 16385、恰好 64000
     * 全部中招）。truncated 只允许表示「真的丢了字节」。
     */
    @Test
    fun `小于输出上限的多行输出完整返回且不标 truncated`() = runBlocking {
        val rows = buildString { repeat(2_000) { append("%09d\n".format(it)) } }.toByteArray()
        assertEquals(20_000, rows.size)
        shell.next = FakeProcess(out = rows)

        val value = okValue(BashTool.execute(args(), ctx()))
        val stdout = value["stdout"]!!.jsonObject
        val text = stdout["text"]!!.jsonPrimitive.content

        assertEquals("false", stdout["truncated"]!!.jsonPrimitive.content)
        assertFalse("没丢字节就不该有 omittedBytes", stdout.containsKey("omittedBytes"))
        assertEquals("2000 行必须原样返回（首行也在）", 20_000, text.toByteArray(Charsets.UTF_8).size)
        assertTrue(text, text.startsWith("000000000\n"))
        assertTrue(text, text.endsWith("000001999\n"))
    }

    /** 刚好越过上限 1 字节：只剩尾巴 + 行对齐丢半行 + spill 全文；omittedBytes 把丢的都算上 */
    @Test
    fun `64001 字节：行对齐 + omittedBytes + spill`() = runBlocking {
        val rows = buildString { repeat(6_400) { append("%09d\n".format(it)) } }.toByteArray()
        shell.next = FakeProcess(out = rows + "X".toByteArray())

        val stdout = okValue(BashTool.execute(args(), ctx()))["stdout"]!!.jsonObject
        val text = stdout["text"]!!.jsonPrimitive.content

        assertEquals("true", stdout["truncated"]!!.jsonPrimitive.content)
        assertTrue(text, text.startsWith("000000001\n"))
        assertTrue(text, text.endsWith("000006399\nX"))
        assertEquals(64_001L - text.toByteArray(Charsets.UTF_8).size, stdout["omittedBytes"]!!.jsonPrimitive.long)
        assertNotNull("真的丢字节时必须有 spillPath", stdout["spillPath"])
    }

    @Test
    fun `60 KB 多行输出也完整返回`() = runBlocking {
        val rows = buildString { repeat(6_000) { append("%09d\n".format(it)) } }.toByteArray()
        assertEquals(60_000, rows.size)
        shell.next = FakeProcess(out = rows)

        val stdout = okValue(BashTool.execute(args(), ctx()))["stdout"]!!.jsonObject
        assertEquals("false", stdout["truncated"]!!.jsonPrimitive.content)
        assertFalse(stdout.containsKey("omittedBytes"))
        assertEquals(60_000, stdout["text"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `单行 16385 字节不标 truncated`() = runBlocking {
        shell.next = FakeProcess(out = ByteArray(16_385) { 'A'.code.toByte() })

        val stdout = okValue(BashTool.execute(args(), ctx()))["stdout"]!!.jsonObject

        assertEquals("false", stdout["truncated"]!!.jsonPrimitive.content)
        assertFalse(stdout.containsKey("omittedBytes"))
        assertEquals(16_385, stdout["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `恰好上限的 64000 字节输出不标 truncated`() = runBlocking {
        // 6400 行 × 10 字节 = 64000 = bashMaxOutputBytes：一个字节都不该丢
        val rows = buildString { repeat(6_400) { append("%09d\n".format(it)) } }.toByteArray()
        assertEquals(64_000, rows.size)
        shell.next = FakeProcess(out = rows)

        val stdout = okValue(BashTool.execute(args(), ctx()))["stdout"]!!.jsonObject

        assertEquals("false", stdout["truncated"]!!.jsonPrimitive.content)
        assertEquals(64_000, stdout["text"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8).size)
    }

    /**
     * 单行大输出（`print('A'*500000)`）：尾巴里没有可对齐的行，丢掉半截首行会把内容变成空串。
     * 这时保留尾巴；丢了多少字节由 `omittedBytes` 说清楚。
     */
    @Test
    fun `单行大输出不会变成空串`() = runBlocking {
        val out = ByteArray(200_000) { 'A'.code.toByte() } + "\n".toByteArray()
        shell.next = FakeProcess(out = out)

        val result = BashTool.execute(args(), ctx())
        val stdout = okValue(result)["stdout"]!!.jsonObject
        val text = stdout["text"]!!.jsonPrimitive.content

        assertEquals("保留的仍是 64000 字节的尾巴", 64_000, text.toByteArray(Charsets.UTF_8).size)
        assertTrue("不能是空串：" + text.length, text.isNotEmpty())
        assertTrue("尾巴就是 A 跑", text.trimEnd('\n').all { it == 'A' })
        assertEquals("true", stdout["truncated"]!!.jsonPrimitive.content)
        assertEquals(200_001L - 64_000L, stdout["omittedBytes"]!!.jsonPrimitive.long)
        val rendered = (result as ToolResult.Ok).text
        assertTrue(rendered, rendered.contains("[output truncated; full output: "))
    }

    /**
     * 尾巴从半行开始、后面还有完整行时：丢掉半截首行（行对齐），被丢的字节数由 omittedBytes 给出，
     * 不再只能靠「看起来像不像连续序列」猜。
     */
    @Test
    fun `中段超长行只丢半截首行其余照常`() = runBlocking {
        val head = "L1\nL2\n".toByteArray()
        val longLine = ByteArray(400_000) { 'B'.code.toByte() }
        val tail = "\nL3\nL4\n".toByteArray()
        shell.next = FakeProcess(out = head + longLine + tail)

        val result = BashTool.execute(args(), ctx())
        val stdout = okValue(result)["stdout"]!!.jsonObject
        val text = stdout["text"]!!.jsonPrimitive.content

        assertEquals("丢掉的只是半截长行，后面的行照常保留", "L3\nL4\n", text)
        assertEquals("true", stdout["truncated"]!!.jsonPrimitive.content)
        assertEquals((head.size + longLine.size + tail.size).toLong() - 6L, stdout["omittedBytes"]!!.jsonPrimitive.long)
        val rendered = (result as ToolResult.Ok).text
        assertTrue(rendered, rendered.contains("[output truncated; full output: "))
    }

    /**
     * 字节对齐的尾巴可能从半行开始：首行必须是完整行（丢掉半截首行），
     * 否则 `seq` 这类输出会被读成「从 90858 开始」的假序列。
     */
    @Test
    fun foregroundTruncatedTailStartsAtAWholeLine() = runBlocking {
        // 220 KB 的定长 11 字节行：64 KB 尾巴必然从半行开始
        shell.next = FakeProcess(out = "0123456789\n".repeat(20_000).toByteArray())

        val result = BashTool.execute(args(), ctx())
        val stdout = okValue(result)["stdout"]!!.jsonObject
        val text = stdout["text"]!!.jsonPrimitive.content

        assertEquals("首行是完整行", "0123456789", text.lineSequence().first())
        assertTrue("丢掉半截首行后长度略小于 64000", text.length in 63_985..64_000)
        assertEquals("true", stdout["truncated"]!!.jsonPrimitive.content)
        // 完整输出 220000 字节；omittedBytes = 220000 - 保留文本字节数（行对齐丢掉的半行也算在内）
        assertEquals(220_000L - text.toByteArray(Charsets.UTF_8).size, stdout["omittedBytes"]!!.jsonPrimitive.long)
        val rendered = (result as ToolResult.Ok).text
        assertTrue(rendered, rendered.startsWith("0123456789\n"))
        assertTrue(rendered, rendered.contains("[output truncated; full output: "))
    }
}
