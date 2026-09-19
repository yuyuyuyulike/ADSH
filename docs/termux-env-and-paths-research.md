# 内嵌 Termux bootstrap 的环境变量与路径问题调研（无 proot / 无 LD_PRELOAD 伪装 root）

调研日期：本轮（web_search / web_fetch 实测抓取）
适用对象：把官方 Termux bootstrap 解包到 `/data/data/<自己的包名>/files/usr`（或 `/data/user/0/...`）当运行时、targetSdk 现代（29+）、无 root 的自建 App（本项目 ADSH：`com.adsh.app[.debug]`，targetSdk 37，等长别名 `/data/data/<pkg>/u -> <filesDir>/usr`）。

> 总纲一句话：**官方 bootstrap 的每一个包都是为 `/data/data/com.termux/files/usr` 编译的**（`scripts/properties.sh` 里 `TERMUX_APP__PACKAGE_NAME="com.termux"`），shebang、`DT_RUNPATH`、apt/dpkg 状态目录全部写死。termux-exec 只解决两件事（Android 10+ 的 W^X exec 限制、`/bin`/`/usr/bin` shebang 改写），**它不解决"前缀不同"**。重定位必须自己做（构建期重新编译 bootstrap，或运行期改写），这点与现有结论一致。

---

## 1. libtermux-exec / LD_PRELOAD

### 1.1 它到底做什么

依据：[termux-exec-package Technical Docs](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/technical/index.md)（本机通过 jsDelivr 抓到全文）。

它 `$LD_PRELOAD` 进去后**覆盖 bionic 的整个 `exec()` 家族**（`ExecIntercept.c` / `ExecVariantsIntercept.c`），做两件事：

1. **绕过 Android 10+ 的 `app_data_file` exec 限制（W^X）**：
   Android ≥10 + `targetSdkVersion ≥ 29` 的 `untrusted_app` 域被 neverallow 掉了 `app_data_file:file execute_no_trans`（只留 `execute`，即 `dlopen`/mmap 可以，`exec()` 不行）。termux-exec 把
   `execve(path, args, env)` 改写成 `execve("/system/bin/linker64", [path, args...], env)`（`system_linker_exec`）；脚本则 `linker64 <interpreter> <script> [args]`。内核/SELinux 只看到 `system_linker_exec` 类型的 linker 被 exec。
   参考 SELinux 原文（[Android-Docs: App Data File Execute Restrictions](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md)）：

       allow untrusted_app_all app_data_file:file { r_file_perms execute };      // dlopen 可以
       neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app }
                 { app_data_file privapp_data_file }:file execute_no_trans;       // exec 不行
       allow untrusted_app_all system_linker_exec:file execute_no_trans;         // linker64 可以

2. **`/bin`、`/usr/bin` 与这类 shebang 的路径改写**：exec 路径或脚本 shebang 解释器落在 `/bin/*`、`/usr/bin/*` 时，把 `*/bin/` 前缀换成 `$TERMUX__PREFIX/bin/`。Android 9+ 的 `/bin` 是 `/system/bin` 的符号链接（Android ≤8.1 根本没有 `/bin`），`/usr` 从来不存在，所以 `#!/bin/sh`、`#!/usr/bin/env python` 这类 shebang 不改写不是报 `No such file` 就是跑到 Android 的 toybox 上。

不拦截的：`syscall(SYS_execve)` 直调、静态链接二进制（zig/musl 静态）、`/system/bin/*` 的系统二进制。

两个变体：`libtermux-exec-direct-ld-preload.so`（direct 执行路径）与 `libtermux-exec-linker-ld-preload.so`（system_linker_exec 专用，多拦一些函数）；安装时 postinst 跑 `termux-exec-system-linker-exec is-enabled` 选一个**拷贝**成 `$TERMUX__PREFIX/lib/libtermux-exec-ld-preload.so`（主用），并留 `libtermux-exec.so` 符号链接给旧客户端。判别命令：`$PREFIX/bin/termux-exec-system-linker-exec is-enabled`。
linker-exec 模式下 `/proc/self/exe` 变成 linker64，所以库会导出 `TERMUX_EXEC__PROC_SELF_EXE` 给需要读自己的程序。

### 1.2 为什么 Termux 必须设 LD_PRELOAD

- 只要进程要 exec **app 私有目录里的文件**（Termux 里就是一切：`$PREFIX/bin/bash`、`login`、`ls`…），在 targetSdk≥29 + Android≥10 上就必须有这层改写，否则 `EACCES`（本仓库 PoC-1 已实测 `error=13, Permission denied`）。
- 同时兜住所有 `#!/bin/sh`、`#!/usr/bin/env` 脚本（bootstrap 里大量脚本如此）。
- **Termux App 自己并不导出 `LD_PRELOAD`**：只有 `$PREFIX/bin/login` 导出（`termux-tools/scripts/login.in`）：

      if [ -f "@TERMUX_PREFIX@/lib/libtermux-exec-ld-preload.so" ]; then
          export LD_PRELOAD="@TERMUX_PREFIX@/lib/libtermux-exec-ld-preload.so"
          $SHELL -c "coreutils --coreutils-prog=true" > /dev/null 2>&1 || unset LD_PRELOAD
      elif [ -f "@TERMUX_PREFIX@/lib/libtermux-exec.so" ]; then ...

  这解释了为什么"给插件/RUN_COMMAND 起的 shell 命令"没有 preload（termux-exec docs 明确说 App 不导出）。自建 App 必须在**每一个入口**（终端、Agent bash、apt/dpkg、sshd、后台任务）自己设。
