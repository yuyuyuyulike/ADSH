# HANDOFF（给下一个会话）

## 这是什么

ADSH：把 deepseek harness（dsh）的 PTC 语义（模型写一段程序组合调用工具）用 Kotlin + Compose 原生重写
成安卓 App —— 无 Node 运行时、无插件、无运行期下载。仓库 <https://github.com/yuyuyuyulike/ADSH>（public / MIT）。

- 功能说明看 [README.md](README.md)；**改代码前先看『不要破的硬规矩』**；历史看『阶段索引』与『经验与坑』。
- 工作区 `D:\WSN2005\Android1\App\ADSH`；dsh 参考源码在 `D:\tmpdsh-work`（**不要删**：`spec-ui.md` / `spec-log.md` 是展示层规格），
  解包产物与 `asar-tool.js` 在 `D:\tmp`。第二阶段的方案、审计与踩坑已合并成
  [docs/LESSONS.md](docs/LESSONS.md)（《经验与坑》：坑清单 + 不做清单 + 验证手段 + 方法论）。

## 当前状态（0.2.2 / versionCode 14）

- **仓库**：远端 `main` 始终最新；本地快照 tag 只留最新一个。**github.com:443 在这台机器上连不通**（api.github.com /
  uploads / codeload 通，raw 不通）—— `git push` 会 `Connection reset`，推送改用 `scripts/push-via-api.py <远端当前提交>`：
  把 `base..HEAD` 的提交用 gh 的 git-data 接口逐个重建到远端（blobs → trees → commits → PATCH ref），比对
  「远端 tree == 本地 HEAD tree」才算成功；远端那串提交 **SHA 与本地不同、内容逐字节相同**，下次 push 前先 `git fetch`（需代理）。
- **发布物**：`dist/ADSH-0.2.2-release.apk`（**44,635,730 字节**，sha256 `afa5c1a9…`；R8 minify + shrinkResources、含
  baseline profile）+ `dist/ADSH-0.2.2-debug.apk`。README 下载链接指向 release；按用户口径**旧 release 不删**。release 不可
  调试（`run-as` 被拒），深度诊断装 debug 包；两者同包名同一把签名，`install -r` 可互相覆盖且不动数据。装机冒烟口径：
  新会话跑一轮问答正常、`adb logcat -b crash` 无 FATAL。
- **门禁与测试口径**：`:app:testDebugUnitTest` 是默认门禁（**858 用例全过**）；改行为面（core/、tools/、ui 的状态接线）
  必须跑，增量单类 3~8 秒，纯搬运也建议跑；删了测试侧代码或改了断言，至少跑 `:app:compileDebugUnitTestKotlin`。
  用户点名「不跑测试」的轮次只保证编译通过 + 装机启动无 FATAL。
- README 被用户在网页上改过 —— 压平 / 改写前先把工作区同步成他那一版，否则 orphan 提交会顶掉他的修改。

## 阶段索引（R0–R204）

原来每轮的详细段落已全部删除，这里**每轮只留结论与判据**；更早的五段历史（项目自己的轮次计数）：① 界面照进 Compose；
② 内嵌 Termux 与执行层（**方案 A：包名 `com.termux`、targetSdk 钉 28** ＋ LD_PRELOAD 写围栏 shim）；③ 提示词与工具逐字对 dsh
（系统提示词**纯静态化**，动态走 `context` 快照）；④ 真机实测（`ctx.jobs` 整链、注入行改**收件箱认领**）；⑤ 收尾（PTC 展示重写、
**QuickJS 栈 256 KB**、自动滚动重写、终端显示根因，出 0.1.9）。

### 一、第二阶段熵减（R0–R99）

目标：**不加新功能**，只做等价改写；验收看行为不变量（编译 + 单测 + 真机），不看减了多少行。

