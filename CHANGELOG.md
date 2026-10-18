# CHANGELOG

历史记录。当前状态看 [HANDOFF.md](HANDOFF.md)，稳定事实看 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。
本文件由 HANDOFF.md 在 2026-10 拆分而来：**逐字搬迁，没有删改**。

## 0.2.4 发版（2026-10-18）

- **新增**：右侧可拖滚动条（贴边时可拖、带防误触）、docx / xlsx / pptx / pdf / 图片的本地预览、双击状态栏刘海回到会话开头。
- **修复**：`edit` 的极端错误（一次调用锁死整条工具网关、CRLF 与大文件匹配）、提示词与实际行为不一致等 harness 问题。
- **其他**：卡片字级 / 间距与 dsh 对齐，两个最大模块按职责拆文件（零行为改动），会话流滚动、键盘与抽屉的每帧开销不再随历史增长。
- 版本 **0.2.4 / versionCode 16**；release APK 已装机复验（版本号、冷启动、crash buffer 空），发布在 GitHub release `v0.2.4-20261018`。

## 两个最大模块的梳理：大文件按职责拆开（2026-10-18）

`:feature:chat`（主代码 9.9k 行）与 `:ui:blocks`（8.3k 行）里有 6 个 750～2000 行的「什么都装」文件。按「同一屏 / 同一渲染管线」聚簇拆文件，**零行为改动**（只搬代码；跨文件用到的 private 放开为 internal）：`:feature:chat` 的 Composer 拆出浮层宿主与定位（`Overlay.kt`）、TurnRail 拆出工具行模型（`ToolRowModel.kt`，纯函数）、TurnList 拆出节点模型与装配器（`TurnModel.kt` / `TurnBuilder.kt`）、ChatViewModel 拆出界面状态类型（`ChatUiState.kt`）；`:ui:blocks` 的 DocumentPreview 拆出图片预览（`ImagePreview.kt`）、LatexCore 拆出解析器与符号表（`LatexParser.kt`）。拆后最大的文件从 1989 行降到 1797 行（ChatViewModel），其余都回到 1500 行以下；模块总量不变。顺手复核了死代码与文档：`deadcode` 候选 0、未使用 import 0、版本目录别名 / 资源 / 字符串 0，`docs/MODULES.md` 的实测行数刷新。

## 工具报错详情卡对齐 dsh：搜索 / 网页族不铺输入段，ioCard 改等宽小字（2026-10-18）

用户对照 dsh 指出 grep / glob / todo_write / web_fetch（可能还有 web_search）报错后的详情卡样式不一致。按 dsh 0.2.1-rc.2 的源码与真机逐项核对：

- **报错卡不再铺「输入」段**：dsh 的 SearchRow（grep / glob / web_search）与 WebRow（web_fetch）从不给 ToolRow 传 rawInput —— 展开体只有结果一侧，报错时就是一段红色输出、没有参数 JSON。ADSH 之前对所有工具都铺「输入(参数 JSON) + 输出」。现在这两族（连同 readFamilyRow / FileMutationRow 的 read / read_image / write / edit）一律没有输入段；todo_write / present 同样只留错误输出（参数 JSON 对报错没有信息量）。
- **ioCard 字体改成等宽 11/16**：dsh 的 ioCard 继承 `--dsw-font-markdown-code-block-small`（等宽代码字体），「输入 / 输出」两个标签也是同一套字；ADSH 之前是 12/18 非等宽。参数 JSON 的缩进同时从 kotlinx 默认 4 空格改成 dsh `JSON.stringify(…, null, 2)` 的两空格。
- 段高 **200dp 保留**（dsh 桌面端 150px）：那是此前用户点名的有意偏离，竖屏手机上 150 太矮。
- 用例：`ToolRowSummaryTest` 钉「结果型工具与 todo / present 都没有输入段，通用工具仍铺两空格缩进 JSON」；门禁全绿（122 类 / 1039 例 / 0 失败）；真机 debug 装机核对 grep / todo_write 两张卡。
- 顺手清掉的死代码：`inputBodyOf` 里被工具名集合完全覆盖的 singleFile 分支（不再有调用路径）；全仓扫过 Kotlin 声明（`deadcode.py` 候选 0，baseline 只剩 5 条测试口）、未使用 import / 版本目录别名 / 资源 / 字符串 / C 静态函数，均为 0。

## 会话流性能：滚动 / 键盘 / 抽屉的每帧开销不再随历史行数增长（2026-10-18）

用户反馈长内容 / 大量 Markdown / 大量工具调用的会话里滚动、抽屉动画、呼出键盘掉帧，并担心几十轮、上千步调用的历史会放大问题。复核后按三处「每帧 O(行数)」的根因修：

- **行级绘制剔除**：每行原来各自读 `ScrollState.value` 和贴底补偿的一串状态 —— 滚动 / 键盘动画每一帧把全部行标脏、逐行重跑剔除。现在只读一个**派生剔除窗口**（滚动位置按 128px 台阶化、视口按 64px 向上取整、含贴底补偿），窗口只在跨台阶时换实例；台阶边界按「桶内可能的最小 / 最大滚动值」取安全边界，宁可多画一条边也不露行（`CullWindowTest` 钉住）。
- **右侧滚动条**：原来在组合期读 value / maxValue，每帧重组 + SideEffect 写几何。现在滚动状态只在**绘制阶段**读（只重画 5dp 那条 RenderNode），thumb 命中 / 拖动几何在按下那一刻现算；边缘防误触的命中区改用 `snapshotFlow` 写 volatile。
- **抽屉景深**：haze 实时模糊覆层每帧要把主内容重新渲染进离屏层再模糊，抽屉动画 p50 48ms / p90 61ms。改成主内容图层自己的 `RenderEffect`（API 31+，半径随 progress 连续长；旧系统退化成轻暗化），模糊全程都在、没有覆层与内容之间的颜色差，p50 12ms / p90 20ms。haze 依赖随之删除。
- 真机 debug 实测（同一长会话，各 3–6 轮）：抽屉 p50 48→12ms / p90 61→20ms，键盘 p50 34→12ms；滚动本来就基本达标（p50 9→8ms、p99 30→24ms）。长历史的剩余成本集中在**首次组合 / 布局**（常驻列表的固有代价，虚拟化是硬约束禁止的方向），交互帧已基本与行数无关。

## 深度 harness 测试修复：工具网关死锁 + edit 行尾/规模 + 提示词清理（2026-10-18）

用户让 App 里的 agent 跑了一轮深度 harness 测试（报告只记问题）。本轮按报告复核、对照 dsh 修复：

