# HANDOFF（给下一个会话）

## 这是什么

ADSH：把 deepseek harness（dsh）的 PTC 语义（模型写一段程序组合调用工具）用 Kotlin + Compose 原生重写
成安卓 App —— 无 Node 运行时、无插件、无运行期下载。仓库 <https://github.com/yuyuyuyulike/ADSH>（public / MIT）。

- 功能说明看 [README.md](README.md)；**改代码前先看 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 的硬约束清单**；改动规矩看 [AGENTS.md](AGENTS.md)；历史看 [CHANGELOG.md](CHANGELOG.md)、踩坑看 [docs/LESSONS.md](docs/LESSONS.md)。
- 工作区 `D:\WSN2005\Android1\App\ADSH`；dsh 参考源码在 `D:\tmpdsh-work`（**0.2.1-rc.1 全量源码树**，tag `dsh-v0.2.1-rc.1`；当前 dsh 是 `dsh-v0.2.1-rc.2`，与 rc.1 的 core 差异可忽略）。重解包（rc.2）：`curl -L https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/dsh-v0.2.1-rc.2`。源码注释对 dsh 的引用一律写仓库相对路径。

## 当前状态（0.2.6 / versionCode 25）

- **0.2.5 发版（2026-10-21，versionCode 24）**：对外沿 0.2.x 发布线（上一公开版 0.2.4）；0.3.0–0.3.5 是内部未发版批次名，内容全部随本版发布。release 已 `:app:assembleRelease`（R8 minify + shrink）→ `dist/ADSH-0.2.5-release.apk` → 装机复验（versionName 0.2.5 / versionCode 24、启动无 crash）；GitHub 尚未推送。本轮同时深度清理死代码：未使用的 `adsh.kotlin.jvm` 约定插件、归零后失效的包环 baseline、haze 第三方声明与 proguard/build 历史注释、`SettingsStore.contextWindow` 死 setter、仅测试用的 `SettingsStore.Companion.acceptsImages` 与 `ToolSdk.description` / `ToolRegistry.byName`。R8 `-printusage` 核对过 release 不可达成员，无 app 类整类死代码。154 类 / 1214 例。
- **0.2.6 发版（2026-10-22，versionCode 25）**：`dist/ADSH-0.2.6-release.apk`（R8 + shrinkResources、debug 签名可覆盖安装）已装机复验（0.2.6 / code 25、启动无 crash）；GitHub 已推送源码并发布 `v0.2.6-20261022`。内容见下面各条。① 会话流右侧滚动条改成只做位置指示、**不可拖动**，停止滚动 **1 秒**后隐去（`SCROLLBAR_HIDE_DELAY_MS`；`EdgeTouchGuard` 不再需要 thumb 豁免）。② 输入框 `@` 文件补全照 dsh（`.tmp/research-dsh-mention.md`）：当前行行首 / 空白后的 `@` 打开候选面板 —— 有界 readdir 索引（50k 项、排除依赖与构建目录、20 条上限、空查询列根目录、含 `/` 列目录、其余模糊评分），目录可 drill、含空格路径用 `@"..."`、选中补一个空格；规则层 `Mention.kt`、面板 `MentionPanel.kt`、回归 `MentionTest`。③ 随包 glibc 运行时（termux-pacman glibc 2.44 精选 + 自写 `grun`，构建期 `scripts/fetch-glibc.sh` 并进 `usr.zip`，`INSTALLER_VERSION` 7）：外部 glibc 动态二进制用 `grun ./tool` 跑；环境细则已写明 glibc 进程不受写围栏 / `/tmp` 映射约束（`.tmp/research-glibc-runner.md`）。④ 输入框下方胶囊行的出现动画收口：启动恢复 / 新会话↔旧会话切换**瞬间出现**，只有**新会话第一轮跑出统计**那一次播 150ms 淡入 + 高度展开；此后数字 / 胶囊变化只改宽度、行高不动（`ChatStatsDock` + `ChatUiState.dockEntranceArmed` + `statsDockVisible`，判据在会话载入时按统计是否为空武装）。⑤ 「启动 / 切会话最后那一下上滚」的真根因是 footer 高度与会话流底部内边距分两帧 + 首帧 WindowInsets 为 0：现在 footer 先量、会话流后量（ChatScreen 的 SubcomposeLayout，同帧完成），启动页等 insets 就绪再画会话页，切会话的统计也跟正文同一帧到齐（不再在抽屉收起动画里改 footer）；顺带把浅色模式滚动条从 dsh 的 neutral-200 加深到 neutral-400（5dp 宽度下原值几乎看不见）。
- **bash 对抗测试 + 提示词修剪 + 死代码清理（随 0.2.6）**：run-as 深度测试 bash（37 用例：输出截断/omittedBytes/spill、promote、kill 树、取消、stdin/workdir、退出码与信号、UTF-8 边界、glibc 直跑 vs grun）与围栏三模式、9 条提示词形状检查。修掉：read-only 下内部 pid 标记被围栏拒 → 每条命令 stderr 多出一段假拒绝（fence.c 单文件放行）；重复当前模式的 sandbox_permissions 被误拒（dsh 直接放行）；run_code 返回值改为无损 JSON 校验（dsh 的 invalid-output）；glob 目录遍历不跟随符号链接；Args 字符串参数严格化、read 的 offset/limit 严格整数、rg 加 --no-config。提示词按第一性原理修剪：删除「工作区变化通知」（连 `lastWorkspacePath` 全链）、full-access 可写根与 `$PREFIX/$HOME` 等冗余 bullet、SDK 段假穷举句与重复解释；环境段标题改 `## Environment`（界面显示名仍是「环境细则」），静态段写围栏矛盾句改为策略中立。报告审查结论与证据见 `.tmp/device-reports/`（D1/D3/D10/D14 等判为 dsh 既有行为不修）。死代码清理：删掉无 UI/调用方的「重命名工作区」整链（`ChatViewModel.renameWorkspace` → `ConversationRepository.renameWorkspace` → `WorkspaceDao.rename`），把只在文件内使用的 `SettingsStore.fetchedModels`、`ChatViewModel.refreshWorkspaceInfo` 收为 private；R8 `-printusage` 复核 release 不可达成员与整类删除，只剩内联常量/测试口。
- **内部未发版批次（已并入 0.2.5）**：0.3.0–0.3.2 tools/agent 现代化 + 深测修复；0.3.3 `:platform:termux` 优化（PTY 生命周期 / bootstrap 只补缺合并 / PTC 泵等待 / 围栏时间戳 / 环境与结构拆分）；0.3.4–0.3.5 bash 截断修复（单行大输出不空、`omittedBytes`、`Jobs.read` 结算裁剪顺序）。细节见 [CHANGELOG.md](CHANGELOG.md)。
- **架构**：单 Activity + Compose，**8 个 Gradle 工程**：`:app`（壳 / 导航 / 设置 / 终端 / 灵动岛 / `runtime.phone` / 组合根）+ `:feature:chat` + `:ui:blocks` + `:core:agent` / `:core:data`（= data + llm + session，带 KSP+Room、`schemas/` 在模块里）+ `:core:tools`（= tools + workspace + jobs）+ `:platform:termux`（= Termux 平台 + `platform.ptc` + cpp）+ `:seam`（六个 `seam.*` 包）。切法与判据见 [docs/MODULES.md](docs/MODULES.md)，包依赖图见 [docs/module-graph.md](docs/module-graph.md)（`scripts/check-module-graph.py --check` 守新鲜度）。**0 个包循环、0 处分层违规**（分层规则见 `scripts/layering-rules.json`）。
- **组合根**：`:app` 的 `AppContainer`（`AdshApp.container`，懒建）是有状态对象的唯一创建点，`ChatViewModel` 全部构造注入；工具上下文由 `com.adsh.app.host.ToolingHost` 装配（全局项组合根读、每轮变量界面传），设置页「获取模型」也走 `AppContainer.newLlmClient()`。
- **harness 修复（已提交 07d98f4）**：`read` 流式窗口（删掉 4 MiB 整文件上限）、`glob` 目录匹配基、工作区外路径返回绝对路径、`Args` 读取 total、`bash` 值补 `cwd` / `stopped`、`grep` include 逗号校验、`ask_user_question` 参数整条校验（`INVALID_ARGS` / `EMPTY_QUESTIONS`）、`present` 值/正文路径口径、PTC 绑定无损 JSON + `run_code` 参数校验与日志省略提示、Spill / pid 标记 / 探针目录回收、write/edit 值补 `beforeBytes` / `afterBytes`。**已过统一门禁 + 真机复验**（证据在 `.tmp/verification-report.md`）。
- **UI 修整轮（已提交，未发版）**：① 胶囊详情卡**去透明**：浮层里的背景模糊取不到源（证据见 LESSONS），菜单 / 详情卡统一用不透明菜单面 + 发丝边 + 柔光；抽屉的 `hazeBlur`（1.5dp）保留并实测有效。轮尾「用量 / 用时」两张明细卡改为**共用一个展开槽**（不再同时打开、互相重叠）。② 会话流改**一次性渲染并常驻**（dsh 的 DOM 语义，`Column + verticalScroll`），删除 LazyColumn 及其 content type / 兜底补钉：滚动条长度取滚动容器真实 `maxValue`，内容不变时不再抖动，上滚不再重建行；自动滚动改接 `ScrollState`（贴底请求 + `KeepPinnedEffect`）。③ 上一轮的输入卡几何、叠层 footer + 36dp 遮罩、贴边滚动条、12dp 边缘防误触豁免保持不变。
- **工具行 / 性能轮（已提交，未发版）**：① 工具行 `preparing` 阶段对齐 dsh（标题先出、右侧摘要随流式参数里的 `description` 实时长、文件路径完整才显示、不可展开）；运行流光改成 dsh 的 TextShimmer（文字上的颜色带，1.5s / 延迟 0.3s），删除整行扫光。② 「吃白饭中」行从输入框上方挪到**会话流末尾**（dsh 的 RunningStatus），不再落在 footer 顶部 36dp 的渐隐带里。③ 会话页性能：`CulledColumn` 布局阶段记行 y、draw 阶段只画视口内的行；同帧贴底改 draw 阶段位移（不用 graphicsLayer）；IME 高度不再在组合期读；抽屉模糊层独立成 composable —— 长会话拉抽屉 / 终端键盘不再掉帧。④ `run_code` 超时 / 结束时把未结算的子调用按 dsh 的 abort 语义收尾（正文 `tool call aborted`），不再留下永远转流光的子行。
- **间距 / 空白轮（已提交，未发版）**：① 修 `CulledColumn` 的行高：量每一行前把 `minHeight` 归零 —— 外层 `fillMaxSize` 会把视口高度写成 `minHeight`，`verticalScroll` 只放开 `maxHeight`，之前每一行都被撑成一屏（真机一行 585dp，整页全是行间空白）。② 会话流行距改成 dsh 的 flow gap：默认 6dp、最终回答前后 12dp、折叠行与它下面一行 16dp（原 16/10/8/2dp）；回归 `TranscriptFlowGapTest`。③「吃白饭中」正文固定 13sp（原 15sp）。
- **Markdown / 流式对齐轮（已提交，未发版）**：① 排版照 dsh：h4~h6 上边距 16（原一律 32）、引用块竖线跟随内容高度且内部块带上下边距、行内代码底框高度 = 代码字体行高（不再用整行 24 撑胖）、窄表（<4 列）铺满列宽并换行 / 宽表横滚、表格单元格 13/22、图片圆角 12、代码块用 dsh 的 card 工具栏（语言 + 复制图标、默认折行、正文 11/19）。② 用户气泡照 dsh：正文改纯文本 14/22、圆角 20（原 22）、最大宽 0.702×列宽（原 82%）、动作行与气泡间距 6（原 8）。③ 流式照 dsh：去掉逐字平滑，收到什么画什么（33ms 只做重绘节流），只有「变短 / 换段」立即跟上。④ 三个 harness 坑确认与 dsh 同源（rg glob 锚定 cwd、PTC 参数必须是对象、read 归一化行尾），只补了工具说明。
- **提示词 / 启动 / 预览轮（已提交，未发版）**：① 系统提示词与动态注入按审计修正：默认（未绑定）工作区是 app 私有 f2fs、不再按 FUSE 描述；`$ADSH_FENCE_ROOTS` 只有 workspace-write 才导出（read-only 不导出）；计划段 / 作业段 / 工具说明里的裸工具名全部改成 `tools.<name>`（只有 `run_code` 能直调）；超时改 promoted 语义、拒绝判据按 `[sandbox:]` / `[fs:]` / 普通 `ToolCallError` 分开；read-only 下不再建议 scratch 构建/装包；`present` 补 1–2 / 至多 8 与「current files」；指令基线按 UTF-8 字节截断并带 truncated 提示、空基线不再每轮重写。② 启动恢复期间只画底色（`ChatUiState.booted`），不再先闪「新会话」首页；持久化主题 / 字号在 VM 初始化时同步回填。③ 修键盘收起后的内容下移：根因是滚动内容挂了 `fillMaxSize`，被视口的 `minHeight` 撑高并**垂直居中**（短会话内容高度 = 视口高度，`maxValue` / 内容高度全失真）；现在滚动内容用 `wrapContentHeight(Alignment.Top, unbounded = true)` 按自身高度测量；`ScrollState.value` 超出 `maxValue` 的回夹与 `KeepPinnedEffect` 绝对值判据保留作兜底。④ 文件预览对齐 dsh：chip 28dp/圆角 8/激活填充、38dp 文档头（路径 + 换行 + 重读）、代码 11/19 + 行号 + 复制、Markdown frontmatter、图片 12dp inset + 缩放胶囊、PDF 连续页 + 12dp 间隙 + 文档底色、加载/空态/失败文案。
- **Word 预览对齐 PDF（未发版）**：.docx 按 A4 版心缩到屏幕宽（读 w:sectPr 的页宽 / 页边距；字号、缩进、表格列宽、图片尺寸乘同一系数，横版工艺卡也不再横滚）；OMML 公式（分式/上下标/根号/大运算符/矩阵等）翻成 LaTeX 走聊天同一套 LatexCore 渲染（WordMath.kt，行内 appendInlineContent、独立公式居中）；w:vertAlign 上下标按 65% 字号 + 基线抬降。**老式二进制 .doc 的浏览已移除**（R220：合并格 / 图片 / OLE 对象在设备上无法可靠还原，先按「用其他应用打开」处理；.xls / .ppt 同）。回归：WordMathTest / WordReadTest（页面设置、公式、vertAlign）/ WordTableUiTest。
- **思考正文切块（未发版）**：展开的思考正文不再是一整行 `MarkdownBody`，按 `reasoningChunks` 切成独立会话流行（空行切、不切围栏、纯段落超长时行边界兜底；块间距走 `hasPreceding/hasFollowing`），展开态上移 `ChatScreen` 的 `reasoningOpen`（键 `reasoningKey`）。修「大段思考展开后滚动 / 键盘 / 抽屉掉帧」：行级绘制剔除现在管得到正文。用例 `ReasoningChunksTest` / `TurnListReasoningTest`。
- **harness 深度测试修复轮（未发版）**：真机报告的两个真问题按 dsh 修掉 —— ① 工具网关死锁：`ToolConcurrency` 改回 **per-run** 实例（`PtcToolRunner` 每轮新建）+ `edit` 改为 dsh 的单趟字面替换（`countOccurrences` + `String.replace`，不再用 `Regex` 物化百万匹配），5 MB 单行 `replace_all` 不再挂死整条工具层；② `edit` 按 dsh 归一化行尾（CRLF 文件用 `\n` 匹配、写回恢复 CRLF），`write`/`edit` 拒绝孤立代理项（不再静默写成 `?`）。提示词按测试结果清理：删掉「grep 跳过隐藏文件」这句被证伪的概称、`edit` 的 CRLF 警告改成事实、`job_output` 的消费语义写清、`web_fetch` 超时按「每地址」说真话，并去掉重复的 FUSE / 升级 / promoted 段落与实测轶事。用例：`ToolConcurrencyTest`、`ToolExecTest`（5 MB 单行、CRLF、代理项）、`PromptAssemblerTest`。
- **会话流性能轮（未发版）**：长会话里滚动 / 键盘 / 抽屉的每帧开销不再随历史行数线性增长 —— 行级绘制剔除改成只读一个**台阶化的派生窗口**（`CULL_STEP_PX`，只在跨台阶 / 视口取整变化时让行失效重画，旧实现每帧把 N 行全部标脏）；右侧滚动条改为**绘制阶段**读滚动状态、thumb 几何按下时现算（不再每帧重组 + SideEffect 写状态）；抽屉 1.5dp 景深从 haze 实时模糊覆层改成主内容图层的 `RenderEffect`（动画全程都有模糊，不再有「动画后突然变清 / 颜色跳一下」）。真机 debug 实测（同一长会话、各 3–6 轮）：抽屉 p50 48→12ms / p90 61→20ms，键盘 p50 34→12ms；滚动本来就基本达标（p50 9→8ms，p99 30→24ms）。新增 `CullWindowTest` 钉「可见 ⇒ 必画」。
- **工具报错卡对齐轮（未发版）**：报错详情卡按 dsh 的行语义 —— grep / glob / web_search / web_fetch（连同 read / read_image / write / edit）的展开体**没有输入段**（dsh 这些 keyed 行不传 rawInput），报错时只剩一段红色输出；todo_write / present 也只留错误输出（参数 JSON 对报错没有信息量）。ioCard 字体改成 dsh 的等宽 11/16、参数 JSON 缩进两空格；段高 200dp（dsh 150px）保留。用例 `ToolRowSummaryTest`。
- **模块梳理轮（未发版）**：`:feature:chat` / `:ui:blocks` 的大文件按职责拆开（Composer→`Overlay.kt`、TurnRail→`ToolRowModel.kt`、TurnList→`TurnModel.kt`+`TurnBuilder.kt`、ChatViewModel→`ChatUiState.kt`、DocumentPreview→`ImagePreview.kt`、LatexCore→`LatexParser.kt`），零行为改动；最大的文件 1989→1797 行。`docs/MODULES.md` 行数已按实测刷新。
- **0.2.4 发版**：新增右侧滚动条 / docx 等文档预览 / 双击刘海回会话开头；修复 `edit` 的极端错误等 harness 问题；统一卡片字级与间距、拆顺两个最大模块、会话流交互与性能收口。release APK 已构建、装机复验并发布（`dist/ADSH-0.2.4-release.apk`）；README 按自然口语重写，用户加在开头的那段说明原样保留。
- **切会话两个回归（未发版）**：① 双击顶栏回会话开头在切过任意会话后失效 —— `ChatTopBar` 的 `pointerInput(Unit)` 只启动一次，捕获的 `onScrollToTop` 是第一次组合那份（指向已卸载的 `ScrollState`），改 `rememberUpdatedState`。② Agent 干活时切走再切回，输入框下方三个统计胶囊消失到下一次工具调用 —— `withLiveRun` 没盖 `stats` / `context`，而 stats 只在轮尾落盘；改成随登记表一起盖回来。用例 `ChatTopBarTest` / `LiveRunStateTest`（两条都做过变异验证）；debug APK 已装机。
- **附件对齐 dsh（未发版）**：① composer 待发附件改成 dsh 的 `AttachmentRail`（padding 2/10/0 + 上收 6、间距 10、溢出翻页箭头、新增滚到末尾；图片 64/圆角 20/0.5 描边/移除键 18 内右上 4；文件卡 240×64/圆角 16/图标 28/14-22-500/12-15）。② 消息单图照 `singleFit`（长边 240、比例夹 [0.25,4]、cover 裁切、锚顶/锚左），多图 64 方格圆角 20，文件卡换按类型着色的 `FileTypeIcon`。③ 附件仓库搬到应用私有 `files/attachments/v1`（内容寻址、只读、去重）；图片按 dsh 准入并归一化（EXIF 方向/2048²/4MiB）；wire 顺序改「附件在前、正文最后」；请求图片预算 2048²；空文件/UTF-8 文件名/BOM/副本失效可见错误。用例 `RequestImagesTest` / `AttachmentStoreTest` / `MessageImageFitTest`；调研证据在 `.tmp/dsh-attachment-{ui,semantics}.md`。
- **动态注入命名 / 精简 / 机制校准 / 报错英文（未发版）**：① 环境段改名 `环境细则`；快照行显示名只写「环境细则」，计划模式开着时 `·plan`（不再拼 `sandbox:policy`）；删掉「工具装好了 / git 配好了 / 网络不稳重试」及同类非必有信息。② 快照正文严格按 dsh `joinContextSections`（抬头一次 + 各段正文，不塞叙述）；上一条快照按 form 找、整串比较决定追加；策略切换不再写 from→to 叙述；计划模式退出叙述改为独立 user 消息（name = `plan-mode`，界面 notice 行）。③ 所有报错改英文（异常 / 工具错误 / 错误条 / Toast / 预览失败 / 诊断脚本失败输出；标签与状态词除外，规则在 AGENTS.md）。用例 `ContextLedgerTest` / `PromptAssemblerTest` / `PlanNarrationTest`。
- **自动滚动 / 浅色阴影 / todo 任务栏（未发版）**：自动滚动改按手机端标准 —— 只有「读者在底部」一个状态（`ChatScrollState.following`），判据是滚动容器已无剩余可滚距离（1px 取整容差，删掉 25dp 迟滞与 `ScrollFollow`）；读者上滑立即停、回到底部恢复，读者动作与手指在屏上不替读者滚。浅色模式新增 `lightCardShadow`：输入框 / 命令栏 / todo / footer 浮条 / 统计胶囊 / 回到底部 / 提问与审批卡（深色不变）；todo 折叠高度 48 → 40dp、折叠态与输入框等宽。用例 `AutoScrollTest`。
- **滚动条补齐（未发版）**：新增共享 `DshScrollbar`（ui:blocks；覆盖式绘制、l1/l2 两档 + 新令牌 `scrollbarThumbL2`），给文件预览 / 文档 / 文件树 / 图片、详情卡（IO 段 / 代码块 / 上下文注入 / 终端输出 / 任务面板 / 模型菜单子页 / Web 块 / 提问卡 / todo / 压缩摘要 / 命令菜单 / 附件条）、抽屉 / 设置页 / 隐私弹层 / 终端回滚挂上；横向 tab 条不加（会横穿 chip）。会话流自绘滚动条与宽表自绘横条不动。门禁全绿（131 类 / 1067 例）。
- **无工作区惰性输入框 / 删除当前会话回首页 / 置顶会话（未发版）**：没有绑定工作区（首页、删过工作区、删掉当前会话后）时输入卡片换成 dsh 的 `.cardWorkspaceTrigger`（1px 虚线描边、不可编辑、占位「选择一个工作区开始」、点整卡展开工作区 chip 详情卡；按下线色转 accent）；删当前会话按 dsh 的 archiveSession → clearMain 回空态首页、不再自动落到下一条；「新会话」无工作区可落时同样 clearMain。会话三点菜单加置顶（dsh slot 100，行尾 14dp 填充钉可点取消置顶），侧栏置顶行在分组内提前、组内按 updatedAt 倒序，Room v18 `conversations.pinned`。用例 `SidebarOrderTest` / `ClearedMainTest` / `ComposerWorkspaceTriggerUiTest`；门禁全绿（134 类 / 1079 例）。
- **门禁**：核心是全模块 `testDebugUnitTest`（用例数直接数 `**/build/test-results/**/TEST-*.xml`，别信任务状态）与 `:app:assembleDebug` —— 后者不能省：变体匹配/打包类回归只有组装才看得见。
- **仓库**：远端 `main` 由 `scripts/push-via-api.py <远端当前提交>` 维护（**github.com:443 连不通**，api.github.com / uploads / codeload 通），成功判据是「远端 tree == 本地 HEAD tree」。**本地 `origin/main` 是 2026-10-11 的旧 fetch 引用，别拿它当远端现状**；下次 push 前先 `git fetch`（需代理）。
- **发布物**：最新 GitHub release 是 `v0.2.6-20261022`（0.2.6 / versionCode 25，R8 + shrinkResources，debug 签名，可覆盖 debug 包；旧 release 按用户口径不删）。`dist/` 只保留最新的 0.2.6 release 与 debug 两个 APK；设备上装的是 0.2.6 release 包。
- **装机冒烟**：release 不可调试（`run-as` 被拒），深度诊断装 debug 包；两者同包名同一把签名，`install -r` 可互相覆盖且不动数据。组件名是 `com.termux/com.adsh.app.MainActivity`（`namespace` 与包名不同，写 `.MainActivity` 会报 `Activity class does not exist`）；稳妥写法 `adb shell cmd package resolve-activity --brief com.termux` 取组件名再 `am start -n`，冒烟前先 `adb logcat -b crash -c`。
- 删过代码之后跑 `python scripts/deadcode.py` 与 `python scripts/find-unused-imports.py`（后者管 import，前者不管）。
- README 被用户在网页上改过 —— 压平 / 改写前先把工作区同步成他那一版，否则 orphan 提交会顶掉他的修改。

