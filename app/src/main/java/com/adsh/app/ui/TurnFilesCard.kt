package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.agent.FileChange
import com.adsh.app.core.agent.PresentedFile
import com.adsh.app.core.agent.mergeTurnFiles
import com.adsh.app.ui.theme.LocalDshPalette

/** dsh 的 `.added` / `.deleted` 配色（ChangedFiles.module.css 的绿 / 红） */
private val CHANGE_ADDED = Color(0xFF2E9E5B)
private val CHANGE_DELETED = Color(0xFFD2453D)

/** 卡片里最多平铺几行文件名，超过就折起来（dsh 的 COLLAPSED_ROWS = 4） */
private const val CHANGED_FILES_COLLAPSED_ROWS = 4

/** dsh 的 `.tile`：标题块左侧那枚 40×40 的圆角方块（里面嵌 20dp 的文件角标） */
private val CARD_TILE_DP = 40.dp

/**
 * 标题块左侧的角标底框（dsh 的 `.tile`：40×40、0.5px 描边、半透明混白底）。
 *
 * dsh 的底是 `color-mix(in srgb, neutral-00 50%, transparent)`（深色下 5%）叠在标题块的
 * `--changes-fill`（neutral-50 / neutral-850）上 —— 浅色下比标题块更白、深色下更亮，
 * 于是两个主题里它都是一枚**微微抬起**的方块。Compose 没有 color-mix，按调色板的 `dark` 取比例。
 */
@Composable
private fun CardTile(content: @Composable () -> Unit) {
    val palette = LocalDshPalette.current
    val tile = Color.White.copy(alpha = if (palette.dark) 0.05f else 0.55f)
    Box(
        modifier = Modifier
            .size(CARD_TILE_DP)
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp))
            .background(tile),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 单文件标题里只显示文件名（用户第 84 轮点名：屏幕有限，不弄完整路径） */
private fun fileNameOf(path: String): String =
    runCatching { java.io.File(path).name }.getOrNull()?.takeIf { it.isNotEmpty() } ?: path

/**
 * 轮尾的文件卡片（dsh 的 ui-deliverables / ChangedFiles）。
 *
 * 第 86 轮把原来分开的两张卡（「已编辑」+「交付文件」）**并成这一张** —— 用户点名：
 * 「交付的文件上面多了一块」「你做的那一块少了 +a -b 部分，你可以仔细参考 dsh」：
 * 同一个文件不该被画两次，卡片形状则照 dsh 那一张（标题块 + 合计 + 一行一个文件 + 折行）。
 *
 * 逐项对齐 dsh（`ChangedFiles.tsx` / `ChangedFiles.module.css`）：
 *  - 标题块：`.tile` 40dp 圆角方块里嵌 20dp 角标 —— **多文件用通用 `</>` 角标**
 *    （`FileTypeIcon kind="code"`），**单文件用那个文件自己的角标**（`<FileTypeIcon path=… />`）；
 *    标题 13/20 中黑；下面一行合计（代码字体、`+` 绿 `-` 红）；
 *  - **单文件时只有标题块**（dsh 同）：标题 = `已编辑 <文件名>` + 这个文件的 `+a -b`；
 *  - 多文件：标题块 + 分隔线 + 一行一个文件（路径在左、`+a -b` 在右；`过大` / `二进制` 不显示数字），
 *    超过 4 行折起来，折行按钮**靠左**并跟一个 14dp 倒角（dsh 的 `.toggle`）；
 *  - **只交付、这一轮没改过**的文件也在这个列表里，只是右边留空（没有增删行可显示）；
 *    路径口径按第 85 轮：`deliverableDisplayPath` 裁掉工作区前缀，工作区外原样；
 *  - 整张卡（单文件时）/ 每一行（多文件时）可点：进文件预览 —— dsh 是进侧栏的 diff 审阅，
 *    ADSH 没有 diff 视图，落到预览页。
 *
 * 两处刻意的手机侧偏离：①行之间加了 0.5px 分隔线（dsh 只有列表上沿那一条；手机上没有 hover
 * 高亮，长路径和数字挨在一起容易看串行）；②合计字号取 11sp（dsh 是 10px，手机上太小）。
 */
