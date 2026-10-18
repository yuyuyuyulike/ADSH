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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.theme.LocalDshPalette

/** dsh 的 --dsh-scrollbar-width：5px；绘制贴屏幕最右侧。 */
private val SCROLLBAR_WIDTH = 5.dp

/** 可拖动的命中区：比视觉宽，方便按住（只有 thumb 那一段吃手势）。 */
internal val SCROLLBAR_HIT_WIDTH = 24.dp

/** 轨道两端内缩：dsh 的 ::-webkit-scrollbar-track 有 margin-block: 2px。 */
private val SCROLLBAR_TRACK_INSET = 2.dp

/** 最短 thumb：dsh 无规定，24dp 是 ADSH 的取值（内容再长也保持可辨认）。 */
private val SCROLLBAR_MIN_THUMB = 24.dp

/** 用户停止滚动后多久隐藏滚动条（用户口径：三秒）。 */
internal const val SCROLLBAR_HIDE_DELAY_MS = 3000L

/** 隐藏 / 显现的渐变时长。 */
internal const val SCROLLBAR_FADE_MS = 200

/** thumb 的像素几何；[maxScrollPx] 给拖动换算用。topPx 相对轨道顶端（调用方再加上下内缩）。 */
internal data class ScrollbarThumb(val topPx: Float, val lengthPx: Float, val maxScrollPx: Float)

/** 一条 thumb 的绘制/拖动几何（px，相对会话区视口；topPx 已含轨道内缩）。 */
internal data class ThumbGeometry(
    val topPx: Float,
    val lengthPx: Float,
    val viewportPx: Float,
    val maxScrollPx: Float,
)

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
        maxScrollPx = maxScrollPx.toFloat(),
    )
}

/** 拖动 thumb 的像素位移 → 列表滚动位移（同一比例；轨道不可动时不动）。 */
internal fun thumbDragScrollDelta(dragDyPx: Float, travelPx: Float, maxScrollPx: Float): Float =
    if (travelPx <= 0f) 0f else dragDyPx / travelPx * maxScrollPx

/**
 * 这一次「按下」是不是落在可拖的 thumb 命中区：右缘 [hitWidthPx] 宽 × thumb 的垂直范围。
 * 非命中区的按下不消费，列表滚动照常（命中区比视觉宽，但只占 thumb 那一段）。
 */
internal fun isOnScrollbarThumb(
    x: Float,
    y: Float,
    viewportWidthPx: Float,
    hitWidthPx: Float,
    topPx: Float,
    lengthPx: Float,
): Boolean = viewportWidthPx > 0f &&
    x >= viewportWidthPx - hitWidthPx &&
    y >= topPx &&
    y <= topPx + lengthPx

/**
 * 按下那一刻现算的 thumb 几何（会话区本地坐标，给 [isOnScrollbarThumb] 与拖动换算用）。
 *
 * 滚动条只在绘制阶段读滚动状态，所以命中区 / 拖动不能依赖「上次组合时算好的几何」——
 * 这里按调用时的 value / maxValue 现算；不可滚时返回 null。
 */
internal fun scrollbarThumbGeometry(
    scrollState: ScrollState,
    viewportPx: Int,
    density: Density,
): ThumbGeometry? {
    val inset = with(density) { SCROLLBAR_TRACK_INSET.roundToPx() }
    val minThumb = with(density) { SCROLLBAR_MIN_THUMB.toPx() }
    val raw = scrollbarThumb(viewportPx, scrollState.maxValue, scrollState.value, minThumb, inset) ?: return null
    val trackPx = viewportPx - 2 * inset
    val top = when {
        scrollState.value <= 0 -> 0f
        scrollState.value >= scrollState.maxValue -> trackPx - raw.lengthPx
        else -> raw.topPx
    }
    return ThumbGeometry(
        topPx = inset + top,
        lengthPx = raw.lengthPx,
        viewportPx = viewportPx.toFloat(),
        maxScrollPx = raw.maxScrollPx,
    )
}