## 下一步

**下一步**：0.2.6（未发版）debug 已装机待点验三条 —— 滚动条不可拖 / 停止滚动 1 秒隐去；输入框 `@` 面板（目录 drill、含空格路径）；终端 `grun --help` 与 `grun <glibc 二进制>`。0.2.5 release 已构建并装机（debug 签名、可覆盖安装；GitHub 未推送）。真机点验仍差三处（fence 自测已在设备上用 `CC=clang` 跑过）：① 进终端页敲 `exit` 再退出，`ps -A -o PID,PPID,STAT | grep bash` 不应出现 `Z`；② 关掉正在跑长命令（如 `apt install`）的终端页，进程组应被带走；③ 重装 APK / 删掉 `$PREFIX/MANIFEST.properties` 触发一次全量安装，用户 `apt` 装的包应保留（合并而不是清前缀）。发布流程：快照 → `scripts/push-via-api.py` → `gh release create`（README 下载链接届时指向 v0.2.5-20261021）。模块数不设硬指标（用户口径：功能优先、8–12 都行；当前 8 个，**别为凑行数硬拆/硬并**）；真要再动边界，先看 `docs/MODULES.md` 的量测与合并判据。

## 统一门禁（一轮结束跑这个）

```powershell
$env:JAVA_HOME='D:\WSN2005\Android\jbr'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
# testDebugUnitTest / lint 用任务名选择器，一次覆盖全部 8 个模块；spotlessCheck 不带前导冒号才会覆盖所有模块（:spotlessCheck 直接失败）
.\gradlew.bat testDebugUnitTest lint :app:assembleDebug spotlessCheck --console=plain
python scripts/check-package-cycles.py --strict    # 环 + 分层规则（scripts/layering-rules.json）
python scripts/check-module-graph.py --check      # 模块图与 docs/module-graph.md 是否一致
python scripts/check-module-graph.py --strict     # 非 :app 模块不许引用根包符号（BuildConfig / R / MainActivity / AdshApp）
python scripts/check-comment-history.py --strict  # 注释里不许有「第 N 轮」
python scripts/check-doc-budget.py
python scripts/check-env-scripts.py               # 内嵌 shell 脚本的 dash 语法
node scripts/check-qjs-preamble.js                # PREAMBLE 语义回归（32 checks）
```

