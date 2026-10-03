#!/usr/bin/env python3
"""Kotlin 源码的注释剥离（给 scripts/ 下的死代码扫描器共用）。

**为什么不能用正则**：代码里的字符串可能含 /* —— 例如 ChatScreen 的文件选择器写着
arrayOf("*/*")，非贪婪正则会从那儿一路吃到下一个 KDoc 的结尾，把中间三百多行代码整段抹掉，
于是明明在用的 import / 声明全被报成「没人引用」（第 123 轮实测的假阳性）。这里按字符扫：
字符串 / 原始字符串 / 字符字面量原样保留，块注释按 Kotlin 的规则支持嵌套；被剥掉的字符
换成空格（换行保留），这样行号与词边界都不变。
"""


def strip_comments(text):
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
