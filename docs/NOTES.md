# ADSH 设计笔记

> **唯一一份「为什么」的记录。** 第 82 轮把原先 6600 多行的逐轮流水账压成了
> 「轮次索引 + 按主题的硬结论」；操作步骤看 [HANDOFF.md](../HANDOFF.md)，功能说明看 [README.md](../README.md)。
> 这里只留**改了会出事**的东西（平台约束、数据不变量、提示词纪律、界面不变量）与偏离 dsh 的地方。

## 一、轮次索引（第 1～82 轮，一行一轮）

第 1～21 轮：骨架、输入框、弹层、上下文条、Markdown / LaTeX 的首批对齐（详细记录已随第 82 轮精简删除）。

- 22 附件内容块、表格不截断、回到底部、设置分栏 · 23 权限持久化回填、会话搜索、模型菜单静态切换
- 24 模型设置照搬 dsh（多提供方 + 自定义） · 25 模型弹窗长度、删会话连带清附件、左滑崩溃
- 26 输入框上方横窗与弹窗、完全权限确认、设置-模型 · 27 goal 跑起来、`/plan` 与 `/compact` 按 dsh 重做
- 28 目标轮注入不该显示成「用户消息」 · 29 删掉 goal · 30 全新安装「一发消息就卡死」（主线程解压 bootstrap）
- 31 查会话轨迹 ＋ 第二处「卡死」 · 32 真凶：`buildMessages` 被删空（死循环 + 空请求）
- 33 工作区文件 / 导入 / 导出全废 ＋ 图片被吞 · 34 工具刷出超大行 → 一进会话就闪退
- 35 并行工具上限失效（一瞬间 30 个）＋ release 包 · 36 文件预览授权、工具行「闪一下跳位」、图片滑动卡顿
- 37 封住闪退的第四条读路径 + 死代码清理 · 38 按 dsh PTC 源码重做工具 + 工具行闪一下 + 子调用竖线
- 39 glob/grep 语义 + 弹出与终端动画 · 40 终端 / Agent 设置对齐 + 并行闸门可重入
- 41 附件（图片判定 / 尺寸 / 路径）+ 供应方目录 · 42 两个收尾瑕疵 · 43 deepseek 模型目录按官网订正
- 45 写围栏 + 审批卡 + 键盘跟随 + 终端渲染 · 46 方案 A 的死代码清理（用户点名：不能暴力清理）
- 47 硬链接为什么失败、怎么让它能用 · 48 静态提示词按 dsh ptc 现文订正（出 release）
- 49 会话滚动「真底部」判据、触摸语义 · 50 终端回车变大、退出终端卡顿、设置卡片展开态丢失
- 51 推理等级逐模型 + 网页搜索后端可换（Exa） · 52 非 DeepSeek 模型用不了 run_code（工具调用没有 id）
- 53 流式正文「从下面闪一下再跳」（采样点写在了列表里面） · 54 死代码清理（第五次快照）
- 55 release + 第六次快照 · 56 图片传输通道 + 三项围栏/噪音修正
- 57 read_image 行展开即图 + 模型级识图开关 + LaTeX 渲染 · 58 第七次快照 + 死代码清理
- 59 提示词三处重复（工作区路径 / 策略拒绝 / 临时目录） · 60 按《提示词审查报告》重写提示词
- 61 提示词逐字重复（用户点名）+ 版本 0.1.1 · 62 按《提示词重复审计报告》逐条去重 + 版本 0.1.2
- 63 正式出 0.1.1（第九次快照 + GitHub 推送与 release） · 64 完全权限下 shell 什么都写不了（围栏门闸）
- 65 死代码清理 + 出 0.1.2（第十次快照 + Release + 工作区清理）
- 66 glibc/grun 跑不动、read 吐二进制乱码、chmod 静默改写、权限切换的注入
- 67 脚本自愈 —— targetSdk 37 的 W^X 让 npm / pip / dpkg 维护脚本全失效
- 68 死代码清理 + 出 0.1.3 release · 69 插话不再挪走工具行 + run_code 一步一个程序 + 出 0.1.3
- 70 思考行即时收尾 + 自动滚动优先级最低 + 停止不再闪 · 71 键盘跟手 + 任务横窗按会话隔离 + 出 release
- 72 工具行展示一致 + 贴底不再过头 + **exec 根因修复（targetSdk 28）** + 环境自检
- 73 渲染网页 / 截图的可用路径（`adsh-shot`）+ hxn 的两条上游问题
- 74 输入框 ＋ 菜单与上下文圆环、Markdown 看齐 dsh、弹层点别处即关、会话行骨架收敛；**全新安装 bootstrap 必挂**（跨挂载点 + 悬空符号链接）
- 75 用户气泡宽度、上下文图标放大加深、点空白收光标 + 出 release（minify + shrink）
- 76 run_code 第一条子调用压在主行上（Box 叠放）+ 折叠行倒角角度
- 77 运行态只留延迟扫光 + 边缘防误触（新设置项）+ 展开后停自动滚动 + 覆盖层点击穿透
- 78 快照与发版（0.1.3 第十一次快照）+ 记录与产物清理
- 79 同会话目标 goal 与后台任务 jobs（**第 80 轮整份回退**，只把数据库 schema 前滚到 v15）
- 80 回退 goal / jobs + ＋ 菜单看齐 dsh + 权限预设弹层宽度
- 81 工具行「闪烁/撕裂」根因（流式尾巴与刚落库的那一步重复一行）+ run_code 报错定位 + 提示词审查（并发闸门按 dsh 重写成写类独占）
- 82 出 0.1.4（第十二次快照）+ 文档精简 + 工作区清理

