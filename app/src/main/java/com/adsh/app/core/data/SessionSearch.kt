package com.adsh.app.core.data

/**
 * 会话搜索里那两段纯逻辑（原先埋在 [ConversationRepository.searchSessions] 里）：
 * 用户输入 → SQL LIKE 的字面量模式，以及命中处前后那一段片段。
 *
 * 为什么要单独钉：前者**顺序敏感**（反斜杠必须最先转义，否则会把刚加上的转义符再转义一遍），
 * 后者是搜索结果行唯一看到的文字（换行要压平、省略号只在真的截断时才出现）；
 * 两者都不碰库、不碰 Android，能在纯 JVM 单测里逐条打表。
 */

/**
 * 把用户输入变成 SQL LIKE 的**字面量**模式（dsh 的 ApiSessionList.search 也是字面量匹配）：
 * `%` 与 `_` 是 LIKE 的通配符，不转义的话用户搜「50%」会变成「匹配任意内容」。
 *
 * **顺序敏感**：反斜杠必须**最先**替换。若先替换 `%`，第二步会把刚加上的那个反斜杠
 * 再转义一遍 —— 输入 `\%`（一个反斜杠 + 一个百分号）的正确结果是三个反斜杠 + `%`，
 * 顺序写反会得到四个（有金用例钉住这条）。
 *
 * 注意：ADSH 的 DAO 用的是 `LIKE '%' || :pattern || '%'`（拼接而不是 ESCAPE 子句），
 * 所以这里只做「让通配符不再是通配符」的最小转义；反斜杠本身在 SQLite 的 LIKE 里
 * 只有配了 `ESCAPE` 才有特殊含义，多转义一层是安全的（见 `MessagesDao.searchContent`）。
 */
internal fun likeLiteralPattern(needle: String): String = needle
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

/**
 * 命中处前后各取 [radius] 个字符，片段里的换行与连续空白压成单空格（结果行只有一行文字）。
 *
 * 三条口径（都有用例）：
 *  - 匹配**大小写不敏感**，但片段保留正文原文的大小写；
 *  - 省略号只在**真的截断**了那一侧才出现（命中在开头就没有前导省略号）；
 *  - 正文里找不到 needle（DAO 那边命中了、这边大小写/空白压平后对不上）时，
 *    退化成「正文开头 [radius] × 2 个字符」，**不加省略号** —— 宁可显示开头，也不要空片段。
 */
internal fun snippetAround(content: String, needle: String, radius: Int = 48): String {
    val flat = content.replace(Regex("\\s+"), " ").trim()
    val at = flat.indexOf(needle, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + needle.length + radius).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}
