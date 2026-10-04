package com.adsh.app.core.ptc

import kotlinx.serialization.Serializable

/**
 * 一次子调用（程序里的 `await tools.<name>(args)`）的轨迹：
 * 界面用它把「开始」与「结束」贴成同一行，落库用它写工具行的 subCallsJson。
 *
 * 第 183 轮从 QuickJsRuntime.kt 搬出来：引擎搬进 `:ptc` 进程之后不再产生它 ——
 * 现在由宿主侧的 [PtcToolRunner] 边执行边记（工具本来就在宿主进程里跑）。
 */
@Serializable
data class SubCall(
    val name: String,
    val args: String,
    val ok: Boolean,
    val result: String,
    val durationMs: Long,
    /** 这次子调用的稳定 id：界面用它把「开始」与「结束」贴成同一行 */
    val id: String = "",
    /**
     * 是否正在跑：只有流式期间为 true。工具行必须**先出现、后结算**（dsh 的 toolRow 形态），
     * 否则用户看到的是「工具行一出现就已经完成」，扫光永远看不到。
     */
    val running: Boolean = false,
    /**
     * 这次子调用产出的图片（read_image / 网页截图等）：界面在这一行下面渲染画廊，
     * 落库后装配下一轮请求时回灌给模型（见 AgentLoop 的 subCalls 收集）。
     */
    val images: List<com.adsh.app.core.agent.ToolImage> = emptyList(),
    /** 交付物（present 出来的文件）：轮尾的文件卡片读它 */
    val deliverables: List<com.adsh.app.core.agent.PresentedFile> = emptyList(),
)
