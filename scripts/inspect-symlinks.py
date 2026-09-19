#!/usr/bin/env python3
"""确认 bootstrap 里哪些 bin/ 名字是符号链接、指向谁——决定 execLibs 需要随包哪些真实二进制。"""
import sys, zipfile, collections
zf = zipfile.ZipFile(sys.argv[1])
links = {}
for raw in zf.open('SYMLINKS.txt').read().decode('utf-8', 'replace').splitlines():
    if '\u2190' not in raw:
        continue
    target, link = raw.split('\u2190', 1)
    links['./' + link.strip().lstrip('./')] = target.strip()

real = {i.filename for i in zf.infolist() if not i.is_dir()}
bin_real = sorted(n for n in real if n.startswith('bin/'))
bin_links = sorted(n for n in links if n.startswith('bin/'))
print('bin 真实文件:', len(bin_real), ' bin 符号链接:', len(bin_links))

targets = collections.Counter(links[n] for n in bin_links)
print()
print('=== bin 符号链接的 target 分布（前 15）===')
for t, c in targets.most_common(15):
    print(f'{c:5d}  {t}')

print()
print('=== 谁指向 coreutils（前 20）===')
n = 0
for link, target in sorted(links.items()):
    if 'coreutils' in target and link.startswith('bin/'):
        n += 1
        if n <= 20:
            print(' ', link, '->', target)
print('  指向 coreutils 的 bin 链接总数 =', sum(1 for l, t in links.items() if 'coreutils' in t and l.startswith('bin/')))

print()
print('=== 需要随包的真实 bin 列表（前 40）===')
for name in bin_real[:40]:
    print(' ', name)
print('  ... 共', len(bin_real))
