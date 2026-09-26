package com.adsh.app.core.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 用户消息携带的附件 —— 对齐 dsh 的 AdmittedPromptContentPart：
 * 文本 / 图片 / 文件三种内容块，附件在**装配请求时**才被翻译成模型看得懂的东西，
 * 消息本身只存一份「引用」（路径 + 尺寸 + 摘要），界面也不再往正文里塞路径。
 *
 * dsh 的两条规则（dsh-llm/content.ts 与 dsh-llm-deepseek/serialize）：
 *  - 文件：模型侧**唯一**的表示是 fileHandleText 那句话（名字 + 字节数 + sha256 + 只读副本路径 +
 *    「需要内容时用文件工具去读」的指令），任何提供方都不接收文件块；
 *  - 图片：能收图的模型收到一条 image 句柄文本 + 一个真正的图片内容块（base64 data URL）；
 *    纯文本模型只收到 textOnlyImageText 那句占位（sha256 摘要），不看不到图也不报错。
 */
@Serializable
data class UserAttachment(
    /** 工作区里的绝对路径（只读副本，随会话删除） */
    val path: String,
    val name: String,
    val bytes: Long,
    /** 按字节嗅探出的图片 MIME；非图片为空串（dsh 的 FileAttachmentRef 没有媒体类型） */
    val mediaType: String = "",
    /** 文件字节的 sha256（十六进制小写），dsh 的 attachmentId = "sha256:<hex>" */
    val sha256: String,
    /** 图片的像素尺寸（已应用 EXIF 方向），非图片为 0 */
    val width: Int = 0,
    val height: Int = 0,
) {
    val isImage: Boolean get() = mediaType.startsWith("image/")
}

/** 装配请求时的一张图片：可放进 wire 的 data URL 与它的实际像素尺寸 */
data class RequestImage(val dataUrl: String, val width: Int, val height: Int)

/** dsh 的 requestImagePixels 默认值（DEFAULT_REQUEST_IMAGE_PIXEL_BUDGET = 64e4） */
internal const val REQUEST_IMAGE_MAX_PIXELS = 640_000

/** dsh 的 DEFAULT_REQUEST_IMAGE_MAX_BYTES = 1 MiB：单张请求图片的编码上限 */
private const val REQUEST_IMAGE_MAX_BYTES = 1024 * 1024

/** dsh 的质量阶梯（IMAGE_ENCODING_QUALITIES）：85 / 75 / 60，取第一个进预算的 */
private val REQUEST_IMAGE_QUALITIES = intArrayOf(85, 75, 60)

private val attachmentJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 消息上的附件清单序列化（落库用；解析失败按「没有附件」处理） */
fun encodeAttachments(list: List<UserAttachment>): String? =
    if (list.isEmpty()) null else runCatching {
        attachmentJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(UserAttachment.serializer()), list)
    }.getOrNull()

fun decodeAttachments(raw: String?): List<UserAttachment> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        attachmentJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(UserAttachment.serializer()), raw)
    }.getOrDefault(emptyList())
}

/**
 * 把「待发附件的路径」变成可落库的附件引用（dsh 的 admitPromptContent）：
 * 读字节数、嗅探图片类型、量尺寸、算 sha256。全部在 IO 线程上做。
 */
suspend fun describeAttachments(paths: List<String>): List<UserAttachment> = withContext(Dispatchers.IO) {
    paths.mapNotNull { path ->
        val file = File(path)
        if (!file.isFile) return@mapNotNull null
        val media = imageMediaTypeOf(file)
        val bounds = if (media != null) imageBounds(file) else null
        UserAttachment(
            path = file.absolutePath,
            name = file.name,
            bytes = file.length(),
            mediaType = media ?: "",
            sha256 = sha256Of(file),
            width = bounds?.first ?: 0,
            height = bounds?.second ?: 0,
        )
    }
}

/**
 * 按**魔数**嗅探图片类型（只看字节，不看扩展名）。
 *
 * 这里比 dsh 宽：dsh 只认 png / jpeg / webp / gif 四种
 * （dsh-attachment-local 的 MEDIA_TYPES + sharp 的 metadata().format），其余一律
 * `Unsupported or malformed image data.` 拒收；ADSH 送图走的不是「按格式解码」而是
 * BitmapFactory 重新编码成 JPEG/WebP，所以只要**设备解得出**就认下来：
 * bmp / heic / heif / avif 也算图片。
 *
 * 之所以必须扩：输入框里那个缩略图是 BitmapFactory 解出来的，而消息里那一行按魔数判定。
 * 两边口径不一致时就会出现「输入框里是图片、发出去变成文件卡片、AI 也看不了」——
 * 安卓手机相册里最常见的就是 HEIC，BMP 截图也不少。
 */
fun imageMediaTypeOf(file: File): String? {
    val head = ByteArray(12)
    val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
    if (read < 4) return null
    return when {
        read >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> "image/png"
        read >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
        read >= 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> "image/gif"
        read >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() &&
            head[3] == 'F'.code.toByte() && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
            head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte() -> "image/webp"
        head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte() -> "image/bmp"
        // ISO-BMFF 家族（HEIC / HEIF / AVIF）：第 4..7 字节是 "ftyp"，8..11 是 major brand
        read >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> isoBrandMediaType(head, read)
        else -> null
    }
}