- **P0 工具网关死锁**：一次 `edit` 把整条工具层锁死（此后连参数校验都超时）。两个根因一起修：`ToolConcurrency` 从全进程单例改为**每轮一份**（`PtcToolRunner` 新建，dsh 的 per-run scheduler）——卡在非协作取消里的在途调用随本轮丢弃，不再堵下一次 `run_code`；`edit` 改用 dsh 的 `countOccurrences` + `String.replace` 单趟字面替换，5 MB 单行 + 百万匹配不再 OOM。
- **edit 行尾语义对齐 dsh**：内容 / `old_string` / `new_string` 先 CRLF→LF 归一化再匹配，写回恢复原风格（此前 CRLF 文件用 `\n` 匹配必失败，提示词还把这个缺陷写成预期行为）。
- **严格 UTF-8**：`write` / `edit` 的孤立代理项直接报错（此前 Java 编码器静默写成 `?`）。
- **提示词**：删掉被测试证伪的「grep 跳过隐藏文件」概称；`edit` 的 CRLF 警告改成归一化事实；`job_output` 写清「流式任务只给上次读取之后的新输出，终局结果只给一次」；`web_fetch` 的 `timeoutMs` 按「每地址计时、一次尝试可能超预算」说真话；去掉重复的 FUSE / 升级 / promoted 段落与实测轶事，压缩 `SDK_INSTRUCTIONS` 里的宿主 API 清单。
- 用例：`ToolConcurrencyTest` 新增「卡死的一轮不拖死下一轮」；`ToolExecTest` 新增 5 MB 单行 `replace_all`、CRLF 归一化、孤立代理项；`PromptAssemblerTest` 去掉被证伪的断言。门禁：全模块 `testDebugUnitTest` + `lint` + `:app:assembleDebug` + `spotlessCheck`，`deadcode` / 未使用 import 0。
- 真机：debug APK 构建装机；清理深度测试残留（workspace `_t_*` / `.adsh/attachments/18`、scratch 测试树、`$TMPDIR` 的 spill 目录）。


## 〇、阶段 10：拆分 `:app` + 归并迷你模块（11 → 8 个工程，2026-10）

用户口径：「尽量不超过 12 个工程；单工程 5k–15k 行；别再有迷你工程」。两件事：先把 34k 行的 `:app` 拆出渲染基础与对话界面，再把剩下 4 个偏小的 core 模块并掉。**行为零改动**，用例全程 **99 类 / 889 例 0 失败**。

**拆分（Lead，批 1–2）**：
- `:ui:kit` → `:ui:blocks`：设计系统整体搬入，再把 `app/ui` 里的渲染基础（Markdown / LaTeX / 代码块 / 工具结果块 / 文件预览 / 图标 / 调色板等 32 个文件）搬进包 `com.adsh.app.ui.blocks`；`:core:workspace` 并进 `:core:tools`。
- 建 `:feature:chat`（`com.adsh.app.ui.chat`，33 主文件 + 22 测试）：ChatViewModel / 会话屏 / 输入区 / 轮次渲染 / 会话级面板。`:app` 只剩壳、导航、设置、终端、灵动岛、组合根。库模块不许读自己的 `BuildConfig`（诊断开关改宿主注入 `ChatDebug.enabled`）；Compose 库单测底座补齐（`unitTests.isIncludeAndroidResources` + debug 清单 + `robolectric.properties`）。

**归并（批 3–5）**：

| 原模块 | 并入 | 依据 |
|---|---|---|
| `:core:base` 的 `core.jobs` | `:core:tools` | 唯一消费者是工具层与组合根 |
| `:core:base` 的 `core.phone` | `:seam` 的 `seam.phone` | 脚本 ↔ App 的线协议，属缝层（包名改） |
| `:core:llm`（含 `core.session`） | `:core:data` | LLM 客户端与会话仓储是同一份公开面 |
| `:core:ptc` | `:platform:termux` | 同属平台实现（进程 / 原生 / QuickJS），都只认 `:seam` 端口（包名改 `com.adsh.app.platform.ptc`） |

**`:ptc` 独立进程服务跟着改**：`app/src/main/AndroidManifest.xml` 的 `android:name` 从 `.core.ptc.PtcWorkerService` 改成 `.platform.ptc.PtcWorkerService`，用 `aapt2 dump xmltree` 核对 APK 清单（`com.adsh.app.platform.ptc.PtcWorkerService` + `android:process=":ptc"`）—— 写错就是运行时崩。

| 指标 | 结果 |
|---|---|
| 工程数 | 16 → 11（阶段 8 / 3c-B）→ **8**（阶段 10） |
| 单工程行数（main+test） | app 9.8k / chat 15.9k / blocks 9.7k / tools 8.7k / data 6.1k / agent 5.0k / platform 4.8k / **seam 1.8k（契约层例外）** |
| 用例 | 99 类 / 889 例 0 失败，搬家全程不变 |
| 单文件增量编译 | `:app` 7.1s（拆分前）→ **4.2s**；`:feature:chat` 3.7s |

判据、例外与「不再拆」的结论见 `docs/MODULES.md`；门禁仍是全模块 testDebugUnitTest / lint / `:app:assembleDebug` / `:app:assembleRelease` / spotlessCheck + python 六条。

**真机复验（Lead，debug 67,429,696 B）**：APK 原生库 291 条且装机后解压成可执行；终端页 PTY 起得来、`adsh-env-check` 全部 ok；新会话一轮 `run_code`→`tools.bash` 输出 `AGENT_OK` + 工作区路径（退出码 0），`:ptc` 进程服务按新 FQN `com.adsh.app.platform.ptc.PtcWorkerService` 正常拉起；logcat crash 缓冲空。

## 一、组合根收口：ToolContext 装配与一次性 LlmClient 移出 UI（2026-10）

路线图阶段 6 收尾：`docs/ARCHITECTURE.md` §五「组合根是唯一创建点」的最后两处例外 —— UI 自己装配工具上下文、设置页自己造 `LlmClient`。

- 新增 `app/src/main/java/com/adsh/app/ToolingHost.kt`：工具上下文的装配点。全局项（应用上下文 / 运行时 / 14 项设置 / 实时权限读取器 / PTC 提供者 `PtcProcess`）在宿主里读一次；每轮才变的（会话 id / 工作区 / 轮次 / 计划模式 / 计划批准回调）由 `contextFor(...)` 传参。
- `ChatViewModel` 只保留 `_state` / `planMode` / 算 turn / `setPlan`，`buildToolContext()` 缩成一次宿主调用；删掉 `com.adsh.app.core.ptc.PtcProcess` 引用与那 14 处 settings 直读。
- 设置页「获取模型」的一次性通道改走 `AppContainer.newLlmClient(ProviderConfig)`，`SettingsModelsSection` 不再 `new LlmClient`。
- 新增 `ToolingHostTest`（3 条）：全局项来自 settings / 注入的提供者，每轮项来自调用参数，`livePermission` 仍是实时读取器。`:app` 用例 53 类 / 463 例全绿。
- 文档：`docs/ARCHITECTURE.md` §二、§五 的 `ChatViewModel.buildToolContext` 改为 `ToolingHost`，删掉「唯一例外」。

## 二、Termux 平台层抽成 `:platform:termux`（2026-10）

路线图 3c-B：`:core` 已成模块、`:seam` 的 `Shell` 端口已立之后，把 `runtime/termux` 从 `:app` 抽成 `:platform:termux`（10 → 11 个 Gradle 工程，用户口径 8–11 的上限）。提供者从此有编译期边界：它只能依赖 `:seam`，想 import `core` / `ui` / `island` 就编不过。

