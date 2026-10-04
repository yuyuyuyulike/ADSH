package com.adsh.app.ui

import java.util.Locale

/**
 * 文件大小文案（dsh 的 `fileSizeText`，逐条照搬）：
 *
 *  - < 1KB → `123B`；
 *  - < 1MB → 数值小于 10 保留一位小数、否则取整：`1.5KB` / `124KB`；
 *  - < 1GB → 同理：`1.1MB` / `124MB`；
 *  - 再大走 GB。
 *
 * 两个细节都跟 dsh 一致：**数字与单位之间没有空格**、小数用点（`Locale.US`，
 * 免得在某些区域设置下变成逗号）。附件卡片、文件预览的说明行都用它一份实现 ——
 * 以前预览那侧另有一个 `%.1f MB`（带空格），两处文案不一致。
 */
internal fun fileSizeText(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + "B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return withUnit(kb, "KB")
    val mb = kb / 1024.0
    if (mb < 1024.0) return withUnit(mb, "MB")
    return withUnit(mb / 1024.0, "GB")
}

/** dsh 的 `kb < 10 ? kb.toFixed(1) : Math.round(kb)` */
private fun withUnit(value: Double, unit: String): String =
    (if (value < 10.0) String.format(Locale.US, "%.1f", value) else Math.round(value).toString()) + unit
