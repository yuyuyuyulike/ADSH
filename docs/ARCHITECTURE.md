# 架构

这份文件写**稳定事实**：分层、边界、硬约束。历史在 CHANGELOG.md，选型理由在 TECH-STACK.md。行数上限 150。

## 一、分层

| 层 | 模块 | 职责 | 不许做什么 |
|---|---|---|---|
| 服务定义 | `:seam` | 纯契约：接口 + 数据类型（六个 `seam.*` 包） | **零应用依赖**；不许依赖它的消费者 |
| 平台 | `:platform:termux` | 平台实现：Termux 进程 / PTY / 原生 shim / bootstrap 安装 + `:ptc` 独立进程与 QuickJS 运行时 | 不许知道业务策略（只认 `:seam` 端口） |
| 核心 | `:core:*` | agent 循环、工具、LLM、持久化、后台任务 | 不许 import `ui` 或 `：app` |
| 界面基础 | `:ui:blocks` | 设计系统 + 渲染基础（调色板、主题、图标、间距、Markdown / LaTeX / 代码块 / 工具结果块 / 文件预览） | 不许知道任何 feature 或 `:core:*` 业务 |
| 功能 | `:feature:chat` | 对话界面（会话宿主 / 会话屏 / 输入区 / 轮次渲染） | 不许知道 `:app` 组合根与平台实现 |
| 组合 | `:app` | 单 Activity、导航/叠层、`AppContainer` 装配 | 不放业务逻辑 |

## 二、能力缝（capability seam）

照参考项目 dsh 的定义（`D:\tmpdsh-work\docs\architecture.md:132`）：一个能力有**三个角色** —— 服务定义（接口）、服务提供者（实现）、消费者（调用方）。三者分属不同模块，且**定义不许反向依赖消费者**。

| 能力 | 定义 | 提供者 | 消费者 | 现状 |
|---|---|---|---|---|
| 工具运行时 | `:seam`（`seam.tool`） | `:core:tools` | `:core:agent` | 端口与实现分属模块，边界由编译器守 |
| PTC / run_code | `:seam`（`seam.ptc`） | `:platform:termux`（`platform.ptc` / QuickJS） | `RunCodeTool` | **端口已立**（`PtcRunner`，装配在组合根 `ToolingHost`）：消费者不再直接依赖 QuickJS |
| Shell 执行 | `:seam`（`seam.exec`） | `:platform:termux` | `:core:tools` | **端口已立**（`Shell` / `ShellProcess`）；提供者只依赖 `:seam`（分层门禁盯着） |
| LLM | `:seam`（`seam.llm`） | `:core:data`（`core.llm`） | `:core:agent` | 方向已经对；`TurnLlm` / `TurnSettings` 是它的窄端口 |
| 附件与文件改动 | `:seam`（`seam.model`） | — | `:core:data`、`:core:tools`、`:core:agent` | 纯数据类型 |

## 三、依赖规则

- 允许：`app → 任意`；`feature → ui:blocks / seam / core`；`provider → 自己的 seam`；`core → seam`。
- 禁止：`seam → 任何应用代码`；`core | ui → app`；`platform → core 上层`；**模块级循环**（Gradle 会直接报错）。
- 门禁：**包**级由 `scripts/check-package-cycles.py`（Tarjan + `scripts/layering-rules.json` 的分层规则）盯；**模块**级先是构建脚本本身（Gradle 编不过就是违规），模块图新鲜度门禁（`docs/module-graph.md`）见 `docs/MODULES.md`。

## 四、能力缝落点与循环（2026-10 实测）

扫描口径：**剥掉注释与字符串**，再按「声明的包名 + 最长前缀」解析每一处 `com.adsh.app.*`。
两个坑都栽过：只看 `import` 行会**漏掉内联全限定名**（`List<com.adsh.app.core.agent.ToolImage>`）；把注释也算进去会**造假边**（KDoc 里的 `[com.adsh.app.core.agent.AgentLoop]` 不是依赖）。改依赖前照这个口径重扫，别信印象。

**当前 0 环。** 2026-10 解掉的这两个是回归基线（门禁用 `--strict`，再来就得红）：

