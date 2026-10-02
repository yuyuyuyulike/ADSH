package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * todo_write 的展开体 —— 对齐 dsh 的 todo 行（ui-tool 的 todo-row.tsx + todo-diff-model.ts，
 * 正文用 ui-tool 的 ToolDetails.tsx 渲染）：
 *
 *  - **行摘要**（[todoRowSummary]）照 plan-summary.ts：`{done}/{total} 已完成`，有进行中的任务
 *    再缀上第一条的名字；后面接清单差异摘要（`新增 1 · 更新 2`）与「还有几条在跑」的 `+N`；
 *  - **展开卡**是 ToolDetails 的清单：一条一行 —— 状态字形（✓ 已完成 / ▶ 进行中 / □ 待处理，
 *    新增显示 `+`、移除显示 `−`）+ 任务正文 + 右对齐的状态文字（状态变过时是 `旧 → 新`）；
 *  - 清单是「与上一次清单相比」的差异：新增 / 状态变化 / 顺序调整 / 移除各带自己的字形与颜色，
 *    没变的收在「N 项未变化」折叠里；比不了（找不到上一次清单）时挂 dsh 的「旧清单不可用」；
 *  - 上限 320dp、超出在卡内滚动，条目之间 0.5px 分隔线（dsh 的 .list / .item）。
 *
 * 基准（上一次清单）从**上一行 todo_write 的参数**来：同一条 run_code 行里的前一条子调用，
 * 或本会话更早的那一行（TurnBuilder 一路带着它传下来，见 RailTool.todoBaseline）。
 * dsh 是会话级的 todoHistory 投影，口径一致。
 */

/** 一条任务（dsh 的 ToolDetailItem 的 todo 形态） */
internal data class TodoCardItem(
    val content: String,
    val status: String,
    /** added / removed / updated（null = 没变化） */
    val change: String? = null,
    /** 状态变过时上一版的状态（dsh 的 previousStatus，画成 `旧 → 新`） */
    val previousStatus: String? = null,
    /** 只是顺序挪了（dsh 的 movedItem：字形与「状态变化」共用，读屏名字不同） */
    val moved: Boolean = false,
)

/** todo 卡（dsh 的 ToolDetailsModel） */
internal data class TodoCard(
    /** 有变化的条目；「旧清单不可用」时就是当前全部条目（不带差异标记） */
    val items: List<TodoCardItem>,
    /** 没变化的条目（收在「N 项未变化」折叠里） */
    val unchanged: List<TodoCardItem>,
    val caption: String,
)

/** dsh 的 detail.todo.* 状态名（zh 字典逐字） */
private fun statusLabel(status: String): String = when (status) {
    "completed" -> "已完成"
    "in_progress" -> "进行中"
    else -> "待处理"
}

/** dsh 的 todo.diff.*Item 变化名（zh 字典逐字） */
private fun changeLabel(change: String): String = when (change) {
    "added" -> "新增"
    "removed" -> "移除"
    else -> "状态变化"
}

/**
 * 参数 JSON → 任务清单。**逐条照 dsh 的 todosDetail 校验**：任意一条的 content 为空、
 * status 不认识、标题重复，整张卡就不成立（返回 null，调用方退回通用卡）。
 */