## 二、平台与环境（最硬的约束）

- **`targetSdk` 必须保持 28**（唯一前提）：设备据此把应用放进 `untrusted_app_27`，termux-exec 的
  `system_linker_exec` 随之关闭，exec 走直接执行 —— `/proc/self/exe` 正常、静态 PIE 与脚本直接可跑。
  调高会让靠自身路径找资源的程序（Chromium / Electron）、静态二进制、直接 `execve()` 的程序一起坏掉。
  `lint` 的 `ExpiredTargetSdkVersion` 已显式关掉，别当成待修告警。
- **包名必须是 `com.termux`**：Termux 编译期写死的前缀 `/data/data/com.termux/files/usr` 才是真实路径。
  别名 / 等长改写 / dpkg 根树 / chrootless 那一整套在方案 A 下都是 no-op（第 46 轮已删除）。
- **工作区在 FUSE（模拟存储）上**：只能读写普通文件 —— 建不了软链、置不了执行位、chmod 无效、不能执行；
  失败是 **EACCES 而不是沙箱拒绝**（不带 `[sandbox: …]` 标记，换更宽的文件策略也解不开）。
  固定约定：工作区 = 素材入口 + 成品出口；装依赖 / 构建 / git 去 `$ADSH_SCRATCH`（`$HOME/scratch`，f2fs）。
  这条约定的唯一出处是提示词的 `PromptAssembler.androidEnvText()`。
- **没有可写的 `/tmp`**：shim 把 `/tmp/…` 改写到 `$TMPDIR`（`$PREFIX/tmp`）并导出 `TMPDIR/TMP/TEMP`；
  `/data/local/tmp` 属 adb shell 用户，应用不可写。`ANDROID__BUILD_VERSION_SDK` 必须导出
  （termux-exec 拿不到它会整个跳过）。
- **硬链接被 SELinux 拒绝**（`avc: denied { link }`，内核层无解）：shim 用**复制**顶替（保留权限位与
  时间戳，目标已存在时 `EEXIST`），`ln` / `cp -l` / `git clone --local` / ccache 因此能跑，
  代价是 inode 不共享（`ADSH_LINK_EMULATE=0` 可关）。
- **`LD_PRELOAD` shim 常驻**，管三件事：写围栏、`/tmp` 映射、**脚本自愈**（第 67 轮：内核 exec `#!` 脚本
  自己会 EACCES，shim 在 EACCES/EPERM 时解析 shebang 按内核 argv 规则改解释器，`shebang_retry`：
  只对 `#!`、必须有执行位、模拟存储不碰、递归上限 4）。ELF 的 linker 改写归 termux-exec。
  两条实现纪律：**初始化只能走惰性路径且允许重试**（早期调用发生在 libc 装好 `environ` 之前，
  `pthread_once` 会让 shim 永远空转）；**constructor 里不许 `dlsym`**（bionic 还握着 linker 锁 → 卡死）。
  `syscall()` 要单独拦（GNU coreutils 的 `mv` 走裸 `renameat2`，LD_PRELOAD 够不着）。
  判「目标是不是外部 libc」时**解释器要递归判自己**（把 `$PREFIX/bin/sh` 当 glibc 会让每个脚本子进程丢围栏）。
