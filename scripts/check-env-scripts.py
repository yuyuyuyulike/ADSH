"""从 Kotlin 源码里抽出环境脚本的正文并做 dash 语法检查（本地跑，改脚本后必跑）。

为什么要它：前缀里的 `/bin/sh` 是 **dash**（不是 bash），脚本一旦用了 dash 不认的写法，
在真机上就是一句 `Syntax error` 而诊断信息全丢。

环境里没有 sh/dash 时明确 SKIP（而不是抛异常、也不在仓库里留临时件）。
用法：python3 scripts/check-env-scripts.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = [
    ("platform/termux/src/main/java/com/adsh/app/runtime/termux/EnvSelfCheck.kt", "ENVCHECK"),
    ("platform/termux/src/main/java/com/adsh/app/runtime/termux/AdshShot.kt", "SHOT"),
]

fail = 0
tmpdir = os.path.join(ROOT, ".tmp")
os.makedirs(tmpdir, exist_ok=True)

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

    shells = [s for s in ("dash", "sh") if shutil.which(s)]
    if not shells:
        print("SKIP  %-4s 环境里没有 sh/dash，未做语法检查" % label)
        continue

    # 临时件放 .tmp/（已 gitignore）；newline="" + 显式 \n：Windows 文本模式会把 \n 翻成 \r\n，
    # 而 **dash 不认 CRLF**（报「word unexpected (expecting do)」——`do\r` 不是关键字），
    # 那样这个检查在 Windows 上必然假失败，而脚本本身没问题。
    fd, tmp = tempfile.mkstemp(prefix="script-%s-" % label.lower(), suffix=".sh", dir=tmpdir)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as fh:
            fh.write(script)
        ok = True
        for shell in shells:
            result = subprocess.run([shell, "-n", tmp], capture_output=True, text=True)
            if result.returncode != 0:
                ok = False
                print("FAIL  %-4s %s: %s" % (label, shell, result.stderr.strip()))
        if ok:
            print("PASS  %-4s %d 行，语法 OK" % (label, script.count("\n")))
        else:
            fail = 1
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)

sys.exit(fail)
