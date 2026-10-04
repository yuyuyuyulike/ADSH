package com.adsh.app.island

import android.app.Service
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.adsh.app.ui.DshIcons
import com.adsh.app.ui.DshSettingIcons
import com.adsh.app.ui.SWEEP_PERIOD_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 岛的三档尺寸（dp）。**收起的宽度是固定的**：用户口径「其水平长度不应该随着
 * 文案长度而有所变化，一开始长一点就行」—— 之前按内容撑开，每换一格宽度就跳一次，
 * 那正是「动效一抽一抽」的第一来源。
 */
private const val PILL_WIDTH_DP = 150f
private const val PILL_HEIGHT_DP = 37f
private const val CARD_WIDTH_DP = 300f

/** 展开态那一块（三行）：行高、上下留白 —— 块高固定，两三行怎么变都不跳 */
private const val DETAIL_LINE_DP = 19f
private const val DETAIL_TOP_DP = 2f
private const val DETAIL_BOTTOM_DP = 12f

/** 收起态的圆角 = 半高（胶囊）；展开态是一张卡片，四角圆一点 */
private const val PILL_CORNER_DP = PILL_HEIGHT_DP / 2f
private const val CARD_CORNER_DP = 24f

/** 胶囊与状态栏下沿之间的空隙（dp）：贴上沿但不进那一条，见 [IslandWindow.pillTopInset] */
private const val PILL_TOP_GAP_DP = 2f

/**
 * 位置微调（dp）：正值往下挪、负值往状态栏那一条里挪。
 *
 * 第 179 轮真机上量到两件事（合起来决定默认值只能是 0 这个「状态栏正下方」）：
 *  1. 状态栏那一条里的**触摸事件归 SystemUI**（点上去毫无反应）；
 *  2. 那一条里的**画面也是 SystemUI 在上面** —— 用户手机开着热点时，MIUI 自己的灵动岛就压在这
 *     个位置，我们的胶囊整个被它盖住；连点一下都会命中它（实测：点下去展开的是 MIUI 的热点卡片）。
 * 想让胶囊更贴挖孔就把这个数调成负的（-8dp 左右 ≈ 露一半），代价是「露出去的那半截才能点」，
 * 而且 MIUI 自己的岛一出现就把它盖住。
 */
private const val PILL_TOP_BIAS_DP = 0f

/** 算不算「点了一下」的位移上限（px）：超过就当成滑动 */
private const val TAP_SLOP_PX = 24f

/** 形态变化（胶囊 ↔ 卡片）的弹簧：临界的，不来回弹；只是把匀速的机械感去掉 */
private val MorphSpring = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 1f, stiffness = 700f)

/** 岛的底色与字色：**不跟主题走**（挖孔/状态栏那一条上只有纯黑压得住，浅色主题一换就露馅） */
private val IslandBg = Color(0xF0000000)
private val IslandBorder = Color(0x1FFFFFFF)
private val IslandText = Color(0xE6FFFFFF)
private val IslandCardText = Color(0xD9FFFFFF)

/**
 * 灵动岛的窗口：一块挂在 [WindowManager] 上的 Compose 视图（[WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]）。
 *
 * 为什么是**悬浮窗**：用户口径是「App 退到后台时，屏幕顶部显示 agent 在干什么」；通知栏里的东西
 * 到不了屏幕顶部，而 TYPE_APPLICATION_OVERLAY 正是系统给「画在别的应用上面」的那一档，代价是需要
 * 「悬浮窗权限」（设置页的权限卡里有那一项）。没授权就不挂窗口（服务照跑，保活不受影响）。
 *
 * 位置与尺寸的口径见 [pillTopInset] 与文件头那几个常量；形态与动效见 [IslandSurface]。
 */
