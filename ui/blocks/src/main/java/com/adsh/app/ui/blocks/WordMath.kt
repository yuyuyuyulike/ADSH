package com.adsh.app.ui.blocks

import org.w3c.dom.Element

/*
 * OMML（Word 的 m:oMath / m:oMathPara）→ LaTeX 源码。
 *
 * 不自己排版公式：聊天里的 LaTeX 已经有盒子模型与位图缓存（[LatexCore] / [Latex]），
 * Word 公式只差一层「结构翻译」。认不出的 m: 结构按子节点顺序拍平 —— 宁可排成一行，
 * 也不能让公式整段消失。
 */

/** 把一段 OMML 元素翻成 LaTeX；结构不认识时退化成按顺序拼接的文本，不抛异常 */
internal fun ommlToLatex(root: Element): String = buildString { appendOmml(root, this) }

private fun appendOmml(node: Element, out: StringBuilder) {
    when (node.tagName) {
        "m:r" -> appendRun(node, out)
        "m:t" -> Unit
        "m:f" -> fraction(node, out)
        "m:sSup" -> script(node, out, "m:sup", "^")
        "m:sSub" -> script(node, out, "m:sub", "_")
        "m:sSubSup" -> {
            out.append('{')
            child(node, "m:e")?.let { appendChildren(it, out) }
            out.append("}_{")
            child(node, "m:sub")?.let { appendChildren(it, out) }
            out.append("}^{")
            child(node, "m:sup")?.let { appendChildren(it, out) }
            out.append('}')
        }
        // 前置上下标没有对应的 LaTeX 结构：拍平成「下标 上标 主体」
        "m:sPre" -> {
            child(node, "m:sub")?.let { appendChildren(it, out) }
            child(node, "m:sup")?.let { appendChildren(it, out) }
            child(node, "m:e")?.let { appendChildren(it, out) }
        }
        "m:rad" -> radical(node, out)
        "m:d" -> delimiters(node, out)
        "m:nary" -> nary(node, out)
        "m:func" -> {
            child(node, "m:fName")?.let { appendFunctionName(it, out) }
            child(node, "m:e")?.let { appendChildren(it, out) }
        }
        "m:limLow" -> {
            child(node, "m:e")?.let { appendChildren(it, out) }
            out.append("_{")
            child(node, "m:lim")?.let { appendChildren(it, out) }
            out.append('}')
        }
        "m:limUpp" -> {
            child(node, "m:e")?.let { appendChildren(it, out) }
            out.append("^{")
            child(node, "m:lim")?.let { appendChildren(it, out) }
            out.append('}')
        }
        "m:acc" -> {
            out.append(accentMark(node)).append('{')
            child(node, "m:e")?.let { appendChildren(it, out) }
            out.append('}')
        }
        "m:bar" -> {
            val bottom = node.children("m:barPr").firstOrNull()
                ?.children("m:pos")?.firstOrNull()?.getAttribute("m:val") == "bot"
            out.append(if (bottom) "\\underline{" else "\\overline{")
            child(node, "m:e")?.let { appendChildren(it, out) }
            out.append('}')
        }
        "m:m" -> matrix(node, out)
        "m:eqArr" -> equationArray(node, out)
        "m:box", "m:borderBox", "m:groupChr", "m:phant" ->
            child(node, "m:e")?.let { appendChildren(it, out) } ?: appendChildren(node, out)
        else -> appendChildren(node, out)
    }
}

private fun appendChildren(node: Element, out: StringBuilder) {
    for (index in 0 until node.childNodes.length) {
        val child = node.childNodes.item(index) as? Element ?: continue
        appendOmml(child, out)
    }
}

private fun child(node: Element, tag: String): Element? = node.children(tag).firstOrNull()

/** 一段 m:r：m:sty 决定直立 / 斜体；中文与多词文本走 \mathrm，单个数学记号直接排 */
private fun appendRun(run: Element, out: StringBuilder) {
    val text = buildString {
        for (index in 0 until run.childNodes.length) {
            val node = run.childNodes.item(index) as? Element ?: continue
            when (node.tagName) {
                "m:t" -> append(node.textContent)
                "w:tab" -> append(' ')
            }
        }
    }
    if (text.isEmpty()) return
    val style = run.children("m:rPr").firstOrNull()?.children("m:sty")?.firstOrNull()
        ?.getAttribute("m:val").orEmpty()
    val plain = style == "p" || style == "b"
    when {
        // 直立体（函数名、中文、带空格的说明文字）：整段按正文排，保留空格
        plain || text.any { it.code > 0x2E7F } -> {
            val safe = text.filterNot { it == '{' || it == '}' || it == '\\' }
            out.append("\\mathrm{").append(safe).append('}')
        }
        else -> out.append(escapeMath(text))
    }
}

/** 数学记号的转义：这些字符在 LaTeX 里有语法含义，加反斜杠后会被当作字符排出来 */
private fun escapeMath(text: String): String = buildString {
    for (ch in text) {
        when (ch) {
            '{', '}', '\\', '^', '_', '&', '$', '%', '#' -> append('\\').append(ch)
            else -> append(ch)
        }
    }
}

private fun fraction(node: Element, out: StringBuilder) {
    out.append("\\frac{")
    child(node, "m:num")?.let { appendChildren(it, out) }
    out.append("}{")
    child(node, "m:den")?.let { appendChildren(it, out) }
    out.append('}')
}

