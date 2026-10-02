package com.adsh.app.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.jobs.Jobs
import com.adsh.app.ui.theme.DshPalette
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

/**
 * 后台任务列表（dsh-client-ui-jobs 的 JobListAction）：会话头部那一个触发器 + 弹层。
 *
 * 逐条对齐 dsh（第 118 轮）：
 *  - 一个任务都没有时**整个控件不出现**（没有常驻入口、没有数字角标）；
 *  - 触发器 = [运行中的转圈] + 计数文案 + 12dp 倒角（打开时转 180°）；
 *  - 弹层分两组：进行中（标题「进行中」）/ 已结束（标题是折叠按钮 + 「清空」），
 *    纯已结束列表默认展开，有运行中任务时默认折叠；「清空」只在客户端隐藏（注册表不动）；
 *  - 运行中的行是**两行卡片**（label 一行；kind + detail + 时长一行），已结束的行是单行；
 *  - 状态点：运行中是转圈，已完成绿、停止/取消黄、失败红；
 *  - 可展开判据 = 还活着，或还留着输出（output.total > 0）；展开时才读输出、收起即停；
 *  - 停止按钮两段式：第一下 arm（3 秒），第二下才真的 kill；人类 kill **不认领投递**，
 *    所以完成通知照发（模型会知道用户停了它的任务 —— 这是 dsh 有意为之）。
 */
