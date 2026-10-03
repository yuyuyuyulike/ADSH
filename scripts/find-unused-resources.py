#!/usr/bin/env python3
"""找没人引用的资源（strings / drawable / xml）—— 死资源扫描（第五十八轮）。

判据：res/values*/strings.xml 里的每个 <string name=X>，以及 res/drawable*/ 下的每个文件，
在整个仓库文本里找不到 R.string.X / R.drawable.Y / @string/X / @drawable/Y 就算候选。
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXT = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in {'.git', 'build', '.gradle', '.idea'}]
    for name in files:
        if name.endswith(('.kt', '.kts', '.xml', '.md', '.py', '.sh', '.toml', '.properties')):
            try:
                TEXT.append(open(os.path.join(base, name), encoding='utf-8', errors='ignore').read())
            except OSError:
                pass
CORPUS = '\n'.join(TEXT)

candidates = []
for base, _dirs, files in os.walk(os.path.join(ROOT, 'app', 'src')):
    for name in files:
        path = os.path.join(base, name)
        rel = os.path.relpath(path, ROOT)
        if name == 'strings.xml':
            body = open(path, encoding='utf-8', errors='ignore').read()
            for match in re.finditer(r'<string name="([^"]+)"', body):
                key = match.group(1)
                if ('R.string.' + key) not in CORPUS and ('@string/' + key) not in CORPUS:
                    candidates.append((rel, 'string', key))
        elif '/drawable' in base.replace('\\', '/') and name.endswith('.xml'):
            key = name[:-4]
            if ('R.drawable.' + key) not in CORPUS and ('@drawable/' + key) not in CORPUS:
                candidates.append((rel, 'drawable', key))

print('候选（没人引用的资源）:', len(candidates))
for rel, kind, key in candidates:
    print('  %-58s %-9s %s' % (rel, kind, key))
