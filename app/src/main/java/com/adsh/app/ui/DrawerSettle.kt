package com.adsh.app.ui

import kotlin.math.abs

// 抽屉手势松手后的判定（AppRoot 的 drawerDrag 用它）。纯函数，能在 JVM 单测里钉住。

/**
 * 甩动阈值（px/s）：比它快就按方向决定开合，否则按「过没过半」决定。
 * 必须区分方向：只要 |速度| < 250 就恒为开的话左滑永远关不上，只能用系统返回手势。
 */
internal const val DRAWER_FLING_PX_PER_S = 250f

/**
 * 松手之后抽屉是开还是关：`|速度| > [DRAWER_FLING_PX_PER_S]` → 按速度方向（右甩 = 开，左甩 = 关）；
 * 否则按 `progress > 0.5`（正好一半算关）。**速度还有一个去处**：开的那一下把它当弹簧初速度交给
 * AppRoot.settleDrawer（关的那一下不接 —— 负初速度会先冲到负进度再弹回来）；归位是 spring，不是定长匀速。
 */
internal fun drawerOpensAfterRelease(progress: Float, velocityPxPerS: Float): Boolean =
    if (abs(velocityPxPerS) > DRAWER_FLING_PX_PER_S) velocityPxPerS > 0f else progress > 0.5f
