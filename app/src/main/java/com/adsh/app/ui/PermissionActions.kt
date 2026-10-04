package com.adsh.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 设置页「权限」卡里的三项（第 181 轮现状：通知 / 电池优化 / 所有文件访问）。
 *
 * 这三项凑在一起不是随手挑的：它们是「前台服务 + 灵动岛」这条保活链路在国产 ROM（HyperOS /
 * MIUI / EMUI / ColorOS）上能否活下来的一组开关 —— 缺一个，agent 那一轮就可能在后台被清掉。
 *
 * 撤掉的两项：**悬浮窗**（岛现在由系统自己画，不再需要 SYSTEM_ALERT_WINDOW）、
 * **自启动**（用户第 180 轮口径「去掉吧，不需要」；顺带 MIUI 也不给读取口，行里只能写「去设置」）。
 */
internal enum class AppPermission(val label: String) {

    /** 前台服务那条常驻通知能不能显示（岛就是它变来的） */
    NOTIFICATION("通知"),

    /** 电池优化白名单：不做这一项，前台服务在多数国产 ROM 上照样会被清 */
    BATTERY("电池优化"),

    /** 真实 POSIX 路径读手机文件（Android 11+ 的 MANAGE_EXTERNAL_STORAGE） */
    FILES("所有文件访问"),
}

/**
 * 这一项现在的状态：true = 已授权、false = 未授权、**null = 系统没有可读的开关**。
 * null 时卡片上写「去设置」而不是编一个状态出来 —— 界面不许撒谎（当前三项都读得到真值，
 * 这一档留给以后加进来的厂商私有开关）。
 */
internal fun permissionStatus(context: Context, permission: AppPermission): Boolean? = when (permission) {
    AppPermission.NOTIFICATION -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    AppPermission.BATTERY -> isIgnoringBatteryOptimizations(context)
    AppPermission.FILES -> filesAccessGranted(context)
}

/**
 * 「所有文件访问」的两种形态：
 *  - Android 11+（R）：MANAGE_EXTERNAL_STORAGE，读 [Environment.isExternalStorageManager]；
 *  - Android 10 及以下：共享存储还是普通运行时权限，读 WRITE_EXTERNAL_STORAGE。
 *
 * 与 [hasAllFilesAccess] 的区别是**这里不改工作区那条路径的语义** —— 那一条把「10 及以下」
 * 直接当成「随便读」（POSIX 路径本来就通），而卡片要对用户说实话：10 及以下得真有那条权限。
 */
internal fun filesAccessGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * 跳到这一项的**打开页**（用户口径：点一下就跳到 adsh 对应的权限页）。
 *
 * 每一档都带回落：厂商 ROM 上单应用页未必存在（ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 * 与 ACTION_MANAGE_OVERLAY_PERMISSION 都可能没有对应 Activity），起不来就退到总列表页、再退到
 * 「应用信息」页 —— 那里一定有所有权限的开关。用户点了没反应是最糟的结果，所以宁可多退两步。
 */
internal fun openPermissionSettings(context: Context, permission: AppPermission) {
    val packageUri = Uri.parse("package:" + context.packageName)
    when (permission) {
        AppPermission.NOTIFICATION -> {
            val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (!start(context, settings)) openAppDetails(context)
        }

        AppPermission.BATTERY -> {
            val requested = start(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            )
            if (!requested) {
                val listed = start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                if (!listed) openAppDetails(context)
            }
        }

        AppPermission.FILES -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openAllFilesAccessSettings(context)
            } else {
                openAppDetails(context)
            }
        }
    }
}

/** 应用信息页（最后一档回落：所有权限的开关都在那儿） */
private fun openAppDetails(context: Context) {
    start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
}

/** 起一个系统页面；起不来返回 false（由调用方决定退到哪一档） */
private fun start(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