internal class IslandWindow(
    private val service: Service,
    private val scope: CoroutineScope,
    private val display: StateFlow<IslandWork?>,
    private val visible: StateFlow<Boolean>,
) {

    private val manager = service.getSystemService(WindowManager::class.java)
    private var host: View? = null
    private var owner: OverlayLifecycleOwner? = null

    /** 展开态（点一下岛）：状态住在窗口这一层，见 [IslandRootView] */
    private val expanded = MutableStateFlow(false)

    /** 左侧那枚应用图标：**进窗口前就加载好**，免得第一帧空一个格子再「蹦」出来 */
    private val icon = MutableStateFlow<ImageBitmap?>(null)
    private var iconLoaded = false

    /** 挂上窗口（已经挂着就什么都不做；没给悬浮窗权限就不挂） */
    fun attach() {
        if (host != null) return
        if (!Settings.canDrawOverlays(service)) return
        loadIcon()
        val lifecycleOwner = OverlayLifecycleOwner().apply { resume() }
        // 取成局部变量再交给 ComposeView：apply 里裸写 display 会命中 View.display（Display）
        val flow = display
        val shown = visible
        val open = expanded
        val appIcon = icon
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            // 摘下来就整棵丢掉：展开态与动画都不该跨窗口存活
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { IslandContent(flow, shown, open, appIcon) }
        }
        // 外面套一层：[IslandRootView] 把触摸全吃掉，展开/收起由它切换
        val root = IslandRootView(service, view) {
            expanded.value = !expanded.value
            android.util.Log.d("ADSH-Island", "tap -> expanded=" + expanded.value)
        }
        val added = runCatching { manager.addView(root, params()) }.isSuccess
        if (!added) {
            lifecycleOwner.destroy()
            return
        }
        host = root
        owner = lifecycleOwner
    }

    /** 摘掉窗口（服务收工、或用户回到 App 前台） */
    fun detach() {
        host?.let { view -> runCatching { manager.removeView(view) } }
        host = null
        owner?.destroy()
        owner = null
    }

    private fun loadIcon() {
        if (iconLoaded) return
        iconLoaded = true
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                service.packageManager.getApplicationIcon(service.packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
            if (bitmap != null) icon.value = bitmap
        }
    }

    private fun params(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 不吃按键焦点（不抢输入），但要收触摸（点一下展开）；状态栏那一条之上仍归 SystemUI
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = pillTopInset()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * 胶囊挂在**状态栏正下方**（y = 状态栏高 + [PILL_TOP_GAP_DP]，可用 [PILL_TOP_BIAS_DP] 微调）。
     *
     * 为什么不压在挖孔/状态栏那一条上（第 179 轮真机实测，两条独立证据）：
     *  1. **触摸**：TYPE_APPLICATION_OVERLAY 的层级低于状态栏窗口 —— 那一条里的事件全归 SystemUI，
     *     点上去毫无反应；
     *  2. **画面**：那一条里的绘制也是 SystemUI 在上 —— MIUI 自己的灵动岛（热点 / 充电 / 音乐）
     *     正好压在这个位置，我们的胶囊被整个盖住，连点一下都会命中它。
     * 挂在状态栏下沿之后：整枚胶囊都在自己的窗口里（点得到），也不会与系统自己的岛打架。
     *
     * @return 胶囊顶边距屏幕顶部的像素
     */
    private fun pillTopInset(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) service.resources.getDimensionPixelSize(id) else 0
        val density = service.resources.displayMetrics.density
        val gap = (PILL_TOP_GAP_DP * density).toInt()
        val bias = (PILL_TOP_BIAS_DP * density).toInt()
        return statusBar + gap + bias
    }
}

/**
 * 岛的根 View：**触摸在这里就被吃掉**，一点都不往下传。
 *
 * 为什么不用 Compose 的 clickable（真机实测的结论）：浮窗是**没有 Activity 的窗口**，Compose 的
 * 命中测试在这条路径上不可靠 —— 事件确实进了本进程的 ViewRootImpl（MIUIInput 的日志里看得到），
 * 但 clickable 一次都没触发，展开态永远打不开。改挂在 ComposeView 身上也不行：AndroidComposeView
 * 是它的子 View，会先把事件消费掉，父 View 的 OnTouchListener 根本轮不到。于是最外层一个
 * FrameLayout，dispatchTouchEvent 直接 return true —— 岛里没有任何需要触摸的子控件（那两三行是
 * 只读文本），全吃掉没有副作用。
 *
 * 位移小于 [TAP_SLOP_PX] 才算点击（以后若给岛加拖动，就在 ACTION_MOVE 里分流）。
 */
