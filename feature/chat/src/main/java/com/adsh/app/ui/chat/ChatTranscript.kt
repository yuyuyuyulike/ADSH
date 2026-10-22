package com.adsh.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.blocks.ContextInjectionRow
import com.adsh.app.ui.blocks.LocalMarkdownRoot
import com.adsh.app.ui.blocks.SystemPromptRow
import com.adsh.app.ui.blocks.TurnStatusRow
import com.adsh.app.ui.kit.DshSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 会话内容的内边距：内容列与右侧滚动条的可视区共用同一份。
 *
 * 侧边 16 = 输入卡的侧留白（内容列与卡等宽）。这与 dsh 的 32（内容列比卡窄 16/侧）是**有意差异**：
 * 用户要的是内容从 28dp 圆角缺口里透出、被圆角裁切；dsh 的 32 下缺口里几乎看不到内容。
 */
internal val TRANSCRIPT_HORIZONTAL_PADDING = 16.dp
internal val TRANSCRIPT_VERTICAL_PADDING = 14.dp

/**
 * 会话流：正序、一次性渲染并常驻的列表（dsh 的 Chat Node 列表）+ 右下角「回到底部」+ 右侧贴边滚动条。
 *
 * 这里只做「把 items 画出来」和读者的手势，状态与判定都在别处：
 *  - 列表内容由 [rememberChatItems] 组装（items 是 derivedStateOf，每次重算换实例）；
 *  - **整份列表一次性组合、不回收**（dsh 把 DOM 节点一直留在树上）：滚动时没有行重建，
 *    滚动容器的 maxValue 就是真实内容高度，滚动条不依赖任何行高估算；
 *  - 自动滚动的状态与判定在 [ChatScrollState]（本文件只读它的 following，写它的 touch）；
 *  - 展开 / 收起、跳转这些**读者动作**一律先交出跟随（[readerAction]），口径见 AutoScroll.kt 的 ⑤。
 *
 * 手势（按着不贴底 + 点空白收键盘）的判据是 [shouldClearComposerFocus]。
 */