/** ISO-BMFF 的 major brand + compatible brands → media type（只认实拍照片会用到的几种） */
private fun isoBrandMediaType(head: ByteArray, read: Int): String? {
    val brands = ArrayList<String>()
    brands += String(head, 8, 4, Charsets.US_ASCII).lowercase()
    // compatible brands 紧跟在 major brand + minor version 之后，每 4 字节一个
    var offset = 16
    while (offset + 4 <= read) {
        brands += String(head, offset, 4, Charsets.US_ASCII).lowercase()
        offset += 4
    }
    return when {
        brands.any { it.startsWith("avif") || it.startsWith("avis") } -> "image/avif"
        brands.any {
            it.startsWith("heic") || it.startsWith("heix") || it.startsWith("hevc") ||
                it.startsWith("hevx") || it.startsWith("heim") || it.startsWith("heis") ||
                it == "mif1" || it == "msf1"
        } -> "image/heic"
        else -> null
    }
}

/** 图片尺寸（应用 EXIF 方向：横竖互换的 90/270 度要换回来，dsh 归一化也这么做） */
fun imageBounds(file: File): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    val width = options.outWidth
    val height = options.outHeight
    if (width <= 0 || height <= 0) return null
    return if (exifSwapsSides(file)) height to width else width to height
}

@Suppress("DEPRECATION")
private fun exifSwapsSides(file: File): Boolean = runCatching {
    when (ExifInterface(file.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
        -> true
        else -> false
    }
}.getOrDefault(false)

fun sha256Of(file: File): String = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { byte -> String.format("%02x", byte) }
}.getOrDefault("")

private fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun digest8(attachment: UserAttachment): String = attachment.sha256.take(8)

/** dsh 的 imageIdentity：名字 + 完整 sha256（没有 sha256 时退化成名字） */
private fun imageIdentity(attachment: UserAttachment): String =
    if (attachment.sha256.isEmpty()) quoted(attachment.name)
    else quoted(attachment.name) + " (sha256:" + attachment.sha256 + ")"

private fun extensionOf(mediaType: String): String = when (mediaType) {
    "image/png" -> ".png"
    "image/jpeg" -> ".jpg"
    "image/webp" -> ".webp"
    "image/gif" -> ".gif"
    "image/bmp" -> ".bmp"
    "image/heic" -> ".heic"
    "image/avif" -> ".avif"
    else -> ".png"
}

/**
 * dsh 的 fileHandleText（逐字，去掉了 ADSH 里不存在的「委派给子代理」那句）：
 * 这是文件在模型侧唯一的表示 —— 地址 + 「需要时用文件工具读」的指令。
 */
fun fileHandleText(attachment: UserAttachment): String {
    val identity = "File " + quoted(attachment.name) + " (" + attachment.bytes + " bytes, sha256:" + digest8(attachment) + ")"
    return "[" + identity + ": verbatim read-only copy saved at " + quoted(attachment.path) +
        ". Read that path with your file tools when its contents are needed; copy it to a writable location before modifying it.]"
}

/** dsh 的 requestImageHandleText：图片句柄（后面紧跟着真正的图片块） */
fun imageHandleText(attachment: UserAttachment, previewWidth: Int, previewHeight: Int): String {
    val preview = "Image " + imageIdentity(attachment) + "; request preview " + previewWidth + "x" + previewHeight + "px."
    if (attachment.width <= 0 || attachment.height <= 0 || attachment.path.isEmpty()) {
        return preview + " It may be resized or re-encoded; source dimensions, format, and byte size may differ."
    }
    return preview + " Normalized copy (read-only; may be resized or re-encoded): " + quoted(attachment.path) +
        " (" + attachment.width + "x" + attachment.height + "px, " + attachment.mediaType + ")." +
        " Source dimensions, format, and byte size may differ." +
        " Copy to a writable path ending in " + extensionOf(attachment.mediaType) + " before editing."
}

/** dsh 的 textOnlyImageText：纯文本模型收到的图片占位（不含路径，逐字） */
fun textOnlyImageText(attachment: UserAttachment): String =
    "[image omitted because this model accepts text only; attachment sha256:" + digest8(attachment) + "]"

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



// ---------------------------------------------------------------- 工具读到的图片（dsh 的 read_image）

/**
 * 一次 read_image 的产物：可落库的引用 + 随图片一起回灌模型的那段信封文本。
 *
 * dsh 里图片走 attachments 服务（saveImage 落一份可寻址的副本），工具的输出内容块是
 * [text 信封, image 块]；PTC 里子调用命中的图片会被 deferContext 成一条 user 消息
 * 追加在 run 之后（dsh-tools 的 tools-ptc：`result.content.some(block => block.type === "image")`），
 * 所以模型在**下一步**才看到图，而不是在 run_code 的返回值里。
 *
 * ADSH 的持久化位置与用户附件相同：`<工作区>/.adsh/attachments/<会话 id>/`（随会话删除）。
 */
