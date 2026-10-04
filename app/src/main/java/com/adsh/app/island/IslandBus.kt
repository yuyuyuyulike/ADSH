package com.adsh.app.island

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 灵动岛的唯一输入：界面那一份状态推出来的**一帧**（见 [islandWorkOf]）。
 *
 * 为什么走进程级单例：岛的窗口挂在前台服务里（[IslandService]），而状态在
 * [com.adsh.app.ui.ChatViewModel] 手里 —— 两者是同一个进程里的两半，中间只有这一条流。
 * 进程被杀就一起没了，所以不需要落盘。
 */
internal object IslandBus {

    private val _work = MutableStateFlow<IslandWork?>(null)

    /** 岛此刻该画什么；null = 没有正在跑的一轮（服务进入「已结束」收尾期） */
    val work: StateFlow<IslandWork?> = _work.asStateFlow()

    fun publish(work: IslandWork?) {
        _work.value = work
    }
}

/**
 * App 是不是在前台（用户口径：**只有退到后台才显示灵动岛**）。
 *
 * 用 Activity 生命周期计数而不是 ProcessLifecycleOwner：少一个依赖，而且「有几个 Activity 在
 * 前台」本来就够用（本项目只有 MainActivity 一个）。计数而不是布尔，是为了旋转/多窗口那种
 * 「新的先 start、旧的后 stop」的顺序不会把前台误判成后台（服务那边还有 [IslandService] 的
 * 迟到宽限兜着）。
 */
internal object AppVisibility {

    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var started = 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                _isForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                _isForeground.value = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * 谁把前台服务拉起来：界面状态一变就推给 [IslandBus]（服务若在跑会立刻看到），
 * 并且**只在「从没有在跑」变成「有在跑」那一下**startForegroundService。
 *
 * 为什么必须由前台的服务来做：[ChatViewModel] 里那一轮跑在 viewModelScope，App 一退到后台，
 * 进程就成了「可回收」的普通后台进程 —— 一轮长任务跑到一半被系统收走，用户回来只看到半截。
 * 前台服务 + 那条常驻通知就是「这一轮跑完之前别收我」的那个声明；岛是它的脸。
 */
internal object IslandController {

    private var running = false

    fun sync(context: Context, work: IslandWork?) {
        IslandBus.publish(work)
        if (work == null || running) return
        running = true
        val started = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java))
        }.isSuccess
        // 起不来（系统拒绝 / 没权限）就把旗子放回去，下一次状态变化再试
        if (!started) running = false
    }

    /** 服务自己收工时回调（见 IslandService.onDestroy） */
    fun markStopped() {
        running = false
    }
}
