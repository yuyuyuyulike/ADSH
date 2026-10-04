package com.adsh.app.island

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.adsh.app.MainActivity
import com.adsh.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "adsh-island"
private const val NOTIFICATION_ID = 1717

/** 「已结束」停留多久（ms）：够看清，又不至于赖着不走 */
private const val DONE_HOLD_MS = 1200L

/** 退场动画（缩放 + 淡出）的时长 + 余量（ms）：服务等它演完再收工，别把窗口抽掉 */
private const val EXIT_MS = 320L

/**
 * 灵动岛的宿主：一个**前台服务**，两件事——
 *
 *  1. **保活**：agent 那一轮跑在 viewModelScope 里，App 一退到后台，进程就成了可回收的普通后台
 *     进程（长任务跑到一半被系统收走 = 用户回来只剩半截）。前台服务 + 常驻通知就是「这一轮跑完
 *     之前别收我」的声明，也是用户口径里那个「后台保活机制」。
 *  2. **画岛**：[IslandWindow] 挂一块悬浮窗，只在 App **不在**前台时显示（用户口径）。用户回到
 *     App，窗口就摘掉 —— 服务本身继续跑，直到这一轮结束、再挂 [DONE_HOLD_MS] 显示「已结束」、
 *     演完退场动画（[EXIT_MS]）之后自己 stopSelf()。
 *
 * 与 dsh 的对应关系：dsh 是网页端，标签页在后台时浏览器照样跑 JS，不存在「进程被收走」这一步；
 * 移植到 Android 之后这一层是**必须新加**的，与 ChatViewModel 里那一轮的生命周期一一对应。
 */
class IslandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 岛此刻画什么；null = 彻底收工（窗口也摘掉了） */
    private val display = MutableStateFlow<IslandWork?>(null)

    /** 岛该不该出现（false = 正在演退场，窗口还留着；见 [IslandWindow] 的 AnimatedVisibility） */
    private val visible = MutableStateFlow(false)

    private lateinit var window: IslandWindow
    private var watching = false
    private var dwelling: Job? = null

    /** 上一次写进通知栏的那一格：文案没变就不重复 notify（每帧一次 IPC 是真会掉帧的） */
    private var notified: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        window = IslandWindow(this, scope, display, visible)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须尽快 startForeground（系统给的窗口只有几秒）
        startForeground(NOTIFICATION_ID, notification(this, display.value))
        if (!watching) {
            watching = true
            watch()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        window.detach()
        IslandController.markStopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun watch() {
        scope.launch { IslandBus.work.collect { work -> onWork(work) } }
        scope.launch { AppVisibility.isForeground.collect { syncWindow() } }
    }

    /** 一轮的状态变了：还在跑就画它；跑完了先画「已结束」，再演退场、收工 */
    private fun onWork(work: IslandWork?) {
        if (work != null) {
            dwelling?.cancel()
            dwelling = null
            display.value = work
            visible.value = true
            notifyIfChanged(work)
            syncWindow()
            return
        }
        val current = display.value
        if (current == null) {
            stopSelf()
            return
        }
        val done = IslandWork(IslandPhase.DONE, current.lines)
        display.value = done
        notifyIfChanged(done)
        dwelling = scope.launch {
            delay(DONE_HOLD_MS)
            visible.value = false          // 退场动画开始（窗口先留着，动画演完才摘）
            delay(EXIT_MS)
            // 这 1.5 秒里用户又发了一轮：接着画，别把刚起来的那轮弄丢
            val latest = IslandBus.work.value
            if (latest == null) {
                display.value = null
                window.detach()
                stopSelf()
            } else {
                onWork(latest)
            }
        }
    }

    /** 窗口的挂与摘：有东西要画、且 App 不在前台 → 挂上；否则摘掉 */
    private fun syncWindow() {
        if (display.value != null && !AppVisibility.isForeground.value) window.attach() else window.detach()
    }

    private fun notifyIfChanged(work: IslandWork?) {
        val label = work?.let { phaseLabel(it.phase) } ?: return
        if (label == notified) return
        notified = label
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this, work))
    }
}

/** 通知（前台服务必须有一条）：点它回 App；文案跟着那一格走，通知栏里也能看出 agent 在干什么 */
private fun notification(context: Context, work: IslandWork?): android.app.Notification {
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dsh_whale)
        .setContentTitle("ADSH 正在后台工作")
        .setContentText(work?.let { phaseLabel(it.phase) } ?: "准备中")
        .setContentIntent(open)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}

/** 通知渠道：低优先级、无声、无角标 —— 它是「保活声明」，不是要打扰用户的消息 */
private fun ensureChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
            description = "agent 在后台工作时显示灵动岛"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        },
    )
}
