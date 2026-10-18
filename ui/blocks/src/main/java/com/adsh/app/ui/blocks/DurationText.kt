package com.adsh.app.ui.blocks

/** dsh 的 duration.seconds / compactMinutes：「1分20秒」「12秒」——工具行与跑动时长共用同一口径 */
fun formatRunDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    // dsh 的 duration.minutes =「{minutes}分{seconds:02}秒」：秒数补零（1分05秒）
    return if (minutes > 0) {
        minutes.toString() + "分" + (seconds % 60).toString().padStart(2, '0') + "秒"
    } else {
        seconds.toString() + "秒"
    }
}
