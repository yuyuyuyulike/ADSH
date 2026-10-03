package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.tools.SearchOutputText
import com.adsh.app.ui.theme.LocalDshPalette
import kotlin.math.ceil

/*
 * grep / glob 的展开体 —— 对齐 dsh 的 SearchBlock（ui-primitives/src/SearchBlock.tsx）。
 *
 * dsh 的这张卡吃的是结果 **meta**（shape = matches|paths、files/paths、truncated、total）；
 * ADSH 的工具行只落库「人看的正文」（QuickJsRuntime 的 SubCall.result = outcome.text），
 * 所以这里把 [SearchOutputText] 那套正文**解析回来**（与 ReadBlock 从 read 信封还原同一个套路，
 * 两端的字面量共用同一份常量，别再各写一遍）。
 *
 * 卡片结构、行型、折中算法都照 dsh：
 *  - 横幅：摘要（`N 处匹配 · K 个文件` / `显示 N / 共 M 处匹配 · K 个文件` / `N 个路径`）+ 复制；
 *  - 正文：grep 按文件分组（文件头 = 粗体路径 + 命中数，点一下折叠这一组；命中行 = `行号: 正文`，
 *    行号用三级色），glob 一行一条路径；
 *  - 折中：超过 CHAT_SEARCH_MAX_LINES(8) 行时「头 4 + … 其余 N 行 + 尾 4」，
 *    尾切片从某个文件组中间开始时把那一组的文件头补回尾部顶端（dsh 的 tailHeader 规则）；
 *  - 空结果画 dsh 的「无结果」；截断时卡片下面挂一行恢复说明（dsh 的 searchRecovery）。
 *
 * 手机侧偏离（与 DiffBlock / ReadBlock 同一条）：正文**折行**而不是 pre + 横向滚动
 * （横滚会和根层抽屉的横向拖动抢手势）；文件头那一行单行省略。
 */

/** dsh 的 CHAT_SEARCH_MAX_LINES：聊天行里的搜索卡只显示 8 行再折中 */
private const val CHAT_SEARCH_MAX_LINES = 8

/** 搜索卡的两种形状（dsh 的 SearchBlockProps.kind） */
internal enum class SearchKind { MATCHES, PATHS }

/** 一条命中行（dsh 的 SearchBlockLineMatch） */
internal data class SearchHit(val lineNumber: Int, val line: String)

/** 一个文件的命中组（dsh 的 SearchFileGroup） */
internal data class SearchFileGroup(val path: String, val matches: List<SearchHit>)

/**
 * 搜索卡模型（dsh 的 SearchCardModel.card）。
 *
 * @param shownCount 卡片持有的结果数（dsh 的 shownCount：matches 数命中行数、paths 数路径数）
 */
internal data class SearchCard(
    val kind: SearchKind,
    val files: List<SearchFileGroup>,
    val paths: List<String>,
    val truncated: Boolean,
    /** 截断前的结果总数（没截断时等于 [shownCount]） */
    val total: Int,
    /** 截断时那一句「完整结果没能保存」的恢复说明（dsh 的 recovery） */
    val recovery: String?,
) {
    val shownCount: Int = if (kind == SearchKind.PATHS) paths.size else files.sumOf { it.matches.size }
}

private val GLOB_FOOTER = Regex("^\\(Showing (\\d+) of (\\d+) paths\\.")
private val GREP_HEADER = Regex("^Found (\\d+)(?: of (\\d+))? matches?$")
private val GREP_LINE = Regex("^Line (\\d+): (.*)$")

/**
 * glob 正文 → 卡片：`No files found` / 一行一条路径 / 超上限时尾部一段
 * `(Showing N of M paths. …)` 页脚。
 *
 * 解析不出来（报错行、被 2MB 上限截断的老数据）返回 null，调用方退回 ioCard。
 */
internal fun parseGlobCard(output: String?): SearchCard? {
    val text = output?.trim() ?: return null
    if (text.isEmpty()) return null
    if (text == SearchOutputText.NO_FILES) {
        return SearchCard(SearchKind.PATHS, emptyList(), emptyList(), truncated = false, total = 0, recovery = null)
    }
    var body = text
    var truncated = false
    var total = 0
    var recovery: String? = null
    val footerAt = body.lastIndexOf("\n\n(")
    if (footerAt >= 0) {
        val parsed = GLOB_FOOTER.find(body.substring(footerAt + 2))
        if (parsed != null) {
            truncated = true
            total = parsed.groupValues[2].toIntOrNull() ?: 0
            recovery = SearchOutputText.GLOB_RECOVERY
            body = body.substring(0, footerAt)
        }
    }
    val paths = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (paths.isEmpty()) return null
    return SearchCard(
        kind = SearchKind.PATHS,
        files = emptyList(),
        paths = paths,
        truncated = truncated,
        total = if (truncated) total else paths.size,
        recovery = recovery,
    )
}