@Composable
internal fun ChatTranscript(
    state: ChatUiState,
    items: List<ChatItem>,
    scrollState: ScrollState,
    scroll: ChatScrollState,
    foldOpen: SnapshotStateMap<Long, Boolean>,
    /** 哪些思考行的正文展开着（键见 [reasoningKey]）：写它 = 读者动作，先交出自动跟随 */
    reasoningOpen: SnapshotStateMap<String, Boolean>,
    /** 读者动作（展开 / 收起会话里的任何一行、打开任务列表）：先把跟随交出去（口径见 AutoScroll.kt） */
    readerAction: () -> Unit,
    /** 把视口钉到内容末端（本帧测量之前生效） */
    pinToBottom: () -> Unit,
    onBranch: (Long) -> Unit,
    onOpenDeliverable: (String) -> Unit,
    /** 底部 footer（输入框那一摞）的实测高度：内容与滚动条都要避开它 */
    footerHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val hasConversation = items.isNotEmpty()
    val contentFontSize = state.contentFontSize
    /**
     * 滚动条可见性：读者滚动中（拖拽 / 惯性）恒显示；停止滚动满 [SCROLLBAR_HIDE_DELAY_MS]
     * 后隐藏，再滚又出现。进会话时先显示，没有滚动操作一秒后自动隐藏。
     *
     * 程序自动贴底（流式跟随）不算「滚动操作」—— 它只跟内容变化走，不是读者在动；
     * 滚动条只对读者的滚动手势计时。
     */
    var scrollbarVisible by remember(scrollState) { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    scrollbarVisible = true
                } else {
                    delay(SCROLLBAR_HIDE_DELAY_MS)
                    scrollbarVisible = false
                }
            }
    }
    /** 滚动视口高度（px）：绘制剔除与同帧贴底修正都读它 */
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    /** 滚动内容总高度（px，含底内边距）：同帧贴底修正读它 */
    var contentHeightPx by remember { mutableIntStateOf(0) }
    /**
     * 同帧贴底修正（px）：Compose 的 ScrollState 只能等下一帧生效，于是「内容刚长高」的那一帧会先
     * 露出旧位置（新行在视口下沿被切 / 闪一下）再跳过去。
     *
     * 这里在绘制阶段给内容整体补一个位移：**这一帧就已经贴到底**，下一帧 KeepPinnedEffect 把
     * ScrollState 拨到真值，位移随之归零 —— 两帧之间没有任何视觉变化。只有「跟随中、手指不在屏上、
     * 也没在惯性滚动」时才补（口径与 [pinAllowed] 相同）；读者翻过历史时一律不补。
     */
    fun bottomCorrectionNow(): Float {
        val maxValue = scrollState.maxValue
        val value = scrollState.value
        return if (viewportHeightPx <= 0 || contentHeightPx <= 0 || maxValue <= 0 ||
            !scroll.following || scroll.touching || scrollState.isScrollInProgress
        ) {
            0f
        } else {
            val desired = (contentHeightPx - viewportHeightPx).coerceAtLeast(0)
            (value - desired).toFloat()
        }
    }
    /**
     * 贴底绘制补偿：**派生状态**。它每帧都被重新求值（值通常一直是 0），但只有**值真的变了**才通知
     * 订阅者 —— 以前每行各自读 value / maxValue / following 等一串状态，键盘动画或滚动一帧就把
     * 整个会话流的行全部标脏，长长会话里这是最大的一笔白工。
     */
    val bottomCorrection = remember(scrollState, scroll) { derivedStateOf { bottomCorrectionNow() } }
    /** 行级绘制剔除的窗口（内容坐标）：滚动 / 视口按台阶取整，只在窗口变化时让行失效（见 CULL_STEP_PX） */
    val cullWindow = remember(scrollState, scroll) {
        derivedStateOf {
            val bucket = scrollState.value / CULL_STEP_PX * CULL_STEP_PX
            val viewport = (viewportHeightPx + CULL_VIEWPORT_STEP_PX - 1) / CULL_VIEWPORT_STEP_PX * CULL_VIEWPORT_STEP_PX
            CullWindow(bucket, viewport, bottomCorrection.value)
        }
    }

    /**
     * 视口变高（收起键盘）/ 内容变短都会让 maxValue 变小，而 ScrollState.value **不会自己往回夹**：
     * value 超出 maxValue 时，绘制仍按旧偏移画（内容整体下移、上方空出一块），而且
     * [KeepPinnedEffect] 里 `maxValue - value <= 0` 会把它当成“已经贴底”而永远不纠正。
     *
     * 首次布局之后再夹：初始的 Int.MAX_VALUE 靠第一帧测量落到内容末端（进会话即底部），
     * 这一条只负责“maxValue 变小”之后的越界回夹。
     */
    LaunchedEffect(scrollState) {
        snapshotFlow { Triple(scrollState.value, scrollState.maxValue, viewportHeightPx) }
            .collect { (value, maxValue, viewport) ->
                if (viewport > 0 && value > maxValue) {
                    if (com.adsh.app.ui.chat.ChatDebug.enabled) trace("pin clamp", "v=" + value + " max=" + maxValue)
                    scrollState.scrollTo(maxValue)
                }
            }
    }

    // 手指按在消息区期间一下都不贴底（[ChatScrollState.touching]）。**手势本身不改跟随状态** ——
    // 它由位置采样按「读者到底移动没移动」决定，所以点一下、按住不动都不会把跟随关掉。
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            // 键用 scrollState：换会话时滚动状态会重建，这里跟着重启
            .pointerInput(scrollState) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val startY = down.position.y
                    // 这一次手势是不是真的把内容滑走了（超过 touch slop）
                    var dragged = false
                    scroll.touch(true)
                    try {
                        // 等这一套手势彻底结束（松手或取消）
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null &&
                                !dragged &&
                                kotlin.math.abs(change.position.y - startY) > viewConfiguration.touchSlop
                            ) {
                                dragged = true
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        // finally 而不是顺序执行：手势被取消（节点回收）时手指状态也必须复位。
                        scroll.touch(false)
                        // 点在消息区（没滑动的那一下）= 离开输入状态：收起光标与键盘。滑动不算 ——
                        // 滑历史的时候键盘不该自己收起来。长按也不算（判据见 shouldClearComposerFocus）。
                        val heldMillis = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                        if (shouldClearComposerFocus(dragged, heldMillis, viewConfiguration.longPressTimeoutMillis)) {
                            focusManager.clearFocus()
                        }
                    }
                }
            },
    ) {
        // dsh 的 --dsh-content-font-size：会话内容区按设置里的字号缩放
        // （只影响会话内容，顶栏 / 输入栏不动，与 dsh 的「仅影响会话内容的字号」一致）
        val baseDensity = LocalDensity.current
        val contentDensity = remember(baseDensity, contentFontSize) {
            androidx.compose.ui.unit.Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * (contentFontSize / 14f),
            )
        }
        // Markdown 里的相对路径（图片 / 文件链接）按会话工作区解析
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides contentDensity,
            LocalMarkdownRoot provides state.workspacePath,
        ) {
        if (!hasConversation) {
            Hero()
        } else {
            // 一次性渲染并常驻（dsh 的 DOM 语义）：每行只组合一次，滚动时不再重建节点；
            // 滚动容器的 maxValue 就是真实内容高度 —— 滚动条比例取它，内容没变长度就不变。
            // 每行在内容坐标系里的 y（layout 阶段写、draw 阶段读）。**不能用 onGloballyPositioned 记**：
            // 滚动 / 键盘动画时全局坐标每帧都变，回调会把每一行都叫一遍 + 写状态，100+ 行时主线程 40ms
            // （用户报的「会话越长越卡」）。纯数据数组不进快照，不会额外触发重组。
            val rowTops = remember { ArrayList<Float>() }
            CulledColumn(
                rowTops = rowTops,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // 滚动内容按自身高度测量，不再被视口的 minHeight 撑高/居中：短会话必须贴顶，
                    // 否则「内容高度 = 视口高度」会让 maxValue / contentHeight 全部失真（键盘收起后
                    // 内容整体下移、上方空一块就是这条引起的）。
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { contentHeightPx = it.height }
                    // 见上面 bottomCorrection：同帧贴底。只在**绘制阶段**平移内容（不改布局、不开图层）：
                    // 会话内容可以高过屏幕几十倍 —— graphicsLayer 会按内容总高分配纹理，真机直接卡死；
                    // offset 会改布局位置，把每行的“内容坐标”也带偏（绘制剔除会算错）。
                    .drawWithContent {
                        val shift = bottomCorrection.value
                        if (shift == 0f) {
                            drawContent()
                        } else {
                            drawContext.canvas.save()
                            drawContext.canvas.translate(0f, shift)
                            drawContent()
                            drawContext.canvas.restore()
                        }
                    }
                    .padding(
                        start = TRANSCRIPT_HORIZONTAL_PADDING,
                        top = TRANSCRIPT_VERTICAL_PADDING,
                        end = TRANSCRIPT_HORIZONTAL_PADDING,
                        bottom = transcriptBottomPadding(footerHeightPx, LocalDensity.current),
                    ),
            ) {
                items.forEachIndexed { rowIndex, item ->
                    key(transcriptKey(item)) {
                        // 只在 debug 里挂这个诊断副作用：宿主注入的 `ChatDebug.enabled` 在 release 里是 false，
                        // 整段 if 会被折掉 —— 否则每一行都会多出一个 DisposableEffect（本行进出组合时的日志）。
                        if (com.adsh.app.ui.chat.ChatDebug.enabled) {
                            val rowKey = transcriptKey(item)
                            androidx.compose.runtime.DisposableEffect(rowKey) {
                                trace("row+", rowKey)
                                onDispose { trace("row-", rowKey) }
                            }
                        }
                        CulledRow(
                            window = cullWindow,
                            topAt = { rowTops.getOrElse(rowIndex) { 0f } },
                        ) {
                        when (item) {
                            // dsh 的 system-prompt 节点：系统提示词 / 系统提示词更新
                            // （会话里任何一行的展开都要先交出自动跟随）
                            is ChatItem.SystemPrompt ->
                                Box(Modifier.padding(top = item.gap)) {
                                    SystemPromptRow(item.text, item.update, onReaderAction = readerAction)
                                }
                            // dsh 的 context 节点：上下文注入
                            is ChatItem.Context -> Box(Modifier.padding(top = item.gap)) {
                                ContextInjectionRow(
                                    label = item.label,
                                    form = item.form,
                                    text = item.text,
                                    onReaderAction = readerAction,
                                )
                            }
                            is ChatItem.User -> Box(Modifier.padding(top = item.gap)) {
                                UserMessage(item.text, item.time, item.attachments)
                            }
                            is ChatItem.Compact -> Box(Modifier.padding(top = item.gap)) {
                                CompactCard(item.text, onReaderAction = readerAction)
                            }
                            // 折叠行：展开 / 收起（状态在 foldOpen 里，items 是 derivedStateOf，
                            // 写它就会重算列表 —— 不需要额外的「修订号」
                            is ChatItem.TurnFold -> TurnFoldRow(
                                view = item.view,
                                open = item.open,
                                // 读者动作：停止自动跟随（否则这一展开会被流式贴底顶上去）。
                                // 展开天然向下长、上方不动，不再做补偿滚动（见上面 readerAction 附近的注释）
                                onToggle = { expanded ->
                                    readerAction()
                                    foldOpen[item.view.key] = expanded
                                },
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 过程里的一条：一行一个 item（展开时只组合看得见的那几行）
                            is ChatItem.TurnEntry -> TurnEntryRow(
                                view = item.view,
                                index = item.index,
                                // 思考行的展开态在界面上（正文是独立行，列表装配要知道它开没开）：
                                // 点开 / 收起是读者动作 —— 先交出自动跟随（展开向下长、上方不动）
                                reasoningOpen = reasoningOpen[reasoningKey(item.view, item.index)] == true,
                                onReasoningToggle = { expanded ->
                                    readerAction()
                                    reasoningOpen[reasoningKey(item.view, item.index)] = expanded
                                },
                                // 行里的展开/收起是行内部状态：它一动就停止自动跟随（readerAction）；
                                // 展开向下长、上方不动，不替读者滚（见上面 readerAction 附近的注释）
                                onReaderAction = { readerAction() },
                                // 点工具行摘要里的文件路径 → 进文件预览（与轮尾交付物卡片同一条路径解析）
                                onOpenFile = onOpenDeliverable,
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 思考正文的一块：块间距口径在 ReasoningBodyRow；行级绘制剔除的粒度就是它
                            is ChatItem.ReasoningBody -> ReasoningBodyRow(
                                text = item.text,
                                before = item.before,
                                after = item.after,
                                streaming = item.streaming,
                            )
                            // 长回答切成的一块（见 ChatItem.TurnAnswer）：块与块之间的边距由
                            // MarkdownBody 的块间距自己带（hasPreceding / hasFollowing），
                            // 所以这里只有第一块吃行距、后续块 gap = 0
                            is ChatItem.TurnAnswer -> Box(Modifier.padding(top = item.gap)) {
                                AssistantText(
                                    content = item.text,
                                    hasPreceding = item.hasPreceding,
                                    hasFollowing = item.hasFollowing,
                                )
                            }
                            // dsh 的 RunningStatus：会话流最后一行（跟着内容滚，不是输入框上方的浮层 —
                            // 浮层会落在 footer 顶部 36dp 的渐变带里，文字看着像透明的）
                            is ChatItem.Running -> Box(Modifier.padding(top = item.gap)) {
                                TurnStatusRow(startedAt = item.startedAt)
                            }
                            // 轮尾：**每个已结束轮次**都挂动作行；能不能分支由 TurnView 的
                            // branchVisible / branchEnabled 判（dsh 的 TurnTailNodeView，判据见 TurnModel.kt）
                            is ChatItem.TurnTail -> TurnTailRow(
                                view = item.view,
                                onBranch = onBranch,
                                modifier = Modifier.padding(top = item.gap),
                                workspacePath = state.workspacePath,
                                onOpenDeliverable = onOpenDeliverable,
                                // 交付文件卡的展开 / 收起与工具行同一条规矩（读者动作）：展开天然
                                // 向下长、上方一动不动，也不做补偿滚动 —— 见下面 readerAction 的注释
                                onCardToggle = { readerAction() },
                            )
                        }
                        }
                        }
                    }
                }
            }
        }
        // 右侧贴边 overlay 滚动条（dsh 是占 5px 布局的原生条，ADSH 有意改成不占布局）：
        // 只做位置指示，不吃任何手势。
        if (hasConversation) {
            TranscriptScrollbar(
                scrollState = scrollState,
                bottomInsetPx = footerHeightPx,
                visible = scrollbarVisible,
            )
        }
        // 「回到底部」：不在跟随状态就浮出来（读者上滑 / 跳转到别处）；跟随中不显示，
        // 所以内容长高不会让它闪。
        if (hasConversation && !scroll.following) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = DshSpacing.Card,
                        bottom = with(LocalDensity.current) { footerHeightPx.toDp() } + DshSpacing.Card,
                    ),
            ) {
                BackToBottomButton {
                    // 无条件贴底：恢复跟随后再滚（不等 KeepPinnedEffect 下一帧补）
                    scroll.resume()
                    pinToBottom()
                }
            }
        }
    }
}

