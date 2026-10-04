package com.adsh.app

import android.app.Application
import com.adsh.app.core.llm.watchNetwork
import com.adsh.app.runtime.phone.PhoneChannel
import com.whl.quickjs.android.QuickJSLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AdshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // QuickJS 原生库在每个进程都要初始化：PTC 程序跑在 :ptc 进程里（见 core/ptc/PtcWorkerService）
        QuickJSLoader.init()
        // 以下两件只对**主进程**有意义。:ptc 只是 PTC 程序的沙箱进程，跟着做只会多出一份副作用
        // （多一个 phone 轮询线程、多一份网络订阅）—— 第 183 轮引入 :ptc 时一并收口。
        if (!isMainProcess()) return
        // 网络状态 → 掉线重连策略（dsh 的 setNetworkAvailable）：断网时暂停自动重试、
        // 网络回来立刻重来，界面显示「断开」而不是「正在重连」。
        watchNetwork(this)
        // phone 通道（T1）：把 phone 脚本放进前缀的 bin，并起一个轮询线程处理请求。
        // 为什么由应用自己做：bash 的 app UID 下 am/input/screencap 全被系统拒绝（见 PhoneChannel 的 KDoc）。
        PhoneChannel.ensureScript(this)
        PhoneChannel.start(this, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /**
     * 当前进程是不是主进程。
     *
     * 为什么不用 `Application.getProcessName()`：那是 **api 28** 才有的，而 minSdk 是 26；
     * 读 `/proc/self/cmdline`（首段就是进程名）在所有版本上都成立，失败就按「是主进程」处理
     * —— 宁可多跑一次初始化，也不要让主进程缺功能。
     */
    private fun isMainProcess(): Boolean = runCatching {
        java.io.File("/proc/self/cmdline").readBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .decodeToString()
    }.getOrDefault(packageName) == packageName
}
