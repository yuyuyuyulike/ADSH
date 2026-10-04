package com.adsh.app.ui

import android.content.Context
import android.content.Intent
import java.io.File

/** 导入结果：成功路径 + 逐条失败原因（必须有可见反馈，不能静默失败） */
data class ImportResult(val paths: List<String> = emptyList(), val failures: List<String> = emptyList())

/**
 * 把系统文件管理器选中的文件复制进会话私有目录（工作区内的 .adsh/attachments/<会话 id>/）。
 *
 * 放在工作区内有两个好处：Agent 的 read/glob 能直接读到；删会话时一起删掉，不需要额外的清理入口，
 * 也不需要用户单独管理这些副本。
 *
 * 第 182 轮从 WorkspaceActions.kt 搬到这里：那个文件当时是「导出 / 导入 / 目录 URI / 交付物路径」
 * 四件事挤在一起（233 行、6 个互不相干的函数），而这条导入链有自己的关注点（净化文件名、同名
 * 去重、provider 授权、逐条失败反馈），值得独立成文件。
 */
fun importAttachments(
    context: Context,
    workspacePath: String?,
    conversationId: Long,
    uris: List<android.net.Uri>,
): ImportResult {
    if (uris.isEmpty()) return ImportResult()
    val root = workspacePath?.let { File(it) } ?: context.filesDir
    val target = File(root, ".adsh/attachments/" + conversationId)
    if (!target.isDirectory && !target.mkdirs()) {
        return ImportResult(failures = listOf("无法创建附件目录：" + target.absolutePath))
    }
    val imported = ArrayList<String>()
    val failures = ArrayList<String>()
    uris.forEach { uri ->
        // 有些 provider 只在拿到持久化授权后才可读；拿不到也不影响本次会话内读取
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = sanitizeFileName(queryDisplayName(context, uri))
            ?: ("import-" + System.currentTimeMillis())
        val file = uniqueFile(target, name)
        val outcome = runCatching {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("无法打开输入流")
            stream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            if (file.length() <= 0L) throw java.io.IOException("复制后文件为空")
            file.absolutePath
        }
        outcome.fold(
            onSuccess = { path ->
                android.util.Log.i("ADSH_IMPORT", "imported " + path + " (" + file.length() + " bytes)")
                imported += path
            },
            onFailure = { error ->
                android.util.Log.w("ADSH_IMPORT", "import failed for " + uri + ": " + error)
                file.delete()
                failures += name + "：" + (error.message ?: error::class.java.simpleName)
            },
        )
    }
    return ImportResult(imported, failures)
}

/** 文件显示名可能带路径分隔符（恶意/畸形 provider），落盘前必须净化 */
private fun sanitizeFileName(raw: String?): String? {
    val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleaned = name.replace('/', '_').replace('\\', '_').replace("\u0000", "")
    return cleaned.take(120).ifBlank { null }
}

/** 同名文件自动加 -1/-2 后缀，避免后一次导入覆盖前一次 */
private fun uniqueFile(directory: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var candidate = File(directory, name)
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, stem + "-" + index + ext)
        index++
    }
    return candidate
}

private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
    val cursor = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    }.getOrNull() ?: return null
    return cursor.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}
