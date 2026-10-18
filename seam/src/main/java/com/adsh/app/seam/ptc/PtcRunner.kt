package com.adsh.app.seam.ptc

import android.content.Context

/**
 * PTC 运行时端口（服务定义）：run_code 把程序交给它跑，程序里的每一次 `await tools.x(args)`
 * 回调给 [run] 的 [call]。QuickJS 进程那一侧的实现由组合根注入，消费者只认这一层。
 */
interface PtcRunner {

    /** 模型没给 timeoutMs 时的默认预算（dsh 的 ptc-runtime-node config：12e4） */
    val defaultTimeoutMs: Long

    /** 程序预算的上限（dsh 的 config maxTimeoutMs） */
    val maxTimeoutMs: Long

    /**
     * 跑一次程序：建通道 → 起 `:ptc` 进程 → 处理程序里的 `tools.x()` → 到点杀进程。
     *
     * **阻塞**到有结果或被中断，调用方要放在 IO 线程上（RunCodeTool 用 Dispatchers.IO 包着）。
     *
     * @param call 子调用的宿主执行体：返回给程序的 wire 信封
     * @param cancel 用户中断探针；命中时按中断收尾（[CodeRunResult.aborted]）
     */
    fun run(
        appContext: Context,
        program: String,
        toolNames: List<String>,
        timeoutMs: Long,
        cancel: () -> Boolean,
        call: suspend (name: String, argsJson: String) -> String,
    ): CodeRunResult
}