private class IslandRootView(
    context: android.content.Context,
    content: View,
    private val onTap: () -> Unit,
) : android.widget.FrameLayout(context) {

    private var downX = 0f
    private var downY = 0f

    init {
        addView(content)
        // 两条保险一起上：不拦下来（intercept）的话，子 View（AndroidComposeView）会先把事件吃掉，
        // 父 View 的 OnTouchListener / onTouchEvent 根本轮不到
        isClickable = true
        setOnClickListener { onTap() }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        trackDown(event)
        // 全部拦下：岛里没有任何需要触摸的子控件
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && withinSlop(event)) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun trackDown(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
        }
    }

    private fun withinSlop(event: MotionEvent): Boolean =
        kotlin.math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) <= TAP_SLOP_PX
}

/**
 * 悬浮窗里的 Compose 需要的那套 owner：不是 Activity，就得自己给一份。
 *
 * [SavedStateRegistryOwner] 也要给：扫光那类组件用 rememberSaveable（见 RunningSweep），没有它
 * 会在组合期直接抛。
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun resume() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * 岛的内容：出现/退场自己演，尺寸与形态在 [IslandSurface] 里。
 *
 * 出现用**缩放 + 淡入**（从顶部那一点长出来），退场对称回去 —— 用户口径「结束后收起的也不丝滑」：
 * 之前是到点直接把窗口摘掉（硬切），现在是先演完退场动画、服务再收工。
 */
@Composable
private fun IslandContent(
    display: StateFlow<IslandWork?>,
    visible: StateFlow<Boolean>,
    expanded: MutableStateFlow<Boolean>,
    icon: StateFlow<ImageBitmap?>,
) {
    val work by display.collectAsState()
    val shown by visible.collectAsState()
    val open by expanded.collectAsState()
    val appIcon by icon.collectAsState()
    val current = work ?: return
    // 首帧先关着，下一帧再打开：AnimatedVisibility 初次组合不播进入动画，得给它一个「从无到有」
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered && shown,
        enter = fadeIn(tween(150)) + scaleIn(
            initialScale = 0.7f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            targetScale = 0.7f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = tween(150, easing = LinearEasing),
        ),
    ) {
        IslandSurface(work = current, open = open, icon = appIcon)
    }
}

/**
 * 岛的**一整块面**：收起是胶囊，点开之后**同一块面**长成卡片（不是另外浮一张 —— 用户口径
 * 「展开不是这样的」）。宽度、高度、圆角三者同时用弹簧插值，内容跟着长出来。
 */
@Composable
private fun IslandSurface(work: IslandWork, open: Boolean, icon: ImageBitmap?) {
    val targetWidth = if (open) CARD_WIDTH_DP.dp else PILL_WIDTH_DP.dp
    val targetHeight = if (open) {
        (PILL_HEIGHT_DP + DETAIL_TOP_DP + DETAIL_LINE_DP * ISLAND_DETAIL_LINES + DETAIL_BOTTOM_DP).dp
    } else {
        PILL_HEIGHT_DP.dp
    }
    val width by animateDpAsState(targetWidth, MorphSpring, label = "islandWidth")
    val height by animateDpAsState(targetHeight, MorphSpring, label = "islandHeight")
    val corner by animateDpAsState(
        (if (open) CARD_CORNER_DP else PILL_CORNER_DP).dp,
        MorphSpring,
        label = "islandCorner",
    )
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(IslandBg)
            .border(0.5.dp, IslandBorder, shape),
    ) {
        Column(Modifier.fillMaxWidth()) {
            HeaderRow(work = work, icon = icon)
            // 详细块自己淡入淡出；高度由上面那块面负责长（内容被 shape 裁掉）
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(140, delayMillis = 90)),
                exit = fadeOut(tween(80)),
            ) {
                DetailSlots(work.lines)
            }
        }
    }
}

/**
 * 收起态那一行：**左边应用图标、右边这一格的图标 + 文案**（用户口径，也是 MIUI 热点灵动岛的
 * 样子：两头对齐、中间留白）。高度固定，两头的位置不随文案长短变。
 */
