package com.adsh.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adsh.app.ui.theme.LocalDshPalette

// ------------------------------------------------------------------ 消息

/**
 * 用户消息（dsh 的 UserStyleBubble / MessageItem.module.css）：
 *
 *  - 右对齐的一列：气泡在上、动作行在下，两者间距 8px（.Sixlwa_userStack gap:8px）；
 *    整列最大宽度 = min(内容宽 * .702, 82%)，手机上就是 82%；
 *  - 气泡：`--dsw-specific-bubble`（浅色 #EDF3FE / 深色 #2C2C2E，**不是**主色蓝）、
 *    圆角 22px、内边距 10px 16px、正文 14/22、颜色 label-primary；
 *  - 动作行（dsh 的 MessageIconActions，clock: "start"）：先时间（13/24 三级色、右侧 12px）
 *    再复制按钮（28×28 圆形、图标 15px，点一下变对勾，1 秒后还原）。
 *
 * internal（而不是 private）：插话（dsh 的 steering 节点）用的是**同一个气泡**，
 * 它作为一轮过程里的条目由 TurnRail 渲染。
 */
@Composable
internal fun UserMessage(
    content: String,
    time: Long,
    attachments: List<com.adsh.app.core.agent.UserAttachment> = emptyList(),
) {
    val palette = LocalDshPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            // dsh 的 .userStack：`max-width: min(0.702×列宽, 82%)` —— 是**上限**不是定宽，
            // 气泡本身按内容收缩（短消息就是一个窄胶囊），超出 82% 才折行。
            // 这里用「列宽 82% + 气泡对齐到 End」等价表达：Column 定宽不影响观感，
            // 气泡（Box）自己 wrap 内容 —— 前提是里面那层 Markdown 不 fillMaxWidth（见 Markdown.kt）。
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
        ) {
            // dsh 的 .attachmentRow：附件排在气泡**上面**（userStack 里 attachmentRow 在前），
            // 右对齐、间距 8、可换行
            if (attachments.isNotEmpty()) UserAttachmentRow(attachments)
            // dsh 的 showBubble：正文与附件至少有一个才画气泡
            if (content.isNotBlank()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.userBubble)
                        .padding(horizontal = DshSpacing.Card, vertical = DshSpacing.Xxl),
                ) {
                    // 对话正文也是 Markdown（dsh 的 MarkdownText 语义），不再原样吐 ## / ** / 表格
                    MarkdownBody(text = content)
                }
            }
            Row(
                modifier = Modifier.height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                UserClock(time)
                // 只发附件（没打字）时没有可复制的东西：不画那个按钮，免得点一下只换来一个空对勾
                if (content.isNotBlank()) UserCopyAction(content)
            }
        }
    }
}

/**
 * 用户消息的附件行（dsh 的 .Sixlwa_attachmentRow: flex-wrap + gap 8 + justify-content flex-end）。
 *
 * 图片走 dsh 的 MessageImage 画廊：**整条消息只有一张图**时铺开显示（宽度占满这一列），
 * 多于一个附件时退化成 64dp 的方格（dsh 的 compact = attachments.length > 1）；
 * 文件走 dsh 的 fileCard：240x64 的圆角卡片，前面一枚类型图标，右边文件名 + 「扩展名 大小」。
 */
@Composable
private fun UserAttachmentRow(attachments: List<com.adsh.app.core.agent.UserAttachment>) {
    val compact = attachments.size > 1
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        attachments.forEach { attachment ->
            if (attachment.isImage) {
                UserImageAttachment(attachment, compact)
            } else {
                UserFileCard(attachment)
            }
        }
    }
}

/**
 * 单张图片的最大显示尺寸。
 *
 * dsh 的 `.fNh4Da_image` 是 `max-width: min(100%, 1600px); max-height: calc(100vh - 80px)`，
 * 元素跟着图片自己的宽高比走、**不放大**（max-width 只是上限）。ADSH 原来写的是
 * `fillMaxWidth().heightIn(max = 320.dp)` + `ContentScale.Fit` —— 那个 Box 永远占满整行、
 * 高度永远顶到 320dp，横图上下留一大片空白、小图也被撑成一张大卡片，就是「面积太大」的来源。
 * 这里按 dsh 的语义来：宽度给到这一列的宽度、高度上限 320dp（手机上的合理值），
 * 按宽高比反推实际尺寸，小图保持原始大小。
 */