- **搬运零改动**：8 个源文件（2332 行）+ 4 个测试 + `cpp/`（`fence.c` / `pty_bridge.c` / `CMakeLists.txt`）整体 `git mv`。包名 / 类名 / JNI 符号 / 资源路径一个没改；`execLibs/`（287 个 ELF）与 `assets/bootstrap/usr.zip` 留在 `:app` —— 库模块运行时通过宿主的 `context.assets` 读它们。
- **原生部分跟着模块走**：`:platform:termux` 自己声明 `ndkVersion` + `externalNativeBuild.cmake`，产出 `libadshfence.so` / `libadshpty.so`；**APK 级**的 `packaging.jniLibs.useLegacyPackaging` 与 `execLibs` 的 `sourceSets` 留在 `:app`（它们决定 APK 怎么装，不属于平台实现）。
- **门禁同步**：`PACKAGE_MODULES` 加 `runtime.termux → :platform:termux`；`layering-rules.json` 加一条「`runtime.termux` 不许 import `core.* / ui.* / island.*`」（0 违规）；`check-env-scripts.py` / `fence-selftest.sh` / `find-unused-c-symbols.py` 的硬编码路径跟着搬。
- 验收：11 个工程；用例 **98 类 / 886 例不变**（4 个 runtime 测试跟着模块走）；`:app:assembleDebug` / `assembleRelease` 通过且 APK 里 `libadshfence.so` / `libadshpty.so` 齐全、`execLibs` 287 个 ELF 一个不少；`:app:assembleSide --dry-run` 通过；python 六条门禁全绿。

## 三、模块合并：16 → 10 个 Gradle 工程（2026-10）

用户口径：16 个工程分得太细（要求 8–11 个）。**纯机械搬运** —— 包名 / 类名 / 行为零改动，只动模块边界、构建脚本、门禁脚本与文档。映射与依据：

| 原模块 | 新模块 | 依据 |
|---|---|---|
| `:seam:{llm,exec,model,tool,ptc}` | `:seam` | 五个包对消费者是**同一个契约面**（都属「服务定义」）；其中 model/tool/ptc 用 Android API，所以合成一个 Android library |
| `:core:jobs` + `:core:phone` | `:core:base` | 两个包各只有 3 / 1 个文件，消费者只有 `:app` 与 `:core:tools`；Jobs 依赖 `:seam` 的 Spill，本来就不能是纯 JVM |
| `:core:session` | `:core:llm` | 只有一个文件；`SessionEvent` 只被 `:core:llm` 与组合根看见 |

结果 10 个工程：`:app` / `:seam` / `:ui:kit` / `:core:{base,workspace,llm,data,ptc,tools,agent}`。配套改动：

- **门禁脚本改成显式映射**：`scripts/check-module-graph.py` 的「包 → 模块」从按名字推断（`core.* → :core:<第一段>`、`seam.* → :seam:<名>`）改成 `PACKAGE_MODULES` 表（最长前缀优先），`--write` / `--check` / `--strict` / `--selftest` 四种模式都过；`docs/module-graph.md` 随之重新生成。
- **用例数逐项不变**（只搬文件没动测试）：`:core:base` 3 类 / 39 例 = `:core:jobs` 2/27 + `:core:phone` 1/12；`:core:llm` 2/14 = llm 2/14 + session 0 例；其余模块不变。
- **连带一处插件变化**：`:core:jobs` 从 `adsh.kotlin.jvm` 改成 `adsh.android.library` —— 它的依赖闭包里出现了 Android library（`:seam`），纯 JVM 模块选不出变体。合并后 10 个工程里已没有纯 Kotlin JVM 模块。
- 每批验收：`:app:assembleDebug` + 全模块 `testDebugUnitTest` + 包循环 / 模块图 / 文档预算门禁。工程数 16 → 12（seam）→ 11（base）→ 10（session）。

## 四、真机 harness 回归修复（2026-10-17）

用户让 App 里的 agent 跑了一轮 harness 深度测试（报告在设备 `/storage/emulated/0/1/App/harness-probe-report.md`），
逐条与 dsh 参考实现比对：**先判定「是不是 dsh 本来就这样」，再决定改不改** —— 13 条里 6 条是 dsh 的既有行为，不动。

修掉的：

- **P0 `job_kill` 后 PTC 程序必崩（`control channel ended`）**：杀进程树的根 pid 来自子 shell 写的标记文件；spawn 后立刻 kill（或进程已退出、标记已被删）时读到空串，旧实现把 `-1` 交给 `Os.kill` —— kill(2) 的 `-1` 是「本 uid 所有进程」的广播，一次 SIGTERM 把 `:ptc` 运行时进程杀掉。修：`killOrder` 对非法根 pid 返回空表、杀树前最多等 250ms 让标记落地、否则只杀直接子进程（`RuntimeDecisionsTest` 钉住）。
- **P1 write/edit 大文件「先落盘后报错」**：4 MiB 值上限在工具执行**之后**才判，而 value 带完整 before/after。修：回显按 256 KiB 截断（字符边界 + 标记 + `beforeBytes`/`afterBytes`），落盘始终完整；4 MiB 上限只留给只读工具。
- **P2 read 行数**：改用 dsh 的切行口径（只认 `\n`、剥行尾 `\r`、末尾残段非空才计行）——`a\nb\n` 是 2 行、空文件 0 行。
- **P3 文案**：glob/grep 失败按 dsh 三种形状（`<tool> pattern rejected by ripgrep` / `search failed (exit N)` / `could not start its search command`）；workdir 是文件时报 `not a directory`。
- **P3 value.path 口径**：read/write/edit/read_image 的结构化 `path` 改成执行世界绝对路径（dsh 口径），信封正文仍用相对路径（界面卡片不变）；glob/grep 仍相对。
- **P3 spill 目录回收**：`$TMPDIR` 每进程一个 `adsh-subprocess-*`，安卓不会自动清；`Spill.sweepStale` 启动时回收超过 24h 的（3 条用例）。
- **P3 PTC 绑定**：`bindService` 直接返回 false 时立刻按 worker-exit 报告，不再白等 5s 报「绑定超时」。

判定为 dsh 既有行为 / 有意偏离、**不修**：

- `read_image` 把副本写进用户工作区：**有意** —— ADSH 的附件都随会话存在 `<工作区>/.adsh/attachments/<会话 id>/`（导出/删除/显示同一套）；dsh 存 `$DSH_HOME/attachments`（用户口径：不修）。
- glob 含 `/` 的 pattern 锚在搜索根：dsh 原样把 pattern 交给 rg，同一行为；工具说明里已写「include a separator to anchor the depth」。
- bash 截断只留尾部、标记加在末尾：dsh 同款（errors and final results cluster at the end）。
- `signal` 恒 null：Android 的 Process API 不暴露 wait status（dsh 从 Node 的 exit 事件拿）；被信号终止体现为 exit code 128+n。
- 非法 UTF-8：bash 静默替换 U+FFFD、read/edit 严格报错 —— dsh 的两条路径本来就不一致（read 用 `TextDecoder(..., fatal)`）。
- job_kill 对 stopping 任务再 kill 仍回 `cancellation-requested`：dsh 的 kill 也返回「请求是否被接受」，不是状态查询。

