package com.adsh.app.core.workspace

import android.content.Context
import com.adsh.app.runtime.termux.TermuxRuntime
import java.io.File

/** 工作区三形态（方案书 §3.2） */
enum class WorkspaceForm { PRIVATE, REAL_PATH, SAF_REFERENCE }

data class Workspace(
    val form: WorkspaceForm,
    val root: File,
    val displayName: String,
    val safUri: String? = null,
) {
    /** shell 可用的真实路径；SAF 引用形态为 null */
    val shellRoot: File? get() = if (form == WorkspaceForm.SAF_REFERENCE) null else root
}

/**
 * 工作区管理：绑定、校验、持久化。
 *
 * 关键点（方案书 §3.3）：**校验必须用 shell 实测**（test -w）。
 * Kotlin 的 File.canWrite()/canExecute() 与 shell 子进程的权限路径不同，会给出错误结论（M0 PoC-1 已证）。
 */
class WorkspaceManager(
    private val context: Context,
    private val runtime: TermuxRuntime,
) {
    private val prefs = context.getSharedPreferences("adsh_workspace", Context.MODE_PRIVATE)

    fun current(): Workspace {
        val stored = prefs.getString(KEY_FORM, null)
        val form = WorkspaceForm.entries.firstOrNull { it.name == stored } ?: WorkspaceForm.PRIVATE
        val path = prefs.getString(KEY_PATH, null)
        if (form != WorkspaceForm.PRIVATE && !path.isNullOrBlank()) {
            val root = File(path)
            if (root.isDirectory) {
                return Workspace(
                    form = form,
                    root = root,
                    displayName = prefs.getString(KEY_NAME, null) ?: root.name,
                    safUri = prefs.getString(KEY_URI, null),
                )
            }
        }
        return privateWorkspace()
    }

    fun privateWorkspace(): Workspace {
        val root = File(context.filesDir, "workspaces/default").apply { mkdirs() }
        return Workspace(WorkspaceForm.PRIVATE, root, "应用私有工作区")
    }

    /** 绑定真实路径工作区；返回 null 表示成功，否则返回失败原因 */
    fun bindRealPath(dir: File, safUri: String? = null): String? {
        if (!dir.isDirectory) return "目录不存在：" + dir.absolutePath
        if (isForbidden(dir)) return "该目录不可作为工作区（系统限制）：" + dir.absolutePath

        val probe = "test -d \"" + dir.absolutePath + "\" && test -w \"" + dir.absolutePath +
            "\" && echo ADSH_WRITABLE"
        val result = runtime.run(probe, workspaceRoot = dir, timeoutMs = 15_000)
        if (!result.output.contains("ADSH_WRITABLE")) {
            return "shell 无法写入该目录（可能缺少「所有文件访问」）：" + dir.absolutePath +
                "；exit=" + result.exitCode + " out=" + result.output.trim()
        }

        prefs.edit()
            .putString(KEY_FORM, WorkspaceForm.REAL_PATH.name)
            .putString(KEY_PATH, dir.absolutePath)
            .putString(KEY_NAME, dir.name)
            .putString(KEY_URI, safUri)
            .apply()
        return null
    }

    private fun isForbidden(dir: File): Boolean {
        val p = dir.absolutePath
        if (p == "/" || p == "/system" || p == "/data" || p == "/proc" || p == "/sys") return true
        return p.contains("/Android/data") || p.contains("/Android/obb") || p.contains("/Android/sandbox")
    }

    companion object {
        private const val KEY_FORM = "form"
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"
        private const val KEY_URI = "uri"
    }
}
