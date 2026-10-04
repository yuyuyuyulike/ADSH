package com.adsh.app.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
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
private const val SESSION_TAG = "adsh-island"

/** 通知上那颗「停止」（也是媒体卡暂停键的那条路） */
internal const val ACTION_STOP = "com.adsh.app.island.STOP"

/**
 * 灵动岛的宿主：一个**前台服务**，两件事——
 *
 *  1. **保活**：agent 那一轮跑在 viewModelScope 里，App 一退到后台，进程就成了可回收的普通后台
 *     进程（长任务跑到一半被系统收走 = 用户回来只剩半截）。前台服务 + 常驻通知就是「这一轮跑完
 *     之前别收我」的声明，也是用户口径里那个「后台保活机制」。
 *  2. **当岛**：这条通知被做成**媒体通知**（MediaStyle + MediaSession），于是 **MIUI 自己的超级岛**
 *     把它画在状态栏那一条里 —— 状态图标给它腾位置、点击展开、动效全是系统的（和手机热点、
 *     波点音乐那颗岛同一条路；用户在第 179 轮点名要走这条，真机已验证 MIUI 会画）。
 *
 * 三个位置的数据源都是这条通知/会话：
 *  - 岛上**左半格那张小图** = [artFor]（黑圆角方 + 按格着色的鲸：白=等模型、琥珀=等你回答、绿=已结束）；
 *  - **展开后的标题 / 副标题** = 那一格文案 + 最新一行（[phaseLabel] 与 [islandLines]）；
 *  - **卡片上的暂停 / 通知上的停止** = [IslandBus.requestStop]（等于输入框右下角那个停止键）。
 *
 * 为什么不再自绘悬浮窗（第 179 轮真机量到的两条）：TYPE_APPLICATION_OVERLAY 在状态栏窗口**之下**
 * —— 那一条里的触摸归 SystemUI（点不动），画面也被系统的岛盖住（热点一开就整颗被盖）。自绘那条路
 * 已被用户否掉，代码一起删了（原 IslandWindow.kt）。
 */
class IslandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 岛此刻显示什么；null = 收工（通知也一起撤） */
    private val display = MutableStateFlow<IslandWork?>(null)

    private var session: MediaSession? = null
    private var watching = false
    private var dwelling: Job? = null

    /** 上一次写进通知栏的那一格：文案没变就不重复 notify（每帧一次 IPC 是真会掉帧的） */
    private var notified: String? = null

    /** 每一格一张小图（192px，五张封顶） */
    private val artCache = HashMap<IslandPhase, Bitmap>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        session = MediaSession(this, SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                /** 媒体卡上的暂停 / 停止 = 中止正在跑的这一轮（用户口径：岛上要有停止） */
                override fun onStop() = IslandBus.requestStop()
                override fun onPause() = IslandBus.requestStop()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须尽快 startForeground（系统给的窗口只有几秒）
        startForeground(NOTIFICATION_ID, notification(this, display.value, session?.sessionToken, null))
        if (intent?.action == ACTION_STOP) IslandBus.requestStop()
        if (!watching) {
            watching = true
            watch()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 会话先放掉：留着它系统会以为我们还在播（媒体卡 / 岛都不走）
        runCatching { session?.release() }
        session = null
        IslandController.markStopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun watch() {
        scope.launch { IslandBus.work.collect { work -> onWork(work) } }
    }

    /** 一轮的状态变了：还在跑就画它；跑完了先画「已结束」，停一会儿再收工 */
    private fun onWork(work: IslandWork?) {
        if (work != null) {
            dwelling?.cancel()
            dwelling = null
            apply(work)
            return
        }
        val current = display.value
        if (current == null) {
            stopSelf()
            return
        }
        apply(IslandWork(IslandPhase.DONE, current.lines))
        dwelling = scope.launch {
            delay(ISLAND_DONE_DWELL_MS)
            // 这 1.2 秒里用户又发了一轮：接着画，别把刚起来的那轮弄丢
            val latest = IslandBus.work.value
            if (latest == null) {
                display.value = null
                stopSelf()
            } else {
                onWork(latest)
            }
        }
    }

    /** 把这一帧写进会话元数据 + 通知（两者都是 MIUI 那颗岛的数据源） */
    private fun apply(work: IslandWork) {
        display.value = work
        val label = phaseLabel(work.phase)
        val art = artFor(work.phase)
        updateSession(label, work.lines.firstOrNull().orEmpty(), work.phase != IslandPhase.DONE, art)
        if (label == notified) return
        notified = label
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(this, work, session?.sessionToken, art))
    }

    /**
     * 会话元数据：标题 = 那一格（思考 / #代码 / …）、副标题 = 最新一行、封面 = [art]。
     *
     * 「正在播放」是给系统看的：媒体岛只在有活动会话时出现，一轮跑完就置成 STOPPED（岛随之收掉）。
     */
    private fun updateSession(title: String, text: String, active: Boolean, art: Bitmap?) {
        val media = session ?: return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "ADSH")
        if (art != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        }
        media.setMetadata(metadata.build())
        media.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (active) 1f else 0f,
                )
                .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
                .build(),
        )
    }

    /**
     * 岛左半格那张小图：黑圆角方 + 一只**按格着色**的鲸（白 = 等模型、琥珀 = 等你回答、绿 = 已结束）。
     *
     * MIUI 的媒体胶囊只画「封面 + 播放波形」两样，文字进不去；于是这一格就是我们能控制的那一半 ——
     * 颜色一变，用户在岛上一眼能看出 agent 是不是卡在等他。五种格各缓存一张。
     */
    private fun artFor(phase: IslandPhase): Bitmap? = artCache.getOrPut(phase) {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, paint)
        ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)?.let { whale ->
            whale.colorFilter = PorterDuffColorFilter(phaseTint(phase), PorterDuff.Mode.SRC_IN)
            val inset = size / 6
            whale.setBounds(inset, inset, size - inset, size - inset)
            whale.draw(canvas)
        }
        bitmap
    }
}

/**
 * 那条常驻通知：**媒体通知**（MediaStyle + 会话 token），点它回 App，上面挂一颗「停止」。
 *
 * 用平台的 Notification.Builder / Notification.MediaStyle（不是 NotificationCompat）：后者的
 * setMediaSession 要 androidx.media 的 MediaSessionCompat，而 MIUI 看的就是
 * android.template=android.app.Notification$MediaStyle 这一项（真机上从波点音乐的通知里读出来的，
 * 第 179 轮实测我们的通知同样被 MIUI 画成了状态栏中间那颗胶囊）。
 */
private fun notification(
    context: Context,
    work: IslandWork?,
    token: MediaSession.Token?,
    art: Bitmap?,
): Notification {
    val label = work?.let { phaseLabel(it.phase) } ?: "准备中"
    val text = work?.lines?.firstOrNull().orEmpty()
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stop = PendingIntent.getService(
        context,
        1,
        Intent(context, IslandService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val style = Notification.MediaStyle().setShowActionsInCompactView(0)
    if (token != null) style.setMediaSession(token)
    val builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dsh_whale)
        .setContentTitle(label)
        .setContentText(text)
        .setSubText("ADSH")
        .setContentIntent(open)
        .setStyle(style)
        .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_dsh_whale),
                "停止",
                stop,
            ).build(),
        )
        .setOngoing(true)
        // 安静：渠道本身无声无振动，这里再声明「只提醒一次」（平台 Builder 没有 setSilent）
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_TRANSPORT)
    if (art != null) builder.setLargeIcon(art)
    return builder.build()
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