/**
 * 行级绘制剔除：**只读一个派生窗口**。
 *
 * 以前每行各自读 `scrollState.value`（以及贴底补偿那一串状态）：滚动 / 键盘动画的每一帧都会把
 * N 行全部标脏、逐行重跑一遍剔除判断；上千步调用的会话里这一步就是随历史线性增长的那笔开销。
 * 现在窗口按 [CULL_STEP_PX] 取整，只在跨过台阶（或视口/补偿真的变了）时换实例 —— 中间每一帧
 * 只有真正在窗口里的行被重画。[CULL_STEP_PX] 与 [CULL_OVERDRAW_PX] 一起保证不会露行：
 * 窗口按「本台阶内可能的最小/最大滚动值」取安全边界，宁可多画一条边。
 */
@Composable
private fun CulledRow(
    window: State<CullWindow>,
    /** 这一行在内容坐标系里的 y（由 CulledColumn 在 layout 阶段写进 rowTops）。 */
    topAt: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                val w = window.value
                if (w.viewport <= 0) {
                    drawContent()
                    return@drawWithContent
                }
                if (rowVisibleInCullWindow(w.bucket, w.viewport, w.correction, topAt(), size.height)) {
                    drawContent()
                }
            },
    ) {
        content()
    }
}

/** 行级剔除窗口：桶化的滚动位置 + 向上取整的视口 + 当前贴底绘制补偿 */
private data class CullWindow(val bucket: Int, val viewport: Int, val correction: Float)