真机验证（debug 包）：一轮 agent 程序 5 个 `run_code` 全部正常结束、**0 次 worker-exit**；job_kill 后读 stopping 与终态 killed 都不再崩；2.97 MB 文件 edit 成功返回并带截断标记；末行换行的 5 行文件 `totalLines=5`（与 `wc -l` 一致）。

## 五、真机修复：Activity 多实例（2026-10-16）

真机现象（用户复现）：agent 干活时切后台、灵动岛挂着，agent 提问 → 切回来答完，主界面像卡住了 —— 输入框的
方块（停止）变回**灰色发送**，正文停在那一刻，agent 跑完正文才「蹦」出来。

根因：`MainActivity` 是 `standard`。桌面图标等 `NEW_TASK` 启动会给已在后台的同一个 App **再开一份实例**；
而会话宿主 `ChatViewModel` 是 `viewModel<ChatViewModel>()`（**Activity 作用域**），于是每份实例各有自己的宿主 ——
用户看到的这份新实例 `sending=false`（按钮变灰）、正文只到开实例那一刻；agent 那一轮照旧在**旧实例**里跑完
（消息落库正常，所以「蹦出来」时内容是全的）。`UserQuestionChannel` 是进程级单例，所以提问卡在新实例上照样作答。
取证：`dumpsys activity activities` 里 Task #25626 **sz=3**（3 个 ActivityRecord / 3 个窗口，`topResumedActivity` 是最后那份）；
`HOME` + `monkey -c android.intent.category.LAUNCHER` 稳定复现（sz 3 → 4）。附带风险：任一份实例退出时
`onCleared` 会 `Jobs.cancelAll("app is shutting down")` —— 关掉一份副实例会掐掉真跑着那一轮的**后台任务**。
数据面还有第二个后果：`Jobs.onNotice` 按 VM 各订阅一份，而 `turnsInFlight` 是实例私有的 —— 多实例时同一条结算
通知会让两份实例**各给同一条会话开一轮**（两个 AgentLoop 抢同一个收件箱与库）。

修法：`MainActivity` 声明 `android:launchMode="singleTask"`（硬约束 24）+ 回归 `MainActivityLaunchModeTest`；装机后同一套操作 sz 恒为 1。

同族第二处（同日发现，用户点名改）：**返回键会 `finish()`** → `onCleared` → `Jobs.cancelAll` —— 等于「返回 = 掐掉正在跑的那一轮与它的
后台任务」，而岛正是为「后台继续跑」才存在。改成 `OnBackPressedCallback` → `moveTaskToBack(true)`（硬约束 25）：返回键只退到后台，
抽屉 / 浮层 / 菜单自己的返回键处理登记得更晚，仍然优先。

## 六、规划与地基加固（2026-10，结构轮次）

不是功能轮次：把「只有 Kotlin + Compose 的直觉」补成**有文档、有门禁、有边界**的工程。逐条结论：

