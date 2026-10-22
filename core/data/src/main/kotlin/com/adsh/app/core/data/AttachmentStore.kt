package com.adsh.app.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.adsh.app.seam.model.UserAttachment
import com.adsh.app.seam.model.imageMediaTypeOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 附件仓库（dsh 的 `DSH_HOME/attachments/v1` 在 ADSH 的落点）。
 *
 * dsh 的语义（`packages/attachment/attachment-local`）：
 *  - **文件逐字节入库、内容寻址**：`files/<sha 前 2>/<sha>/<净化后的叶子名>`，id = sha256，读只读副本；
 *  - **图片先准入再归一化**：源图 ≤ 20 MiB / ≤ 64 MP / 单边 ≤ 8192，归一化成单帧 8-bit sRGB
 *    （EXIF 方向已应用、≤ 2048²、≤ 4 MiB、剥元数据），再按归一化字节寻址（`objects/<sha 前 2>/<sha>`）；
 *  - 会话删除**不回收**（内容寻址仓库没有 GC，dsh 同样如此），同一内容重复导入只多一个别名。
 *
 * ADSH 的根是应用私有的 `filesDir/attachments/v1`：不在工作区里，所以
 * ① 模型拿到的「只读副本」路径真的在工作区写围栏之外（workspace-write / read-only 都写不动）；
 * ② 换工作区 / 移动工作区后历史附件的句柄仍然有效；③ 不会被 glob/grep 当成工作区源码。
 */
object AttachmentStore {

    /** dsh 的 ImageAttachmentLimits（attachment-local/src/index.ts:33-42） */
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 64_000_000L
    const val MAX_IMAGE_DIMENSION = 8192

    /** dsh 的归一化预算（attachment-local/src/index.ts:50-54） */
    const val NORMALIZED_IMAGE_MAX_PIXELS = 2048 * 2048
    const val NORMALIZED_IMAGE_MAX_DIMENSION = 8192
    const val NORMALIZED_IMAGE_MAX_BYTES = 4 * 1024 * 1024

    /** dsh 的 IMAGE_ENCODING_QUALITIES */
    private val QUALITIES = intArrayOf(85, 75, 60)

    /** 准入拒绝（消息直接回给导入方；dsh 对应 ATTACHMENT 错误码的文案语义） */
    class Rejected(message: String) : Exception(message)

    fun root(filesDir: File): File = File(filesDir, "attachments/v1")

    /**
     * 收一个导入流：图片走准入 + 归一化，其它按原样逐字节入库。调用方负责流与显示名。
     *
     * @throws Rejected 图片超过 dsh 的准入上限（文件没有准入上限）
     */
    fun save(filesDir: File, stagingDir: File, input: InputStream, displayName: String?): UserAttachment {
        if (!stagingDir.isDirectory && !stagingDir.mkdirs()) {
            throw java.io.IOException("cannot create the attachment staging directory: " + stagingDir.absolutePath)
        }
        val temp = File(stagingDir, "in-" + System.nanoTime())
        var attachment: UserAttachment? = null
        try {
            input.use { source -> temp.outputStream().use { sink -> source.copyTo(sink) } }
            val bytes = temp.length()
            val media = imageMediaTypeOf(temp)
            attachment = if (media == null) {
                saveVerbatim(filesDir, temp, displayName)
            } else {
                if (bytes > MAX_IMAGE_BYTES) {
                    throw Rejected("image exceeds the " + (MAX_IMAGE_BYTES / 1024 / 1024) + " MB limit")
                }
                val normalized = normalizeImage(temp, media)
                if (normalized == null) saveVerbatim(filesDir, temp, displayName) else saveNormalizedImage(filesDir, normalized, displayName)
            }
            return attachment
        } finally {
            temp.delete()
        }
    }

