package com.adsh.app.host

import android.content.Context
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.tools.ToolContext
import com.adsh.app.core.workspace.WorkspaceManager
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.ptc.PtcRunner
import com.adsh.app.ui.chat.ToolContextFactory
import java.io.File

/**
 * 工具上下文的装配点（`docs/ARCHITECTURE.md` §五 的组合根）。
 *
 * 全局项 —— 应用上下文、运行时、14 项设置、实时权限读取器 —— 在这里读；
 * 每轮才变的东西（会话 id、附件落点根、轮次、计划模式、计划批准回调）由 [contextFor] 传参。
 *
 * 放 `:app` 而不是 `:core:tools`：只有组合根认识 [ptcRunner] 的提供者实现（PtcProcess），
 * 消费者 `RunCodeTool` 只认 `PtcRunner` 端口。:feature:chat 只见 [ToolContextFactory] 这张脸。
 */
class ToolingHost(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val runtime: Shell,
    private val workspaces: WorkspaceManager,
    private val ptcRunner: PtcRunner,
) : ToolContextFactory {

    /**
     * 这一轮的工具上下文。
     *
     * @param workspacePath 会话自己的工作区（附件落点用它；null = 没有工作区，read_image 无副本落点）
     * @param turn 这一轮是第几轮（dsh 的 present 输出带 turn）
     * @param planMode 本轮是否处于计划模式（exit_plan_mode 只在计划模式里可用）
     * @param onPlanModeChanged 计划被批准后的回调（写回会话的 planMode）
     */
    override fun contextFor(
        conversationId: Long,
        workspacePath: String?,
        turn: Int,
        planMode: Boolean,
        onPlanModeChanged: (Boolean) -> Unit,
    ): ToolContext = ToolContext(
        workspace = workspaces.current(),
        // run_code 要把 PTC 程序绑到 `:ptc` 进程上跑（dsh 的独立进程隔离，见 PtcProcess）
        appContext = appContext,
        // 消费者只认 seam.ptc 的 PtcRunner 端口；提供者实现由组合根注入
        ptcRunner = ptcRunner,
        runtime = runtime,
        webSearchProvider = settings.webSearchProvider,
        webSearchBaseUrl = settings.webSearchBaseUrl,
        webSearchApiKey = settings.webSearchApiKey,
        webSearchMaxUses = settings.webSearchMaxUses,
        maxParallelSubCalls = settings.agentMaxParallel,
        bashTimeoutMs = settings.bashTimeoutMs,
        bashMaxOutputBytes = settings.bashMaxOutputBytes,
        bashMaxTimeoutMs = settings.bashMaxTimeoutMs,
        permission = settings.permission,
        // 受限调用现读一次预设：本轮中途改了权限，下一次 bash / write / edit 就按新的判
        livePermission = { settings.permission },
        // read_image：模型能力闸门 + 图片副本的落点 + 报错里回显的模型 id
        modelId = settings.model,
        imageCapable = settings.modelAcceptsImages(settings.model),
        attachmentDir = workspacePath?.let { File(it, ".adsh/attachments/" + conversationId) },
        turn = turn,
        conversationId = conversationId,
        planMode = planMode,
        onPlanModeChanged = onPlanModeChanged,
    )
}