| 强连通分量 | 成员 |
|---|---|
| **核心环**（已解） | `core.agent` ↔ `core.data` ↔ `core.llm` ↔ `core.ptc` ↔ `core.session` ↔ `core.tools` ↔ `core.workspace` ↔ `runtime.termux` |
| **界面环**（已解） | `island` ↔ `ui` ↔ `ui.panels` —— island 定义 `IslandSnapshot` 端口由 `ChatUiState` 实现；基础件下沉 `ui/kit` |

（根包 `com.adsh.app` 只放 `MainActivity` / `AdshApp` / `AppContainer` 与生成的 `R`、`BuildConfig`，不算架构节点：**别的类放进根包会被 `ui` 引成包循环** —— `ToolingHost` 因此落在 `com.adsh.app.host`。）

### 核心环的 8 条回边与它们的落点（已落地）

落点列写包名；五个 `seam.*` 包都在 `:seam` 里。

| 回边 | 被引用的符号 | 落点 |
|---|---|---|
| `core.data → core.agent` | `encodeAttachments`、`describeAttachments` | `seam.model` |
| `core.llm → core.data` | `ApiProtocol`、`BuiltInProviders.DEEPSEEK_ID/NAME`（`ChatModels` 的默认参数值） | `seam.llm` |
| `core.session → core.ptc` | `SubCall` | `seam.ptc` |
| `core.ptc → core.agent` | `ToolImage`、`PresentedFile` | `seam.tool` |
| `core.tools → core.agent` | `ToolImage`、`PresentedFile`、`NO_OUTPUT`、图片信封与上限、`filePolicyOf`、`captureBefore` | `seam.tool` / `seam.model` |
| `core.tools → core.ptc` | `PtcProcess`、`PtcToolRunner`、`QuickJsRuntime` —— **消费者直接依赖提供者** | `seam.ptc` 定义 `PtcRunner` 端口，提供者由组合根注入 |
| `runtime.termux → core.tools` | `Escalation`、`FULL_ACCESS`（平台反向依赖业务策略） | `seam.exec` |
| `runtime.termux → core.jobs` | `Spill` | `seam.exec` |

砍完剩下的全部单向：`agent → {data, llm, tools, ptc, session, workspace}`、`tools → {data, jobs, workspace}`、`ui → {core.*, island, ui.theme, ui.panels}`。

**两个环都已归零**（`check-package-cycles.py` 报 0）。

### 已立起的能力缝

| 包 | 内容 |
|---|---|
| `seam.tool` | `ToolImage`、`PresentedFile`、`RequestImages`、`NO_OUTPUT` |
| `seam.ptc` | `SubCall`、`CodeRunResult`、`PtcRunner`（端口）、PTC wire 助手 |
| `seam.exec` | `Escalation`、`Permissions`、`Spill` |
| `seam.llm` | `ApiProtocol`、DeepSeek 常量 |
| `seam.model` | `Attachments`、`FileChanges` |

消费者与提供者由此分开：`RunCodeTool`（core.tools）只认 `PtcRunner`，`PtcProcess`（platform.ptc）实现它，装配在组合根 `ToolingHost`。

## 五、装配与数据流

- 组合根：`AppContainer`（`AdshApp.container`，懒建）是**有状态对象**的唯一创建点，`ChatViewModel` 全部构造注入（`ChatViewModelFactory`）。工具上下文由 `ToolingHost` 装配（全局项在组合根读，每轮变量由界面传）；设置页「获取模型」的一次性 `LlmClient` 也走 `AppContainer.newLlmClient()`。
- 数据流：状态（不可变 data class）→ 事件 → 新状态。业务判断抽成纯函数并配单测（现状已经这样做，保持）。
- 触发条件：装配点 >15 个 / 需要按作用域注入 / 引入 WorkManager 时换 Hilt（判据与理由见 `docs/TECH-STACK.md`）。

## 六、硬约束清单（改代码前逐条过）

平台与构建：

1. `targetSdk = 28`、包名 `com.termux` —— 内嵌 Termux 能直接 exec 自己数据目录里文件的唯一前提；release 的 R8 `minify`+`shrinkResources` 保持 true；lint 的 `ExpiredTargetSdkVersion` 保持关闭。
2. 写围栏、`/tmp` 映射、脚本 shebang 自愈都在 LD_PRELOAD shim（`fence.c`）；**read-only 也要挂围栏**。改完跑 `scripts/fence-selftest.sh`。
3. 内嵌环境排障用 `adsh-env-check`（**别用 `run-as` 测 exec**）；改 `$PREFIX/bin` 脚本后跑 `python3 scripts/check-env-scripts.py`。

