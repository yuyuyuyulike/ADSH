#!/usr/bin/env python3
"""长函数排名（口径：先把注释与字符串/字符字面量挖空，再按花括号配平）。

为什么需要这个脚本：R9 期间发现，直接数花括号会把**字符串里的花括号**算进去
（Compose 里 `Text("…{…}…")` 之类很常见），于是那些函数被算短了 ——
TerminalPanel(538)、AppRoot(375)、QuestionCard(368)、runTurnBody(397) 都曾被漏掉。

R13 又发现第二个坑：**带默认值 lambda 参数**的函数（`onX: () -> Unit = {}`）会被算成几行 ——
扫描从声明行开始数花括号，那一行的 `{}` 一进一出就归零，于是函数「提前结束」。
ChatScreen() 因此从来没上过榜，而它的函数体有 891 行。
所以现在**先按圆括号跳过参数表，再从函数体的那个 `{` 开始配平**；没有块体的表达式函数
（`fun f() = …`）只量到该声明结束（不吞下一个函数）。

生成的数据表文件（ModelThinkingLevels.kt）单独列出，不参与排名。
"""
import io
import os
import re

ROOT = "app/src/main/java"
GENERATED = {"ModelThinkingLevels.kt"}

FUN = re.compile(
    r"^[ \t]*(?:@\w+\s*)?(?:private |internal |public |override |suspend |inline |operator )*"
    r"fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[\\(<]",
    re.M,
)
# 顶层声明起点：表达式体函数到这里就算结束，别把下一个声明吞进来
TOP_DECL = re.compile(
    r"^[ \t]*(?:@\w+|private |internal |public |override |suspend |inline |operator |companion |"
    r"fun |val |const val |class |object |enum class |\})",
    re.M,
)


def blank_noncode(src):
    """（与原版一致）把注释与字符串/字符字面量挖空，保留换行。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != chr(10):
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            out[i] = out[i + 1] = " "
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                if src[i] != chr(10):
                    out[i] = " "
                i += 1
            if i + 1 < n:
                out[i] = out[i + 1] = " "
                i += 2
        elif c == '"':
            if src.startswith('"""', i):
                j = i + 3
                while j < n and not src.startswith('"""', j):
                    if src[j] != chr(10):
                        out[j] = " "
                    j += 1
                for k in range(i, min(j + 3, n)):
                    if src[k] != chr(10):
                        out[k] = " "
                i = j + 3
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == chr(92):
                        out[j] = " "
                        j += 1
                        if j < n:
                            out[j] = " "
                        j += 1
                        continue
                    if src[j] == chr(10):
                        break
                    out[j] = " "
                    j += 1
                if j < n:
                    out[j] = " "
                out[i] = " "
                i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == chr(92):
                    out[j] = " "
                    j += 1
                    if j < n:
                        out[j] = " "
                    j += 1
                    continue
                if src[j] == chr(10):
                    break
                out[j] = " "
                j += 1
            if j < n:
                out[j] = " "
            out[i] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def span_end(code, match_end):
    """从声明返回函数结束字符的下标；没有块体（表达式体）返回它该结束的位置。

    match_end 指向 FUN 匹配到的那个 [\(<] 之后 —— 所以先退回一格，拿到的才是参数表（或泛型表）。
    """
    k = match_end - 1
    if k < 0 or k >= len(code):
        return None
    if code[k] == "<":
        d = 0
        while k < len(code):
            if code[k] == "<":
                d += 1
            elif code[k] == ">":
                d -= 1
                if d == 0:
                    break
            k += 1
        k = code.find("(", k)
    if k < 0:
        return None
    d = 0
    while k < len(code):
        c = code[k]
        if c == "(":
            d += 1
        elif c == ")":
            d -= 1
            if d == 0:
                break
        k += 1
    body = code.find("{", k)
    if body < 0:
        return None
    nxt = TOP_DECL.search(code, k + 1)
    if nxt is not None and nxt.start() < body:
        # 表达式体（没有块）：量到下一个顶层声明之前，别吞掉它
        return nxt.start() - 1
    d = 0
    j = body
    while j < len(code):
        c = code[j]
        if c == "{":
            d += 1
        elif c == "}":
            d -= 1
            if d == 0:
                return j
        j += 1
    return None


def main():
    rows = []
    for dp, _, fs in os.walk(ROOT):
        for f in sorted(fs):
            if not f.endswith(".kt"):
                continue
            raw = io.open(os.path.join(dp, f), encoding="utf-8").read()
            code = blank_noncode(raw)
            for m in FUN.finditer(code):
                end = span_end(code, m.end())
                if end is None:
                    continue
                start_line = code.count(chr(10), 0, m.start())
                end_line = code.count(chr(10), 0, end)
                name = m.group(1)
                # 顶层 / 嵌套看声明行自己的缩进（正则已经把前导空白吃进匹配里）
                indent = len(m.group(0)) - len(m.group(0).lstrip())
                rows.append((end_line - start_line + 1, f, start_line + 1, name, indent))
    rows.sort(reverse=True)
    print("手写代码里最长的函数（已排除生成的数据表）：")
    k = 0
    for n, f, ln, name, ind in rows:
        if f in GENERATED:
            continue
        print("  %4d 行  %-22s %-24s :%-5d %s" % (n, f, name, ln, "顶层" if ind == 0 else "嵌套"))
        k += 1
        if k == 15:
            break
    gen = [r for r in rows if r[1] in GENERATED]
    if gen:
        print()
        print("生成物（数据表，不参与排名）：")
        for n, f, ln, name, ind in gen:
            print("  %4d 行  %-24s :%d" % (n, name, ln))


if __name__ == "__main__":
    main()