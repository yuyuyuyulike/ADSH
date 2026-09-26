# HANDOFF（给下一个会话）

## 这是什么

ADSH：把 deepseek harness（dsh）的 PTC 语义（模型写一段程序组合调用工具）用 Kotlin + Compose 原生重写成
安卓 App —— 无 Node 运行时、无插件、无运行期下载。仓库 <https://github.com/yuyuyuyulike/ADSH>（public / MIT）。

- 功能说明看 [README.md](README.md)；**改代码前先看 [docs/NOTES.md](docs/NOTES.md)**（平台约束、
  数据不变量、提示词纪律、界面不变量、偏离 dsh 的清单都在那里）。
- 工作区：`D:\WSN2005\Android1\App\ADSH`。

## 当前状态（2026-09-26，0.1.4）

- **版本 0.1.4 / versionCode 6**，已装机（`install -r`，`firstInstallTime` 仍是 2026-09-24 21:37:30 =
  数据没动），启动无 FATAL。产物 `dist/ADSH-0.1.4-release.apk`：**44,474,615 字节**，
  sha256 `3F3E3EF155431860B2245BDF9B783C8C6BD76AEB5C2CC0D4EF518E5D305DD90B`。
- 单测 **195 全过**（`:app:testDebugUnitTest`）；死代码五个扫描全 0。
- **快照**：第十二次快照（orphan 单提交 + 清旧历史 + 远端 force push），tag `v0.1.4-20260926`，
  GitHub 上只有这一个 release；远端只有 `main` + 这个 tag。
- **工作区**：临时文件与构建中间产物已清（`app/build/`、`build/`、`.gradle/`、`.kotlin/`、`app/.cxx/`、
  `.tmp/`）；`dist/` 里只留最新那一份 APK（APK 不入库，走 GitHub Release 资产）。

## 构建与发版

- 命令（`wsl.exe` 在本机不可用，**别用 `build-debug.sh`**）：
  `cmd.exe /c "cd /d D:\WSN2005\Android1\App\ADSH && scripts\build-debug.bat :app:testDebugUnitTest :app:assembleRelease"`
  （JDK 17 = `D:\WSN2005\Android\jbr`，脚本自己会设 `JAVA_HOME`）。
- release 必须 `isMinifyEnabled = true` + `isShrinkResources = true`（build.gradle.kts 已开）。
  debug / release 同包名同一把签名，`install -r` 可互相覆盖且不动数据；**versionCode 只往前加**。
- 装机：`adb install -r dist/ADSH-0.1.4-release.apk`（adb = `D:\WSN2005\Android1\platform-tools\adb.exe`，
  设备 `FQJZF6U4TCVC7DB6`）。装完 `adb shell dumpsys package com.termux | findstr version` 核对版本、
  `adb logcat -b crash` 应无输出。
- **打快照 + 发 Release 的流程**（第 78 / 82 轮各做过一次）：
  1. `:app:testDebugUnitTest :app:assembleRelease` → 把 APK 覆盖成 `dist/ADSH-<版本>-release.apk`；
  2. 文档/版本号定稿后打快照：`git checkout --orphan tmp` → `git add -A` → 单提交 →
     `git branch -M main` → `git reflog expire --expire=now --all` → `git gc --prune=now`
     （历史上只留这一个提交，旧记录随之消失）；
  3. 推送：`git push git@github.com:yuyuyuyulike/ADSH.git main --force`（HTTPS 在本机不通，SSH 正常）；
  4. `gh release create v<版本>-<日期> dist/ADSH-<版本>-release.apk --title … --notes …`；
     旧的用 `gh release delete <tag> --cleanup-tag` 清掉；发完核对资产大小与下载 HEAD 200；
  5. README 的下载链接指向新 tag。

## 不要破的硬规矩（详细理由见 docs/NOTES.md）

- `targetSdk = 28`、包名 `com.termux` —— 内嵌 Termux 能直接 exec 的前提。
- release 的 R8 两个开关都保持 true；`lint` 的 `ExpiredTargetSdkVersion` 是关掉的，别"修"。
- 写围栏 / `/tmp` 映射 / 脚本 shebang 自愈都在 LD_PRELOAD shim 里；read-only **也要挂围栏**
  （白名单可以为空，哨兵缺失时失败关闭）。改 `fence.c` 后跑 `scripts/fence-selftest.sh`。
- 提示词不许重复（`PromptAssemblerTest` 盯着）；工具说明只在 `ToolSdk.specs` 写一次；
  `PTC_ONLY` 两句都要在；只有 `run_code` 能被模型直接调用。
- 会话里**一行消息不能超过 ~2 MB**（`CursorWindow`），读会话一律走 `readMessages`；
  数据库迁移**不能断链**（断了会掉进 `fallbackToDestructiveMigration` 清库）。
- 界面：贴底靠 `SideEffect` + `requestScrollToItem` + 隔帧核对；流式节流在 `ChatScreen` 这一层且
  「变短/换段立刻跟上」；展开/收起会让自动滚动停跟随；覆盖页是叠层，别用 `AnimatedContent`。
- 内嵌环境出怪事跑 `adsh-env-check`（**别用 `run-as` 测 exec**）；渲染/截图走 `adsh-shot`；
  改 `$PREFIX/bin` 下的脚本后用 `python3 scripts/check-env-scripts.py` 过一遍（前缀的 `/bin/sh` 是 dash）。
- 改动 dsh 侧行为时报据从 GitHub 读：`gh api repos/deepseek-ai/deepseek-harness/contents/<path> -H "Accept: application/vnd.github.raw"`。

## 构建输入（不要删）

- `app/src/main/assets/bootstrap/usr.zip`（32 MB，Termux 前缀，运行期解压）
- `app/src/main/execLibs/`（随包分发的可执行文件 + `bin/` 下 79 个脚本）与 `assets-src/`
  —— **必须原样复制**（它们从 nativeLibraryDir 执行，改文本会把前缀改成不存在的路径）
- `local.properties`、`keystore.properties`、`keystore/adsh-side.jks`、`gradle/`（wrapper）

## 待办

- **终端还没有 Ctrl / 方向键**：终端页是转录式面板（输入行按行送进 PTY），Ctrl-C、Tab 补全、上下键
  翻历史都用不了。要补就往 master fd 写控制字节（`\x03` 等，行规程会变成 SIGINT）；等用户拍板。
- **截 ADSH 自己界面**仍未做：网页渲染截图有 `adsh-shot` 了，但把 App 自己的窗口画成位图（无需授权）
  或 `MediaProjection`（每次要授权）都还没定；等用户拍板。
- 终端「回车后整页变大」按用户要求**不再修**（要修的下一条路是 `adjustNothing` + 手动 insets，
  别去调字号 / padding）。
- 《内嵌 Termux 环境审查报告》剩下的 P1-4 / P1-5（`login` 的 `LD_PRELOAD`、shim 收敛成只做策略）
  没做；P2 的静态二进制检测、Play 上架不打算做。
- pi-ai 的 `openai-responses` / `anthropic-messages` 两个协议没实现，供应方目录里只用得到
  `openai-completions` 的那些。
