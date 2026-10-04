package com.adsh.app.core.ptc

/**
 * 一次 PTC 程序执行的结果（宿主拿到的那一份）。
 *
 * 第 183 轮起**不再带 subCalls**：子调用的轨迹由宿主侧的 [PtcToolRunner] 边跑边记
 * （工具本来就在宿主进程里执行），引擎只管「程序本身跑出了什么」。
 *
 * [failureKind] 是 dsh 失败信封里那一段（`code run failed (<kind>): <message>` 的 kind）：
 *  - `timeout`：到点了（泵循环优雅收尾，或宿主直接杀进程 —— 见 [PtcProcess]）；
 *  - `worker-exit`：`:ptc` 进程没了（崩了 / 被系统杀了），或控制通道断了
 *    （dsh 原文：control channel ended before the program settled）；
 *  - `protocol`：通道上的帧不合法（超限 / 空帧 / 不是 JSON / 调用 id 对不上 / 未声明的工具名）；
 *  - `abort`：用户按了停止；
 *  - `exception`：程序自己抛了（含语法错误）；
 *  - null：程序正常结束。
 */
data class CodeRunResult(
    val valueJson: String?,
    val logs: List<String>,
    val error: String?,
    val durationMs: Long,
    /** 程序内 await tools.x() 的次数（dsh 状态栏「步」的一部分） */
    val toolCalls: Int = 0,
    val failureKind: String? = null,
    /** 这次运行是因为用户中断而停下的，不是程序自己结束 */
    val aborted: Boolean = false,
) {
    val ok: Boolean get() = error == null
}
