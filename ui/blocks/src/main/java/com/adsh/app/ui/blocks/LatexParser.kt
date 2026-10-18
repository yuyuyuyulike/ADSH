package com.adsh.app.ui.blocks

/**
 * LaTeX 公式的解析与排版（**纯 Kotlin，零 Android 依赖** —— 单测可以直接跑）。
 *
 * 为什么不引第三方库：Android 上现成的 LaTeX 渲染器（jlatexmath-android）是 GPL-2.0，
 * 而本仓库是 MIT，装进来会把整个 APK 拖进 GPL；再往上一层（MathJax/KaTeX + WebView）
 * 要么得把几百 KB 的 JS/字体塞进 assets、要么每次渲染都要起一个 WebView，滚动时更卡。
 *
 * 这里做的是「AI 输出里真的会出现的那个子集」：
 *   - 结构：^ _、\frac \dfrac \tfrac、\sqrt（含 \sqrt[n]）、\left…\right、重音
 *     （\hat \bar \vec \dot \ddot \tilde \overline \underline）、\sum \prod \int 等大运算符
 *     （显示模式下上下限，行内模式右侧）、矩阵/方程组（pmatrix/bmatrix/matrix/cases/vmatrix）；
 *   - 符号：希腊字母、常用关系/二元运算符、箭头、定界符、函数名（\sin \log \lim …）；
 *   - \text \mathrm \mathbf \operatorname（直立文本）、\, \; \: \! \quad \qquad（间距）、
 *     \displaystyle \textstyle（忽略：显示/行内由块级与调用方决定）。
 * 认不出来的命令按直立文本原样排（不会抛异常、不会吐红字）。
 *
 * 排版产出是一棵「盒子 + 绘制指令」：坐标以盒子左上角为原点、y 向下、基线在 ascent 处。
 * 绘制由 [LatexImages]（Android 侧）完成，这样这套几何可以脱离设备单测。
 */

// ---------------------------------------------------------------- 语法树

internal sealed interface LatexNode {
    /** 水平排列 */
    data class Seq(val items: List<LatexNode>) : LatexNode

    /** 一段文字：italic = 数学变量（斜体），upright 用于数字/运算符/函数名 */
    data class Glyphs(val text: String, val italic: Boolean = false) : LatexNode

    /** 水平空白（em） */
    data class Space(val em: Float) : LatexNode

    /** 上标 / 下标（可以只有其一） */
    data class Script(val base: LatexNode, val sup: LatexNode? = null, val sub: LatexNode? = null) : LatexNode

    /** 分式：display 由 \dfrac / \tfrac 强制，null 时跟随外层 */
    data class Frac(val num: LatexNode, val den: LatexNode, val display: Boolean? = null) : LatexNode

    /** 根式：\sqrt{x} / \sqrt[n]{x} */
    data class Sqrt(val body: LatexNode, val index: LatexNode? = null) : LatexNode

    /** 大运算符（\sum \int …）带上下限 */
    data class Big(val symbol: String, val sup: LatexNode? = null, val sub: LatexNode? = null) : LatexNode

    /** 自动伸缩的定界符（\left( … \right)） */
    data class Delim(val left: String, val body: LatexNode, val right: String) : LatexNode

    /** 重音：mark ∈ hat / bar / tilde / vec / dot / ddot / underline */
    data class Accent(val body: LatexNode, val mark: String) : LatexNode

    /** 矩阵 / 方程组：rows × cells，左右定界符可以是空串 */
    data class Grid(val rows: List<List<LatexNode>>, val left: String = "", val right: String = "") : LatexNode
}

// ---------------------------------------------------------------- 命令表

/** 一条 LaTeX 命令的排版含义 */
private data class LatexSymbol(
    /** 排出来的字符 */
    val text: String,
    /** 直立（函数名、数字、运算符）还是斜体（变量） */
    val italic: Boolean = false,
    /** 大运算符（上下限在显示模式下堆叠） */
    val big: Boolean = false,
)

/**
 * 命令 → 符号。覆盖 AI 输出里常见的那批；没收录的命令按名字直立排出（见 LatexParser）。
 */
