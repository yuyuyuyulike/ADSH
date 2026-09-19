#!/usr/bin/env python3
"""删掉没用到的 import（带锚点校验：只在整行与预期文本完全一致时才删，否则整体放弃）。

排查口径见 docs/UI-v6-report.md 第五十四轮：属性委托约定的 getValue/setValue/provideDelegate
即使名字不出现在正文里也必须保留。
"""
import glob
import os
import re
import sys

ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"
KEEP = {"getValue", "setValue", "provideDelegate"}
IMPORT = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$")
APPLY = "--apply" in sys.argv

plan = {}
for path in sorted(glob.glob(os.path.join(ROOT, "app/src/**/*.kt"), recursive=True)):
    text = open(path, encoding="utf-8", errors="replace").read()
    lines = text.splitlines()
    body = "\n".join(l for l in lines if not l.startswith("import "))
    for line in lines:
        match = IMPORT.match(line.strip())
        if not match:
            continue
        fq, alias = match.group(1), match.group(2)
        if fq.endswith(".*"):
            continue
        name = alias or fq.split(".")[-1]
        if name in KEEP:
            continue
        if not re.search(r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])", body):
            plan.setdefault(path, []).append(line)

total = 0
for path, drops in plan.items():
    text = open(path, encoding="utf-8", errors="replace").read()
    for line in drops:
        if text.count(line + "\n") != 1:
            print("放弃：" + path + " 里这一行不唯一 → " + line)
            sys.exit(1)
    if APPLY:
        for line in drops:
            text = text.replace(line + "\n", "", 1)
        open(path, "w", encoding="utf-8").write(text)
    total += len(drops)
    print("%-70s -%d" % (os.path.relpath(path, ROOT), len(drops)))
print(("已删除 " if APPLY else "待删除 ") + str(total) + " 行 import（" + str(len(plan)) + " 个文件）")
