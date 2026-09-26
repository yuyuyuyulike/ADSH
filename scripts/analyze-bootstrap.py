#!/usr/bin/env python3
"""分析 bootstrap 构成，用于决定哪些二进制要进 execLibs（必须以 nativeLibraryDir 形式分发才能 exec）。"""
import sys, zipfile, collections

zip_path = sys.argv[1]
zf = zipfile.ZipFile(zip_path)

# SYMLINKS.txt 里的 linkPath 由安装器创建，不是真实文件，不需要进 execLibs
symlink_links = set()
with zf.open('SYMLINKS.txt') as fh:
    for raw in fh.read().decode('utf-8', 'replace').splitlines():
        if '\u2190' in raw:
            link = raw.split('\u2190', 1)[1].strip()
            symlink_links.add(link.lstrip('./'))

buckets = collections.defaultdict(lambda: [0, 0])
top = []
for info in zf.infolist():
    if info.is_dir():
        continue
    name = info.filename
    if name in symlink_links:
        kind = 'symlink(staged)'
    else:
        kind = name.split('/')[0]
    buckets[kind][0] += 1
    buckets[kind][1] += info.file_size
    if kind == 'bin':
        top.append((info.file_size, name))

print('=== 按目录汇总（真实文件）===')
for kind, (count, size) in sorted(buckets.items(), key=lambda kv: -kv[1][1]):
    print(f'{kind:20s} files={count:5d} bytes={size:>12,d}  ({size/1048576:.1f} MiB)')

print()
print('=== bin/ 最大的 30 个 ===')
for size, name in sorted(top, reverse=True)[:30]:
    print(f'{size:>10,d}  {name}')
print()
print('bin/ 真实文件数 =', len(top), ' 合计 =', f'{sum(s for s,_ in top):,d}', 'bytes')
