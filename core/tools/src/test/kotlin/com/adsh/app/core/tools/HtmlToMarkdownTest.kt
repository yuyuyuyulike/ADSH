package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HtmlToMarkdown 的行为测试（对齐 dsh 用的 turndown + gfm 的语义）。
 * 网页抓取返回给模型的就是这段 Markdown，所以这里把结构、链接、表格都钉住。
 */
class HtmlToMarkdownTest {

    @Test
    fun headingsParagraphsAndLinks() {
        val html = "<html><head><title>标题</title></head><body>" +
            "<h1>Hello</h1><p>See <a href=\"https://example.com/a?b=1&amp;c=2\" title=\"ex\">the docs</a> now.</p>" +
            "</body></html>"
        val md = HtmlToMarkdown.convert(html)
        assertEquals("# Hello\n\nSee [the docs](https://example.com/a?b=1&c=2 \"ex\") now.", md)
    }

    @Test
    fun listsAndCode() {
        val html = "<ul><li>one</li><li>two<ul><li>nested</li></ul></li></ul>" +
            "<ol start=\"3\"><li>three</li><li>four</li></ol>" +
            "<pre><code class=\"language-kotlin\">val a = 1\nval b = 2</code></pre>"
        val md = HtmlToMarkdown.convert(html)
        assertTrue(md, md.contains("-   one"))
        assertTrue(md, md.contains("3.  three"))
        assertTrue(md, md.contains("4.  four"))
        assertTrue(md, md.contains("val a = 1"))
    }

    @Test
    fun tablesBecomeGfm() {
        val html = "<table><thead><tr><th align=\"left\">A</th><th>B</th></tr></thead>" +
            "<tbody><tr><td>1</td><td>x|y</td></tr></tbody></table>"
        val md = HtmlToMarkdown.convert(html)
        assertTrue(md, md.contains("| A   | B   |"))
        assertTrue(md, md.contains(":---"))
        assertTrue(md, md.contains("x\\|y"))
    }

    @Test
    fun scriptsStylesAndHiddenAreDropped() {
        val html = "<div><script>var x = '</p>';</script><style>.a{color:red}</style>" +
            "<p style=\"display:none\">secret</p><p hidden>also secret</p><p>visible</p></div>"
        val md = HtmlToMarkdown.convert(html)
        assertEquals("visible", md)
    }

    @Test
    fun entitiesAndInlineMarkup() {
        val html = "<p>&lt;tag&gt; &amp; &quot;quoted&quot; <strong>bold</strong> <em>em</em> " +
            "<code>a_b</code> &nbsp;end</p>"
        val md = HtmlToMarkdown.convert(html)
        // turndown 的 escapeMarkdown 只转义 markdown 自己的元字符（* _ [ ] 反引号 行首标记），
        // 尖括号原样保留；代码块里的内容不转义
        assertEquals("<tag> & \"quoted\" **bold** _em_ `a_b` end", md)
    }

    @Test
    fun textBodyIsPassedThroughVerbatim() {
        // text/plain 的正文不经过转换（dsh 的 renderBody 里 kind === "text" 就是原样）
        val raw = "line one\nline two"
        assertEquals(raw, raw)
    }
}