- **read-only 的语义是「命令照跑、写被拒」**：围栏在 read-only 下必须挂上并传**空白名单**
  （`fence.c` 认「有 `ADSH_FENCE_MODE` 就启用」）——**绝不能改回「roots 为空就不启用」**（那等于只读
  预设 = 完整写权限）；哨兵文件缺失时失败关闭。自测：`scripts/fence-selftest.sh`（40+ 项）。
- **可写根的唯一定义是 `Args.writableRoots(ctx)`**：工作区 + `$TMPDIR` + `java.io.tmpdir` + `$ADSH_SCRATCH`；
  围栏（`TermuxRuntime.fenceEnv`）与 write/edit 的 `Args.gateWrite` 必须用同一份。
- **终端与 Agent 一律 `bash -i` / `bash -c`，不用 login shell**（`-l` 会 source `/etc/profile`）；
  bootstrap second stage 由安装器跑完并留 lock（`profile.d` 的 fallback 脚本靠它跳过，别删）。
- **全新安装走「拷贝回退」**：`filesDir`（`/data/user/0/…`）与前缀（`/data/data/…`）是不同挂载点，
  `ATOMIC_MOVE` 必然 `EXDEV`，回退的 `copyTree()` **必须按符号链接原样重建**（bootstrap 里
  `etc/apt/trusted.gpg.d → $PREFIX/share/termux-keyring` 这类绝对链接在 `usr-staging` 里天然悬空，
  顺着读就 `NoSuchFileException`，装一半死掉、永远装不完）。
- **内嵌环境出怪事先跑 `adsh-env-check`**（终端命令；debug 启动也会写 logcat，tag `ADSH_ENVCHECK`）。
  **别用 `run-as` 测 exec**：那是 `runas_app` 域，`app_data_file` 的 exec 不受限，结论会反。
- **渲染网页 / 截图一律走 `adsh-shot`**：自己解析浏览器、本地路径转 `file://`、等页面、打印输出路径，
  `--which` 给必须收 `-b` 的工具。真机钉死三条：用 `chromium --headless=new`（`headless_shell` 在本环境
  rc=0 却不产出）；**绝不加 `--user-data-dir`**（带上就挂到超时）；本地路径不存在要**报错**，
  不能当域名拼 `https://`。改 `$PREFIX/bin` 里的脚本后跑 `python3 scripts/check-env-scripts.py`
  —— 前缀的 `/bin/sh` 是 dash，漏个引号整份脚本只剩一行 `Syntax error`。

## 三、会话与数据

- **一行消息不能超过 ~2 MB**（`CursorWindow` 上限）：超了 SQLite 抛 `SQLiteBlobTooBigException`，
  整条会话变成「一打开就闪退」。所以：所有读会话的路径都走 `ConversationRepository.readMessages`
  （失败退回 `listCapped` 截断读）；「轮 / 步」用 `COUNT` 查询而不是读全表；
  工具输出落库前 `take(MAX_TOOL_CONTENT_CHARS)`。
- **数据库迁移史**（`app/schemas/…/*.json` 是导出物，**别删**）：
  v2→v13 逐版加列；**v11→v12 用「重建表 + 搬数据」摘掉 `conversations` 上的 7 个 goal 列**
  （老 SQLite 没有 `DROP COLUMN`）；v13→v14 加了 goal 表与 `messages.goalRound`；
  **v14→v15 又把它们摘回去**（第 79 轮加、第 80 轮退，schema 精确还原成 v13 形状）。
  两条纪律：①**升级路径不能断** —— 旧安装必须能凑出 13→14→15，缺一条就会掉进
  `fallbackToDestructiveMigration(dropAllTables = true)` **清库**；②反向（降级）也必须靠前滚解决，
  不能让 Room 撞降级（报错或清库）。
