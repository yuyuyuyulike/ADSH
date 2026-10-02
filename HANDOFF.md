# HANDOFF（给下一个会话）

## 这是什么

ADSH：把 deepseek harness（dsh）的 PTC 语义（模型写一段程序组合调用工具）用 Kotlin + Compose 原生重写
成安卓 App —— 无 Node 运行时、无插件、无运行期下载。仓库 <https://github.com/yuyuyuyulike/ADSH>（public / MIT）。

- 功能说明看 [README.md](README.md)；**改代码前先看本文件末尾的『不要破的硬规矩』**（平台约束、数据不变量、
  提示词纪律、界面不变量都在那一节），想知道一路是怎么走到今天的看上面那节阶段总结。
- 工作区：`D:\WSN2005\Android1\App\ADSH`。dsh 的参考源码在 `D:\tmpdsh-work`（**不要删**：里面的
  `spec-ui.md` / `spec-log.md` 是展示层的规格依据），解包产物与 `asar-tool.js` 在 `D:\tmp`。

## 当前状态（2026-10-02，0.1.9 / versionCode 11）

- **仓库**：`main` 是**单条 orphan 提交**（第 104 轮起把历史压平，之后 107 / 121 / 124 轮又各压过一次），
  远端只有这一个提交 + 一个 tag（tag 与 `main` 指的是同一个提交）。
- **当前发布**：tag `v0.1.9-20261002`、资产 `ADSH-0.1.9-release.apk`（R8 minify + shrinkResources，
  含 baseline profile）。README 的下载链接指向它；**GitHub 上只保留这一个 release 与 tag**。
  用户在网页上改过 README（`c3f39b6`，删掉了 `adsh-env-check` 那一行）—— 下次压平前先把工作区同步成
  他那一版，否则 orphan 提交会把他的修改顶掉。
- **发布物**：`dist/ADSH-0.1.9-release.apk`（versionName `0.1.9` / code 11）。它与 debug 包同包名、
  同一把签名，`install -r` 可互相覆盖且不动数据；**设备上现在装的就是这一份 release 包**。
  release 包不可调试（`run-as` 会被拒）—— 要拉库或进沙箱就先装 `app/build/outputs/apk/debug/app-debug.apk`。
- **验证基线**：单测 **368 全过**（截至第 116 轮）。第 117 轮起按用户要求**不再跑测试**，只保证编译通过、
  装机启动无 FATAL；这一轮起如果删了测试侧的代码或改了断言，至少把 `:app:compileDebugUnitTestKotlin` 跑过。

## 一百多轮做了什么（阶段总结）

**① 第 1～40 轮 · 把 dsh 的界面照进 Compose。** 单 Activity + Compose 起骨架：会话流（工具行 / 子调用 /
详情卡）、输入框与各种弹层、上下文占用、Markdown 与 LaTeX、附件与图片通道、会话抽屉与搜索、设置页与模型
目录，逐项对齐 dsh 的视觉规范。工具行「闪一下跳位」这类回归从这时候就开始反复出现。

**② 第 41～63 轮 · 内嵌 Termux 与执行层（最硬的一段）。** 官方 Termux 用户态静态入包（bootstrap 32 MB，
运行期解压到官方前缀）；定下**方案 A：包名就是 `com.termux`、targetSdk 钉 28** —— 这是「直接 exec 自己数据
目录里的文件」的唯一前提；写 LD_PRELOAD 的**写围栏 shim**（可写根白名单、`/tmp` 映射、脚本 shebang 自愈）
与审批卡；出 0.1.1，并定下「orphan 单提交快照 + GitHub Release + 清旧包」这套发版流程。

**③ 第 64～90 轮 · 提示词与工具语义逐字对 dsh。** 系统提示词按 dsh 现文订正并最终**纯静态化**（模型名 /
工作区 / 策略改走运行时注入的 `context` 快照，决策在 `ContextLedger`）；工具说明只在 `ToolSdk.specs` 写一次、
只有 `run_code` 能被模型直接调用；网页搜索 / 抓取、`todo_write` / `present` 的卡片按 dsh 的展示规格重做。

**④ 第 91～108 轮 · 真机实测驱动的深水区。** 探针把执行层的老问题一个个钉死（退出码 / stdin / 硬链接 /
chmod / `[fs:]` 提示）；**后台任务（dsh 的 `ctx.jobs` 整条链）**；注入行（插话、任务通知）改成 dsh 的
**收件箱认领**（到达只入队、认领才落库，位置一次定死）；自动滚动反复收敛过好几轮；PTC 展示重写开工。