/**
 * grep 正文 → 卡片：`No matches found` / `Found N matches`（截断时 `Found N of M matches`）
 * + 空行分隔的「路径 + `Line N: 正文`」分组；尾部可能有那一句恢复说明。
 */
internal fun parseGrepCard(output: String?): SearchCard? {
    val text = output?.trim() ?: return null
    if (text.isEmpty()) return null
    if (text == SearchOutputText.NO_MATCHES) {
        return SearchCard(SearchKind.MATCHES, emptyList(), emptyList(), truncated = false, total = 0, recovery = null)
    }
    val breakAt = text.indexOf("\n\n")
    val header = if (breakAt < 0) text else text.substring(0, breakAt)
    val parsed = GREP_HEADER.matchEntire(header) ?: return null
    val shown = parsed.groupValues[1].toIntOrNull() ?: return null
    val capped = parsed.groupValues[2]
    var body = if (breakAt < 0) "" else text.substring(breakAt + 2)
    body = body.removeSuffix("\n\n(" + SearchOutputText.GREP_RECOVERY + ")")
    val files = ArrayList<SearchFileGroup>()
    body.split("\n\n").forEach { section ->
        val lines = section.lines()
        val path = lines.firstOrNull()?.trim().orEmpty()
        if (path.isEmpty()) return@forEach
        val matches = ArrayList<SearchHit>()
        lines.drop(1).forEach { line ->
            val hit = GREP_LINE.matchEntire(line) ?: return@forEach
            val number = hit.groupValues[1].toIntOrNull() ?: return@forEach
            matches += SearchHit(number, hit.groupValues[2])
        }
        if (matches.isNotEmpty()) files += SearchFileGroup(path, matches)
    }
    if (files.isEmpty()) return null
    return SearchCard(
        kind = SearchKind.MATCHES,
        files = files,
        paths = emptyList(),
        truncated = capped.isNotEmpty(),
        total = if (capped.isEmpty()) shown else capped.toIntOrNull() ?: shown,
        recovery = if (capped.isEmpty()) null else SearchOutputText.GREP_RECOVERY,
    )
}

/** 复制正文（dsh 的 copyText）：paths 一行一条；matches 是「路径 + `行号: 正文`」，组间空行 */
private fun searchCopyText(card: SearchCard): String = if (card.kind == SearchKind.PATHS) {
    card.paths.joinToString("\n")
} else {
    card.files.joinToString("\n\n") { file ->
        (listOf(file.path) + file.matches.map { it.lineNumber.toString() + ": " + it.line }).joinToString("\n")
    }
}

/** 一行渲染行（dsh 的 SearchRow：文件头 / 命中行 / 路径行） */
private sealed interface SearchRow {
    data class Header(val index: Int, val path: String, val count: Int, val collapsed: Boolean) : SearchRow
    data class Hit(val number: Int, val line: String, val fileIndex: Int) : SearchRow
    data class Path(val path: String) : SearchRow
}

/**
 * grep / glob 的展开卡（dsh 的 SearchBlock，聊天行变体）。
 *
 * @param card [parseGlobCard] / [parseGrepCard] 解析出来的模型
 */
