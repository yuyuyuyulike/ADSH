package com.adsh.app.ui.chat

/**
 * 对话界面的诊断开关（帧级 trace 等）：值由宿主（:app）在启动时注入 `BuildConfig.DEBUG`。
 *
 * 库模块不许读自己的 `BuildConfig`：`side` 变体对库的 matchingFallbacks 是 debug，库自己的
 * `DEBUG` 恒为 true，会改变日志行为（见 `docs/MODULES.md` §三.3）。宿主只注一次，UI 只读。
 */
object ChatDebug {

    /** 宿主注入：debug 包 true、release 包 false。改动只发生在启动时。 */
    @Volatile
    var enabled: Boolean = false
}
