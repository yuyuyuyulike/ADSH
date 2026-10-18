package com.adsh.app.seam.exec

/** 一次非交互命令的执行结果 */
data class ExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
    /**
     * 单独捕获的 stderr。只有 separateStreams = true 的调用会填它；
     * 否则 stderr 已经并进 [output]（默认行为，探针类调用依赖合并后的文本）。
     * dsh 的 bash 工具 stdout/stderr 是两个字段，模型看到的正文里 stderr 带 [stderr] 头，
     * 所以 bash 工具走单独捕获这条路径。
     */
    val stderr: String = "",
    val stderrTruncated: Boolean = false,
    /**
     * stdout 全文 spill 文件的路径（dsh 的 canonicalBashResult.stdout.spillPath）：
     * 内存里只留尾部，全文在这里；模型可以自己 read 回来。
     */
    val spillPath: String? = null,
    val stderrSpillPath: String? = null,
    /** 本次调用的超时上限（dsh 的输出里会回显 [timed out after Nms]） */
    val timeoutMs: Long = 0,
    /**
     * 这次执行是因为**用户中断**而停下的（[Shell.run] 的 `cancel` 探针命中）。
     * 与超时区分开：超时是「命令太慢」，中断是「人按了停止」——工具行上的文案不一样。
     */
    val cancelled: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}
