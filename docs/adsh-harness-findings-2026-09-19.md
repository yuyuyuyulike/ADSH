# ADSH / Termux 环境问题报告（供 harness 开发者）

- 报告时间：2026-09-19
- 环境：Android 16，Kernel 6.6.118，小米 25060RK16C，Termux 0.119.0（DSH 定制构建，VERSION_CODE=1）
- 工作区：/storage/emulated/0/1/App
- 方法：全部结论均由实测得出，命令与报错附在每条问题下

---

## 0. 结论速览

| 能力 | 状态 |
|---|---|
| GNU bash 5.3.15 真身 + 完整特性 | 通过 |
| GNU coreutils / grep / sed / gawk / tar | 通过（非 busybox） |
| apt / pip / npm 国内镜像 | 通过 |
| Node 24.18.0 + npm 11.19.1 | 通过 |
| Vite 8.3.0 构建 + dev server（HOME/f2fs） | 通过（build 64ms，serve 352ms 就绪） |
| 原生模块编译链（make/clang/pkg-config/python3） | 通过 |
| 在工作区（FUSE）npm install | 失败（EACCES） |
| 在工作区 git 操作 | 默认失败（dubious ownership），已可修复 |
| /tmp 可写 | 失败 |

---

## 1. 关于工作流（开发者的问题）

**「素材放工作区 -> 在 f2fs 里干活 -> 成品写回工作区」这个流程是可行的，也是当前唯一稳妥的用法。**

建议固化为约定：