- **两个实用细节直接抄**：
  - 设完要做**自检**（Termux 用 `coreutils --coreutils-prog=true`）：`LD_PRELOAD` 在个别 ROM 上不被动态链接器采纳（[termux-packages#2066](https://github.com/termux/termux-packages/issues/2066)、commit `1ec6c042`/`6fb2bb2f`），失败要 unset 并降级。
  - 改 `LD_PRELOAD` 只对**之后新 exec 的进程**生效；当前 shell unset 不会卸载已映射的库。

### 1.3 自建 App 设 LD_PRELOAD 的前提（逐条结论）

| 前提 | 结论 | 依据 |
|---|---|---|
| `android:extractNativeLibs` / nativeLibraryDir | **不需要**。preload 的 .so 放在 app 私有目录（`$PREFIX/lib`）就行：动态链接器加载 .so 是 dlopen 语义，SELinux 只要 `app_data_file:file execute`，`untrusted_app_all` 一直有；**不需要 `execute_no_trans`，也不需要 `chmod +x`**（Termux 的 `.so` 就是 0644）。nativeLibraryDir 是"要 exec 的 ELF"才需要（本仓库 PoC-1 的路线）。 | Android-Docs（同链接）：`execute` 供 dlopen，`execute_no_trans` 才供 exec；`allow untrusted_app_all app_data_file:file { r_file_perms execute }` |
| `extractNativeLibs` 与 bootstrap 打包 | Termux App 用 `packagingOptions { jniLibs { useLegacyPackaging true } }`（等价旧 `extractNativeLibs=true`）+ 每 ABI split，把 bootstrap zip 用 `.incbin` 塞进 `libtermux-bootstrap.so`（`app/src/main/cpp/termux-bootstrap-zip.S`），运行时 `System.loadLibrary("termux-bootstrap")` + JNI `getZip()` 取出 zip 解压。**不设 `useLegacyPackaging` 时 AGP 对 minSdk>23 默认 Stored/page-aligned，nativeLibraryDir 下没有真实文件 → exec 失败**（本仓库 PoC-1 已复现）。 | [termux-packages wiki: For-maintainers / Bootstraps](https://github.com/termux/termux-packages/wiki/For-maintainers)、`termux-bootstrap-zip.S`、`termux-bootstrap.c`、`app/build.gradle` |
| API level | Android ≥10 才有/才需要 `system_linker_exec`（linker 支持"用 linker 执行任意 ELF"是 Android 10 加的）；Android <10 的 `untrusted_app` 可 `execute_no_trans`，直接 exec。 | technical docs（System Linker Exec Solution） |
| bionic namespace | 绝对路径的 `dlopen`/`LD_PRELOAD` 不受 namespace 的 soname 搜索限制；但 **preload 库自身的 `DT_NEEDED` 必须能解析**（`DT_RUNPATH` 或 `$LD_LIBRARY_PATH`）。所以别把 preload 库链接到 `$PREFIX/lib` 下的私有库（Termux 的只依赖 system 的 libc/libdl）。 | technical docs；`readelf -d` 实践 |
| exec 权限 / chmod | `.so` 不需要可执行位；**要 exec 的真实文件**需要（TermuxInstaller 解压后对 `bin/`、`libexec`、`lib/apt/apt-helper`、`lib/apt/methods` 一律 `chmod 0700`）。 | `TermuxInstaller.java` |
| W^X 与 targetSdk | App **自己的进程**（zygote fork 出来的）拿不到 `LD_PRELOAD`，所以 targetSdk≥29 时 App 用 `Runtime.exec()` 直接跑 `$PREFIX/bin/*` 一定失败。三条正路：(a) `targetSdk` 保持 ≤28（**Termux 至今 `gradle.properties` 里就是 `targetSdkVersion=28`**，这是它能直接 exec 的根本原因）；(b) 首跳 exec `/system/bin/sh -c 'LD_PRELOAD=... exec ...'` 或 `/system/bin/linker64 <exe>`，之后由 termux-exec 接管；(c) 可执行文件走 nativeLibraryDir + 符号链接（本仓库 PoC-3 方案）。 | termux-app `gradle.properties`、`TermuxShellUtils.setupShellCommandArguments()`（Java 侧自己解析 shebang 把 `/bin/foo`→`$PREFIX/bin/foo`，正是对"App 进程没有 preload"的补偿） |

### 1.4 硬编码路径问题（本仓库 PoC-3 "preload 加载了但不改写 exec" 的精确解释）

- **libtermux-exec 不硬编码"自己的路径"**；它硬编码的是**回退用的 Termux 目录**。输入变量（[Usage Docs](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/usage/index.md)、termux-core 的 `TermuxShellEnvironment.h`/`TermuxFile.h`）：
  - `TERMUX_APP__DATA_DIR`（默认 `/data/data/com.termux`）
  - `TERMUX_APP__LEGACY_DATA_DIR`（默认 `/data/data/com.termux`，非 legacy 值会自动转 legacy）
  - `TERMUX__PREFIX`（默认 `/data/data/com.termux/files/usr`，长度上限 `TERMUX__PREFIX_DIR___MAX_LEN=90`）
  - `TERMUX__SE_PROCESS_CONTEXT`（不设则读 `/proc/self/attr/current`）
  - `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE`（`enable`/`disable`/`force`）、`TERMUX_EXEC__EXECVE_CALL__INTERCEPT`、`TERMUX_EXEC__LOG_LEVEL`
  这些默认值来自编译期 `scripts/properties.sh`（`TERMUX_APP__DATA_DIR="/data/data/$TERMUX_APP__PACKAGE_NAME"`），官方 bootstrap ⇒ `/data/data/com.termux`。
- **关键判定函数**（已读源码 `TermuxExecLDPreload.c`）：`shouldEnableSystemLinkerExecForFile(executablePath)` → `termuxApp_dataDir_isPathUnder(executablePath)`。**只有 exec 的文件路径"位于 `TERMUX_APP__DATA_DIR` 或 `TERMUX_APP__LEGACY_DATA_DIR` 之下"才会启用 linker64**，否则回落到直接 exec → `untrusted_app` 直接 `EACCES`。文档也写明这条检查用 DATA_DIR 而不是 ROOTFS，理由是要覆盖 `$TERMUX__APPS_DIR`、`$TERMUX__CACHE_DIR`。
- **本仓库最可能的病根**：`TermuxRuntime.kt` 里

      "TERMUX_APP__DATA_DIR=" + dataDir,          // /data/user/0/com.adsh.app.debug
      "TERMUX_APP__LEGACY_DATA_DIR=" + dataDir,   // ← 也给了 /data/user/0/...（错）
      "TERMUX__PREFIX=" + p + "/",                // ← 尾斜杠
      "TERMUX__HOME=" + home + "/",               // ← 尾斜杠

  而实际被 exec 的路径（等长别名方案）长这样：`/data/data/com.adsh.app.debug/u/bin/ls`。它**不在** `/data/user/0/com.adsh.app.debug` 之下、也不在（被错设的）"LEGACY"之下 ⇒ 库认为"这不是我的 app 数据目录" ⇒ 不改写 ⇒ 观测到的 `maps 里有 libtermux-exec-*，但 ls 仍 Permission denied`。`TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force` 也救不了：force 只跳过"是否需要"的判断，仍要求"exec 路径在 DATA_DIR/LEGACY_DATA_DIR 之下"。
- **修法与验证（可直接落地）**：
  1. `TERMUX_APP__DATA_DIR=/data/user/0/<pkg>`（即 `ApplicationInfo.dataDir`）、`TERMUX_APP__LEGACY_DATA_DIR=/data/data/<pkg>`（**legacy 形态**）。两者都设，别名路径与真实路径就都覆盖到了。
  2. `TERMUX__PREFIX`/`TERMUX__HOME`/`TERMUX__ROOTFS` **不要尾斜杠**（官方 `TermuxShellEnvironment.java` 用 `TermuxConstants.TERMUX_PREFIX_DIR_PATH`，无尾斜杠；官方 shell 助手 `termux-exec-ld-preload-lib` 的校验是 `case "$TERMUX__PREFIX" in /*[!/]) ok;; *) 回退编译期值;; esac`，尾斜杠直接判非法）。
  3. 打开 `TERMUX_EXEC__LOG_LEVEL=5`（VVERBOSE），`logcat` 看 tag `ld-preload`：`system_linker_exec_enabled`、`app_data_file_exec_exempted`、`is_exe_under_termux_app_data_dir`、`system_linker_exec_enabled_for_file` 这几行会直接指出卡在哪一步。
  4. 在设备上跑 `$PREFIX/bin/termux-exec-system-linker-exec is-enabled`（Termux 自己的判定），并确认 `$PREFIX/lib/libtermux-exec-ld-preload.so` 存在且是 linker/direct 变体之一。
  5. 抄 login 的自检：`$SHELL -c "coreutils --coreutils-prog=true"`。
- **即使修好也别忘了**：preload 只解决 W^X 与 `/bin`、`/usr/bin`。bootstrap 里写死的 `/data/data/com.termux/...`（shebang、RUNPATH、apt/dpkg 路径）还是得靠已有的构建期/安装期改写。

### 1.5 关于 "`/system/bin` 下的 termux-exec shim"

**不存在这种东西**，也不需要：无 root 不可能往 `/system/bin` 写文件。termux-exec 装上的是
`$PREFIX/lib/libtermux-exec-{direct-,linker-,}ld-preload.so`（+ `libtermux-exec.so` 兼容符号链接）与 `$PREFIX/bin/termux-exec-*` 助手脚本（如 `termux-exec-system-linker-exec`、`termux-exec-ld-preload-lib`）。
看起来像 "shim" 的是 Android 自己的 `/bin -> /system/bin` 符号链接，termux-exec 的改写规则正是针对它。

---

## 2. Termux 设置的环境变量

### 2.1 谁设置什么（源码级）

| 变量 | 谁设置 | 值来源 | 备注 |
|---|---|---|---|
| `TERMUX_VERSION` | **App**（`TermuxAppShellEnvironment.ENV_TERMUX_VERSION`） | `PackageUtils.getVersionNameForPackage(Termux 包自己的 PackageInfo)`，即 APK 的 `versionName`（如 `0.119.0`） | **不是 termux-tools 写的**；`login.in` 只读它（playstore motd 提示 + 与 `0.119.0` 比较决定是否补 `TERMUX_MAIN_PACKAGE_FORMAT`）。v0.107 起才有 |
| `TERMUX_APP__VERSION_NAME` / `_VERSION_CODE` | App | `PackageInfo.versionName/versionCode` | ≥0.119 |
| `TERMUX_APP__PACKAGE_NAME` | App | `TermuxConstants.TERMUX_PACKAGE_NAME`（= applicationId） | |
| `TERMUX_APP__PID` | App | `TermuxUtils.getTermuxAppPID()` | 旧名 `TERMUX_APP_PID`（≤0.118） |
| `TERMUX_APP__UID` | App | `ApplicationInfo.uid` | |
| `TERMUX_APP__TARGET_SDK` | App | `ApplicationInfo.targetSdkVersion` | 第三方脚本用它判断 W^X 行为 |
| `TERMUX_APP__IS_DEBUGGABLE_BUILD` | App | `ApplicationInfo.FLAG_DEBUGGABLE` | 旧名 `TERMUX_IS_DEBUGGABLE_BUILD` |
| `TERMUX_APP__APK_RELEASE` | App | 签名证书 SHA-256 → `F-Droid`/`Github`/`Google Play Store`/`Termux Devs`，非 ASCII 转 `_` 并大写 | 旧名 `TERMUX_APK_RELEASE` |
| `TERMUX_APP__APK_PATH` / `_IS_INSTALLED_ON_EXTERNAL_STORAGE` | App | `ApplicationInfo` | |
| `TERMUX_APP__SE_PROCESS_CONTEXT` / `_SE_FILE_CONTEXT` / `_SE_INFO` | App | `SELinuxUtils` / `ApplicationInfo.seInfo` | termux-exec 的输入之一 |
| `TERMUX_APP__USER_ID` / `_PROFILE_OWNER` | App | 多用户 / 工作资料 | 注意**不是** `TERMUX__USER_ID` |
| `TERMUX_APP__PACKAGE_MANAGER` / `_PACKAGE_VARIANT` | App | `BuildConfig.TERMUX_PACKAGE_VARIANT` → `apt`/`pacman` | ≥0.119。`pkg`/`termux-setup-package-manager` 首选它 |
| `TERMUX_APP__FILES_DIR` | App | `Context.getFilesDir()` | 值就是 `/data/user/0/<pkg>/files` |
| `TERMUX_APP__AM_SOCKET_SERVER_ENABLED` | App | `termux-am-socket` 开关 | `termux-am` 用 |
| `TERMUX__PREFIX` / `TERMUX__HOME` / `TERMUX__ROOTFS` / `TERMUX__PROJECT_DIR` / `TERMUX__CORE_DIR` / `TERMUX__APPS_DIR` / `TERMUX__CACHE_DIR` | App | `TermuxConstants` 派生 | ≥0.119；**是 termux-exec 的输入** |
| `PREFIX` / `HOME` / `TMPDIR` / `PATH` / `LD_LIBRARY_PATH`(仅 Android<7) | App（`TermuxShellEnvironment.getEnvironment()`） | `TERMUX_PREFIX_DIR_PATH`、`TERMUX_HOME_DIR_PATH`、`TERMUX_TMP_PREFIX_DIR_PATH` | `PREFIX` 已被标记 deprecated（仍给） |
| `LANG=en_US.UTF-8` / `COLORTERM=truecolor` / `TERM=xterm-256color` | App（`AndroidShellEnvironment`） | 常量 | bionic 实际不认 locale（见 §4） |
| `LD_PRELOAD` | **`$PREFIX/bin/login`**（termux-tools） | `$PREFIX/lib/libtermux-exec-ld-preload.so`（或旧 `.so`），带自检 | App 不导出它 |
| `TERMUX_MAIN_PACKAGE_FORMAT` | `login`（仅当 App < 0.119 时补，值 `debian`/`pacman`） | 编译进 login 的 `@TERMUX_PACKAGE_FORMAT@` | 新代码用 `TERMUX_APP_PACKAGE_MANAGER` |
| `TERMUX_EXEC__PROC_SELF_EXE` | termux-exec（linker 模式） | 被执行的 ELF 真实路径 | `/proc/self/exe` 的补偿 |
| `SHELL_CMD__{RUNNER_NAME,PACKAGE_NAME,SHELL_ID,SHELL_NAME,APP_SHELL_NUMBER_*,TERMINAL_SESSION_NUMBER_*}` | App（每个 shell 会话） | `ExecutionCommand` | `termux-info` 等会读 |
| `SHELL` | `login`（`~/.termux/shell` 符号链接或默认 bash） | | |
| `TERMUX__USER_ID` | **不是 App 设的**（用户/脚本自设），`termux-info`、`termux-reload-settings`、`termux-bootstrap-second-stage.sh` 会清洗后使用（非数字/前导 0 → 0） | | |

源码：[TermuxAppShellEnvironment.java](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/shell/command/environment/TermuxAppShellEnvironment.java)、[TermuxShellEnvironment.java](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/shell/command/environment/TermuxShellEnvironment.java)、[AndroidShellEnvironment.java](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/shell/command/environment/AndroidShellEnvironment.java)、[termux-tools login.in](https://github.com/termux/termux-tools/blob/master/scripts/login.in)、[Termux-execution-environment wiki](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)。

### 2.2 `TERMUX_VERSION` 的具体写入点（用户问题的直接回答）

- 写入者：**termux-app**，类 `com.termux.shared.termux.shell.command.environment.TermuxAppShellEnvironment`，常量 `ENV_TERMUX_VERSION = "TERMUX_VERSION"`，值 = `PackageUtils.getVersionNameForPackage(packageInfo)`（Termux 包自己的 `versionName`）。
- termux-tools 只**消费**：`scripts/login.in`（判断是否 Play Store 老版本 → 打印 motd-playstore；与 `0.119.0` 比较决定是否补 `TERMUX_MAIN_PACKAGE_FORMAT`）、`scripts/termux-info.in`（`if [ -n "$TERMUX_VERSION" ]` 才打印 `TERMUX_VERSION` 段）、`termux-setup-package-manager`。
- **为空会踩的坑**：`dpkg --compare-versions "" lt 0.119.0` 报错退出非 0 ⇒ login 里那段"老 App 兼容"分支不会执行；某些第三方安装脚本用 `[ -n "$TERMUX_VERSION" ]` 判断"我在 Termux 里"。
- 自建 App 建议：直接给**语义化且 ≥0.119.0** 的值（例如对齐你自己的 `TERMUX_APP__VERSION_NAME`），别留空。

### 2.3 让第三方脚本正常工作的最小变量集合（落地清单）

必须：`HOME`、`PREFIX`、`TERMUX__PREFIX`、`TERMUX__HOME`、`TERMUX__ROOTFS`、`TMPDIR`、`PATH=$PREFIX/bin`、`TERMUX_VERSION`、`TERMUX_APP__VERSION_NAME`、`TERMUX_APP__VERSION_CODE`、`TERMUX_APP__PID`、`TERMUX_APP__UID`、`TERMUX_APP__TARGET_SDK`、`TERMUX_APP__PACKAGE_NAME`、`TERMUX_APP__DATA_DIR`、`TERMUX_APP__LEGACY_DATA_DIR`、`TERMUX_APP__FILES_DIR`、`TERMUX_APP__APK_RELEASE`、`TERMUX_APP__IS_DEBUGGABLE_BUILD`、`TERMUX_APP__PACKAGE_MANAGER`、`TERMUX_APP__SE_PROCESS_CONTEXT`、`TERMUX_APP__SE_FILE_CONTEXT`、`TERMUX_APP__USER_ID`、`COLORTERM`、`TERM`、`LANG`、`LD_PRELOAD`。
兼容旧脚本再加：`TERMUX_APP_PID`、`TERMUX_APK_RELEASE`、`TERMUX_IS_DEBUGGABLE_BUILD`、`TERMUX_MAIN_PACKAGE_FORMAT=debian`。
**实现雷区**：
- App 源码里 `TermuxConstants.TERMUX_PACKAGE_NAME` 若仍写 `com.termux`，`PackageUtils.getPackageInfoForPackage` 查不到自己的包 ⇒ `getEnvironment()` 返回 null ⇒ **整组 `TERMUX_APP__*` 一个都不导出**。fork 时必须把它改成自己的 applicationId（官方文档列的 fork 改动点之一）。
- 所有路径值**不要尾斜杠**（官方 app 的值都无尾斜杠）。
- `$PREFIX/etc/termux/termux.env`：App 在 bootstrap 装好后与 prefix 被 wipe 后都会重写（`TermuxShellEnvironment.writeEnvironmentToFile()`，先写 `termux.env.tmp` 再 rename）。自建 App 若希望 sshd 等"非 App 启动"的入口也能拿到变量，照做并在 sshd 启动脚本里 source 它。
- `$PREFIX/etc/termux/` 目录还可放：`mirrors/`（`termux-change-repo`、`pkg` 读）、`chosen_mirrors` 符号链接；`$PREFIX/etc/profile.d/init-termux-properties.sh`（termux-tools 提供，首启把 `$PREFIX/share/examples/termux/termux.properties` 复制到 `~/.termux/`）；`$PREFIX/etc/termux-login.sh`（被 `login` source）。

---

## 3. TMPDIR 与 `/data/local/tmp`

### 3.1 结论

- **`/data/local/tmp` 对普通 App 不可写**：DAC 是 `drwxrwx--x shell shell`（other 只有 `x`，没有 `w`/读目录），App 的 uid 既不是 `shell` 也不在 `shell` 组；SELinux 上是 `shell_data_file`。StackOverflow [Android: Permission denied for /data/local/tmp/*](https://stackoverflow.com/questions/23424602/android-permission-denied-for-data-local-tmp)（答案原文给了 `ls -ld` 输出与 "Once, this was possible. But on recent versions of Android, security has been tightened"）。adb push 进去的文件 `adb shell` 能跑（同属 shell 域/用户），**App 不能读也不能 exec**。
- **Termux 的 `/tmp` 就是 `$PREFIX/tmp`**：bootstrap 里 `mkdir -p $PREFIX/tmp`（`scripts/build-bootstraps.sh`），App 通过 `TMPDIR=$PREFIX/tmp` 导出（`TermuxShellEnvironment`：只有非 failsafe 模式才覆盖，failsafe 模式保留 Android 默认 `/data/local/tmp`，因为那个 session 只用 `/system/bin` 的系统二进制），退出时清空（`TermuxShellUtils.clearTermuxTMPDIR()`，可配置保留天数/不清理）。wiki 对 `$TERMUX__PREFIX/tmp` 的描述是 "Temporary files. Erased on each application restart. Combines /tmp and /var/tmp"。
- Android **没有** `/tmp`、`/var/tmp`、`/run`、`/dev/shm`；daemon 的 PID/socket 按 Termux 约定放 `$PREFIX/var/run`。
- `/data/local/tmp` 的"官方替代路径"在 Android 上并不存在：语义上它属于 `shell` 用户（adb/调试用）。对内嵌运行时，正确替代就是 **`$PREFIX/tmp`**；App 层代码用 `context.getCacheDir()`/`context.getFilesDir()`。
- 常见"写 `/data/local/tmp`"的软件：pip/setuptools 的构建临时目录、cargo/go 的部分临时目录、NDK/clang 某些 wrapper、用户从文档抄来的 `/data/local/tmp/xxx` 路径、Python 的 `multiprocessing`/`subprocess` 若显式写死。**没有官方 remap 机制**，要么改 TMPDIR（这些程序多数会读 `TMPDIR`），要么用本仓库已有的 `ADSH_REDIRECT=/tmp=...;/data/local/tmp=...` shim（路径重定向，不改权限——这是对的做法），要么改脚本。

### 3.2 落地做法

1. `TMPDIR=$PREFIX/tmp`，启动时 `mkdir -p` 并 `chmod 0700`；确保它**是目录不是符号链接**（Termux 的 `clearTermuxTMPDIR` 注释里特意提到这点）。
2. 不要把 `/data/local/tmp` 加进 `PATH`/`TMPDIR`。
3. 需要 `/tmp` 字面量的程序：用 shim 重定向（本仓库已做），不要试图 `ln -s /tmp`（无权限）。
4. 需要 SYSV 共享内存的（PostgreSQL 等）：Android 内核不支持，用 `libandroid-shmem`（或 `postgres` 的 `dynamic_shared_memory_type=mmap`）。

---

## 4. 其它内嵌常见问题清单（逐项：根因 → 正确修法）

| # | 问题 | 根因 | 正确修法 | 来源 |
|---|---|---|---|---|
| 1 | dpkg/apt 的 admin dir 与 cache 路径 | bootstrap 已预建 `$PREFIX/var/lib/dpkg/{info,triggers,updates}`+`status`/`available`、`$PREFIX/etc/apt/apt.conf.d`、`preferences.d`、`$PREFIX/var/log/apt`、`$PREFIX/tmp` | 保持这些目录存在；用 `DPKG_ADMINDIR=$PREFIX/var/lib/dpkg`；`DPKG_ROOT=""`（Termux second-stage 的做法：Android 非 root 不能 chroot，所以只 `cd /`，不设 `DPKG_ROOT`） | `scripts/build-bootstraps.sh`、`scripts/bootstrap/termux-bootstrap-second-stage.sh` |
| 2 | apt cache 路径 | apt 编译参数 `-DCACHE_DIR=$TERMUX_CACHE_DIR/apt` ⇒ `/data/data/com.termux/cache/apt`（**app cache 目录会被 Android 在低存储时整体删除**），而 `pkg clean` 清的是 `$TERMUX__CACHE_DIR/apt/archives` | 用 `$PREFIX/etc/apt/apt.conf.d/00-adsh-dirs` 覆盖 `Dir::Cache`（本仓库已做）；同时把 `Dir::State`/`Dir::State::lists`/`Dir::State::status`/`Dir::Etc::sourcelist`/`Dir::Etc::trusted` 一起 `apt-config dump` 校验一遍（apt 是 `-DCMAKE_INSTALL_FULL_LOCALSTATEDIR=$TERMUX_PREFIX` 构建的，默认值都是 `$PREFIX/var/lib/apt`） | `packages/apt/build.sh`、`scripts/pkg.in`、[Termux App Cache Directory wiki](https://github.com/termux/termux-packages/wiki/Termux-file-system-layout) |
| 3 | apt 的 `_apt` 用户 / 权限 | Debian 上 apt 以 root 运行时会降权到 `_apt`；Android 没有这个用户。**Termux 的 apt 永远不会以 root 运行**（补丁：`apt`/`apt-get`/`apt-mark` 在 `getuid()==0` 时直接打印 "Ability to run this command as root has been disabled permanently for safety purposes." 并返回 1；`apt-key` 同理禁 root） | 非 root 运行即可，无需创建 `_apt`；若你的 rootfs 里出现 `No sandbox user '_apt' on the system, can not drop privileges`，在 `apt.conf.d` 里设 `APT::Sandbox::User "root";`（=不降权）或不要以 root 运行 apt | `packages/apt/0010-prevent-usage-as-root.patch`、`0007-aptkey-no-root.patch`、[Termux Home Directory wiki](https://github.com/termux/termux-packages/wiki/Termux-file-system-layout) |
| 4 | apt 默认保留 deb | Termux 补丁把 `Binary::apt::APT::Keep-Downloaded-Packages` 从 `false` 改成 `true` | 自建 rootfs 若用官方 deb，缓存会留在 `$TERMUX__CACHE_DIR/apt`；不想留就自己设 `false` 或定期 `pkg autoclean` | `packages/apt/0011-keep-downloaded-packages.patch` |
| 5 | locale | bionic 没有完整 locale/gettext：`nl_langinfo(CODESET)`、`std::locale("")`、`setlocale` 基本是空实现；Termux 的 apt 补丁直接把 `setlocale`/`nl_langinfo`/`UTF8ToCodeset` 用 `#ifndef __ANDROID__` 去掉，并把 `[y/N]` 判定从 `nl_langinfo(YESEXPR)` 换成 `^[yY]` | 不要依赖 `LANG`/`LC_ALL` 改变行为（`LC_ALL=C` 这类只对自带 locale 处理的程序有意义）；需要 gettext/iconv 的程序依赖 `libandroid-support`；UTF-8 本身可用。App 侧照 Termux 给 `LANG=en_US.UTF-8` 即可（不要指望被遵守） | `packages/apt/0002-no-locales.patch`、[Common-porting-problems wiki](https://github.com/termux/termux-packages/wiki/Common-porting-problems)、`AndroidShellEnvironment.java` |
| 6 | `/etc/resolv.conf` 与 DNS | Android 的 bionic `getaddrinfo` 走 netd（系统代理），**正常程序解析没问题**；`/etc/resolv.conf` 在 Android 上不存在。自带 resolver 的程序（Go、musl/静态二进制、c-ares 类）会去读它 | Termux 用独立包 **`resolv-conf`** 提供：`$PREFIX/etc/hosts`（`127.0.0.1 localhost` / `::1 ip6-localhost`）与 `$PREFIX/etc/resolv.conf`（`nameserver 8.8.8.8` / `8.8.4.4`，这就是 [termux-app#2020 "hardcoded Google resolvers"](https://github.com/termux/termux-app/issues/2020) 的来源）。Go 被 patch 成读 `$PREFIX/etc/resolv.conf`（[PR#9721](https://github.com/termux/termux-packages/pull/9721)，补丁 `packages/golang/patch-script/fix-hardcoded-etc-resolv-conf.diff`）。自建 App：保证这两个文件存在且内容合理（可写真实 DNS 或用 `getprop net.dns1` 生成），对写死 `/etc` 的二进制用路径重定向 shim | `packages/resolv-conf/build.sh`、`packages/golang/patch-script/fix-hardcoded-etc-resolv-conf.diff` |
| 7 | `/etc/hosts` | bionic 的 `getaddrinfo` 读 Android 的 `/etc/hosts`，不读 `$PREFIX/etc/hosts` | 已被提出：[termux-packages#10277](https://github.com/termux/termux-packages/issues/10277)；短期内靠 `resolv-conf` 的文件 + 重定向/自带 resolver 的程序 | 同 #6 |
| 8 | shebang 为什么要 termux-exec / termux-fix-shebang | `#!/bin/sh`：Android ≥9 会落到 `/system/bin/sh`（toybox，行为不同），≤8.1 直接 `No such file`；`#!/usr/bin/env python` 同理 | 运行时：termux-exec 改写（覆盖所有 exec，含运行时生成的脚本）；离线：`termux-fix-shebang <files>`，实现就是一行 sed：`sed -i -E "1 s@^#\!(.*)/[sx]?bin/(.*)@#\!$TERMUX_PREFIX/bin/\2@"`，**每次安装/升级脚本后都要重跑**。App 侧（Kotlin 进程没有 preload）还要像 `TermuxShellUtils.setupShellCommandArguments()` 那样自己解析 shebang/无 shebang 脚本并用 `$PREFIX/bin/sh` 执行 | `termux-tools/scripts/termux-fix-shebang.in`、`TermuxShellUtils.java`、termux-exec technical docs |
| 9 | `/dev/null`、`/proc`、`/dev/shm` | App mount namespace 里有 `/dev/null`、`/dev/zero`、`/dev/random`、`/dev/urandom`、`/dev/ptmx`、`/dev/tty`（`/dev/pts` 由 ptmx 动态分配）；**没有** `/dev/shm`（SYSV/POSIX shm）、`/dev/fd`、`/tmp`、`/run`。`/proc` 通常 `hidepid=2`，`/proc/net` 自 Android 10 起受限 | 用 `/proc/self/fd` 代替 `/dev/fd`；PID/socket 放 `$PREFIX/var/run`；SYSV shm 用 `libandroid-shmem`，POSIX 命名信号量用 `libandroid-posix-semaphore`；不要 `ls /proc/net`（会 Permission denied，属预期） | [Termux-file-system-layout wiki](https://github.com/termux/termux-packages/wiki/Termux-file-system-layout)、[Common-porting-problems wiki](https://github.com/termux/termux-packages/wiki/Common-porting-problems) |
| 10 | SELinux/seccomp 层的系统调用限制 | Android 8+ seccomp 拦部分 syscall（`Bad system call` 崩溃）；Android 9+ 拦 `setuid` 相关；SELinux 拒 `tcsetattr(TCSAFLUSH)`；无 `chroot`（非 root）；无 SYSV sem/shm；bionic 缺 `glob.h`、`<sys/termios.h>`、`rindex`、`<sys/fcntl.h>` 等 | 用 `TCSANOW`；`libandroid-glob`；不要 `chroot`（Termux second-stage 明确用 `cd /` 代替）；需要降权的程序在 Termux 里本来就不支持 | [Common-porting-problems wiki](https://github.com/termux/termux-packages/wiki/Common-porting-problems) |
| 11 | `getpwuid` 失败 / 没有 `/etc/passwd` | Android 没有 `/etc/passwd` 数据库（bionic 只为少数系统 uid 合成条目），Termux 也没有 `$PREFIX/etc/passwd` | 依赖 passwd 数据库的程序必须 patch：termux-packages 的 NDK 补丁 `ndk-patches/pwd.h.patch` 给 `getpwuid` 系列做了兜底（把 shell 指到 `$PREFIX/bin/login`）。第三方软件（如 Java/.NET 的 user lookup）会直接失败——要么 patch，要么用能 patch 的替代 | `ndk-patches/pwd.h.patch`（termux-packages） |
| 12 | `HOME` 权限 | HOME 必须是 app uid 拥有、0700；Termux 的 `TermuxInstaller` 会先校验 files dir 可访问再解压 | 自建 App 建目录时统一 `0700`；不要在 `$PREFIX` 下创建 root/other 拥有的文件（`root` 操作过一次就可能毁掉整个环境，wiki 明确警告） | `TermuxInstaller.java`、`TermuxFileUtils.isTermuxFilesDirectoryAccessible()`、wiki |
| 13 | 时间与证书 | Android 自己管时间（App 无 NTP）；系统 CA 在 `/system/etc/security/cacerts`（每个证书一个文件，**不是** PEM bundle） | Termux 用 `ca-certificates` 包提供 `$PREFIX/etc/tls/cert.pem`（openssl 构建时 `--openssldir=$TERMUX_PREFIX/etc/tls`，另装 `bin/add-trusted-certificate`）；静态 Go/Node 的默认 CA 路径找不到 Android 的目录，需要 patch（Go 补丁把 `/etc/ssl/certs/ca-certificates.crt` 等指到 `$PREFIX`）或用重定向 shim | `packages/ca-certificates/build.sh`、`packages/openssl/build.sh`、`packages/openssl/add-trusted-certificate` |
| 14 | `install-packages` | Termux **没有**这个命令 | 等价物是 `$PREFIX/bin/pkg`（`termux-tools/scripts/pkg.in`）：内部 `source $PREFIX/bin/termux-setup-package-manager` 决定 `apt`/`pacman`（优先 `TERMUX_APP_PACKAGE_MANAGER`，退化到 `TERMUX_MAIN_PACKAGE_FORMAT`，再退化到编译期值），cache 目录 `$TERMUX__CACHE_DIR/apt/archives` | `scripts/pkg.in`、`scripts/termux-setup-package-manager.in` |
| 15 | 从 App（Kotlin/JVM）exec | `Runtime.exec()` 走 bionic `execvpe`，**App 进程没有 LD_PRELOAD**，targetSdk≥29 时 exec app 私有目录必失败 | (a) targetSdk ≤28（Termux 路线）；(b) 首跳 `/system/bin/sh -c 'LD_PRELOAD=... exec <real>'` 或 `/system/bin/linker64 <real>`；(c) ELF 走 nativeLibraryDir（本仓库路线）。三者都要配合 Java 侧自己的 shebang 处理 | `TermuxShellUtils.java`、`gradle.properties`(`targetSdkVersion=28`)、`packages/apt/0010-*`（同一限制的另一面） |

---

## 5. 对本仓库当前实现最相关的 8 条（可直接改）

1. **`TERMUX_APP__LEGACY_DATA_DIR` 改成 `/data/data/<pkg>`**（现在与 DATA_DIR 同值，导致等长别名 `/data/data/<pkg>/u/...` 不被 termux-exec 认作"app 数据目录"）。这是 PoC-3 "preload 加载了但不改写 exec" 的最可能病根。
2. `TERMUX__PREFIX`/`TERMUX__HOME` **去掉尾斜杠**（官方实现无尾斜杠；shell 侧助手对尾斜杠直接判非法并回退到编译期前缀）。
3. 加 `TERMUX_EXEC__LOG_LEVEL=5` 跑一次，logcat 过滤 `ld-preload`：看 `is_exe_under_termux_app_data_dir` 与 `system_linker_exec_enabled_for_file`；再用 `$PREFIX/bin/termux-exec-system-linker-exec is-enabled` 交叉验证。
4. 抄 login 的 preload 自检（`coreutils --coreutils-prog=true`），失败就 unset，避免"以为有 preload 其实没有"。
5. 补齐 `TERMUX_VERSION`（≥0.119.0，别留空）、`TERMUX_APP_PACKAGE_MANAGER=apt`（兼容旧脚本再加 `TERMUX_MAIN_PACKAGE_FORMAT=debian`）、`TERMUX_APP__VERSION_NAME/CODE/TARGET_SDK/UID/SE_PROCESS_CONTEXT/SE_FILE_CONTEXT/FILES_DIR`；旧名别名 `TERMUX_APP_PID`/`TERMUX_APK_RELEASE`/`TERMUX_IS_DEBUGGABLE_BUILD` 按需给。
6. `$PREFIX/etc/termux/termux.env` 在"bootstrap 重装/prefix 被清"后重写一次（对齐官方时机）。
7. DNS/证书：确认 `$PREFIX/etc/resolv.conf`、`$PREFIX/etc/hosts` 存在（来自 `resolv-conf` 包，内容可替换成非 8.8.8.8）；`$PREFIX/etc/tls/cert.pem` 供 openssl 类程序。
8. apt/dpkg 的 `Dir::*` 与 `DPKG_ADMINDIR` 用 `apt-config dump` + `dpkg --print-architecture` 实测校验一遍（尤其 `Dir::Cache`、`Dir::State::lists`、`Dir::State::status`）。

---

## 6. 主要来源

- termux-exec 技术文档（exec 拦截 / W^X / system_linker_exec / /bin 改写）：https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/technical/index.md
- termux-exec 使用文档（输入/输出/处理的环境变量、长度上限、默认值）：https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/usage/index.md
- Android-Docs：App Data File Execute Restrictions（neverallow 原文、nativeLibraryDir、system_linker_exec、Play 政策）：https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md
- Termux wiki：Termux Execution Environment（环境变量全集、PATH/LD_LIBRARY_PATH/LD_PRELOAD、W^X）：https://github.com/termux/termux-packages/wiki/Termux-execution-environment
- Termux wiki：Termux Filesystem Layout（路径、prefix、tmp、cache、路径长度限制、shebang 长度）：https://github.com/termux/termux-packages/wiki/Termux-file-system-layout
- Termux wiki：Common porting problems（seccomp/SELinux/shm/locale 相关）：https://github.com/termux/termux-packages/wiki/Common-porting-problems
- Termux wiki：For-maintainers（Bootstraps 一节：libtermux-bootstrap.so / useLegacyPackaging / generate-bootstraps）：https://github.com/termux/termux-packages/wiki/For-maintainers
- 源码：TermuxAppShellEnvironment.java、TermuxShellEnvironment.java、AndroidShellEnvironment.java、TermuxShellUtils.java、TermuxInstaller.java、TermuxConstants.java（termux-app master）
- 源码：scripts/properties.sh、scripts/build-bootstraps.sh、scripts/generate-bootstraps.sh、scripts/bootstrap/termux-bootstrap-second-stage.sh、packages/apt/*.patch、packages/resolv-conf/build.sh、packages/ca-certificates/build.sh、packages/openssl/build.sh、ndk-patches/pwd.h.patch（termux-packages master）
- 脚本：termux-tools/scripts/{login,termux-fix-shebang,pkg,termux-setup-package-manager,termux-info,termux-change-repo}.in、Makefile.am
- 源码：termux-exec-package/lib/termux-exec_nos_c/.../TermuxExecLDPreload.c（shouldEnableSystemLinkerExecForFile）、termux-core-package TermuxShellEnvironment.h / TermuxFile.h（环境变量名与长度上限）
- StackOverflow：/data/local/tmp 权限（drwxrwx--x shell shell）：https://stackoverflow.com/questions/23424602/android-permission-denied-for-data-local-tmp
- termux-app gradle.properties（targetSdkVersion=28）、app/build.gradle（packagingOptions { jniLibs { useLegacyPackaging true } }）、app/src/main/cpp/termux-bootstrap-zip.S / termux-bootstrap.c
