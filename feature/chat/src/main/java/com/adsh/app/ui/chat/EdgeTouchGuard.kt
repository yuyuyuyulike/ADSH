package com.adsh.app.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 边缘防误触。应用侧拿不到「这一下是不是误触」（`MotionEvent` 的 palm 分类是 @hide），能做的就是把
 * 「贴边的那一条」当死区：**手指「按下」的那一点落在左右边缘带里 ⇒ 这一根手指整根不参与手势**（按下 /
 * 移动 / 抬起全部消费掉，带外的手指不受影响）。代价是贴边 12dp 内**故意**从边缘起手的滑动也不响应。
 */

/**
 * 这一「按下」的点是不是落在边缘带里。
 *
 * @param x 按下点在屏幕坐标系里的 x（px）
 * @param width 屏幕（本层）宽度（px）
 * @param bandPx 单边带宽（px）；<= 0 表示功能关闭
 *
 * 单独拎成纯函数是为了能单测：Compose 的手势本身跑不进 JVM 单测。
 */
internal fun isEdgeTouch(x: Float, width: Float, bandPx: Float): Boolean {
    if (bandPx <= 0f || width <= 0f) return false
    // 分屏 / 小窗下屏幕可能比两条带还窄：把带上限压到 1/3 宽，
    // 否则中间一点可点的区域都不剩（整个人被自己的防误触锁死）
    val effective = minOf(bandPx, width / 3f)
    return x <= effective || x >= width - effective
}

/**
 * 边缘防误触：把落在左右边缘带里的手指整根摘掉（详见文件头的说明）。
 *
 * 挂在**根布局**上（[AppRoot]），并且要在抽屉手势之前 ——同一元素上多个 pointerInput
 * 按链序派发，Initial 阶段最外层先跑，先摘掉的手指后面谁都拿不到。
 *
 * [band] <= 0（关闭）时返回原 Modifier：连指针节点都不挂。
 */
fun Modifier.edgeTouchGuard(band: Dp): Modifier {
    if (band <= 0.dp) return this
    return pointerInput(band) {
        val bandPx = band.toPx()
        awaitPointerEventScope {
            // 已经被摘掉的手指：整根（按下 / 移动 / 抬起）都吃掉
            val rejected = HashSet<PointerId>()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                for (change in event.changes) {
                    if (change.id !in rejected && change.pressed && !change.previousPressed) {
                        if (isEdgeTouch(change.position.x, size.width.toFloat(), bandPx)) {
                            rejected += change.id
                        }
                    }
                    if (change.id in rejected) {
                        change.consume()
                        if (!change.pressed) rejected -= change.id
                    }
                }
            }
        }
    }
}