1. 工作区 /storage/emulated/0/1/App 只承担「素材入口」和「成品出口」：可读可写，但**不能装依赖、不能执行脚本、不能建软链**。
2. 构建/安装/打包在 f2fs 上进行，例如 /data/data/com.termux/files/home/projects/xxx 或专门的 scratch 目录。
3. 交付物优先是**构建后的产物**（dist/*、打包好的 zip、单个文件），而不是「需要 npm install 才能跑的工程源码」。
4. 若要交付源码工程，请明确告知，我可以在 HOME 里构建验证后再把源码回写到工作区；但使用者拿到后需要在可执行文件系统里 install。

---

## 2. P0：会直接让任务失败的问题

### 2.1 工作区落在 FUSE，不支持软链接与执行位

- 现象：
  - ln -s 报 Permission denied
  - chmod +x 后 stat 仍是 -rw-rw----
  - 执行脚本报 Permission denied
  - 文件系统类型：stat -f 显示 workspace = fuse，HOME = f2fs
- 连带后果（实测）：
  - npm install 默认要在 node_modules/.bin 建软链，直接 EACCES 失败：
    npm error code: 'EACCES', syscall: 'symlink',
    dest: '.../node_modules/.bin/cowthink'
  - npm install --no-bin-links 对简单包可行，但 esbuild 这类 postinstall 需执行二进制的包仍失败
- 根因：DSH 把 workspace 直接绑定到 Android 外部存储（FUSE）。

**建议的根因修复（按推荐度）**

1. 引入「可执行工作区」：真实 cwd 放 f2fs，/storage 目录仅作 import/export 挂载点，框架负责双向同步。
2. 或提供标准 scratch 变量与命令，例如环境变量 ADSH_SCRATCH 指向 f2fs 目录，外加 adsh-sync（rsync 双向）。
3. 或在创建 workspace 时同时准备 f2fs 侧目录，并在系统提示里明确告知 agent「重活去 f2fs」。

### 2.2 git 对工作区报 dubious ownership

- 现象：fatal: detected dubious ownership in repository at '/storage/emulated/0/1/App/.gittest'
- 根因：工作区文件属主是 u0_a221(media_rw)，而进程 uid 是 u0_a388。
- 影响：一切 git 操作失败；对有版本控制的项目是致命的。
- 已采取的临时修复（我已在本次会话执行）：
  - git config --global --add safe.directory '*'
  - git config --global --add safe.directory /storage/emulated/0/1/App
  - 复测：工作区内 git init / add / commit 已通过
- 建议：在 bootstrap 阶段就写入 safe.directory（至少覆盖工作区路径），或从根上统一文件属主与 app uid。

---

## 3. P1：影响体验或造成隐蔽失败

### 3.1 /tmp 不可写

- 现象：/tmp 属主 shell:shell，权限 drwxrwx--x，当前用户无写权限。
- 实测踩坑：
  - pip download --dest /tmp/... -> PermissionError
  - 我把 vite 日志重定向到 /tmp/vite.log -> Permission denied
- 现状：TMPDIR 已正确设置为 $PREFIX/tmp，但硬编码 /tmp 的第三方工具不会读它。
- 建议：harness 层尽量保证 /tmp 可用（bootstrap 时可考虑 proot 包装，或提供一个可写的 /tmp 视图），并同时导出 TMPDIR / TMP / TEMP。

### 3.2 python-pip postinst 的 py3compile 报 PermissionError

- 现象（apt 安装 python-pip 时）：
  PermissionError: [Errno 13] Permission denied: '/data/data/com.termux/files/usr/bin/python3.14'
  （由 py3compile -> subprocess Popen 抛出）
- 影响：apt 退出码仍为 0，但日志有 traceback；可能导致首次 .pyc 未生成。
- 现状：dpkg --configure -a 后状态干净，python3.14 权限为 -rwx------（属主可执行），功能正常。
- 怀疑方向：postinst 执行时机与文件/exec 就绪存在竞态，或 LD_PRELOAD 注入干扰了 exec。
- 建议：postinst 中对 py3compile 容错；或设置 PYTHONDONTWRITEBYTECODE / PYTHONPYCACHEPREFIX。

### 3.3 LD_PRELOAD 被全局注入

- 证据（termux-info）：
  LD_PRELOAD=<apk>/lib/arm64/libadshfence.so:$PREFIX/lib/libtermux-exec-ld-preload.so
- 影响：每个子进程都被注入。对调试器、检查 LD_PRELOAD 的程序、沙箱/静态敏感工具、node-gyp、Rust 工具链等，可能引发难定位的诡异问题。
- 本次会话尚未遇到它导致的直接故障，但属「未来疑难 bug」的高风险源。
- 建议：缩小注入范围（只对交互 shell 注入，而非全部子进程），或提供开关。

### 3.4 后台进程：能用，但无管理

- 文档层面：bash 工具不支持后台执行。
- 实测：nohup sleep 600 & 启动后，在**后续独立的 bash 调用**中仍存活（pgrep 可见）。
- 影响：dev server 能起来，但变成无生命周期、无日志、无端口回收的孤儿进程。
- 建议：正式提供后台任务 API（start / stop / logs / ports）。对网页开发这是刚需。

### 3.5 /bin/sh 与部分命令软链到 APK 内的 hashed 路径

- 现象：readlink -f /bin/sh -> /data/app/~~k_Eq8TzA19sFU-kzhOrhJw==/com.termux-.../lib/arm64/libbin_dash.so
- 同类：am、addpart 等也是 libbin_*.so
- 风险：
  - file / readelf / ldd 会把它当成 .so，个别按「可执行文件类型」判定的脚本或构建系统可能误判
  - 软链指向带 hash 的 APK 路径，**应用升级后路径变化会使软链失效**（除非 bootstrap 的 SYMLINKS.txt 机制负责重建）
- 建议：确认升级流程会重建这些软链；或改用稳定路径。

---

## 4. P2：观察与次要问题

### 4.1 inotify sysctl 不可读
- cat /proc/sys/fs/inotify/max_user_watches -> Permission denied
- 影响：无法调参；大项目可能 watcher 报 ENOSPC。实测 fs.watch 在 f2fs 正常，Vite HMR 正常。

### 4.2 包名使用 com.termux
- 若设备装有官方 Termux，会存在数据目录/包名冲突风险。建议独立包名，或启动时检测并提示。

### 4.3 dpkg 版本为 1.22.6-dirty
- 打过补丁的构建。若曾修改 dpkg 行为，注意与 apt 2.8.1 的兼容性验证。

### 4.4 esbuild 在 Android 上安装失败（Vite 8 已规避）
- 现象：npm install esbuild 的 postinstall 失败：
  Command failed: /apex/com.android.runtime/bin/linker64 .../esbuild/bin/esbuild --version
  error: ".../esbuild/bin/esbuild" has bad ELF magic: 23212f75
- 原因：esbuild 的 install.js 通过 linker64 执行 bin/esbuild 做校验，而该文件是脚本（开头为 #!/），因此被当成非法 ELF。
- 关键好消息：**Vite 8.3.0 使用 Rolldown（Rust）替代 esbuild**，实测安装依赖里没有 esbuild，构建与 dev server 全部正常。
- 影响范围：直接依赖 esbuild 的旧版 Vite、esbuild CLI、部分打包器会失败。建议在文档中推荐 Vite >= 8，并记录该坑。

### 4.5 工作区文件在会话中途消失（非 agent 所为）
- 会话开始时 /storage/emulated/0/1/App 下存在：hello.txt、dsh_tool_test.txt、adsh-session-20260918-090251.md/.zip
- 会话中途再次列目录，这些文件已不存在，仅剩 .adsh 与 .dsh-tools
- 说明：本次会话所有 rm 仅针对自建探针目录（.probe_sdcard/.smoke/.gittest/.nlbtest/.nlb2 等），未触碰上述文件
- 同类文件在兄弟目录 /storage/emulated/0/1/HTML/ 下存在（adsh-session-20260918-083713/090140）
- 建议：排查是否有工作区清理/会话轮转逻辑误删，或外部进程改动

---

## 5. 已验证通过的能力（正面结论）

- bash 真身：GNU bash 5.3.15(1)-release (aarch64-unknown-linux-android)，维护者为 Termux 官方 dev；索引数组、关联数组、进程替换、[[ =~ ]]、花括号展开、printf %q、extglob、coproc、mapfile 全部通过
- GNU 工具链：coreutils 9.11、grep 3.12、sed 4.10、gawk 5.3.2、tar 1.35、findutils 4.10，无 busybox/toybox
- 镜像：apt 清华源 13 MB/s；pip 清华；npm npmmirror
- Node 24.18.0 / npm 11.19.1；process.platform 报告为 android / arm64
- Vite 8.3.0：真实项目 build 成功（4 modules，64ms），dev server 352ms 就绪并正确返回 HTML 与转换后的 JS
- 原生编译链：make / clang 21.1.8 / pkg-config / python3 齐全
- 资源：4 核，11 GiB 内存（可用约 2.7 GiB），325 GB 可用空间

---

## 6. 建议排期清单

| 优先级 | 事项 |
|---|---|
| P0 | 提供 f2fs 上的可执行构建目录（或自动同步机制） |
| P0 | bootstrap 自动写入 git safe.directory |
| P1 | 解决 /tmp 不可写（或明确约定只用 TMPDIR） |
| P1 | 修复 python-pip postinst 的 py3compile 报错 |
| P1 | 收窄 LD_PRELOAD 注入范围 |
| P1 | 提供受管的后台任务 API（dev server 刚需） |
| P2 | 排查工作区文件消失问题 |
| P2 | 文档记录 esbuild/Android 坑，推荐 Vite >= 8 |