| 轮次 | 结论 / 判据 |
|---|---|
| R0 | 恢复单测门禁 + 立熵基线（CPD）｜368 绿 |
| R1 | 83 处无涟漪点击 → `Modifier.dshClickable` |
| R2 | 图标 5 份私有工厂 → `DshIconFactory` |
| R3 | 133 处 `spacedBy(N.dp)` → `DshSpacing` |
| R4 | 审批 / 计划卡共用壳 `DshWarnCard` |
| R5 | 删 `ChatEvent.Finished` 整条链 |
| R6 | 抽屉两处 `SessionRow` → 局部 composable |
| R7/R7b | 图标工厂残留并入 `dshIcon` + `check-icon-structure.py` |
| R8 | 381 个裸 dp → `DshSpacing` |
| R9 | 设置页三张插件卡拆开 |
| R10 | 拆 `TerminalPanel`（4 提交） |
| R11 | `runTurnBody` 判定提纯 → `TurnDecisions.kt`（8 纯函数） |
| R12 | 浮层互斥 + dismissed → `ChatOverlays.kt` |
| R13 | 切缝 ChatScreen 六刀 + `AutoScroll.kt` |
| R14 | ChatScreen 主函数 state holder |
| R15 | `DshComposer` 弹层菜单 → `ComposerMenus.kt` |
| R16 | 抽屉整簇 → `Drawer.kt` |
| R17 | 抽屉主体拆四小节 |
| R18 | 问题卡拆三段 |
| R19 | 命令字典 → `Palette.sessionPaletteCommands` |
| R20 | items 装配 + 附件选择器提纯 |
| R21 | AppRoot 画布 → `AppLayerStack` |
| R22 | 三处流式收文本 → `LlmTextCollect.collectText` |
| R23 | 先织网：三个窄端口 + 黄金用例 |
| R24 | runTurnBody 一刀：请求装配 / 落库 / 日志 |
| R25 | 二刀：记账 + 采样 → `TurnMeter.kt` |
| R26 | 三刀：工具派发 → `runToolCalls` + `ToolCallGuard.kt` |
| R27 | 工具世界收成端口 `TurnTools.kt` |
| R28 | `buildMessages` 拆四函数｜150 → 43 |
| R29 | 请求装配整簇 → `RequestMessages.kt` |
| R30 | `Attachments.kt` 三分 |
| R31 | `startTurn` 一刀：事件 → 状态纯判定 |
| R32 | 事件循环 / 收尾各起名字 |
| R33 | 开轮输入 / 工具上下文提纯，startTurn 收官 |
| R34 | 多重集比对固化成 `scripts/check-move.py` |
| R35 | 删死代码 + 默认值兜底（漏接线变编译错误） |
| R36 | 「自己发的消息」判定六处 → `ChatDecisions.kt` |
| R37 | 触发菜单可见性算式 → `paletteViewOf` |
| R40 | 死代码清扫（未用 import / 死参数 / 过期注释） |
| R41 | 粘滞 Ctrl 判定 → `draftOutcome` |
| R42 | 切会话改成「内容就位再收起」 |
| R43 | 换模块设置页：容量字段 → `SettingsModels.kt` |
| R44 | 添加卡就绪算式 → `providerFormState` |
| R45 | 编辑卡「保存」转移提纯｜变异 3 处 |
| R46 | 提供方清单增 / 存 / 删提纯 |
| R47 | 按 dsh 修「空白 ID 模型行」 |
| R48 | `scripts/dsh-extract.py` 从 `app.asar` 取原实现 |
| R49 | 拉模型弹窗候选 / 过滤 / 全选 / 采纳提纯 |
| R50 | 修草稿生命周期 bug（取消后空行残留） |
| R51 | 模型行内编辑 → `modelEdited` |
| R52 | 设置页切缝一：模型区整簇 |
| R54 | 切缝二：功能区整簇｜顺带修脚本写 CRLF |
| R55 | 切缝三：共用小件 → `SettingsParts.kt` |
| R56 | core/data 一：`claimInjected` 的 name 改写 |
| R57 | 会话搜索纯逻辑 → `SessionSearch.kt` |
| R58 | 落库标题判定 → `SessionTitle.kt` |
| R59 | 老配置 → 提供方清单迁移判定（挖到共享密钥 bug） |
| R60 | `modelAcceptsImages` 三岔 → `acceptsImagesFrom` |
| R61 | 按 dsh 修迁移 bug：凭据按提供方路由各一份 |
| R62 | 网页搜索 prefs 值判定 → `WebSearchSettings.kt` |
| R63 | 深度死代码清理｜扫描器全 0 候选 |
| T0 | `/system/bin` 进 bash PATH（零权限只读认知层） |
| T1 | phone 通道（`$PREFIX/bin/phone` + 轮询） |
| T1-熵减 | 假字段 / 协议提纯 |
| R64 | runtime/termux 一：`BootstrapInstaller` → `BootstrapPlan` |
| R65 | 二：环境装配 → `shellEnvironment(ShellFacts)` |
| R66 | 三：围栏 / 杀树顺序 / `/proc` 解析 → `RuntimeDecisions.kt` |
| R67 | runtime/termux 收官 |
| R68 | core/tools 一：`renderBash` → `BashRender.kt` |
| R69 | 二：字符集判定提纯（整页乱码防线） |
| R70 | 审计 §8.3 / §8.4 收官 |
| R71 | core/jobs 一：`OutputRing` + `RingChunk` 提纯 |
| R72 | 二：栅栏 + 结算 detail → `JobRules.kt` |
| R73 | 三：注册表行为用例（不改生产代码） |
| R74 | core/jobs 收官 + 结论文档 |
| R75 | 自查 R64–R74：删三处「为可测多出来的层」 |
| R76 | 深度死代码清理（六角度） |
| R77 | 修实测报告第 4 / 8 / 9 点（含 `spawn` cwd） |
| R78 | run-as 深度测终端 / bash |
| R79 | 修「标题没总结」= 标题调用没关思考 |
| R83 | 抽屉动画退回定长缓动（弹簧被否） |
| R84 | 抽屉最终口径：**不要缓动**（线性） |
| R85 | 抽屉自己的时长 170ms（原与覆盖页共用 220） |
| R86 | 抽屉 170 → 130ms |
| R87 | 抽屉 130 → 112ms；模型重连照 dsh |
| R88 | 抽屉 112 → 120ms；灵动岛 |
| R89 | 保活没生效的真因 = 自绘悬浮窗外层崩溃 |
| R90 | 岛样式改走 MIUI 焦点通知 |
| R91 | 焦点通知真机不画 → 撤回媒体路线 |
| R92 | 切会话「停」的真根因：`stashLiveRun()` 用屏幕上的会话 id |
| R93 | 四点熵减 + 死代码扫描器深度优化 |
| R94 | `Hairline` 42 处替换 + run-as 深度测 bash |
| R95 | 后台任务「一直等」真因在 `run_code` |
| R96 | 按 dsh 进程隔离模型重做 PTC 运行时（**到点杀进程**） |

