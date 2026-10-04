#!/usr/bin/env python3
"""Kotlin 源码的词法层（第 182 轮从 kt_comments.py 长出来的）：给 scripts/ 下的死代码扫描器共用。

三件事，每一件都对应一个**踩过的坑**：

1. strip_comments —— 注释剥离。**不能用正则**：代码里的字符串可能含 /*（例如 ChatScreen 的
   arrayOf("*/*")），非贪婪正则会从那儿一路吃到下一个 KDoc 的结尾，把三百多行代码整段抹掉，
   于是明明在用的 import / 声明全被报成「没人引用」（第 123 轮实测的假阳性）。
   这里按字符扫，字符串 / 原始字符串 / 字符字面量原样保留，块注释支持 Kotlin 的嵌套规则；
   被剥掉的字符换成空格（换行保留），行号与词边界都不变。

2. mask_strings —— 字符串**正文**抹成空格，但**保留模板引用**（"$name" 以及 $ 后跟花括号的写法）。
   为什么必须做：旧扫描器把整个仓库的文本拼起来数名字，于是 **字符串里提一句同名**
   （toast 文案、日志 tag、资源名）就算「有人引用」—— 一个真的没人调用的函数只因为
   日志里写着它的名字就能永远躲过扫描（漏报）。反过来，Kotlin 的字符串模板是真引用
   （"会话 $id 完成" 里的 id 就是读了这个变量），所以模板里的名字要留下。

3. identifiers —— 词法级标识符流：每行扫一遍 [A-Za-z_][A-Za-z0-9_]*，附带「它是不是成员访问
   （前面是点）、是不是命名实参（后面是等号且前面是左括号或逗号）」这两种上下文。死代码扫描的
   引用计数建立在这上面，而不是 re.findall 数名字 —— 后者分不清
   「另一个同名声明」「命名实参的标签」「字符串里的同名」。

行号约定：所有函数都保持**行数不变**（被抹掉的部分用空格顶位、换行原样保留），
调用方可以直接用 split 换行后的行号定位。
"""


def strip_comments(text):
    """把注释换成空格（换行保留）；字符串 / 原始字符串 / 字符字面量原样留下。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif text[i] == '"' or text[i] == "'":
            quote, j = text[i], i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                elif text[j] == quote:
                    j += 1
                    break
                else:
                    j += 1
            out.append(text[i:j])
            i = j
        elif text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append("".join("\n" if c == "\n" else " " for c in text[i:j]))
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def _template_refs(body):
    """字符串模板里的引用（$name 以及 $ 后跟花括号的形式）—— 这些是**真引用**，抹字符串时留着。"""
    keep = []
    i, n = 0, len(body)
    while i < n:
        if body[i] != "$":
            i += 1
            continue
        if i + 1 < n and body[i + 1] == "{":
            depth, j = 1, i + 2
            while j < n and depth:
                if body[j] == "{":
                    depth += 1
                elif body[j] == "}":
                    depth -= 1
                j += 1
            keep.append(body[i:j])
            i = j
        else:
            j = i + 1
            while j < n and (body[j].isalnum() or body[j] == "_"):
                j += 1
            if j > i + 1:
                keep.append(body[i:j])
            i = j
    return " ".join(keep)


def mask_strings(code):
    """把字符串 / 字符字面量的正文抹成空格，只留模板引用；行号不变。

    三引号（原始字符串）同样处理：里面的 $name 也是模板引用。
    """
    out = []
    i, n = 0, len(code)
    while i < n:
        if code.startswith('"""', i):
            j = code.find('"""', i + 3)
            j = n if j < 0 else j + 3
            body = code[i:j]
            out.append('"""' + _template_refs(body) + '"""')
            i = j
        elif code[i] == '"' or code[i] == "'":
            quote, j = code[i], i + 1
            while j < n:
                if code[j] == "\\":
                    j += 2
                elif code[j] == quote:
                    j += 1
                    break
                else:
                    j += 1
            body = code[i + 1:max(i + 1, j - 1)]
            out.append(quote + _template_refs(body) + quote)
            i = j
        else:
            out.append(code[i])
            i += 1
    return "".join(out)


_IDENT = None


def identifiers(masked):
    """词法级标识符流：[(line_no, name, context)]。

    context 四种：
      "import"    —— 出现在 import 行上（import 行的名字不算「用了它」）
      "named_arg" —— 命名实参的标签（foo(name = 1)），不是对某个同名声明的引用
      "member"    —— 前面紧跟点号（成员访问 / 扩展调用）
      "plain"     —— 其余（普通引用）
    """
    global _IDENT
    if _IDENT is None:
        import re as _re
        _IDENT = _re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
    result = []
    for number, line in enumerate(masked.split("\n"), start=1):
        stripped = line.lstrip()
        is_import = stripped.startswith("import ")
        for match in _IDENT.finditer(line):
            name = match.group(0)
            if is_import:
                result.append((number, name, "import"))
                continue
            before = line[:match.start()].rstrip()
            after = line[match.end():].lstrip()
            if after.startswith("=") and not after.startswith("==") and before[-1:] in ("(", ","):
                result.append((number, name, "named_arg"))
            elif before.endswith("."):
                result.append((number, name, "member"))
            else:
                result.append((number, name, "plain"))
    return result


def strip_comments_and_strings(text):
    """一步到位：先剥注释、再抹字符串正文（模板引用留下）。扫描器要的就是这一份。"""
    return mask_strings(strip_comments(text))