- 量数字时给测试任务加 `--rerun`（有时仍会 UP-TO-DATE），**最稳是直接数 `*/build/test-results/**/TEST-*.xml`**。
- 用例**分家**了：各模块的测试跟着模块走，**只跑 `:app` 会漏掉模块里那一批**；门禁的任务名选择器 `testDebugUnitTest` / `lint` 一次覆盖全部模块（根项目没有 `lint` 任务，别写 `:lint`）。
- **`:app:assembleDebug` 必须在门禁里**：变体匹配与打包类回归**只有组装才看得见**——测试 / lint / spotless 只碰 debug 变体。实测踩过：模块化之后 `:app` 的 `side` 变体找不到库模块的匹配变体，`assemble` 直接 `No matching variant` 失败，而其余门禁全绿。
- **同一时刻只允许一个 Gradle 构建**（见 [AGENTS.md](AGENTS.md) 的「跑 Gradle」）；要跑先拿门：`.\scripts\gradle-gate.ps1 -Command '.\gradlew.bat :app:testDebugUnitTest :app:lint :app:spotlessCheck --console=plain'`（锁在 `.tmp/gradle-gate.lock`，抢不到会等并打印持锁者，跑完自动交回）。
- `:app:lint` 有坑：增量中间结果偶发腐坏，lint 内部 K2 报 `pointer already disposed`，`--rerun` 救不回 —— 删掉 `app/build/intermediates` 下的 `*lint_partial_results*` 与 `lint-cache` 再跑（一键：`.\scripts\reset-lint-cache.ps1`，先看删什么加 `-DryRun`）。
- lint 基线：**0 error**；三条窄豁免写在 `gradle/lint.xml`（各带理由）；`ExpiredTargetSdkVersion` 按硬约束 1 保持关闭。
- 注释门禁扫描范围：`.kt/.kts/.c/.h/.js/.pro/.sh`（`.md` 不扫，注释门禁只管代码）；注释语法按扩展名分派（Kotlin/C/JS 走 `//` 与 `/* */` —— Kotlin 块注释可嵌套、C/JS 不可；shell/ProGuard 走词首 `#`，`echo "# …"` 不算）。
- 注释门禁判定：注释与代码（标识符）命中即失败，**字符串命中只报告不失败**；匹配「第 <阿拉伯数字含全角 / 中文数字≥2字 / 十开头> 轮」、区间写法（`第 108～113 轮`）与轮次引用 `R<NN>`（排除 Android 的 R8 编译器与 `DeepSeek-R1` 模型名；selftest 34 条钉住）。