internal fun todoItemsOf(arguments: String): List<Pair<String, String>>? {
    val args = ToolArgs.parse(arguments) ?: return null
    val todos = args["todos"] as? JsonArray ?: return null
    val out = ArrayList<Pair<String, String>>(todos.size)
    val seen = HashSet<String>()
    todos.forEach { element ->
        val item = element as? JsonObject ?: return null
        val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return null
        val status = (item["status"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (status != "completed" && status != "in_progress" && status != "pending") return null
        if (!seen.add(content)) return null
        out += content to status
    }
    return out
}

/**
 * 清单差异（dsh 的 todoDiffModel）：与 [baseline]（上一次 todo_write 的参数）比出
 * 新增 / 状态变化 / 顺序调整 / 移除，返回（摘要文案, 卡片模型）。
 * 摘要文案为空串 = 这条摘要没有（基准不可用 / 清单没变化时按 dsh 的 noChanges 给一句）。
 */
internal fun todoDiffOf(current: List<Pair<String, String>>, baseline: String?): Pair<String, TodoCard> {
    val previous = baseline?.let { todoItemsOf(it) }
    if (previous == null) {
        // dsh：基准不可用时只画当前清单，并挂一句「旧清单不可用」
        return "" to TodoCard(current.map { TodoCardItem(it.first, it.second) }, emptyList(), "旧清单不可用")
    }
    // 标题 → 状态（保持上一次清单的顺序：移除项按原顺序排在后面，dsh 同）
    val previousByTitle = LinkedHashMap<String, String>()
    previous.forEach { (title, status) -> previousByTitle[title] = status }
    val currentTitles = current.map { it.first }.toSet()
    val retainedOrder = previous.filter { currentTitles.contains(it.first) }.map { it.first }
    val items = ArrayList<TodoCardItem>()
    val unchanged = ArrayList<TodoCardItem>()
    var retainedIndex = 0
    var added = 0
    var updated = 0
    current.forEach { (content, status) ->
        val before = previousByTitle.remove(content)
        if (before == null) {
            added++
            items += TodoCardItem(content, status, change = "added")
        } else {
            val moved = retainedOrder.getOrNull(retainedIndex) != content
            retainedIndex++
            if (before != status || moved) {
                updated++
                items += TodoCardItem(
                    content = content,
                    status = status,
                    change = "updated",
                    previousStatus = statusLabel(before).takeIf { before != status },
                    moved = before == status,
                )
            } else {
                unchanged += TodoCardItem(content, status)
            }
        }
    }
    previousByTitle.forEach { (title, status) -> items += TodoCardItem(title, status, change = "removed") }
    val parts = ArrayList<String>()
    if (added > 0) parts += "新增 " + added
    if (updated > 0) parts += "更新 " + updated
    if (previousByTitle.isNotEmpty()) parts += "移除 " + previousByTitle.size
    val caption = if (previous.isEmpty()) "首次记录" else "与上次清单相比"
    return (parts.joinToString(" · ").ifEmpty { "清单没有变化" }) to TodoCard(items, unchanged, caption)
}

/**
 * 行摘要（dsh 的 summarize + planSummary）：`{done}/{total} 已完成`，有进行中的再缀第一条名字，
 * 后面接差异摘要与「还有几条在跑」的 `+N`。解析不出来（流式半截 JSON / 非法清单）返回 null，
 * 调用方退回通用摘要。
 */
internal fun todoRowSummary(arguments: String, baseline: String?): String? {
    val todos = todoItemsOf(arguments) ?: return null
    val done = todos.count { it.second == "completed" }
    val active = todos.filter { it.second == "in_progress" }
    val activeContent = active.firstOrNull()?.first?.takeIf { it.isNotBlank() }
    val head = done.toString() + "/" + todos.size + " 已完成"
    val text = if (activeContent == null) head else head + " · " + activeContent
    // dsh 的 summarySuffix：差异摘要 + 还有几条在跑（基准不可用时没有差异那一段）
    val (diffSummary, _) = todoDiffOf(todos, baseline)
    val extra = if (activeContent != null) active.size - 1 else 0
    val suffix = listOfNotNull(
        diffSummary.takeIf { baseline != null && it.isNotEmpty() },
        ("+" + extra).takeIf { extra > 0 },
    ).joinToString(" · ")
    return if (suffix.isEmpty()) text else text + " · " + suffix
}

/** todo 卡（dsh 的 ToolDetails：清单 + 差异字形 + 「N 项未变化」折叠） */
@Composable
internal fun RailTodoCard(card: TodoCard, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    var showUnchanged by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp)),
    ) {
        if (card.caption.isNotEmpty()) {
            Text(
                text = card.caption,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelCaption,
            )
        }
        if (card.items.isEmpty() && card.unchanged.isEmpty()) {
            Text(
                text = "任务清单为空",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.labelSecondary,
            )
        } else {
            // dsh 的 .list：上限 320px + 卡内滚动
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                card.items.forEachIndexed { index, item -> TodoItemRow(item, divider = index > 0) }
                if (card.unchanged.isNotEmpty()) {
                    TodoUnchangedRow(card.unchanged.size, showUnchanged) { showUnchanged = !showUnchanged }
                    if (showUnchanged) {
                        card.unchanged.forEachIndexed { index, item ->
                            TodoItemRow(item, divider = index > 0)
                        }
                    }
                }
            }
        }
    }
}

/** 一条任务（dsh 的 DetailItem 的 todo 形态：字形 + 正文 + 右对齐状态文字） */
@Composable
private fun TodoItemRow(item: TodoCardItem, divider: Boolean) {
    val palette = LocalDshPalette.current
    val removed = item.change == "removed"
    Column(Modifier.fillMaxWidth()) {
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 0.5.dp,
                color = palette.borderL2,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TodoGlyph(item)
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.content,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = if (removed) palette.labelTertiary else palette.labelSecondary,
                textDecoration = if (removed) TextDecoration.LineThrough else null,
                fontWeight = if (item.status == "in_progress") FontWeight.Medium else FontWeight.Normal,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (item.previousStatus != null) {
                    item.previousStatus + " → " + statusLabel(item.status)
                } else {
                    statusLabel(item.status)
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelCaption,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 状态字形（dsh 的 .status）：新增 `+`（成功色）、移除 `−`（错误色）、
 * 已完成 ✓、进行中 ▶，其余是 10×10 的方框（dsh 的 .pending）。
 */
@Composable
private fun TodoGlyph(item: TodoCardItem) {
    val palette = LocalDshPalette.current
    Box(Modifier.width(14.dp), contentAlignment = Alignment.CenterStart) {
        when {
            item.change == "added" -> Text(
                text = "+",
                fontSize = 16.sp,
                lineHeight = 18.sp,
                color = palette.success,
            )

            item.change == "removed" -> Text(
                text = "−",
                fontSize = 16.sp,
                lineHeight = 18.sp,
                color = palette.errorLabel,
            )

            item.status == "completed" -> Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = changeLabelOrStatus(item),
                modifier = Modifier.size(14.dp),
                tint = palette.labelTertiary,
            )

            item.status == "in_progress" -> Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = changeLabelOrStatus(item),
                modifier = Modifier.size(14.dp),
                tint = palette.labelTertiary,
            )

            else -> Box(Modifier.size(10.dp).border(1.dp, palette.labelTertiary, RoundedCornerShape(2.dp)))
        }
    }
}

/** 字形读屏用的名字（dsh 的 aria-label：有变化用变化名，否则用状态名） */
private fun changeLabelOrStatus(item: TodoCardItem): String = when {
    item.change == "updated" && item.moved -> "顺序调整"
    item.change != null -> changeLabel(item.change)
    else -> statusLabel(item.status)
}

/** 「N 项未变化」折叠（dsh 的 details.unchanged：收起朝右、展开朝下） */
@Composable
private fun TodoUnchangedRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 14.dp),
            thickness = 0.5.dp,
            color = palette.borderL2,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggle() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = DshIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(if (open) 0f else -90f),
                tint = palette.labelTertiary,
            )
            Text(
                text = count.toString() + " 项未变化",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
            )
        }
    }
}
