package com.adsh.app.ui.blocks

/*
 * 公式的**盒子化排版与绘制指令**：解析（[LatexParser]）在 LatexParser.kt，绘制（[LatexImages]）在 Android 侧。
 * 坐标以盒子左上角为原点、y 向下、基线在 ascent 处 —— 几何因此可以脱离设备单测。
 */

// ---------------------------------------------------------------- 排版（盒子模型）

/** 绘制指令（坐标相对盒子左上角、y 向下） */
internal sealed interface LatexOp {
    /** 一段文字，基线在 baseline */
    data class Glyphs(val x: Float, val baseline: Float, val text: String, val size: Float, val italic: Boolean) : LatexOp
    /** 横线（分数线 / 根号上划线 / \bar / \underline） */
    data class Rule(val x: Float, val top: Float, val width: Float, val thickness: Float) : LatexOp
    /** 折线（根号那个 √ 的笔画） */
    data class Stroke(val points: List<Pair<Float, Float>>, val thickness: Float) : LatexOp
}

/** 排版后的一个盒子：宽、基线上方、基线下方、绘制指令 */
internal class LatexBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val ops: List<LatexOp>,
) {
    val height: Float get() = ascent + descent
}

/** 文字度量（Android 侧用 Paint 实现；单测用假实现） */
internal interface LatexMetrics {
    fun width(text: String, size: Float, italic: Boolean): Float
    fun ascent(size: Float): Float
    fun descent(size: Float): Float
}

/**
 * 盒子化排版。所有尺寸都是「相对字号」的比例，所以同一份公式在任何字号下几何一致。
 * @param display 块级公式（分式更大、大运算符的上下限堆叠）
 */
internal object LatexLayout {

    /** 上标字号比例 */
    private const val SCRIPT_SCALE = 0.7f
    /** 行内分式的字号比例 */
    private const val INLINE_FRAC_SCALE = 0.85f
    /** 上标抬高 / 下标压低（相对字号） */
    private const val SUP_SHIFT = 0.45f
    private const val SUB_SHIFT = 0.22f

    fun layout(node: LatexNode, size: Float, metrics: LatexMetrics, display: Boolean = false): LatexBox =
        when (node) {
            is LatexNode.Seq -> sequence(node.items, size, metrics, display)
            is LatexNode.Glyphs -> glyphs(node.text, size, metrics, node.italic)
            is LatexNode.Space -> LatexBox(node.em * size, 0f, 0f, emptyList())
            is LatexNode.Script -> script(node, size, metrics, display)
            is LatexNode.Frac -> frac(node, size, metrics, display)
            is LatexNode.Sqrt -> sqrt(node, size, metrics, display)
            is LatexNode.Big -> big(node, size, metrics, display)
            is LatexNode.Delim -> delim(node, size, metrics, display)
            is LatexNode.Accent -> accent(node, size, metrics, display)
            is LatexNode.Grid -> grid(node, size, metrics, display)
        }

    // ------------------------------------------------------------ 基本

    private fun glyphs(text: String, size: Float, metrics: LatexMetrics, italic: Boolean): LatexBox {
        if (text.isEmpty()) return LatexBox(0f, 0f, 0f, emptyList())
        val ascent = metrics.ascent(size)
        return LatexBox(
            width = metrics.width(text, size, italic),
            ascent = ascent,
            descent = metrics.descent(size),
            ops = listOf(LatexOp.Glyphs(0f, ascent, text, size, italic)),
        )
    }

    private fun sequence(items: List<LatexNode>, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        if (items.isEmpty()) return LatexBox(0f, 0f, 0f, emptyList())
        var x = 0f
        var ascent = 0f
        var descent = 0f
        val ops = ArrayList<LatexOp>()
        items.forEach { item ->
            val box = layout(item, size, metrics, display)
            ops += translate(box.ops, x, 0f)
            ascent = maxOf(ascent, box.ascent)
            descent = maxOf(descent, box.descent)
            x += box.width
        }
        return LatexBox(x, ascent, descent, ops)
    }

