# M0 技术验证报告（阶段结论：全部通过）

日期：2026-09-14　设备：Android 16 / SDK 36，内核 6.6.118-android15，aarch64　targetSdk 37 / minSdk 26

四个 PoC 全部在真机通过，且过程中暴露并修掉了一个只有真机才会出现的问题。

## 结论一览

| PoC | 目标 | 结果 | 证据文件 |
|---|---|---|---|
| PoC-1 | 无 root/无 proot 下能否 exec APK 内可执行文件 | ✅ `nativeLibraryDir/lib*.so` 可 exec（exit=0）；**app 私有目录下同文件 `error=13`**（即使 `canExecute()=true`） | docs/exec-escape-spike.md |
| PoC-2 | PTY 与会话控制 | ✅ `tty=/dev/pts/0`；`stty size` 初值 `24 80`；resize 后收到 **SIGWINCH** 且 `stty size=40 100` | 本文 §2 |
| PoC-3 | Termux bootstrap 重定位 | ✅ 解压 3478 文件/706ms、前缀改写 276 文件、1213 条符号链接、186 个可执行文件，**1.0–1.1s 装完**；端到端 shell 操作全部可用 | docs/bootstrap-spike.md |
| PoC-4 | 共享存储真实路径 | ✅ shell 直接 `ls/mkdir/写/cat/mv/cp/rm` `/storage/emulated/0/…` | 本文 §3 |

## 一、两个改变设计的发现

**1）exec 的关键是「符号链接」，不是「把二进制放进 nativeLibraryDir」本身。**
内核 execve 会先解析符号链接，再对最终文件做 SELinux 检查。所以 app 私有目录里的符号链接指向 `nativeLibraryDir` 是**可执行**的；而直接把真实文件放在 app 私有目录**不可执行**。最终方案：186 个 ELF 随包进 `execLibs`，安装期在 `$PREFIX/bin` 下建成符号链接。

**2）termux-exec 不可用（实测）。**
`LD_PRELOAD` 确实生效（`/proc/self/maps` 里能看到 `libtermux-exec-*`，maps-termux=3/4），但三种变体 + `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force` 都无法让 `$PREFIX/bin/ls` 跑起来。于是放弃 preload 方案，改用符号链接农场（不需要 linker64，也不需要自写 shim）。`linker64 $PREFIX/bin/bash` 虽然可行，但既然符号链接够用就不引入这个额外依赖。

## 二、PoC-2 实测输出

```
spawned pid = 17652 (rows=24 cols=80)
:/data/local/tmp $ tty          -> /dev/pts/0
:/data/local/tmp $ stty size    -> 24 80
（resize 到 40x100）
:/data/local/tmp $ GOT-WINCH    <- trap 'echo GOT-WINCH' WINCH 被触发
:/data/local/tmp $ stty size    -> 40 100
```
说明 setsid + TIOCSCTTY + dup2 正确，窗口尺寸走 TIOCSWINSZ，内核向前台进程组投递了 SIGWINCH。

## 三、PoC-4 实测输出（真实共享存储）

```
isExternalStorageManager = true
list-Download: 6a6414edcf695_31915_3938.png / 6a6414f11f015_31915_8751.jpg / DLManager
created / wrote / hello-adsh / moved / copied
-rw-rw----. 1 u0_a221 media_rw 11 hello2.txt
-rw-rw----. 1 u0_a221 media_rw 11 hello3.txt
removed
```
覆盖 §3.5 断言表的 查看 / 创建 / 读取 / 写入 / 移动 / 复制 / 删除。

## 四、过程中发现的真机问题（已修）

**问题**：每次 APK 更新，`nativeLibraryDir` 路径都会变（含随机段），导致安装期建的 1213 + 186 条符号链接**全部指向已不存在的旧路径**，bash 报 `No such file or directory`。

**修复**：manifest 记录安装时的 `nativeLibraryDir`；启动时若与当前不一致，只重建链接（不解压）：
```
relinked after app update: symlinks=1213, executables=186 in 65ms
```
修复后 PoC-4 仍全部通过。这条对真实使用很关键——否则**每次升级 App 都会把 shell 环境弄坏**。

## 五、当前构建产物

| 项 | 值 |
|---|---|
| debug APK | 40.3 MB（含 bootstrap 32.8 MB + execLibs 14.7 MB + ripgrep 4.5 MB） |
| 安装后占用 | `files/usr` ≈ 90 MB |
| 首次安装耗时 | 1.0–1.1 s（解压 + 改写 + 建链接） |
| 升级后重链接耗时 | 65 ms |
| 已可执行工具 | 186 个 ELF（coreutils 为 multicall，`ls`/`cat`/`head` 等经 `SYMLINKS.txt` 指向它） |
| 额外静态工具 | ripgrep 15.2.0（musl 静态，4,466,000 B） |

## 六、M0 对方案书的修订

- §5.3 exec 通道：由「linker64 为主」改为**符号链接农场**（方案 A 的强化版），并注明 termux-exec 实测不可用。
- §3.3 工作区绑定：shell 实测校验（`test -w`）的必要性再次被印证——`canExecute()` 会骗人。
- 新增：APK 升级后必须重链接（原方案未覆盖此场景）。

## 七、下一步（M1）

1. 把 app 模块拆成方案书 §5.7 的多模块骨架。
2. 抽出 `TermuxRuntime`（环境变量、执行、超时、输出上限、取消）与 `ExecBackend` 接口。
3. 落地 `bash`/`read`/`write`/`edit`/`glob`/`grep` 六个静态工具 + 工作区三形态（W1/W2/W3）。
4. 把 PoC 阶段的验证台（`MainActivity`）替换为真正的会话页骨架。
