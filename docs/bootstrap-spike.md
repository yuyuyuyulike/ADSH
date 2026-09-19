# PoC-3：Termux bootstrap 重定位（结论：通过）

日期：2026-09-14　对应方案书 §5.3 / §6 M0-PoC3　设备：Android 16（SDK 36），内核 6.6.118-android15，aarch64

## 目的

在**无 root、无 proot、现代 targetSdk（37）**下，把官方 Termux bootstrap 重定位到
`/data/user/0/com.adsh.app.debug/files/usr`，并让 bash 及其工具链真正可用。

## 一、关键结论（三条，都改变了原设计）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **app 私有目录里的「符号链接」指向 nativeLibraryDir 时可被 exec** | 建 `bin/zz-librg -> nativeLibraryDir/librg.so`，直接 exec 该符号链接 → exit=0，输出 ripgrep 15.2.0 |
| 2 | **`/system/bin/linker64 <app私有目录程序>` 也能直接跑**（方案 B 可用） | T1：`linker64 $PREFIX/bin/bash -c ...` → exit=0 |
| 3 | **termux-exec 的 LD_PRELOAD 会被加载但不改写 exec**（不可用） | `maps-termux=3/4`（preload 确实映射进来了），但 `$PREFIX/bin/ls` 仍 `Permission denied`；三种变体 + `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force` 结果一致 |

**因此最终设计选「符号链接农场」**：把需要的可执行文件随包放进 `execLibs`（安装后位于 nativeLibraryDir），
首启在 `$PREFIX/bin` 下把它们替换成指向 nativeLibraryDir 的符号链接。不依赖 linker64，也不依赖 termux-exec。

> 注意区分：符号链接（可 exec）与直接放在 app 私有目录的真实文件（不可 exec）——后者是 PoC-1 的结论。
> 内核在 execve 时会**先解析符号链接**再检查最终文件，所以链接到 apk_data_file 是放行的。

## 二、安装实测数据

```
extracted 3478 files in 706ms
rewrote prefix in 276 text files
created 1213 symlinks
linked executables to nativeLibraryDir: 186
install finished in 1042ms
PREFIX = /data/user/0/com.adsh.app.debug/files/usr
bin entries = 401     lib entries = 119
```

- bootstrap：`bootstrap-2026.09.13-r1+apt.android-7`，`bootstrap-aarch64.zip` 32,799,409 B（SHA-256 已校验），解压 90,081,197 B / 3773 条目。
- prefix 改写命中 **276 个文本文件**（与实测预估的 274 一致）；`SYMLINKS.txt` 的 **1213 条**符号链接全部建成（含 20 条绝对路径目标的重写）。
- `bin/` 真实文件 265 个，其中 **186 个是 ELF**（另 79 个是脚本，留在 `$PREFIX` 里用 `bash <file>` 跑）。
- `execLibs` 合计 **14,730,880 B**（+ ripgrep 4,466,000 B）。

## 三、端到端验证（T7 实测输出）

```
--- e2e start ---
PREFIX=/data/user/0/com.adsh.app.debug/files/usr
total 828
drwx------.  2 u0_a375 u0_a375 24576 Sep 14 18:03 .
lrwxrwxrwx.  1 u0_a375 u0_a375     9 Sep 14 18:03 [ -> coreutils
hello-ADSH                       <- echo hello-world | sed s/world/ADSH/
grep-lines=40                    <- grep -c . $PREFIX/etc/profile
.../usr/etc/resolv.conf          <- find $PREFIX/etc -maxdepth 1 -type f
data                             <- mkdir -p + echo > file + cat
rw-remove-ok                     <- rm -rf
--- e2e done ---
```

覆盖了方案书 §3.5 断言表里的查看 / 创建 / 读取 / 写入 / 删除 / 文本处理 / 查找。
另外 `[ -> coreutils` 一行说明：**coreutils 是 multicall 二进制**，`ls`/`cat`/`head` 等在
`SYMLINKS.txt` 里是指向 `coreutils` 的链接，而 `coreutils` 本身又是指向 nativeLibraryDir 的链接，
符号链接链可被内核完整解析。

## 四、遗留与后续

- `read`/`write`/`edit`/`glob`/`grep` 这些 Kotlin 侧文件工具直接用真实路径读写 `$PREFIX`，与 shell 共享同一工作区。
- ripgrep 目前只作为 `librg.so` 存在，M1 里会给它加一条 `bin/rg` 映射。
- 未随包的脚本类工具（例如 `bzgrep`）可用 `bash <path>` 执行；模型侧提示词要写清这一点。
- 若将来要支持更多工具，只需把它们放进 `execLibs` 并在 `execlibs.map` 增加一行，安装器会自动建链接；
  `INSTALLER_VERSION` 递增即触发已装设备重装。

## 五、复现

```bash
./scripts/prepare-assets.sh      # fetch-tools + fetch-bootstrap + prepare-execlibs
./scripts/build-debug.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.adsh.app.debug      # 强制重装 bootstrap（或递增 INSTALLER_VERSION）
adb shell am start -n com.adsh.app.debug/com.adsh.app.MainActivity
adb logcat -d -s ADSH_POC3:I
```
