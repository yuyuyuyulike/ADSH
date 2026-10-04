package com.adsh.app.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 图片解码的唯一入口（第 182 轮熵减）。
 *
 * 以前这一套在**四个文件里各写了一遍**：Composer 的待发附件缩略图、UserMessages 的消息图、
 * DocumentPreview 的大图预览、TurnRail 的子调用图；缓存还挂在 UserMessages.kt 里，被 Markdown 与
 * TurnRail 隔着文件 import（一个「消息图」的私有缓存成了全 UI 的公共设施）。判据也分了两派：
 *
 *  - 缩略图那两处按「宽高**都** >= 目标」缩 —— 对极端长宽比不缩：4000×200 的长截图会整张解码
 *    （3.2MB 位图）只为画一个 72dp 的方格；
 *  - 大图预览按「长边 >= 上限」缩 —— 这个才是对的（内存只与最长边有关）。
 *
 * 现在只有一份：**按最长边**做 2 的幂下采样。三处的行为因此统一，长截图那类不再白烧内存。
 * 解码失败（不是图片 / 内存不足）一律返回 null，由调用方决定画什么兜底。
 */
internal fun decodeSampledImage(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / (sample * 2) >= targetPx) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

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

/** 是不是图片：按**魔数**判（与发出去之后消息里那一行同一套），不看扩展名 */
internal fun isImagePath(path: String): Boolean =
    runCatching { com.adsh.app.core.agent.imageMediaTypeOf(java.io.File(path)) != null }.getOrDefault(false)