private val LATEX_SYMBOLS: Map<String, LatexSymbol> = buildMap {
    fun sym(name: String, text: String, italic: Boolean = false) {
        put(name, LatexSymbol(text, italic))
    }
    fun big(name: String, text: String) {
        put(name, LatexSymbol(text, italic = false, big = true))
    }
    // 小写希腊
    sym("alpha", "α"); sym("beta", "β"); sym("gamma", "γ"); sym("delta", "δ")
    sym("epsilon", "ε"); sym("varepsilon", "ε"); sym("zeta", "ζ"); sym("eta", "η")
    sym("theta", "θ"); sym("vartheta", "ϑ"); sym("iota", "ι"); sym("kappa", "κ")
    sym("lambda", "λ"); sym("mu", "μ"); sym("nu", "ν"); sym("xi", "ξ"); sym("pi", "π")
    sym("varpi", "ϖ"); sym("rho", "ρ"); sym("varrho", "ϱ"); sym("sigma", "σ"); sym("varsigma", "ς")
    sym("tau", "τ"); sym("upsilon", "υ"); sym("phi", "φ"); sym("varphi", "ϕ"); sym("chi", "χ")
    sym("psi", "ψ"); sym("omega", "ω")
    // 大写希腊
    sym("Gamma", "Γ"); sym("Delta", "Δ"); sym("Theta", "Θ"); sym("Lambda", "Λ"); sym("Xi", "Ξ")
    sym("Pi", "Π"); sym("Sigma", "Σ"); sym("Upsilon", "Υ"); sym("Phi", "Φ"); sym("Psi", "Ψ")
    sym("Omega", "Ω")
    // 二元运算 / 关系
    sym("times", "×"); sym("div", "÷"); sym("cdot", "⋅"); sym("pm", "±"); sym("mp", "∓")
    sym("ast", "∗"); sym("star", "⋆"); sym("circ", "∘"); sym("bullet", "∙"); sym("oplus", "⊕")
    sym("ominus", "⊖"); sym("otimes", "⊗"); sym("oslash", "⊘"); sym("odot", "⊙"); sym("wedge", "∧")
    sym("vee", "∨"); sym("cap", "∩"); sym("cup", "∪"); sym("setminus", "∖")
    sym("le", "≤"); sym("leq", "≤"); sym("ge", "≥"); sym("geq", "≥"); sym("ne", "≠"); sym("neq", "≠")
    sym("approx", "≈"); sym("equiv", "≡"); sym("sim", "∼"); sym("simeq", "≃"); sym("cong", "≅")
    sym("propto", "∝"); sym("ll", "≪"); sym("gg", "≫"); sym("prec", "≺"); sym("succ", "≻")
    sym("in", "∈"); sym("notin", "∉"); sym("ni", "∋"); sym("subset", "⊂"); sym("subseteq", "⊆")
    sym("supset", "⊃"); sym("supseteq", "⊇"); sym("emptyset", "∅"); sym("varnothing", "∅")
    sym("forall", "∀"); sym("exists", "∃"); sym("nexists", "∄"); sym("neg", "¬"); sym("lnot", "¬")
    sym("land", "∧"); sym("lor", "∨"); sym("perp", "⊥"); sym("parallel", "∥"); sym("angle", "∠")
    sym("triangle", "△"); sym("square", "□"); sym("partial", "∂"); sym("nabla", "∇")
    sym("infty", "∞"); sym("aleph", "ℵ"); sym("hbar", "ℏ"); sym("ell", "ℓ"); sym("Re", "ℜ"); sym("Im", "ℑ")
    sym("degree", "°"); sym("prime", "′")
    // 箭头
    sym("to", "→"); sym("rightarrow", "→"); sym("leftarrow", "←"); sym("leftrightarrow", "↔")
    sym("Rightarrow", "⇒"); sym("Leftarrow", "⇐"); sym("Leftrightarrow", "⇔"); sym("mapsto", "↦")
    sym("uparrow", "↑"); sym("downarrow", "↓"); sym("longrightarrow", "⟶"); sym("longleftarrow", "⟵")
    // 省略号 / 点
    sym("ldots", "…"); sym("dots", "…"); sym("cdots", "⋯"); sym("vdots", "⋮"); sym("ddots", "⋱")
    // 定界符（字符形式）
    sym("langle", "⟨"); sym("rangle", "⟩"); sym("lceil", "⌈"); sym("rceil", "⌉")
    sym("lfloor", "⌊"); sym("rfloor", "⌋"); sym("lvert", "|"); sym("rvert", "|")
    sym("lVert", "‖"); sym("rVert", "‖"); sym("vert", "|"); sym("Vert", "‖"); sym("mid", "|")
    sym("backslash", "\\"); sym("|", "‖")
    // 函数名（直立）
    for (name in listOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "coth", "exp", "log", "ln", "lg", "lim", "limsup", "liminf",
        "min", "max", "sup", "inf", "det", "dim", "ker", "deg", "gcd", "hom", "arg", "Pr",
        "mod", "bmod", "pmod", "operatorname",
    )) {
        sym(name, name)
    }
    // 大运算符
    big("sum", "∑"); big("prod", "∏"); big("coprod", "∐"); big("int", "∫"); big("iint", "∬")
    big("iiint", "∭"); big("oint", "∮"); big("bigcup", "⋃"); big("bigcap", "⋂")
    big("bigoplus", "⨁"); big("bigotimes", "⨂"); big("bigvee", "⋁"); big("bigwedge", "⋀")
    big("lim", "lim"); big("intop", "∫"); big("smallint", "∫")
}

