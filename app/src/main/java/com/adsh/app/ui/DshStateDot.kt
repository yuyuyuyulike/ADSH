package com.adsh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * dsh 的 StateDot（两个读者：bash 工具行横幅、掉线重连条的「连接中」）：
 *  - `"ongoing"`：14px 的旋转弧 —— 25% 透明度的整圈轨道 + 一段 72° 的弧，1.5s 线性循环；
 *  - `"done"` / `"error"`：10px 槽里一枚 6px 的圆核（绿 / 红）。
 *
 * 原来它是 `TerminalBlock.kt` 里的私有件；重连条（dsh 的 ConnectionIndicator 用的就是这枚
 * StateDot）需要同一个东西，所以提出来共用 —— 这是本轮熵减的一部分：同一枚 dsh 元件只留一份实现。
 *
 * 注意：`JobsPanel` 里那枚 `JobStateDot` **不是**它（270° 弧、尺寸/配色都按任务状态取），
 * 视觉上本来就不一样，别顺手合并。
 */
@Composable
internal fun DshStateDot(state: String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    if (state == "ongoing") {
        val transition = rememberInfiniteTransition(label = "stateDot")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "stateDotSpin",
        )
        Canvas(modifier.size(14.dp)) {
            val stroke = 1.2.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = palette.labelTertiary.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            rotate(angle) {
                drawArc(
                    color = palette.labelTertiary,
                    startAngle = 0f,
                    // dsh 的 stroke-dasharray 12/周长≈59.7 ≈ 72°
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    } else {
        val color = if (state == "error") palette.errorLabel else palette.success
        Box(modifier.size(10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
    }
}
