package com.adsh.app.seam.exec

import java.io.File
import java.io.InputStream

/**
 * Shell 能力端口（服务定义）：core 只通过它调运行时（起进程 / 跑命令 / 临时目录 / 写围栏）。
 * 只列消费方（core.tools、core.workspace）真正用到的调用；实现由组合根注入。
 */
interface Shell {

    companion object {
        /** 对齐 dsh：shell.timeoutMs 默认 120000，maxOutputBytes 默认 64000 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 64_000

        /** 写围栏 shim 的文件名（CMake 目标 adshfence，落在 nativeLibraryDir） */
        const val FENCE_LIB = "libadshfence.so"
    }

    /** bash 入口（$PREFIX/bin/bash；缺失时退回 nativeLibraryDir 里的副本） */
    val bashPath: String

    /** 随包 ripgrep（静态 musl），供 grep / glob 类工具使用 */
    val ripgrepPath: String

    /** 临时目录（$PREFIX/tmp） */
    fun tmpDir(): File

    /** f2fs 上的构建目录（默认 $HOME/scratch）：工作区在外部存储上，重活放这里做 */
    fun scratchDir(): File

    /** 应用私有 cache 目录的两种拼写，都算可写根 */
    fun cacheRoots(): List<File>

    /**
     * 写围栏的环境变量（LD_PRELOAD 等）：[mode] 是沙箱模式，[roots] 是可写白名单，
     * [mark] 是 shim 一加载就写的哨兵文件。不挂围栏时返回空数组。
     */
    fun fenceEnv(mode: String, roots: List<File?>, mark: File?): Array<String>

    /** 起一个**不等待**的进程（后台任务与前台执行共用同一份 argv / 环境 / stdin 拼装） */
    fun spawn(
        command: String,
        workspaceRoot: File?,
        extraEnv: Array<String>? = null,
        separateStreams: Boolean = false,
    ): ShellProcess

    /** 跑一条命令并等它结束；[cancel] 探针命中时杀整棵进程树再返回（结果里 aborted/cancelled 标出来） */
    fun run(
        command: String,
        workspaceRoot: File?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        extraEnv: Array<String>? = null,
        separateStreams: Boolean = false,
        cancel: (() -> Boolean)? = null,
    ): ExecResult
}

/**
 * 一条已经起来的 shell 命令：两条输出流 + 等待 / 收尾 / 终止。
 * 只列 core 用到的调用，杀树细节留给实现。
 */
interface ShellProcess {
    val stdout: InputStream
    val stderr: InputStream

    /** 一直等到结束 */
    fun waitFor(): Int

    fun exitValue(): Int

    /** 进程结束后的收尾：实现自己清临时件 */
    fun dispose()

    /** 请求终止（非阻塞，实现内部排队） */
    fun terminate()
}