- **轮 / 步口径**：带 `name` 的 user 行（插话 `steering`、权限切换通知 `sandbox:switch`）**不算一轮**；
  插话属于它插进去的那一轮（`ChatViewModel.steer()` 不许动 `liveTurnId`）；插话没被认领时由
  `continueIfDangling` 先 `clearName`，它才是下一轮开头。
- **工具调用与结果靠 id 配对**：解析时缺 id 就补合成 id（`CallAccumulator`），装配历史先按 id、
  配不上再按顺序兜底（`pairToolResults`，有单测）。
- **图片通道**：`read_image` 那一行的展开体就是图（输出正文不展示）；模型侧只走 `image_url` data URL
  （`requestImageOf`：640k 像素 / 1 MiB 质量阶梯），不要往 tool 消息里塞图片块；
  `ModelDef.imageInput` 三态（null 按目录 / true / false）决定模型认不认图。
- **注入的展示层 ≠ 线上请求**：`buildMessages` 只发一条 system；`sysprompt` / `context` / `command`
  三种节点装配时被跳过，会话里看到的多行是 `recordContext` 为展示各存了一份。
- **推理等级是逐模型的**：菜单走 `core/data/Reasoning.kt`，发请求走 `wireEffort`；
  `core/data/ModelThinkingLevels.kt` 是**生成物**（`scripts/gen-reasoning-levels.py`），别手改。
- **网页搜索一次只有一个后端**（`deepseek-official` / `exa`）：地址 / 密钥 / 上限按后端分开存，
  换后端 = 关掉另一个；Exa 每次 query 取 10 条候选、最终最多 7 条来源（DeepSeek 侧沿用 dsh 的 8）。
- **任务清单是会话级的**（dsh 的 `sessionProjections`）：`TodoStore` 按 `conversationId` 分，
  `startTurn` 开头清空、删会话 `forget`；界面横窗只订阅当前会话，**别退回全局单例**。

## 四、提示词与 PTC

- **提示词不许重复**（第 59～62 轮定的规矩，`PromptAssemblerTest` 盯着，含段落 / 句子 / 8-gram 三档）：
  ① 工作区路径只在结尾那行动态行里出现一次；② FUSE 与临时目录的精确口径只在 `env:android-termux`；
  ③ 「被拒绝是策略不是 bug」在 `sandbox:policy`，拒绝标记与**唯一的**升级流程在 `## Working rules`；
  ④ 每个工具自己的说明**只在 `ToolSdk.specs` 写一次**；⑤ 身份只有一句。
  **schema 也是提示词的一部分**（`run_code` 的 description 每轮随 tools 数组发出），改一处要看另一处。
  唯一的例外是 run_code **语言约束**（第 81 轮）：dsh 跑可擦除 TypeScript、这边是 QuickJS 的纯 JS，
  写类型标注就整段不跑 —— 这条值得在模型最先读到的 schema 里出现一次。
- **`PTC_ONLY_INSTRUCTION` 是两句**：第一句「只有 run_code 能直接调」，第二句
  "Reach every tool the SDK declares below from inside the program."（少第二句模型会连着发好几个
  run_code 去够不同工具）；配套「一步一个程序」在 SDK 段（`PromptAssemblerTest` 两条都盯着）。
- **PTC 模式下只有 run_code 能被直接调用**：`AgentLoop.execute()` 那道闸门不能撤。
- **run_code 的三条硬事实**：①**两分钟**固定执行预算（超时停程序、保留已打印内容）；
  ②同一程序内的子调用**串行**（QuickJS 包装库没有 `executePendingJob`，靠「泵 microtask + 同步宿主函数」），
  并行只能走 `Promise.all`（宿主侧按上限并发）；③失败会带**程序行号 + 那一行原文**，
  语法失败再指出第一处 TypeScript 写法（`core/ptc/ProgramDiagnostics.kt`，包装器偏移 3 行）。
  `console.log` 的对象在 JS 侧先 `JSON.stringify`（宿主回调只收一个字符串）。
- **子调用并发按 dsh 的调度器形状**（`ToolConcurrency`）：提交顺序单队列 + head-of-line；
  读类（`read/read_image/glob/grep/web_fetch/web_search/present/ask_user_question/todo_write`）
  最多并行 `maxParallel` 个；**写类（bash / write / edit / 未登记的工具）独占** —— 等池子排空、独自跑。
  工具内部扇出可重入（不占新名额）。
