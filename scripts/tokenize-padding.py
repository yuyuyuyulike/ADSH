#!/usr/bin/env python3
"""R8 padding 刻度化：把 padding(...) 实参里的裸 dp 字面量换成 DshSpacing 名字。

口径（严格）：
  - 只处理后面紧跟 ( 的 padding 调用（Modifier.padding / apply 内的 padding 都算）；
  - 只替换「整个实参就是一个裸 dp 字面量」且数值已在 DshSpacing 刻度上的情况；
  - 表达式实参、刻度外数值、0.dp 一律原样保留；只改名字，不改任何数值。
"""
import re
import sys
import os

ROOT = "app/src/main/java"
SCALE = {0.5: "Hairline", 1: "Xxs", 2: "Xs", 3: "Sm", 4: "Md", 6: "Lg",
         8: "Xl", 10: "Xxl", 12: "Xxxl", 14: "Section", 16: "Card", 20: "Page"}
VAL = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\.dp")
CALL = re.compile(r"(?<![\w])padding\(")
IMPORT = "import com.adsh.app.ui.DshSpacing"


def split_top(s):
    parts, d, cur = [], 0, ""
    for ch in s:
        if ch == "(":
            d += 1
        if ch == ")":
            d -= 1
        if ch == "," and d == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return parts


def rewrite_inner(inner):
    n = 0
    new_parts = []
    for part in split_top(inner):
        if "=" in part:
            name, a = part.split("=", 1)
            pref = name + "="
        else:
            pref, a = "", part
        lead = a[: len(a) - len(a.lstrip())]
        trail = a[len(a.rstrip()):]
        core = a.strip()
        m = VAL.fullmatch(core)
        if m and float(m.group(1)) in SCALE:
            new_parts.append(pref + lead + "DshSpacing." + SCALE[float(m.group(1))] + trail)
            n += 1
        else:
            new_parts.append(part)
    return ",".join(new_parts), n


def transform(src):
    out, last, n = [], 0, 0
    for m in CALL.finditer(src):
        i = m.end()
        d = 1
        while i < len(src) and d > 0:
            if src[i] == "(":
                d += 1
            elif src[i] == ")":
                d -= 1
            i += 1
        inner = src[m.end():i - 1]
        new_inner, c = rewrite_inner(inner)
        if c:
            out.append(src[last:m.end()])
            out.append(new_inner)
            last = i - 1
            n += c
    out.append(src[last:])
    return "".join(out), n


def add_import(src):
    """把 DshSpacing 的 import 按字典序插进 import 块。同包文件不需要。"""
    if src.split("\n")[0].strip() == "package com.adsh.app.ui":
        return src, False
    if IMPORT in src:
        return src, False
    lines = src.split("\n")
    idxs = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if not idxs:
        return src, False
    block = sorted(set([lines[i] for i in idxs] + [IMPORT]))
    lines[idxs[0]:idxs[-1] + 1] = block
    return "\n".join(lines), True


def main():
    apply = "--apply" in sys.argv
    total, touched, import_added = 0, [], []
    for dp, _, fs in sorted(os.walk(ROOT)):
        for f in sorted(fs):
            if not f.endswith(".kt"):
                continue
            p = os.path.join(dp, f)
            src = open(p, encoding="utf-8").read()
            if "padding(" not in src:
                continue
            new, n = transform(src)
            if not n:
                continue
            new, added = add_import(new)
            total += n
            touched.append((p, n))
            if added:
                import_added.append(p)
            if apply:
                open(p, "w", encoding="utf-8", newline="").write(new)
    print("replacements: %d in %d files" % (total, len(touched)))
    for p, n in touched:
        print("  %3d  %s" % (n, p))
    print("import added to: %s" % import_added)


if __name__ == "__main__":
    main()