> 原表缺 R38/39/53/80–82/97–99 的独立行；R104 与 R106–R191 也没有独立行（过程记录在更早的历史压平里被删）。

### 二、之后的功能与实测修复（R100–R204）

| 轮次 | 结论 / 判据 |
|---|---|
| R100 | 发布 0.2.1 + 整理仓库 / 工作区｜冒烟 `2 + 3` → `5` |
| R101 | UI 九条 + 熵减｜弹窗夹取照 dsh |
| R102 | 细节四条 + 版本 0.2.2 |
| R103 | 细节两条（新会话不占位） |
| R105 | 快照 → 试做自绘灵动岛 → 被否回滚 → 改启动行为 |
| R192 | 丢自绘岛 + 清空会话 + 查 `ask_user` |
| R194 | 新会话首轮慢的定位：标题调用没关思考 |
| R195 | 标题生成默认关闭思考（按路由族给） |
| R196 | 只有 DeepSeek 那条路由发 `thinking=disabled` |
| R197 | 工具输出打爆 app 的闪退 → 加工具侧上限 |
| R198 | 发布 0.2.2 + 整理工作区（压平 + `push-via-api.py`） |
| R199 | UI 三条 + 细节两条（抽屉照 0.1.9 的 spring） |
| R200 | 窗口三条 + 细节两条（胶囊可直接切换） |
| R201 | 卡片折行 + 展示细节向 dsh 看齐 |
| R202 | 修「一轮结束后子调用只剩最后一条」= 累积 + 同步列表 |
| R203 | 深度清理死代码 + 构建 release 装机｜44,635,730 字节 / code 14 |
| R204 | 一步里第二个 run_code 必失败 → 真机定位到 MIUI 拒绝重启 `:ptc` 服务｜解绑先于杀 + 等进程消失；标题换字去动画 |

## 经验与坑

### 构建 / 工具链

- `cmd.exe /c gradlew …` 一个任务都不跑 → 被当成交互式 cmd、参数被吞 → 从 bash 直接跑 `./gradlew.bat`，显式给 `JAVA_HOME` / `PATH`。
- 自动替换脚本自己会出 bug（插进 Modifier 链 / KDoc、Windows 写 CRLF）→ 改完必须复核：编译器 + `remove-unused-imports.py`。
- 变异测试「没响」多半是变异体根本没编译 → 多行锚点用 `\n` 转义少引号、`sed` 的 `&` 是整匹配 → 锚点改用三引号；一次只跑一个变异、还原放 `finally`；**被 `timeout` 杀掉时 `finally` 不执行**，下一轮开工先 grep 锚点确认还原干净。
- 报错出现在文件末尾 `Unclosed comment` → Kotlin 块注释会嵌套（KDoc 里的 `/*` 片段开了新注释）→ KDoc 里避免 `/*`。

### Kotlin / Compose

- 提纯后报「可能为空」→ 抽纯函数切断了顺着 `val` 的智能转换 → 显式 `checkNotNull` + 注释；别放弃提纯，也别把 `!!` 当默认答案。
- 漏接参数却不报错 → Composable 的默认值兜底 → 删掉默认值，漏接线从此是**编译错误**；反过来，只写不读的字段不进库。
- 弹层夹取与 dsh 对不上 → Compose 弹窗默认被裁剪 → `PopupProperties(clippingEnabled = false)` 之后窗口才是整屏。
- 自动滚动 / 字距 / 抽屉的坑见『硬规矩』。

