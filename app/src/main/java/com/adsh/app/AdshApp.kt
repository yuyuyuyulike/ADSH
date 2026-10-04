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
        // QuickJS 原生库只需初始化一次
        QuickJSLoader.init()
        // 网络状态 → 掉线重连策略（dsh 的 setNetworkAvailable）：断网时暂停自动重试、
        // 网络回来立刻重来，界面显示「断开」而不是「正在重连」。
        watchNetwork(this)
        // phone 通道（T1）：把 phone 脚本放进前缀的 bin，并起一个轮询线程处理请求。
        // 为什么由应用自己做：bash 的 app UID 下 am/input/screencap 全被系统拒绝（见 PhoneChannel 的 KDoc）。
        PhoneChannel.ensureScript(this)
        PhoneChannel.start(this, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