@Composable
private fun HeaderRow(work: IslandWork, icon: ImageBitmap?) {
    Row(
        Modifier.fillMaxWidth().height(PILL_HEIGHT_DP.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppIcon(icon)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = phaseIcon(work.phase),
                contentDescription = null,
                tint = phaseTint(work.phase),
                modifier = Modifier.size(15.dp),
            )
            StatusLabel(
                text = phaseLabel(work.phase),
                color = phaseTint(work.phase),
                shimmer = work.phase == IslandPhase.THINKING || work.phase == IslandPhase.TOOL,
            )
        }
    }
}

/** 展开态那两三行：**固定三个槽位**（空行也占位），行数怎么变，块高都不变 —— 不跳 */
@Composable
private fun DetailSlots(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = DETAIL_TOP_DP.dp, bottom = DETAIL_BOTTOM_DP.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(ISLAND_DETAIL_LINES) { index ->
            Text(
                text = lines.getOrElse(index) { "" },
                modifier = Modifier.height(DETAIL_LINE_DP.dp),
                fontSize = 12.sp,
                lineHeight = DETAIL_LINE_DP.sp,
                color = IslandCardText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 左侧那枚应用图标（进窗口前就加载好，见 [IslandWindow.loadIcon]） */
@Composable
private fun AppIcon(bitmap: ImageBitmap?) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(21.dp).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Spacer(Modifier.size(21.dp))
    }
}

/**
 * 状态文案。**同一个 Text 节点**，亮度/流光用同一个 brush 表达（见下），不换组件 ——
 * 换组件会在换格那一帧重建文字，观感就是「抽一下」。
 *
 * 流光：周期 [SWEEP_PERIOD_MS]、前 90% 用 ease-out 走完（cubic-bezier(0, 0, 0.58, 1)），
 * 与对话里思考行/工具行**同一套**；区别只是这里扫的是字面本身（TextStyle 的 brush），
 * 而不是在行上盖一层底色 —— 岛是黑底白字，盖一层白雾会把字擦掉。
 */
@Composable
private fun StatusLabel(text: String, color: Color, shimmer: Boolean) {
    // 流光强弱也做成渐变：换格那一下是「流光淡出」，不是「文字突然换个颜色」
    val strength by animateFloatAsState(if (shimmer) 1f else 0f, tween(240), label = "islandShimmer")
    val transition = rememberInfiniteTransition(label = "islandSweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SWEEP_PERIOD_MS.toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "islandSweepProgress",
    )
    val density = LocalDensity.current
    val band = with(density) { 84.dp.toPx() }
    val t = (progress / 0.9f).coerceAtMost(1f)
    val u = 1f - t
    val eased = 1f - u * u * u
    val x = -band + eased * (band * 3f)
    val base = color.copy(alpha = color.alpha * (1f - 0.18f * strength))
    val peak = lerp(color, Color.White, strength)
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            // 长工具名（ask_user_question）在 150dp 里放不下：按长度降一档字号，不截断
            fontSize = if (text.length > 10) 11.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(0f to base, 0.5f to peak, 1f to base),
                startX = x,
                endX = x + band,
            ),
        ),
    )
}

/** 收起态右半格的文案（用户口径的五格） */
internal fun phaseLabel(phase: IslandPhase): String = when (phase) {
    IslandPhase.THINKING -> "思考"
    IslandPhase.TOOL -> "#代码"
    IslandPhase.ASK -> ASK_TOOL_LABEL
    IslandPhase.OUTPUT -> "输出中"
    IslandPhase.DONE -> "已结束"
}

/** 每一格自己的图标（思考那格用户点名要「思考二字加思考图标」） */
private fun phaseIcon(phase: IslandPhase): ImageVector = when (phase) {
    IslandPhase.THINKING -> DshIcons.Think
    IslandPhase.TOOL -> DshIcons.Terminal
    IslandPhase.ASK -> DshSettingIcons.Question
    IslandPhase.OUTPUT -> DshSettingIcons.ListPen
    IslandPhase.DONE -> DshIcons.Check
}

/** 每一格的颜色：等模型的两格是白的，等你回答是琥珀，跑完是绿的 */
private fun phaseTint(phase: IslandPhase): Color = when (phase) {
    IslandPhase.ASK -> Color(0xFFFFC66D)
    IslandPhase.DONE -> Color(0xFF7BE0A3)
    else -> IslandText
}
