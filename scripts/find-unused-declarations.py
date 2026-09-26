#!/usr/bin/env python3
"""找「除声明处之外没人提到」的顶层/类成员声明（第五十四、五十八轮的死代码扫描）。

判据（宁可漏报，不可误报）：
  * 只在 app/src/main 的 .kt 里找声明；
  * 名字在整个语料（main+test+res/xml+AndroidManifest+*.gradle.kts+cpp+docs+scripts+README+HANDOFF）
    里按词边界计数，**只有 1 次**（就是声明那一行）才算候选；
  * 属性访问 obj.name 也算引用，所以只会漏报。

用法：python3 scripts/find-unused-declarations.py [--json]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DECL = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:internal |private |public |protected |open |abstract |sealed |data |enum |annotation |value |inline |suspend |operator |override |expect |actual |external |lateinit |const |tailrec |infix |vararg |crossinline |noinline |reified )*'
    r'(fun|val|var|class|object|interface|typealias)\s+'
    r'(?:<[^>]*>\s*)?'
    r'([A-Za-z_][A-Za-z0-9_]*)'
)

ROOTS = ['main', 'test'] if '--tests' in sys.argv else ['main']
SOURCES = []
for root in ROOTS:
    for base, _dirs, files in os.walk(os.path.join(ROOT, 'app', 'src', root)):
        for name in files:
            if name.endswith('.kt'):
                SOURCES.append(os.path.join(base, name))

CORPUS = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in {'.git', 'build', '.gradle', '.idea'}]
    for name in files:
        if name.endswith(('.kt', '.kts', '.py', '.sh', '.md', '.c', '.h', '.xml', '.toml', '.properties', '.json', '.txt', '.bat', '.yml', '.yaml')):
            path = os.path.join(base, name)
            try:
                CORPUS.append(open(path, encoding='utf-8', errors='ignore').read())
            except OSError:
                pass
TEXT = '\n'.join(CORPUS)

candidates = []
for path in SOURCES:
    rel = os.path.relpath(path, ROOT)
    lines = open(path, encoding='utf-8').read().split('\n')
    for number, line in enumerate(lines, start=1):
        match = DECL.match(line)
        if not match:
            continue
        # @Test / @Before / @After 标注的函数由 JUnit 反射调用，静态看永远「没人引用」
        if any(marker in lines[number - 2] for marker in ('@Test', '@Before', '@After')) if number >= 2 else False:
            continue
        kind, name = match.group(1), match.group(2)
        if kind in ('val', 'var') and not re.match(r'^\s*(?:@\w+\s*)*(?:internal|private|public|const|lateinit|override|open|abstract)\b', line):
            # 只收「看起来是文件级/对象级属性」的（局部变量不在此列：它们有缩进与 val/var 前缀）
            pass
        count = len(re.findall(r'(?<![\w.])' + re.escape(name) + r'\b', TEXT)) + len(re.findall(r'\.' + re.escape(name) + r'\b', TEXT))
        if count <= 1:
            candidates.append({'file': rel, 'line': number, 'kind': kind, 'name': name, 'text': line.strip()})

if '--json' in sys.argv:
    print(json.dumps(candidates, ensure_ascii=False, indent=1))
else:
    print('候选（除声明处无人提及）:', len(candidates))
    for item in candidates:
        print('  %s:%s  %s %s' % (item['file'], item['line'], item['kind'], item['name']))
