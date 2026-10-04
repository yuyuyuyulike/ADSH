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
import android.os.Bundle
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
import org.json.JSONArray
import org.json.JSONObject

private const val CHANNEL_ID = "adsh-island"
private const val NOTIFICATION_ID = 1717

/** 通知上那颗「停止」（也是岛上那张大卡里那颗按钮） */
internal const val ACTION_STOP = "com.adsh.app.island.STOP"

/** 焦点通知里引用的图片名 / 动作名（JSON 与 Bundle 必须对得上） */
private const val PIC_MAIN = "adsh.focus.pic_main"
private const val ACTION_NAME = "adsh.focus.action_stop"

/**
 * 灵动岛的宿主：一个**前台服务**，两件事——
 *
 *  1. **保活**：agent 那一轮跑在 viewModelScope 里，App 一退到后台，进程就成了可回收的普通后台
 *     进程（长任务跑到一半被系统收走 = 用户回来只剩半截）。前台服务 + 常驻通知就是「这一轮跑完
 *     之前别收我」的声明，也是用户口径里那个「后台保活机制」。
 *  2. **当岛**：这条通知按 MIUI 的**焦点通知（超级岛）**协议写 extras，由 **MIUI 自己**把胶囊画在
 *     状态栏那一条里 —— 状态图标给它腾位置、点击展开、动效全是系统的。
 *
 * 协议是从**这台机器上系统自己的热点通知**里读出来的（dumpsys notification --noredact）：
 * extras 是 miui.focus.param（一段 JSON，param_v2 里放业务名 / 胶囊文案 / 大小卡内容 / 动作）、
 * miui.focus.pics（名字 → Icon）、miui.focus.actions（名字 → Notification.Action）、miui.appIcon。
 * 通知本身是 category=status、ONGOING、importance 3（与热点那条一致）。
 *
 * 三处内容都来自同一条状态流：胶囊与卡片上的字 = 那一格文案 + 最新一行（[phaseLabel] / [islandLines]），
 * 图片 = [artFor]（黑圆角方 + 按格着色的鲸：白=等模型、琥珀=等你回答、绿=已结束），
 * 「停止」= [IslandBus.requestStop]（等于输入框右下角那个停止键）。
 *
 * 为什么不再自绘悬浮窗（第 179 轮真机量到）：TYPE_APPLICATION_OVERLAY 在状态栏窗口**之下** ——
 * 那一条里的触摸归 SystemUI（点不动），画面也被系统的岛盖住。自绘那条路已被用户否掉。
 */
class IslandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 岛此刻显示什么；null = 收工（通知也一起撤） */
    private val display = MutableStateFlow<IslandWork?>(null)

    private var watching = false
    private var dwelling: Job? = null

    /** 上一次写进通知栏的那一格：文案没变就不重复 notify（每帧一次 IPC 是真会掉帧的） */
    private var notified: String? = null

    /** 每一格一张小图（192px，五张封顶） */
    private val artCache = HashMap<IslandPhase, Bitmap>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须尽快 startForeground（系统给的窗口只有几秒）
        startForeground(NOTIFICATION_ID, notification(this, display.value, null, null))
        if (intent?.action == ACTION_STOP) IslandBus.requestStop()
        if (!watching) {
            watching = true
            watch()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

    /** 把这一帧写进通知（MIUI 那颗岛的数据源） */
    private fun apply(work: IslandWork) {
        display.value = work
        val label = phaseLabel(work.phase)
        if (label == notified) return
        notified = label
        val art = artFor(work.phase)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(this, work, islandParam(this, work, label), art))
    }

    /**
     * 岛左半格那张小图：黑圆角方 + 一只**按格着色**的鲸（白 = 等模型、琥珀 = 等你回答、绿 = 已结束）。
     *
     * MIUI 的胶囊只画「图 + 文字」，图就是这一张；颜色一变，用户在岛上一眼能看出 agent 是不是卡在等他。
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
 * 焦点通知（MIUI 超级岛）的那段 JSON。字段与系统自己的热点通知**逐字对齐**（真机 dump 出来的），
 * 只把文案/图片名/动作换成我们的：
 *
 * - ticker / aodTitle / smallIslandArea / bigIslandArea.textInfo = 那一格文案（思考 / #代码 / …）；
 * - iconTextInfo = 展开后那张大卡的标题与正文（最新一行）；
 * - actions = 大卡右上那颗「停止」（type 2 = 按钮，action 名对应 miui.focus.actions 里的条目）。
 */
