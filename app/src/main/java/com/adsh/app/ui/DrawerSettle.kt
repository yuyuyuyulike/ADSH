package com.adsh.app.ui

import kotlin.math.abs

// 抽屉手势松手后的判定（AppRoot 的 drawerDrag 用它）。纯函数，能在 JVM 单测里钉住。

/**
 * 甩动阈值（px/s）：比它快就按方向决定开合，否则按「过没过半」决定。
 *
 * 250 是第 96 轮定的（更早的写法是「只要 |速度| < 250 就恒为开」，于是左滑永远关不上，
 * 只能用系统返回手势）。
 */
internal const val DRAWER_FLING_PX_PER_S = 250f

/**
 * 松手之后抽屉是开还是关：
 *  - `|速度| > [DRAWER_FLING_PX_PER_S]` → 按速度方向（往右甩 = 开，往左甩 = 关）；
 *  - 否则按 `progress > 0.5`（正好一半算关）。
 *
 * **这里只决定方向，速度不参与动画**（第 82–84 轮试出来的）。试过两版都被用户否掉：
 * 第 82 轮把手势速度当弹簧初速度（想要「甩出去自己停下来」的物理感）→「末尾抖动、很花哨」；
 * 第 83 轮换成 `FastOutSlowInEasing` 的定长缓动 →「和一开始的怪怪感一样」。
 * 最终口径是**不要缓动**：AppRoot 的 settleDrawer 就是 `tween(SLIDE_MS, LinearEasing)` ——
 * 定长、匀速、与手势速度无关，最笨、也最不会读成「怪」的一种。
 */
internal fun drawerOpensAfterRelease(progress: Float, velocityPxPerS: Float): Boolean =
    if (abs(velocityPxPerS) > DRAWER_FLING_PX_PER_S) velocityPxPerS > 0f else progress > 0.5f
