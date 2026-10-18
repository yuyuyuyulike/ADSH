package com.adsh.app.ui.chat

/**
 * 帧级诊断（**只在 debug 包生效**：宿主把 `BuildConfig.DEBUG` 注入到 [ChatDebug.enabled]，release 里恒 false，
 * 常量折叠后删除）。打点四处（都只读状态、不改行为）：`row+ / row-`、`pin req`、`pin fix`、`tool+/-` 与
 * `sub+/-`。用法 `adb logcat -c && adb logcat -s ADSH_TRACE`，一边复现一边看。
 */
internal fun trace(area: String, message: String) {
    if (ChatDebug.enabled) android.util.Log.i("ADSH_TRACE", area + " " + message)
}