@Composable
fun JobsControl(
    conversationId: Long?,
    onReaderAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalDshPalette.current
    val roster by Jobs.roster.collectAsState()
    val mine = roster.filter { it.owner == conversationId }
    val live = mine.filter { !it.status.terminal }.sortedBy { it.startedAt }
    var open by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(emptySet<String>()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var armKill by remember { mutableStateOf<String?>(null) }
    var killFailed by remember { mutableStateOf<String?>(null) }
    var settledOpen by remember { mutableStateOf<Boolean?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val settled = mine.filter { it.status.terminal && it.id !in cleared }
        .sortedByDescending { it.finishedAt ?: it.startedAt }

    // dsh：可见行数归零时整个控件消失（弹层也要收起来，别把焦点留在被卸载的节点上）
    val empty = live.isEmpty() && settled.isEmpty()
    LaunchedEffect(empty) { if (empty) open = false }
    if (empty) return

    val settledExpanded = settledOpen ?: live.isEmpty()
    // arm 的 3 秒窗口与「停止失败」的 4 秒提示，都按状态切换清理（dsh 的 KILL_ARM_MS / KILL_FAILED_MS）
    LaunchedEffect(armKill) { if (armKill != null) { delay(KILL_ARM_MS); armKill = null } }
    LaunchedEffect(killFailed) { if (killFailed != null) { delay(KILL_FAILED_MS); killFailed = null } }
    // 时钟只在「弹层打开且存在运行中行」时每秒跳一次（dsh 的 setInterval）
    LaunchedEffect(open, live.isNotEmpty()) {
        while (open && live.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val cardWidth = minOf(LocalConfiguration.current.screenWidthDp.dp - 32.dp, 420.dp)
    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    now = System.currentTimeMillis()
                    open = !open
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (live.isNotEmpty()) JobStateDot(Jobs.Status.RUNNING, size = 13.dp)
            Text(
                text = if (live.isNotEmpty()) live.size.toString() + " 个后台任务运行中" else settled.size.toString() + " 个后台任务",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
            )
            Icon(
                imageVector = DshIcons.ChevronDown,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(12.dp).rotate(if (open) 180f else 0f),
            )
        }
        if (open) {
            DshPopup(onDismiss = { open = false }, below = true, gap = 5.dp) {
                DshMenuCard(modifier = Modifier.width(cardWidth).heightIn(max = 440.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(3.dp),
                    ) {
                        if (live.isNotEmpty()) {
                            JobSectionHeader("进行中")
                            live.forEach { job ->
                                JobRow(
                                    job = job,
                                    live = true,
                                    now = now,
                                    armed = armKill == job.id,
                                    killFailed = killFailed == job.id,
                                    expanded = expandedId == job.id,
                                    onToggle = {
                                        onReaderAction()
                                        expandedId = if (expandedId == job.id) null else job.id
                                    },
                                    onArmKill = { armKill = job.id },
                                    onConfirmKill = {
                                        armKill = null
                                        val failed = runCatching {
                                            Jobs.kill(job.id, job.owner, "cancelled by the user")
                                        }.isFailure
                                        if (failed) killFailed = job.id
                                    },
                                )
                                if (expandedId == job.id) JobOutputPanel(job)
                            }
                        }
                        if (settled.isNotEmpty()) {
                            JobSectionHeader(
                                text = "已结束 " + settled.size,
                                chevronRight = !settledExpanded,
                                onToggle = { settledOpen = !settledExpanded },
                                action = "清空",
                                onAction = {
                                    cleared = cleared + settled.map { it.id }
                                    expandedId = null
                                },
                            )
                            if (settledExpanded) {
                                settled.forEach { job ->
                                    JobRow(
                                        job = job,
                                        live = false,
                                        now = now,
                                        armed = false,
                                        killFailed = false,
                                        expanded = expandedId == job.id,
                                        onToggle = {
                                            onReaderAction()
                                            expandedId = if (expandedId == job.id) null else job.id
                                        },
                                        onArmKill = {},
                                        onConfirmKill = {},
                                    )
                                    if (expandedId == job.id) JobOutputPanel(job)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 弹层里的点击一律**没有涟漪**（dsh 的菜单行就是这样：只有 hover 底色，触屏没有 hover）。
 * 用 `clickable(onClick)` 的默认写法在真机上是一圈会扩散的气泡（用户点名不要）。
 */
@Composable
private fun Modifier.tap(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** arm 的窗口（dsh 的 KILL_ARM_MS） */
private const val KILL_ARM_MS = 3_000L

/** 「停止失败」提示的停留时间（dsh 的 KILL_FAILED_MS） */
private const val KILL_FAILED_MS = 4_000L

/** 观察文本的尾部上限（dsh 客户端的 RENDER_TAIL_LIMIT = 128Ki UTF-16 单元，从头部截断） */
private const val RENDER_TAIL_CHARS = 128 * 1024

/** 分组标题：左按钮（可选倒角 + 文案）、右按钮（清空） */
@Composable
private fun JobSectionHeader(
    text: String,
    chevronRight: Boolean = false,
    onToggle: (() -> Unit)? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalDshPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(if (onToggle != null) Modifier.tap(onToggle) else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (onToggle != null) {
                Icon(
                    imageVector = DshIcons.ChevronDown,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(12.dp).rotate(if (chevronRight) -90f else 0f),
                )
            }
            Text(text, fontSize = 11.sp, lineHeight = 16.sp, color = palette.labelTertiary)
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                text = action,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.labelTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .tap(onAction)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** 一行任务：运行中两行卡片、已结束单行（dsh 的 rowLineLive / rowSettled） */
@Composable
private fun JobRow(
    job: Jobs.View,
    live: Boolean,
    now: Long,
    armed: Boolean,
    killFailed: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onArmKill: () -> Unit,
    onConfirmKill: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val duration = durationText(if (live) now - job.startedAt else (job.finishedAt ?: job.startedAt) - job.startedAt)
    val detail = job.detail
    val observable = job.observable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (live) Modifier.background(palette.bgLayer2) else Modifier)
            .heightIn(min = 28.dp)
            .tap(onToggle, enabled = observable)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        JobStateDot(job.status, size = if (live) 13.dp else 10.dp)
        if (live) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = job.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    JobKindBadge(job.kind)
                    if (detail != null) {
                        // dsh 的 .status 是 flex:none（时长同样 flex:none）：两者都由外层按内容
                        // 先占位，状态文案只吃**剩下的**那一段，绝不会把时长压成 0 宽。
                        // fill = false：文字短就是自己的宽度，不占满分到的那一段（不留空档）。
                        Text(
                            text = detail,
                            fontSize = 10.sp,
                            lineHeight = 16.sp,
                            color = palette.labelTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text(duration, fontSize = 11.sp, lineHeight = 18.sp, color = palette.labelTertiary)
                }
            }
            KillButton(armed = armed, failed = killFailed, onArm = onArmKill, onConfirm = onConfirmKill)
        } else {
            JobKindBadge(job.kind)
            // dsh 的 .label（flex:1）+ .status（flex:none）在**同一段弹性空间**里：这一段的宽度
            // 由外层（时长 / 倒角先占位）决定，状态文案再长也挤不掉右侧的时长与倒角。
            // 少了外面这层 Row，状态就是外层的直接子节点 —— 它按内容把剩余宽度吃光，时长被压成
            // 0 宽：真机上「5秒」竖着折成两行、倒角被顶出弹层（用户第 119 轮截图）。
            // 里面：状态先按自己的宽度占位（上限 = 这一段），命令行占剩下的并省略号。
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = job.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = detail ?: statusLabel(job.status),
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(duration, fontSize = 11.sp, lineHeight = 18.sp, color = palette.labelTertiary)
            if (observable) {
                Icon(
                    imageVector = DshIcons.ChevronDown,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(12.dp).rotate(if (expanded) 180f else -90f),
                )
            }
        }
    }
}

/** kind 徽标（dsh 的 .kind：10px、药丸底） */
@Composable
private fun JobKindBadge(kind: String) {
    val palette = LocalDshPalette.current
    Text(
        text = kind,
        fontSize = 10.sp,
        lineHeight = 16.sp,
        color = palette.labelSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(palette.hover)
            .padding(horizontal = 5.dp),
    )
}

/** 状态点：运行中是转圈，终态是实心点（dsh 的 StateDot 配色） */
@Composable
private fun JobStateDot(status: Jobs.Status, size: Dp) {
    val palette = LocalDshPalette.current
    val color = statusColor(status, palette)
    if (status == Jobs.Status.RUNNING) {
        // 一圈 270° 的弧 + 1.5s 匀速旋转（dsh 的 ongoing 转圈）
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "job-dot")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 1500,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            ),
            label = "job-dot-angle",
        )
        Canvas(Modifier.size(size).rotate(angle)) {
            val stroke = 1.5.dp.toPx()
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke),
            )
        }
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.6f).clip(CircleShape).background(color))
        }
    }
}

private fun statusColor(status: Jobs.Status, palette: DshPalette): androidx.compose.ui.graphics.Color =
    when (status) {
        Jobs.Status.RUNNING -> palette.labelSecondary
        Jobs.Status.COMPLETED -> palette.success
        Jobs.Status.STOPPING, Jobs.Status.KILLED -> palette.warnLabel
        Jobs.Status.FAILED -> palette.errorLabel
    }

private fun statusLabel(status: Jobs.Status): String = when (status) {
    Jobs.Status.RUNNING -> "运行中"
    Jobs.Status.STOPPING -> "正在停止"
    Jobs.Status.COMPLETED -> "已完成"
    Jobs.Status.KILLED -> "已取消"
    Jobs.Status.FAILED -> "已失败"
}

/** dsh 的 duration.seconds / .minutes / .hours 三档文案 */
private fun durationText(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> (hours.toString() + "小时" + minutes + "分")
        minutes > 0 -> (minutes.toString() + "分" + (seconds % 60) + "秒")
        else -> (seconds.toString() + "秒")
    }
}

/** 停止按钮（两段式：第一下 arm 成红色确认胶囊，第二下才真的 kill） */
@Composable
private fun KillButton(armed: Boolean, failed: Boolean, onArm: () -> Unit, onConfirm: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    if (armed) {
        Row(
            modifier = Modifier
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.errorLabel.copy(alpha = 0.12f))
                .border(1.dp, palette.errorLabel.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onConfirm)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(DshIcons.Stop, null, tint = palette.errorLabel, modifier = Modifier.size(10.dp))
            Text("确认停止", fontSize = 11.sp, lineHeight = 18.sp, color = palette.errorLabel, maxLines = 1)
        }
    } else {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.menu)
                .border(0.5.dp, palette.borderL2, RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onArm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DshIcons.Stop,
                contentDescription = if (failed) "停止失败" else "停止任务",
                tint = if (failed) palette.errorLabel else palette.labelSecondary,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/**
 * 展开后的输出面板：**只读观察**（Jobs.readAt，绝不动模型游标），120ms 一拍，收起即停。
 *
 * 与 dsh 的两处一致：渲染文本只留尾部 128Ki 字符；**不自动滚底**（原版的 TerminalBlock 没有
 * 任何 scrollTop/scrollIntoView 逻辑）。丢字节时在正文上方补一行 dsh 的提示。
 */
@Composable
private fun JobOutputPanel(job: Jobs.View) {
    val palette = LocalDshPalette.current
    var text by remember(job.id) { mutableStateOf("") }
    var cursor by remember(job.id) { mutableStateOf(job.earliest) }
    // dsh 的 lossy 判据之一：**打开时**游标就落在保留窗口之后（`from < output.earliest`）。
    // 少了它，环早就淘汰过头部时面板会把「保留的那一段」当成开头，看起来像凭空冒出一列 A。
    var dropped by remember(job.id) { mutableStateOf(job.earliest > 0) }
    LaunchedEffect(job.id) {
        while (true) {
            val read = runCatching { Jobs.readAt(job.id, cursor, job.owner) }.getOrNull()
            if (read != null) {
                if (read.chunks.isNotEmpty()) {
                    text = (text + read.chunks.joinToString("") { it.text }).takeLast(RENDER_TAIL_CHARS)
                }
                cursor = read.next
                if (read.lossy || read.chunks.any { it.gapBefore }) dropped = true
            }
            delay(120)
        }
    }
    val blank = text.isBlank()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        if (dropped) {
            Text(
                "……较早的输出已丢弃……",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.labelTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        // 运行中还没有任何输出时不画正文区（dsh）；已结束没输出写「（无输出）」
        if (!blank || job.status.terminal) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 288.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.codeBlock),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 288.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        text = if (blank) "（无输出）" else text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 22.sp,
                        color = palette.labelPrimary,
                    )
                }
            }
        }
    }
}