## 构建与发版

- 命令：**从 bash 里直接跑 gradle wrapper**（`cmd.exe /c` 会被当成交互式 cmd、一个任务都不跑）：
  `JAVA_HOME='D:\WSN2005\Android\jbr' PATH="/d/WSN2005/Android/jbr/bin:$PATH" ./gradlew.bat --offline <tasks>`
  （JDK 17 = `D:\WSN2005\Android\jbr`；`scripts/build-debug.bat` 是给 Windows 侧用的同一套设置）。
- release 必须 `isMinifyEnabled = true` + `isShrinkResources = true`（build.gradle.kts 已开）；debug / release 同包名同一把签名，
  `install -r` 可互相覆盖且不动数据；**versionCode 只往前加**。
- 装机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（debug）或 `adb install -r dist/ADSH-<版本>-release.apk`（release）——
  adb = `D:\WSN2005\Android1\platform-tools\adb.exe`，设备 `FQJZF6U4TCVC7DB6`。装完 `adb shell dumpsys package com.termux | findstr version`
  核对版本、`adb logcat -b crash` 应无输出；`scripts/verify-apk.bat <apk>` 看 badging 与签名。
- 发版流程：① `:app:assembleRelease` → APK 覆盖 `dist/ADSH-<版本>-release.apk` → 装机核对；② 打快照：`git checkout --orphan tmp` →
  `git add -A` → 单提交 → `git branch -M main` → `git reflog expire --expire=now --all` → `git gc --prune=now`；新 tag 打在它上面，旧 tag 删掉；
  ③ 推送：`python scripts/push-via-api.py <远端当前提交>`（本地必须有那个提交）；④ `gh release create v<版本>-<日期> dist/ADSH-<版本>-release.apk`，**绝不要 `gh release edit`** ——
  release 的说明与标题是用户自己写的，换资产用 `gh release upload <tag> <apk> --clobber`；⑤ README 下载链接指向新 tag。
- 只想留本地快照（不上传、不发版）：`git add -A && git commit` 就够。**快照规矩**：`.backup/` 只留最新一个 `adsh-snapshot-<阶段>.bundle`
  与同名 tag，新快照验证可用后删旧件和旧 tag，别攒。

## 文档地图

| 文件 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 改动规矩（注释、依赖方向、文档预算） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、能力缝、依赖规则、**硬约束清单** |
| [docs/TECH-STACK.md](docs/TECH-STACK.md) | 选型、版本、为什么没选什么 |
| [docs/MODULES.md](docs/MODULES.md) | 模块化：8 个工程怎么切、判据、靠什么守 |
| [docs/LESSONS.md](docs/LESSONS.md) | 经验与坑、不做清单、验证手段 |
| [CHANGELOG.md](CHANGELOG.md) | 最近 8 轮变更 |