    private fun script(node: LatexNode.Script, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox =
        scriptOf(layout(node.base, size, metrics, display), node.sup, node.sub, size, metrics)

    /** 上/下标：底盒已经排好（大运算符的行内形态直接把自己的盒子当底） */
    private fun scriptOf(
        base: LatexBox,
        supNode: LatexNode?,
        subNode: LatexNode?,
        size: Float,
        metrics: LatexMetrics,
    ): LatexBox {
        val scriptSize = size * SCRIPT_SCALE
        val sup = supNode?.let { layout(it, scriptSize, metrics, display = false) }
        val sub = subNode?.let { layout(it, scriptSize, metrics, display = false) }
        val shiftUp = SUP_SHIFT * size
        val shiftDown = SUB_SHIFT * size
        val kern = 0.05f * size
        val scriptWidth = maxOf(sup?.width ?: 0f, sub?.width ?: 0f)
        val width = base.width + if (scriptWidth > 0f) kern + scriptWidth else 0f
        val ascent = maxOf(base.ascent, if (sup != null) shiftUp + sup.ascent else 0f)
        val descent = maxOf(base.descent, if (sub != null) shiftDown + sub.descent else 0f)
        val ops = ArrayList<LatexOp>()
        ops += translate(base.ops, 0f, ascent - base.ascent)
        sup?.let { ops += translate(it.ops, base.width + kern, ascent - shiftUp - it.ascent) }
        sub?.let { ops += translate(it.ops, base.width + kern, ascent + shiftDown - it.ascent) }
        return LatexBox(width, ascent, descent, ops)
    }

    // ------------------------------------------------------------ 分式 / 根式

    private fun frac(node: LatexNode.Frac, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        val isDisplay = node.display ?: display
        val partSize = if (isDisplay) size else size * INLINE_FRAC_SCALE
        val num = layout(node.num, partSize, metrics, display = isDisplay)
        val den = layout(node.den, partSize, metrics, display = isDisplay)
        val thickness = maxOf(1f, size * 0.045f)
        val pad = size * (if (isDisplay) 0.20f else 0.13f)
        val sideGap = size * 0.12f
        val width = maxOf(num.width, den.width) + sideGap * 2f
        val barTop = num.height + pad
        val height = barTop + thickness + pad + den.height
        val ascent = barTop + thickness / 2f
        val ops = ArrayList<LatexOp>()
        ops += translate(num.ops, (width - num.width) / 2f, 0f)
        ops += LatexOp.Rule(0f, barTop, width, thickness)
        ops += translate(den.ops, (width - den.width) / 2f, barTop + thickness + pad)
        return LatexBox(width, ascent, height - ascent, ops)
    }

    private fun sqrt(node: LatexNode.Sqrt, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        val body = layout(node.body, size, metrics, display)
        val index = node.index?.let { layout(it, size * 0.6f, metrics, display = false) }
        val pad = size * 0.14f
        val thickness = maxOf(1f, size * 0.04f)
        val radicalWidth = maxOf(size * 0.5f, thickness * 6f)
        val bodyX = radicalWidth + pad * 0.6f
        val bodyTop = pad
        val height = body.height + pad * 2f
        val top = pad * 0.45f
        val bottom = height - pad * 0.35f
        val mid = bottom - (bottom - top) * 0.42f
        val width = bodyX + body.width + pad * 0.3f
        val ops = ArrayList<LatexOp>()
        ops += LatexOp.Stroke(
            points = listOf(
                0f to mid,
                radicalWidth * 0.32f to (bottom - (bottom - mid) * 0.15f),
                radicalWidth * 0.66f to top,
                width to top,
            ),
            thickness = thickness,
        )
        ops += translate(body.ops, bodyX, bodyTop)
        index?.let {
            // 根指数排在根号左上角（近似：贴在笔画起点上方）
            ops += translate(it.ops, 0f, top - it.height - pad * 0.1f)
        }
        val baseline = bodyTop + body.ascent
        val extraTop = index?.let { it.height + pad * 0.1f } ?: 0f
        val totalAscent = baseline + extraTop
        val shifted = translate(ops, 0f, extraTop)
        return LatexBox(width, totalAscent, height + extraTop - totalAscent, shifted)
    }

    // ------------------------------------------------------------ 大运算符 / 定界符

    private fun big(node: LatexNode.Big, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        val symbolSize = size * (if (display) 1.45f else 1.15f)
        val symbol = glyphs(node.symbol, symbolSize, metrics, italic = false)
        if (node.sup == null && node.sub == null) return symbol
        if (!display) {
            // 行内的大运算符：上下限排在右侧（LaTeX 的 inline 形态）
            return scriptOf(symbol, node.sup, node.sub, size, metrics)
        }
        val scriptSize = size * SCRIPT_SCALE
        val sup = node.sup?.let { layout(it, scriptSize, metrics, display = false) }
        val sub = node.sub?.let { layout(it, scriptSize, metrics, display = false) }
        val gapAbove = if (sup != null) size * 0.12f else 0f
        val gapBelow = if (sub != null) size * 0.16f else 0f
        val width = maxOf(symbol.width, sup?.width ?: 0f, sub?.width ?: 0f)
        val ascent = (sup?.height ?: 0f) + gapAbove + symbol.ascent
        val descent = symbol.descent + gapBelow + (sub?.height ?: 0f)
        val ops = ArrayList<LatexOp>()
        sup?.let { ops += translate(it.ops, (width - it.width) / 2f, 0f) }
        val symbolTop = (sup?.height ?: 0f) + gapAbove
        ops += translate(symbol.ops, (width - symbol.width) / 2f, symbolTop)
        sub?.let { ops += translate(it.ops, (width - it.width) / 2f, symbolTop + symbol.height + gapBelow) }
        return LatexBox(width, ascent, descent, ops)
    }

    private fun delim(node: LatexNode.Delim, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        val body = layout(node.body, size, metrics, display)
        val plainHeight = metrics.ascent(size) + metrics.descent(size)
        // 定界符按内容高度等比放大（LaTeX 的 \left…\right 语义）
        val scale = maxOf(1f, (body.height + size * 0.1f) / plainHeight)
        val left = if (node.left.isEmpty()) null else glyphs(node.left, size * scale, metrics, italic = false)
        val right = if (node.right.isEmpty()) null else glyphs(node.right, size * scale, metrics, italic = false)
        val gap = size * 0.08f
        val leftWidth = left?.width ?: 0f
        val rightWidth = right?.width ?: 0f
        val width = leftWidth + gap + body.width + gap + rightWidth
        // 定界符与内容垂直居中
        fun delimTop(d: LatexBox?): Float = if (d == null) 0f else (body.height - d.height) / 2f
        val leftTop = delimTop(left)
        val rightTop = delimTop(right)
        val top = minOf(0f, leftTop, rightTop)
        val bottom = maxOf(body.height, leftTop + (left?.height ?: 0f), rightTop + (right?.height ?: 0f))
        val padTop = -top
        val ops = ArrayList<LatexOp>()
        left?.let { ops += translate(it.ops, 0f, leftTop + padTop) }
        ops += translate(body.ops, leftWidth + gap, padTop)
        right?.let { ops += translate(it.ops, leftWidth + gap + body.width + gap, rightTop + padTop) }
        val baseline = padTop + body.ascent
        return LatexBox(width, baseline, bottom + padTop - baseline, ops)
    }

    // ------------------------------------------------------------ 重音

    private fun accent(node: LatexNode.Accent, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        val body = layout(node.body, size, metrics, display)
        val markHeight = size * 0.34f
        val ops = ArrayList<LatexOp>()
        val isRule = node.mark == "bar" || node.mark == "underline"
        if (node.mark == "underline") {
            val thickness = maxOf(1f, size * 0.04f)
            val gap = size * 0.12f
            ops += translate(body.ops, 0f, 0f)
            ops += LatexOp.Rule(0f, body.height + gap, body.width, thickness)
            val height = body.height + gap + thickness
            return LatexBox(body.width, body.ascent, height - body.ascent, ops)
        }
        if (isRule) {
            val thickness = maxOf(1f, size * 0.05f)
            ops += translate(body.ops, 0f, markHeight)
            ops += LatexOp.Rule(0f, markHeight - thickness, body.width, thickness)
            return LatexBox(body.width, body.ascent + markHeight, body.descent, ops)
        }
        val mark = when (node.mark) {
            "hat" -> "^"
            "tilde" -> "~"
            "vec" -> "→"
            "ddot" -> "··"
            else -> "·"
        }
        val markSize = size * 0.8f
        val markWidth = metrics.width(mark, markSize, false)
        ops += translate(body.ops, 0f, markHeight)
        ops += LatexOp.Glyphs(
            x = (body.width - markWidth) / 2f,
            baseline = markHeight - size * 0.08f,
            text = mark,
            size = markSize,
            italic = false,
        )
        return LatexBox(body.width, body.ascent + markHeight, body.descent, ops)
    }

    // ------------------------------------------------------------ 矩阵

    private fun grid(node: LatexNode.Grid, size: Float, metrics: LatexMetrics, display: Boolean): LatexBox {
        val rows = node.rows.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return LatexBox(0f, 0f, 0f, emptyList())
        val laid = rows.map { row -> row.map { layout(it, size, metrics, display = false) } }
        val columns = laid.maxOf { it.size }
        val columnWidth = FloatArray(columns)
        laid.forEach { row ->
            row.forEachIndexed { index, box -> columnWidth[index] = maxOf(columnWidth[index], box.width) }
        }
        val columnGap = size * 0.7f
        val rowGap = size * 0.35f
        val rowAscents = laid.map { row -> row.maxOf { it.ascent } }
        val rowDescents = laid.map { row -> row.maxOf { it.descent } }
        val width = columnWidth.sum() + columnGap * (columns - 1).coerceAtLeast(0)
        var height = 0f
        rowAscents.forEachIndexed { index, ascent -> height += ascent + rowDescents[index] + if (index < rowAscents.size - 1) rowGap else 0f }
        val baseline = rowAscents.first()
        val ops = ArrayList<LatexOp>()
        var y = 0f
        laid.forEachIndexed { rowIndex, row ->
            var x = 0f
            row.forEachIndexed { cellIndex, box ->
                val cellWidth = columnWidth[cellIndex]
                ops += translate(box.ops, x + (cellWidth - box.width) / 2f, y + rowAscents[rowIndex] - box.ascent)
                x += cellWidth + columnGap
            }
            y += rowAscents[rowIndex] + rowDescents[rowIndex] + rowGap
        }
        val inner = LatexBox(width, baseline, height - baseline, ops)
        if (node.left.isEmpty() && node.right.isEmpty()) return inner
        return wrap(inner, node.left, node.right, size, metrics)
    }

    /** 用定界符把任意盒子包起来（矩阵 / cases 用） */
    private fun wrap(body: LatexBox, left: String, right: String, size: Float, metrics: LatexMetrics): LatexBox {
        val plainHeight = metrics.ascent(size) + metrics.descent(size)
        val scale = maxOf(1f, (body.height + size * 0.1f) / plainHeight)
        val leftBox = if (left.isEmpty()) null else glyphs(left, size * scale, metrics, italic = false)
        val rightBox = if (right.isEmpty()) null else glyphs(right, size * scale, metrics, italic = false)
        val gap = size * 0.1f
        val leftWidth = leftBox?.width ?: 0f
        val rightWidth = rightBox?.width ?: 0f
        val width = leftWidth + gap + body.width + gap + rightWidth
        val leftTop = if (leftBox == null) 0f else (body.height - leftBox.height) / 2f
        val rightTop = if (rightBox == null) 0f else (body.height - rightBox.height) / 2f
        val top = minOf(0f, leftTop, rightTop)
        val bottom = maxOf(body.height, leftTop + (leftBox?.height ?: 0f), rightTop + (rightBox?.height ?: 0f))
        val padTop = -top
        val ops = ArrayList<LatexOp>()
        leftBox?.let { ops += translate(it.ops, 0f, leftTop + padTop) }
        ops += translate(body.ops, leftWidth + gap, padTop)
        rightBox?.let { ops += translate(it.ops, leftWidth + gap + body.width + gap, rightTop + padTop) }
        val baseline = padTop + body.ascent
        return LatexBox(width, baseline, bottom + padTop - baseline, ops)
    }

    // ------------------------------------------------------------ 工具

    private fun translate(ops: List<LatexOp>, dx: Float, dy: Float): List<LatexOp> {
        if (dx == 0f && dy == 0f) return ops
        return ops.map { op ->
            when (op) {
                is LatexOp.Glyphs -> op.copy(x = op.x + dx, baseline = op.baseline + dy)
                is LatexOp.Rule -> op.copy(x = op.x + dx, top = op.top + dy)
                is LatexOp.Stroke -> op.copy(points = op.points.map { (x, y) -> (x + dx) to (y + dy) })
            }
        }
    }
}