提示词（模型直接可见）：

4. 系统提示词必须**静态**：`PromptAssembler.STATIC_SYSTEM_PROMPT` 里不许出现模型名、工作区路径、策略、计划；`ToolSdk.section()` 与 `WORKING_RULES` 保持无参数。
5. 动态只允许三块：`sandbox:policy`、`env:android-termux`、`plan:policy`（仅计划模式开着时），走 `context` 行且内容一变**追加**新快照；`form = snapshot` 的 `context` 行要作为 **user 消息**发出。决策留在 `ContextLedger`（纯函数），别挪回 `AgentLoop`。
6. 提示词**不许重复**（`PromptAssemblerTest` 盯着）；工具说明只在 `ToolSdk.specs` 写一次；`PTC_ONLY` 两句都要在；**只有 `run_code` 能被模型直接调用**。
7. `run_code` 失败必须带「已跑成的子调用回执」（`RunCodeTool.completedDigest`）；SDK 段那句「失败后只补发剩下的步骤」与它是一对，改一处要改另一处。
8. 提示词里只写「App 本体就有」的东西；设备上额外装的、某台机器量出来的例子、诊断脚本都不进提示词。
9. **开发备注一个字都不许拼进提示词串** —— 模型与用户都会直接看到。

运行时：

10. `QuickJS` 栈预算 `256 * 1024` **不许调大**：账比 pthread 栈（约 1 MB）大就永远不会先抛 `stack overflow`，递归吃穿栈 = **进程级 SIGSEGV**。
11. 后台任务唯一真相是 `com.adsh.app.core.jobs.Jobs`；`Jobs.settle` 的 awaited 判据 `waiters > 0 || held` 不许删；通知两种形态不许合并；**杀树根 pid 来自子 shell 自己写的标记文件**（安卓运行时没有 `Process.pid()`）。**只许对解析出来的正 pid 发信号**：根 pid 拿不到（标记还是空串、或进程已退出标记已被删）时只能杀直接子进程 —— 把 `-1` 交给 `kill(2)` 是「同 uid 所有进程」的广播，会顺手杀掉 `:ptc` 运行时（回归：`RuntimeDecisionsTest` 的「根 pid 非法：空表」）。
12. 注入行（插话/任务通知）只在**认领时**落库：`enqueueInjected` 只入内存队列，`claimInjected` 在 AgentLoop 每步开头写库。**别加本地回显**。
13. 花名册 `Jobs.roster` 走 100ms 合并推送；结算通知必须走合批车道（一批一次落库 + 一次 `_state.update`）。
14. 会话里一行消息不能超过 **2 MB**（`CursorWindow`），读会话一律走 `readMessages`；数据库迁移**不能断链**（断了会掉进 `fallbackToDestructiveMigration` 清库）。

界面：

15. 自动滚动只有一个「跟随意图」（`AutoScroll.kt` 的 `ScrollFollow`）；**25dp 迟滞是 dsh 口径，别动**（它只判「读者还在不在底部」——读者往上滑哪怕几个像素也立刻交出跟随，不受迟滞保护）；贴底只由「变化」触发，不许回到「每重组一次贴一次底」。**自动贴底有生命周期**（`ChatScrollState.autoScroll`）：只在这一轮进行期间 + 结束那一拍的收尾贴底内生效，会话结束即停（图片解码 / 输入区高度变化不再把读者拽回底部）。
16. 抽屉收起必须等「内容换完」（`settleDrawer` 先 `withFrameNanos` 等两帧，且必须在同一个 job 里）；`switchConversation` 是 `suspend` 的，等内容就位再收起。
17. 排版不许吃 Material3 默认字距：`ui/theme/Theme.kt` 的 `AdshTypography` **别删**，别在调用点逐个补 `letterSpacing`。
18. 长回答必须切块（`answerChunks`）：切点只落在「上下都是普通段落」的空行，**前缀稳定**；短回答仍走 `TurnEntry` 一行。
19. 行内代码底框只能走缓存（`TextLayoutHolder`）；卡片不许吃滚动链（别再挂 `nestedScroll` 拦截件）。
20. 长按选中正文不许碰焦点：只在**短按**时 `clearFocus()`，判据 `shouldClearComposerFocus`。
21. 交付物落 `deliverablesJson`、本轮改动落 `changesJson`；轮尾**只有一张**文件卡片（判据是纯函数 `mergeTurnFiles`），只在「已结束」的轮次上画。

