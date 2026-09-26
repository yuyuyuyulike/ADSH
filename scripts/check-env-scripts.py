"""从 Kotlin 源码里抽出环境脚本的正文并做 dash 语法检查（本地跑，改脚本后必跑）。

为什么要它：前缀里的 `/bin/sh` 是 **dash**（不是 bash），脚本一旦用了 dash 不认的写法，
在真机上就是一句 `Syntax error` 而诊断信息全丢。第 73 轮踩过一次（漏了个引号）。
用法：python3 scripts/check-env-scripts.py
"""
import re
import subprocess
import sys
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = [
    ("app/src/main/java/com/adsh/app/runtime/termux/EnvSelfCheck.kt", "ENVCHECK"),
    ("app/src/main/java/com/adsh/app/runtime/termux/AdshShot.kt", "SHOT"),
]

fail = 0
for rel, label in SOURCES:
    path = os.path.join(ROOT, rel)
    text = open(path, encoding="utf-8").read()
    match = re.search(r'val SCRIPT(?:: String)? = """\n(.*?)\n\s*"""\.trimIndent\(\)', text, re.S)
    if not match:
        print("SKIP  %-4s 没找到 SCRIPT 常量：%s" % (label, rel))
        continue
    body = match.group(1)
    # Kotlin 里的 ${'$'} 就是脚本里的 $
    body = body.replace("${'$'}", "$")
    # trimIndent 的语义：按最短缩进统一去掉
    lines = body.split("\n")
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    cut = min(indents) if indents else 0
    script = "\n".join(l[cut:] if l.strip() else "" for l in lines) + "\n"
    tmp = os.path.join(ROOT, ".script-%s.sh" % label.lower())
    open(tmp, "w", encoding="utf-8").write(script)
    checks = [["sh", "-n", tmp]]
    if subprocess.run(["which", "dash"], capture_output=True).returncode == 0:
        checks.append(["dash", "-n", tmp])
    ok = True
    for cmd in checks:
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            ok = False
            print("FAIL  %-4s %s: %s" % (label, " ".join(cmd[:2]), result.stderr.strip()))
    if ok:
        print("PASS  %-4s %d 行，语法 OK" % (label, script.count("\n")))
    else:
        fail = 1
    os.remove(tmp)

sys.exit(fail)