/**
 * 滚动条 thumb 的命中区（Compose root 坐标）。null = 不可拖。
 * 边缘防误触（[edgeTouchGuard]）据此豁免「按下点落在 thumb 上」的那根手指；只在该区非空时生效。
 */
object TranscriptScrollbarHitRegion {
    @Volatile
    var bounds: Rect? = null
}

/**
 * 会话区最右侧（贴屏幕边缘、不受 edgeTouchGuard 的 12dp 限制）的 overlay 滚动条。
 * dsh 是**占 5px 布局**的原生条；ADSH 有意改成不占布局的 overlay（用户口径），其余照 dsh：
 * 轨道透明、thumb 圆角 999、轨道两端内缩 [SCROLLBAR_TRACK_INSET]。长度与位置直接取滚动容器的
 * 真实可滚距离 —— 与 dsh 的 scrollHeight 语义一致，内容没变时长度不动。
 *
 * 可见性由调用方给（[visible]）：用户停止滚动满 [SCROLLBAR_HIDE_DELAY_MS] 后隐藏、再滚又出现，
 * 淡入淡出 [SCROLLBAR_FADE_MS]。不可滚时照旧不画。
 *
 * **滚动状态只在绘制阶段读**：value / maxValue 每帧都变，在组合期读会让这个 composable 跟着
 * 每一帧重组（长会话里纯属白烧）。绘制阶段读只重画这一条 5dp 的 RenderNode；命中区/拖拽几何
 * 由调用方在按下那一刻现算（见 [ChatTranscript]）。
 */
@Composable
internal fun TranscriptScrollbar(
    scrollState: ScrollState,
    dragging: Boolean = false,
    /** 底部 footer 高度（px）：轨道/命中区都避开它（footer 叠在会话区之上） */
    bottomInsetPx: Int = 0,
    /** false = 自动隐藏（渐隐中或已隐藏）；隐藏时 thumb 不吃手势、也不报告边缘防误触豁免区 */
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
    val hitWidthPx = with(density) { SCROLLBAR_HIT_WIDTH.toPx() }
    var contentBounds by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { contentBounds = it.boundsInRoot() }) {
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
                    val color = if (dragging) palette.scrollbarThumbHover else palette.scrollbarThumb
                    drawRoundRect(
                        color = color.copy(alpha = color.alpha * alpha),
                        topLeft = Offset(0f, trackInsetPx + top),
                        size = Size(size.width, raw.lengthPx),
                        cornerRadius = CornerRadius(size.width / 2f, size.width / 2f),
                    )
                }
            }
        }
        // 命中区（root 坐标）只给边缘防误触读：**不进组合**，滚动每帧写一个 volatile；
        // 以前是在组合期算几何再 SideEffect 写回，等于每帧一次重组 + 一次对象分配。
        LaunchedEffect(scrollState, visible, bottomInsetPx, trackInsetPx, hitWidthPx) {
            snapshotFlow {
                Triple(scrollState.value, scrollState.maxValue, contentBounds)
            }.collect { (value, maxScroll, content) ->
                TranscriptScrollbarHitRegion.bounds = if (!visible || content == null) {
                    null
                } else {
                    val viewportPx = (content.height - bottomInsetPx).toInt()
                    val raw = scrollbarThumb(viewportPx, maxScroll, value, minThumbPx, trackInsetPx)
                    if (raw == null) {
                        null
                    } else {
                        val trackPx = viewportPx - 2 * trackInsetPx
                        val top = when {
                            value <= 0 -> 0f
                            value >= maxScroll -> trackPx - raw.lengthPx
                            else -> raw.topPx
                        }
                        Rect(
                            left = content.right - hitWidthPx,
                            top = content.top + trackInsetPx + top,
                            right = content.right,
                            bottom = content.top + trackInsetPx + top + raw.lengthPx,
                        )
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose { TranscriptScrollbarHitRegion.bounds = null }
        }
    }
}