22. 任务弹层那行的排版不许动：外层 Row 里「命令行 + 状态」必须**一起**待在一层带 `weight` 的 Row 里（时长与倒角在外层先占位），运行中行的第二行同理（`weight(1f, fill = false)`）。状态放回外层会按内容吃光宽度、把时长压成 0 宽。
23. jobs 展开的输出卡 = dsh 的 `RailTerminalBlock` 任务面板变体（`gutter = TERMINAL_GUTTER_PANEL`、`runStateDot = false`、`outputMaxHeight = TERMINAL_OUTPUT_MAX_HEIGHT_PANEL`、`settlePill = false`、`copyText = job.label`、`noOutputText = "（无输出）"`）——别在 `JobsPanel` 里另写一套输出框。

24. `MainActivity` 必须 `launchMode="singleTask"`：它守的是「**一进程只有一个 `ChatViewModel`**」这条不变量 —— `liveRuns` / `turnsInFlight` / `Jobs` 与岛的订阅都按实例私有，多一份实例就多一个会话宿主：新实例看不到在跑的那一轮（输入框变灰、正文停在开实例那一刻，那一轮还在旧实例里跑完），同一条结算通知还会让两份实例**各给同一条会话开一轮**。回归：`MainActivityLaunchModeTest`。
25. 返回键**不许退出 App**（`MainActivity` 的 `OnBackPressedCallback` 走 `moveTaskToBack`）：会话宿主是 Activity 作用域的，`finish()` → `onCleared` → `Jobs.cancelAll` 会掐掉正在跑的那一轮与它的后台任务。抽屉 / 浮层 / 菜单的返回键处理登记得更晚，仍优先。

26. **会话屏层模型**：会话流**一次性组合、常驻**（dsh 的 DOM 语义：纵向堆叠 + `verticalScroll`，不做虚拟化）—— 滚动容器的 `maxValue` 就是真实内容高度，右侧滚动条比例直接取它，内容不变时长度恒定；**别改回 LazyColumn**（虚拟化只能估行高：总高度随「哪几行被量到」变化，滚动条长度会跳、上滚还会重建行）。footer（输入区整摞）仍叠在滚动容器上（同层、不占高度）：底部内边距 = footer 实测高度 + 14dp，footer 顶部 36dp 渐变遮罩（最右 8dp 留给贴边滚动条）。**别把 footer 改回「Column 里占布局」** —— 消息会在输入框上方被硬线截断，看不到「从输入框下滚过」的淡出。
    真机卡顿的三条硬约束（都在 `ChatTranscript` / `ChatScreen` 的注释里）：

    ① **离屏行不画**：可见性按「内容坐标 + `ScrollState.value`」判定；每行的 y 在**布局阶段**记进纯数组（`CulledColumn`），**不许**用 `onGloballyPositioned` —— 滚动 / 键盘动画时全局坐标每帧都变，回调会把每一行都叫一遍再写状态（100+ 行时主线程 40ms/帧）。
    ② **同帧贴底只做绘制位移**（draw 阶段 translate）；**不许**给整块内容挂 `graphicsLayer`（按内容总高分配纹理，几十屏的会话直接卡死），也不许用 `offset`（会让行的内容坐标带偏、剔除算错）。
    ③ **IME 高度不许在组合期读**（每帧重组整页，会话越长越卡）；视觉贴底靠 ②，`ScrollState` 由 `LaunchedEffect` 里的 insets 观察者补一帧。
    ④ **子行必须按自身高度测量**：`CulledColumn` 量行前把 `minHeight` 归零 —— 外层 `fillMaxSize` 会把视口高度写成 `minHeight`，`verticalScroll` 只放开 `maxHeight`，原样透传会把每一行撑成一屏（行与行之间几百 dp 空白）。
    ⑤ **滚动内容必须按自身高度测量**：`CulledColumn` 的外层不许再挂 `fillMaxSize`（会被视口 `minHeight` 撑高并**垂直居中**）；`verticalScroll` 之后必须加 `wrapContentHeight(Alignment.Top, unbounded = true)`。否则短会话的内容高度恒等于视口、`maxValue` / `contentHeightPx` 全部失真 —— 表现就是键盘收起后内容整体下移、上方空出一块。
