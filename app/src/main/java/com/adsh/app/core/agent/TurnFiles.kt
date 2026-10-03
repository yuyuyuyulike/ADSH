package com.adsh.app.core.agent

/**
 * 轮尾文件卡片里的一行：**这一轮碰过的文件** —— `write` / `edit` 改过的，或 `present` 交付过的。
 *
 * 第 86 轮并表（用户点名：「交付的文件上面多了一块」「你做的那个少了 +a -b」）：
 * 同一个文件不该既在「已编辑」卡里出现一次、又在「交付文件」卡里再出现一次 ——
 * 现在两张卡合成 dsh 的 ChangedFiles 那一张，一行一个文件。
 *
 * @param path 显示路径：工作区内是相对工作区的路径，工作区外是绝对路径（见 [deliverableDisplayPath]）
 * @param edited 这一轮 `write` / `edit` 改过它。false = **只交付过**，没有增删行可显示
 */
data class TurnFileRow(
    val path: String,
    val edited: Boolean,
    val added: Int = 0,
    val deleted: Int = 0,
    val oversized: Boolean = false,
    val binary: Boolean = false,
)

/**
 * 把「本轮改动」（`messages.changesJson`）与「本轮交付」（`messages.deliverablesJson`）
 * 并成卡片要画的行。
 *
 * 规则：
 *  - 以**改动那一行**为底（它带 `+A -B` / `过大` / `二进制`），交付只是补充新路径；
 *  - 交付路径先按 [deliverableDisplayPath] 裁掉工作区前缀再比对，所以「模型写绝对路径」
 *    与「改动记录写相对路径」指向同一个文件时**只留一行**（顺手去掉 `./` 前缀，那是模型常写的写法）；
 *  - 顺序：改动文件按记录顺序在前，只交付没改过的按交付顺序追加在后。
 */
fun mergeTurnFiles(
    changes: List<FileChange>,
    deliverables: List<PresentedFile>,
    workspaceRoot: String?,
): List<TurnFileRow> {
    val rows = LinkedHashMap<String, TurnFileRow>()
    changes.forEach { change ->
        val path = change.path.trim()
        if (path.isEmpty()) return@forEach
        rows[path] = TurnFileRow(
            path = path,
            edited = true,
            added = change.added,
            deleted = change.deleted,
            oversized = change.oversized,
            binary = change.binary,
        )
    }
    deliverables.forEach { file ->
        val path = deliverableDisplayPath(workspaceRoot, file.path).removePrefix("./").trim()
        if (path.isEmpty() || rows.containsKey(path)) return@forEach
        rows[path] = TurnFileRow(path = path, edited = false)
    }
    return rows.values.toList()
}
