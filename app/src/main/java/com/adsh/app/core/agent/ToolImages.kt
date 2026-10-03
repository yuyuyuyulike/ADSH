package com.adsh.app.core.agent

/**
 * **工具结果里的图片**（从 Attachments.kt 拆出来的）：read_image 与子调用产出的图片怎么落库、
 * 怎么回灌模型（dsh 的 tools-ptc deferContext：图片作为一条 user 消息追加在这次 run 之后），
 * 以及落库预算与解码采样尺寸。
 *
 * 请求侧的图片编码在 RequestImages.kt；附件本身的表示在 Attachments.kt。
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

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
