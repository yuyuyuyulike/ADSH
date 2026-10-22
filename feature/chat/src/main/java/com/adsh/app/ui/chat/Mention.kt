package com.adsh.app.ui.chat

import java.io.File
import java.nio.file.Files

/**
 * 输入框里的 `@` 文件补全（dsh 的 `@file` reference）。
 *
 * 语法逐条照 dsh 的 `packages/context/file-reference/src/grammar.ts`：
 *  - 未加引号：`(?:^|\s)@(\S*)$`，`@` 只能在当前行行首或空白之后（`user@host` 不触发）；
 *  - 已开引号：`(?:^|\s)@"([^"]*)$`，query 可以含空白，直到下一个 `"`；
 *  - token 必须结束在光标处，光标在草稿 0 或落在 token 外时没有活 token。
 *
 * 命中后的候选菜单、插入与排序见 [MentionPanel] / [mentionEntriesFor]。
 */
internal data class ActiveMention(
    /** token 起点（`@` 的下标） */
    val start: Int,
    /** token 终点（恒等于光标） */
    val end: Int,
    /** `@` / `@"` 之后的过滤串 */
    val query: String,
    /** 是否处于未闭合的 `@"...` 里 */
    val quoted: Boolean,
)

private val QUOTED_AT = Regex("(?:^|\\s)(@\"([^\"]*))$")
private val PLAIN_AT = Regex("(?:^|\\s)(@(\\S*))$")

/**
 * 光标处的活 `@` token（dsh 的 `activeAtToken`）。
 *
 * 只在**当前行**里找：dsh 的 detect 传的是编辑器当前行，token 不能跨行。
 * @return null = 没有活 token（面板应收起）
 */
internal fun activeAtToken(text: String, cursor: Int): ActiveMention? {
    if (cursor <= 0 || cursor > text.length) return null
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val before = text.substring(lineStart, cursor)
    QUOTED_AT.find(before)?.let { match ->
        return ActiveMention(
            start = lineStart + match.groups[1]!!.range.first,
            end = cursor,
            query = match.groupValues[2],
            quoted = true,
        )
    }
    PLAIN_AT.find(before)?.let { match ->
        return ActiveMention(
            start = lineStart + match.groups[1]!!.range.first,
            end = cursor,
            query = match.groupValues[2],
            quoted = false,
        )
    }
    return null
}

/** 一次查询最多给几条候选（dsh 的 DEFAULT_FILE_SEARCH_MAX_RESULTS） */
internal const val MENTION_MAX_RESULTS = 20

/**
 * 索引里的一条候选：`path` 是**工作区相对路径**、以 `/` 分隔；`directory` 区分目录（可 drill）。
 * 小写形式在构造时算好：每次按键都要给整个索引打分，逐条 lowercase 会把输入框拖慢。
 */
internal class MentionEntry(val path: String, val directory: Boolean) {
    val name: String = path.substringAfterLast('/')
    val parent: String = path.substringBeforeLast('/', "")
    internal val lowerPath: String = path.lowercase()
    internal val lowerName: String = name.lowercase()
}

/**
 * 一次 `@` 查询的候选（dsh 的 `WorkspaceFileSearch.list`）。
 *
 * 两条路径照 dsh 分开：
 *  - 空 query 或含 `/`：**列该目录的一级条目**（空 query 列工作区根）；
 *  - 其余：对整份索引做大小写不敏感的模糊匹配。
 *
 * 绝对路径与 `..` 一律空（只能引用工作区内），排除目录（[WorkspaceFileIndex.EXCLUDED_DIRECTORIES]）
 * 下的查询也空。无结果返回空 —— 面板据此收起（dsh 没有「无匹配」空态）。
 */
internal fun mentionEntriesFor(
    entries: List<MentionEntry>,
    query: String,
    maxResults: Int = MENTION_MAX_RESULTS,
): List<MentionEntry> {
    if (entries.isEmpty() || maxResults <= 0) return emptyList()
    val normalized = query.replace('\\', '/')
    if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) return emptyList()
    val slash = normalized.lastIndexOf('/')
    if (normalized.isEmpty() || slash >= 0) {
        val directory = if (slash < 0) "" else normalized.substring(0, slash)
        val fragment = if (slash < 0) "" else normalized.substring(slash + 1)
        if (directory.split('/').any { it in WorkspaceFileIndex.EXCLUDED_DIRECTORIES }) return emptyList()
        val candidates = entries.filter { entry ->
            entry.parent == directory && (fragment.startsWith(".") || !entry.name.startsWith("."))
        }
        return rankMentions(candidates, fragment, maxResults)
    }
    return rankMentions(entries.filter { visibleForGlobalQuery(it.path, normalized) }, normalized, maxResults)
}

/** dsh 的 visibleForGlobalQuery：查询本身就带 `.` 时才把隐藏条目算进来 */
private fun visibleForGlobalQuery(path: String, query: String): Boolean =
    query.startsWith(".") || query.contains("/.") || path.split('/').none { it.startsWith(".") }

/**
 * dsh 的 rankCandidates：分数（精确 1000 / 前缀 900 / 基名含 700 / 路径含 500 / 子序列 300+）
 * + 目录加成 25；同分时目录优先 → 非空查询短路径优先 → 字典序。上限 [limit]。
 */
