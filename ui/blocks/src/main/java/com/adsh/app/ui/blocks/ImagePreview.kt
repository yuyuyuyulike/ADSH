package com.adsh.app.ui.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 原图预览的最长边上限（下采样解码见 ui/ImageDecode.kt：全套只有那一份实现） */
private const val PREVIEW_MAX_PX = 2048

// ------------------------------------------------------------------------------------------------
// 图片：12dp inset + 12dp 圆角 + 底部常显缩放胶囊（fit-width 默认不放大）
// ------------------------------------------------------------------------------------------------

@Composable
internal fun ImagePreview(path: String, reloadKey: Int = 0) {
    val palette = LocalDshPalette.current
    var bitmap by remember(path, reloadKey) { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    /** null = 适应宽度（dsh 的 Fit width）；否则是相对原始像素宽的百分比（25..400） */
    var percent by remember(path, reloadKey) { mutableStateOf<Int?>(null) }

    LaunchedEffect(path, reloadKey) {
        bitmap = null
        failure = null
        percent = null
        // 长边压到 2048 以内（共用实现，见 ui/ImageDecode.kt）：一次性的原图预览不进缓存
        val decoded = withContext(Dispatchers.IO) { decodeSampledImage(path, PREVIEW_MAX_PX) }
        bitmap = decoded
        failure = if (decoded == null) "cannot display this image" else null
    }

    val image = bitmap
    when {
        failure != null -> PreviewEmpty(path, failure!!)
        image == null -> PreviewLoading()
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val availableWidth = maxWidth
            val intrinsicWidth = with(density) { image.width.toDp() }
            val ratio = image.width.toFloat() / image.height.coerceAtLeast(1)
            // dsh 的 fit width 不放大比 pane 小的图；百分比档按图片固有像素宽算（100% = 原始 CSS 像素）
            val width = when (val value = percent) {
                null -> minOf(availableWidth, intrinsicWidth)
                else -> (intrinsicWidth * (value / 100f)).coerceIn(24.dp, availableWidth * 4f)
            }
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            // 横轴挂外层可视区：内层横向容器的高度随图片，竖图放大后挂在它底边的滚动条会滚出视口
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .dshVerticalScrollbar(vertical)
                    .dshHorizontalScrollbar(horizontal)
                    .verticalScroll(vertical),
            ) {
                if (width > availableWidth - 24.dp) {
                    Box(Modifier.horizontalScroll(horizontal).clipToBounds()) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.width(width).height(width / ratio).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.width(width).height(width / ratio).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            ZoomCapsule(
                percent = percent,
                onPercent = { percent = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            )
        }
    }
}

/** dsh 的缩放胶囊（图片 / PDF 共用）：bottom 14、padding 4 6、gap 2、0.5 borderL3、圆角 16、底 bgLayer1 */
@Composable
private fun ZoomCapsule(percent: Int?, onPercent: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val shape = RoundedCornerShape(16.dp)
    var menuOpen by remember { mutableStateOf(false) }
    val current = percent ?: 100
    Row(
        modifier = modifier
            .shadow(8.dp, shape)
            .clip(shape)
            .background(palette.bgLayer1)
            .border(0.5.dp, palette.borderL3, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoomStep("−", "缩小") { onPercent((current - 25).coerceIn(25, 400)) }
        Box {
            Text(
                text = if (percent == null) "适应宽度" else percent.toString() + "%",
                modifier = Modifier
                    .widthIn(min = 58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .dshClickable(interactionSource = dshInteraction()) { menuOpen = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelPrimary,
                textAlign = TextAlign.Center,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf<Pair<String, Int?>>(
                    "适应宽度" to null,
                    "25%" to 25,
                    "50%" to 50,
                    "100%" to 100,
                    "150%" to 150,
                    "200%" to 200,
                ).forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp) },
                        onClick = {
                            onPercent(value)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        ZoomStep("+", "放大") { onPercent((current + 25).coerceIn(25, 400)) }
    }
}

/** 胶囊里的 −/+：宽 28、高 28，字形 14；视觉 28dp，触点由外层胶囊留白补足 */
@Composable
private fun ZoomStep(label: String, description: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, color = palette.labelPrimary)
    }
}

/**
 * 点开一张图时的原图预览（dsh-client-ui-attachment 的 lightbox："Document-level original-image
 * preview opened by clicking a thumbnail"）：整屏黑底 + 缩放胶囊，右上角关闭。
 *
 * 对话里**任何**一张缩略图（用户附件、read_image 的结果）都打开这一个对话框，
 * 状态放在 [ImagePreviewState] 里 —— 图片散在消息/工具行的深处，逐层传 lambda
 * 只会把每一层都污染一遍。
 */
object ImagePreviewState {
    private val _path = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val path: kotlinx.coroutines.flow.StateFlow<String?> = _path.asStateFlow()

    fun open(path: String) {
        if (path.isNotBlank()) _path.value = path
    }

    fun close() {
        _path.value = null
    }
}

@Composable
fun ImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    val palette = LocalDshPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Compose 的 Dialog 自带一层系统 dim，这里自己画背景，所以把它压掉（与 DshModal 同一做法）
        androidx.compose.runtime.SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.bgLayer1)
                .dshClickable(interactionSource = dshInteraction(), onClick = onDismiss),
        ) {
            Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 40.dp)) {
                ImagePreview(path)
            }
            Text(
                text = File(path).name,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.menu.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 10.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = palette.labelSecondary,
                )
            }
        }
    }
}
