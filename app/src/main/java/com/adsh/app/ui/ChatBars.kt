package com.adsh.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.tools.Answer
import com.adsh.app.core.tools.ApprovalChannel
import com.adsh.app.core.tools.PlanReview
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.core.tools.UserQuestionChannel
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

// 会话页的浮条与空态（从 ChatScreen 搬出来的最后一组：彼此成组，对外只有调用点）

private const val HERO_HEADLINE = "探索未至之境"
private const val HERO_BADGE = "预览版"

/**
 * 「回到底部」上面那两个快捷导航按钮：与 dsh 的 toBottomSlot **同一个形状**
 * （34dp 圆形、圆角 100、menu 底、.5px border-l3、图标 16dp），只换图标与语义。
 */
@Composable
internal fun RoundRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    /** 图标旋转角度（「回会话顶部」就是把底部那个倒角转 180°） */
    iconRotation: Float = 0f,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(palette.menu)
            .border(0.5.dp, palette.borderL3, RoundedCornerShape(100.dp))
            .dshClickable(interactionSource = dshInteraction()) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = palette.labelPrimary,
            modifier = Modifier.size(16.dp).rotate(iconRotation),
        )
    }
}

/**
 * dsh 的「回到底部」（.EvIC1a_toBottom + chat.toBottom）：
 * 34px 圆形、100px 圆角、浮在内容右下角（bottom 16px）、图标是 ChevronDown14，
 * 只在不在底部时出现；点一下瞬移到底（不做动画滚动）。
 */
@Composable
internal fun BackToBottomButton(onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(palette.menu)
            .border(0.5.dp, palette.borderL3, RoundedCornerShape(100.dp))
            .dshClickable(interactionSource = dshInteraction()) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            DshIcons.ChevronDown,
            contentDescription = "回到底部",
            tint = palette.labelPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** dsh 的 /compact 进行中提示 */
@Composable
internal fun CompactingBar() {
    val palette = com.adsh.app.ui.theme.LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.Xxl)
            .padding(bottom = DshSpacing.Lg)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.menu)
            .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        Text("正在压缩上下文…", fontSize = 12.sp, color = palette.labelSecondary)
    }
}

/**
 * 排队发送的可见交代（dsh 的 queue chip）：运行中按下的消息正排着队，
 * 这一轮结束就发出去。点一下清空队列。
 */
@Composable
internal fun QueuedBar(count: Int, onClear: () -> Unit) {
    val palette = com.adsh.app.ui.theme.LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.Xxl)
            .padding(bottom = DshSpacing.Lg)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.menu)
            .dshClickable(interactionSource = dshInteraction(), onClick = onClear)
            .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        Text("已排队 " + count + " 条，本轮结束后发出", fontSize = 12.sp, color = palette.labelSecondary)
        Spacer(Modifier.weight(1f))
        Text("清空", fontSize = 12.sp, color = palette.labelTertiary)
    }
}

/**
 * 掉线重连条（dsh 的 ConnectionIndicator：断开 / 连接中 / 已恢复三态）。
 *
 * 形状与令牌**逐条**取自 dsh 的 `ConnectionIndicator.module.css`：
 *  - 28px 高、8px 圆角（`--dsw-radius-sm`）、左右各 8px 内边距、图标与文案间距 4px；
 *  - 12px / 字重 500 / 行高 18px 的字；
 *  - warning 态 = `state-warn-tertiary` 底 + `state-warn-label` 字 + 同色 20% 描边（可点）；
 *    success 态 = `state-success-tertiary` 底 + `state-success-primary` 字 + 同色 20% 描边（不可点）；
 *  - 图标：连接中 = dsh 的 `StateDot ongoing`（转圈），断开 = 刷新图形，已恢复 = 对勾；
 *  - **文案自己写着动作**（dsh 没有额外的「点击重试」提示），连接中的点接在文案后面、
 *    宽度固定 1em（dsh 的 `.dots`）—— 点长出来时整条不会跟着抖。
 */