private fun rankMentions(candidates: List<MentionEntry>, query: String, limit: Int): List<MentionEntry> {
    if (candidates.isEmpty()) return emptyList()
    val needle = query.lowercase()
    val scored = ArrayList<Pair<MentionEntry, Int>>(candidates.size)
    for (entry in candidates) {
        val score = mentionScore(entry, needle) ?: continue
        scored += entry to score
    }
    scored.sortWith(
        compareByDescending<Pair<MentionEntry, Int>> { it.second }
            .thenBy { if (it.first.directory) 0 else 1 }
            .thenBy { if (needle.isEmpty()) 0 else it.first.path.length }
            .thenBy { it.first.path },
    )
    return scored.take(limit).map { it.first }
}

private fun mentionScore(entry: MentionEntry, needle: String): Int? {
    if (needle.isEmpty()) return 0
    val directoryBonus = if (entry.directory) 25 else 0
    if (entry.lowerName == needle) return 1000 + directoryBonus
    if (entry.lowerName.startsWith(needle)) return 900 + directoryBonus
    if (entry.lowerName.contains(needle)) return 700 + directoryBonus
    if (entry.lowerPath.contains(needle)) return 500 + directoryBonus
    val subsequence = subsequenceScore(entry.lowerPath, needle) ?: return null
    return 300 + subsequence + directoryBonus
}

private fun subsequenceScore(target: String, query: String): Int? {
    var targetIndex = 0
    var gap = 0
    for (character in query) {
        val found = target.indexOf(character, targetIndex)
        if (found < 0) return null
        gap += found - targetIndex
        targetIndex = found + 1
    }
    return maxOf(0, 100 - gap)
}

/**
 * 选中一条候选时插进草稿的正文（dsh 的 `formatFileMention`）。
 *
 * 属性：文件 `@src/a.ts`；目录 `@src/`（尾斜杠）；含空白的路径用 `@"..."` 引号形式，
 * 目录保持开引号（drill 要继续往里打）。控制字符或 `"` 的路径无法安全写成 token，返回 null
 * （dsh 对应候选直接不显示）。
 *
 * @param preserveQuote 用户已经开了引号（drill 时要保持它开着）
 */
internal fun formatMention(path: String, directory: Boolean, preserveQuote: Boolean): String? {
    val normalized = path.replace('\\', '/')
    if (normalized.isEmpty()) return null
    if (normalized.any { it.code < 0x20 || it.code == 0x7f || it == '"' }) return null
    val target = if (directory) normalized.trimEnd('/') + "/" else normalized
    val quoted = preserveQuote || target.any { it.isWhitespace() }
    if (!quoted) return "@" + target
    return if (directory) "@\"" + target else "@\"" + target + "\""
}

/** 选中（settle）时的引用正文：目录也闭合引号，保证插进草稿后是一个完整 token。 */
internal fun formatMentionPick(path: String, directory: Boolean): String? {
    val raw = formatMention(path, directory, preserveQuote = false) ?: return null
    return if (raw.startsWith("@\"") && !raw.endsWith("\"")) raw + "\"" else raw
}

/**
 * 工作区文件的**有界索引**（dsh 的 `WorkspaceFileSearch` 的扫描部分）。
 *
 * 布局与 dsh 一致：BFS、按名字排序、最多 [MAX_ENTRIES] 条、跳过 [EXCLUDED_DIRECTORIES] 这类
 * 依赖 / 构建目录（**不读 .gitignore** —— dsh 也不读）。符号链接不收录（dsh 的 `readdir` 对
 * 链接既不是 file 也不是 directory），读不出的子目录静默跳过，根读不出 = 空索引。
 *
 * 索引由调用方在 IO 线程上重建（见 Composer 的 `mentionIndex`）：一次 @ 面板打开的成本是
 * 一遍工作区遍历，不能放在组合期。
 */
internal object WorkspaceFileIndex {

    /** 索引条数上限（dsh 的 DEFAULT_FILE_SEARCH_MAX_ENTRIES） */
    const val MAX_ENTRIES = 50_000

    /** dsh 的 DEFAULT_FILE_SEARCH_EXCLUDED_DIRECTORIES：只按基名排除，不读 ignore 文件 */
    val EXCLUDED_DIRECTORIES: Set<String> = setOf(
        ".git", "node_modules", "dist", "build", "out", "coverage", "target",
        ".next", ".nuxt", ".turbo", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".gradle",
    )

    fun build(root: File, maxEntries: Int = MAX_ENTRIES): List<MentionEntry> {
        if (maxEntries <= 0 || !root.isDirectory) return emptyList()
        val out = ArrayList<MentionEntry>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && out.size < maxEntries) {
            val (directory, relative) = queue.removeFirst()
            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            children.sortBy { it.name }
            for (child in children) {
                if (out.size >= maxEntries) break
                val name = child.name
                val path = if (relative.isEmpty()) name else relative + "/" + name
                if (isSymbolicLink(child)) continue
                if (child.isDirectory) {
                    if (name in EXCLUDED_DIRECTORIES) continue
                    out += MentionEntry(path, directory = true)
                    queue.add(child to path)
                } else if (child.isFile) {
                    out += MentionEntry(path, directory = false)
                }
            }
        }
        return out
    }

    /** 链接按文件对待也不收录：dsh 的 Dirent 对链接两个判定都为 false。 */
    private fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
