#!/usr/bin/env python3
"""版本目录里没人引用的条目（第五十八轮）。

判据：libs.versions.toml 的 [libraries] / [plugins] / [bundles] 里每个条目，
在所有 *.gradle.kts / *.toml / settings.gradle.kts 里都找不到对应访问器（libs.a.b / alias(libs.plugins.x)）就算候选。
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, 'gradle', 'libs.versions.toml')
TEXT = []
for base, dirs, files in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in {'.git', 'build', '.gradle', '.idea'}]
    for name in files:
        if name.endswith(('.kts', '.toml')):
            TEXT.append(open(os.path.join(base, name), encoding='utf-8', errors='ignore').read())
CORPUS = '\n'.join(TEXT)

section = None
unused = []
for line in open(CATALOG, encoding='utf-8'):
    stripped = line.strip()
    if stripped.startswith('['):
        section = stripped
        continue
    if section not in ('[libraries]', '[plugins]', '[bundles]') or '=' not in stripped or stripped.startswith('#'):
        continue
    alias = stripped.split('=')[0].strip()
    parts = alias.split('-')
    dotted = 'libs.' + '.'.join(parts) if section == '[libraries]' else 'libs.plugins.' + '.'.join(parts)
    if section == '[bundles]':
        dotted = 'libs.bundles.' + '.'.join(parts)
    if dotted not in CORPUS:
        unused.append((section, alias, dotted))

print('没人引用的版本目录条目:', len(unused))
for section, alias, dotted in unused:
    print('  %-12s %-28s (%s)' % (section, alias, dotted))
