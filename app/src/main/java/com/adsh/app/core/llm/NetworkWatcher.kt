package com.adsh.app.core.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 把系统的网络状态接到 [NetworkAvailability] 上（dsh 的 `setNetworkAvailable`）。
 *
 * 为什么必须有它：断网时按退避一路重试是**白打服务端**，而且界面上分不清「服务器挂了」与
 * 「手机没网」。接上之后 [com.adsh.app.core.llm.ConnectionRecovery.awaitNetwork] 会挂起，
 * 界面显示 dsh 的 `disconnected` 状态（静态的「断开，点此重试」），网络一回来立刻重来。
 *
 * 只注册**默认网络**的回调（`registerDefaultNetworkCallback`）：dsh 读的也是浏览器那一个
 * `navigator.onLine` 的语义（有没有网，而不是「某个特定网络在不在」）。
 * 注册在 `AdshApp.onCreate` 里，进程活着就一直有效。
 */
internal fun watchNetwork(context: Context) {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    // 先按当前状态定一次初值：注册回调之前就可能已经断网了
    NetworkAvailability.set(manager.hasDefaultNetwork())
    runCatching {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    NetworkAvailability.set(true)
                }

                override fun onLost(network: Network) {
                    // 默认网络丢了：再看一眼系统当前还有没有默认网络（切换 Wi-Fi ↔ 数据时
                    // onLost 会先来、onAvailable 后到，中间这一小段不能误报成断网）
                    NetworkAvailability.set(manager.hasDefaultNetwork())
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    NetworkAvailability.set(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            },
        )
    }
}

/** 系统现在有没有可用的默认网络（API 23+ 的 [ConnectivityManager.getActiveNetwork] 口径） */
private fun ConnectivityManager.hasDefaultNetwork(): Boolean {
    val network = activeNetwork ?: return false
    val caps = getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