### Android / 真机

- release 包 `run-as` 被拒 → 深度诊断装 debug 包；围栏判定可免模型造现场：`run-as com.termux env LD_PRELOAD=<nativeLib>/libadshfence.so ADSH_FENCE_MODE=… bash -c '…'`（nativeLib 从 `readlink -f files/usr/bin/bash` 反推）。
- shim 报 `CANNOT LINK EXECUTABLE … not found` 差点当成真机 bug → `nativeLibraryDir` 的值含 `==`，按 `=` split 会从中间截断 → 按第一个 `=` 取剩余；先怀疑自己的解析。
- 拿不到子进程树根 pid、孙进程被 init 收养 → 安卓运行时没有反射的 `Process.pid()` → 由子 shell 写标记文件；属性与转发它的函数不许同名（真机 `StackOverflowError`）。
- `sh -c 'pm list packages'` 退化成打印用法 → adb 把多参数重拼、引号分组全丢 → 复合命令整串当一个参数传。
- 闪退 / OOM：`adb logcat -b crash -d -v time` 看 Abort message 与堆上限，`dumpsys meminfo` 盯 Dalvik Heap。
- 「保活没生效」「岛不显示」→ 真因是自绘悬浮窗外层崩溃，不是通知渠道；MIUI 焦点通知真机上**不画** → 留一条**已验证能显示**的媒体路线兜底。
- 首轮比第二轮慢 → 标题那次小调用没关思考、先受理还与主请求抢带宽 → 按 dsh 口径按路由关思考；前缀缓存命中由**服务端**决定。
- **同一个步骤里连续两次 run_code，第二个必失败**（`code run failed (worker-exit): PTC 进程没起来（绑定超时）`，正好 5 秒）：`:ptc` 进程死在「还挂着 `BIND_AUTO_CREATE` 绑定」的状态下时，AMS 把它当「服务需要重启」再拉一次，**MIUI 的 `AutoStartManager` 直接拒绝**（`logcat`：`Reject RestartService service :ComponentInfo{…PtcWorkerService}`），于是紧跟其后的那次 `bindService` 挂在一条再也不会起来的服务记录上。两次调用之间正常隔着一次模型往返（~2 秒）所以看不出来，只有同一步的背靠背才踩中。修法两条一起：**先解绑再杀** + **杀完等 `/proc/<pid>` 消失**（`PtcProcess.close()` / `kill()`）。判据是 `logcat` 里 `ActivityManager: Start proc …:ptc` 与 `Process …:ptc (pid …) has died` 的成对出现，以及 `ADSH_TRACE` 的 `tool+ / tool-` 时间戳。

### 与 dsh 对齐

- 自己发明的方案会被否（「放弃线程」的看门狗）→ dsh 是**进程隔离 + 到点杀进程** → 行为分歧先读 dsh 现文：`gh api repos/deepseek-ai/deepseek-harness/contents/<path> -H "Accept: application/vnd.github.raw"` 或本机 `D:\tmpdsh-work`。
- 展示细节不确定时照抄 dsh 客户端的常量 / 规格（100ms 花名册窗口、25dp 迟滞、`letter-spacing: normal`）；`scripts/dsh-extract.py` 从 `app.asar` 取原实现。
- 在 dsh 里搜不到某个常量名 → 那是 ADSH 自己的（如 `JOB_NOTICE`）→ 按 KDoc 语义钉行为，并在提交信息里说明没有原实现可对。

### 扫描器与门禁

- 扫描器把有调用者的文件报成孤儿 → 正则没覆盖修饰符组合；「0 候选」≠ 没有死代码。
- 审计报告的关键字计数不可直接当待办（五条里三条不成立）→ 最危险的是「`ChatEvent.Usage` 是死分支」：它在喂 token 账本，删了会**静默丢统计**、没有编译或测试会拦 → 凡「死代码 / 重复」结论，动手前先看代码。
- 等价改写用 `check-move.py` 做「代码行 / 注释行多重集比对」（`--allow-old/--allow-new` 当门禁）；提纯要配**变异测试**；只有真机才走到的代码靠逐字等价 + 用例 + 诚实说明。

### 诊断方法

