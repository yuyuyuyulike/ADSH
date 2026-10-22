package com.adsh.app

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import com.adsh.app.runtime.phone.PhoneChannel
import com.whl.quickjs.android.QuickJSLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalFoundationApi::class)
class AdshApp : Application() {

    /** 组合根：设置 / 数据库 / LLM / 运行环境 / AgentLoop 的唯一创建点（见 [AppContainer]） */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        // Compose 1.12 起 Android 默认走新的 context-menu 路径；它**不经过** LocalTextToolbar，
        // 于是 ui:blocks 的「没有选中内容就不弹」过滤器整体失效 —— 长按正文松手后闪一下只有
        // 「全选」的浮动栏。官方把这条 flag 定位为「新路径回归时的开关」，关掉回到旧 TextToolbar
        // 路径（过滤器在那边生效）。必须在 Compose 库代码被加载前设置，所以放在 super.onCreate() 之前。
        // 与 ui:blocks 的 SelectionToolbar.kt 是一对，改一处要同时改另一处。
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        super.onCreate()
        // QuickJS 原生库在每个进程都要初始化：PTC 程序跑在 :ptc 进程里（见 platform/ptc/PtcWorkerService）
        QuickJSLoader.init()
        // 以下两件只对**主进程**有意义。:ptc 只是 PTC 程序的沙箱进程，跟着做只会多出一份副作用
        // （多一个 phone 轮询线程、多一份网络订阅）。
        if (!isMainProcess()) return
        // 诊断开关交给 UI 层只读使用：库模块不许读自己的 BuildConfig（side 变体里它是 true）
        com.adsh.app.ui.chat.ChatDebug.enabled = BuildConfig.DEBUG
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
