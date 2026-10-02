package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 公式的解析与排版（LatexCore.kt 是纯 Kotlin，所以这些用例跑在 JVM 上）。
 *
 * 度量用假的：每个字符宽 0.5em、基线以上 0.75em、基线以下 0.25em ——
 * 断言的几何关系（谁比谁高、分式有没有分数线、根号有没有笔画）与字体无关。
 */
class LatexTest {

    private val metrics = object : LatexMetrics {
        override fun width(text: String, size: Float, italic: Boolean): Float = text.length * size * 0.5f
        override fun ascent(size: Float): Float = size * 0.75f
        override fun descent(size: Float): Float = size * 0.25f
    }

    private fun layout(latex: String, display: Boolean = false): LatexBox =
        LatexLayout.layout(LatexParser.parse(latex), 20f, metrics, display)

    /** 解析结果的顶层永远是 Seq（parse 的实现如此），测试里按类型捞需要的节点 */
    private inline fun <reified T : LatexNode> top(latex: String): T =
        (LatexParser.parse(latex) as LatexNode.Seq).items.filterIsInstance<T>().first()

    /** 把节点树拍平成文字（断言「有没有丢内容」用） */
    private fun flatten(node: LatexNode): String = when (node) {
        is LatexNode.Glyphs -> node.text
        is LatexNode.Seq -> node.items.joinToString("") { flatten(it) }
        is LatexNode.Space -> " "
        is LatexNode.Script -> flatten(node.base) + (node.sup?.let { flatten(it) } ?: "") + (node.sub?.let { flatten(it) } ?: "")
        is LatexNode.Frac -> flatten(node.num) + "/" + flatten(node.den)
        is LatexNode.Sqrt -> "√" + flatten(node.body)
        is LatexNode.Big -> node.symbol
        is LatexNode.Delim -> node.left + flatten(node.body) + node.right
        is LatexNode.Accent -> flatten(node.body)
        is LatexNode.Grid -> node.rows.joinToString("; ") { row -> row.joinToString(" ") { flatten(it) } }
    }

    @Test
    fun superscriptAndSubscriptBecomeScriptNodes() {
        val node = LatexParser.parse("x^2_i")
        assertTrue(node is LatexNode.Seq)
        val script = (node as LatexNode.Seq).items.single()
        assertTrue(script is LatexNode.Script)
        script as LatexNode.Script
        assertEquals(LatexNode.Glyphs("x", italic = true), script.base)
        assertEquals(LatexNode.Glyphs("2", italic = false), script.sup)
        assertEquals(LatexNode.Glyphs("i", italic = true), script.sub)
    }

    @Test
    fun greekAndOperatorsComeFromTheSymbolTable() {
        val node = LatexParser.parse("\\alpha\\beta \\times \\infty")
        val text = (node as LatexNode.Seq).items
            .filterIsInstance<LatexNode.Glyphs>()
            .joinToString("") { it.text }
        assertEquals("αβ×∞", text)
    }

    @Test
    fun fractionHasARuleAndGrowsDownwards() {
        val box = layout("\\frac{a}{b}", display = true)
        assertTrue("分式要有分数线", box.ops.any { it is LatexOp.Rule })
        assertTrue("分式总高要大于单个字符", box.height > metrics.ascent(20f) + metrics.descent(20f))
        assertTrue("分式要比分子宽", box.width > metrics.width("a", 20f, true))
    }

    @Test
    fun sqrtDrawsARadicalStroke() {
        val box = layout("\\sqrt{x+1}")
        assertTrue("根号要有笔画", box.ops.any { it is LatexOp.Stroke })
        assertTrue(box.width > metrics.width("x+1", 20f, true))
    }

    @Test
    fun bigOperatorStacksLimitsOnlyInDisplayMode() {
        val inline = layout("\\sum_{i=1}^{n}", display = false)
        val display = layout("\\sum_{i=1}^{n}", display = true)
        // 行内：上下限排在右边 → 宽而矮
        assertTrue("行内形态应该更宽", inline.width > display.width)
        // 显示：上下限堆叠 → 高
        assertTrue("显示形态应该更高", display.height > inline.height)
    }

    @Test
    fun matrixKeepsRowsAndWrapsWithDelimiters() {
        val node = top<LatexNode.Grid>("\\begin{pmatrix}a & b \\\\ c & d\\end{pmatrix}")
        assertEquals(2, node.rows.size)
        assertEquals(2, node.rows.first().size)
        assertEquals("(", node.left)
        assertEquals(")", node.right)
        val box = LatexLayout.layout(node, 20f, metrics, display = true)
        assertTrue(box.width > metrics.width("ac", 20f, true))
    }

    @Test
    fun accentsAndTextCommands() {
        val hat = layout("\\hat{x}")
        assertTrue("重音要抬高基线以上", hat.ascent > layout("x").ascent)
        val text = LatexParser.parse("\\text{if }x>0")
        val glyphs = ((text as LatexNode.Seq).items.filterIsInstance<LatexNode.Glyphs>())
        assertEquals("if ", glyphs.first().text)
        assertTrue("\\text 里是直立的", !glyphs.first().italic)
    }

    @Test
    fun unknownCommandsDegradeToTextWithoutThrowing() {
        val text = flatten(LatexParser.parse("\\foobar{x}"))
        assertTrue("认不出来的命令按名字排", text.startsWith("foobar"))
        // 括号里的 x 也得排出来（不能丢）
        assertTrue(text.contains("x"))
    }

    @Test
    fun deepNestingAndHugeInputDoNotBlowUp() {
        val deep = "\\frac{".repeat(60) + "x" + "}{x}".repeat(60)
        val box = layout(deep)
        assertTrue(box.width >= 0f)
        val huge = "x^2 ".repeat(3000)
        val node = LatexParser.parse(huge)
        assertNotNull(node)
        assertTrue("超长输入退回纯文本", node is LatexNode.Glyphs)
    }

    @Test
    fun leftRightDelimitersScaleWithTheBody() {
        val box = layout("\\left(\\frac{a}{b}\\right)")
        val single = layout("(a)")
        assertTrue("包住分式时括号要比单字符高", box.height > single.height)
    }

    // ------------------------------------------------------------ Markdown 侧

    @Test
    fun displayMathBecomesItsOwnBlock() {
        val single = parseMarkdown("前文\n\n\$\$E = mc^2\$\$\n\n后文")
        assertEquals(1, single.filterIsInstance<MdBlock.Math>().size)
        assertEquals("E = mc^2", single.filterIsInstance<MdBlock.Math>().first().latex)

        val multi = parseMarkdown("\$\$\n\\frac{a}{b}\n\$\$")
        assertEquals("\\frac{a}{b}", multi.filterIsInstance<MdBlock.Math>().first().latex)

        val bracket = parseMarkdown("\\[x^2\\]")
        assertEquals("x^2", bracket.filterIsInstance<MdBlock.Math>().first().latex)
    }

    @Test
    fun inlineMathHeuristicKeepsCurrencyOut() {
        // 单符号 / 带 LaTeX 标记的算公式
        assertTrue(looksLikeMath("x"))
        assertTrue(looksLikeMath("x^2"))
        assertTrue(looksLikeMath("\\frac{a}{b}"))
        assertTrue(looksLikeMath("a+b"))
        // 中文里的美元（含空格、没有标记）不算公式
        assertTrue(!looksLikeMath("5 到 "))
        assertTrue(!looksLikeMath("100 元"))
        assertTrue(!looksLikeMath(""))
    }
}