/**
 * 一行要不要画：窗口按「本台阶内可能的最小/最大滚动值」取安全边界，所以桶内任何真实滚动位置下
 * 可见的行都会被画到（宁可多画一条边，也不能露行）。
 *
 * 抽成纯函数是为了让 [CullWindowTest] 直接把「可见 ⇒ 必画」这条不变量扫一遍。
 */
internal fun rowVisibleInCullWindow(
    bucket: Int,
    viewport: Int,
    correction: Float,
    top: Float,
    height: Float,
): Boolean {
    val placed = top + correction
    return placed + height >= bucket - CULL_OVERDRAW_PX &&
        placed <= bucket + CULL_STEP_PX + viewport + CULL_OVERDRAW_PX
}

/**
 * 会话流的纵向堆叠（替代 Column）：**布局时把每行在内容坐标系里的 y 记进 [rowTops]**，绘制剔除
 * （[CulledRow]）直接读它。
 *
 * 为什么不用 Column + onGloballyPositioned：滚动 / 键盘动画时全局坐标每帧都变，回调会把每一行
 * 都叫一遍再写一次状态 —— 100+ 行时主线程 40ms（用户报的「会话越长越卡」）。这里的数组是纯
 * Kotlin 数据、不进快照，布局写、绘制读，没有每帧回调，也不会额外触发重组。
 */