@Serializable
data class ToolImage(
    val attachment: UserAttachment,
    /** dsh 的 formatImageReadOutput 信封（deferred user 消息里的 text 块） */
    val envelope: String = "",
)

fun encodeToolImages(list: List<ToolImage>): String? =
    if (list.isEmpty()) null else runCatching {
        attachmentJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ToolImage.serializer()), list)
    }.getOrNull()

fun decodeToolImages(raw: String?): List<ToolImage> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        attachmentJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ToolImage.serializer()), raw)
    }.getOrDefault(emptyList())
}

/**
 * dsh 的 formatImageReadOutput（逐字，含缩小时的坐标换算建议）：
 *
 *     <path>…</path>
 *     <type>image</type>
 *     <content>
 *     image/png image, WxH px, N bytes (downscaled from OWxOH px; multiply coordinates by X to
 *     locate features in the original file)
 *     </content>
 *
 * @param width/height 模型**实际收到**的那张图的尺寸（请求变体，见 requestImageDimensions）
 * @param originalWidth/originalHeight 源文件尺寸；两者相同（没缩小）时不写那段说明
 */
fun toolImageEnvelope(
    display: String,
    mediaType: String,
    bytes: Long,
    width: Int,
    height: Int,
    originalWidth: Int = 0,
    originalHeight: Int = 0,
): String {
    var scaled = ""
    if (originalWidth > 0 && originalHeight > 0 && (originalWidth != width || originalHeight != height)) {
        val x = (originalWidth.toDouble() / width).format2()
        val y = (originalHeight.toDouble() / height).format2()
        val advice = if (x == y) {
            "multiply coordinates by " + x
        } else {
            "multiply x coordinates by " + x + " and y coordinates by " + y
        }
        scaled = " (downscaled from " + originalWidth + "x" + originalHeight +
            " px; " + advice + " to locate features in the original file)"
    }
    return "<path>" + display + "</path>\n<type>image</type>\n<content>\n" +
        mediaType + " image, " + width + "x" + height + " px, " + bytes + " bytes" + scaled +
        "\n</content>"
}

/** dsh 的 toFixed(2)（Java 的 String.format 在默认 Locale 下会把小数点写成逗号，必须显式给 Locale） */
private fun Double.format2(): String = String.format(java.util.Locale.US, "%.2f", this)

/**
 * dsh 的 deferred 图片消息内容块（tools-ptc 的 deferContext）：
 * 按顺序给出每张图的 [信封文本, 图片块]；不能收图的模型拿到 textOnlyImageText 占位
 * （dsh 的 contentParts 对 image 块的处理）。没有图片时返回 null（调用方不要追加空消息）。
 */
suspend fun toolImageContentPart(
    images: List<ToolImage>,
    imageCapable: Boolean,
): kotlinx.serialization.json.JsonElement? {
    if (images.isEmpty()) return null
    return kotlinx.serialization.json.buildJsonArray {
        images.forEach { image ->
            val attachment = image.attachment
            if (image.envelope.isNotBlank()) add(kotlinx.serialization.json.buildJsonObject {
                put("type", "text")
                put("text", image.envelope)
            })
            if (imageCapable) {
                val encoded = requestImageOf(attachment)
                if (encoded == null) {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", "text")
                        put("text", fileHandleText(attachment))
                    })
                } else {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("type", "image_url")
                        put("image_url", kotlinx.serialization.json.buildJsonObject { put("url", encoded.dataUrl) })
                    })
                }
            } else {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", "text")
                    put("text", textOnlyImageText(attachment))
                })
            }
        }
    }
}

/**
 * 把一张读到的图片收进会话附件目录（dsh 的 attachments.saveImage）：
 * 落一份**逐字节相同**的只读副本（ADSH 不做 dsh 的 16-bit → 8-bit 归一化，见报告），
 * 返回可落库的引用。文件名带内容地址前缀，同一张图重复读不会堆副本。
 */
suspend fun admitToolImage(source: File, mediaType: String, directory: File, displayName: String): UserAttachment? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = source.readBytes()
            val sha = sha256Of(bytes)
            if (sha.isEmpty()) return@runCatching null
            if (!directory.isDirectory && !directory.mkdirs()) return@runCatching null
            val safeName = displayName.replace('/', '_').replace('\\', '_').take(120).ifBlank { "image" }
            val target = File(directory, sha.take(16) + "-" + safeName)
            if (!target.isFile || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
            val bounds = imageBounds(target) ?: return@runCatching null
            UserAttachment(
                path = target.absolutePath,
                name = safeName,
                bytes = target.length(),
                mediaType = mediaType,
                sha256 = sha,
                width = bounds.first,
                height = bounds.second,
            )
        }.getOrNull()
    }

fun sha256Of(bytes: ByteArray): String = runCatching {
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> String.format("%02x", byte) }
}.getOrDefault("")

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