- **规划文档**：`AGENTS.md`（改动规矩）、`docs/ARCHITECTURE.md`（分层 / 能力缝 / 硬约束清单）、`docs/TECH-STACK.md`（选型与触发条件）、`docs/MODULES.md`（模块化方案）、`docs/ROADMAP.md`；`HANDOFF.md` 从 463 行拆成「当前状态 + 下一步」，历史搬到本文件、操作细节搬到 `docs/LESSONS.md`，文档行数由 `check-doc-budget.py` 卡住。
- **解耦**：两个强连通分量归零 —— 界面环（island ↔ ui ↔ ui.panels）与核心环（8 个包）。后者靠 8 条回边下沉 `seam.*` 并立起 `PtcRunner`、`Shell` 两个端口。包循环与**依赖方向**从此由门禁盯（`check-package-cycles.py`：剥注释、认内联全限定名、Tarjan；分层规则在 `scripts/layering-rules.json`）。
- **模块化**：切出 5 个 `:seam:*` + `:ui:kit`。`:seam:llm` / `:seam:exec` 能编成**纯 Kotlin JVM** —— 这是「缝没有 Android 依赖」的判据本身；边界用**故意违规探针**验证过（改坏 → 编译失败）。
- **工具链**：build-logic 约定插件、Android Lint 进门禁、Robolectric + Compose ui-test + Turbine 测试底座、Spotless（只做行尾空白 / 末尾换行 / LF；**全仓 ktfmt 格式化被否决** —— 实测会给中文注释插 ASCII 空格）、`gradle-gate.ps1`（Gradle 串行锁）、`reset-lint-cache.ps1`。
- **注释**：全仓「第 N 轮」归零（含 `.c/.h/.js/.pro/.sh`，含中文数字与区间写法）；口径改成**判据制**（不设硬性行数上限）。154 个改动文件的**代码在剥掉注释后逐字节相同**。
- **组合根 + 门禁补口（2026-10 同日续）**：`:app` 的 `AppContainer`（`AdshApp.container`，懒建）成为有状态对象的**唯一创建点**，`ChatViewModel` 全部构造注入（`ChatViewModelFactory`）——「谁依赖谁」只写在构造函数上，界面不再自己 `new` 数据库 / LLM / 运行环境；设置页「获取模型」是唯一例外（一次性列模型）。注释门禁口径补上「第 60 几轮」这类**数字后夹「几 / 多」**的写法 —— 旧口径漏了它，仓里真有一处，自检 28 → 29。
- **阶段 7 批 1：`:core:session` / `:core:jobs` 切成模块（2026-10 同日）**：包名与文件内容一个字节没动（6 个文件 `git mv`，逐字节比对过）；`:core:session` 因公开签名带 `seam.ptc.SubCall` 而只能是 Android library，`:core:jobs` 才能纯 JVM —— 判据是**依赖闭包全 JVM**，不是「自己有没有 android import」。可见性口径：只把需要 `internal` 的 2 个测试搬进模块（6 个 internal 一个都没放宽）。用例总数不变（`:app` 93/845 + `:core:jobs` 2/27 = 95/872），因此门禁口径改成「两处测试加起来」——**只跑 `:app` 会漏 27 条**。同一批还落地了**模块图门禁**：`scripts/check-module-graph.py`（`--write`/`--check`/`--selftest`，复用包循环脚本的词法器）+ `docs/module-graph.md`（180 文件 / 61 条包级边 / 0 环；`agent ↔ tools` 已是单向）。
- **阶段 7 批 2：`:core:workspace` / `:core:llm` 切成模块（2026-10 同日）**：10 个文件 `git mv`（内容逐字节不变），接线 `api(:seam:exec)` / `api(:core:session)` 与 `implementation(:seam:llm)`。两处口径定案：① `internal` 只要是 `:app` 既定入口就**放宽为 public 并补契约 KDoc**（`collectText`、`watchNetwork`），不许悄悄放宽；② **库不许读自己的 `BuildConfig`** —— `LlmClient` 改成构造注入 `logDebug`（由组合根传 :app 的 `BuildConfig.DEBUG`），因为 `side` 变体对库的 matchingFallbacks 是 debug，库自己的 `DEBUG` 会打开日志、行为就不是零改动了。`:core:llm` 另加模块清单声明 `ACCESS_NETWORK_STATE`（lint 的 MissingPermission 按模块清单判，合并后仍只有一条）。用例分家后总数不变：`app` 91/831 + `:core:jobs` 2/27 + `:core:llm` 2/14 = **95 类 / 872 用例**。
- **阶段 7 批 1 余项 + 批 3：`:core:phone`/`:core:ptc`/`:core:data`（2026-10 同日）**：47 个文件 `git mv`（index 里全部逐字节相同）。`:core:data` 这刀最重：14 个源文件 + 7 个测试 + **16 份 Room 导出 schema** 一起搬（数据库类 FQN 不变，schema 身份不变），`ksp { room.schemaLocation }` 跟着走，`:app` 那边删掉已无用的 KSP 插件 / `ksp {}` 块 / room-compiler，Room 在模块里改 `api`（`AdshDatabase` 的父类型要给消费者看得见）。可见性共放宽 6 个（`resumeConversationId`、`fallbackTitleOf`、`NEW_SESSION_TITLE`、`generateSessionTitleIfNeeded`、`webSearchKeyFollowsProvider`、`injectedName`）+ phone 6 个协议函数 + `PtcProcess`，每处都补了契约 KDoc。**切模块逼出一条真 bug**：`SessionExport.kt` 对 `message.reasoning` 的智能转换失效（别的模块的 `public val` 不许智能转换）→ 先取局部变量。用例总数不变：`app` 80/735 + `jobs` 2/27 + `phone` 1/12 + `llm` 2/14 + `ptc` 3/14 + `data` 7/70 = **95 类 / 872 用例**；`:app:lint` 的告警从 62 降到 38（Room/数据代码已经搬出 :app），五个核心模块 lint 全「No issues found」。
- **阶段 7 批 4：`:core:tools`（2026-10 同日）**：30 个文件 `git mv`（index 里逐字节相同：15 源 + 15 测试），`:app` 侧测试从 80/735 降到 65/614，总数仍是 **95 类 / 872 用例**。api/implementation 按「类型是否出现在公开签名上」逐条定：api = `:seam:tool`/`:seam:ptc`/`:seam:exec`/`:core:workspace`/serialization/coroutines，implementation = `:core:data`/`:core:jobs`/`:seam:model`。第三次放宽 internal（共 20 个：llm 2 / data 6 / phone 6 / ptc 1 / tools 5），口径补了一条：**测试搬不动时（跨模块契约只能留在 :app 测）就把符号放宽并写明它是契约面** —— `GrepMatch`/`renderGlobPaths`/`renderGrep` 就是 `SearchWebCardTest` 钉「工具正文 ↔ UI 卡片」这一对契约用的。两次返工的教训：① 测试搬进模块要同时补 `testImplementation(libs.junit)`；② 扫 internal 的第一版正则漏了成员级（类内 `internal fun`）与 `suspend`，`ToolConcurrency.holdingNow()` 就是被漏掉的那个。
- **阶段 7 批 5（收尾）：`:core:agent`（2026-10 同日）**：21 个文件 `git mv`（16 个逐字节相同；5 个差异全是批准过的可见性/注入改动），`:app` 侧降到 56 类 / 532 用例，总数仍是 **95 类 / 872 用例 0 失败**。依赖按公开签名逐条定：api = `:core:llm`/`:core:data`/`:core:tools`/`:core:workspace`/`:seam:model`/`:seam:tool`，implementation = `:core:session`/`:seam:ptc`/`:seam:exec`/`:seam:llm`/coroutines/serialization。**切模块照出一处真的反向依赖**：`AgentLoop.kt` 直接读 `com.adsh.app.BuildConfig.DEBUG`（core → :app）—— 处理同 `LlmClient`：`AgentLoop(..., logDebug)` 由组合根注入宿主值（不开库自己的 buildConfig，理由同 side 变体）。可见性再放宽 4 个（`estimateTokens`、`contextMessageTokens`、`titleNoThink`、`ThinkingWire`），共 24 个。**九个核心包到此全部成模块**，`:app` 只剩 `ui`/`island`/`runtime`/组合根。
- **口径修正：Hilt 的触发条件（2026-10 收尾）**：原写「模块数 ≥8 或引入 WorkManager 时换 Hilt」。切完 9 个 `:core:*`（全仓 16 个 Gradle 工程）之后回头量：装配仍然只是一个 ~40 行的 `AppContainer` + 一个 `ChatViewModelFactory`，而 Hilt 的代价是**每个模块**都跑 KSP 与字节码转换 —— 「模块数」是个错代理。判据改成**装配点 >15 个 / 需要按作用域注入（Activity、Service、Worker）/ 引入 WorkManager**，TECH-STACK、ARCHITECTURE §五、MODULES §七 三处同时改（此前三处口径一致，但指的是错的那个量）。
- **最有价值的踩坑**（详见 `docs/LESSONS.md` 新增三节）：**单元测试通过 ≠ 能出包** —— 模块化引入的变体匹配问题让 `assemble` 失败，而测试 / lint / spotless 全绿。
## 七、逐轮结论（R213–R216）

- **R213（本轮）的装机口径**：改动**只进了源码**，`dist/` 与 GitHub release 仍是 0.2.3 的旧制品；最终装机的是
  `app/build/outputs/apk/release/app-release.apk`（**44,653,034 字节**，同包名同一把签名，`install -r` 直接覆盖，数据不丢）——
  收尾那一轮（job 工具行照 `presentCall` 对齐 + 清掉拼进提示词的开发备注）也并进了这份 release：核对 `versionCode=15 versionName=0.2.3`、
  `run-as` 被拒（非 debuggable）、MainActivity 起得来、`logcat -b crash` 空、截图确认工具行与长回答切块渲染正常。
  真机结论（用户手指）：**快速上下滑动的「卡一下 / 回弹」明显好转**；工具详情卡滚到头之后**能带动会话继续滑**。
  jobs 展开卡的观感等用户下一次有后台任务时再确认。门禁 861 → **867 用例**（新增 `AnswerChunksTest` 6 条）。
