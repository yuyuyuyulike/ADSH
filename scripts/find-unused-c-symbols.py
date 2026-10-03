#!/usr/bin/env python3
"""C 源里没人用到的 static 符号（第五十八轮）。

做法很土但不会误伤：从 .c 的**行首 static 声明**里取符号名，再在整个仓库文本里按词边界数它出现几次。
只有 1 次（声明那行）才算候选；随后人工确认那唯一一次不是注释。
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CPP = os.path.join(ROOT, 'app', 'src', 'main', 'cpp')

corpus = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in {'.git', 'build', '.gradle', '.idea'}]
    for name in files:
        if name.endswith(('.c', '.h', '.kt', '.kts', '.py', '.sh', '.md')):
            corpus.append(open(os.path.join(base, name), encoding='utf-8', errors='ignore').read())
TEXT = '\n'.join(corpus)

DECL = re.compile(r'^static\s+.*?([A-Za-z_]\w*)\s*(\(|\[|=|;)', re.M)
found = 0
for name in sorted(os.listdir(CPP)):
    if not name.endswith(('.c', '.h')):
        continue
    path = os.path.join(CPP, name)
    raw = open(path, encoding='utf-8', errors='ignore').read()
    for match in DECL.finditer(raw):
        symbol = match.group(1)
        uses = len(re.findall(r'\b' + re.escape(symbol) + r'\b', TEXT))
        if uses <= 1:
            line = raw[:match.start()].count('\n') + 1
            snippet = raw.split('\n')[line - 1].strip()
            print('  %s:%s  %s   | %s' % (name, line, symbol, snippet[:90]))
            found += 1
print('候选:', found)
