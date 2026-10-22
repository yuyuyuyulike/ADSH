package com.adsh.app.runtime.termux

import java.io.File

/**
 * App 侧的家目录准备（[TermuxRuntime.ensureReady] 每次启动幂等跑一遍）。
 *
 * 与安装器分开的原因：这些动作不属于「解压 bootstrap」，而是「让前缀像一台已配置好的
 * Termux」——git 的 safe.directory、两个诊断/渲染脚本、构建用的 scratch 目录。
 * 任何一步失败都只记日志，不影响启动。
 */
internal object HomePrep {

    fun prepare(home: File, prefix: File) {
        runCatching { File(home, "scratch").mkdirs() }
        runCatching { ensureGitSafeDirectory(home) }
        // 环境自检命令：终端里敲 `adsh-env-check` 就能把「exec 模式 / SELinux 域 /
        // 预载库 / 存储 / 浏览器」一次打出来 —— 内嵌 Termux 与官方基准的差异全在这里，出问题先看它。
        runCatching { EnvSelfCheck.installScript(prefix) }
        // 网页渲染 / 截图助手：自己解析浏览器、本地路径转 file://，
        // 第三方工具还能用 `adsh-shot --which` 拿到浏览器路径（省掉写死的 /usr/bin/chrome）。
        runCatching { AdshShot.installScript(prefix) }
    }

    /**
     * 让 git 认工作区。
     *
     * 工作区里的文件属主是 u0_a221(media_rw)，而我们的进程是 u0_a388：git 2.35+ 会直接拒绝
     * 一切操作（fatal: detected dubious ownership in repository）。官方 Termux 的 git 也有同样
     * 的坑，标准解法就是 safe.directory。
     *
     * 写的是 $HOME/.gitconfig（git 的全局配置），只追加、不覆盖用户自己写的内容；已经有一条
     * 同样的声明就直接返回（幂等）。用 `*` 而不是逐个工作区路径：工作区可以由用户随时更换，
     * App 每次启动都在这里，逐个维护成本更高还容易漏（设备本身是用户自己的）。
     */
    private fun ensureGitSafeDirectory(home: File) {
        val config = File(home, ".gitconfig")
        val existing = runCatching { if (config.isFile) config.readText() else "" }.getOrDefault("")
        if (existing.contains(GIT_SAFE_LINE)) return
        val append = buildString {
            if (existing.isNotEmpty() && !existing.endsWith("\n")) appendLine()
            appendLine("# 由 ADSH 追加：工作区在外部存储上，属主与 App 不同")
            appendLine("[safe]")
            appendLine("\t" + GIT_SAFE_LINE)
        }
        config.appendText(append)
    }

    /** 只追加的 safe.directory 声明（见 ensureGitSafeDirectory） */
    private const val GIT_SAFE_LINE = "directory = *"
}