**⑤ 第 109～124 轮 · 收尾与根治。** PTC 展示重写落地（一步定稿进日志、诊断格子）；`ask_user_question`
卡片与提示词按真机实测收敛；**QuickJS 栈预算 2 MB → 256 KB**（原来递归会把整个 App 打成进程级 SIGSEGV）；
后台任务弹层排版与结算通知合批；**自动滚动整套重写成 dsh 的口径**（跟随意图 + 只在内容 / 视口变化时贴底 +
兜底补钉的 `movedByReader` 判据）；终端的四个显示根因（Material3 默认字距漏进全 App 的 `Text`、输入行与
转录区差 5px 首行基线、回车残影、键盘弹起贴底不跟）；最后一轮换了判据清死代码并出 **0.1.9**。

一句话：**界面与提示词对齐 dsh，执行层靠内嵌 Termux + 写围栏顶住平台限制，展示与自动滚动按 dsh 的事件语义
重写；每一轮踩到的坑都沉淀成了本文件末尾那张硬规矩。**

## 构建与发版

- 命令：**从 bash 里直接跑 gradle wrapper**（第 100 轮实测：`cmd.exe /c` 会被当成交互式 cmd、
  一个任务都不跑）：
  `JAVA_HOME='D:\WSN2005\Android\jbr' PATH="/d/WSN2005/Android/jbr/bin:$PATH" ./gradlew.bat --offline <tasks>`
  （JDK 17 = `D:\WSN2005\Android\jbr`；`scripts/build-debug.bat` 是给 Windows 侧用的同一套设置）。
- release 必须 `isMinifyEnabled = true` + `isShrinkResources = true`（build.gradle.kts 已开）。
  debug / release 同包名同一把签名，`install -r` 可互相覆盖且不动数据；**versionCode 只往前加**。
- 装机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（debug）或
  `adb install -r dist/ADSH-0.1.9-release.apk`（release）—— adb = `D:\WSN2005\Android1\platform-tools\adb.exe`，
  设备 `FQJZF6U4TCVC7DB6`。装完 `adb shell dumpsys package com.termux | findstr version` 核对版本、
  `adb logcat -b crash` 应无输出。
- **打快照 + 发 Release 的流程**（第 78 / 82 / 94 / 97 / 102 / 104 轮各做过；第 100 轮用户点名「旧的暂时不动」，
  所以那一次是**普通提交 + 快照 tag**，第 102 轮又按用户要求**清掉全部旧快照**做了 orphan 压平）：
  **只想留个本地快照（不上传、不发版）时别走这套** —— 直接 `git add -A && git commit`（旧快照留在
  历史里当父提交，远端一动不动）就够了；下面这套是「要发版」时才用的：
  1. `:app:assembleRelease`（需要时再加 `:app:compileDebugUnitTestKotlin`；用户点名不跑测试） → 把 APK 覆盖成 `dist/ADSH-<版本>-release.apk`，
     再 `scripts/verify-apk.bat dist/ADSH-<版本>-release.apk` 看 badging（versionCode / versionName /
     launchable-activity）与签名，最后 `adb install -r` 装机核对；
  2. 文档/版本号定稿后打快照：`git checkout --orphan tmp` → `git add -A` → 单提交 →
     `git branch -M main` → `git reflog expire --expire=now --all` → `git gc --prune=now`
     （历史上只留这一个提交，旧记录随之消失）；新 tag 打在这个提交上
     （`git tag -f v<版本>-<日期>`）、旧 tag 删掉；推的时候 `main` 与 tag 都要 `--force`；
  3. 推送：**走 HTTPS + gh 的凭据助手**（第 97 轮实测：SSH 这边是坏的 —— `$HOME` 里是非 ASCII 路径，
     `git@github.com` 直接 `Permission denied (publickey)`）。**第 100 轮实测本机 DNS 给出的
     `github.com`（20.205.243.166）连不上**（`Failed to connect … after 21s`），而 `api.github.com`、
     `codeload`、`uploads` 都正常 —— 别的 GitHub IP 是通的，所以 push 时直接钉一个能连的 IP：
     `git -c http.curloptResolve=github.com:443:140.82.121.4 -c credential.https://github.com.helper='!gh auth git-credential' push https://github.com/yuyuyuyulike/ADSH.git main`
     （试过可用的：`140.82.121.4` / `140.82.112.4` / `140.82.113.4` / `20.27.177.113`；
     `api.github.com` 一定要走正常 DNS，别把全局的 resolve 钉死）。`/usr/bin/gh … No such file`
     那行 stderr 是无害的；同一个 IP 会时好时坏（第 124 轮：`140.82.121.4` 先通后死、
     `140.82.113.4` 稳定），不通就换一个重试；
  4. `gh release create v<版本>-<日期> dist/ADSH-<版本>-release.apk --title … --notes …`
     （`gh` 走 api/uploads，不受上面那个 IP 问题影响）；
     旧的用 `gh release delete <tag> --cleanup-tag --yes` 清掉（它连本地 tag 一起删）；
     发完核对资产大小与 digest；
  5. README 的下载链接指向新 tag；
  6. 收尾清工作区：`app/build/`、`build/`、`app/.cxx/`、`.kotlin/`、`scripts/__pycache__/`（构建依赖在 `GRADLE_USER_HOME` =
     `D:\WSN2005\Android1\.gradle`，**不在**工作区里，别误删；`dist/` 只留最新那份 APK）。