- 执行层行为靠探针量：临时测试把 `shellEnvironment` 的真实输出落盘成环境变量，再用 `sh -s` 喂进设备（探针目录用完即删）。
- App 里删不到空会话 → 改库：`am force-stop` → `run-as` 把 `databases/adsh.db` 与 `-wal` 拷到 `files/` 再 `exec-out cat` → 本机 `sqlite3` 删行（WAL 自动重放）→ `wal_checkpoint(TRUNCATE)` → `base64 -w0` 写回 → 删设备上 `-wal` / `-shm` → 起 App 核对。
- 「首轮慢在哪」：`adb logcat -c` → 发送 → `adb logcat -d -v time -s ADSH_LLM:V ADSH:V`（`POST … msgs=N tools=M` 与 HTTP 200）；再用本机 python 直连同一把 key 做 A/B，那一步量的是**服务端**行为。
- 这台 MIUI 上 **`adb shell input tap` 进不了第三方 App**（合成触摸被丢，`input keyevent` 打系统窗口却有效，Home 能回桌面）→ 想驱动一次真实回合，最省事的是往 `MainActivity` 里临时加 5 行钩子（`LaunchedEffect` 读 `intent.getStringExtra("adsh_send")` 调 `viewModel.send`），`am start -n … -e adsh_send '<prompt>'` 发一条；**提示词按整串单引号传**（`adb shell "am start … -e k 'a b c'"`），否则设备侧 sh 会把空格拆开、`am` 只把第一个词当参数值（模型收到一个 "Run"）。用完把钩子删干净。
- 同一个进程里连开两次 Activity（`am start` 不加 flag）会留下两个实例、两套 ViewModel：界面停在旧实例上、新实例在后台跑回合，截图会看错对象 → 观察 UI 用**干净的一次启动**（`force-stop` 再 `am start`）。

## 构建与发版

- 命令：**从 bash 里直接跑 gradle wrapper**（`cmd.exe /c` 会被当成交互式 cmd、一个任务都不跑）：
  `JAVA_HOME='D:\WSN2005\Android\jbr' PATH="/d/WSN2005/Android/jbr/bin:$PATH" ./gradlew.bat --offline <tasks>`
  （JDK 17 = `D:\WSN2005\Android\jbr`；`scripts/build-debug.bat` 是给 Windows 侧用的同一套设置）。
- release 必须 `isMinifyEnabled = true` + `isShrinkResources = true`（build.gradle.kts 已开）；debug / release 同包名同一把签名，
  `install -r` 可互相覆盖且不动数据；**versionCode 只往前加**。
- 装机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（debug）或 `adb install -r dist/ADSH-<版本>-release.apk`（release）——
  adb = `D:\WSN2005\Android1\platform-tools\adb.exe`，设备 `FQJZF6U4TCVC7DB6`。装完 `adb shell dumpsys package com.termux | findstr version`
  核对版本、`adb logcat -b crash` 应无输出；`scripts/verify-apk.bat <apk>` 看 badging 与签名。
- **只想留个本地快照（不上传、不发版）时别走下面这套**：直接 `git add -A && git commit` 就够了（远端一动不动）。
  下面这套是「要发版」时才用的：
  1. `:app:assembleRelease`（需要时加 `:app:compileDebugUnitTestKotlin`）→ APK 覆盖成 `dist/ADSH-<版本>-release.apk` → 装机核对；
  2. 打快照：`git checkout --orphan tmp` → `git add -A` → 单提交 → `git branch -M main` → `git reflog expire --expire=now --all` →
     `git gc --prune=now`（历史上只留这一个提交）；新 tag 打在它上面（`git tag -f v<版本>-<日期>`）、旧 tag 删掉；
  3. 推送走「当前状态」那套 `scripts/push-via-api.py`。若 DNS 又给出连不上的 `github.com`，可临时钉 IP：
     `git -c http.curloptResolve=github.com:443:140.82.113.4 push origin main`（`140.82.121.4` / `140.82.113.4` / `20.27.177.113` 都试通过，
     同一 IP 时好时坏；`api.github.com` 走正常 DNS）。`/usr/bin/gh … No such file` 那行 stderr 无害；`gh auth` 的 keyring token
     会过期 → 重跑 `gh auth login -h github.com`；
  4. `gh release create v<版本>-<日期> dist/ADSH-<版本>-release.apk --title … --notes …`（走 api/uploads）；旧的用
     `gh release delete <tag> --cleanup-tag --yes`；发完核对资产大小与 digest。**绝不要 `gh release edit`**：release 的说明与标题是
     **用户自己在网页上写的**，换资产用 `gh release upload <tag> <apk> --clobber`（只换资产、不动说明）；
  5. README 的下载链接指向新 tag；
  6. 收尾清工作区：`app/build/`、`build/`、`app/.cxx/`、`.kotlin/`、`scripts/__pycache__/`（构建依赖在 `GRADLE_USER_HOME` =
     `D:\WSN2005\Android1\.gradle`，**不在**工作区里，别误删；`dist/` 只留最新那份 APK）。

