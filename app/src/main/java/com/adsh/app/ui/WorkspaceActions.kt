package com.adsh.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.adsh.app.core.llm.SessionStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「导出日志」（对应 dsh 的 dsh-session-log-export）：把当前会话写成 Markdown 落到工作区，
 * 便于贴给模型或留档。
 * @return 导出文件的绝对路径，失败返回 null
 */
fun exportSessionLog(context: Context, state: ChatUiState): String? {
    val directory = state.workspacePath?.let { File(it) }?.takeIf { it.isDirectory } ?: context.filesDir
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(directory, "adsh-session-" + stamp + ".md")
    val stats: SessionStats = state.stats
    return runCatching {
        file.writeText(
            buildString {
                appendLine("# ADSH 会话导出")
                appendLine()
                appendLine("- 会话 ID：" + (state.conversationId ?: 0))
                appendLine("- 模型：" + state.modelLabel)
                appendLine("- 工作区：" + (state.workspacePath ?: "（应用私有）"))
                appendLine(
                    "- 统计：" + stats.turns + " 轮 " + stats.steps + " 步 · " +
                        formatTokens(stats.totalTokens) + " tok · 缓存命中 " + stats.cacheHitPercent + "% · " +
                        String.format("%.1f", stats.tps) + " tok/s"
                )
                appendLine()
                state.messages.forEach { message ->
                    appendLine("## " + message.role + (message.name?.let { " (" + it + ")" } ?: ""))
                    if (!message.reasoning.isNullOrBlank()) {
                        appendLine()
                        appendLine("> 思考：" + message.reasoning.replace("\n", "\n> "))
                    }
                    appendLine()
                    appendLine(message.content)
                    appendLine()
                }
            }
        )
        file.absolutePath
    }.getOrNull()
}

/** token 数的紧凑写法（会话导出用；面板里的格式化在 StatsPopup.kt） */
private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

/** 导入结果：成功路径 + 逐条失败原因（必须有可见反馈，不能静默失败） */
data class ImportResult(val paths: List<String> = emptyList(), val failures: List<String> = emptyList())

/**
 * 把系统文件管理器选中的文件复制进会话私有目录（工作区内的 .adsh/attachments/<会话 id>/）。
 * 放在工作区内有两个好处：Agent 的 read/glob 能直接读到；删会话时一起删掉，不需要额外的清理入口，
 * 也不需要用户单独管理这些副本。
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

/** dsh 的 `export` 指令：把当前会话（Markdown + 附件）打包成 ZIP 放到工作区 */
fun exportSessionZip(context: Context, state: ChatUiState): String? {
    val markdown = exportSessionLog(context, state) ?: return null
    val source = File(markdown)
    val zip = File(source.parentFile ?: context.filesDir, source.nameWithoutExtension + ".zip")
    return runCatching {
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry(source.name))
            source.inputStream().use { it.copyTo(out) }
            out.closeEntry()
            val attachments = state.workspacePath?.let { File(it, ".adsh/attachments/" + (state.conversationId ?: 0)) }
            attachments?.listFiles()?.forEach { file ->
                out.putNextEntry(java.util.zip.ZipEntry("attachments/" + file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip.absolutePath
    }.getOrNull()
}

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

fun toastPath(context: Context, path: String?) {
    val text = if (path == null) "导出失败" else "已导出到 " + path
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