private fun script(node: Element, out: StringBuilder, tag: String, mark: String) {
    out.append('{')
    child(node, "m:e")?.let { appendChildren(it, out) }
    out.append('}').append(mark).append('{')
    child(node, tag)?.let { appendChildren(it, out) }
    out.append('}')
}

private fun radical(node: Element, out: StringBuilder) {
    // m:deg 为空元素时是「平方根」，Word 也允许 degHide 把它藏起来
    val degree = child(node, "m:deg")?.takeIf { hasContent(it) }
    if (degree != null) {
        out.append("\\sqrt[")
        appendChildren(degree, out)
        out.append("]{")
    } else {
        out.append("\\sqrt{")
    }
    child(node, "m:e")?.let { appendChildren(it, out) }
    out.append('}')
}

/** m:d：定界符（括号 / 方括号 / 花括号），默认圆括号 */
private fun delimiters(node: Element, out: StringBuilder) {
    val props = child(node, "m:dPr")
    val left = props?.children("m:begChr")?.firstOrNull()?.getAttribute("m:val").orEmpty()
    val right = props?.children("m:endChr")?.firstOrNull()?.getAttribute("m:val").orEmpty()
    out.append("\\left").append(delimiterTex(left.ifEmpty { "(" }))
    node.children("m:e").forEach { appendChildren(it, out) }
    out.append("\\right").append(delimiterTex(right.ifEmpty { ")" }))
}

/** 定界符字符 → LaTeX（花括号 / 竖线 / 尖括号有专用命令） */
private fun delimiterTex(value: String): String = when (value) {
    "{" -> "\\{"
    "}" -> "\\}"
    "|" -> "|"
    "‖" -> "\\Vert"
    "〈", "⟨" -> "\\langle"
    "〉", "⟩" -> "\\rangle"
    "【", "[" -> "["
    "】", "]" -> "]"
    "⌊" -> "\\lfloor"
    "⌋" -> "\\rfloor"
    "⌈" -> "\\lceil"
    "⌉" -> "\\rceil"
    else -> value
}

private val NARY_SYMBOLS: Map<String, String> = mapOf(
    "∑" to "\\sum", "∏" to "\\prod", "∐" to "\\coprod",
    "∫" to "\\int", "∬" to "\\iint", "∭" to "\\iiint", "∮" to "\\oint",
    "⋃" to "\\bigcup", "⋂" to "\\bigcap",
    "⨁" to "\\bigoplus", "⨂" to "\\bigotimes", "⋁" to "\\bigvee", "⋀" to "\\bigwedge",
)

private fun nary(node: Element, out: StringBuilder) {
    val character = child(node, "m:naryPr")?.children("m:chr")?.firstOrNull()
        ?.getAttribute("m:val").orEmpty().ifEmpty { "∫" }
    val symbol = NARY_SYMBOLS[character]
    if (symbol == null) {
        out.append("\\mathrm{").append(character).append('}')
    } else {
        out.append(symbol)
    }
    val sub = child(node, "m:sub")?.takeIf { hasContent(it) }
    val sup = child(node, "m:sup")?.takeIf { hasContent(it) }
    if (sub != null) {
        out.append("_{")
        appendChildren(sub, out)
        out.append('}')
    }
    if (sup != null) {
        out.append("^{")
        appendChildren(sup, out)
        out.append('}')
    }
    child(node, "m:e")?.let {
        out.append('{')
        appendChildren(it, out)
        out.append('}')
    }
}

private fun hasContent(node: Element): Boolean = node.textContent.isNotEmpty() ||
    (0 until node.childNodes.length).any { node.childNodes.item(it) is Element }

/** 函数名：认识的直接当命令（\tan），不认识按 uid 直立排出 */
private fun appendFunctionName(node: Element, out: StringBuilder) {
    val name = node.textContent.trim()
    if (name.isEmpty()) {
        appendChildren(node, out)
        return
    }
    if (name in KNOWN_FUNCTIONS) out.append('\\').append(name) else out.append("\\mathrm{").append(name).append('}')
}

private val KNOWN_FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh", "coth", "exp", "log", "ln", "lg", "lim", "min", "max",
    "sup", "inf", "det", "dim", "ker", "deg", "gcd", "arg", "mod",
)

/** m:acc 的重音符号：按 m:chr 选命令，认不出就用帽子 */
private fun accentMark(node: Element): String {
    val mark = child(node, "m:accPr")?.children("m:chr")?.firstOrNull()?.getAttribute("m:val").orEmpty()
    return when (mark) {
        "̂", "^" -> "\\hat"
        "̃", "~" -> "\\tilde"
        "̄", "‾" -> "\\bar"
        "⃗", "→" -> "\\vec"
        "̈" -> "\\ddot"
        "̇" -> "\\dot"
        "̲", "_" -> "\\underline"
        else -> "\\hat"
    }
}

/** m:m：矩阵（m:mr 是行，m:e 是格） */
private fun matrix(node: Element, out: StringBuilder) {
    out.append("\\begin{matrix}")
    node.children("m:mr").forEachIndexed { index, row ->
        if (index > 0) out.append("\\\\")
        row.children("m:e").forEachIndexed { cellIndex, cell ->
            if (cellIndex > 0) out.append('&')
            appendChildren(cell, out)
        }
    }
    out.append("\\end{matrix}")
}

/** m:eqArr：多行等式（对齐环境） */
private fun equationArray(node: Element, out: StringBuilder) {
    out.append("\\begin{aligned}")
    node.children("m:e").forEachIndexed { index, row ->
        if (index > 0) out.append("\\\\")
        appendChildren(row, out)
    }
    out.append("\\end{aligned}")
}