@Composable
internal fun ConnectionBar(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val recovered = state is ConnectionState.Recovered
    val connecting = state is ConnectionState.Reconnecting
    val tint = if (recovered) palette.success else palette.warnLabel
    val background = if (recovered) palette.successBg else palette.warnBg
    val shape = RoundedCornerShape(8.dp)          // dsh 的 --dsw-radius-sm = 8px
    // 文案自己写着动作（dsh 的三条文案同理），所以不需要另加「点击重试」那一行
    val label = when (state) {
        is ConnectionState.Recovered -> "连接已恢复"
        is ConnectionState.Disconnected -> "连接已断开，点此重试"
        is ConnectionState.Reconnecting -> "正在重连（第 " + state.attempt + " 次）"
        ConnectionState.Idle -> ""
    }
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, tint.copy(alpha = 0.2f), shape)
            .then(
                if (recovered) {
                    Modifier
                } else {
                    Modifier.dshClickable(interactionSource = dshInteraction(), onClick = onRetry)
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            recovered -> Icon(
                DshIcons.Check,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            connecting -> DshStateDot("ongoing")
            else -> Icon(
                DshIcons.Refresh,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
        // 连接中的点：宽度固定（dsh 的 .dots 是 1em），所以点长出来时这条不会抖
        if (connecting) LoadingDots(tint)
    }
}

/**
 * 「连接中」的一至三个点，每 500ms 推进一格（dsh 的 ongoing loader：
 * 「一至三个点以独立于 retry 时序的 500ms 节奏推进」）。
 */
@Composable
internal fun LoadingDots(tint: Color) {
    val step by produceState(1) {
        while (true) {
            kotlinx.coroutines.delay(500)
            value = value % 3 + 1
        }
    }
    Text(
        text = ".".repeat(step).padEnd(3, ' '),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = tint,
        modifier = Modifier.width(12.dp),
    )
}

/** 检查点卡片：历史被压缩后的摘要，默认折叠 */@Composable
internal fun CompactCard(text: String, onReaderAction: () -> Unit = {}) {
    val palette = com.adsh.app.ui.theme.LocalDshPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val body = text.substringAfter("<compacted-summary>", text).substringBefore("</compacted-summary>").trim()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.menu)
            .dshClickable(interactionSource = dshInteraction()) {
                onReaderAction()
                expanded = !expanded
            }
            .padding(DshSpacing.Xxxl),
    ) {
        // dsh 的 CompactionItem 文案：message.compaction =「上下文已压缩」，
        // message.compaction.expand =「点击查看压缩摘要」
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
            Text("上下文已压缩", fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起摘要" else "点击查看压缩摘要",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelCaption,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = palette.labelSecondary,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** dsh 的空态：鲸鱼 + 「探索未至之境」+「预览版」 */
@Composable
internal fun Hero() {
    Column(
        Modifier.fillMaxSize().padding(bottom = 72.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DshWhale(width = 62.dp, tint = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl)) {
            Text(
                text = HERO_HEADLINE,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = HERO_BADGE,
                    modifier = Modifier.padding(horizontal = DshSpacing.Lg, vertical = DshSpacing.Xs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Lg),
    ) {
        Row(
            Modifier.padding(start = DshSpacing.Xxxl, end = DshSpacing.Md, top = DshSpacing.Xs, bottom = DshSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("忽略") }
        }
    }
}

/**
 * 顶栏：会话统计（点开是统计浮窗）/ 上下文占用 / 后台任务 / 工作区文件。
 *
 * R14 第三步从 ChatScreen 主函数搬出来（原文一字未改，只有 `overlays = ` 这三处写入改成
 * [onOverlaysChange] 回调）。只在有会话时出现 —— 条件留在调用点。顶栏空白处点一下也算
 * 「别处」：收起输入框的光标与键盘（子节点三个图标自己会消费掉点击）。
 */
@Composable
internal fun ChatTopBar(
    state: ChatUiState,
    overlays: ChatOverlays,
    onOverlaysChange: (ChatOverlays) -> Unit,
    draft: String,
    readerAction: () -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = DshSpacing.Section)
            // 顶栏的空白处也算「别处」：点一下就收起输入框的光标与键盘。
            // 子节点（三个图标）自己会消费掉点击，所以这里只会接住落在空白上的那一下。
            .pointerInput(Unit) {
                detectTapGestures { focusManager.clearFocus() }
            },
        verticalAlignment = Alignment.CenterVertically,
        // 第 117 轮：右上角那个图标在 Spacer 之后，改这一行的间距只会让「上下文」
        // 那一块再左移 8（用户点名「上下文窗口的图标再整块左移 8」）。
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        // dsh 会话统计入口（IconGaugeOutline16），点开是统计弹窗（不再整页跳转）
        Box {
            // 第 117 轮用户要求：顶栏三个图标**各小一点点**（20 → 18）；Gauge 的线宽也跟着
            // 调细（见 DshIcons.Gauge），顶栏看起来才不是一排水桶。
            IconTap(DshIcons.Gauge, "会话统计与 Token 用量", size = 18.dp, tint = palette.labelPrimary) {
                onOverlaysChange(overlays.statsToggled())
            }
            if (overlays.statsOpen) {
                DshPopup(
                    onDismiss = { onOverlaysChange(overlays.copy(statsOpen = false)) },
                    alignStart = true,
                    below = true,
                ) { SessionStatsPanels(state.stats) }
            }
        }
        // 上下文占用紧挨在会话统计右侧（dsh 的 ContextMeter 就是「会话统计右侧的圆环 +
        // 百分比」）。放在这里还有一个副作用是想要的：它不再出现在输入框里，
        // 于是会话一开始（上下文用量可用）时，模型按钮不会被它顶走一格。
        // （state.context 不是可空的：没有用量时它就是一个 0 用量的默认值，
        //  这里原先写的 `state.context?.let` 恒等于直接调用。）
        ContextMeter(
            usage = state.context,
            open = overlays.composerMenu == "context",
            below = true,
            // 打开时顺手收掉统计浮窗与触发菜单 —— 互斥规则在 ChatOverlays.menuChanged 里
            onOpenChange = { open ->
                onOverlaysChange(overlays.menuChanged(if (open) "context" else null, draft))
            },
        )
        Spacer(Modifier.weight(1f))
        // dsh 的会话头部 actions 区：后台任务列表（第 118 轮）。**没有任务时整个控件不出现**
        // —— 与 dsh 的 JobListAction 一样，不为一个没用到的能力常驻一个入口。
        JobsControl(
            conversationId = state.conversationId,
            onReaderAction = readerAction,
        )
        // 右上角只保留「工作区文件预览」（dsh 的 IconPanelLeftOutline16 镜像 = 分栏线在右）
        IconTap(DshIcons.PanelRight, "工作区文件", size = 18.dp, tint = palette.labelPrimary) { onOpenWorkspaceFiles() }
    }
}

/**
 * 输入框上方那一摞：错误条 / 提问卡 / 计划待审卡 / 审批卡 / 压缩条 / 排队条 / 待办 dock /
 * 轮次状态行 / 掉线重连条。R14 第三步从 ChatScreen 主函数搬出来，**原文一字未改**。
 *
 * 顺序就是屏幕上的竖列顺序；整摞的测量高度由 ChatScreen 那层 `Modifier.layout` 拿去补贴底
 * （输入框自己长高时本屏不重组，只能靠那一层；见 [ChatScrollState.chromeMeasured]）。
 */
@Composable
internal fun ChatBottomBars(
    state: ChatUiState,
    question: UserQuestionChannel.Pending?,
    approval: ApprovalChannel.Pending?,
    todos: List<TodoItem>,
    onClearError: () -> Unit,
    onAnswer: (List<Answer>) -> Unit,
    onSkipQuestion: () -> Unit,
    onAllowApproval: () -> Unit,
    onRejectApproval: () -> Unit,
    onClearQueued: () -> Unit,
    onRetryConnection: () -> Unit,
) {
    state.error?.let { message -> ErrorBar(message = message, onDismiss = onClearError) }

    // 提问卡放在输入框**上面**，而不是替掉输入框（dsh 里提问卡就是会话流里的一个节点，
    // 输入框一直在）。之前是二选一：AI 一提问，输入框连同焦点、键盘、正在组合的拼音
    // 一起被拆掉 —— 用户看到的就是「打字打一半键盘被抢走、回车把拼音变成字母」。
    if (question != null) {
        // dsh 的 PlanReviewPanel：intent 是 plan-review 的问题渲染成「计划待审」卡
        val review = question.questions.firstOrNull()?.takeIf { it.intent == PlanReview.KIND }
        if (review != null) {
            PlanReviewCard(
                plan = review.detail.orEmpty(),
                onApprove = { onAnswer(listOf(Answer(review.id, listOf(PlanReview.APPROVE)))) },
                onDecline = { onAnswer(listOf(Answer(review.id, listOf(PlanReview.KEEP_PLANNING)))) },
                onDiscuss = onSkipQuestion,
            )
        } else {
            QuestionCard(questions = question.questions, onAnswer = onAnswer, onSkip = onSkipQuestion)
        }
    }
    // 审批卡（dsh 的 ApprovalPanel .mna1RW_card）：越权请求要用户点一下才继续。
    // dsh 里它直接顶掉输入框（composer takeover）；手机上保持输入框在场（键盘/焦点不拆），
    // 卡片贴在输入框上方 —— 与提问卡、计划待审卡同一条竖列。
    approval?.let { pending ->
        ApprovalCard(
            toolName = pending.toolName,
            reason = pending.reason,
            detail = pending.detail,
            onAllow = onAllowApproval,
            onReject = onRejectApproval,
        )
    }
    if (question == null) {
        if (state.compacting) CompactingBar()
        if (state.queuedCount > 0) QueuedBar(count = state.queuedCount, onClear = onClearQueued)
        TodoDock(conversationId = state.conversationId, todos = todos)
        // dsh 的 TurnStatus：全程钉在输入框左上角（绑定文件夹那一行的位置），
        // 本轮输出完或被打断就随 sending 一起消失（计时跟着停），下一次发送重新起算；
        // 它在输入框上方的这一列里，所以呼出键盘时会跟着输入框一起上移。
        if (state.sending) {
            TurnStatusRow(
                startedAt = state.runStartedAt,
                modifier = Modifier.padding(start = DshSpacing.Section, bottom = DshSpacing.Xs),
            )
        }
    }
    // 掉线重连条（dsh 的 ConnectionIndicator）：断线 / 连接中 / 已恢复三态，
    // 钉在输入框上面那一列（与 TurnStatus 同一个位置）。它有话要说时**替换**掉
    // TurnStatus 的那一行 —— 两行一起挂着反而看不清现在到底是「在跑」还是「断了」。
    // 出现 / 消失都淡入淡出 150ms（dsh 的 `indicator-enter` / `.leaving` 也是 150ms）：
    // 退场期间内容还留着（AnimatedVisibility 会保留最后一帧），所以不会「啪」地消失。
    AnimatedVisibility(
        visible = state.connection !is ConnectionState.Idle,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
    ) {
        ConnectionBar(
            state = state.connection,
            modifier = Modifier.padding(start = DshSpacing.Section, bottom = DshSpacing.Xs),
            onRetry = onRetryConnection,
        )
    }
}
