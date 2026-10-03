#!/usr/bin/env python3
"""从 dsh 前端的 app.asar 里取文件（用于「参照 dsh」时核对原实现）。

为什么需要它：dsh 的实现只在 121MB 的 resources/app.asar 里，本机没有解包后的源码树，
而设置页 / 提示词 / 图标那些还原工作经常要回原实现核口径（R47 就是靠它拿到
validateDeepSeekModels 与 EditorFooter.submitDisabled 的确切行为的）。

asar 的格式（自己解析，不装 asar 包）：开头 8 字节 = [4, pickle 长度]，
pickle 里前 4 字节是 JSON 长度（再对齐 4 字节），JSON 从 pickle[8:] 开始；
每个文件的 offset 相对「8 + pickle 长度」。

用法：
  python scripts/dsh-extract.py --list settings-models          # 只列路径与大小
  python scripts/dsh-extract.py dsh-client-ui-settings-models/lib/client.js
  # 取出来默认写到 .git/dsh-<basename>（.git 里不进工作区、也不会被提交）

默认 asar 路径可用环境变量 DSH_ASAR 覆盖，或用 --asar 指定。
"""
import argparse
import json
import os
import struct
import sys

DEFAULT_ASAR = 'D:/WSN2005/deepseek harness/resources/app.asar'


def read_header(asar):
    with open(asar, 'rb') as f:
        first = f.read(8)
        if len(first) < 8:
            sys.exit('不是 asar：文件太短')
        _, pickle_size = struct.unpack('<II', first)
        pickle = f.read(pickle_size)
        if len(pickle) < pickle_size:
            sys.exit('asar 头被截断')
        json_size = struct.unpack('<I', pickle[:4])[0]
        return json.loads(pickle[8:8 + json_size].decode('utf-8')), 8 + pickle_size


def walk(node, path, out):
    for name, meta in node.get('files', {}).items():
        cur = path + '/' + name
        if 'files' in meta:
            walk(meta, cur, out)
        else:
            out.append((cur, int(meta.get('size', 0)), int(meta.get('offset', 0))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path', nargs='?', help='asar 内的路径（可以只给结尾一段）')
    ap.add_argument('--asar', default=os.environ.get('DSH_ASAR', DEFAULT_ASAR))
    ap.add_argument('--list', action='store_true', help='只列命中项，不取内容')
    ap.add_argument('--out', help='写到哪个文件（默认 .git/dsh-<basename>）')
    args = ap.parse_args()

    if args.path is None and not args.list:
        sys.exit('要么给路径，要么用 --list')
    needle = (args.path or '').lstrip('/')
    header, base = read_header(args.asar)
    entries = []
    walk(header, '', entries)

    hits = [e for e in entries if e[0].lstrip('/').endswith(needle)] if needle else list(entries)
    if not hits and needle:
        hits = [e for e in entries if needle in e[0]]
    hits.sort(key=lambda e: e[0])
    if not hits:
        sys.exit('没命中：' + needle)
    if args.list or (len(hits) > 1 and not args.out):
        for cur, size, _ in hits[:60]:
            print('%9d  %s' % (size, cur))
        if len(hits) > 60:
            print('… 还有 %d 个' % (len(hits) - 60))
        if args.list or len(hits) > 1:
            return
    cur, size, off = hits[0]
    out = args.out or ('.git/dsh-' + cur.split('/')[-1])
    with open(args.asar, 'rb') as f:
        f.seek(base + off)
        data = f.read(size)
    with open(out, 'wb') as g:
        g.write(data)
    print('取出 %s（%d 字节）-> %s' % (cur, size, out))


main()
