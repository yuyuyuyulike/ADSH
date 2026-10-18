package com.adsh.app.ui.blocks

import android.content.Context
import android.content.Intent

/**
 * 是否已拿到「所有文件访问」（Android 11+ 的 MANAGE_EXTERNAL_STORAGE）。
 *
 * 工作区绑定走的是 SAF（选目录）+ **真实路径**（File API）两条腿：SAF 只保证「选得中」，
 * 真正列目录 / 读文件 / 写导出文件用的都是 POSIX 路径。没有这个权限时 listFiles() 直接返回
 * 空数组（不报错），表现就是「明明手机里有文件，工作区文件里什么都没有」、
 * 导入附件失败、导出 ZIP 失败。
 *
 * 契约面：:app 的启动检查 / 权限页与 :ui:blocks 的文件树都读它。
 */
fun hasAllFilesAccess(): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

/** 跳到系统设置的「所有文件访问」授权页；厂商 ROM 没有单应用页时回落到总列表页 */
fun openAllFilesAccessSettings(context: Context) {
    val app = Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        android.net.Uri.parse("package:" + context.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(app) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
