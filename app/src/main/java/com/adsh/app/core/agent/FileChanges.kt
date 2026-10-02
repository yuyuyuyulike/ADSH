package com.adsh.app.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一轮里被改动的文件（dsh 的 `workspace-changes` 子系统）。
 *
 * dsh 的做法：git 仓库里靠**每轮一棵临时树**（`add --all` 到一个临时 index + `write-tree`），
 * 仓库外用「文件工具调用前后各存一份内容副本」兜底；两者都做不了的行（shell 直接改的、
 * 没被 git 覆盖的）就不出现在卡片上。卡片正文是 dsh 的 zh 字典那几句：
 * 「已编辑 N 个文件」/「已编辑 <名字>」+「+A -B」+ 文件名列表。
 *
 * ADSH 只做**文件工具那一半**（`write` / `edit` 调用前后各留一份底）：Android 侧每轮跑一次
 * git 快照太重（工作区在 FUSE 上），而 PTC 的提示词本来就是「优先用 write/edit，不要用 shell
 * 重定向」。所以用 bash 直接改的文件不计 —— 与 dsh 在没有 git 时是同一条口径。
 *
 * @param path 工作区相对路径（工作区外是绝对路径），界面只显示文件名
 * @param added / [deleted] 行数增减；`oversized` / `binary` 时不数
 */
@Serializable
data class FileChange(
    val path: String,
    val added: Int = 0,
    val deleted: Int = 0,
    /** 有一侧超过 [TurnChangeTracker.MAX_FILE_BYTES]：内容没读，只列出来 */
    val oversized: Boolean = false,
    /** 有 NUL 字节：不数行 */
    val binary: Boolean = false,
)

private val changesJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun encodeFileChanges(list: List<FileChange>): String? =
    if (list.isEmpty()) null else runCatching {
        changesJson.encodeToString(ListSerializer(FileChange.serializer()), list)
    }.getOrNull()

fun decodeFileChanges(raw: String?): List<FileChange> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        changesJson.decodeFromString(ListSerializer(FileChange.serializer()), raw)
    }.getOrDefault(emptyList())
}

/**
 * 一轮的文件改动记录器（dsh 的 TurnRecorder 的文件工具那一半）。
 *
 * 用法：一轮开始时 [beginTurn]；`write` / `edit` 真正落盘**之前** [captureBefore]；这一轮结束时
 * [finish] 拿汇总。同一个路径一轮只留一次底（dsh：`once per path per turn`），
 * 所以「同一文件被改两次」在卡片上仍是一行，行数按「本轮开始 vs 本轮结束」算。
 */
object TurnChangeTracker {

    /** dsh 的 maxFileBytes 默认值：超过它的文件不比较内容，只列出来 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 行比较的规模上限（dsh 是 diffTimeoutMs=100ms 超时后降级）；超过就按整文件替换数 */
    private const val MAX_DIFF_CELLS = 4_000_000L

    /** 留底：内容为 null 表示「当时不存在」或「太大没读」 */
    private class Capture(val existed: Boolean, val bytes: ByteArray?)

    private val captures = LinkedHashMap<String, Capture>()

    /** 一轮开始：丢掉上一轮没来得及收的底 */
    fun beginTurn() {
        synchronized(captures) { captures.clear() }
    }

    /**
     * 文件工具落盘前留底。**必须是改动之前**：这一轮里第一次碰到这个路径时才读一次。
     * @param path 绝对路径（工具已经解析过的那个）
     */
    fun captureBefore(path: String) {
        synchronized(captures) {
            if (captures.containsKey(path)) return
            val file = File(path)
            captures[path] = when {
                !file.isFile -> Capture(existed = false, bytes = null)
                file.length() > MAX_FILE_BYTES -> Capture(existed = true, bytes = null)
                else -> Capture(existed = true, bytes = runCatching { file.readBytes() }.getOrNull())
            }
        }
    }

