package com.adsh.app.ui

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 工作区这条路上的动作：文件夹 URI ↔ 绝对路径、目录授权的长期保留、交付物路径解析、
 * 「所有文件访问」的读取与跳转。
 *
 * 第 182 轮把混在这里的另外两件事拆了出去（当时 233 行、6 个互不相干的函数）：
 *  - 附件导入 → [AttachmentImport.kt]（复制进会话私有目录那一条链）
 *  - 会话导出 → [SessionExport.kt]（Markdown + ZIP）
 * 拆的理由是**关注点**而不是行数：导入链有自己的净化 / 去重 / 逐条失败反馈，
 * 导出链要读会话状态；而工作区这条链两边都不需要。
 */

/**
 * 系统文件管理器选回来的「文件夹」URI → 手机上的绝对路径。
 * DocumentsUI 的树 URI 形如 content://com.android.externalstorage.documents/tree/primary%3Aadsh-ws，
 * documentId = "primary:adsh-ws" → /storage/emulated/0/adsh-ws；解析不出来（网盘 / MTP 等）返回 null。
 */
fun folderPathFromTreeUri(uri: android.net.Uri?): String? {
    if (uri == null) return null
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val relative = parts.getOrNull(1).orEmpty().trim('/')
    val base = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/" + volume
    return if (relative.isEmpty()) base else base + "/" + relative
}

/** 长期保留这个文件夹的读写权限（重启后依然有效） */
fun persistTreePermission(context: Context, uri: android.net.Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

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

/**
 * 是否已拿到「所有文件访问」（Android 11+ 的 MANAGE_EXTERNAL_STORAGE）。
 *
 * 工作区绑定走的是 SAF（选目录）+ **真实路径**（File API）两条腿：SAF 只保证「选得中」，
 * 真正列目录 / 读文件 / 写导出文件用的都是 POSIX 路径。没有这个权限时 listFiles() 直接返回
 * 空数组（不报错），表现就是「明明手机里有文件，工作区文件里什么都没有」、
 * 导入附件失败、导出 ZIP 失败。
 */
fun hasAllFilesAccess(): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

/** 跳到系统设置的「所有文件访问」授权页；厂商 ROM 没有单应用页时回落到总列表页 */
fun openAllFilesAccessSettings(context: Context) {
    val app = Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        android.net.Uri.parse("package:" + context.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(app) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