/** 间距命令 → em */
private val LATEX_SPACES: Map<String, Float> = mapOf(
    "," to 0.167f, ":" to 0.222f, ";" to 0.278f, "!" to -0.167f, " " to 0.333f,
    "quad" to 1f, "qquad" to 2f, "enspace" to 0.5f, "thinspace" to 0.167f,
)

/** 重音命令 → mark */
private val LATEX_ACCENTS: Map<String, String> = mapOf(
    "hat" to "hat", "widehat" to "hat", "bar" to "bar", "overline" to "bar",
    "tilde" to "tilde", "widetilde" to "tilde", "vec" to "vec", "dot" to "dot",
    "ddot" to "ddot", "underline" to "underline",
)

/** 矩阵环境 → 左右定界符 */
private val LATEX_ENVIRONMENTS: Map<String, Pair<String, String>> = mapOf(
    "matrix" to ("" to ""),
    "pmatrix" to ("(" to ")"),
    "bmatrix" to ("[" to "]"),
    "Bmatrix" to ("{" to "}"),
    "vmatrix" to ("|" to "|"),
    "Vmatrix" to ("‖" to "‖"),
    "cases" to ("{" to ""),
    "aligned" to ("" to ""),
    "array" to ("" to ""),
    "smallmatrix" to ("" to ""),
)

// ---------------------------------------------------------------- 解析

/**
 * 递归下降解析。宽容优先：任何不认识的东西都排成文字，绝不抛异常
 * （公式来自模型输出，坏一个字符不该让整条消息渲染不出来）。
 */
internal object LatexParser {

    /** 输入上限：超过就按纯文本排（防一次渲染把内存/时间拖爆） */
    const val MAX_SOURCE = 4000

    /** 嵌套深度上限（\frac{\frac{\frac…}}} 这种） */
    const val MAX_DEPTH = 24

    fun parse(source: String): LatexNode {
        if (source.length > MAX_SOURCE) return LatexNode.Glyphs(source, italic = false)
        return runCatching { State(source).parseAll() }.getOrElse { LatexNode.Glyphs(source, italic = false) }
    }

    private class State(val src: String) {
        var i = 0
        var depth = 0

        fun parseAll(): LatexNode {
            val node = sequence(stops = "", stopCommand = null, stopRowBreak = false)
            skipSpaces()
            return node
        }

        private fun skipSpaces() {
            while (i < src.length && (src[i] == ' ' || src[i] == '\n' || src[i] == '\t' || src[i] == '\r')) i++
        }

        /** 往后看一个命令名（不消费）；不是命令返回 null */
        fun peekCommand(): String? {
            if (i >= src.length || src[i] != '\\') return null
            var j = i + 1
            val start = j
            while (j < src.length && src[j].isLetter()) j++
            return if (j == start) null else src.substring(start, j)
        }

        /**
         * 水平序列。
         * @param stops 这些字符出现就停（例如 "}" 或 "&"）
         * @param stopCommand 这个命令出现就停（例如 "right" / "end"）
         * @param stopRowBreak 遇到 \\ 就停（矩阵换行）
         */
        fun sequence(stops: String, stopCommand: String?, stopRowBreak: Boolean): LatexNode {
            val items = ArrayList<LatexNode>()
            while (i < src.length) {
                // 数学模式里空白不排版，先跳过再判分隔符 ——
                // 否则 "a & b" 里的空格会让 & 被当成普通原子吃掉（矩阵会塌成一行）
                skipSpaces()
                if (i >= src.length) break
                if (stops.isNotEmpty() && stops.indexOf(src[i]) >= 0) break
                if (stopRowBreak && src.startsWith("\\\\", i)) break
                val cmd = peekCommand()
                if (cmd != null && stopCommand != null && cmd == stopCommand) break
                val before = i
                val atom = atom() ?: continue
                var node = atom
                var sup: LatexNode? = null
                var sub: LatexNode? = null
                while (i < src.length && (src[i] == '^' || src[i] == '_')) {
                    val kind = src[i]
                    i++
                    val arg = scriptArgument()
                    if (kind == '^') sup = arg else sub = arg
                }
                if (sup != null || sub != null) node = LatexNode.Script(node, sup, sub)
                items += node
                if (i == before) i++ // 兜底：任何情况下都要前进，绝不空转
            }
            return LatexNode.Seq(items)
        }

