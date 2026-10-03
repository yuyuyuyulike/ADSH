package com.adsh.app.core.agent

/**
 * **图片怎么进请求**（从 Attachments.kt 拆出来的）：按 dsh 的 contentParts 把附件翻成模型看得懂的内容块 ——
 * 缩放到像素预算内、按路由决定发图片块还是纯文本占位，以及那条 user 消息怎么拼。
 *
 * 附件本身的表示与探测（含句柄文本）留在 Attachments.kt，工具结果里的图片在 ToolImages.kt。
 */

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** dsh 的 requestImageDimensions：等比缩到像素预算内，小图不放大 */
fun requestImageDimensions(width: Int, height: Int, maxPixels: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    val scale = min(1.0, sqrt(maxPixels.toDouble() / (width.toDouble() * height.toDouble())))
    if (scale >= 1.0) return width to height
    if (width >= height) {
        var projectedWidth = max(1, (width * scale).toInt())
        var projectedHeight = max(1, (projectedWidth.toDouble() * height / width).roundToInt())
        while (projectedWidth * projectedHeight > maxPixels && projectedWidth > 1) {
            projectedWidth -= 1
            projectedHeight = max(1, (projectedWidth.toDouble() * height / width).roundToInt())
        }
        return projectedWidth to projectedHeight
    }
    var projectedHeight = max(1, (height * scale).toInt())
    var projectedWidth = max(1, (projectedHeight.toDouble() * width / height).roundToInt())
    while (projectedWidth * projectedHeight > maxPixels && projectedHeight > 1) {
        projectedHeight -= 1
        projectedWidth = max(1, (projectedHeight.toDouble() * width / height).roundToInt())
    }
    return projectedWidth to projectedHeight
}

/**
 * 请求图片缓存：按 dsh 的 requestImages（attachment id + 策略的 variant）做进程内缓存。
 * 历史里的图片每一轮都要重新装配，没有缓存的话每轮都要解码 + 重新编码全部图片。
 */
/** dsh 的 DEFAULT_REQUEST_IMAGE_MAX_BYTES = 1 MiB：单张请求图片的编码上限 */
private const val REQUEST_IMAGE_MAX_BYTES = 1024 * 1024

/** dsh 的质量阶梯（IMAGE_ENCODING_QUALITIES）：85 / 75 / 60，取第一个进预算的 */
private val REQUEST_IMAGE_QUALITIES = intArrayOf(85, 75, 60)

private fun sampleSizeFor(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    var sample = 1
    var halfWidth = width
    var halfHeight = height
    while (halfWidth / 2 >= targetWidth && halfHeight / 2 >= targetHeight) {
        halfWidth /= 2
        halfHeight /= 2
        sample *= 2
    }
    return sample
}

private val requestImageCache = object : LinkedHashMap<String, RequestImage>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestImage>): Boolean = size > 8
}

/** 把一张图片编码成模型请求用的 data URL（dsh 的 prepareRequestImages + 质量阶梯） */
suspend fun requestImageOf(attachment: UserAttachment): RequestImage? = withContext(Dispatchers.IO) {
    if (!attachment.isImage) return@withContext null
    val key = attachment.path + "|" + attachment.bytes + "|" + attachment.sha256
    synchronized(requestImageCache) { requestImageCache[key] }?.let { return@withContext it }
    val encoded = runCatching { encodeRequestImage(attachment) }.getOrNull() ?: return@withContext null
    synchronized(requestImageCache) { requestImageCache[key] = encoded }
    encoded
}

private fun encodeRequestImage(attachment: UserAttachment): RequestImage? {
    val file = File(attachment.path)
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(attachment.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val (targetWidth, targetHeight) = requestImageDimensions(bounds.outWidth, bounds.outHeight, REQUEST_IMAGE_MAX_PIXELS)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
    }
    val decoded = BitmapFactory.decodeFile(attachment.path, options) ?: return null
    val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    }
    val hasAlpha = decoded.hasAlpha()
    // API 30 起 `WEBP` 被拆成 LOSSY / LOSSLESS（前者与旧的 WEBP 等价）；minSdk 26 上只能用旧常量，
    // 所以这个 else 分支带一条 deprecation 警告，是有意的，别「修」成只在 R+ 可用的那个。
    val format = if (hasAlpha) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    } else {
        Bitmap.CompressFormat.JPEG
    }
    val mediaType = if (hasAlpha) "image/webp" else "image/jpeg"
    var smallest: ByteArray? = null
    var chosen: ByteArray? = null
    for (quality in REQUEST_IMAGE_QUALITIES) {
        val output = ByteArrayOutputStream()
        val ok = runCatching { scaled.compress(format, quality, output) }.getOrDefault(false)
        if (!ok) continue
        val bytes = output.toByteArray()
        if (smallest == null || bytes.size < smallest.size) smallest = bytes
        if (bytes.size <= REQUEST_IMAGE_MAX_BYTES) {
            chosen = bytes
            break
        }
    }
    val bytes = chosen ?: smallest ?: return null
    val dataUrl = "data:" + mediaType + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    return RequestImage(dataUrl, scaled.width, scaled.height)
}

/**
 * 用户消息的 wire content（dsh 的 contentParts / imageParts）：
 *  - 正文只有一个文本块；
 *  - 每个文件变成一个文本块（fileHandleText）；
 *  - 每张图片：能收图的模型拿到「句柄文本 + 图片块」（句柄在已有内容时自带一个换行），
 *    纯文本模型只拿到 textOnlyImageText 占位。
 */
suspend fun userContentPart(
    text: String,
    attachments: List<UserAttachment>,
    imageCapable: Boolean,
): kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.buildJsonArray {
    val parts = this
    var count = 0
    fun addText(value: String) {
        parts.add(kotlinx.serialization.json.buildJsonObject {
            put("type", "text")
            put("text", value)
        })
        count++
    }
    if (text.isNotBlank()) addText(text)
    attachments.forEach { attachment ->
        when {
            !attachment.isImage -> addText(fileHandleText(attachment))
            imageCapable -> {
                val encoded = requestImageOf(attachment)
                if (encoded == null) {
                    // 重编码失败（设备解不了这种编码，例如老机器上的 HEIC）：退回**文件句柄**。
                    // 这里以前写的是 textOnlyImageText（"image omitted because this model accepts
                    // text only"）—— 那句话在这个分支上是假的：模型明明能收图，是我们的编码失败，
                    // 模型看到那句话会以为自己收不了图，而不是去读文件。
                    addText(fileHandleText(attachment))
                } else {
                    addText((if (count == 0) "" else "\n") + imageHandleText(attachment, encoded.width, encoded.height))
                    parts.add(kotlinx.serialization.json.buildJsonObject {
                        put("type", "image_url")
                        put("image_url", kotlinx.serialization.json.buildJsonObject { put("url", encoded.dataUrl) })
                    })
                }
            }
            else -> addText(textOnlyImageText(attachment))
        }
    }
}