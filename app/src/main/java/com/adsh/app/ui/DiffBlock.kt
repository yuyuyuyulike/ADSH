package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.DshPalette
import com.adsh.app.ui.theme.LocalDshPalette
import kotlin.math.ceil

/*
 * 文件改动（write / edit）的展开体 —— 对齐 dsh 的 DiffBlock（ui-primitives/src/DiffBlock.tsx）。
 *
 * dsh 用 `diff` 库的 structuredPatch(context:3) 算局部补丁，再按 CHAT_DIFF_MAX_LINES(=9) 折中显示；
 * 这里没有 JS 的 diff 库，用等价的行级 LCS 自己算，并复刻它的两条截断：
 *  1. hunk 截断：改动上下各留 3 行 context，中间大段未改动折成一个 `⋯`（structuredPatch 的 context:3）；
 *  2. 高度截断：超过 9 行时只显示「头 5 + 折叠条 + 尾 4」，点折叠条展开（DiffBlock 的 maxLines/FoldToggle）。
 *
 * 卡片结构与 dsh 的 CodeCard 一致：顶上一条横幅（[RailCopyAction] 所在的那条「刘海」，
 * 左边语言标签、最右复制按钮）+ 正文。dsh 的正文第一行就是文件路径（buildRows 的 path 行），
 * 横幅上不重复画它。
 *
 * 与 dsh 的三处手机侧偏离（都写在这里，别处不再各写一套）：
 *  - 行**折行**而不是横向滚动：dsh 的 .body 是 overflow-x:auto，手机上横滚既难滑又和根层的
 *    抽屉手势抢横向拖动；折行能一眼看全每一行。
 *  - 路径行**单行省略**（用户点名）：dsh 是 pre + 横向滚动，长路径整条都在；手机屏幕有限，
 *    这里只占一行、超出省略。
 *  - 展开后最多再渲染 [MAX_DIFF_ROWS] 行：write 整个大文件时会有上千行新增，一次性组合上千个
 *    Text 会卡甚至 OOM，超出部分折成一条「内容过长」的 gap。
 */

/** 一行 diff 的角色（dsh 的 DiffRow.kind） */
internal enum class DiffKind { PATH, DEL, ADD, CONTEXT, GAP }

internal data class DiffRow(val kind: DiffKind, val text: String)

/** dsh 的 CHAT_DIFF_MAX_LINES：留得下「路径 + 一对增删 + 上下各 3 行 context」 */
private const val CHAT_DIFF_MAX_LINES = 9

/** 展开后最多渲染多少行 diff（超大文件的安全阀，见文件头注释） */
private const val MAX_DIFF_ROWS = 400

/** LCS 动态规划的规模上限：超过就退回「全删 + 全增」（对齐 dsh 的 maxEditLength 兜底） */
private const val MAX_DIFF_CELLS = 1_000_000L

/**
 * dsh 的 contentLines：空文本 = 0 行（全删的 newText / 新建时缺省的 oldText 都不画），
 * 结尾单个换行是**终止符**而不是多出来的空行；中间真正的空行（`\n\n`）保留。
 */
private fun diffContentLines(text: String?): List<String> {
    if (text.isNullOrEmpty()) return emptyList()
    val body = if (text.endsWith("\n")) text.dropLast(1) else text
    return body.split("\n")
}

/**
 * 行级 diff（LCS）。edit 的 old_string/new_string 通常是小片段，O(n·m) 的 DP 完全够；
 * 规模超过 [MAX_DIFF_CELLS] 时退回「全删 + 全增」（dsh 的 maxEditLength 兜底也是这个形状），
 * 免得 DP 表把内存吃爆。
 */