## 不要破的硬规矩（改代码前逐条看）

- `targetSdk = 28`、包名 `com.termux` —— 内嵌 Termux 能直接 exec 的前提。
- release 的 R8 两个开关都保持 true；`lint` 的 `ExpiredTargetSdkVersion` 是关掉的，别"修"。
- 写围栏 / `/tmp` 映射 / 脚本 shebang 自愈都在 LD_PRELOAD shim 里；read-only **也要挂围栏**
  （白名单可以为空，哨兵缺失时失败关闭）。改 `fence.c` 后跑 `scripts/fence-selftest.sh`。
- 提示词不许重复（`PromptAssemblerTest` 盯着）；工具说明只在 `ToolSdk.specs` 写一次；
  `PTC_ONLY` 两句都要在；只有 `run_code` 能被模型直接调用。
- **系统提示词必须是静态的**（第 84 轮）：`PromptAssembler.STATIC_SYSTEM_PROMPT` 里不许出现
  模型名 / 工作区路径 / 策略 / 计划；`ToolSdk.section()` 与 `WORKING_RULES` 保持无参数。
  剩下的动态只允许两块外来文本（工作区指令文件链、用户自定义后缀）。
- **两条动态注入**（第 83 轮）：`sandbox:policy` 与 `env:android-termux`（含工作区事实与计划模式）
  走 `context` 行、内容一变**追加**新快照；`context` 行 `form = snapshot` 时**要作为 user 消息发出去**。
  决策逻辑在 `core/agent/ContextLedger.kt`（纯函数 + 单测），别把它挪回 AgentLoop 里。
- **提示词里只写「App 本体就有」的东西**（第 101 轮，用户点名）：设备上额外装的（浏览器）、
  某台机器上量出来的例子、测试期的诊断脚本（`adsh-env-check`）都不进提示词 ——
  两个脚本照旧安装、终端里照旧能敲，只是不再对模型宣传。
- **run_code 失败要带「已跑成的子调用回执」**（`RunCodeTool.completedDigest`）：抛错语义照 dsh
  不动（reject + 中止，dsh 原话 "Programs are never replayed automatically"），SDK 段里那句
  「失败后只补发剩下的步骤、别整段重发」与它是一对，改一处要改另一处。
- `present` 的交付物落在工具行 `messages.deliverablesJson`、本轮改动落在轮首行 `messages.changesJson`；
  轮尾**只有一张**文件卡片（第 86 轮并表：改动 ∪ 交付，`mergeTurnFiles` 是纯函数，别在界面里各写一套
  合并规则），只在「已结束」的轮次上画。别把交付物塞回子调用轨迹（那是有上限的展示件）。
- **长按选中正文时不许碰焦点**（第 85 轮）：消息区手势只在**短按**时 `clearFocus()`，
  判据是纯函数 `shouldClearComposerFocus(dragged, heldMillis, longPressTimeoutMillis)`
  （别改回「没滑动就 clearFocus」—— 那会在抬手后 4ms 清掉 SelectionContainer 的选中，
  用户就复制不了正文了）。
- 会话里**一行消息不能超过 ~2 MB**（`CursorWindow`），读会话一律走 `readMessages`；
  数据库迁移**不能断链**（断了会掉进 `fallbackToDestructiveMigration` 清库）。