- **R214（本轮）**：**只改注释与文档，行为面零改动** —— ① dsh 参考源换成 master 全量源码树（见上）；② 16 处
  `spec-log §x` / `[session]:行号` 引用改指新源码现址（`packages/core/session`、`packages/core/agent-loop`、
  `packages/core/tools/src/ptc.ts`、`packages/llm/llm/src/assembler.ts`）；③ 提示词复查：把 `PromptAssembler.kt` /
  `ToolSdk.kt` 的**全部字符串字面量**抽出来做词法扫描 → 模型可见文本里**没有作者注**（R213 那处是唯一一处）；
  拆句查重复 → **无逐字重复**（命中的 3 组是互斥分支或两个工具的同名参数说明）。装机：**debug 包**（用户点名，
  `app/build/outputs/apk/debug/app-debug.apk` 64,641,269 字节 → 覆盖了 R213 装的那份 release，数据不动）：
  `versionName=0.2.3-debug`、MainActivity 起得来、`logcat -b crash` 空、截图正常；门禁 **868 用例全过**。
  ④ 第 4 点核实（用户截图里那位 agent 的原话「程序里我看不到图，图片是跑完之后作为附件回来的，所以一次视觉迭代固定两轮」）：
  **成立，且与最新 dsh 机制完全相同** —— PTC 子调用返回的图片走 `deferContext`，作为一条 user 消息紧跟那次 run 的
  tool 结果之后（`packages/core/tools/src/ptc.ts`，dsh 自己的 `run_code` SDK 文案也是这么写的），所以「产出图的那一轮
  看不到图、必须等下一轮」两边都成立，不是 ADSH 的缺陷 → 用户口径「一样的话那就不动了」，代码未动。
  **顺带核实**：那位 agent 提的优化方向「希望一次提交多张图，而不是每张一次 read_image」**现在就已经支持** ——
  一个程序里 `read_image` 调 N 次，N 张图会在同一条 deferred user 消息里一起回来（`AgentLoop.kt` 从子调用轨迹
  `subCalls.flatMap { it.images }` 收图片；`SubCallTrimmer` 的 60 条上限只管**落库的轨迹**，不碰图片）；
  每张的上限仍是 dsh 那两个（640k 像素 / 1 MiB）。
- **R215（本轮）**：**滚动卡顿对齐 dsh + 三处修复（只改源码）**。用户口径：① 滚到**两轮交界处**总是卡；
  ② 展开 run-code 详情也有渲染代价；③ 思考**收起时**的滚动展示落后 dsh（追问：滚动时那一行会闪 / 跳 / 高度变一下）。
  **dsh 侧的事实**（master 源码，`packages/client/ui-chat`）：会话**从不做 DOM 虚拟化**（`ChatView.tsx` 全量渲染），
  靠**宿主分页**（`api/session-controller` 的 `PAGE_MESSAGES=50 / maxMessages=500 / turnWindow`）+ **每行一把 key 的脏订阅**
  （`chat-snapshot-builder.publish()` 只推脏 key）+ memo + 稳定 key 扛长会话；两轮之间**没有任何分割元素**（只有 `margin-top`
  变量与零高度占位），过程行固定 33px（边框含在盒内）、动作行 28px、hover 只改 opacity；**收起态思考行 = 24px 定高 +
  `contain: size layout`**，切换只影响展开方向；动画一律关在小盒子里（全仓 `will-change` 2 处、`contain` 4 处；没有
  `content-visibility` / `translateZ`）。**结论：dsh 滚动时不需要重建任何东西；ADSH 的 LazyColumn 每滚回一行都要重新组合 +
  重新测量文本**，交界处一次进出四五种行 ⇒ 手感最差。
  **改动**：① LazyColumn 补 **`contentType`**（`ChatDecisions.chatItemType`，11 类）—— 之前所有行共用一个类型，交界处那几种行
  互相顶替槽位、整棵子树重建；② 运行扫光**只在绘制相位读动画值**（`RunningSweep` / `DshStateDot`）—— 之前每帧重组整行，
  dsh 的同类动画只动合成层；③ 扫光相位**只由墙钟算**（`sweepPhaseAt`），修掉「行滚出视口再回来时动画从 0 重来、偏移已按
  墙钟走远」的**跳相**（用户报的「滚动时那一行会闪 / 跳」）；④ 核对展开体上限，与 dsh 一致（代码块 260dp / ioCard 150 /
  终端 224 / 系统提示词 141，都带内部滚动）。
  **测量与判据**：合成的 `input swipe` **复现不了**用户手指的卡顿（慢速连续拖 5 次：1742 帧 / 0 Janky / p99 22ms；快速 fling：
  99 分位 20ms / 2 Janky）⇒ 手感仍以用户手指为准；`parseMarkdown` 基准（JVM 单测）**320 字 62µs / 4KB 84µs** —— 慢的不是
  Markdown 解析，**别再去缓存它**；本机 atrace 文本 trace 会丢事件（B/E 栈失衡 ⇒ 帧时长不可信），别拿它当验收。
  装机：debug 包 66,164,054 字节（`versionName=0.2.3-debug`）。
- **R216（本轮）**：**清死代码 + 构建 release 装机**。`scripts/deadcode.py` 两档阈值（默认 / `--min-confidence 20`）都是 **0 条**，
  baseline 里那 5 条豁免逐条复核过（都是「测试口」且理由成立，不动）；**真正翻出来的是 3 处没用的 import**
  （`ChatDecisions.kt` 的 `getValue`/`setValue`、`PtcChannelTest.kt` 的 `assertFalse`）—— deadcode.py 的判据只覆盖
  **声明**，import 不在里面，所以顺手把这个洞补上：新增 **`scripts/find-unused-imports.py`**（复用 `kt_source` ——
  注释与字符串正文都抹掉再搜名字，`$name` 模板引用保留；getValue/setValue/invoke/plus 这类**运算符与委托名跳过**，
  它们的 import 是给 `by` 和运算符糖提供语义的），带 `--fail-on-found` 可当门禁，自带 6 条自检（含「只在字符串/
  注释里出现 = 没用」这两条反例）。**注意别再写 `scripts/remove-unused-imports.py`**（旧笔记里的这个名字在仓库里不存在）。
  装机：**release 包** `app/build/outputs/apk/release/app-release.apk` = **44,652,138 字节**（`versionCode=15` /
  `versionName=0.2.3`，`run-as` 被拒 = 非 debuggable，签名仍是那把 debug 钥匙），覆盖安装、数据没动；冒烟：冷启动落在
  新会话空态 → 抽屉里三个会话与两个工作区都在（`课设内容与要求（分支）/（2 小时）` 等），`logcat -b crash` 空。
  门禁 868 用例全过；`dist/` 与 GitHub release 仍未动。

## 八、阶段索引


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

