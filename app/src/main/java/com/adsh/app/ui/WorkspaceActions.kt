package com.adsh.app.ui

import android.content.Context
import android.content.Intent

/**
 * 工作区这条路上的动作：文件夹 URI ↔ 绝对路径、目录授权的长期保留、交付物路径解析。
 * 「所有文件访问」的读取与跳转在 :ui:blocks（文件树也要用）。附件导入与会话导出各自成文件
 * （[AttachmentImport] / [SessionExport]）—— 拆的理由是
 * **关注点**：导入链有自己的净化 / 去重 / 逐条失败反馈，导出链要读会话状态，工作区这条两边都不需要。
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
