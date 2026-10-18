package com.adsh.app.seam.model

/**
 * 外观偏好（dsh 的 THEME_PREFERENCES：light / dark / system）的持久化取值。
 *
 * 放在 `seam.model` 是因为 `core.data`（读写 prefs）与 `ui.blocks`（按偏好选亮/暗配色）都要用它，
 * 而界面基础层不许依赖应用代码 —— 这三个值是两边唯一共享的契约。
 */
object ThemeMode {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"
}
