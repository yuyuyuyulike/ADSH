package com.adsh.app.ui.blocks

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片解码的唯一入口：**按最长边**做 2 的幂下采样（内存只与最长边有关）。判据必须统一 ——
 * 按「宽高都 >= 目标」缩的写法对极端长宽比不缩：4000×200 的长截图会整张解码（3.2MB 位图）
 * 只为画一个 72dp 的方格。解码失败（不是图片 / 内存不足）一律返回 null，由调用方决定兜底。
 */
internal fun decodeSampledImage(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

/**
 * 内嵌图（OOXML 的 media 部件）那一路：字节已经在内存里，不必先落盘再 decodeFile。
 * 与 [decodeSampledImage] 共用同一套采样口径，失败一律 null。
 */
internal fun decodeImageBytes(data: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
    }
    BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
}.getOrNull()

/** 2 的幂下采样：内存只与**最长边**有关（解码口径见文件注释） */
private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/**
 * 图片缓存（按字节计价，32MB；key = 路径@目标像素）。
 *
 * 为什么必须有：滚动时同一张图会反复进出视口，没有缓存就是每次重新 decodeFile 一张 960px 的
 * 整图（约 3.7MB 位图），滑起来肉眼可见地掉帧。dsh 的图片节点是「解码一次、按节点缓存」，
 * 这里等价地按「路径 + 目标像素」缓存住。
 */
private val imageBitmaps = object : android.util.LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        value.width.coerceAtLeast(1) * value.height.coerceAtLeast(1) * 4
}

/** 带缓存的 [decodeSampledImage]（滚动路径都用它；大图预览那种一次性解码直接调上面那个） */
internal fun cachedImage(path: String, targetPx: Int): ImageBitmap? {
    val key = path + "@" + targetPx
    imageBitmaps.get(key)?.let { return it }
    val decoded = decodeSampledImage(path, targetPx) ?: return null
    imageBitmaps.put(key, decoded)
    return decoded
}

/**
 * 路径 → 位图（IO 线程 + 缓存）—— 组合层的入口：滚动路径上要「IO 线程解码 + 缓存」的，
 * 直接 `by rememberImage(path, 目标像素)`。
 */
@Composable
fun rememberImage(path: String, targetPx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, path, targetPx) {
        value = withContext(Dispatchers.IO) { cachedImage(path, targetPx) }
    }

/** 字节 → 位图（文档预览里的内嵌图那一类：同一份字节只解一次） */
@Composable
internal fun rememberImageBytes(data: ByteArray, targetPx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, data) {
        value = withContext(Dispatchers.IO) { decodeImageBytes(data, targetPx) }
    }

/** 是不是图片：按**魔数**判（与发出去之后消息里那一行同一套），不看扩展名 */
fun isImagePath(path: String): Boolean =
    runCatching { com.adsh.app.seam.model.imageMediaTypeOf(java.io.File(path)) != null }.getOrDefault(false)
