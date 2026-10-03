#!/usr/bin/env python3
"""图标结构校验：每个 dshIcon 调用的实参必须是「名字 + viewBox + 若干 dshPart」。

为什么需要它（第 2 阶段 R7 的教训）：把 grouped()/stroke() 那几处手写 builder 并进 dshIcon 时，
我用脚本重新拼过这些块，两次把**实参拼错位**（path 行丢了、tx 出现在 dshPart 外面）——
**编译器全都没报错**，因为 dshPart 的每个参数都有默认值，by-name 传参再错也只是「画出来不对」。
最后是真机首帧抛 `IllegalArgumentException: Unknown command for: D` 才暴露出来。

所以这里按**结构**校验（不看 path 内容）：
  1. dshIcon 的第 1 个实参必须是一个字符串字面量；
  2. 第 2 个必须是浮点字面量（viewBox）；
  3. 其余实参必须是 dshPart(...) 调用；
  4. 每个 dshPart 的第 1 个实参必须是以 M/m 开头的 path 字符串字面量。
退出码非 0 表示有问题。
"""
import io, re, sys, os

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app/src/main/java")

def skip_string(s, i):
    q = s[i]; i += 1
    while i < len(s):
        c = s[i]
        if c == chr(92): i += 2; continue
        if c == q: return i + 1
        i += 1
    return i

def find_close(s, start):
    depth = 0; i = start
    while i < len(s):
        c = s[i]
        if c == chr(34): i = skip_string(s, i); continue
        if c == "(": depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0: return i
        i += 1
    return -1

def split_top(text):
    parts = []; depth = 0; cur = []; i = 0
    while i < len(text):
        c = text[i]
        # 行注释整段丢掉：实参列表里允许写注释（例如「// dsh 的轨道弧 opacity:0.35」），
        # 但它不是实参，留着会被下面的切分当成一个段。
        if c == "/" and i + 1 < len(text) and text[i+1] == "/":
            j = text.find(chr(10), i)
            i = (j + 1) if j > 0 else len(text)
            cur.append(chr(10))
            continue
        if c == chr(34):
            j = skip_string(text, i); cur.append(text[i:j]); i = j; continue
        if c in "([{": depth += 1
        elif c in ")]}": depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur)); cur = []; i += 1; continue
        cur.append(c); i += 1
    if "".join(cur).strip(): parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]

problems = []
checked = 0
for dp, dn, fn in os.walk(ROOT):
    for f in sorted(fn):
        if not f.endswith(".kt"): continue
        path = os.path.join(dp, f)
        src = io.open(path, encoding="utf-8").read()
        idx = 0
        while True:
            k = src.find("dshIcon(", idx)
            if k < 0: break
            if k > 0 and (src[k-1].isalnum() or src[k-1] in "_."):
                idx = k + 8; continue
            if src[max(0, k-40):k].rstrip().endswith("fun"):
                idx = k + 8; continue
            cp = find_close(src, k + len("dshIcon"))
            if cp < 0: break
            args = split_top(src[k + len("dshIcon("):cp])
            ln = src.count(chr(10), 0, k) + 1
            loc = "%s:%d" % (os.path.basename(path), ln)
            checked += 1
            if len(args) < 3:
                problems.append("%s 实参只有 %d 个（至少要 名字 + viewBox + 1 个 dshPart）" % (loc, len(args)))
            else:
                if not args[0].startswith(chr(34)):
                    problems.append("%s 第 1 个实参不是字符串字面量：%s" % (loc, args[0][:40]))
                if not re.match(r"^[0-9.]+f?$", args[1]):
                    problems.append("%s 第 2 个实参不是 viewBox 字面量：%s" % (loc, args[1][:40]))
                for a in args[2:]:
                    if not a.startswith("dshPart("):
                        problems.append("%s 有非 dshPart 的段实参：%s" % (loc, a[:40]))
                        continue
                    inner = a[len("dshPart("):a.rindex(")")]
                    sub = split_top(inner)
                    if not sub:
                        problems.append("%s dshPart 是空的" % loc); continue
                    first = sub[0]
                    is_literal = first.startswith(chr(34)) and re.match(r'^"(?:M|m)', first, re.S)
                    # 也允许引用常量（如 DshIcons 里那条较长的 SHIELD_OUTLINE）—— 那种情况内容在定义处
                    is_const = re.match(r'^[A-Z][A-Z0-9_]*$', first) is not None
                    if not (is_literal or is_const):
                        problems.append("%s dshPart 第 1 个实参既不是 path 字面量也不是常量名：%s" % (loc, first[:40]))
            idx = cp + 1
print("校验 dshIcon 调用:", checked, "个")
if problems:
    print("!! 结构问题", len(problems), "处:")
    for x in problems: print("   ", x)
    sys.exit(1)
print("图标结构校验通过")