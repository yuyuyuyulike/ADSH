package com.adsh.app.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * .docx 解析：继承链（docDefaults → 样式 → 直接格式）、run 格式、段落格式、表格结构。
 *
 * 夹具是**现造的最小组件**（ZIP + 三个 XML 部件）—— 不入库二进制文档，格式契约在用例里一眼看清。
 */
class WordReadTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 造一个最小 .docx：document.xml 必填，styles.xml / numbering.xml 可选 */
    private fun docx(document: String, styles: String? = null, numbering: String? = null): File {
        val file = temp.newFile("test.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("word/document.xml", document)
            if (styles != null) put("word/styles.xml", styles)
            if (numbering != null) put("word/numbering.xml", numbering)
        }
        return file
    }

    private fun paras(blocks: List<WordBlock>): List<WordPara> =
        blocks.filterIsInstance<WordBlock.Para>().map { it.para }

    private fun textOf(para: WordPara): String =
        para.inlines.filterIsInstance<WordInline.Text>().joinToString("") { it.run.text }

    private fun runsOf(para: WordPara): List<WordRun> =
        para.inlines.filterIsInstance<WordInline.Text>().map { it.run }

    // 不能用 trimIndent 的多行模板：$content 自己带多行时公共缩进会被算成 0，
    // 于是 XML 声明前留下一行空白 → 解析器拒绝（声明必须在文档最开头）。
    private fun body(content: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document " + NS + "><w:body>" + content + "</w:body></w:document>"

    @Test
    fun `样式链决定字号与标题档，直接格式覆盖样式`() {
        val file = docx(
            document = body(
                """
                <w:p><w:r><w:t>正文</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>标题</w:t></w:r></w:p>
                <w:p><w:pPr><w:jc w:val="center"/><w:spacing w:before="240" w:after="120"/></w:pPr>
                     <w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:t>居中大字</w:t></w:r></w:p>
                """.trimIndent(),
            ),
            styles = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:styles $NS>
                  <w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults>
                  <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>
                    <w:rPr><w:b/><w:sz w:val="44"/></w:rPr>
                    <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
                  </w:style>
                </w:styles>
            """.trimIndent(),
        )
        val parsed = paras(readWordDoc(file).blocks)
        assertEquals(3, parsed.size)
        // 文档默认 24 半磅 = 12 磅
        assertEquals(12f, parsed[0].sizePt, 0.01f)
        // 标题：样式里的 44 半磅 = 22 磅 + 加粗 + 大纲级别 → 1 档
        assertEquals(22f, parsed[1].sizePt, 0.01f)
        assertTrue(parsed[1].bold)
        assertEquals(1, parsed[1].heading)
        // 直接格式的 sz 只改这一段的 run（段落默认仍是 12 磅）；居中；段前 / 段后是 twip → dp（1/15）
        assertEquals(12f, parsed[2].sizePt, 0.01f)
        assertEquals(14f, runsOf(parsed[2]).single().sizePt, 0.01f)
        assertEquals(WordAlign.CENTER, parsed[2].align)
        assertEquals(16f, parsed[2].spaceBeforeDp, 0.01f)
        assertEquals(8f, parsed[2].spaceAfterDp, 0.01f)
    }

    @Test
    fun `run 的粗斜下划线字号颜色各自生效`() {
        val file = docx(
            document = body(
                """
                <w:p><w:r><w:t>普通</w:t></w:r>
                     <w:r><w:rPr><w:b/><w:i/><w:u w:val="single"/><w:sz w:val="32"/><w:color w:val="FF0000"/></w:rPr>
                          <w:t>强调</w:t></w:r></w:p>
                """.trimIndent(),
            ),
        )
        val para = paras(readWordDoc(file).blocks).single()
        assertEquals("普通强调", textOf(para))
        val runs = runsOf(para)
        assertEquals(2, runs.size)
        val run = runs[1]
        assertTrue(run.bold)
        assertTrue(run.italic)
        assertTrue(run.underline)
        assertEquals(16f, run.sizePt, 0.01f)
        assertEquals(0xFF0000, run.color)
    }

    @Test
    fun `表格读得出列宽合并底纹边框与行高`() {
        val file = docx(
            document = body(
                """
                <w:tbl>
                  <w:tblPr>
                    <w:tblW w:w="6000" w:type="dxa"/>
                    <w:jc w:val="center"/>
                    <w:tblBorders>
                      <w:top w:val="single" w:sz="8" w:color="000000"/>
                      <w:insideH w:val="single" w:sz="4"/>
                      <w:insideV w:val="single" w:sz="4"/>
                    </w:tblBorders>
                    <w:tblCellMar><w:left w:w="72" w:type="dxa"/></w:tblCellMar>
                  </w:tblPr>
                  <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="4000"/></w:tblGrid>
                  <w:tr>
                    <w:trPr><w:trHeight w:val="480"/></w:trPr>
                    <w:tc>
                      <w:tcPr><w:gridSpan w:val="2"/><w:shd w:fill="F2F2F2"/><w:vAlign w:val="center"/></w:tcPr>
                      <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>合并表头</w:t></w:r></w:p>
                    </w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>纵向</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>右</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>
                    <w:tc><w:p><w:r><w:t>右下</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )
        val table = readWordDoc(file).blocks.filterIsInstance<WordBlock.Table>().single()
        assertEquals(listOf(2000, 4000), table.grid)
        assertEquals(6000, table.widthTw)
        assertEquals(WordAlign.CENTER, table.align)
        assertEquals(72, table.cellMarginTw)
        assertEquals(8, table.borders[WordBorderEdge.TOP]?.widthEighthPt)
        assertEquals(3, table.rows.size)
        // 第一行：两列合并、底纹、纵向居中、行高 480 twip = 32dp
        val header = table.rows[0].cells.single()
        assertEquals(2, header.span)
        assertEquals(0xF2F2F2, header.shading)
        assertEquals(WordVAlign.CENTER, header.vAlign)
        assertEquals(32f, table.rows[0].heightDp ?: 0f, 0.01f)
        // 第二 / 三行：起始格 + 续格
        assertFalse(table.rows[1].cells[0].vContinue)
        assertTrue(table.rows[2].cells[0].vContinue)
    }

    @Test
    fun `编号段带前缀，缩进取编号级别`() {
        val file = docx(
            document = body(
                """
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                     <w:r><w:t>第一条</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                     <w:r><w:t>第二条</w:t></w:r></w:p>
                """.trimIndent(),
            ),
            numbering = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:numbering $NS>
                  <w:abstractNum w:abstractNumId="0">
                    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/>
                      <w:pPr><w:ind w:left="420" w:hanging="420"/></w:pPr></w:lvl>
                  </w:abstractNum>
                  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
                </w:numbering>
            """.trimIndent(),
        )
        val parsed = paras(readWordDoc(file).blocks)
        assertEquals("1.", parsed[0].bullet)
        assertEquals("2.", parsed[1].bullet)
        assertEquals(420 * DP_PER_TWIP, parsed[0].indentLeftDp, 0.01f)
        assertEquals(420 * DP_PER_TWIP, parsed[0].indentRestDp, 0.01f)
    }

    /** 带 OMML 命名空间的正文（公式测试用） */
    private fun bodyWithMath(content: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document " + NS +
            " xmlns:m='http://schemas.openxmlformats.org/officeDocument/2006/math'>" +
            "<w:body>" + content + "</w:body></w:document>"

    @Test
    fun `页面设置读得出页宽与页边距`() {
        val file = docx(
            document = body(
                """
                <w:p><w:r><w:t>正文</w:t></w:r></w:p>
                <w:sectPr>
                  <w:pgSz w:w="10000" w:h="14000"/>
                  <w:pgMar w:top="800" w:right="1200" w:bottom="900" w:left="1000"/>
                </w:sectPr>
                """.trimIndent(),
            ),
        )
        val page = readWordDoc(file).page
        assertEquals(10000, page.widthTw)
        assertEquals(1000, page.marginLeftTw)
        assertEquals(1200, page.marginRightTw)
        assertEquals(800, page.marginTopTw)
        assertEquals(900, page.marginBottomTw)
        // 版心 = 页宽 − 左右边距
        assertEquals(7800, page.widthTw - page.marginLeftTw - page.marginRightTw)
    }

    @Test
    fun `OMML 公式转成 LaTeX 并作为独立块进段落`() {
        val file = docx(
            document = bodyWithMath(
                """
                <w:p><w:pPr><w:jc w:val="center"/></w:pPr>
                  <m:oMathPara><m:oMath>
                    <m:r><m:t>q = </m:t></m:r>
                    <m:f>
                      <m:num><m:r><m:t>U · I</m:t></m:r></m:num>
                      <m:den><m:r><m:t>v</m:t></m:r></m:den>
                    </m:f>
                  </m:oMath></m:oMathPara>
                </w:p>
                <w:p><m:oMath><m:sSup><m:e><m:r><m:t>a</m:t></m:r></m:e>
                  <m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:oMath></w:p>
                """.trimIndent(),
            ),
        )
        val parsed = paras(readWordDoc(file).blocks)
        assertEquals(2, parsed.size)
        val display = parsed[0].inlines.single() as WordInline.Math
        assertTrue(display.display)
        assertTrue("公式应转成 LaTeX 分式，实际：${display.latex}", display.latex.contains("\\frac"))
        val inline = parsed[1].inlines.filterIsInstance<WordInline.Math>().single()
        assertFalse(inline.display)
        assertTrue("上标应写成 ^ 脚本，实际：${inline.latex}", inline.latex.contains("^{"))
    }

    @Test
    fun `run 的上下标写进 vertAlign`() {
        val file = docx(
            document = body(
                """
                <w:p>
                  <w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>2</w:t></w:r>
                  <w:r><w:rPr><w:vertAlign w:val="subscript"/></w:rPr><w:t>n</w:t></w:r>
                </w:p>
                """.trimIndent(),
            ),
        )
        val runs = runsOf(paras(readWordDoc(file).blocks).single())
        assertEquals(WordVertAlign.SUPER, runs[0].vertAlign)
        assertEquals(WordVertAlign.SUB, runs[1].vertAlign)
    }

    @Test
    fun `按内容认 OOXML，不认普通文件`() {
        val file = docx(body(""))
        assertEquals("word", ooxmlKindOf(file))
        assertTrue(readWordDoc(file).blocks.isEmpty())
        assertNull(ooxmlKindOf(temp.newFile("plain.doc").apply { writeText("not a zip") }))
    }

    companion object {
        private const val NS = "xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'"
    }
}
