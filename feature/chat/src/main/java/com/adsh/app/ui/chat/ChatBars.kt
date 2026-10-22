package com.adsh.app.ui.chat

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.seam.tool.Answer
import com.adsh.app.seam.tool.ApprovalChannel
import com.adsh.app.core.tools.PlanReview
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.seam.tool.UserQuestionChannel
import com.adsh.app.ui.blocks.DshIcons
import com.adsh.app.ui.blocks.dshVerticalScrollbar
import com.adsh.app.ui.blocks.lightCardShadow
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction

// 会话页的浮条与空态（从 ChatScreen 搬出来的最后一组：彼此成组，对外只有调用点）

private const val HERO_HEADLINE = "探索未至之境"
private const val HERO_BADGE = "预览版"

/**
 * dsh 的「回到底部」（.EvIC1a_toBottom + chat.toBottom）：
 * 34px 圆形、100px 圆角、浮在内容右下角（在 footer 之上 16dp，dsh 是 composerHeight + 16）、
 * 图标是 ChevronDown14，只在不在底部时出现；点一下瞬移到底（不做动画滚动）。
 */
@Composable
internal fun BackToBottomButton(onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .lightCardShadow(RoundedCornerShape(100.dp))
            .clip(RoundedCornerShape(100.dp))
            .background(palette.menu)
            .border(DshSpacing.Hairline, palette.borderL3, RoundedCornerShape(100.dp))
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
            .lightCardShadow(RoundedCornerShape(12.dp))
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
            .lightCardShadow(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
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
            val summaryScroll = rememberScrollState()
            Text(
                text = body,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = palette.labelSecondary,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .dshVerticalScrollbar(summaryScroll, elevated = true)
                    .verticalScroll(summaryScroll),
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
 * 顶栏：**会话标题**（左上角）/ 后台任务 / 工作区文件。
 *
 * 会话统计、Token 用量与上下文占用都在输入框**下方**那一排（dsh 的 composer dock，见
 * [ChatStatsDock]）—— 顶栏只留标题与右侧两个入口。只在有会话时出现，条件留在调用点。
 * 顶栏空白处点一下也算「别处」：收起输入框的光标与键盘（子节点自己会消费掉点击）。
 * **双击这一行 = 回到会话开头**（滚到顶，并交出自动跟随）；单击仍只收输入状态。
 */
@Composable
internal fun ChatTopBar(
    title: String,
    conversationId: Long?,
    readerAction: () -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
    /** 双击标题那一行：回到会话开头（滚到顶；调用方负责交出自动跟随） */
    onScrollToTop: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val palette = LocalDshPalette.current
    // **手势块不能钉死在第一次组合的回调上**：`pointerInput(Unit)` 的协程只启动一次，
    // 它捕获的闭包不会随重组更新 —— 切会话后仍会去滚上一条会话那份已经卸载的 ScrollState，
    // 表现就是「切换过会话后双击刘海完全没反应」。
    val scrollToTop by rememberUpdatedState(onScrollToTop)
    // 标题的宽度上限 = **半个屏幕**（用户口径）：再长就省略，不留到把右侧图标挤走。
    // 窗口宽度用 LocalWindowInfo 而不是 Configuration.screenWidthDp —— 分屏 / 折叠屏下后者是整块屏。
    val titleMaxWidth = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.width / 2).toDp()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = DshSpacing.Section)
            // 顶栏的空白处也算「别处」：点一下就收起输入框的光标与键盘。
            // 子节点自己会消费掉点击，所以这里只会接住落在空白上的那一下。
            .pointerInput(Unit) {
                // 带 onDoubleTap 时单击要等双击超时（约 300ms）才分发 —— 顶栏空白处的点击本来
                // 就只是「收输入状态」，这点延迟看不出来；换来的是双击回顶部。
                detectTapGestures(
                    onTap = { focusManager.clearFocus() },
                    onDoubleTap = {
                        focusManager.clearFocus()
                        scrollToTop()
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        // 会话标题（dsh 会话头部那一行 crumbs 的**当前**那一段：一级色、500、单行省略）。字体固定 15px，
        // **不跟会话内容字号走**（那是「只缩放会话内容」的档，见 dsh 的 --dsh-content-font-size）。
        // 「新会话」换成真标题是**直接换字**，不要任何特效动画；宽度上限挂在这一层，超长照样单行省略。
        Text(
            text = title,
            modifier = Modifier.widthIn(max = titleMaxWidth),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        // dsh 的会话头部 actions 区：后台任务列表。**没有任务时整个控件不出现** —— 不为一个没用到的
        // 能力常驻一个入口（与 dsh 的 JobListAction 一样）。
        JobsControl(
            conversationId = conversationId,
            onReaderAction = readerAction,
        )
        // 右上角只保留「工作区文件预览」，几何照 dsh 的 ExpandButton（ui-sidebar-right
        // ExpandButton.module.css）：28px 按钮、15px 图标、label-secondary ——
        // 原来 18dp + 一级色，比 dsh 大一圈也重一档。
        // 点开它也是离开输入状态：光标立刻消失（覆盖页会盖住输入框，光标不该在底下继续闪）。
        Box(
            modifier = Modifier
                .size(28.dp)
                .dshClickable(interactionSource = dshInteraction()) {
                    focusManager.clearFocus()
                    onOpenWorkspaceFiles()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DshIcons.PanelRight,
                contentDescription = "工作区文件",
                tint = palette.labelSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * 输入框上方那一摞：错误条 / 提问卡 / 计划待审卡 / 审批卡 / 压缩条 / 排队条 / 待办 dock / 轮次状态行，
 * 顺序就是屏幕上的竖列顺序。整摞的测量高度由 ChatScreen 那层 `Modifier.layout` 拿去补贴底（输入框自己
 * 长高时本屏不重组）。掉线重连条不在这里 —— 它在输入框那一行的右侧（见 DshComposer 的 connection 参数）。
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
) {
    state.error?.let { message -> ErrorBar(message = message, onDismiss = onClearError) }

    // 提问卡放在输入框**上面**，不替掉输入框（dsh 里提问卡是会话流里的一个节点，输入框一直在）：
    // 替掉会把焦点 / 键盘 / 正在组合的拼音一起拆掉。
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
        // 「吃白饭中」那一行（dsh 的 RunningStatus）**不在这里**：dsh 把它挂在会话流末尾
        // （ChatFlow: [...rows, ...running ? [<RunningStatus/>] : []]），见 ChatTranscript。
        // 放在 footer 会让它落在顶部 36dp 的渐隐带里，文字看起来是透明的。
    }
    // 掉线重连条**不在这里**：按用户口径挪到输入框那一行的右侧了（模型选择器与发送键之间，
    // 见 DshComposer 的 connection 参数与 dsh 的 conversation.input.activity 槽）。
}