## 不要破的硬规矩（改代码前逐条看）

- **`targetSdk = 28`、包名 `com.termux`** —— 内嵌 Termux 能直接 exec 自己数据目录里文件的唯一前提。release 的 R8 两个开关保持 true；lint 的 `ExpiredTargetSdkVersion` 是**关掉的**，别「修」。
- **写围栏**：围栏 / `/tmp` 映射 / 脚本 shebang 自愈都在 LD_PRELOAD shim 里；read-only **也要挂围栏**（白名单可为空，哨兵缺失时失败关闭）。改 `fence.c` 后跑 `scripts/fence-selftest.sh`。
- **提示词纪律**：不许重复（`PromptAssemblerTest` 盯着）；工具说明只在 `ToolSdk.specs` 写一次；`PTC_ONLY` 两句都要在；只有 `run_code` 能被模型直接调用。
- **系统提示词必须静态**：`PromptAssembler.STATIC_SYSTEM_PROMPT` 里不许出现模型名 / 工作区路径 / 策略 / 计划；`ToolSdk.section()` 与 `WORKING_RULES` 保持无参数；动态只允许两块外来文本（工作区指令文件链、用户自定义后缀）。
- **两条动态注入**：`sandbox:policy` 与 `env:android-termux`（含工作区事实与计划模式）走 `context` 行、内容一变**追加**新快照；`form = snapshot` 的 `context` 行**要作为 user 消息发出去**。决策在 `core/agent/ContextLedger.kt`（纯函数 + 单测），别挪回 AgentLoop。
- **提示词只写「App 本体就有」的东西**：设备上额外装的（浏览器）、某台机器量出来的例子、测试期诊断脚本（`adsh-env-check`）都不进提示词 —— 脚本照旧安装、终端里照旧能敲，只是不向模型宣传。
- **`run_code` 失败要带「已跑成的子调用回执」**（`RunCodeTool.completedDigest`）：抛错语义照 dsh（reject + 中止，"Programs are never replayed automatically"），SDK 段那句「失败后只补发剩下的步骤」与它是一对，改一处要改另一处。
- **交付物**：`present` 的交付物落工具行 `deliverablesJson`、本轮改动落轮首行 `changesJson`；轮尾**只有一张**文件卡片（改动 ∪ 交付，判据是纯函数 `mergeTurnFiles`，别在界面各写一套），只在「已结束」的轮次上画；别把交付物塞回子调用轨迹。
- **长按选中正文不许碰焦点**：只在**短按**时 `clearFocus()`，判据是 `shouldClearComposerFocus(dragged, heldMillis, longPressTimeoutMillis)` —— 改回「没滑动就 clearFocus」会在抬手后 4ms 清掉 `SelectionContainer` 的选中，正文就复制不了。
- 会话里**一行消息不能超过 `2 MB`**（`CursorWindow`），读会话一律走 `readMessages`；数据库迁移**不能断链**（断了会掉进 `fallbackToDestructiveMigration` 清库）。
- **抽屉收起必须等「内容换完」**：六条路径（选会话 / 新建 / 分叉 / 拖拽 / 点空白 / 返回键）全走 `AppRoot.settleDrawer(0f)`，它先 `withFrameNanos { }` **等两帧**（≈33ms，抽屉还盖着主界面）再 `animate` —— 等的是状态落进下一帧的组合（有无缓存都一样，不是固定延迟）。状态更新（读库 / 换绑工作区）必须落在滑动起步**之前**，否则滑进来的那几帧内容整块换掉 = 「切会话时主界面闪一下」；等待必须待在**同一个 job** 里（另起协程会被下一次 cancel 抛下），别改回固定 delay、也别当冗余删。**第二道**：`switchConversation` 是 `suspend` 的，抽屉**等内容就位再收起**（命中 = 同步换；未命中 = 等读出来、上限 150ms），收起动画期间不许再换入换出。
- **自动滚动只有一个「跟随意图」**（`AutoScroll.kt` 的 `ScrollFollow` = dsh 的 `following`），只由三处写：读者真的移动过（位置一变按 **25dp 迟滞**重算 = dsh 的 `movedByReader`）、读者动作 / 显式导航 → `pause()`、自己发消息 / 点回到底部 → `follow()`。贴底统一走 `pinToBottom()`、**只由「变化」触发**（内容 = `items` 换实例、视口 = 键盘）；**不许回到「每重组一次贴一次底」**：会话停下后仍会因轮询 / 横窗 / 连接 / 用量重组，每次都贴就是「结束后自动滚动还在跑」。兜底补钉（`snapshotFlow { listState.layoutInfo }` → `scrollBy`）**位置变了就一律不补**（读者滚出的位移只重算意图），只补「视口没动、布局却变了」（图片 / LaTeX 异步解码），否则读者小幅快速上滑会被一帧帧拽回底部。**两条贴底路径都先过 `pinAllowed`**：`following && !touching && !listState.isScrollInProgress`（最后一项 = dsh 的 `!movedByReader` 门；少了它，流式期间内容每长高一帧就把上滑按回底部）。**25dp 迟滞是 dsh 口径、别动**；「回到底部」= `!following`（64dp 迟滞已删）。流式节流在 `ChatScreen` 这层且「变短 / 换段立刻跟上」；展开 / 收起一律 `readerAction()` 先交出跟随；失败标记（工具行红点 / 子调用行）只进不退；覆盖页是叠层，别用 `AnimatedContent`。
- **排版不许吃 Material3 默认字距**：`MaterialTheme` 的 `LocalTextStyle` = `bodyLarge`（0.5sp 字距），`Text` 没显式给的排版属性会继承它 —— 等宽文本因此比 `BasicTextField` 宽 5%，终端看着就是「执行命令后字被横向拉长」，PTY 列数也比实际渲染宽 2 列。字距归零声明在 `ui/theme/Theme.kt` 的 `AdshTypography`（= dsh 的 `letter-spacing: normal`）—— **别删**，也别在调用点逐个补 `letterSpacing`、更别包 `CompositionLocalProvider`。
- 内嵌环境出怪事跑 `adsh-env-check`（**别用 `run-as` 测 exec**）；渲染 / 截图走 `adsh-shot`；改 `$PREFIX/bin` 脚本后跑 `python3 scripts/check-env-scripts.py`（前缀的 `/bin/sh` 是 dash）。
- **后台任务**：唯一真相是 `com.adsh.app.core.jobs.Jobs`（别另存状态），模型面三个工具只经它读写；`Jobs.settle` 的 awaited 判据 `waiters > 0 || held`（前台注册的持位）**别删** —— 删了就回到「极短命令既发通知又被 remove」；**通知两种形态不许合并**（`JOB_NOTICE` = 开一轮 / `JOB_NOTICE_INJECTED` = 落在跑着的那一轮；投递决策看 `turnsInFlight` 而不是 `sending`）；前台 bash 走「注册成 job + 有界 wait + 结算即 remove」，`run()` 只剩探针 / rg / 安装器在用；会话删掉或 View 销毁时 `cancelOwner` / `cancelAll`；**杀树根 pid 来自子 shell 自己写的标记文件**（`printf %s "$$" > <marker>; <command>`）—— 别改回反射 `Process.pid()`：安卓运行时没这个方法，拿不到就只剩 `destroy()` 直接子进程、孙进程被 init 收养；属性与转发它的函数不许同名（真机 `StackOverflowError`）。
- **任务弹层那行排版别动**：外层 Row 里「命令行 + 状态」必须**一起**待在一层带 `weight` 的 Row 里（时长与倒角在外层先占位）—— 状态放回外层就会按内容吃光宽度、把时长压成 0 宽（真机：`5秒` 竖折两行）；运行中行第二行同理（`weight(1f, fill = false)`）。
- **注入行（插话 / 任务通知）只在「认领」时落库**：`enqueueInjected` 只入内存队列，`claimInjected` 在 AgentLoop **每步开头**（装配请求之前）写库，位置一次定死：上一步工具结果之后、这一步 assistant 之前（dsh 的 `preStep` → `inbox.claim`）。**为什么不能「先落库再移动」**：界面会看着它跳一次，挪到 assistant 之后 / tool 之前仍与 dsh 差一格、还会打断工具结果配对；当年的 `keepToolGroupsAdjacent` / `realignInjectedAfter` / TurnList 寄存都因此删掉，别加回来。收尾判据照 dsh：这一步没有工具调用时若 `hasInjected` 就 `continue`（`turnEnds && inbox.nextStep.length === 0` 才 break）；`cancel` 清收件箱；认领后让界面重读一次（`ChatEvent.InboxClaimed`），否则刚认领的行要等下一步定稿才画。**别加本地回显**（加过 `pendingEcho` 已撤）：插话与通知都在**认领那一刻**才出现。
- **QuickJS 栈预算必须小于执行线程真实栈**：`QuickJsRuntime` 里 `setMaxStackSize(256 * 1024)`，**别调大**。它的溢出判据 `sp < stack_top - stack_size` 记的是自己的账；账比 Android 线程栈（约 1 MB）大就永远不会先抛 `stack overflow`，递归吃穿 pthread 栈 = **进程级 SIGSEGV**（crash 日志 `Cause: stack pointer is not in a rw map`），整个 App 一起死。dsh 没这风险（PTC 跑在独立 Node 进程里）。
- **花名册（`Jobs.roster`）合并推送**：`emitRoster` 走 100ms 窗口（dsh 的 `DEFAULT_OBSERVE_FLUSH_MS`），窗口内提交只推一帧 —— 不让「一个任务结算与另一个注册之间那一瞬间的空花名册」传到界面（卡片会把「已结束」整段展开又收起）。别改回同步 `_roster.value`。
- **结算通知必须走合批车道**：`Jobs.onNotice` 只 `trySend` 进 `noticeChannel`，投递由 ViewModel 的消费者做（一批一次落库 + 一次 `_state.update` + **一次**开轮决定：第一条唤醒、其余注入）。别改回「每条通知各起一个协程、各刷一次界面」——10 条通知就是 10 次整条对话流重建；唤醒那支也别在 `startTurn` 前自己先刷一次 `messages`（会画出已结束的空轮）。注入那支不落库（进收件箱），所以「行落库的时刻决定位置」只对唤醒那支成立。
- 改动 dsh 侧行为时报据从 GitHub 读：`gh api repos/deepseek-ai/deepseek-harness/contents/<path> -H "Accept: application/vnd.github.raw"`。

