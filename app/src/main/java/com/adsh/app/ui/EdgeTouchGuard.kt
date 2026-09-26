package com.adsh.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 边缘防误触。
 *
 * 为什么需要它：手指/手掌「按住」屏幕两侧边缘时，触摸屏照样会报出真实的按下事件 ——
 * 于是什么都没打算点的握持姿势会点开一条会话、蹭亮一个按钮，或者让列表自己滚一下。
 * 系统层有 ROM 自己的边缘防误触（游戏模式里的那一条），但那是 ROM 侧的；应用侧没有
 * 公开 API 能拿到「这一下是不是误触」（`MotionEvent` 的 palm 分类是 @hide），
 * 能做到的就是把「贴边的那一条」当死区（Play 上那些 Block Edge Touch 类的应用同样如此）。
 *
 * 这里的规则只有一条：**手指「按下」的那一点落在左右边缘带里 ⇒ 这一根手指整根不参与手势**。
 *  - 按下、移动、抬起全部消费掉：点击不会成立，长按不会成立，滚动 / 拖拽也不会被这根手指带起来；
 *  - 只按「按下时的位置」判定，之后它滑到屏幕中间也照样屏蔽 —— 否则一次误触只要滑进来
 *    就变成了真的滚动；
 *  - 已经落在带外的那些手指完全不受影响（消费是按 change 走的，多指时只影响这一根）。
 *
 * 代价是贴边 12dp（默认档）之内**故意**从边缘起手的滑动也不再响应 —— 抽屉手势与列表滚动
 * 都要从带外起手。这与「边缘让给系统返回手势」本来就是同一条边界，所以默认取系统自己的
 * 边缘定义（12dp），要更宽可以在设置里调，或者直接关掉。
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