@Composable
internal fun RailSearchBlock(card: SearchCard, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var copied by rememberCopiedFlag()
    // 折叠的**文件组下标**（dsh 的 collapsed 集合）。存成 List<Int> 才能进 rememberSaveable
    // （Set 不在 Bundle 支持的类型里）；列表被 LazyColumn 回收重建时折叠状态还在。
    var collapsedList by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    val collapsed = collapsedList.toSet()

    val rows = buildList {
        if (card.kind == SearchKind.PATHS) {
            card.paths.forEach { add(SearchRow.Path(it)) }
        } else {
            card.files.forEachIndexed { index, file ->
                val folded = collapsed.contains(index)
                add(SearchRow.Header(index, file.path, file.matches.size, folded))
                if (!folded) file.matches.forEach { add(SearchRow.Hit(it.lineNumber, it.line, index)) }
            }
        }
    }
    val hidden = rows.size - CHAT_SEARCH_MAX_LINES
    val capped = hidden > 0 && !expanded
    val headLines = ceil(CHAT_SEARCH_MAX_LINES / 2.0).toInt()
    val head = if (capped) rows.take(headLines) else rows
    val naturalTail = if (capped) rows.takeLast(CHAT_SEARCH_MAX_LINES - headLines) else emptyList()
    // 尾切片正好从某个文件组的中间开始时，那一组的文件头在切点之上 —— 补回尾部顶端，
    // 否则尾巴那几行看不出属于哪个文件；头切片里已经有它时（单个大文件）不补，免得画两遍。
    // 补回来的头**占掉尾部第一行**：可见行数仍是 maxLines，hidden 也不会多算一行（dsh 同）。
    val lead = naturalTail.firstOrNull()
    val tailHeader = if (lead is SearchRow.Hit && head.none { it is SearchRow.Header && it.index == lead.fileIndex }) {
        rows.firstOrNull { it is SearchRow.Header && it.index == lead.fileIndex }
    } else {
        null
    }
    val tail = if (tailHeader == null) naturalTail else naturalTail.drop(1)

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.codeBlock),
        ) {
            // 横幅（dsh 的 .header：padding 9px 14px，底色 markdown-code-block-banner）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.codeBlockBanner)
                    .padding(horizontal = DshSpacing.Section, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = searchSummary(card),
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    // dsh 的 .copyButton：文字按钮（这一张卡不用 CodeToolbar 的图标）
                    Text(
                        text = if (copied) "复制成功" else "复制",
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .dshClickable(interactionSource = dshInteraction()) {
                                copyToClipboard(context, searchCopyText(card))
                                copied = true
                            },
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = palette.labelSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    text = "无结果",
                    modifier = Modifier.padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 19.sp,
                    color = palette.labelTertiary,
                )
            } else {
                // 正文（dsh 的 .body：padding 8px 14px 12px 0，行自己带 14px 左内边距）
                Column(Modifier.fillMaxWidth().padding(top = DshSpacing.Xl, end = DshSpacing.Section, bottom = DshSpacing.Xxxl)) {
                    head.forEach { SearchRowView(it) { index -> collapsedList = toggleIndex(collapsedList, index) } }
                    if (hidden > 0) {
                        Text(
                            text = if (expanded) "收起" else "… 其余 " + hidden + " 行",
                            modifier = Modifier
                                .padding(start = DshSpacing.Section, top = DshSpacing.Xs, bottom = DshSpacing.Xs)
                                .clip(RoundedCornerShape(4.dp))
                                .dshClickable(interactionSource = dshInteraction()) { expanded = !expanded },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 19.sp,
                            color = palette.labelTertiary,
                        )
                    }
                    if (tailHeader != null) {
                        SearchRowView(tailHeader) { index -> collapsedList = toggleIndex(collapsedList, index) }
                    }
                    tail.forEach { SearchRowView(it) { index -> collapsedList = toggleIndex(collapsedList, index) } }
                }
            }
        }
        // dsh 的 .searchRecovery：卡片下面那行恢复说明（截断时才有）
        if (card.recovery != null) {
            Text(
                text = card.recovery,
                modifier = Modifier.padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md, end = DshSpacing.Md),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.labelTertiary,
            )
        }
    }
}

/** 折叠集合的增删（List<Int> 形态的 Set） */
private fun toggleIndex(list: List<Int>, index: Int): List<Int> =
    if (list.contains(index)) list.filterNot { it == index } else list + index

/**
 * 横幅摘要（dsh 的 summaryText + zh 字典）：
 * `N 个路径` / `显示 N / 共 M 个路径` / `N 处匹配 · K 个文件` / `显示 N / 共 M 处匹配 · K 个文件`。
 */
private fun searchSummary(card: SearchCard): String = if (card.kind == SearchKind.PATHS) {
    if (card.truncated) {
        "显示 " + card.shownCount + " / 共 " + card.total + " 个路径"
    } else {
        card.shownCount.toString() + " 个路径"
    }
} else {
    val files = card.files.size
    if (card.truncated) {
        "显示 " + card.shownCount + " / 共 " + card.total + " 处匹配 · " + files + " 个文件"
    } else {
        card.shownCount.toString() + " 处匹配 · " + files + " 个文件"
    }
}

/** 画一行（dsh 的 renderRow） */
@Composable
private fun SearchRowView(row: SearchRow, onToggleGroup: (Int) -> Unit) {
    val palette = LocalDshPalette.current
    when (row) {
        is SearchRow.Path -> Text(
            text = row.path,
            modifier = Modifier.fillMaxWidth().padding(start = DshSpacing.Section),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 19.sp,
            color = palette.labelPrimary,
        )

        is SearchRow.Hit -> Text(
            // 行号与正文**同一段文本**：折行时行号只出现在第一行开头（dsh 是一个 span）
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = palette.labelTertiary)) { append(row.number.toString() + ": ") }
                append(row.line)
            },
            modifier = Modifier.fillMaxWidth().padding(start = DshSpacing.Section),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 19.sp,
            color = palette.labelPrimary,
        )

        is SearchRow.Header -> Row(
            Modifier
                .fillMaxWidth()
                .dshClickable(interactionSource = dshInteraction()) { onToggleGroup(row.index) }
                .padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.path,
                // weight(fill = false)：路径收缩到文字宽度，短路径时右边空出来的地方仍可点（折叠那一组）
                modifier = Modifier.weight(1f, fill = false),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.count.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 19.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