private fun computeLineDiff(oldLines: List<String>, newLines: List<String>): List<DiffRow> {
    if (oldLines.isEmpty() && newLines.isEmpty()) return emptyList()
    if (oldLines.isEmpty()) return newLines.map { DiffRow(DiffKind.ADD, it) }
    if (newLines.isEmpty()) return oldLines.map { DiffRow(DiffKind.DEL, it) }
    val n = oldLines.size
    val m = newLines.size
    if (n.toLong() * m.toLong() > MAX_DIFF_CELLS) {
        return oldLines.map { DiffRow(DiffKind.DEL, it) } + newLines.map { DiffRow(DiffKind.ADD, it) }
    }
    // dp[i][j] = oldLines[i..] 与 newLines[j..] 的最长公共子序列长度
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        val oi = oldLines[i]
        val row = dp[i]
        val next = dp[i + 1]
        for (j in m - 1 downTo 0) {
            row[j] = if (oi == newLines[j]) next[j + 1] + 1
            else if (next[j] >= row[j + 1]) next[j] else row[j + 1]
        }
    }
    val out = ArrayList<DiffRow>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            oldLines[i] == newLines[j] -> { out += DiffRow(DiffKind.CONTEXT, oldLines[i]); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffRow(DiffKind.DEL, oldLines[i]); i++ }
            else -> { out += DiffRow(DiffKind.ADD, newLines[j]); j++ }
        }
    }
    while (i < n) { out += DiffRow(DiffKind.DEL, oldLines[i]); i++ }
    while (j < m) { out += DiffRow(DiffKind.ADD, newLines[j]); j++ }
    return out
}

/**
 * 把 context 收敛成 hunk（对齐 dsh structuredPatch 的 context:3）：改动行上下各保留 [context] 行，
 * 中间大段没改动的折成一个 `⋯`。没有任何改动时返回空（dsh 的 DiffBlock 在 rows 为空时不画）。
 */
private fun hunkify(diff: List<DiffRow>, context: Int = 3): List<DiffRow> {
    if (diff.none { it.kind == DiffKind.DEL || it.kind == DiffKind.ADD }) return emptyList()
    val keep = BooleanArray(diff.size)
    for (idx in diff.indices) {
        val kind = diff[idx].kind
        if (kind == DiffKind.DEL || kind == DiffKind.ADD) {
            var k = idx - context
            while (k <= idx + context) {
                if (k in diff.indices) keep[k] = true
                k++
            }
        }
    }
    val out = ArrayList<DiffRow>()
    var inGap = false
    for (idx in diff.indices) {
        if (keep[idx]) {
            out += diff[idx]
            inGap = false
        } else if (!inGap) {
            out += DiffRow(DiffKind.GAP, "⋯")
            inGap = true
        }
    }
    return out
}

/**
 * 一个文件改动的完整 diff 行：路径头 + 收敛后的增删/上下文（dsh buildRows 的单文件形态）。
 * write 传 oldText=null（整篇都是新增），edit 传 old_string/new_string。
 */
internal fun diffRows(path: String, oldText: String?, newText: String): List<DiffRow> {
    val hunked = hunkify(computeLineDiff(diffContentLines(oldText), diffContentLines(newText)))
    val rows = ArrayList<DiffRow>(hunked.size + 1)
    rows += DiffRow(DiffKind.PATH, path)
    rows += hunked
    if (rows.size > MAX_DIFF_ROWS) {
        val capped = ArrayList<DiffRow>(rows.subList(0, MAX_DIFF_ROWS))
        capped += DiffRow(DiffKind.GAP, "⋯（内容过长，已截断）")
        return capped
    }
    return rows
}

/**
 * 复制的正文（dsh 的 copyText）：删行 `- `、增行 `+ `、上下文补两个空格，路径与 `⋯` 原样。
 * 复制的是**全部** diff 行，包括被折中藏起来的那几行（dsh 同）。
 */
private fun diffCopyText(rows: List<DiffRow>): String = rows.joinToString("\n") { row ->
    when (row.kind) {
        DiffKind.DEL -> "- " + row.text
        DiffKind.ADD -> "+ " + row.text
        DiffKind.CONTEXT -> "  " + row.text
        DiffKind.PATH, DiffKind.GAP -> row.text
    }
}

/**
 * 文件改动的展开体（dsh 的 DiffBlock）：代码块底 + .5px border-l1 + 圆角 12 的卡片，
 * 顶上一行横幅（语言标签 + 复制），正文一行一条 diff；超过 [CHAT_DIFF_MAX_LINES] 行时
 * 折中显示，点折叠条展开/收起。
 *
 * @param rows [diffRows] 算出来的行（空列表不画）
 */