@Composable
fun TurnFilesCard(
    changes: List<FileChange>,
    deliverables: List<PresentedFile>,
    workspacePath: String?,
    onOpen: (String) -> Unit,
    /**
     * 卡片展开了 / 收起了（读者动作）。
     *
     * 与工具行的展开同一条规矩（第 87 轮）：**展开天然向下长、上面已经画好的行一动不动，
     * 也不做任何补偿滚动** —— 卡片在列表**项内部**，如果这一下还跟着把视口钉到底，整屏内容会
     * 往上挪一截（用户第 108 轮点名「展开卡片直接向下展开就是了，怎么还会把消息往上顶」）。
     * 回调让本屏知道「读者刚动过」，于是这一下不恢复自动跟随、下面那个兜底补钉也不会来推一把
     * （否则新露出来的几行会先被画在视口底边、下一帧再闪回正确位置）。
     */
    onToggle: () -> Unit = {},
) {
    val palette = LocalDshPalette.current
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(changes, deliverables, workspacePath) {
        mergeTurnFiles(changes, deliverables, workspacePath)
    }
    if (rows.isEmpty()) return
    val single = rows.singleOrNull()
    val edited = rows.filter { it.edited }
    val added = edited.sumOf { it.added }
    val deleted = edited.sumOf { it.deleted }
    // 单文件**且改过**时才收成「只有标题块」（dsh 的单文件形态）。只交付没改过的那个文件必须
    // 把路径画出来（第 85 轮用户点名：保留路径）—— 那种情况下标题是「交付文件」，撑不起路径。
    val headerOnly = single != null && single.edited
    val foldable = rows.size > CHANGED_FILES_COLLAPSED_ROWS
    val shown = if (foldable && !expanded) rows.take(CHANGED_FILES_COLLAPSED_ROWS) else rows
    // 标题：单文件且改过 → dsh 的 changes.singleTitle；有改动的多文件 → changes.title
    // （N 取**行数**，这样数字和下面的列表永远一致）；这一轮一个文件都没改过（纯交付）→
    // 第 85 轮定的「交付文件」。
    val title = when {
        single != null && single.edited -> "已编辑 " + fileNameOf(single.path)
        edited.isNotEmpty() -> "已编辑 " + rows.size + " 个文件"
        else -> "交付文件"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(0.5.dp, palette.borderL2, RoundedCornerShape(16.dp))
            .background(palette.inputMajor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.inlineCode)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onOpen(rows.first().path)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardTile {
                if (single != null) {
                    FileTypeIcon(name = fileNameOf(single.path), size = 20.dp)
                } else {
                    CodeTypeIcon(size = 20.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (edited.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (single != null && (single.binary || single.oversized)) {
                            ChangeCount(if (single.binary) "二进制" else "过大", palette.labelTertiary)
                        } else {
                            ChangeCount("+" + added, CHANGE_ADDED)
                            ChangeCount("-" + deleted, CHANGE_DELETED)
                        }
                    }
                }
            }
        }
        if (!headerOnly) {
            HorizontalDivider(thickness = 0.5.dp, color = palette.borderL2)
            shown.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onOpen(row.path)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.path,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = palette.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 只交付、这一轮没改过：右边留空（没有增删行可显示）
                    if (row.edited && (row.binary || row.oversized)) {
                        ChangeCount(if (row.binary) "二进制" else "过大", palette.labelTertiary)
                    } else if (row.edited) {
                        ChangeCount("+" + row.added, CHANGE_ADDED)
                        ChangeCount("-" + row.deleted, CHANGE_DELETED)
                    }
                }
            }
            if (foldable) {
                HorizontalDivider(thickness = 0.5.dp, color = palette.borderL2)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            expanded = !expanded
                            // 这一项的高度变了：先告诉本屏，让它在这一帧的测量之前重钉底部
                            onToggle()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Text(
                        text = if (expanded) "收起" else "全部 " + rows.size + " 个文件",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = palette.labelTertiary,
                    )
                    RailChevron(open = expanded)
                }
            }
        }
    }
}

/** 卡片里的增删数字：dsh 用代码字体（`.statCounts` / `.counts` 都是 `--ds-font-family-code`） */
@Composable
private fun ChangeCount(text: String, color: Color) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = color,
        maxLines = 1,
    )
}
