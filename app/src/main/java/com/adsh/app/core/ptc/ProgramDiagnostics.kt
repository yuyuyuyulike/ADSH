package com.adsh.app.core.ptc

/**
 * run_code 失败时的**定位**（第 81 轮）。
 *
 * 起因：用户实测「ADSH 里 run_code 报错比 dsh 多不少」。逐条对着 dsh 看下来，除了语言不同
 * （dsh 跑可擦除 TypeScript，ADSH 跑 QuickJS 的纯 JavaScript）之外，最要命的一条是**错误没有位置**：
 * 模型只拿到 `SyntaxError: unexpected token ':'`，既不知道是第几行，也不知道是本运行时不认识的
 * TypeScript 写法，只能整段重写再发一次 —— 于是「连续调用 run_code」和「报错次数多」同时出现。
 * dsh 那边程序由 Node/TS 运行时执行，报错里带 `program.js:LINE:COL` 与出错那一段源码，模型一眼就能改。
 *
 * 这里把两件事补上（都是纯函数，单测逐条钉死）：
 *  1. 把 QuickJS 的行号（`program.js:N`，文件行号）换算成**模型自己写的那一行** ——
 *     程序体被嵌在包装器的第 4 行（见 [QuickJSRuntime] 的 wrapper），所以要减 [WRAPPER_LINE_OFFSET]；
 *  2. 语法失败时，如果运行时没给行号，就在程序里找出**第一处 TypeScript 专有写法**报给模型
 *     （类型标注 / interface / enum / namespace / as / 泛型 / 非空断言）——这正是本运行时与
 *     dsh 最大的差别，也是模型最常犯的那一类错。
 */
object ProgramDiagnostics {

    /**
     * 包装器把程序体嵌在第 4 行，所以 `program.js` 的文件行号 = 程序行号 + 3。
     * 对齐 [QuickJSRuntime] 里 wrapper 的拼法：
     * ```
     * 1: (async () => {
     * 2:   try {
     * 3:     globalThis.__adsh_state.value = await (async () => {
     * 4: <程序第一行>
     * ```
     */
    const val WRAPPER_LINE_OFFSET = 3

    private val PROGRAM_FRAME = Regex("""program\.js:(\d+)""")

    /** TypeScript 专有写法：声明类（`interface` / `enum` / …），一行就够判定 */
    private val TS_DECLARATION = Regex("""^\s*(interface|enum|namespace|declare|type)\s+[A-Za-z_$]""")

    /** 变量/常量上的类型标注：`const total: number = …`（在 JS 里是语法错误） */
    private val TS_VARIABLE_ANNOTATION =
        Regex("""\b(?:const|let|var)\s+[A-Za-z_$][\w$]*\s*:\s*[A-Za-z_$][\w$.<>\[\]| ]*\s*=""")
    private val TS_OPTIONAL = Regex("""[A-Za-z_$][\w$]*\?\s*:\s*[A-Za-z_$]""")

    /** 函数返回类型标注：`): string {` / `): Promise<void> =>` */
    private val TS_RETURN_TYPE = Regex("""\)\s*:\s*[A-Za-z_$][\w$.<>\[\]| ]*\s*[={]""")

    /** 参数类型标注：`(a: string)` / `(a: string, b: number)`（对象字面量在 `(` 后面是 `{`，不会命中） */
    private val TS_PARAMETER_TYPE =
        Regex("""\(\s*[A-Za-z_$][\w$]*\s*:\s*[A-Za-z_$][\w$.<>\[\]| ]*\s*[,)]""")

    /** `as const` / `as SomeType`（JS 里没有 `as` 运算符） */
    private val TS_AS = Regex("""\bas\s+(?:const|[A-Za-z_$][\w$.<>\[\]|]*)""")

    /** 非空断言：`foo!.bar` / `value!` */
    private val TS_NON_NULL = Regex("""[\w$)\]]!\s*[.;,)]""")

    /**
     * 泛型实参：`Array<string>` / `new Map<string, number>()`。
     * 类型部分写得很严、后面还要跟 `( [ . ; , )` 或行尾 —— `<` 在 JS 里是运算符，
     * 不设这些约束的话 `a<b>c` 这种比较链也会被当成泛型。
     */
    private val TS_GENERIC = Regex(
        """\b[A-Za-z_$][\w$]*<\s*[A-Za-z_$][\w$.]*(?:\[\])?(?:\s*,\s*[A-Za-z_$][\w$.]*(?:\[\])?)*\s*>(?=\s*[(\[.;,)]|\s*$)"""
    )