        /** 上下标的参数：{…} 或单个原子（LaTeX 的规则） */
        private fun scriptArgument(): LatexNode {
            skipSpaces()
            if (i < src.length && src[i] == '{') return group()
            return atom()
                ?: LatexNode.Seq(emptyList())
        }

        /** 花括号分组（消费 { 与 }） */
        fun group(): LatexNode {
            if (i >= src.length || src[i] != '{') {
                return atom() ?: LatexNode.Seq(emptyList())
            }
            i++ // {
            depth++
            val inner = if (depth > MAX_DEPTH) {
                // 太深：吃掉这一层但不继续展开
                skipGroup()
                LatexNode.Glyphs("…", italic = false)
            } else {
                sequence("}", null, false)
            }
            depth--
            if (i < src.length && src[i] == '}') i++
            return inner
        }

        /** 只跳过一层花括号（不建节点） */
        private fun skipGroup() {
            var level = 1
            while (i < src.length && level > 0) {
                when (src[i]) {
                    '{' -> level++
                    '}' -> level--
                }
                i++
            }
        }

        /** 取一段花括号里的**原文**（\text{…} / \begin{…} 用） */
        private fun groupText(): String {
            skipSpaces()
            if (i >= src.length || src[i] != '{') return ""
            i++
            val start = i
            var level = 1
            while (i < src.length && level > 0) {
                when (src[i]) {
                    '{' -> level++
                    '}' -> level--
                }
                if (level > 0) i++
            }
            val text = src.substring(start, i)
            if (i < src.length && src[i] == '}') i++
            return text
        }

        /** 可选参数 [n] */
        private fun optionalGroup(): LatexNode? {
            skipSpaces()
            if (i >= src.length || src[i] != '[') return null
            val close = src.indexOf(']', i + 1)
            if (close < 0) return null
            val inner = src.substring(i + 1, close)
            i = close + 1
            return parse(inner)
        }

        /** \left \right 后面的定界符：单个字符或一个命令 */
        private fun delimiter(): String {
            skipSpaces()
            if (i >= src.length) return ""
            if (src[i] == '\\') {
                val cmd = peekCommand()
                if (cmd != null) {
                    i += 1 + cmd.length
                    if (cmd == "lbrace" || cmd == "{") return "{"
                    if (cmd == "rbrace" || cmd == "}") return "}"
                    return LATEX_SYMBOLS[cmd]?.text ?: ""
                }
                i++
                return ""
            }
            val c = src[i]
            i++
            return if (c == '.') "" else c.toString()
        }

        /**
         * 读一个原子（分组 / 命令 / 上下标 / 空格 / 普通字符）。
         *
         * 边界（stops / stopCommand / stopRowBreak）由 [sequence] 自己判（见那里的三处），
         * 这里曾有同名参数但**一次都没被读过** —— 死参数已删。
         */
        fun atom(): LatexNode? {
            skipSpaces()
            if (i >= src.length) return null
            val c = src[i]
            return when {
                c == '{' -> group()
                c == '\\' -> command()
                c == '^' || c == '_' -> { i++; null } // 没有底数的上下标：忽略
                c == '&' -> { i++; null }
                c == '~' -> { i++; LatexNode.Space(0.333f) }
                c == '$' -> { i++; null }
                c.isDigit() -> {
                    val start = i
                    while (i < src.length && src[i].isDigit()) i++
                    LatexNode.Glyphs(src.substring(start, i), italic = false)
                }
                c.isLetter() -> { i++; LatexNode.Glyphs(c.toString(), italic = true) }
                else -> { i++; LatexNode.Glyphs(c.toString(), italic = false) }
            }
        }