## 构建输入（不要删）

- `app/src/main/assets/bootstrap/usr.zip`（32 MB，Termux 前缀，运行期解压）
- `app/src/main/execLibs/arm64-v8a/`（287 个 ELF：265 个是 `bin/` 下的可执行文件与脚本，
  映射见 `app/src/main/assets/execlibs.map`；其余是 `libbash.so`、`librg.so` 这类）与 `assets-src/`
  —— **必须原样复制**（它们从 nativeLibraryDir 执行，改文本会把前缀改成不存在的路径）
- `local.properties`、`keystore.properties`、`keystore/adsh-side.jks`、`gradle/`（wrapper）

## 待办

- **两个 dsh 动效：用户第 202 轮看完规格后决定「不做了」**（下面这段规格留档，将来要做照抄）：`dsh-turn-preview-enter` 与 `dsh-turn-mark-busy` 是 dsh **轮次条（TurnNavigator）** 的两个部件，
  ADSH **没有这个组件**（右侧只有「回到底部 / 上一条」那几个快捷导航按钮）。规格已从
  `@deepseek-ai/dsh-client-ui-chat/lib/client.js` 抄全（`dsh-extract.py` 可取），照抄即可：轨道 slot 高 0 贴会话区顶、
  `frame` 28px 宽 / 距右 12px、在「视口 − 输入区」那条带里垂直居中（`top: band/2`、`max-height: min(band−64, 420px)`）、
  上下 24px 遮罩、一轮一格；刻度每格 10px 高，`::before` 是 20×2px 横条（默认 `border-l4` + `scaleX(.6)`，hover →
  `label-tertiary` + `scaleX(.9)`，当前轮 → `label-primary` + `scaleX(1)`，未加载 → `opacity .6` + `scaleX(.4)`，过渡 `.14s`），
  跑着那一轮走 `dsh-turn-mark-busy`（`1s ease-in-out infinite`、`50%{opacity:.35}`）；预览卡在轨道左侧、宽
  `min(300px, 100cqw−120px)`、内容 = 提问 + 回答预览，入场 = `dsh-turn-preview-enter`
  （`.12s ease-out`、`translate(4px) → none`）。**交互要用户定**：dsh 是 hover 出预览、点击跳转，手机没有 hover。
- 终端「回车后整页变大」按用户要求**不再修**（下一条路是 `adjustNothing` + 手动 insets，别调字号 / padding）。
- 用户点名：**其余待办一律不做**，已全部删掉（截 ADSH 自己界面、《内嵌 Termux 环境审查报告》的 P1-4 / P1-5、pi-ai 的另外两个协议、
  用户气泡正文可选、改动卡片只统计 write / edit、插话只在步边界落库）。
- **实况窗（Android 16 Live Updates）**：API 已在 `platforms/android-37.0` 上 javap 核对完（`ProgressStyle` /
  `setRequestPromotedOngoing` / `setShortCriticalText` / `canPostPromotedNotifications`）。下一步是**设备实验**：API 36+ 且
  `canPostPromotedNotifications()` 为真时改走实况窗（也可能 MIUI 不 promote），拿不准就留媒体路线兜底；
  targetSdk 28 够不够资格被 promote 未证实。
- **切会话修复待用户真机复测**：切走再切回来，流式尾巴 / 工具行 / 岛要原样接上，上一条会话的「吃白饭中」也不许跑到新会话里
  （判据在 `ui/LiveRunStateTest`）。