@Composable
internal fun RailDiffBlock(rows: List<DiffRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val palette = LocalDshPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hidden = rows.size - CHAT_DIFF_MAX_LINES
    val capped = hidden > 0 && !expanded
    // 与 dsh 同一套折中算法：头 ceil(max/2)、尾 max-head
    val headLines = ceil(CHAT_DIFF_MAX_LINES / 2.0).toInt()
    val head = if (capped) rows.take(headLines) else rows
    val tail = if (capped) rows.takeLast(CHAT_DIFF_MAX_LINES - headLines) else emptyList()
    // 横幅左上的语言标签：按路径猜（dsh 的 languageForPath），猜不出来显示 dsh 的「代码块」
    val lang = rows.firstOrNull { it.kind == DiffKind.PATH }?.text?.let { languageOf(it) } ?: CODE_BLOCK_LABEL

    // dsh 的 --dsw-alias-code-diff-added/-deleted：green-500 / red 底色按 8%(浅) 12%(深) 调淡
    val addedBg = palette.success.copy(alpha = if (palette.dark) 0.12f else 0.08f)
    val deletedBg = palette.errorLabel.copy(alpha = if (palette.dark) 0.12f else 0.08f)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp)),
    ) {
        // 横幅（dsh 的 CodeToolbar / CodeCard 的 .header：padding 10px 18px 8px 22px）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = DshSpacing.Xxl, end = 18.dp, bottom = DshSpacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lang,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            RailCopyAction(payload = { diffCopyText(rows) })
        }
        // 正文（dsh 的 .body：padding 6px 0 20px，每行自己带 22px 横向内边距）
        Column(Modifier.fillMaxWidth().padding(top = DshSpacing.Lg, bottom = DshSpacing.Page)) {
            head.forEach { DiffLine(it, palette, addedBg, deletedBg) }
            if (hidden > 0) {
                DiffFoldToggle(hidden = hidden, expanded = expanded, onToggle = { expanded = !expanded })
            }
            tail.forEach { DiffLine(it, palette, addedBg, deletedBg) }
        }
    }
}

/** 一条 diff 行：dsh 的 .line —— 等宽、左 3px 状态色条（增/删）、`- `/`+ `/`  ` 前缀、折行 */
@Composable
private fun DiffLine(row: DiffRow, palette: DshPalette, addedBg: Color, deletedBg: Color) {
    // 路径行只占一行、长的省略（用户点名：手机屏幕有限）；其余行照 dsh 折行显示
    val oneLine = row.kind == DiffKind.PATH
    val prefix = when (row.kind) {
        DiffKind.DEL -> "- "
        DiffKind.ADD -> "+ "
        DiffKind.CONTEXT -> "  "
        DiffKind.PATH, DiffKind.GAP -> ""
    }
    val textColor = when (row.kind) {
        DiffKind.DEL -> palette.errorLabel
        DiffKind.ADD -> palette.success
        DiffKind.PATH -> palette.labelSecondary
        DiffKind.GAP -> palette.labelTertiary
        DiffKind.CONTEXT -> palette.labelSecondary
    }
    val bgColor = when (row.kind) {
        DiffKind.DEL -> deletedBg
        DiffKind.ADD -> addedBg
        else -> Color.Transparent
    }
    val barColor = when (row.kind) {
        DiffKind.DEL -> palette.errorLabel
        DiffKind.ADD -> palette.success
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            // 底色 + 左侧 3px 状态色条（dsh 的 box-shadow: inset 3px 0 0 <状态色>）一次画在文字下面
            .drawBehind {
                if (bgColor != Color.Transparent) drawRect(color = bgColor)
                if (barColor != Color.Transparent) {
                    drawRect(color = barColor, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
                }
            }
            .padding(horizontal = 22.dp),
    ) {
        Text(
            text = prefix + row.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 19.sp,
            color = textColor,
            fontWeight = if (row.kind == DiffKind.PATH) FontWeight.Medium else FontWeight.Normal,
            maxLines = if (oneLine) 1 else Int.MAX_VALUE,
            softWrap = !oneLine,
            overflow = if (oneLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

/** dsh 的 FoldToggle：折叠条（文案取自 conversation 字典 diff.expandRest / collapse） */
@Composable
private fun DiffFoldToggle(hidden: Int, expanded: Boolean, onToggle: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .dshClickable(interactionSource = dshInteraction(), onClick = onToggle)
            .padding(horizontal = 22.dp, vertical = DshSpacing.Xs),
    ) {
        Text(
            text = if (expanded) "收起" else "… 其余 " + hidden + " 行",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 19.sp,
            color = palette.labelTertiary,
        )
    }
}