### 二、之后的功能与实测修复（R100–R213）

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
| R205 | 展示与标题都照 dsh 回正｜撤回 run_code 行的「流式详情」；标题改回「首条消息写兜底 + 主请求之后跑小模型」 |
| R206 | 卡片与文字照 dsh 的令牌回正｜菜单面 = `--dsw-specific-menu`（半透明 + backdrop blur）的合成色 + .5px 描边 + `--dsw-elevation-prominent`；行内代码改圆角底框、紧凑档列表标记改 inherit；`log` 别名 |
| R207 | 「应用缓存很少」核查 = 正常（磁盘缓存只有 apt partial，热缓存全在内存）；清掉残留的 `\$TMPDIR` 目录；版本 0.2.3 / code 15，release 装机 |
| R208 | 发版 0.2.3：本地压成单提交快照（旧 tag 全清）、API 推源码、`gh release create v0.2.3-20261011`、README 链接更新；整理工作区 |
| R209 | 指令栏改**同层布局**（键盘联动不再卡）｜自建 TextToolbar 拦掉「只有全选」的残留栏｜工具详情卡片圆角照 dsh 回到 16（12 是 radius-md）｜预览补齐 .docx/.xlsx/.pptx/.csv/.tsv（OOXML 就地解析）｜装 debug 包 |
| R210 | 指令栏三处收尾｜「+」菜单改成一次性（草稿一动就作废，删回空不再自己回来）｜切会话清草稿（dsh 的输入框状态挂在会话上）｜指令栏改成**不占布局**（报 0 高、画到输入框上方）：它占布局时会把输入卡片自己的高度吃光，键盘弹起后按钮行与下方胶囊被压成 0 高消失｜Office 预览改**按内容认格式** + Word 表格照版面还原（tblGrid 列宽比例 / gridSpan+vMerge 合并 / jc+sz+b / 格子里的图）｜装 debug 包 |
| R211 | 当天改动的一次熵减（不改行为）｜OfficePreview.kt 拆成 **OfficeRead.kt（就地解析）/ OfficePreview.kt（渲染）**｜图片解码收口：字节那一路进 ImageDecode.kt，五处手写的 produceState+IO+缓存 收成 rememberImage / rememberImageBytes｜指令面板的「不占布局」改由组件自带（调用方漏传就重犯旧 bug）｜网格计划用具名 PlacedCell（不再 Triple）｜面板最小高度 160→96dp（它现在是同层卡片，下限大过可用空间就会顶出屏幕上沿）｜清掉过时注释与失效 import｜**菜单卡圆角统一到 16**（DshMenuCard 默认 12→16 = --dsw-radius-lg，12 处调用点跟着走；用户口径「浮层圆角统一为 dsh 的 16」）｜死代码扫描 0 条（阈值 40 与放宽到 20 都 0）后**构建 release 装机** |
| R212 | 快照 + 整理：`git add -A && git commit`（不发版、不动远端）；清 `app/build/`（640MB）/`build/`/`app/.cxx/`/`.kotlin/`/`scripts/__pycache__/` 与 `D:\tmp\adsh-recon`；HANDOFF 收束（指令栏那条经验按现在的实现重写、菜单圆角补上 16 的口径、用例数 858→861、解析器位置改指 OfficeRead.kt、轮次行排序） |
| R213 | 打磨三件（**只改源码、未发版**）｜① 长回答**切块**：一整条回答原来是一个 LazyColumn item，它进视口那一帧要一次性组合 + 测量 + 首绘上百个文本节点 —— 真机 atrace 实测 `Compose:recompose` 18ms + 文本测量 ~30ms + `AndroidOwner:draw` 45ms ≈ **95ms**（60Hz 预算 16.7ms），表现为「滑到正文底部卡一下、位置瞬移 400~500px」；`answerChunks` 只在「上下都是普通段落」的空行上切、前缀稳定，`hasPreceding`/`hasFollowing` 保住切点两侧的边距 ⇒ 渲染与整段一致；② **行内代码底框**改「按布局缓存 + 一行只查两端字符框」：`drawBehind` 每次重录制都会跑，逐字符 `getBoundingBox` 一屏要 ~100ms（关掉它、同一协议下 5 次停顿→0 次）；③ jobs 展开的输出区换成 dsh 的 **TerminalBlock 任务面板变体**（12px 沟槽、不画状态点与收尾胶囊、输出上限 288px、复制命令行、无输出写「（无输出）」）；④ 取消 **ScrollEdgeEater**（六处用法 + 连接本体）：卡片滚到头之后恢复 dsh / 浏览器的滚动链，用户第 213 轮点名；⑤ 对话里的 job 工具行照 dsh 的 `presentCall` 对齐：`job_list` / `job_output` / `job_kill` 各有标题（列出后台任务 / 读取任务输出 / 停止后台任务），**摘要与展开体只取 `job_id`**（不铺参数 JSON 原文；`job_list` 没有 rawInput ⇒ 没有输入段）；⑥ 清掉拼进提示词的一句开发备注（env 快照里混进过「(第 186 轮删掉…：dsh 的 present 不做这个判定)」，用户在「上下文注入」卡片里直接看到了）—— 复查 `PromptAssembler.kt` / `ToolSdk.kt` 的字符串字面量：中文 0 处 |
| R214 | 参考源更新 + 提示词复查（**只改源码、未发版**）｜① dsh 参考源码换成 GitHub master 的**全量源码树**（`gh api repos/deepseek-ai/deepseek-harness/tarball/master` → 34MB tar，commit 5719020 / 2026-10-14，16122 个文件 / 160MB，仍在 `D:\tmpdsh-work`）：旧的 `gh-src` / `src` 两套零散拷贝与手写规格 `spec-ui.md` / `spec-log.md` 按用户口径**整目录删掉重建**；源码里 16 处 `spec-log §x` / `[session]:行号` 引用改指新源码现址（`packages/core/session`、`packages/core/agent-loop`、`packages/core/tools/src/ptc.ts`、`packages/llm/llm/src/assembler.ts`），一个悬空引用都不留｜② 提示词复查：把 `PromptAssembler.kt` / `ToolSdk.kt` 的**全部字符串字面量**抽出来做词法扫描（CJK / 「第 N 轮」/ `dsh` / 旧实现 / TODO 这类开发者标记）→ 模型可见文本里**没有作者注**（R213 那句是唯一一处，已清）；拆句查重复 → **无逐字重复**（命中 3 组分别是互斥分支、与两个工具的同名参数说明）｜③ 第 4 点「一次视觉迭代固定两轮」核实**成立、且与最新 dsh 机制完全相同**（PTC 子调用返回的图片走 `deferContext`，作为一条 user 消息紧跟那次 run 的 tool 结果之后，模型下一步看到；dsh 自己的 SDK 文案也是这么写的）→ 用户口径「一样的话那就不动了」，不改代码 |
| R215 | 滚动卡顿对齐 dsh + 三处修复（**只改源码**）｜查出 dsh 的会话**不做 DOM 虚拟化**（全量渲染 + 宿主分页 + 每行脏订阅 + memo），两轮之间**没有分割元素**（只有 margin-top 与零高度占位），收起态思考行 **24px 定高 + `contain: size layout`**，动画只关在小盒子里 ⇒ ADSH 的 LazyColumn「滚回即重建」是结构性差异；改动：① `contentType`（11 类）② 扫光/状态点改**只在绘制相位读动画值** ③ 扫光相位改**纯墙钟**（修行滚出再回来时的跳相）④ 核对展开体上限与 dsh 一致（260/150/224/141）｜`parseMarkdown` 基准 62µs/320 字 ⇒ 不是解析的锅；合成 swipe 复现不了手指卡顿（p99 20~22ms） |
| R216 | 清死代码 + release 装机（**只改源码**）｜`deadcode.py` 两档阈值 0 条、baseline 5 条豁免逐条复核；补掉它管不到的那一类：**未使用 import** 3 处（`ChatDecisions` 的 getValue/setValue、`PtcChannelTest` 的 assertFalse），并新增 `scripts/find-unused-imports.py`（复用 kt_source，运算符/委托名跳过，自带自检，可 `--fail-on-found`）｜release 44,652,138 字节装机、抽屉里会话与工作区齐全、crash 缓冲空 |
| R220 | 移除老式 .doc 浏览 + 清理死代码（**只改源码**）｜① 按用户口径「docx 没问题了，.doc 浏览先去掉」：删掉 WordLegacy.kt（CFB / PAPX / CHPX / SEPX / TDefTable 全量解析）、WordLegacyTest 与 preview.doc 夹具（THIRD_PARTY_NOTICES 同步删行）；OfficePreview 去掉 LegacyWordPreview 与 isLegacyWord 分支，.doc / .xls / .ppt 统一落「不支持」屏（说明需要 Office 引擎 + 「用其他应用打开」）。② 死代码清理：WordBody 去掉只为老式 .doc 存在的 notice / openWithPath 参数与顶部横幅；删掉解析了却从未使用的 WordPage.heightTw 与 WordCell.widthTw；WordTable.widthTw（w:tblW）改为表格宽度真值（之前只解析不用，现在参与「自然宽度」判断）；删掉失效 import。③ 新回归 DocUnsupportedTest：.doc 走「不支持」屏（说明里点明 .doc/.xls/.ppt 需要 Office 引擎）+ 可用其他应用打开。④ 门禁：全模块测试 / lint / assembleDebug / spotless + Python 七条全绿，deadcode 0 条、未使用 import 0 处 |
| R219 | 文件浏览：Word 预览对齐 PDF（**只改源码**）｜① **A4 版心缩放**：.docx 读 w:sectPr 的页宽 / 页高 / 页边距（老式 .doc 读 SEPX），预览按页宽缩到屏幕宽 —— 字号、缩进、表格列宽、图片尺寸都乘同一系数，横版工艺卡也不再左右滚动；白纸底 + 页边距，与 PDF 预览同比例。② **OMML 公式**：新增 WordMath.kt（m:oMath / m:oMathPara → LaTeX：分式 / 上下标 / 根号 / 定界符 / 大运算符 / 矩阵 / 等式组 / 直立体文字；认不出的结构按子节点拍平），渲染走聊天同一套 LatexCore（位图缓存），行内公式用 appendInlineContent 占位、独立公式居中成块；w:vertAlign 上下标（x² / H₂O）按 65% 字号 + 基线抬降画。③ **老式 .doc 全量升级**：WordLegacy.kt 从「只提正文」改成读 PAPX（对齐 / 缩进 / 段距 / 行距 / fInTable+fTtp）、CHPX（粗 / 斜 / 下划线 / 字号 / 上下标 / 颜色）、SEPX（页宽页边距）、TDefTable（列宽），表格结构与列宽都还原；兼容 WPS 系「记录前多 1 字节 0x00」的 PAPX 变体；表格按细线画。④ 用例：WordMathTest（OMML 七种结构）+ WordReadTest 加页面设置 / 公式内联 / vertAlign。真机复验：pWPS 的 4 个线能量分式、工艺卡（横版 A4）、老式 .doc（任务书 / 大纲 / 日进度表）都不再横滚。已知边界：老式 .doc 的合并格（TC 标志）与图片 / OLE 对象不还原，模板类表单可能出现空行；⑤ 表格「一行格子多于列数」时按列数截断（真机在模板文档上踩到 ArrayIndexOutOfBounds，measure 阶段列下标越界），回归见 WordTableUiTest |
| R218 | 文件浏览热修（**只改源码**）｜① 真机闪退定位：logcat 里 `IllegalStateException: measure() may not be called multiple times on the same Measurable` —— 表格为了算行高把同一格量了两遍（Compose 一次测量只许量一次，LazyColumn 预取也会触发）。改为**单次测量**：格子只按列宽量内容，行高 / 纵向对齐 / 底纹 / 边框由 `WordTableGeometry` 在测量阶段算好、父层 draw 阶段画（`drawBehind` + 纯数据几何，与 ChatTranscript 的 rowTops 同一套做法）。② 顺带修两个「黑变透明」：`wordColor` 存的是 0xRRGGBB，边框 / 正文字色直接 `Color(it)` 会把 `000000` 当 alpha=0 → 黑边框、黑字全看不见；渲染处补 `0xFF000000 or it`。③ 解析兜底：字号夹 4..96 磅、缩进 / 间距 / 行高 / 列宽 / 列数全部夹进可用范围（生成器写坏的巨大 twip 会把 Compose 约束撑爆）；docDefaults 的段落默认值（pPrDefault）也纳入继承链。④ 新回归：`WordTableUiTest`（Robolectric + Compose：表格放进 LazyColumn，让预取真的跑到）+ `ui/blocks` 的 `robolectric.properties`（sdk=34，不设会按高版本 SDK 起 Robolectric 在 ApplicationSharedMemory 崩）。真机用用户的实际 .docx 复验：工艺卡（15 列 / 22 行 / 纵横向合并 / tcBorders / 内嵌示意图）与评定报告（8 表 469 格）都能打开、滚动不崩 |

