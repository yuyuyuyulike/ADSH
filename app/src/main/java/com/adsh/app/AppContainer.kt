package com.adsh.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.adsh.app.core.agent.AgentLoop
import com.adsh.app.core.agent.PromptAssembler
import com.adsh.app.core.agent.compaction.CompactionHooks
import com.adsh.app.core.agent.compaction.CompactionService
import com.adsh.app.core.data.AdshDatabase
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.LlmClient
import com.adsh.app.core.llm.ProviderConfig
import com.adsh.app.core.workspace.WorkspaceManager
import com.adsh.app.seam.exec.filePolicyOf
import com.adsh.app.host.ToolingHost
import com.adsh.app.island.IslandBus
import com.adsh.app.island.IslandController
import com.adsh.app.runtime.termux.TermuxRuntime
import com.adsh.app.ui.chat.ChatViewModel

/**
 * 组合根（`docs/ARCHITECTURE.md` §五）：有状态对象只在 `:app` 这一处创建，别处一律**接收**。
 * 「谁依赖谁」于是只写在构造函数上 —— 界面不会自己 `new` 数据库，循环也不会自己造 LLM 通道。
 *
 * 只建一次：[com.adsh.app.AdshApp] 持有（懒建，首次进 Activity 时发生），Activity 重建不重建这些对象。
 * 换 Hilt 的触发条件见 `docs/TECH-STACK.md`（装配点 >15 / 多作用域 / 引入 WorkManager）。
 */
class AppContainer(private val application: Application) {

    val settings: SettingsStore = SettingsStore(application)

    val runtime: TermuxRuntime = TermuxRuntime(application)

    val repository: ConversationRepository = ConversationRepository(AdshDatabase.get(application))

    val workspaces: WorkspaceManager = WorkspaceManager(application, runtime)

    /** 标题与 `/compact` 等一次性小调用单独一条 LLM 通道（不经过 AgentLoop 的工具循环） */
    val compactLlm: LlmClient = LlmClient(configProvider = { settings.providerConfig() }, logDebug = com.adsh.app.BuildConfig.DEBUG)

    /**
     * 压缩服务（dsh 的 compaction-basic）：区间选择 / 工具结果剪枝 / 摘要请求 / 历史替换都在
     * :core:agent；组合根只提供摘要请求的系统提示词（口径与迁移前的 ChatViewModel.summarize 一致）。
     */
    val compactor: CompactionService = CompactionService(
        store = repository,
        settings = settings,
        summaryLlm = compactLlm,
        systemPrompt = { planMode ->
            runCatching {
                PromptAssembler.buildParts(
                    workspace = workspaces.current(),
                    extraSuffix = settings.systemPromptSuffix,
                    filePolicy = filePolicyOf(settings.permission),
                    planMode = planMode,
                ).system
            }.getOrDefault("")
        },
    )

    /**
     * 主循环：LLM 通道每次现取 `settings.providerConfig()`，设置里换模型 / Key 立刻生效。
     * 自动压缩挂在两个决策点上（请求装配前的 pressure、请求失败后的 overflow）；
     * `AgentHooks.None` 时主循环行为与没有决策点时逐字一致。
     */
    val agent: AgentLoop = AgentLoop(
        LlmClient(configProvider = { settings.providerConfig() }, logDebug = com.adsh.app.BuildConfig.DEBUG),
        repository,
        settings,
        logDebug = com.adsh.app.BuildConfig.DEBUG,
        hooks = CompactionHooks(compactor),
    )

    /**
     * 工具上下文的装配点：全局项（运行时 / 14 项设置 / PTC 提供者实现）在这里读，
     * 界面只给每轮才变的东西。见 [ToolingHost]。
     */
    val tooling: ToolingHost = ToolingHost(application, settings, runtime, workspaces, com.adsh.app.platform.ptc.PtcProcess)

    /**
     * 设置页「获取模型」的一次性 LLM 通道（dsh 的 listModels）：列一次模型就丢，不进 AgentLoop 的账。
     * 由组合根建，界面只给提供方坐标。
     */
    fun newLlmClient(config: ProviderConfig): LlmClient =
        LlmClient(configProvider = { config }, logDebug = com.adsh.app.BuildConfig.DEBUG)
}

/**
 * `ChatViewModel` 的工厂：默认工厂建不出带参构造。
 *
 * 类的判据用 `isAssignableFrom` 而不是相等：`viewModel(factory = …)` 传进来的就是这个类，
 * 但放宽一点以后加子类时不会先炸在这里。
 */
class ChatViewModelFactory(
    private val application: Application,
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "unknown ViewModel: " + modelClass.name
        }
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(
            app = application,
            repository = container.repository,
            settings = container.settings,
            runtime = container.runtime,
            workspaces = container.workspaces,
            agent = container.agent,
            compactLlm = container.compactLlm,
            compactor = container.compactor,
            tooling = container.tooling,
            onIslandFrame = { work -> IslandController.sync(application, work) },
            islandStopRequests = IslandBus.stopRequests,
        ) as T
    }
}