- 界面：**自动滚动只有一个「跟随意图」**（第 122 轮整套重写，`ChatScreen` 的 `ScrollFollow` = dsh 的
  `ScrollFollow.following` / `followingTail`）—— 只由三处写它：「读者真的移动过」（位置一变就按 25dp
  阈值重算 = dsh 的 `sample(metrics, movedByReader)`）、「读者动作 / 显式导航 → `pause()`」、
  「自己发消息 / 点回到底部 → `follow()`」。贴底请求统一走 `pinToBottom()`，**只由「变化」触发**
  （内容 = `items` 换了实例、视口 = 键盘）—— **不许回到「每重组一次贴一次底」**：会话停下来之后本屏
  仍会因为后台任务轮询 / 任务横窗 / 连接状态 / 用量重组，每重组一次贴一次就是「会话结束后自动滚动还在跑」。
  兜底补钉（`snapshotFlow { listState.layoutInfo }` → `scrollBy`）**位置变了就一律不补**（= dsh 的
  `movedByReader`：读者自己滚出来的位移只重算意图、不贴底），只补「视口没动、布局却变了」（图片 / LaTeX
  异步解码）—— 少了这条判据，读者「极小幅度快速上滑」会被一帧一帧拽回底部（第 122 轮用户报的抖动）。
  「回到底部」的可见性就是 `!following`（dsh 的 `!followingTail`），第 96 轮那套 64dp 迟滞已删。
  流式节流在 `ChatScreen` 这一层且「变短/换段立刻跟上」；展开/收起一律 `readerAction()` 先交出跟随；
  失败标记（工具行红点 / 子调用行）只进不退；覆盖页是叠层，别用 `AnimatedContent`。
- **排版不许吃 Material3 的默认字距**（第 123 轮）：`MaterialTheme` 的 `LocalTextStyle` = `bodyLarge`
  （0.5sp 字距），`Text` 没显式给的排版属性会从它继承 —— 等宽文本因此比 `BasicTextField` 宽 5%，
  终端看着就是「执行命令后字被横向拉长」，PTY 列数也会比实际渲染宽 2 列。字距归零声明在
  `ui/theme/Theme.kt` 的 `AdshTypography`（= dsh 的 CSS `letter-spacing: normal`）——
  **别删它**，也别改成在调用点逐个补 `letterSpacing`、更别包 `CompositionLocalProvider`（见 NOTES 第 123 轮）。
- 内嵌环境出怪事跑 `adsh-env-check`（**别用 `run-as` 测 exec**）；渲染/截图走 `adsh-shot`；
  改 `$PREFIX/bin` 下的脚本后用 `python3 scripts/check-env-scripts.py` 过一遍（前缀的 `/bin/sh` 是 dash）。
- **后台任务**（第 118 轮）：进程内的唯一真相是 `com.adsh.app.core.jobs.Jobs`（别在工具里另存一份状态）；
  模型面的三个工具只经它读写；`Jobs.settle` 的 awaited 判据是 `waiters > 0 || held`（前台注册时的持位，
  见 `hold`），**别删** —— 删了就回到「极短命令既发通知又被 remove」那个 bug；**通知的两种形态也不许合并**
  （`JOB_NOTICE` = 开一轮 / `JOB_NOTICE_INJECTED` = 落在跑着的那一轮里；投递决策看 `turnsInFlight` 而不是
  `sending`）；前台 bash 走的是
  「注册成 job + 有界 wait + 结算即 remove」，`run()` 只剩探针 / rg / 安装器在用；会话删掉或 View 销毁
  时 `cancelOwner` / `cancelAll`（安卓杀 App 不会带走子进程）；**杀树的根 pid 来自子 shell 自己写的标记
  文件**（`spawn` 里 `printf %s "$$" > <marker>; <command>`）—— 别改回反射 `Process.pid()`：安卓的运行时
  里没有这个方法，拿不到就只剩 `destroy()` 直接子进程，孙进程会被 init 收养（真机实测过）；属性与转发它的函数不许同名（真机上就是
  `StackOverflowError: stack size 1037KB`）。
- **任务弹层那一行的排版别动**（第 119 轮）：外层 Row 里「命令行 + 状态」必须**一起**待在一层带 `weight` 的 Row
  里（时长与倒角因此在外层先占位）——状态一旦放回外层，它就会按内容吃光剩余宽度，把时长压成 0 宽（真机：
  `5秒` 竖着折成两行、倒角被顶出弹层）；运行中行第二行同理（状态 `weight(1f, fill = false)`）。