    /**
     * 这一轮结束：把留底与现在比一遍。
     * @param workspaceRoot 用来把路径写成工作区相对形式（工作区外保持绝对路径）
     */
    fun finish(workspaceRoot: String?): List<FileChange> {
        val snapshot: Map<String, Capture>
        synchronized(captures) {
            snapshot = LinkedHashMap(captures)
            captures.clear()
        }
        if (snapshot.isEmpty()) return emptyList()
        val out = ArrayList<FileChange>()
        snapshot.forEach { (path, before) ->
            changeOf(path, before, workspaceRoot)?.let { out += it }
        }
        // dsh 按 display 路径排序（父目录 / 绝对路径排在前面）
        return out.sortedBy { it.path }
    }

    private fun changeOf(path: String, before: Capture, workspaceRoot: String?): FileChange? {
        val file = File(path)
        val display = displayPath(workspaceRoot, file)
        val existedAfter = file.isFile
        // 「留底时太大没读」时不敢断言没变（dsh：unread content is never known to be unchanged）
        if (before.existed && before.bytes == null) {
            return FileChange(path = display, oversized = true)
        }
        if (!existedAfter) {
            // 这一轮把它删了（或写失败）：只有本来存在才算改动
            if (!before.existed) return null
            val old = before.bytes ?: return FileChange(path = display, oversized = true)
            if (isBinary(old)) return FileChange(path = display, binary = true)
            return FileChange(path = display, added = 0, deleted = lineCount(old))
        }
        val after = if (file.length() > MAX_FILE_BYTES) null else runCatching { file.readBytes() }.getOrNull()
        if (after == null) return FileChange(path = display, oversized = true)
        val old = before.bytes
        if (!before.existed) {
            // 本轮新建
            if (isBinary(after)) return FileChange(path = display, binary = true)
            return FileChange(path = display, added = lineCount(after), deleted = 0)
        }
        if (old == null) return FileChange(path = display, oversized = true)
        if (isBinary(old) || isBinary(after)) return FileChange(path = display, binary = true)
        if (sameText(old, after)) return null
        val (added, deleted) = lineDiffCounts(splitLines(old), splitLines(after))
        return FileChange(path = display, added = added, deleted = deleted)
    }

    /** 工作区内的路径写成相对形式（与 `Args.display` 同一条规则），工作区外保持绝对路径 */
    private fun displayPath(workspaceRoot: String?, file: File): String {
        val root = workspaceRoot?.let { File(it) } ?: return file.path
        return runCatching {
            val base = root.canonicalFile.toPath()
            val target = file.canonicalFile.toPath()
            val rel = base.relativize(target).toString().replace(File.separatorChar, '/')
            if (rel.isEmpty() || rel.startsWith("..")) file.path else rel
        }.getOrDefault(file.path)
    }

    /** 有 NUL 字节就当二进制（dsh：`binary` for a side that holds a NUL byte） */
    internal fun isBinary(bytes: ByteArray): Boolean =
        bytes.any { it == 0.toByte() }

    /** 行数：末尾换行不算多一行（dsh：a missing final newline compares as unchanged） */
    private fun lineCount(bytes: ByteArray): Int = splitLines(bytes).size

    internal fun splitLines(bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }

    /** 只差结尾那个换行 = 没变 */
    private fun sameText(a: ByteArray, b: ByteArray): Boolean {
        if (a.contentEquals(b)) return true
        return splitLines(a) == splitLines(b)
    }

    /**
     * 增删行数：能算就算精确的（LCS），太长的按「整文件替换」降级（两条都与 dsh 一致 ——
     * 它超时后也是 `coarse`：一个 hunk 替换全部行）。
     */
    internal fun lineDiffCounts(old: List<String>, new: List<String>): Pair<Int, Int> {
        if (old.isEmpty()) return new.size to 0
        if (new.isEmpty()) return 0 to old.size
        if (old.size.toLong() * new.size.toLong() > MAX_DIFF_CELLS) {
            return new.size to old.size
        }
        val lcs = lcsLength(old, new)
        return (new.size - lcs) to (old.size - lcs)
    }

    /** 最长公共子序列长度（滚动两行，内存 O(min(n,m))） */
    private fun lcsLength(a: List<String>, b: List<String>): Int {
        val (short, long) = if (a.size <= b.size) a to b else b to a
        val previous = IntArray(short.size + 1)
        val current = IntArray(short.size + 1)
        long.forEach { rowItem ->
            for (index in short.indices) {
                current[index + 1] = if (short[index] == rowItem) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        return previous[short.size]
    }
}
