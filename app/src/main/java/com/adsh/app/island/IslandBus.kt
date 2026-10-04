package com.adsh.app.island

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 灵动岛的两条进程内通道：界面状态推出来的**一帧**（见 [islandWorkOf]）与「用户按了岛上的停止」。
 *
 * 为什么走进程级单例：画岛的是 [IslandService]（前台服务 + 媒体通知），而状态在 ChatViewModel
 * 手里 —— 两者是同一个进程里的两半，中间只有这两条流。进程被杀就一起没了，所以不需要落盘。
 */
internal object IslandBus {

    private val _work = MutableStateFlow<IslandWork?>(null)

    /** 岛此刻该显示什么；null = 没有正在跑的一轮（服务进入「已结束」收尾期） */
    val work: StateFlow<IslandWork?> = _work.asStateFlow()

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 岛上的「停止」被按了（媒体卡的暂停键 / 通知上的停止）—— 由 ChatViewModel 收走并中止这一轮 */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    fun publish(work: IslandWork?) {
        _work.value = work
    }

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}

/**
 * 谁把前台服务拉起来：界面状态一变就推给 [IslandBus]（服务若在跑会立刻看到），
 * 并且**只在「从没有在跑」变成「有在跑」那一下**startForegroundService。
 *
 * 为什么必须由前台的服务来做：ChatViewModel 里那一轮跑在 viewModelScope，App 一退到后台，
 * 进程就成了「可回收」的普通后台进程 —— 一轮长任务跑到一半被系统收走，用户回来只看到半截。
 * 前台服务 + 那条常驻通知就是「这一轮跑完之前别收我」的那个声明；而那条通知同时被 MIUI 画成
 * 超级岛（见 [IslandService]）。
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
