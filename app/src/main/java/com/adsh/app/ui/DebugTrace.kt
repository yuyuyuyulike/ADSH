package com.adsh.app.ui

/**
 * 帧级诊断（**只在 debug 包生效**，release 里被 R8 折掉：`BuildConfig.DEBUG` 是编译期常量，
 * 整段 if 会被常量折叠后删除，所以生产包里既没有日志也没有这个分支）。
 *
 * 为什么留一个这样的开关（第 105 轮加）：像「工具行出现时闪一下」「贴底漏半行」这类问题，
 * 现象只存在于真机的某几帧里，而用户的口径又是主观的（「闪」可能指行进出组合、也可能指视口
 * 抖动、也可能指某一行的内容变了）。有了它就能把**事件时间线**与**帧级状态**对齐着看：
 *
 * ```
 * adb logcat -c && adb logcat -s ADSH_TRACE      # 一边复现一边看
 * ```
 *
 * 打点的地方就四处（都只读状态、不改行为）：
 *  - `row+ / row-`：LazyColumn 里某一**行**进入 / 离开组合（key 见 `transcriptKey`）——
 *    「同一行闪一下」在这里表现为 `row-` 紧跟 `row+`；
 *  - `pin req`：`SideEffect` 发出的贴底请求（带它认得的三个信号与当时的 gap）；
 *  - `pin fix`：兜底补钉按真实差值 `scrollBy` 的那一下（带 gap 与首个可见行）；
 *  - `tool+ / tool- / sub+ / sub-`：工具与子调用的开始 / 结束（ChatViewModel 那边打的）。
 */
internal fun trace(area: String, message: String) {
    if (com.adsh.app.BuildConfig.DEBUG) android.util.Log.i("ADSH_TRACE", area + " " + message)
}