        private fun command(): LatexNode? {
            i++ // '\'
            if (i >= src.length) return LatexNode.Glyphs("\\", italic = false)
            if (!src[i].isLetter()) {
                val c = src[i]
                i++
                LATEX_SPACES[c.toString()]?.let { return LatexNode.Space(it) }
                return LatexNode.Glyphs(c.toString(), italic = false)
            }
            val start = i
            while (i < src.length && src[i].isLetter()) i++
            val name = src.substring(start, i)
            LATEX_SPACES[name]?.let { return LatexNode.Space(it) }
            LATEX_ACCENTS[name]?.let { mark ->
                val body = if (i < src.length && src[i] == '{') group() else atom() ?: LatexNode.Seq(emptyList())
                return LatexNode.Accent(body, mark)
            }
            return when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val num = group()
                    val den = group()
                    LatexNode.Frac(num, den, display = if (name == "dfrac") true else if (name == "tfrac") false else null)
                }
                "sqrt" -> {
                    val index = optionalGroup()
                    LatexNode.Sqrt(group(), index)
                }
                "left" -> {
                    val left = delimiter()
                    val body = sequence("", stopCommand = "right", stopRowBreak = false)
                    var right = ""
                    if (peekCommand() == "right") {
                        i += 6
                        right = delimiter()
                    }
                    LatexNode.Delim(left, body, right)
                }
                "right" -> { delimiter(); null }
                "begin" -> environment()
                "end" -> { groupText(); null }
                "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "operatorname", "textbf", "textit" -> {
                    val content = groupText()
                    LatexNode.Glyphs(content, italic = name == "mathit" || name == "textit")
                }
                // 纯排版开关：没有输出（间距命令在上面按 LATEX_SPACES 处理过了）
                "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "limits", "nolimits",
                "nonumber", "notag", "hfill",
                -> LatexNode.Seq(emptyList())
                // 带参数但没有可见输出：把参数一起吃掉，别让它变成正文（	ag{1} 的 "1"）
                "label", "tag", "hspace", "vspace", "phantom", "mathstrut" -> {
                    if (i < src.length && src[i] == '{') groupText()
                    LatexNode.Seq(emptyList())
                }
                "color" -> { groupText(); LatexNode.Seq(emptyList()) }
                "mathbb", "mathcal", "mathfrak", "boldsymbol", "vec" -> {
                    val content = if (i < src.length && src[i] == '{') group() else LatexNode.Seq(emptyList())
                    LatexNode.Glyphs(inlineText(content), italic = false)
                }
                else -> {
                    val symbol = LATEX_SYMBOLS[name]
                    when {
                        symbol == null -> LatexNode.Glyphs(name, italic = false)
                        symbol.big -> {
                            var sup: LatexNode? = null
                            var sub: LatexNode? = null
                            var guard = 0
                            while (i < src.length && (src[i] == '^' || src[i] == '_') && guard < 4) {
                                val kind = src[i]
                                i++
                                val arg = scriptArgument()
                                if (kind == '^') sup = arg else sub = arg
                                guard++
                            }
                            LatexNode.Big(symbol.text, sup, sub)
                        }
                        else -> LatexNode.Glyphs(symbol.text, symbol.italic)
                    }
                }
            }
        }

        private fun environment(): LatexNode {
            val env = groupText().trim()
            val (left, right) = LATEX_ENVIRONMENTS[env] ?: ("" to "")
            val rows = ArrayList<List<LatexNode>>()
            var row = ArrayList<LatexNode>()
            var guard = 0
            while (i < src.length && guard++ < 512) {
                val cell = sequence("&", stopCommand = "end", stopRowBreak = true)
                row += cell
                if (i < src.length && src[i] == '&') { i++; continue }
                if (src.startsWith("\\\\", i)) {
                    i += 2
                    rows += row
                    row = ArrayList()
                    continue
                }
                if (peekCommand() == "end") {
                    i += 4
                    groupText()
                    rows += row
                    return LatexNode.Grid(rows, left, right)
                }
                break
            }
            if (row.isNotEmpty()) rows += row
            return LatexNode.Grid(rows, left, right)
        }

        /** 把节点树拍平成纯文本（mathbb/mathcal 这类只要求「排出来」的命令用） */
        private fun inlineText(node: LatexNode): String = when (node) {
            is LatexNode.Glyphs -> node.text
            is LatexNode.Seq -> node.items.joinToString("") { inlineText(it) }
            is LatexNode.Space -> " "
            is LatexNode.Script -> inlineText(node.base) + inlineText(node.sup ?: LatexNode.Seq(emptyList())) +
                inlineText(node.sub ?: LatexNode.Seq(emptyList()))
            is LatexNode.Frac -> inlineText(node.num) + "/" + inlineText(node.den)
            is LatexNode.Sqrt -> "√" + inlineText(node.body)
            is LatexNode.Big -> node.symbol
            is LatexNode.Delim -> node.left + inlineText(node.body) + node.right
            is LatexNode.Accent -> inlineText(node.body)
            is LatexNode.Grid -> node.rows.joinToString("; ") { row -> row.joinToString(" ") { inlineText(it) } }
        }
    }
}
