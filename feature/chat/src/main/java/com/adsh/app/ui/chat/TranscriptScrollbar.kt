package com.adsh.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.theme.LocalDshPalette

/** dsh 的 --dsh-scrollbar-width：5px；绘制贴屏幕最右侧。 */
private val SCROLLBAR_WIDTH = 5.dp

/** 轨道两端内缩：dsh 的 ::-webkit-scrollbar-track 有 margin-block: 2px。 */
private val SCROLLBAR_TRACK_INSET = 2.dp

/** 最短 thumb：dsh 无规定，24dp 是 ADSH 的取值（内容再长也保持可辨认）。 */
private val SCROLLBAR_MIN_THUMB = 24.dp

/** 用户停止滚动后多久隐藏滚动条（用户口径：一秒）。 */
internal const val SCROLLBAR_HIDE_DELAY_MS = 1000L

/** 隐藏 / 显现的渐变时长。 */
internal const val SCROLLBAR_FADE_MS = 200

/** thumb 的像素几何；topPx 相对轨道顶端（调用方再加上下内缩）。 */
internal data class ScrollbarThumb(val topPx: Float, val lengthPx: Float)

/**
 * 精确的 thumb 几何：内容高度 = 视口 + 可滚距离（浏览器 scrollHeight 语义）；
 * 比例 = 视口 / 内容，位置 = 已滚距离 / 可滚距离。视口装得下内容时返回 null（不画）。
 *
 * [trackInsetPx] 是轨道上下各内缩的量；[minThumbPx] 内容再长也保持可辨认。
 */
internal fun scrollbarThumb(
    viewportPx: Int,
    maxScrollPx: Int,
    scrollValuePx: Int,
    minThumbPx: Float,
    trackInsetPx: Int = 0,
): ScrollbarThumb? {
    if (viewportPx <= 0 || maxScrollPx <= 0) return null
    val trackPx = viewportPx - 2 * trackInsetPx.coerceAtLeast(0)
    if (trackPx <= 0) return null
    val contentPx = viewportPx.toFloat() + maxScrollPx
    val length = (trackPx.toFloat() * viewportPx / contentPx)
        .coerceAtLeast(minThumbPx.coerceAtMost(trackPx.toFloat()))
        .coerceAtMost(trackPx.toFloat())
    val travel = trackPx - length
    val progress = scrollValuePx.coerceIn(0, maxScrollPx).toFloat() / maxScrollPx
    return ScrollbarThumb(
        topPx = progress * travel,
        lengthPx = length,
    )
}

/**
 * 会话区最右侧（贴屏幕边缘）的 overlay 滚动条：**只做位置指示，不参与手势**。
 *
 * dsh 是占 5px 布局的原生条；ADSH 有意改成不占布局的 overlay，其余照 dsh：
 * 轨道透明、thumb 圆角 999、轨道两端内缩 [SCROLLBAR_TRACK_INSET]。长度与位置直接取滚动容器的
 * 真实可滚距离 —— 与 dsh 的 scrollHeight 语义一致，内容没变时长度不动。
 *
 * 可见性由调用方给（[visible]）：读者停止滚动满 [SCROLLBAR_HIDE_DELAY_MS] 后隐藏、再滚又出现，
 * 淡入淡出 [SCROLLBAR_FADE_MS]。不可滚时照旧不画。
 *
 * **滚动状态只在绘制阶段读**：value / maxValue 每帧都变，在组合期读会让这个 composable 跟着
 * 每一帧重组（长会话里纯属白烧）。绘制阶段读只重画这一条 5dp 的 RenderNode。
 */
@Composable
internal fun TranscriptScrollbar(
    scrollState: ScrollState,
    /** 底部 footer 高度（px）：轨道避开它（footer 叠在会话区之上） */
    bottomInsetPx: Int = 0,
    /** false = 自动隐藏（渐隐中或已隐藏） */
    visible: Boolean = true,
) {
    val palette = LocalDshPalette.current
    val density = LocalDensity.current
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SCROLLBAR_FADE_MS),
        label = "scrollbarAlpha",
    )
    val trackInsetPx = with(density) { SCROLLBAR_TRACK_INSET.roundToPx() }
    val minThumbPx = with(density) { SCROLLBAR_MIN_THUMB.toPx() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(bottom = with(density) { bottomInsetPx.coerceAtLeast(0).toDp() })
                .width(SCROLLBAR_WIDTH),
        ) {
            if (alpha > 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    // 绘制阶段读滚动状态：只让这一条重画，不触发组合
                    val viewportPx = size.height.toInt()
                    val maxScroll = scrollState.maxValue
                    val scrollValue = scrollState.value
                    val raw = scrollbarThumb(viewportPx, maxScroll, scrollValue, minThumbPx, trackInsetPx)
                        ?: return@Canvas
                    val trackPx = viewportPx - 2 * trackInsetPx
                    val top = when {
                        scrollValue <= 0 -> 0f
                        scrollValue >= maxScroll -> trackPx - raw.lengthPx
                        else -> raw.topPx
                    }
                    val color = palette.scrollbarThumb
                    drawRoundRect(
                        color = color.copy(alpha = color.alpha * alpha),
                        topLeft = Offset(0f, trackInsetPx + top),
                        size = Size(size.width, raw.lengthPx),
                        cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
                    )
                }
            }
        }
    }
}
