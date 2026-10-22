package com.adsh.app.ui.blocks

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.theme.DshPalette
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * dsh 的滚动条皮肤（ui-theme/styles/scrollbar.css）：宽 5px、轨道透明、thumb 全圆角、只在可滚时出现。
 *
 * dsh 把这层皮肤挂在所有原生滚动容器上，并按面的高度重绑 thumb 色：页面级滚动用 l1，
 * 抬升面（菜单 / 弹层 / 任务面板 / 卡片）用 l2 —— 对应下面的 elevated。
 * ADSH 侧是**覆盖式**绘制：不占布局（dsh 的 webkit 滚动条占 5px），也不吃手势；滚动状态只在
 * 绘制阶段读，不触发重组。容器最后 5dp 的正文会被 thumb 盖住，正文自带右/下内边距不足 5dp 的
 * 地方用 inset 参数把 thumb 让开。
 */
object DshScrollbar {
    /** dsh 的 --dsh-scrollbar-width（宽高同值） */
    val Width = 5.dp
    /** thumb 的可见下限（短内容段也要能看见） */
    val MinThumb = 24.dp
}

/**
 * 竖轴，用在 [ScrollState] 的滚动容器上：
 * `Modifier.dshVerticalScrollbar(scroll).verticalScroll(scroll)`。
 *
 * @param elevated 抬升面（菜单 / 弹层 / 任务面板 / 卡片）用 l2；页面级滚动用 l1
 * @param topInset / [bottomInset] thumb 轨道相对容器上下边的内缩（给圆角或内边距让位）
 */
@Composable
fun Modifier.dshVerticalScrollbar(
    state: ScrollState,
    elevated: Boolean = false,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
): Modifier {
    val color = LocalDshPalette.current.scrollbarThumbFor(elevated)
    val density = LocalDensity.current
    val widthPx = with(density) { DshScrollbar.Width.toPx() }
    val minThumbPx = with(density) { DshScrollbar.MinThumb.toPx() }
    val topPx = with(density) { topInset.toPx() }
    val bottomPx = with(density) { bottomInset.toPx() }
    return drawWithContent {
        drawContent()
        val viewport = size.height - topPx - bottomPx
        val max = state.maxValue
        if (viewport <= 0f || widthPx <= 0f || max <= 0) return@drawWithContent
        val thumb = thumbLength(viewport, viewport + max, minThumbPx)
        val progress = (state.value.toFloat() / max).coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - widthPx, topPx + (viewport - thumb) * progress),
            size = Size(widthPx, thumb),
            cornerRadius = CornerRadius(widthPx / 2f),
        )
    }
}

/** 横轴，用在 [ScrollState] 的横向滚动容器上（贴在容器底边）。 */
@Composable
fun Modifier.dshHorizontalScrollbar(
    state: ScrollState,
    elevated: Boolean = false,
    startInset: Dp = 0.dp,
    endInset: Dp = 0.dp,
): Modifier {
    val color = LocalDshPalette.current.scrollbarThumbFor(elevated)
    val density = LocalDensity.current
    val widthPx = with(density) { DshScrollbar.Width.toPx() }
    val minThumbPx = with(density) { DshScrollbar.MinThumb.toPx() }
    val startPx = with(density) { startInset.toPx() }
    val endPx = with(density) { endInset.toPx() }
    return drawWithContent {
        drawContent()
        val viewport = size.width - startPx - endPx
        val max = state.maxValue
        if (viewport <= 0f || widthPx <= 0f || max <= 0) return@drawWithContent
        val thumb = thumbLength(viewport, viewport + max, minThumbPx)
        val progress = (state.value.toFloat() / max).coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(startPx + (viewport - thumb) * progress, size.height - widthPx),
            size = Size(thumb, widthPx),
            cornerRadius = CornerRadius(widthPx / 2f),
        )
    }
}

/**
 * 竖轴，用在 [LazyListState] 的列表上（文件预览 / 文档预览 / 终端回滚这类）。
 *
 * Lazy 列表拿不到总内容高度，按可见项的**平均项高 × 总项数**估算 —— 等高行的列表足够准；
 * 行高不齐的列表 thumb 长度/位置会略有跳动。
 */
@Composable
fun Modifier.dshVerticalScrollbar(
    state: LazyListState,
    elevated: Boolean = false,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
): Modifier {
    val color = LocalDshPalette.current.scrollbarThumbFor(elevated)
    val density = LocalDensity.current
    val widthPx = with(density) { DshScrollbar.Width.toPx() }
    val minThumbPx = with(density) { DshScrollbar.MinThumb.toPx() }
    val topPx = with(density) { topInset.toPx() }
    val bottomPx = with(density) { bottomInset.toPx() }
    return drawWithContent {
        drawContent()
        val viewport = size.height - topPx - bottomPx
        if (viewport <= 0f || widthPx <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (info.totalItemsCount <= 0 || visible.isEmpty()) return@drawWithContent
        val first = visible.first()
        val last = visible.last()
        val average = (last.offset + last.size - first.offset).toFloat() /
            (last.index - first.index + 1)
        if (average <= 0f) return@drawWithContent
        val content = average * info.totalItemsCount
        if (content <= viewport) return@drawWithContent
        val thumb = thumbLength(viewport, content, minThumbPx)
        // 已滚进首项多少像素（首项 offset 相对视口起点；含 contentPadding 时向上溢出按 0 算）
        val intoFirst = (-(first.offset - info.viewportStartOffset)).toFloat().coerceIn(0f, average)
        val maxFirstIndex = (info.totalItemsCount - visible.size).coerceAtLeast(1).toFloat()
        val progress = ((first.index + intoFirst / average) / maxFirstIndex).coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - widthPx, topPx + (viewport - thumb) * progress),
            size = Size(widthPx, thumb),
            cornerRadius = CornerRadius(widthPx / 2f),
        )
    }
}

/** thumb 长度：按「视口 / 内容」比例，夹在 [minThumb]（不超过视口）与视口之间 */
private fun thumbLength(viewport: Float, content: Float, minThumb: Float): Float =
    (viewport * viewport / content).coerceAtLeast(minThumb.coerceAtMost(viewport)).coerceAtMost(viewport)

private fun DshPalette.scrollbarThumbFor(elevated: Boolean) =
    if (elevated) scrollbarThumbL2 else scrollbarThumb