- **注入行（插话 / 任务通知）只在「认领」时落库，不许再回到「到达即落库 + 事后挪位置」**（第 120 轮，
  收件箱落地）：`ConversationRepository.enqueueInjected` 只入内存队列，`claimInjected` 在 AgentLoop
  **每步开头**（装配请求之前）写库 —— 位置一次定死：上一步的工具结果之后、这一步的 assistant 之前
  （dsh 的 `preStep` → `inbox.claim`）。**为什么不能是「先落库再移动」**：界面会看着它跳一次
  （用户原话「都显示注入了，agent 一输出下一秒就变换注入的位置了」），而且挪到 assistant 之后、
  tool 之前仍然与 dsh 差一格 —— 那个位置还会打断工具结果配对（第 119 轮最贵的那条 bug）。
  第 119 轮的 `keepToolGroupsAdjacent` / `realignInjectedAfter` / TurnList 的寄存都因此**删掉**了，
  别再加回来。收尾判据照 dsh：这一步没有工具调用时若 `hasInjected` 就 `continue`（`turnEnds &&
  inbox.nextStep.length === 0` 才 break）；`cancel` 要清收件箱（dsh 的 `inbox.clear()`）。
  认领后要让界面重读一次消息（`ChatEvent.InboxClaimed`），否则刚认领的行要等下一步定稿才画出来。
  **别再加本地回显**：第 120 轮加过一版（`pendingEcho`，提交那一帧就上屏），用户复看后说不好用，
  已按原样撤掉 —— 现在插话与通知都是**认领那一刻**才出现在会话里（dsh 的节点时机；dsh 客户端那份
  `PendingSteeringBubble` 是从 host 的 inbox 投影来的，ADSH 没有这一层）。
- **QuickJS 的栈预算必须小于执行线程的真实栈**（第 120 轮）：`QuickJsRuntime` 里
  `setMaxStackSize(256 * 1024)`，**别调大**。QuickJS 的溢出判据是 `sp < stack_top - stack_size`，
  记的是它自己的账；账比 Android 线程栈（约 1 MB）还大时它永远不会先抛 `stack overflow`，
  递归会吃穿 pthread 栈 —— 那是**进程级 SIGSEGV**（crash 日志 `Cause: stack pointer is not in a rw map`），
  整个 App 一起死。dsh 没这层风险（它的 PTC 程序跑在独立 Node 进程里），ADSH 是同进程 QuickJS。
- **花名册（`Jobs.roster`）是合并推送的**（第 119 轮）：`emitRoster` 走 100ms 窗口
  （dsh 的 `DEFAULT_OBSERVE_FLUSH_MS`），窗口内的提交只推一帧 —— 这是为了不让「一个任务结算与另一个
  注册之间那一瞬间的空花名册」传到界面（卡片会把「已结束」整段展开又收起）。别改回同步 `_roster.value`。
- **结算通知必须走合批车道**（第 119 轮）：`Jobs.onNotice` 的回调只 `trySend` 进 `noticeChannel`，投递由
  ViewModel 里那个消费者做（一批一次落库 + 一次 `_state.update` + **一次**开轮决定：第一条唤醒、其余注入）。
  别改回「每条通知各起一个协程、各刷一次界面」——10 条通知就是 10 次整条对话流重建。唤醒那一支也别在
  `startTurn` 之前自己先刷一次 `messages`（中间会画出一个已结束的空轮）。**第 120 轮起注入那一支不再
  落库**（进收件箱，见上一条），所以「行落库的时刻决定它的位置」这件事只对唤醒那一支还成立。
- 改动 dsh 侧行为时报据从 GitHub 读：`gh api repos/deepseek-ai/deepseek-harness/contents/<path> -H "Accept: application/vnd.github.raw"`。

## 构建输入（不要删）

- `app/src/main/assets/bootstrap/usr.zip`（32 MB，Termux 前缀，运行期解压）
- `app/src/main/execLibs/`（随包分发的可执行文件 + `bin/` 下 79 个脚本）与 `assets-src/`
  —— **必须原样复制**（它们从 nativeLibraryDir 执行，改文本会把前缀改成不存在的路径）
- `local.properties`、`keystore.properties`、`keystore/adsh-side.jks`、`gradle/`（wrapper）

## 待办

- 终端「回车后整页变大」按用户要求**不再修**（要修的下一条路是 `adjustNothing` + 手动 insets，
  别去调字号 / padding）。
- 第 117 轮用户点名：**其余待办一律不做**，已经全部删掉（截 ADSH 自己界面、《内嵌 Termux 环境审查
  报告》剩下的 P1-4 / P1-5、pi-ai 的另外两个协议、用户气泡正文可选、改动卡片只统计 write / edit、
  插话只在步边界落库）。原文不再保留（历史已压成一条 orphan 提交），要捡只能从 GitHub 上被顶掉的旧提交里翻。