private fun islandParam(context: Context, work: IslandWork, label: String): String {
    val text = work.lines.firstOrNull().orEmpty()
    val param = JSONObject()
        .put("protocol", 1)
        .put("updatable", true)
        .put("business", "adsh-agent")
        .put("reopen", "close")
        .put("enableFloat", false)
        .put("islandFirstFloat", false)
        .put("timeout", 720)
        .put("ticker", label)
        .put("tickerPic", PIC_MAIN)
        .put("tickerPicDark", PIC_MAIN)
        .put("aodTitle", label)
        .put("aodPic", PIC_MAIN)
        .put(
            "param_island",
            JSONObject()
                .put("islandProperty", 1)
                .put("dismissIsland", false)
                .put(
                    "bigIslandArea",
                    JSONObject()
                        .put(
                            "imageTextInfoLeft",
                            JSONObject()
                                .put("type", 1)
                                .put("picInfo", JSONObject().put("type", 1).put("pic", PIC_MAIN)),
                        )
                        .put("textInfo", JSONObject().put("title", label).put("useHighLight", false)),
                )
                .put(
                    "smallIslandArea",
                    JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", PIC_MAIN)),
                ),
        )
        .put(
            "actions",
            JSONArray().put(
                JSONObject().put("type", 2).put("actionTitle", "停止").put("action", ACTION_NAME),
            ),
        )
        .put(
            "iconTextInfo",
            JSONObject()
                .put("animIconInfo", JSONObject().put("type", 0).put("src", PIC_MAIN).put("srcDark", PIC_MAIN))
                .put("title", label)
                .put("content", text),
        )
    return JSONObject().put("param_v2", param).toString()
}

/**
 * 那条常驻通知：**焦点通知**（MIUI 超级岛）。文案跟着那一格走，点它回 App，上面挂一颗「停止」。
 *
 * 用平台的 Notification.Builder（不是 NotificationCompat）：焦点通知的 extras 是 Bundle 里的
 * Icon / Action / JSON 字符串，平台 API 直接就能放，不需要额外依赖。
 */
private fun notification(
    context: Context,
    work: IslandWork?,
    param: String?,
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
    val stopAction = Notification.Action.Builder(
        Icon.createWithResource(context, R.drawable.ic_dsh_whale),
        "停止",
        stop,
    ).build()
    val builder = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_dsh_whale)
        .setContentTitle(label)
        .setContentText(text)
        .setSubText("ADSH")
        .setContentIntent(open)
        .addAction(stopAction)
        .setOngoing(true)
        // 安静：渠道本身无声无振动，这里再声明「只提醒一次」（平台 Builder 没有 setSilent）
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_STATUS)
    if (art != null) builder.setLargeIcon(art)
    if (param != null && art != null) {
        val extras = Bundle()
        extras.putString("miui.focus.param", param)
        extras.putBundle(
            "miui.focus.pics",
            Bundle().apply { putParcelable(PIC_MAIN, Icon.createWithBitmap(art)) },
        )
        extras.putBundle(
            "miui.focus.actions",
            Bundle().apply { putParcelable(ACTION_NAME, stopAction) },
        )
        extras.putParcelable("miui.appIcon", Icon.createWithBitmap(art))
        builder.addExtras(extras)
    }
    return builder.build()
}

/**
 * 通知渠道：与系统热点那条同级（IMPORTANCE_DEFAULT），但**无声无振动无角标** ——
 * 它是「保活声明 + 岛的载体」，不是要打扰用户的消息。
 *
 * 建之前先删一次旧的同名渠道：渠道的重要性一旦建成就改不了（用户的设置是粘的），
 * 而这条渠道在焦点通知之前是按 LOW 建的 —— 不删的话新等级永远不生效。
 */
private fun ensureChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.deleteNotificationChannel(CHANNEL_ID)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "agent 在后台工作时显示灵动岛"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        },
    )
}
