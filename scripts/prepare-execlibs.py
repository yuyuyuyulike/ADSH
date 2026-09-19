#!/usr/bin/env python3
"""把 bootstrap 里 bin/ 下的真实可执行文件复制成 execLibs/<abi>/lib<name>.so，并生成映射表 asset。

原理（PoC-1 / PoC-3 真机实证）：
  - Android 10+ 的 app 私有目录禁止 execve（SELinux neverallow）；
  - 只有 nativeLibraryDir 下的文件可执行；
  - app 私有目录里的「符号链接」指向 nativeLibraryDir 时，exec 会被内核解析到最终文件，因此可执行（PoC-3 T6）。
所以：二进制随包放进 execLibs（安装后位于 nativeLibraryDir），运行期在 $PREFIX/bin 下建同名符号链接。
"""
import os
import re
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIP = os.path.join(ROOT, 'assets-src', 'bootstrap', 'bootstrap-aarch64.zip')
ABI = sys.argv[1] if len(sys.argv) > 1 else 'arm64-v8a'
DEST = os.path.join(ROOT, 'app', 'src', 'main', 'execLibs', ABI)
MAP = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'execlibs.map')


def sanitize(name: str) -> str:
    return re.sub(r'[^A-Za-z0-9_]', '_', name)


def main() -> None:
    if not os.path.isfile(ZIP):
        raise SystemExit('missing ' + ZIP + ' (run scripts/fetch-bootstrap.sh first)')
    os.makedirs(DEST, exist_ok=True)
    os.makedirs(os.path.dirname(MAP), exist_ok=True)

    zf = zipfile.ZipFile(ZIP)
    entries = []
    used = {}
    for info in zf.infolist():
        if info.is_dir():
            continue
        # bin/ 下的一级文件 + apt 的辅助程序（lib/apt/**）+ libexec/**（不含 installed-tests）
        # —— 后面这两类是「被别的程序 exec 的」可执行文件：apt 会去起 lib/apt/methods/http，
        # 少了它们 apt update 就是「Method ... did not start correctly」。
        name = info.filename
        include = name.startswith('bin/') and '/' not in name[len('bin/'):]
        include = include or name.startswith('lib/apt/')
        include = include or (name.startswith('libexec/') and '/installed-tests/' not in name)
        if not include:
            continue
        with zf.open(name) as probe:
            head = probe.read(4)
        is_elf = head == b'\x7fELF'
        is_symlink = (info.external_attr >> 16) & 0o170000 == 0o120000
        if not is_elf:
            # bin/ 下的「脚本」（termux-info、pkg、df、apt-key、termux-* …，共 79 个）一起搬进来。
            #
            # 为什么脚本也能这样跑：execve(脚本) 时内核只做两件事 —— 检查脚本本身的执行位，
            # 读它第一行的 shebang，然后 exec 解释器、把脚本当参数传过去。所以真正被
            # 「app 私有目录禁止 exec」卡住的是**解释器**（$PREFIX/bin/bash 在 app 私有目录里），
            # 而脚本文件只要自己可执行就行。把脚本也放进 nativeLibraryDir（那里是 0700、
            # 可 exec），再在 $PREFIX/bin 下建同名符号链接，内核解析到 nativeLibraryDir 里的
            # 脚本 → 执行位有了 → shebang（已被就地改成别名前缀）指向的 bash 也是符号链接 →
            # 同样落在 nativeLibraryDir → 整条链路全通。
            #
            # 这正是脚本版「符号链接农场」，不需要 termux-exec，也不需要 proot。
            if not (name.startswith('bin/') and '/' not in name[len('bin/'):] and not is_symlink):
                continue
        lib = 'lib' + sanitize(name) + '.so'
        if lib in used:
            raise SystemExit('name collision %s: %s vs %s' % (lib, used[lib], name))
        used[lib] = name
        entries.append((lib, name))

    total = 0
    for lib, member in entries:
        out_path = os.path.join(DEST, lib)
        with zf.open(member) as src, open(out_path, 'wb') as out:
            shutil.copyfileobj(src, out)
        total += os.path.getsize(out_path)

    with open(MAP, 'w', encoding='utf-8') as fh:
        fh.write('# libname\t<relative path under $PREFIX>' + chr(10))
        for lib, member in sorted(entries):
            fh.write(lib + chr(9) + member + chr(10))

    print('execLibs entries : ' + str(len(entries)))
    print('execLibs dir     : ' + DEST)
    print('execLibs bytes   : ' + format(total, ','))
    print('mapping asset    : ' + MAP)


main()