- **静态提示词的参照物只有两个**：dsh ptc 模式的渲染结果（现在只能从 GitHub 读：
  `gh api repos/deepseek-ai/deepseek-harness/contents/<path> -H "Accept: application/vnd.github.raw"`）
  与本项目实现。抄 dsh 的句子前先确认「ADSH 也这么做吗」；**外部审查报告不要照单全收**。

### 有意偏离 dsh 的地方（都写在对应代码注释里）

| 偏离 | 为什么 |
|---|---|
| `targetSdk = 28`、`com.termux` 包名 | 内嵌 Termux 直接 exec 的前提（dsh 是桌面 Node 程序） |
| 程序语言是纯 JavaScript（QuickJS），不是可擦除 TypeScript | 没有可用的 JS 引擎能抹类型；代价是类型标注直接语法错，用 schema 里那句约束 + 报错定位补 |
| 子调用串行 + `Promise.all` 才并行 | 同上（QuickJS 包装库的限制） |
| `glob` 连目录一起返回、按字典序 | 用户实测要求（dsh 只给文件、按修改时间） |
| `grep` 的 250 条上限在**结构化值**上也生效 | PTC 下正文不进上下文，上限只写正文等于没有；这里也没有 spill |
| 没有 spill 文件（页脚不写「完整结果已存到 …」） | 没有这个服务，写了就是假的 |
| 没有 subagent、后台任务、`DSH_*` 托管环境变量 | 这些能力 ADSH 不存在（提示词里也不许出现） |
| 多一段 `env:android-termux`（FUSE / scratch / tmp / 硬链接 / adsh-shot） | Android 特有的坑，dsh 桌面版没有 |
| 运行扫光有 2 秒入场延迟 | 秒回的工具不该闪一下 |
| `todo_write` 的并行示例改成「同一程序里的独立调用」 | 没有并发 subagent / 后台命令可举例 |

## 五、界面不变量

- **流式重绘节流必须放在 `ChatScreen` 自己的作用域**（`sampledStreaming` 声明在这一层，按「会话 + 这一轮」
  重建）：写在列表项里 → 行「先长高、下一帧才贴底」；抽成子 composable → 写入只失效子作用域，
  正文直到定稿才整段出现。**变短 / 换段要立刻跟上采样值**（第 81 轮：AgentLoop 先落库再发 StepCommitted，
  慢半拍会让同一步的正文在「库里那行」与「流式尾巴」里各出现一次，下面的工具行被顶下去再弹回来）。
- **贴底 = `SideEffect` 里的 `requestScrollToItem` + 隔帧核对**：请求发生在测量之前，多行同时长出来会过头；
  `pinSignal`（CONFLATED Channel）+ `withFrameNanos` 在真实测量就位后按 `bottomGap` **精确 `scrollBy`**
  补一次（第 81 轮由「再发一次请求」改成量差值）。**看「是否在底部」只认 `bottomGap()`**
  （判列表最后一项），拿 `visibleItemsInfo.lastOrNull()` 会在每轮边界误判。
- **自动滚动的优先级最低**：只在「尾部真的往后长」时贴底（行数 / 最后一行 key / 流式尾巴长度 /
  **IME inset** 变了）；读者展开或收起任何一行都要 `readerAction()` 停跟随（展开态是行内
  `rememberSaveable`，外面看不见 → **回调必须当参数传下去**，别用 CompositionLocal）；手指按着时不贴底。
  呼出键盘时消息与输入框**同帧**跟随（IME inset 是第四种贴底信号，写进同一个 `SideEffect`；
  不许用 `LaunchedEffect(imeBottom) { withFrameNanos {} }` —— 先等一帧就是慢一拍）。
- **思考行的 `running` 由 `ChatUiState.reasoningRunning` 决定**（dsh：`running = streaming && i === last`），
  模型开始写工具调用参数（`ToolCallDelta`）就停，等 `ToolStarted` 已经晚了。
- **停止时不许清流式内容**：`sending` 立刻复位只为按钮响应，`streaming` / `reasoning` / `liveCalls`
  要留到 AgentLoop 落成 `interrupted` 消息、由 finally 一次换掉；「活轮」判据只看 `liveTurnId`。
