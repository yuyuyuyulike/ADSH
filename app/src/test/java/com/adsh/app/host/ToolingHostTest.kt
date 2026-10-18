package com.adsh.app.host

import android.app.Application
import android.content.Context
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.workspace.WorkspaceManager
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import com.adsh.app.seam.ptc.CodeRunResult
import com.adsh.app.seam.ptc.PtcRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 组合根的契约：`ToolingHost` 出来的 [com.adsh.app.core.tools.ToolContext]
 * 全局项来自 settings / 注入的运行时与 PTC 提供者，每轮项来自调用参数 ——
 * 不经过 ChatViewModel 的任何状态。
 *
 * Application 换成裸的：AdshApp.onCreate 会 loadLibrary(quickjs-android-wrapper)，宿主 JVM 上没有这个 .so。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToolingHostTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var shell: FakeShell
    private lateinit var ptc: FakePtc
    private lateinit var host: ToolingHost

    /** 只为构造 [WorkspaceManager] / [ToolingHost]：本组用例不起进程 */
    private class FakeShell(private val dir: File) : Shell {
        override val bashPath = "/nonexistent/bash"
        override val ripgrepPath = "/nonexistent/rg"

        override fun tmpDir(): File = dir
        override fun scratchDir(): File = dir
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

    private class FakePtc : PtcRunner {
        override val defaultTimeoutMs = 1L
        override val maxTimeoutMs = 2L

        override fun run(
            appContext: Context,
            program: String,
            toolNames: List<String>,
            timeoutMs: Long,
            cancel: () -> Boolean,
            call: suspend (name: String, argsJson: String) -> String,
        ): CodeRunResult = throw UnsupportedOperationException("unit test must not run PTC")
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shell = FakeShell(context.filesDir)
        ptc = FakePtc()
        settings = SettingsStore(context)
        host = ToolingHost(context, settings, shell, WorkspaceManager(context, shell), ptc)
    }

    @Test
    fun globalFieldsComeFromSettingsAndInjectedProviders() {
        settings.webSearchProvider = "exa"
        settings.webSearchBaseUrl = "https://search.example"
        settings.webSearchApiKey = "sk-test"
        settings.webSearchMaxUses = 9
        settings.agentMaxParallel = 3
        settings.bashTimeoutMs = 111_000L
        settings.bashMaxOutputBytes = 22_000
        settings.bashMaxTimeoutMs = 333_000L
        settings.permission = Permissions.READ_ONLY
        settings.model = "qwen-test-model"

        val ctx = host.contextFor(
            conversationId = 42L,
            workspacePath = null,
            turn = 0,
            planMode = false,
            onPlanModeChanged = {},
        )

        assertSame(context, ctx.appContext)
        assertSame(shell, ctx.runtime)
        assertSame(ptc, ctx.ptcRunner)
        assertEquals("exa", ctx.webSearchProvider)
        assertEquals("https://search.example", ctx.webSearchBaseUrl)
        assertEquals("sk-test", ctx.webSearchApiKey)
        assertEquals(9, ctx.webSearchMaxUses)
        assertEquals(3, ctx.maxParallelSubCalls)
        assertEquals(111_000L, ctx.bashTimeoutMs)
        assertEquals(22_000, ctx.bashMaxOutputBytes)
        assertEquals(333_000L, ctx.bashMaxTimeoutMs)
        assertEquals(Permissions.READ_ONLY, ctx.permission)
        assertEquals("qwen-test-model", ctx.modelId)
        assertEquals(settings.modelAcceptsImages("qwen-test-model"), ctx.imageCapable)
        assertEquals(42L, ctx.conversationId)
        assertNull("没有工作区就没有附件落点", ctx.attachmentDir)
    }

    /** 每轮才变的东西（工作区 / 轮次 / 计划模式 / 回调）由调用方给，宿主不读界面状态 */
    @Test
    fun perTurnFieldsComeFromCallArguments() {
        val workspacePath = File(context.filesDir, "ws").absolutePath
        var approvedPlanTo: Boolean? = null

        val ctx = host.contextFor(
            conversationId = 7L,
            workspacePath = workspacePath,
            turn = 5,
            planMode = true,
            onPlanModeChanged = { approvedPlanTo = it },
        )

        assertEquals(File(workspacePath, ".adsh/attachments/7"), ctx.attachmentDir)
        assertEquals(5, ctx.turn)
        assertEquals(7L, ctx.conversationId)
        assertTrue(ctx.planMode)
        ctx.onPlanModeChanged!!(false)
        assertEquals(false, approvedPlanTo)
    }

    /** livePermission 是**实时读取器**：本轮中途改权限，下一次受限调用按新的判（快照字段不变） */
    @Test
    fun livePermissionReadsSettingsOnEveryCall() {
        settings.permission = Permissions.READ_ONLY
        val ctx = host.contextFor(7L, null, 0, false) {}

        assertEquals(Permissions.READ_ONLY, ctx.permission)
        settings.permission = Permissions.FULL_ACCESS
        assertEquals(Permissions.FULL_ACCESS, ctx.livePermission!!())
        assertEquals("快照按构造时的预设，不跟着变", Permissions.READ_ONLY, ctx.permission)
    }
}
