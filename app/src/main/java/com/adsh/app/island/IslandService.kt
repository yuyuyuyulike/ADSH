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

/** 通知重发的最小间隔（约 5 次/秒）：正文流式期间既跟得上，又不会每帧一次 IPC */
private const val NOTIFY_MIN_INTERVAL_MS = 200L
private const val NOTIFICATION_ID = 1717
private const val SESSION_TAG = "adsh-island"

/** 通知上那颗「停止」（也是岛上大卡里那颗按钮） */
internal const val ACTION_STOP = "com.adsh.app.island.STOP"

/**
 * 灵动岛的宿主：一个**前台服务**，两件事——
 *
 *  1. **保活**：agent 那一轮跑在 viewModelScope 里，App 一退到后台，进程就成了可回收的普通后台
 *     进程（长任务跑到一半被系统收走 = 用户回来只剩半截）。前台服务 + 常驻通知就是「这一轮跑完
 *     之前别收我」的声明。
 *  2. **当岛**：这条通知做成**媒体通知**（MediaStyle + MediaSession），MIUI 就把它画成状态栏那
 *     一条里那颗胶囊（和手机热点、波点音乐同一条路）—— 真机已验：我们的鲸 + 播放波形画在状态栏
 *     中间，状态图标自动让位，点击展开与动效都归系统。
 *
 * 走过的两条弯路（都写在这儿，别再走）：
 *  - **自绘悬浮窗**：TYPE_APPLICATION_OVERLAY 在状态栏窗口之下（触摸与画面都是），点不动也压不住；
 *  - **MIUI 焦点通知**（miui.focus.param，从系统自己的热点通知 dump 出来的那套）：真机上 MIUI 不画
 *    （多半要业务白名单），用户实测「没有灵动岛了」→ 撤回媒体路线。
 *
 * 下一步想走的是 **Android 16 的实况窗（Live Updates）**：API 名字已经在真机上核对完了 ——
 * 本机 SDK 是 `platforms/android-37.0`（不是 android-37），javap 确认
 * `Notification$ProgressStyle`（setProgress / setProgressPoints / setProgressSegments /
 * setProgressIndeterminate / setStyledByProgress）、`Notification$Builder
 * .setRequestPromotedOngoing` / `.setShortCriticalText`、
 * `NotificationManager.canPostPromotedNotifications` 都在。要上就得先做设备实验：
 * API 36+ 且 `canPostPromotedNotifications()` 为真时改走实况窗（可能多出一条通知、
 * 也可能 MIUI 根本不 promote），拿不准就还是这条媒体路线兜底。
 */
class IslandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 岛此刻显示什么；null = 收工（通知也一起撤） */
    private val display = MutableStateFlow<IslandWork?>(null)

    private var session: MediaSession? = null
    private var watching = false
    private var dwelling: Job? = null

    /**
     * 上一次写进通知栏的那一份内容（格子 + 最新一行）与它的时刻。
     *
     * 键里**带上那一行正文**：通知栏那一条（以及按通知取文案的 ROM）显示的就是它 ——
     * 只按格子去重的话，一轮里标题从「思考」变成「输出中」之后整段正文都不再刷新，
     * 用户看到的就停在那一刻。真机上 MIUI 的媒体大卡读的是 **MediaSession 元数据**（那份每帧都
     * 更新，见 [updateSession]），这一条是给通知栏本身与别的 ROM 兜底的。
     * 流式正文每帧都在变，所以还加了一个最小间隔（[NOTIFY_MIN_INTERVAL_MS]，约 5 次/秒）：
     * 既不卡也不糊。
     */
    private var notifiedKey: String? = null
    private var notifiedAt = 0L
    private var notifyJob: Job? = null

    /** 每一格一张小图（256px，五张封顶） */
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
            // 点岛上那一格（以及媒体卡上的封面）= 回 App 接着看。不给的话系统点开的是空的媒体页
            setSessionActivity(openAppIntent(this@IslandService))
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
        val line = work.lines.firstOrNull().orEmpty()
        updateSession(label, line, work.phase != IslandPhase.DONE, artFor(work.phase))
        publish(work)
    }

    /**
     * 通知的节流重发（见 [notifiedKey]）：内容变了才发，最短间隔 [NOTIFY_MIN_INTERVAL_MS]。
     *
     * 排在后面的那一发**发的是当时最新的一帧**（不是排队时那一帧）：一轮结束时最后那几行
     * 一定落在屏幕上，而中间那些帧该丢就丢 —— 通知栏不是逐帧播放器。
     */
    private fun publish(work: IslandWork) {
        val key = phaseLabel(work.phase) + "\u0000" + work.lines.firstOrNull().orEmpty()
        if (key == notifiedKey || notifyJob != null) return
        val wait = NOTIFY_MIN_INTERVAL_MS - (System.currentTimeMillis() - notifiedAt)
        if (wait <= 0L) {
            notifyNow(work, key)
            return
        }
        notifyJob = scope.launch {
            delay(wait)
            notifyJob = null
            val latest = display.value ?: return@launch
            notifyNow(latest, phaseLabel(latest.phase) + "\u0000" + latest.lines.firstOrNull().orEmpty())
        }
    }

    private fun notifyNow(work: IslandWork, key: String) {
        notifiedKey = key
        notifiedAt = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(this, work, session?.sessionToken, artFor(work.phase)))
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
            // 系统卡（以及实况窗那一类）优先读 DISPLAY_* 这一对：不给的话有的 ROM 会去翻
            // ALBUM / ALBUM_ARTIST，展开后副标题就成了「ADSH」
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, text)
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
     * 颜色一变，用户在岛上一眼能看出 agent 是不是卡在等他。
     */
    private fun artFor(phase: IslandPhase): Bitmap? = artCache.getOrPut(phase) {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val tint = phaseTint(phase)
        val full = size.toFloat()
        val radius = full * 0.24f
        // 「需要你」与「已结束」两格用同色系的暗底（不是纯黑）：状态栏里那一下就"亮"起来，
        // 剩下三格（等模型 / 在跑）保持纯暗底 + 白环 —— 一眼分得出「它在干活」和「它在等我」
        val emphasized = phase == IslandPhase.ASK || phase == IslandPhase.DONE
        paint.style = Paint.Style.FILL
        paint.color = if (emphasized) blend(PLATE_COLOR, tint, 0.22f) else PLATE_COLOR
        canvas.drawRoundRect(RectF(0f, 0f, full, full), radius, radius, paint)
        // 环：同色描边。系统把封面裁成圆角方或圆形都还看得见
        val stroke = full * 0.035f
        val ring = RectF(stroke, stroke, full - stroke, full - stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke * 2f
        paint.color = tint
        paint.alpha = if (emphasized) 220 else 110
        canvas.drawRoundRect(ring, radius, radius, paint)
        // 中间还是那只鲸，按格着色
        ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)?.let { whale ->
            whale.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            val inset = size / 5
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
 * setMediaSession 要 androidx.media 的 MediaSessionCompat；而 MIUI 看的就是
 * android.template=android.app.Notification$MediaStyle 这一项（真机上从波点音乐的通知里读出来的，
 * 我们的通知同样被 MIUI 画成了状态栏中间那颗胶囊）。
 */
private fun notification(
    context: Context,
    work: IslandWork?,
    token: MediaSession.Token?,
    art: Bitmap?,
): Notification {
    val label = work?.let { phaseLabel(it.phase) } ?: "准备中"
    val text = work?.lines?.firstOrNull().orEmpty()
    val open = openAppIntent(context)
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

/** 点岛 / 点通知回 App（媒体会话的 sessionActivity 与通知的 contentIntent 共用同一个） */
private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
    ),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** 那一格的底色（近黑偏蓝，和 App 的深色底一个调） */
private const val PLATE_COLOR = 0xFF0E1116.toInt()

/** 两色按 [ratio]（0..1）混一点对方进去 —— 给「强调格」的暗底用 */
private fun blend(base: Int, other: Int, ratio: Float): Int {
    fun channel(shift: Int): Int {
        val a = (base shr shift) and 0xFF
        val b = (other shr shift) and 0xFF
        return (a + (b - a) * ratio).toInt().coerceIn(0, 255)
    }
    return Color.argb(255, channel(16), channel(8), channel(0))
}

/** 通知渠道：低优先级、无声、无角标 —— 它是「保活声明 + 岛的载体」，不是要打扰用户的消息 */
private fun ensureChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.deleteNotificationChannel(CHANNEL_ID)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
            description = "agent 在后台工作时显示灵动岛"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        },
    )
}