    /**
     * 从错误文本或调用栈里取 `program.js` 的行号（文件行号，1-based）。
     *
     * 取**第一个**匹配：QuickJS 的栈是「最内层在前」，第一个就是出错的那一帧。
     * 文本与栈都要看 —— 运行时错误把栈存在 state 里，语法错误只有 Java 异常的 message。
     */
    fun fileLineOf(vararg texts: String?): Int? {
        texts.forEach { text ->
            if (text == null) return@forEach
            val match = PROGRAM_FRAME.find(text) ?: return@forEach
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 文件行号 → 模型程序的第几行（1-based）。超出程序行数（例如错误发生在包装器自己的那几行）
     * 返回 null：宁可不报位置，也不能报一个错的。
     */
    fun programLineOf(fileLine: Int, programLineCount: Int): Int? {
        val line = fileLine - WRAPPER_LINE_OFFSET
        return if (line in 1..programLineCount) line else null
    }

    /** 程序里第一处看起来是 TypeScript 专有写法的行：行号（1-based）与该行原文 */
    fun firstTypeScriptLine(program: String): Pair<Int, String>? {
        program.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                return@forEachIndexed
            }
            if (looksLikeTypeScript(line)) return (index + 1) to raw
        }
        return null
    }

    private fun looksLikeTypeScript(line: String): Boolean =
        TS_DECLARATION.containsMatchIn(line) ||
            TS_VARIABLE_ANNOTATION.containsMatchIn(line) ||
            TS_OPTIONAL.containsMatchIn(line) ||
            TS_RETURN_TYPE.containsMatchIn(line) ||
            TS_PARAMETER_TYPE.containsMatchIn(line) ||
            TS_AS.containsMatchIn(line) ||
            TS_NON_NULL.containsMatchIn(line) ||
            TS_GENERIC.containsMatchIn(line)

    /**
     * 这段错误文本看起来是**语法**失败吗（parse 阶段就挂了，程序一行都没跑）。
     * 语法失败时 QuickJS 通常不给行号，但几乎总是 TypeScript 写法造成的。
     */
    fun looksLikeSyntaxFailure(errorText: String?): Boolean {
        val text = errorText ?: return false
        return text.contains("SyntaxError") ||
            text.contains("unexpected token") ||
            text.contains("unexpected end of input") ||
            text.contains("invalid syntax")
    }

    /**
     * 给模型补的那一段定位说明；没有任何可用信息时返回 null。
     *
     * 输出形状（接在错误消息后面）：
     * ```
     * Program line 3: const total: number = items.length;
     * Note: this runtime is QuickJS — plain JavaScript only. TypeScript syntax (type annotations,
     * `interface`, `enum`, `namespace`, `as`, generics) is a syntax error here; fix it and run the
     * corrected program once.
     * ```
     *
     * @param program 模型写的程序体（原文，用来回显出错那一行）
     * @param errorText 运行时给的错误消息
     * @param stack 运行时的调用栈（QuickJS 的 Error.stack；可能为 null）
     * @param parserFailure 失败发生在 parse 阶段（Java 侧 evaluate 直接抛，程序没跑过）
     */
    fun describe(
        program: String,
        errorText: String?,
        stack: String?,
        parserFailure: Boolean,
    ): String? {
        val lines = program.lineSequence().toList()
        val syntaxFailure = parserFailure || looksLikeSyntaxFailure(errorText)
        val fileLine = fileLineOf(stack, errorText)
        val line = fileLine?.let { programLineOf(it, lines.size) }
        val typescript = if (syntaxFailure) firstTypeScriptLine(program) else null
        if (line == null && typescript == null) return null
        return buildString {
            if (line != null) {
                append("Program line ").append(line).append(": ").append(lines[line - 1].trim())
            }
            if (typescript != null) {
                if (isNotEmpty()) append('\n')
                append("TypeScript syntax on line ").append(typescript.first).append(": ")
                    .append(typescript.second.trim())
                append("\nNote: this runtime is QuickJS — plain JavaScript only. Type annotations, ")
                append("`interface`, `enum`, `namespace`, `as`, generics and non-null `!` are syntax ")
                append("errors here; drop them and run the corrected program once (do not re-send the same code).")
            }
        }
    }
}