    /** 逐字节文件：`files/<sha2>/<sha>/<leaf>`，写完置只读；同内容复用。 */
    private fun saveVerbatim(filesDir: File, source: File, displayName: String?): UserAttachment {
        val sha = sha256Of(source)
        val leaf = leafName(displayName ?: source.name)
        val target = File(root(filesDir), "files/" + sha.take(2) + "/" + sha + "/" + leaf)
        if (!target.isFile || target.length() != source.length()) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            target.setReadOnly()
        }
        return UserAttachment(
            path = target.absolutePath,
            name = leaf,
            bytes = target.length(),
            mediaType = "",
            sha256 = sha,
        )
    }

    /** 归一化图片：`objects/<sha2>/<sha>`（无扩展名：模型侧按魔数认，dsh 的 `read_image` 也允许无扩展名）。 */
    private fun saveNormalizedImage(filesDir: File, image: NormalizedImage, displayName: String?): UserAttachment {
        val sha = sha256Of(image.bytes)
        val target = File(root(filesDir), "objects/" + sha.take(2) + "/" + sha)
        if (!target.isFile || target.length() != image.bytes.size.toLong()) {
            target.parentFile?.mkdirs()
            target.writeBytes(image.bytes)
            target.setReadOnly()
        }
        return UserAttachment(
            path = target.absolutePath,
            name = leafName(displayName ?: "image"),
            bytes = image.bytes.size.toLong(),
            mediaType = image.mediaType,
            sha256 = sha,
            width = image.width,
            height = image.height,
        )
    }

    /** 归一化结果（dsh 的 NormalizedImage） */
    private data class NormalizedImage(
        val bytes: ByteArray,
        val mediaType: String,
        val width: Int,
        val height: Int,
    )

    /**
     * dsh 的归一化管线（normalization.ts）：应用 EXIF 方向 → 限制到 2048²/8192 → 质量阶梯 85/75/60
     * 编码（有 alpha 走 WebP，否则 JPEG）→ 取第一个 ≤4 MiB 的，都超就取最小的。
     * 解不开 / 尺寸为 0 返回 null（调用方退回文件句柄）。
     */
    private fun normalizeImage(source: File, media: String): NormalizedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null
        if (rawWidth.toLong() * rawHeight > MAX_IMAGE_PIXELS) {
            throw Rejected("image exceeds the " + MAX_IMAGE_PIXELS + "-pixel limit")
        }
        if (max(rawWidth, rawHeight) > MAX_IMAGE_DIMENSION) {
            throw Rejected("image side exceeds " + MAX_IMAGE_DIMENSION + "px")
        }
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swaps = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val orientedWidth = if (swaps) rawHeight else rawWidth
        val orientedHeight = if (swaps) rawWidth else rawHeight
        val (targetWidth, targetHeight) = fitInside(orientedWidth, orientedHeight)
        // dsh 在桌面用 sharp 的原生管线；Android 侧要在 Java 堆里解码，64 MP 的源图按「够用就停」
        // 采样会解出 16 MP（64 MB）位图，旋转/缩放时再叠一份 → 有 OOM 风险。这里额外把解码结果
        // 压到 8 MP 以内（仍然远大于 2048² 的归一化目标）。
        var sample = sampleSizeFor(orientedWidth, orientedHeight, targetWidth, targetHeight)
        while ((orientedWidth / sample).toLong() * (orientedHeight / sample) > 8_000_000L && sample < 64) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val oriented = applyOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        val scaled = if (oriented.width == targetWidth && oriented.height == targetHeight) {
            oriented
        } else {
            Bitmap.createScaledBitmap(oriented, targetWidth, targetHeight, true).also { oriented.recycle() }
        }
        val hasAlpha = scaled.hasAlpha()
        // API 30 起 WEBP 拆成 LOSSY/LOSSLESS；minSdk 26 只能用旧常量，这个 deprecation 是有意的
        @Suppress("DEPRECATION")
        val format = if (hasAlpha) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val outputMedia = if (hasAlpha) "image/webp" else "image/jpeg"
        var smallest: ByteArray? = null
        var chosen: ByteArray? = null
        for (quality in QUALITIES) {
            val out = ByteArrayOutputStream()
            if (!runCatching { scaled.compress(format, quality, out) }.getOrDefault(false)) continue
            val bytes = out.toByteArray()
            if (smallest == null || bytes.size < smallest.size) smallest = bytes
            if (bytes.size <= NORMALIZED_IMAGE_MAX_BYTES) {
                chosen = bytes
                break
            }
        }
        val result = chosen ?: smallest ?: return null
        val width = scaled.width
        val height = scaled.height
        scaled.recycle()
        return NormalizedImage(result, outputMedia, width, height)
    }

    /** 等比缩进 2048² / 单边 8192，不放大（dsh 的 resize fit:'inside', withoutEnlargement） */
    private fun fitInside(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val scale = min(
            1.0,
            min(
                sqrt(NORMALIZED_IMAGE_MAX_PIXELS.toDouble() / (width.toDouble() * height.toDouble())),
                NORMALIZED_IMAGE_MAX_DIMENSION.toDouble() / max(width, height).toDouble(),
            ),
        )
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

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

    /** 应用 EXIF 方向的 8 种情形（dsh 的 sharp .rotate()） */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return bitmap
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
            .getOrDefault(bitmap)
    }

    /** dsh 的 fileLeafName：两种分隔符都截断、控制字符去掉、Windows 非法字符换 `_`、
     *  尾部点/空格去掉、设备名前加 `_`、按 UTF-8 字节截到 255，空/./.. 退化成 `file`。 */
    fun leafName(raw: String?): String {
        if (raw == null) return "file"
        val leaf = raw.substring(max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1)
        var clean = leaf
            .filter { it.code >= 0x20 && it.code != 0x7f }
            .map { if (it == '<' || it == '>' || it == ':' || it == '"' || it == '|' || it == '?' || it == '*') '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.', ' ')
        if (clean.lowercase() in setOf("con", "prn", "aux", "nul") || Regex("^(com|lpt)[1-9]$").matches(clean.lowercase())) {
            clean = "_" + clean
        }
        clean = utf8Prefix(clean, 255).trimEnd('.', ' ')
        return if (clean.isEmpty() || clean == "." || clean == "..") "file" else clean
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        val builder = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            builder.append(text)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

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
        digest.digest().joinToString("") { String.format("%02x", it) }
    }.getOrDefault("")

    fun sha256Of(bytes: ByteArray): String =
        runCatching { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format("%02x", it) } }
            .getOrDefault("")
}
