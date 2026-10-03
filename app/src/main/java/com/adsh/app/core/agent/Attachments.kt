package com.adsh.app.core.agent

import android.graphics.BitmapFactory
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

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

internal val attachmentJson = Json { ignoreUnknownKeys = true; isLenient = true }   // 附件清单与工具图片共用同一份 Json 配置

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

/** 这条消息（或这一轮）里有没有图片附件 —— 失败文案要不要提「可识别图片」就看它 */
fun hasImageAttachment(raw: String?): Boolean = decodeAttachments(raw).any { it.isImage }

/**
 * 图片请求失败时的指路文案（第八十三轮，用户点名）。
 *
 * 背景：模型目录里没收录的新模型现在**默认按「能收图」发**（[com.adsh.app.core.data.SettingsStore.defaultImageInput]），
 * 万一它其实不能识图，提供方会直接报错。这句就贴在错误正文后面 —— 不说「一定是这个原因」，
 * 只说「如果是」，因为同一轮里也可能是网络 / 余额 / 上下文超限。
 *
 * @param modelLabel 设置页里显示的模型名（设置 → 模型 → 这一条 → 展开）
 */
fun imageRouteHintText(modelLabel: String): String =
    "如果这个模型不能识图：请到「设置 → 模型 → " + modelLabel +
        " → 展开」取消勾选「可识别图片」，再重新发送。"

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