private val MESSAGE_IMAGE_MAX_HEIGHT = 320.dp

/**
 * 按原始像素尺寸算展示尺寸：只缩不放。
 * 图片的原始像素直接当 dp 用（dsh 的 <img> 不带 width/height，就是按自然像素渲染）。
 */
internal fun fitImageSize(
    naturalWidthPx: Int,
    naturalHeightPx: Int,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    if (naturalWidthPx <= 0 || naturalHeightPx <= 0) {
        return maxWidth to maxHeight
    }
    val scale = minOf(
        1f,
        maxWidth.value / naturalWidthPx.toFloat(),
        maxHeight.value / naturalHeightPx.toFloat(),
    )
    return (naturalWidthPx * scale).dp to (naturalHeightPx * scale).dp
}

/** dsh 的 MessageImage：单图按宽高比铺开（不放大），多图 64×64 方格 */
@Composable
private fun UserImageAttachment(
    attachment: com.adsh.app.core.agent.UserAttachment,
    compact: Boolean,
) {
    val palette = LocalDshPalette.current
    val targetPx = if (compact) 160 else 960
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.path, targetPx) {
        value = withContext(Dispatchers.IO) { cachedAttachmentBitmap(attachment.path, targetPx) }
    }
    val frame = Modifier
        .clip(RoundedCornerShape(16.dp))
        .border(0.5.dp, palette.borderL2, RoundedCornerShape(16.dp))
        .background(palette.selector)
        // 点一下打开原图预览（dsh 的 lightbox：缩略图 → 原图）
        .dshClickable(interactionSource = dshInteraction()) { ImagePreviewState.open(attachment.path) }
    if (compact) {
        Box(frame.size(64.dp), contentAlignment = Alignment.Center) {
            AttachmentImageContent(bitmap, attachment, compact)
        }
        return
    }
    // 单图：先量出这一列能给的宽度，再按图片自己的宽高比算出实际尺寸
    androidx.compose.foundation.layout.BoxWithConstraints(frame) {
        val size = fitImageSize(
            naturalWidthPx = attachment.width.takeIf { it > 0 } ?: (bitmap?.width ?: 0),
            naturalHeightPx = attachment.height.takeIf { it > 0 } ?: (bitmap?.height ?: 0),
            maxWidth = maxWidth,
            maxHeight = MESSAGE_IMAGE_MAX_HEIGHT,
        )
        Box(Modifier.size(size.first, size.second), contentAlignment = Alignment.Center) {
            AttachmentImageContent(bitmap, attachment, compact)
        }
    }
}

/**
 * 图片本体（或加载失败时的文件名兜底）。
 * 尺寸已经由外层的 Box 定好，这里只负责「填满并等比内嵌」。
 */