- **输入框的 `＋` 与键入 `/` 是同一个菜单**（规则层在 `ui/Palette.kt`，可单测）：`＋` 打开**全量菜单**，
  草稿非空时只列能与草稿共存的命令（计划 / 权限不列）；查询**精确命中命令名**即收起（回车直接执行）；
  触发词遇空白即失效；一条候选都不剩也收起；拾取只消费键入的触发词（**不清空草稿**）。
  菜单**窗口高度在生命周期里恒定**（筛选只让卡片在窗口里长缩）——Popup 是独立窗口，
  窗口一变尺寸就会「先画旧尺寸那一帧再跳」（逐字删除时的「从下往上滑一下」）。
- 权限预设弹层宽度 = **输入卡片的一半**（内容是 `fillMaxWidth`，宽度必须自己定死）。
- 覆盖页（设置 / 终端 / 工作区文件）是**叠层**：每层保持组合，被盖住的用 `Modifier.layout { layout(0,0) {} }`
  压成 0×0；**不要改回 `AnimatedContent`**（会销毁设置页组合 → 退出终端卡顿、卡片展开态丢失）。
- 终端是**转录式面板**（输入行按行送进 PTY，不是 VT 模拟器）：转录正文**必须分块**
  （`transcriptChunks`，每块 200 行，整段一个 `Text` 一帧 ~500ms）；「把键盘要回来」只在提交命令时做一次；
  `$ADSH_WORKSPACE` 必须是当前会话的工作区，PTY 尺寸跟视口（别写死列数）。
- 别把 `com.termux:terminal-view` 加回来：终端是自研 Compose 面板 + `pty_bridge.c`，
  全仓库没有一处 `import com.termux.*`。
- **公式渲染**：几何在 `ui/LatexCore.kt`（纯 Kotlin），绘制与缓存只在 `ui/Latex.kt`；行内公式用
  `InlineTextContent` + `Placeholder(AboveBaseline)`（占位尺寸必须是 sp）；分隔符要在**跳过空白之后**判，
  解析 / 尺寸 / 位图三级缓存缺一不可，`looksLikeMath` 的启发式别放宽（中文里的「$5 到 $10」会被当公式）。

## 六、构建、发版与工具

- 构建：`cmd.exe /c "cd /d D:\WSN2005\Android1\App\ADSH && scripts\build-debug.bat <tasks>"`
  （`wsl.exe` 在本机已不可用，别再用 `build-debug.sh`）。JDK 17 = `D:\WSN2005\Android\jbr`。
- release 必须 `isMinifyEnabled = true` + `isShrinkResources = true`；debug / release / side 同包名、
  debug 与 release 同一把签名，`install -r` 可互相覆盖且不动数据。
- **干净重建的 APK 内容相同、sha256 不同**（zip 容器元数据）——核对 release 要比内容或看
  `app/build/outputs/mapping/release/mapping.txt`，别只比 sha256；也不要用「dex 里 grep 字符串」
  验证改动（R8 会内联 / 消除不可达分支）。
- 已知无害噪声：`assembleRelease` 会打几十行 `llvm-strip: … not recognized as a valid object file`
  （随包分发的**脚本**被当成 `.so` 去 strip），构建照常成功。
- 五个死代码扫描（改完代码复跑）：`scripts/find-unused-declarations.py`、`find-unused-resources.py`、
  `find-unused-c-symbols.py`、`find-unused-catalog-entries.py`、`remove-unused-imports.py`。
  判据一律「除声明处之外整个语料里没人提」——**只删这种**，删前人工核对（老数据兼容分支、
  `srcDir(...)`、`app/schemas/*.json` 都别删）；`find-unused-declarations.py` 会把每个测试类报成
  「没人提」（假阳性）且很慢，放后台跑。
- 设备侧读库（发布包不可调试，`run-as … cat` 拉出来的不是库）：`adb push` 一个脚本到 `/data/local/tmp`，
  再 `adb shell run-as com.termux files/usr/bin/python3 /data/local/tmp/x.py`，脚本里直接
  `sqlite3.connect("databases/adsh.db")`（WAL 里的最新事务也算数）。
- 设备：`FQJZF6U4TCVC7DB6`；adb = `D:\WSN2005\Android1\platform-tools\adb.exe`（传 Windows 路径）。
