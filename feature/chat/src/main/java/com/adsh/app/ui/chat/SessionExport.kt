package com.adsh.app.ui.chat

import android.content.Context
import android.widget.Toast
import com.adsh.app.core.llm.SessionStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出（dsh 的 dsh-session-log-export / `export` 指令）：Markdown 落盘与「Markdown + 附件」
 * 打包成 ZIP。
 *
 * 落盘根由调用方给（[ChatViewModel.exportSession] 用 `conversationWorkspacePath()` 解析）——
 * 不能用 `ChatUiState.workspacePath`：那个字段是启动时的**兜底**工作区，绑定 / 切换工作区后不跟着
 * 变，用它会把导出写到应用私有目录或上一个工作区。
 */

/** 导出目录：会话工作区的真实路径存在就用它；否则退回应用私有目录（SAF 引用形态没有可写真实路径） */
internal fun exportDirectory(workspacePath: String?, fallback: File): File =
    workspacePath?.let { File(it) }?.takeIf { it.isDirectory } ?: fallback

/**
 * 把当前会话写成 Markdown 落到工作区，便于贴给模型或留档。
 * @param workspacePath 当前会话绑定的工作区路径（null = 没有真实路径）
 * @return 导出文件的绝对路径，失败返回 null
 */
private fun exportSessionLog(context: Context, state: ChatUiState, workspacePath: String?): String? {
    val directory = exportDirectory(workspacePath, context.filesDir)
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
                appendLine("- 工作区：" + (workspacePath ?: "（应用私有）"))
                appendLine(
                    "- 统计：" + stats.turns + " 轮 " + stats.steps + " 步 · " +
                        formatTokens(stats.totalTokens) + " tok · 缓存命中 " + stats.cacheHitPercent + "% · " +
                        String.format("%.1f", stats.tps) + " tok/s"
                )
                appendLine()
                state.messages.forEach { message ->
                    // 模型请求重试行是 log-only 的界面节点，导出里没有信息量
                    if (message.role == "retry") return@forEach
                    appendLine("## " + message.role + (message.name?.let { " (" + it + ")" } ?: ""))
                    // 先取局部变量：跨模块的 public val 不能被智能转换（MessageEntity 在 :core:data）
                    val reasoning = message.reasoning
                    if (!reasoning.isNullOrBlank()) {
                        appendLine()
                        appendLine("> 思考：" + reasoning.replace("\n", "\n> "))
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

/**
 * dsh 的 `export` 指令：把当前会话（Markdown + 附件）打包成 ZIP 放到工作区。
 * @param workspacePath 当前会话绑定的工作区路径（附件也从同一个根取）
 */
fun exportSessionZip(context: Context, state: ChatUiState, workspacePath: String?): String? {
    val markdown = exportSessionLog(context, state, workspacePath) ?: return null
    val source = File(markdown)
    val zip = File(source.parentFile ?: context.filesDir, source.nameWithoutExtension + ".zip")
    return runCatching {
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry(source.name))
            source.inputStream().use { it.copyTo(out) }
            out.closeEntry()
            val attachments = workspacePath?.let { File(it, ".adsh/attachments/" + (state.conversationId ?: 0)) }
            attachments?.listFiles()?.forEach { file ->
                out.putNextEntry(java.util.zip.ZipEntry("attachments/" + file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        zip.absolutePath
    }.getOrNull()
}

/** 导出之后的那句 toast（成功给路径，失败说「导出失败」） */
fun toastPath(context: Context, path: String?) {
    val text = if (path == null) "export failed" else "已导出到 " + path
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
