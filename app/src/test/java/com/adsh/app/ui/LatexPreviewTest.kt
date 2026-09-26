package com.adsh.app.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公式排版的**目视检查**（顺带当渲染冒烟测试）：
 * 把一批公式用 AWT 按同一份几何画成一张 PNG 落到临时目录，并打印路径 ——
 * 用来肉眼确认分数线 / 根号 / 上下标 / 矩阵的几何对不对（Android 那边只是换一个 Paint，
 * 几何完全共用 LatexLayout）。
 *
 *   ./scripts/build-debug.sh :app:testDebugUnitTest --tests "*LatexPreviewTest*"
 */
class LatexPreviewTest {

    /** AWT 的文字度量：与 Android 侧同构（Serif / 斜体区分变量） */
    private class AwtMetrics : LatexMetrics {
        private val context = FontRenderContext(null, true, true)
        private fun font(size: Float, italic: Boolean): Font =
            Font(Font.SERIF, if (italic) Font.ITALIC else Font.PLAIN, 1).deriveFont(size)

        override fun width(text: String, size: Float, italic: Boolean): Float =
            font(size, italic).getStringBounds(text, context).width.toFloat()

        override fun ascent(size: Float): Float = font(size, false).getLineMetrics("x", context).ascent

        override fun descent(size: Float): Float = font(size, false).getLineMetrics("x", context).descent
    }

    private val cases = listOf(
        "fraction" to "\\frac{a+b}{2c}",
        "quadratic" to "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
        "scripts" to "x_i^2 + y_{n+1}",
        "sum" to "\\sum_{i=1}^{n} i^2 = \\frac{n(n+1)(2n+1)}{6}",
        "integral" to "\\int_0^\\infty e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
        "greek" to "\\alpha\\beta\\gamma \\le \\infty \\times \\cdot \\approx",
        "matrix" to "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
        "cases" to "f(x) = \\begin{cases} x^2 & x \\ge 0 \\\\ -x & x < 0 \\end{cases}",
        "delim" to "\\left( \\frac{1}{x} \\right)^{n}",
        "accent" to "\\hat{x} + \\bar{y} + \\vec{v} + \\dot{z}",
        "text" to "\\text{if } x > 0 \\text{ then } y = 1",
        "cube" to "\\sqrt[3]{x+1}",
    )

    @Test
    fun previewRendersToPng() {
        val directory = System.getProperty("java.io.tmpdir") ?: "."
        val metrics = AwtMetrics()
        val size = 30f
        val lineGap = 26
        val width = 900
        var height = 20
        val boxes = cases.map { (name, latex) ->
            val box = LatexLayout.layout(LatexParser.parse(latex), size, metrics, display = true)
            height += box.height.toInt() + lineGap
            Triple(name, latex, box)
        }
        val image = BufferedImage(width, height + 20, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height + 20)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        var y = 20f
        boxes.forEach { (name, latex, box) ->
            g.color = Color(0x99, 0x99, 0x99)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            g.drawString(name + "   " + latex, 10f, y - 6f)
            g.color = Color(0x11, 0x11, 0x11)
            g.translate(40.0, y.toDouble())
            box.ops.forEach { op ->
                when (op) {
                    is LatexOp.Glyphs -> {
                        g.font = Font(Font.SERIF, if (op.italic) Font.ITALIC else Font.PLAIN, 1).deriveFont(op.size)
                        g.drawString(op.text, op.x, op.baseline)
                    }
                    is LatexOp.Rule -> {
                        g.fillRect(op.x.toInt(), op.top.toInt(), op.width.toInt(), op.thickness.toInt().coerceAtLeast(1))
                    }
                    is LatexOp.Stroke -> {
                        g.stroke = BasicStroke(op.thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        val path = Path2D.Float()
                        op.points.forEachIndexed { index, point ->
                            if (index == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
                        }
                        g.draw(path)
                    }
                }
            }
            g.translate(-40.0, -y.toDouble())
            y += box.height + lineGap
        }
        g.dispose()
        val target = File(directory)
        target.mkdirs()
        val file = File(target, "adsh-latex-preview.png")
        ImageIO.write(image, "png", file)
        println("ADSH_LATEX_PREVIEW=" + file.absolutePath)
        assertTrue(file.length() > 0)
    }
}