| R217 | 文件浏览：Word 渲染 + 老式 .doc（**只改源码**）｜① .docx 解析改成**按 OOXML 摊平继承链**（docDefaults → 段落样式 basedOn → 直接格式）：字号取文档里的真值（w:sz 半磅 → dp）、run 的粗/斜/下划线/删除线/颜色、段落对齐 / 左右缩进 / 首行 / 悬挂缩进 / 段前段后 / 行距、标题档（样式名或 w:outlineLvl）、列表前缀与编号级别缩进 —— 不再一律 14sp + 24sp 行高。② 表格改成**按版面真值画**：列宽取 w:tblGrid（窄表等比撑满、宽表横滚）、w:gridSpan 横向合并、w:vMerge 纵向合并由起始格按行高撑满（自定义 Layout 两遍测量）、w:tblBorders / w:tcBorders 只画文档真有的线（样式名带 Grid 兜底）、w:shd 底纹、w:vAlign、w:tblCellMar 内边距、w:trHeight 行高。③ 老式二进制 .doc：新增 CFB（OLE 复合文档）+ FIB 片段表解析（`WordLegacy.kt`），提取正文（段落 / 单元格制表符 / 域代码跳过），顶部一句口径说明 + 「用其他应用打开」。④ 预览读不了的格式（老式 .xls/.ppt、未知类型）给「用其他应用打开」：FileProvider 受控 URI + ACTION_VIEW（安卓 7 起的标准出路，`file://` 会抛 FileUriExposedException）。⑤ 新用例：`WordReadTest`（现造最小 .docx：样式链 / run 格式 / 表格结构 / 编号）+ `WordLegacyTest`（dsh 的 MIT `preview.doc` 夹具）；`THIRD_PARTY_NOTICES` 记夹具来源 |

