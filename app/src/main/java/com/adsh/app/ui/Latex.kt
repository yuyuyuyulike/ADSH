package com.adsh.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max

/**
 * LaTeX 公式的绘制与缓存（几何在 [LatexCore]，这里只做「盒子 → 位图」）。
 *
 * 缓存分三层（用户点名：滑动不能卡）：
 *  1. **解析**：源码 → 语法树（[latexParsed]，纯 Kotlin LRU，公式原文做键）；
 *  2. **排版尺寸**：源码 + 字号 → 宽/高/基线（[latexSizes]，省掉每帧重新 measureText）；
 *  3. **位图**：源码 + 字号 + 颜色 + 显示/行内 → [ImageBitmap]（按字节计价的 LRU）。
 * 于是滚动时每一帧只做 map 查表，不会在滑动过程中重新排版或绘制。
 */

/** 一张渲染好的公式：位图 + 以 sp 为单位的尺寸（行内排版要靠它算占位） */
internal class LatexRender(
    val bitmap: ImageBitmap,
    /** 宽 / 高 / 基线（相对底边的下沉量），单位 sp */
    val widthSp: Float,
    val heightSp: Float,
    val descentSp: Float,
)

/** 位图缓存：按字节计价（一张公式位图通常几 KB 到几十 KB） */
private val latexBitmaps = object : android.util.LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        value.width.coerceAtLeast(1) * value.height.coerceAtLeast(1) * 4
}

/** 尺寸缓存（位图被 LRU 淘汰后还能靠它把行内占位算对，不至于让整段文字重排两次） */
private val latexSizes = android.util.LruCache<String, FloatArray>(512)

/** 语法树缓存（纯 Kotlin LRU，键 = 公式原文） */
private val parseCache = object : LinkedHashMap<String, LatexNode>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LatexNode>): Boolean = size > 256
}

internal fun latexParsed(latex: String): LatexNode = synchronized(parseCache) { parseCache[latex] }
    ?: LatexParser.parse(latex).also { node -> synchronized(parseCache) { parseCache[latex] = node } }

/** Android 的文字度量：serif（近似 LaTeX 的衬线体），变量斜体 */
private object AndroidMetrics : LatexMetrics {
    private val paints = HashMap<Long, Paint>()

    private fun paint(size: Float, italic: Boolean): Paint {
        val key = (size.toBits().toLong() shl 1) or if (italic) 1L else 0L
        return paints.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (italic) Typeface.create(Typeface.SERIF, Typeface.ITALIC) else Typeface.SERIF
                textSize = size
            }
        }
    }

    override fun width(text: String, size: Float, italic: Boolean): Float = paint(size, italic).measureText(text)

    override fun ascent(size: Float): Float = -paint(size, false).fontMetrics.ascent

    override fun descent(size: Float): Float = paint(size, false).fontMetrics.descent
}

/**
 * 渲染一张公式位图（三层缓存，见文件头）。
 * @param sizePx 基线字号（px）
 * @param pxPerSp 1sp 等于多少 px（sp = px / pxPerSp；行内占位的尺寸必须是 sp）
 * @param display 块级公式（分式更大、大运算符上下限堆叠）
 */
private fun latexBitmap(
    latex: String,
    sizePx: Float,
    pxPerSp: Float,
    colorArgb: Int,
    display: Boolean,
): LatexRender? {
    val key = latex + "|" + sizePx.toInt() + "|" + colorArgb + "|" + display
    val cachedBitmap = latexBitmaps.get(key)
    val cachedSize = latexSizes.get(key)
    if (cachedBitmap != null && cachedSize != null) {
        return LatexRender(cachedBitmap, cachedSize[0], cachedSize[1], cachedSize[2])
    }
    val node = latexParsed(latex)
    val box = runCatching { LatexLayout.layout(node, sizePx, AndroidMetrics, display) }.getOrNull() ?: return null
    val padding = max(2f, sizePx * 0.08f)
    val width = ceil(box.width + padding * 2f).toInt().coerceAtLeast(1)
    val height = ceil(box.height + padding * 2f).toInt().coerceAtLeast(1)
    // 病态输入（写成 4000x4000 的公式）直接不画：正文照常显示，不要拖垮内存
    if (width > 4096 || height > 4096) return null
    val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    drawBox(Canvas(bitmap).also { it.translate(padding, padding) }, box, colorArgb)
    val image = bitmap.asImageBitmap()
    val safePxPerSp = if (pxPerSp > 0f) pxPerSp else 1f
    val render = LatexRender(
        bitmap = image,
        widthSp = width / safePxPerSp,
        heightSp = height / safePxPerSp,
        descentSp = (box.descent + padding) / safePxPerSp,
    )
    latexBitmaps.put(key, image)
    latexSizes.put(key, floatArrayOf(render.widthSp, render.heightSp, render.descentSp))
    return render
}

private fun drawBox(canvas: Canvas, box: LatexBox, colorArgb: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }
    box.ops.forEach { op ->
        when (op) {
            is LatexOp.Glyphs -> {
                paint.typeface = if (op.italic) Typeface.create(Typeface.SERIF, Typeface.ITALIC) else Typeface.SERIF
                paint.textSize = op.size
                paint.style = Paint.Style.FILL
                canvas.drawText(op.text, op.x, op.baseline, paint)
            }
            is LatexOp.Rule -> {
                paint.typeface = null
                paint.style = Paint.Style.FILL
                canvas.drawRect(op.x, op.top, op.x + op.width, op.top + op.thickness, paint)
            }
            is LatexOp.Stroke -> {
                paint.typeface = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = op.thickness
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                val path = android.graphics.Path()
                op.points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
                }
                canvas.drawPath(path, paint)
            }
        }
    }
}

/** AnnotatedString 里行内公式占位的 id（[inlineMarkdown] 与 [MdText] 必须用同一个） */
internal fun latexInlineId(index: Int): String = "latex" + index

/**
 * 渲染入口（非 Composable 版本）：给 [MdText] 在 remember 里一次性把整段的公式都渲染出来。
 * 三层缓存命中时只是一次查表。
 */
internal fun latexRenderOf(
    latex: String,
    sizePx: Float,
    pxPerSp: Float,
    colorArgb: Int,
    display: Boolean,
): LatexRender? = runCatching { latexBitmap(latex, sizePx, pxPerSp, colorArgb, display) }.getOrNull()

/** 块级公式（$$…$$ / \[…\]）：居中一张图；渲染不出来时退回等宽原文（不丢信息） */
@Composable
internal fun LatexBlockImage(latex: String, fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val pxPerSp = with(density) { 1.sp.toPx() }
    val argb = color.toArgb()
    val render = remember(latex, sizePx, argb) {
        runCatching { latexBitmap(latex, sizePx, pxPerSp, argb, display = true) }.getOrNull()
    }
    if (render == null) {
        Text(
            text = latex,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = color,
        )
        return
    }
    Image(
        bitmap = render.bitmap,
        contentDescription = "公式",
        contentScale = ContentScale.Fit,
        modifier = modifier.width(render.widthSp.dp).height(render.heightSp.dp),
    )
}
