package com.adsh.app.ui.chat

import java.io.File

/**
 * 交付物卡片点开时的路径解析（dsh 的 `resolveWorkspacePath(cwd, path)`）：
 * present 存的是**声明时**的路径 —— 工作区内是相对路径、工作区外是绝对路径
 * （见 com.adsh.app.core.tools.Args.display），文件预览页要的是手机上的绝对路径。
 *
 * @param workspaceRoot 当前会话的工作区根（没有绑定时为 null）
 */
fun resolveDeliverablePath(workspaceRoot: String?, path: String): String {
    val file = File(path)
    if (file.isAbsolute || workspaceRoot.isNullOrBlank()) return path
    return File(workspaceRoot, path).path
}