@Composable
private fun CulledColumn(
    rowTops: MutableList<Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        // 子行必须按**自身高度**测量：外层链上的 fillMaxSize 会把视口高度写成 minHeight，
        // verticalScroll 只放开 maxHeight —— 把 constraints 原样传下去，每一行都会被撑成一屏，
        // 行与行之间变成整屏空白（真机上一屏只看得见一行）。Column 的测量策略同样会把子项的
        // minHeight 归零，这里必须照做。
        val rowConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(rowConstraints) }
        var total = 0
        placeables.forEach { total += it.height }
        if (rowTops.size < placeables.size) {
            while (rowTops.size < placeables.size) rowTops.add(0f)
        }
        var y = 0
        for (index in placeables.indices) {
            rowTops[index] = y.toFloat()
            y += placeables[index].height
        }
        layout(constraints.maxWidth, total) {
            var top = 0
            for (placeable in placeables) {
                placeable.place(0, top)
                top += placeable.height
            }
        }
    }
}


/** 剔除时上下各多留一点：阴影 / 描边 / 圆角会画出布局边界 */
internal const val CULL_OVERDRAW_PX = 96

/**
 * 剔除窗口的滚动台阶（px）：滚动位置只在跨过台阶时才让行失效重画。
 * 台阶越大、每帧失效的行越少，但窗口边界外多画的余量也越大（见 [CulledRow]）。
 */
internal const val CULL_STEP_PX = 128

/** 视口高度的取整台阶（px）：键盘 / 输入区动画里每帧变的是几十像素，取整后只换几次窗口 */
private const val CULL_VIEWPORT_STEP_PX = 64