@Composable
internal fun AttachmentImageContent(
    bitmap: ImageBitmap?,
    attachment: com.adsh.app.core.agent.UserAttachment,
    compact: Boolean,
) {
    val palette = LocalDshPalette.current
    Box(contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                contentScale = if (compact) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = attachment.name,
                modifier = Modifier.padding(DshSpacing.Xl),
                fontSize = 12.sp,
                color = palette.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** dsh 的 .Sixlwa_fileCard：240×64、圆角 16、.5px 边框、图标 28 + 文件名 + 扩展名与大小 */
@Composable
private fun UserFileCard(attachment: com.adsh.app.core.agent.UserAttachment) {
    val palette = LocalDshPalette.current
    val meta = fileMetaText(attachment)
    Row(
        modifier = Modifier
            .width(240.dp)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(0.5.dp, palette.borderL2, RoundedCornerShape(16.dp))
            .background(palette.inputMajor)
            .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = palette.labelSecondary,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** dsh 的 fileMeta 文案：扩展名（大写，最多 8 字）+ 文件大小 */
private fun fileMetaText(attachment: com.adsh.app.core.agent.UserAttachment): String {
    val dot = attachment.name.lastIndexOf('.')
    val extension = if (dot > 0 && dot < attachment.name.length - 1) {
        attachment.name.substring(dot + 1).uppercase().take(8)
    } else {
        ""
    }
    return listOf(extension, fileSizeText(attachment.bytes)).filter { it.isNotEmpty() }.joinToString(" ")
}

// 字节数文案统一走 ui/FileSizeText.kt 的 fileSizeText（dsh 的规则：<10 一位小数、≥10 取整、
// 数字与单位之间没有空格）。以前这里另有一份「恒一位小数 + 带空格」的近似实现，
// 同一个包里两个同名函数还会撞签名。

/**
 * 附件封面缓存（按字节数计价，32MB）。
 *
 * 之前每次滑回视口都会重新 decodeFile 一遍 960px 的整图（一张 = 约 3.7MB 位图），
 * 上下滑动遇到图片就明显掉帧。dsh 的图片节点是「解码一次、按节点缓存」，
 * 这里等价地按 path@目标像素 缓存住。
 */
private val attachmentBitmaps = object : android.util.LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        value.width.coerceAtLeast(1) * value.height.coerceAtLeast(1) * 4
}

/** 带缓存的附件封面解码 */
internal fun cachedAttachmentBitmap(path: String, targetPx: Int): ImageBitmap? {
    val key = path + "@" + targetPx
    attachmentBitmaps.get(key)?.let { return it }
    val decoded = loadAttachmentBitmap(path, targetPx) ?: return null
    attachmentBitmaps.put(key, decoded)
    return decoded
}

/** 附件封面：按目标像素做 2 的幂下采样（图片很大时避免整张解码进内存） */
private fun loadAttachmentBitmap(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

/** dsh 的 .xzv4MW_timeStart：13/24 三级色，右侧 12px 间距 */
@Composable
private fun UserClock(time: Long) {
    val palette = LocalDshPalette.current
    if (time <= 0L) return
    Text(
        text = formatMessageClock(time),
        modifier = Modifier.padding(end = DshSpacing.Xxxl),
        fontSize = 13.sp,
        lineHeight = 24.sp,
        color = palette.labelTertiary,
    )
}

/** dsh 的 MessageIconActions 里的复制按钮：点一下换成 IconCheckOutline16，1 秒后还原 */
@Composable
private fun UserCopyAction(text: String) {
    val context = LocalContext.current
    var copied by rememberCopiedFlag()
    RailIconAction(
        icon = if (copied) DshIcons.Check else DshToolIcons.Copy,
        label = if (copied) "复制成功" else "复制",
    ) {
        copyToClipboard(context, text)
        copied = true
    }
}

/**
 * dsh 的 formatMessageClock（中文字典 clock.md / clock.ymd）：
 * 同一天只给 HH:mm；同一年给「M月d日 HH:mm」；跨年给「y年M月d日 HH:mm」。
 */
private fun formatMessageClock(time: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
    val clock = String.format("%02d:%02d", then.get(java.util.Calendar.HOUR_OF_DAY), then.get(java.util.Calendar.MINUTE))
    if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        return clock
    }
    val month = then.get(java.util.Calendar.MONTH) + 1
    val day = then.get(java.util.Calendar.DAY_OF_MONTH)
    if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) {
        return month.toString() + "月" + day + "日 " + clock
    }
    return then.get(java.util.Calendar.YEAR).toString() + "年" + month + "月" + day + "日 " + clock
}

/**
 * 助手正文（dsh 的 AssistantMarkdown）。
 *
 * 流式期间按 ~33ms 采样重绘一次（与自动滚动同一节奏），避免每个 token 都重解析整篇 Markdown。
 * 注意不能写成 `LaunchedEffect(content) { delay(120); shown = content }` ——
 * 那样每次内容变化都会取消并重启延时，token 持续到达时节流永远不触发，
 * 结果整段文字在结束时一次性蹦出来（旧实现的「看不到流式渲染」）。
 */
@Composable
internal fun AssistantText(content: String) {
    // 不再用「流式走一条分支、定稿走另一条分支」的写法 —— 那样一段正文在定稿的瞬间会被整棵树
    // 重建（SelectionContainer 与 MarkdownBody 的层级变了），视觉上就是正文闪一下、行高重排一次。
    // 现在两条路径共用同一棵树；流式期间的重绘节流在**列表外面**做（rememberSampledStreaming）。
    SelectionContainer { MarkdownBody(text = content, modifier = Modifier.fillMaxWidth()) }
}
