package com.adsh.app.ui.blocks

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OMML → LaTeX：Word 公式的结构翻译（分式 / 上下标 / 根号 / 大运算符 / 矩阵 / 直立体文字）。
 *
 * 夹具是 XML 片段（DOM 不开命名空间，前缀按全名取）；断言只看「翻出来的结构对不对」，
 * 排版本身由 LatexCore 的单测覆盖。
 */
class WordMathTest {

    private fun math(xml: String): String {
        val root = parseXml(
            "<m:oMath xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'" +
                " xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>" + xml + "</m:oMath>",
        )
        return ommlToLatex(root.documentElement)
    }

    private fun run(text: String, plain: Boolean = false): String = if (plain) {
        "<m:r><m:rPr><m:sty m:val=\"p\"/></m:rPr><m:t>" + text + "</m:t></m:r>"
    } else {
        "<m:r><m:t>" + text + "</m:t></m:r>"
    }

    @Test
    fun `分式翻成 frac`() {
        val latex = math("<m:f><m:num>" + run("U · I") + "</m:num><m:den>" + run("v") + "</m:den></m:f>")
        assertTrue(latex, latex.startsWith("\\frac{") && latex.contains("}{"))
    }

    @Test
    fun `上标与下标翻成脚本`() {
        assertTrue(math("<m:sSup><m:e>" + run("a") + "</m:e><m:sup>" + run("2") + "</m:sup></m:sSup>").contains("^{2}"))
        assertTrue(math("<m:sSub><m:e>" + run("a") + "</m:e><m:sub>" + run("n") + "</m:sub></m:sSub>").contains("_{n}"))
    }

    @Test
    fun `带次数的根号翻成 sqrt 的可选参数`() {
        val latex = math("<m:rad><m:deg>" + run("3") + "</m:deg><m:e>" + run("x") + "</m:e></m:rad>")
        assertTrue(latex, latex == "\\sqrt[3]{x}")
        val square = math("<m:rad><m:deg/><m:e>" + run("y") + "</m:e></m:rad>")
        assertTrue(square, square == "\\sqrt{y}")
    }

    @Test
    fun `求和带上下限`() {
        val latex = math(
            "<m:nary><m:naryPr><m:chr m:val=\"∑\"/></m:naryPr>" +
                "<m:sub>" + run("i=0") + "</m:sub><m:sup>" + run("n") + "</m:sup>" +
                "<m:e>" + run("x") + "</m:e></m:nary>",
        )
        assertTrue(latex, latex.startsWith("\\sum_{i=0}^{n}{x}"))
    }

    @Test
    fun `矩阵翻成 matrix 环境`() {
        val latex = math(
            "<m:m><m:mr><m:e>" + run("a") + "</m:e><m:e>" + run("b") + "</m:e></m:mr>" +
                "<m:mr><m:e>" + run("c") + "</m:e><m:e>" + run("d") + "</m:e></m:mr></m:m>",
        )
        assertTrue(latex, latex.contains("\\begin{matrix}") && latex.contains("&") && latex.contains("\\\\"))
    }

    @Test
    fun `中文与直立体 run 用 mathrm 保住空格`() {
        val latex = math(run("打底：q = ") + "<m:f><m:num>" + run("U") + "</m:num><m:den>" + run("v") + "</m:den></m:f>")
        assertTrue(latex, latex.startsWith("\\mathrm{打底：q = }"))
        val plain = math(run("mm", plain = true))
        assertTrue(plain, plain == "\\mathrm{mm}")
    }

    @Test
    fun `认不出的结构按子节点拍平 不丢文字`() {
        val latex = math("<m:box><m:e>" + run("x + 1") + "</m:e></m:box>")
        assertTrue(latex, latex.contains("x + 1"))
    }
}
