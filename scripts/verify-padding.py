#!/usr/bin/env python3
"""R8 校验：新旧两份源码的 padding 实参必须「数值完全一致」。

退出码 0 表示每一处 padding 的每个实参数值与结构都没变。

做法：把每处 padding(...) 的实参按顶层逗号切开，逐实参归一化成
  - 裸字面量 -> 数值（4.dp 与 DshSpacing.Md 都归一成 4）
  - 其他表达式 -> 去掉数字与空白后的骨架（用于比对结构）
然后逐个调用点比对。
用法：python scripts/verify-padding.py <旧目录> <新目录>
"""
import re
import sys
import os

SCALE_NAME = {0.5: "Hairline", 1: "Xxs", 2: "Xs", 3: "Sm", 4: "Md", 6: "Lg",
              8: "Xl", 10: "Xxl", 12: "Xxxl", 14: "Section", 16: "Card", 20: "Page"}
NAME_VAL = {v: k for k, v in SCALE_NAME.items()}
LIT = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\.dp")
TOK = re.compile(r"DshSpacing\.(\w+)")
def fmt(v):
    return "%g" % float(v)


CALL = re.compile(r"(?<![\w])padding\(")


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


def norm_arg(a):
    if "=" in a:
        name, val = a.split("=", 1)
        pref = name.strip() + "="
    else:
        pref, val = "", a
    core = val.strip()
    m = LIT.fullmatch(core)
    if m:
        return pref + ("NUM:" + fmt(float(m.group(1))))
    m = TOK.fullmatch(core)
    if m and m.group(1) in NAME_VAL:
        return pref + ("NUM:" + fmt(NAME_VAL[m.group(1)]))
    # 表达式：去掉数字后比较骨架
    skel = LIT.sub("<lit>", core)
    skel = TOK.sub("<tok>", skel)
    return pref + "EXPR:" + re.sub(r"\s+", " ", skel)


def sites(path):
    src = open(path, encoding="utf-8").read()
    res = []
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
        line = src[:m.start()].count("\n") + 1
        res.append((line, [norm_arg(p) for p in split_top(inner)]))
    return res, len(LIT.findall(src)), len(TOK.findall(src)) + len(LIT.findall(src))


def main():
    old_root, new_root = sys.argv[1], sys.argv[2]
    bad = 0
    checked = 0
    for dp, _, fs in sorted(os.walk(new_root)):
        for f in sorted(fs):
            if not f.endswith(".kt"):
                continue
            np_ = os.path.join(dp, f)
            rel = os.path.relpath(np_, new_root)
            op = os.path.join(old_root, rel)
            if not os.path.exists(op):
                print("MISSING OLD:", rel)
                bad += 1
                continue
            ns, nlit, ntot = sites(np_)
            os_, olit, otot = sites(op)
            if len(ns) != len(os_):
                print("SITE COUNT DIFF %s: %d -> %d" % (rel, len(os_), len(ns)))
                bad += 1
                continue
            checked += len(ns)
            for (ol, oa), (nl, na) in zip(os_, ns):
                if oa != na:
                    print("ARG DIFF %s:%d -> %d\n   old %s\n   new %s" % (rel, ol, nl, oa, na))
                    bad += 1
            if olit != nlit:
                # padding 之外仍有裸 dp（width/height/size 等），这里只做提示，不算失败
                print("note: padding 外仍有裸 dp %s: %d -> %d" % (rel, olit, nlit))
    print("padding 调用点逐实参比对: %d 个；数值/结构不一致: %d 个" % (checked, bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
