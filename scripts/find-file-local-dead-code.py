#!/usr/bin/env python3
"""比 find-unused-declarations.py 更细的一层：**文件内**没人用到的 private 声明。

现有扫描器的判据是「名字在整个语料里只出现一次」——只要名字在别处撞上（局部变量、
字符串、注释里的同名词），它就漏报。这里只数**同一个文件内**的引用，专门抓这类漏网。

只报 `private`（含 private const val / private fun / private class），并且排除：
  * 声明行本身；
  * `@Test` / `@Before` / `@After` 标注的函数（JUnit 反射调用）；
  * `@SerialName` 的属性（序列化器读写）；
  * 名字在后面几行里作为字符串/注释出现也算「提到过」——宁可漏报。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DECL = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:private\s+(?:const\s+)?(?:inline\s+|suspend\s+|operator\s+|lateinit\s+|open\s+|abstract\s+)*)'
    r'(fun|val|var|class|object|interface|typealias)\s+'
    r'(?:<[^>]*>\s*)?'
    # 扩展函数/属性：`fun Double.format2()`、`val String.foo` —— 接收者要跳过，名字在后面
    r'(?:[A-Za-z_][A-Za-z0-9_.<>,? ]*\.)?'
    r'([A-Za-z_][A-Za-z0-9_]*)'
)

hits = []
for base, dirs, files in os.walk(os.path.join(ROOT, 'app', 'src', 'main')):
    dirs[:] = [d for d in dirs if d not in {'build'}]
    for name in files:
        if not name.endswith('.kt'):
            continue
        path = os.path.join(base, name)
        text = open(path, encoding='utf-8').read()
        lines = text.split('\n')
        for number, line in enumerate(lines, start=1):
            m = DECL.match(line)
            if not m:
                continue
            kind, symbol = m.group(1), m.group(2)
            prev = lines[number - 2] if number >= 2 else ''
            if any(k in prev for k in ('@Test', '@Before', '@After', '@SerialName')):
                continue
            others = 0
            for other_number, other in enumerate(lines, start=1):
                if other_number == number:
                    continue
                others += len(re.findall(r'(?<![A-Za-z0-9_])' + re.escape(symbol) + r'(?![A-Za-z0-9_])', other))
            if others == 0:
                hits.append((os.path.relpath(path, ROOT), number, kind, symbol))

for rel, number, kind, symbol in hits:
    print('%s:%d  private %s %s' % (rel, number, kind, symbol))
print('\n候选 %d 个' % len(hits))