27. **工具行的 preparing 阶段照 dsh**：标题先出现；右侧摘要从流式参数里的 `description` **实时长**（`PartialArguments.text` 语义，见 `preparingSummaryOf` / `partialStringValue`）；文件类工具要 `path/file_path` **完整**才显示；这一行不可展开、没有子调用，直到 `tool/call` 落日志（`expandable = state !== 'preparing'`）。运行态的流光 = 文字上的颜色带（`DshTextShimmer`，1.5s / 延迟 0.3s），**不是**整行扫过一道底色。
28. **Markdown 排版与流式照 dsh**：数值以 dsh 的 `MarkdownText.module.css` / `CodeCard.module.css` / `MessageItem.module.css` 为准（段 16、列表 16/6、表格 13/22 且窄表铺满列宽 / 宽表横滚、行内代码底框 = 代码字体行高、代码块 card 11/19 + 默认折行、用户气泡 14/22 + 圆角 20 + 最大宽 0.702×列宽、气泡正文是纯文本）。流式**收到什么画什么**：33ms 只做重绘节流，`StreamReveal` 不做逐字平滑，只有「变短 / 换段」立即跟上（否则会重复画一段正文）。
29. **会话流里可展开的大文本必须切成行**：可见性剔除（`CulledRow`）是行粒度的 —— 一行一整段 Markdown 会让滚动 / 键盘 / 抽屉每帧整段重绘（真机掉帧就是这么来的）。思考正文走 `reasoningChunks`：空行切、不切围栏、前缀稳定；纯段落超长时按行边界兜底（两侧边距归零）。切点间距一律走 `hasPreceding` / `hasFollowing`。

工具语义（PTC）：

30. **工具调度器一轮一份**：`ToolConcurrency` 由 `PtcToolRunner` 每轮新建（dsh 的 per-run scheduler），**不是**全进程单例 —— 超时 / 崩溃时在途调用可能卡在非协作取消里，共用一个实例会让下一次 `run_code` 的每条调用都排在它后面（真机事故：一次 edit 卡死之后连参数校验都进不去）。
31. **`edit` 按 dsh 归一化行尾**：内容、`old_string`、`new_string` 都先 CRLF→LF 再匹配，写回时恢复原文件的行尾风格；匹配用单趟 `countOccurrences` + `String.replace`，**不许用 `Regex` 物化匹配列表**（5 MB 单行 + 百万次匹配会 OOM，进而挂死工具网关）。`write`/`edit` 的正文字节必须能严格 UTF-8 编码（孤立代理项直接拒绝，不许静默写成 `?`）。

32. **会话流的每帧开销不随历史行数增长**：行级剔除只读一个派生窗口（`CULL_STEP_PX` 台阶化的滚动位置 + 取整视口 + 贴底补偿），滚动 / 键盘动画的中间帧不再逐行读 `ScrollState`（旧实现每帧把 N 行全部标脏）；滚动条只在**绘制阶段**读滚动状态，thumb 几何在按下那一刻现算。抽屉的 1.5dp 景深用主内容图层的 `RenderEffect`（API 31+；旧系统退化成轻暗化），**不许**换回 haze 覆面层 —— 实时模糊覆层每帧要重渲主内容，是抽屉动画掉帧的根因。

## 七、构建输入（不要删）

`app/src/main/assets/bootstrap/usr.zip`（32 MB）、`app/src/main/execLibs/arm64-v8a/`（287 个 ELF，**必须原样复制**——它们从 nativeLibraryDir 执行，改文本会把前缀改成不存在的路径）、`platform/termux/src/main/cpp/`（围栏 / PTY 原生库，产出 `libadshfence.so` / `libadshpty.so`）、`assets-src/`、`local.properties`、`keystore.properties`、`keystore/adsh-side.jks`、`gradle/`。
