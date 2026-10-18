package com.adsh.app.ui.blocks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * dsh 的 TextShimmer（ui-primitives/TextShimmer.tsx + TextShimmer.module.css）。
 *
 * 运行中的一行**不是**整行掠过一道底色：它是文字本体上掠过一条**颜色带**（其余位置颜色不变）。
 * 几何逐项照 dsh 的 CSS：
 *  - 周期 1.5s，延迟 0.3s（行出现后先静默 0.3s）；
 *  - 带子在周期的前 2/3（1s）里从内容左外侧扫到右外侧，剩下 0.5s 空档；
 *  - 遮罩形状 = 内容宽度的 20%（40% 渐入 / 60% 渐出）：dsh 的 mask 是
 *    linear-gradient(105deg, transparent 0%, black 40% 60%, transparent 100%)；
 *  - 颜色 = dsh 的 --dsw-alias-label-shimmer：浅色 neutral-1000 30% 透明、深色 neutral-00 45%，
 *    也就是**给文字压一层更深的同色带**，而不是提亮到快看不见。
 *
 * 与 dsh 的差别：dsh 用两条反向平移的副本 + mask 对齐字形；这里在离屏层里用同一条梯形渐变
 * 以 [BlendMode.SrcAtop] 压在文字上 —— 视觉等价（带子只在文字像素上出现），且只重绘这一块离屏层。
 *
 * @param active 行是否在运行（preparing / running）；false 时不组合任何动画
 * @param tint 带子的颜色，null = 按 dsh 的 label-shimmer 令牌取（深浅两套）
 */
@Composable
fun DshTextShimmer(
    active: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalDshPalette.current
    val resolved = tint ?: if (palette.dark) {
        Color.White.copy(alpha = SHIMMER_ALPHA_DARK)
    } else {
        Color.Black.copy(alpha = SHIMMER_ALPHA_LIGHT)
    }
    // 只有运行中的行才需要离屏层；不运行时保持普通绘制（完成的行不该常驻一个图层）
    val layer = if (active) {
        Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    } else {
        Modifier
    }
    Box(modifier.then(layer)) {
        content()
        if (active) ShimmerSweep(resolved)
    }
}

/** 一条正在跑的行上的流光带（铺满父容器、只改文字像素） */
@Composable
private fun BoxScope.ShimmerSweep(tint: Color) {
    val transition = rememberInfiniteTransition(label = "textShimmer")
    // 只当帧脉冲用：值不参与相位计算（相位是墙钟），但每帧把它读一次让这枚 Canvas 失效
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(TEXT_SHIMMER_PERIOD_MS.toInt(), easing = LinearEasing),
        ),
        label = "textShimmerPulse",
    )
    // 行挂上来的时刻 = dsh 元素的动画起点（CSS 动画从元素创建时开始计时）
    val startAt = remember { System.currentTimeMillis() }
    Canvas(Modifier.matchParentSize()) {
        pulse.value
        val phase = textShimmerPhaseAt(System.currentTimeMillis() - startAt)
        // 延迟段：dsh 的 animation-delay: .3s —— 带子还没出现
        if (phase < 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val travel = textShimmerSweepProgress(phase)
        // mask 元素宽 = 内容宽，从左外侧 (-w) 平移到右外侧 (+w)；梯形带子在元素内 40%..60%
        val maskLeft = -size.width + 2f * size.width * travel
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    TEXT_SHIMMER_BAND_START to tint,
                    TEXT_SHIMMER_BAND_END to tint,
                    1f to Color.Transparent,
                ),
                startX = maskLeft,
                endX = maskLeft + size.width,
                tileMode = TileMode.Clamp,
            ),
            size = size,
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/**
 * 流光在 [elapsedMillis] 时的相位（0..1 = 一个 1.5s 周期内的位置），-1 = 还在 0.3s 延迟里。
 * 纯函数，单测直接打（见 ui:blocks 的 TextShimmerTest）。
 */
internal fun textShimmerPhaseAt(elapsedMillis: Long): Float {
    val afterDelay = elapsedMillis - TEXT_SHIMMER_DELAY_MS
    if (afterDelay < 0L) return -1f
    return (afterDelay % TEXT_SHIMMER_PERIOD_MS).toFloat() / TEXT_SHIMMER_PERIOD_MS
}

/** 相位 -> 遮罩元素的平移进度（0 = 左外侧，1 = 右外侧）；前 2/3 走完，之后停住。 */
internal fun textShimmerSweepProgress(phase: Float): Float =
    (phase / TEXT_SHIMMER_SWEEP_FRACTION).coerceIn(0f, 1f)

/** dsh 的 animation-duration: 1.5s */
internal const val TEXT_SHIMMER_PERIOD_MS = 1500L

/** dsh 的 animation-delay: .3s */
internal const val TEXT_SHIMMER_DELAY_MS = 300L

/** dsh 的关键帧在 66.6667% 走完整个平移，剩下 1/3 周期空档 */
internal const val TEXT_SHIMMER_SWEEP_FRACTION = 2f / 3f

/** 带子在 mask 元素里的 40%..60%（dsh 的 black 40% 60%） */
internal const val TEXT_SHIMMER_BAND_START = 0.4f
internal const val TEXT_SHIMMER_BAND_END = 0.6f

/** dsh 的 --dsw-alias-label-shimmer（浅色 neutral-1000 30% 透明 / 深色 neutral-00 45%） */
internal const val SHIMMER_ALPHA_LIGHT = 0.30f
internal const val SHIMMER_ALPHA_DARK = 0.45f
