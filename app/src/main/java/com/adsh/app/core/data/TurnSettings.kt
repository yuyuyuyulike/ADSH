package com.adsh.app.core.data

import com.adsh.app.core.llm.ProviderConfig

/**
 * Agent 主循环面向设置项的窄端口（甲方案第 1 步）。
 *
 * 与 [TurnStore] 同理：[com.adsh.app.core.agent.AgentLoop] 的整条回合只读到这里列的 9 项设置
 * （逐一从既有调用点量出来的），生产实现就是 [SettingsStore]（类声明上
 * `class SettingsStore(context: Context) : TurnSettings`）。把 Android 的 Context /
 * SharedPreferences 挡在端口外面，测试才能在没有 Android 环境的情况下跑完整条循环。
 */
interface TurnSettings {
    /** 上一次装配提示词时使用的工作区；本轮内固定，变化时输出「基线替换」。 */
    var lastWorkspacePath: String?

    /** 当前提供方的连接参数（地址 / 密钥 / 模型 / 协议）。 */
    fun providerConfig(): ProviderConfig

    /** 当前提供方的展示用路由名（写进本轮的用量）。 */
    val providerRoute: String

    /** 这个模型是否接受图片输入（决定工具结果里的图片是发图还是发文字占位）。 */
    fun modelAcceptsImages(modelId: String): Boolean

    /** 思考档位（dsh 的 reasoning effort，空串 = 不下发）。 */
    val reasoningEffort: String

    /** 追加到系统提示词末尾的用户后缀。 */
    val systemPromptSuffix: String

    /** 权限档位（决定提示词里的 filePolicy 段）。 */
    val permission: String

    /** bash 工具的默认超时。 */
    val bashTimeoutMs: Long

    /** bash 工具的超时上限。 */
    val bashMaxTimeoutMs: Long
}
