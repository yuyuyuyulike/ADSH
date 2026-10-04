# HANDOFF（给下一个会话）

## 这是什么

ADSH：把 deepseek harness（dsh）的 PTC 语义（模型写一段程序组合调用工具）用 Kotlin + Compose 原生重写
成安卓 App —— 无 Node 运行时、无插件、无运行期下载。仓库 <https://github.com/yuyuyuyulike/ADSH>（public / MIT）。

- 功能说明看 [README.md](README.md)；**改代码前先看本文件末尾的『不要破的硬规矩』**（平台约束、数据不变量、
  提示词纪律、界面不变量都在那一节），想知道一路是怎么走到今天的看上面那节阶段总结。
- 工作区：`D:\WSN2005\Android1\App\ADSH`。dsh 的参考源码在 `D:\tmpdsh-work`（**不要删**：里面的
  `spec-ui.md` / `spec-log.md` 是展示层的规格依据），解包产物与 `asar-tool.js` 在 `D:\tmp`。

## 当前状态（2026-10-04；**已发布** 0.2.0 / code 12，**开发中** 0.2.1 / code 13）

- **仓库**：历史在第 104 / 107 / 121 / 124 轮各压平过一次，最后一次压平之后又线性累积了 111 个提交
  （R0 起）；**远端 `main` 停在 `08dbb09`，那之后的 28 个提交只在本地**（见文末版本块）。
- **当前发布**：tag `v0.1.9-20261002`、资产 `ADSH-0.1.9-release.apk`（R8 minify + shrinkResources，
  含 baseline profile）。README 的下载链接指向它；**GitHub 上只保留这一个 release 与 tag**。
  用户在网页上改过 README（`c3f39b6`，删掉了 `adsh-env-check` 那一行）—— 下次压平前先把工作区同步成
  他那一版，否则 orphan 提交会把他的修改顶掉。
- **发布物**：`dist/ADSH-0.1.9-release.apk`（0.1.9 / code 11，R14 收官时构建的那一份）。
  **当前版本已升到 `0.2.0`（code 12）**：R15 / R16 之后构建并装机的是
  `app/build/outputs/apk/debug/app-debug.apk`（versionName `0.2.0-debug`）—— 与 release 同包名、
  同一把签名，`install -r` 可互相覆盖且不动数据。要出 0.2.0 的发布包：
  `:app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` → 覆盖 `dist/`。
  release 包不可调试（`run-as` 会被拒）—— 要拉库 / 进沙箱 / 看 logcat 就装 debug 包。
- **验证基线**：单测 **824 全过**（每次提交都重跑；第二阶段起恢复为默认门禁，见上面「第二阶段」一节，逐条数字看 R 表）。第 117 轮起按用户要求**不再跑测试**，只保证编译通过、
  装机启动无 FATAL；这一轮起如果删了测试侧的代码或改了断言，至少把 `:app:compileDebugUnitTestKotlin` 跑过。

## 第二阶段（代码熵减，进行中）

目标：**不加新功能**，只做等价改写；验收看行为不变量（编译 + 单测 + 真机），不看减了多少行。
完整方案与路线见 [docs/PHASE2-ENTROPY-PLAN.md](docs/PHASE2-ENTROPY-PLAN.md)（含模块账、五把刀、外部依据）。

**门禁（与第一阶段不同，注意）**：第 117 轮停跑的 `:app:testDebugUnitTest` **已恢复为默认门禁**，
用例数每步都在涨（R79 是 800），增量单类 3~8 秒。改行为面（core/、tools/、ui 里的状态接线）必须跑；纯搬运也建议跑。
开工时它还红着两个用例，是**测试自己的问题**（已修，见下），不是实现回归。

已完成（每步一个提交，都可单独回滚）：

| 提交 | 内容 | 判据 |
|---|---|---|
| R0 | 恢复单测门禁 + 立熵值基线（PMD CPD 7.28.0 本地 CLI） | 368 全绿；CPD 5 组 / 118 行 |
| R1 | 无涟漪点击归一：83 处 `clickable(interactionSource, indication = null)` → `Modifier.dshClickable` | 全库只剩封装自己 1 处（checkbox 有意保留涟漪） |
| R2 | 图标集装配收敛：5 份私有工厂 → `DshIconFactory.dshIcon/dshPart` | CPD 118 → 46 行；119 段 path 多重集逐字未变 |
| R3 | 间距刻度收敛：133 处 `spacedBy(N.dp)` → `DshSpacing` 命名刻度 | 数值分布完全一致（纯改名） |
| R4 | 审批卡 / 计划待审卡共用壳 → `DshWarnCard` | CPD 46 → 27 行 |
| R5 | 删掉 `ChatEvent.Finished` 整条链（解析→发→转发→丢弃） | 368 全绿 |
| R6 | 抽屉两处逐字相同的 `SessionRow` 调用 → 局部 composable | CPD 27 → 12 行 |
| R7/R7b | 图标工厂三处残留（`grouped`/`stroke`/手写 builder）并入 `dshIcon`；补 `scripts/check-icon-structure.py` | 全库只剩 1 处 `ImageVector.Builder` |
| R8 | `padding(...)` 实参里的 381 个裸 dp → `DshSpacing` 刻度名（刻度外 52 个不动） | 258 个调用点逐实参比对 0 处不一致（`scripts/verify-padding.py`） |
| R9 | 设置页「功能」分节：三张插件卡拆成 `TerminalPlugin` / `AgentLoopPlugin` / `WebSearchPlugin`，状态跟着卡走 | 245 行 → 18 行；209 行代码逐字未变 |
| R10 | 拆 `TerminalPanel`（538 行，四个提交）：①输入行纯逻辑 → `TerminalInput.kt`（+18 用例）②会话 / I/O → `TerminalSession.kt`（网格提纯 +5 用例）③三块布局 → `TerminalParts.kt` ④输入行接到纯函数 | 391 全绿；「代码行 / 注释行」多重集比对**无一行丢失**；真机冒烟过（起 bash / 执行 / ↑↓ / Ctrl-C / 退出重开）；最长手写函数 538 → 321 行 |
| R11 | 提纯 `runTurnBody` 的判定 → `TurnDecisions.kt`：路由 / 思考字段 / 死循环 / 本轮结束 / 两种落库口径 / 收尾文案，8 个纯函数；两个阈值常量跟着规则搬过去 | 405 全绿（+14 用例）；多重集比对无逻辑丢失；runTurnBody 397 → 391 行（**只提纯不拆**，见下）；装机启动无 FATAL |
| R12 | 提纯会话页浮层的互斥（统计 ↔ 输入框弹层）与 dsh 的 dismissed 语义 → `ChatOverlays.kt`（纯状态，四合一）；ChatScreen 的写入点从六个收成一个状态 | 415 全绿（+10 用例）；ChatScreen 净减 21 行；装机后统计浮窗可开可关（其余由用户手测） |
| R13（方案外） | 切缝 ChatScreen：①滚动意图提纯 → `AutoScroll.kt`（ScrollFollow / scrollTowardTop / bottomGapOf / pinAllowed / shouldPinFix）②六刀搬出 `QuestionCard` / `ApprovalCards` / `TodoDock` / `UserMessages` / `ChatBars` / `ChatDecisions`③修 `scripts/longest-functions.py` 的测量 bug | ChatScreen **2651 → 1024 行**；425 全绿；每刀做「代码行 / 注释行」多重集比对（差异只有可见性）；**主函数仍 936 行**，下一步是 state holder |
| R14（方案外） | 给 ChatScreen 主函数做 state holder（就是 R13 末尾点名的那件），五步：①自动滚动的**状态**（判定在 R13 已提纯）→ `AutoScroll.ChatScrollState` + `PinFixEffect` ②会话流（列表 + 手势 + 快捷导航）→ `ChatTranscript.kt` ③顶栏与输入框上方那一摞 → `ChatBars.kt` ④回车意图 → `Palette.commandIntentOf` ⑤流式采样 → `StreamReveal.kt` | ChatScreen **1024 → 546 行**（**主函数 936 → 480**）；441 全绿（+16 用例）；每步都做「代码行多重集」比对（搬走的部分逐行对应，差异只有可见性与回调改写） |
| R15 | `DshComposer`（495 行）里的两个弹层菜单 → `ComposerMenus.kt`：模型 / 推理等级两级菜单（含窗口高度夹取）与权限预设弹层；6 个只服务它们的常量跟着代码走 | DshComposer **495 → 326 行**（第 1 掉到第 5）；441 全绿；多重集比对「只在旧侧」只剩被改写的标识符 |
| R16 | 会话抽屉整簇（`Drawer` + 11 个私有件）→ `Drawer.kt`，AppRoot.kt 只剩画布 / 浮层栈 / 引导 / 返回处理 | AppRoot.kt **1310 → 542 行**（Drawer.kt 800 行）；441 全绿；多重集比对「只在旧侧」只有 `private fun Drawer(` 一行（正文一字未改） |
| R17 | 抽屉主体拆成四个小节（字标 / 新会话按钮 / 工作区小节头含展开搜索 / 删除工作区确认框） | `Drawer` **306 → 214 行**；441 全绿；「只在旧侧」只剩参数化与三处接线改写 |
| R18 | 问题卡片拆成三段（卡片头 / 选项行 / 卡片脚） | `QuestionCard` **369 → 215 行**；441 全绿；草稿与校验逻辑未动 |
| R19 | 触发菜单的命令字典（五条命令）→ `Palette.sessionPaletteCommands` | ChatScreen 主函数 **480 → 440 行**；441 全绿；字典内容一字未改 |
| R20 | ChatScreen 的 items 装配 → `TurnList.rememberChatItems`；附件选择器 → `Composer.rememberAttachmentPicker` | `ChatScreen` **440 → 397 行**；441 全绿；两个新函数各 1 处非注释调用点（R17 之后新增的检查） |
| R21 | AppRoot 的画布（图层栈 + 四个覆盖页分支）→ `AppLayerStack` | `AppRoot` **391 → 356 行**；441 全绿；1 处非注释调用点；**ui 四个大户全部落地**（480/391/369/306 → 397/356/215/214） |
| R22 | 新加的标题生成与压缩摘要各抄了一遍「流式收文本」（Delta / StreamReset 清空 / Failed 抛出）→ `core/llm/LlmTextCollect.collectText` | 两处各去掉 11 行重复；449 全绿；`ChatEvent.StreamReset` 的语义只剩一个实现 |
| R23 | **甲方案第 1 步：先织网**（补 core/agent 的可测性缺口）。把 `AgentLoop` 的三个具体依赖抽成窄端口：`core/llm/TurnLlm.kt`（整条回合只用到 `stream` 一个方法）、`core/data/TurnStore.kt`（10 个存储方法，逐一从调用点量出来）、`core/data/TurnSettings.kt`（9 项设置）；生产实现仍是 `LlmClient` / `ConversationRepository` / `SettingsStore`（类声明加 `: 端口`，**没有适配器层**）。参数默认值搬到接口上 —— Kotlin 不允许 override 重复默认值、但调用方按**静态类型**继承默认值（先用一次性探针编译确认），所以现有调用点一字未改，只有 `settings.modelAcceptsImages()` 一处改成显式传参。再补 `TurnFakes.kt`（脚本化模型 / 内存存储 / 内存设置）+ `AgentLoopGoldenTest.kt` 4 条黄金用例：纯文本一轮（请求 / 库行 / 事件 / 用量逐字钉住）、失败轮只留用户行、显示与快照节点上不上 wire、收件箱认领时机。顺带把 `send` 的 debug 日志自检收进 `runCatching`（纯 JVM 单测里 `android.util.Log` 会抛 not mocked，而它在 finally 里会盖掉真正的行为差异），改由测试直接断言 `SessionLog.violations` 为空 | **449 → 453 全绿**（61 类，连跑 3 次无 flaky）；`runTurnBody` **一行未动**（先有网后拆）；纯 JVM / 无线程 / 无网络；`assembleDebug` + 真机装机启动无 FATAL |
| R24 | `runTurnBody` **第一刀**（R23 的网第一次派上用场）：①请求装配 → `assembleRoundRequest`（`buildMessages` + `Reasoning.wireEffort` + `thinkingWire` + `ChatRequest` 28 行收成一次调用；只被用一次的局部量 `deepseekRoute` 随它进函数）②assistant 这一步的**落库** → `addAssistantRow`（带工具调用 / 最后一步 / 死循环停下三处写入点收成一处形状，`toolCallsJson` 可选）③assistant 这一步的**日志** → `appendAssistantStep`（三处 `SessionBody.Step` 收成一处，`interrupted = true` 同形） | `runTurnBody` **391 → 342 行**（全库第 3）；453 全绿；代码行多重集比对**只在旧侧 17 种全是收口掉的旧写法、只在新侧 34 种全是新函数骨架与调用行**；真机装机启动无 FATAL |
| R25 | `runTurnBody` **第二刀（最大的一刀）**：①本轮记账与这一步的流式采样 → 新文件 `core/agent/TurnMeter.kt`（6 个「本轮」+ 9 个「这一步」局部量与局部函数 `persistTurnUsage` 收成一个纯 Kotlin 类：`beginRound` / `markFirstToken` / `resetFirstToken` / `onUsage` / `addToolMillis` / `setSteps` / `endRound` / `stats` / `persist`）②收流 → `AgentLoop.collectRound`（Delta / Reasoning / Usage / ToolCallDelta / StreamReset / Failed 六个分支 + 三个累积器）③补 `TurnMeterTest`（7 用例：usage 取最后一份不累加、stats 是这一步增量而 persist 是本轮累计、ttft 只认本轮第一次、StreamReset 作废重来、没出字就没有样本、工具耗时步数每步归零、没有轮首就什么都不写） | `runTurnBody` **342 → 251 行**（全库第 7）；**453 → 460 全绿**；跨文件多重集比对（旧 AgentLoop vs 新 AgentLoop + TurnMeter）差异只剩**局部量改名**（`cacheHit` → `roundCacheHit` 等）与新类骨架；真机装机启动无 FATAL |
| R26 | `runTurnBody` **第三刀**：①工具派发循环 → `runToolCalls(conversationId, calls, context, log, guard, meter): String?`（原 84 行 for 循环 + 日志补对 + 子调用轨迹 + 图片/交付物落库整体搬出，返回非空 = 死循环判据命中）②死循环计数 → 新文件 `core/agent/ToolCallGuard.kt`（`callSignatures` / `toolCallsThisTurn` 两个局部量收成一个类：`record(name, argsJson)` 返回「该不该停」的原因） | `runTurnBody` **251 → 166 行**（三刀合计 **391 → 166**，-58%）；460 全绿；跨文件多重集比对只在旧侧 9 行（两个局部量 + `aborted`/`break`）、只在新侧 24 行（guard 类 + 函数骨架 + 改名行）；`runToolCalls` 1 处非注释调用点；真机装机启动无 FATAL |
| R27 | **甲方案第二步：工具世界也收成端口**。`AgentLoop` 过去直接拿 `ToolContext`（要 `TermuxRuntime(Context)`，纯 JVM 构造不出来）→ 新窄端口 `core/agent/TurnTools.kt`（只有两样：`workspace` 与 `run(name, argsJson, callId, execToken)`）；生产实现是 AgentLoop 内部的 `ToolContextTools`（补 callId/execToken 与子调用回调后交给真实 `execute`），`send()` 对外签名不变、另加 internal 测试口 `sendWithTools(...)`（与生产口共用同一具 `sendFlow`）；补 `AgentLoopToolRoundTest` 2 用例：①工具轮黄金路径（第一次请求带 run_code 的 schema、第二次请求 = system+user+assistant(带 tool_calls)+tool 结果、库里四行 `user/assistant/tool/assistant`、工具被调一次且 callId=模型给的 id、execToken>0、per-step stats 第一步 steps=1 收尾 0）②死循环判据（第 4 次相同调用**不执行**、库里 9 行、最后一行的正文 = `stoppedText(loopGuardReason(...))`） | **460 → 462 全绿**；`runTurnBody` 仍 166 行（这一步补可测性、不拆行）；**工具轮第一次进纯 JVM 单测**；真机装机启动无 FATAL |
| R28 | `buildMessages`（150 行）按「库里的一行长什么样」拆成四个小函数：`appendContextRow`（快照 / 指令文件链 → user 消息，其余 context 行只是展示节点）、`appendAssistantGroup`（无 tool_calls 发一条；有就把紧跟其后的连续 tool 行收走配对）、`appendToolResults`（每个调用一条 tool 消息 + 「结果未知」兜底 + 图片按 deferContext 作为 user 消息追加）、`appendUserRow`（附件按 contentParts 组装）；`while` 里改成 `index = when (entity.role) { … }` 一个表达式 | `buildMessages` **150 → 43 行**（四个新函数 16 / 59 / 38 / 21）；462 全绿；多重集比对只在旧侧 10 行（5 个分支头 + 3 个 `index++` + `index = cursor`）、只在新侧 37 行（签名 / 返回值 / 调用行），**搬走的正文逐字未变**；真机装机无 FATAL。踩到三个坑（都是编译期抓的）：切片漏了 `forEachIndexed` 的收尾括号（一次报 20 个下游 unresolved）、两处 `index++` 被带进新函数、两个新函数其实要 `suspend`（`userContentPart` / `toolImageContentPart` 是 suspend） |
| R29 | `AgentLoop.kt` 在 R23–R28 之后长到 **1021 行**（成了本模块最大文件，越过审计的 800 行线）—— 把**请求装配整簇**搬成 `core/agent/RequestMessages.kt`：`build()`（原 `buildMessages`）+ 四个分支函数（快照行 / assistant 组 / 工具结果 / 用户行）+ `decodeToolCalls` 扩展 + 两条崩溃修复文案常量 + `pairToolResults`。依赖收成 `TurnStore` + 一个 `systemPrompt(previousPath)` 取值函数（稳定段仍由 AgentLoop 连同 workspace / 设置装配），于是这个类自己就能在纯 JVM 单测里打表 | `AgentLoop.kt` **1021 → 762 行**（回到 800 行线以下）、新增 `RequestMessages.kt` 302 行；462 全绿；多重集比对差异只有**限定名与改名**（`com.adsh.app.core.data.MessageEntity` → `MessageEntity`、`private suspend fun buildMessages(` → `suspend fun build(`、`repository.messages` → `store.messages`、`textContent(assemblePrompt(...).system)` → `textContent(systemPrompt(previousPath))`、两条常量 `const val` → `internal const val`）；真机装机无 FATAL |
| R30 | `Attachments.kt`（538 行，30 个小函数、没有巨型函数）按**关注点**三分：①`Attachments.kt` 242 行 = 附件本身的表示与探测（UserAttachment / RequestImage、encode / decode、句柄文本、媒体类型与边界探测、sha256）②`RequestImages.kt` 169 行 = **图片怎么进请求**（像素预算缩放、质量阶梯编码、`userContentPart`）③`ToolImages.kt` 161 行 = **工具结果里的图片**（encode / decode、信封、deferContext 回灌、落库预算与解码采样尺寸）。跨文件要用的东西就近归位：两个请求图片常量与 `sampleSizeFor` 跟着请求侧走，`attachmentJson` 改 `internal` 共用 | 462 全绿；多重集比对**只在旧侧 1 行**（`private val attachmentJson` → `internal`），只在新侧 22 行（三份文件各自的 package / import + 那行 internal）——**代码行一行没丢**；真机装机无 FATAL |
| R31 | `ChatViewModel.startTurn`（307 行，全库第 5）**先织网再拆的第一刀**：104 行「事件 → 界面状态」的纯判定 → 新文件 `ui/TurnStreamState.kt`（`turnOpened` / `turnIdentityResolved` / `turnStatsOf` / `turnFinished` / `turnProjected` 八个事件分支 + 私有 `turnAppended`），并把 `imageRouteHint` 的 `settings.modelLabel()` 提成参数（这条判定因此完全纯）；`collect` 的 when 从 11 支缩到 5 支（重连两支仍归 `setConnection` 的计时器、trace 五分支抽成 ViewModel 内的 `traceAppended`、`else -> Unit` 换成纯投影） | `startTurn` **307 → 189 行**；`ChatViewModel.kt` 1755 → 1648；**480 全绿**（462 + `TurnStreamStateTest` **18 例**：开轮 12 字段、轮身份两种口径、绝对值统计、Step 定稿同帧、ToolResult 截断口径、Stats 累加、StreamReset、失败文案带图/不带图、重连与 Usage 原样返回、`imageRouteHint` 打表）；多重集比对差异全是可解释的写法差（`it.`→`current.`、`_state.update { it.copy(` 包裹消失、9 条分支头、新增 6 个函数头）；真机装机启动无 FATAL |
| R32 | `startTurn` 剩下的两段编排各起一个名字（**纯搬运**）：**事件循环** → `collectTurn(conversationId, events)`（49 行；掉线重连两支仍留给 `setConnection` 的计时器、trace 留给 ViewModel 的 `traceAppended`、Appended 的两处 IO 保持顺序）、**收尾** → `finishTurn(conversationId, stopped)`（33 行；`withContext(NonCancellable)` 跟着进函数体，`stopped` 走参数**不提字段**） | `startTurn` **189 → 111 行**（两刀合计 **307 → 111**）；480 全绿；多重集比对**只在旧侧 1 行**（`agent.send(...).collect { event ->`）、只在新侧 7 行（两个调用点 + `events.collect {` + 两个函数签名 + 两处收尾括号）——**正文一行未改**；真机装机启动无 FATAL |
| R33 | `startTurn` 最后两段装配各起一个名字（**纯搬运**）：**开轮输入** → `openTurnInputs(conversationId, body, persistUser, attachmentPaths, turnKey)`（39 行：系统提示词 / 上下文注入落库 → 用户消息落库并上屏 → 轮身份 → 侧栏刷新 → 异步标题；顺序即 dsh 的节点顺序）、**工具上下文** → `buildToolContext(conversationId, workspacePath)`（30 行：14 处 `settings.` 与 30 个实参逐字照搬，含 `livePermission` 实时读取器） | `startTurn` **111 → 44 行**（三刀合计 **307 → 44，-86%**）；480 全绿；多重集比对旧侧 1 行（`val toolContext = …ToolContext(`）、新侧 14 行（两个函数签名 + 参数 + 两个调用点 + 括号）——**正文一行未改**；真机装机启动无 FATAL。**startTurn 到此收官**：剩下的 44 行是「开轮 → 装配输入 → 跑一轮 → 收尾」的骨架，catch/finally 的时序一个字没动 |
| R34 | 把 R24–R33 每刀都手写一遍的「等价性多重集比对」固化成脚本 `scripts/check-move.py`（`--old 旧文件 --rev 提交 --new 新文件…`，去注释去空白逐行计数后给「只在旧侧 / 只在新侧」两份清单；`--allow-old/--allow-new` 可当门禁，超出即非零退出；顺带打注释行数变化） | 纯工具提交，无行为改动；用它复跑 R33（`--rev HEAD~2`）得到与当时一致的两侧清单；已写进审计 §6 的复现命令 |
| R35 | ui 四个大户的**只读体检**结论落地第一刀：**剩余熵不是「函数太长」**（ChatScreen 253 个代码行里 94 行是参数/转发、约 45 行 remember/effect，已无 ≥20 行逻辑块；AppRoot/DshComposer/TerminalPanel 同理，见审计 §3 新增的三条「不做」）→ ①删死代码：`ChatScreen.kt:237-238` 的 `focusManager`（`clearFocus` 早已随 R14 搬到 `ChatTranscript.kt:95`）与 `Composer.kt` 的 `enabled: Boolean = true` 死参数（唯一调用点不传、恒 true）②**拆掉「默认值兜底」**：`ChatScreen` 27 个、`DshComposer` 19 个默认值删除（两者各自只有一个调用点，且今天已全部显式传参）—— 以后漏接一条线是**编译错误**，不再静默降级 | 480 全绿；`check-move` 逐条对得上（ChatScreen only_old=28 / only_new=27 = 27 个默认值行 + 1 行 focusManager；Composer only_old=21 / only_new=19 = 19 个默认值行 + 死参数两行）；真机装机启动无 FATAL |
| R36 | 「用户自己发的消息」（`role = user` 且**无 name**）这条判定全库**六处各写一遍、零用例** → `ui/ChatDecisions.kt` 的 `isOwnUserMessage` / `lastOwnUserMessage` / `lastOwnUserId`；会话页的 ownInput、这一轮的身份（`turnIdentityResolved`）、统计口径（`turnStatsOf`）、附件指路（`imageRouteHint`）、`buildToolContext` 的轮次统计五处改成调用它；补 2 个用例（带 name 的通知 / 插话不算自己发的；**通知落库不恢复跟随** —— 这条失效的后果正是「一条后台通知把正在翻历史的读者拽回底部」） | 480 → **482 全绿**；`check-move`：ChatScreen 3 → 1、TurnStreamState 7 → 3、ChatViewModel 1 → 1（都是那段判定的行数，只搬不改）；真机装机启动无 FATAL |
| R37 | 触发菜单的**可见性算式**（10 行：活触发词 → 精确命中 → typed/launcher 两条路径 → 选行 → visible）→ `Palette.kt` 的 `paletteViewOf(overlays, draft, commands): PaletteView<T>`（泛型到 `PaletteEntry`，因为 `PaletteCommand` 带 `ImageVector`、纯 JVM 单测加载不了）；主函数里只剩一行调用 + 两处实参 | 482 → **491 全绿**（PaletteTest 补 9 例：没触发词 / 刚敲斜杠 / 键入查询 / 打全让位 / 有空白 / 一条不命中 / dismissed 与改草稿重新武装 / 「+」按共存过滤 / **两条路径同时成立时 launcher 赢**——这条优先级以前没有任何断言）；`check-move` only_old=12 / only_new=3 **与方案预测逐字一致**；真机装机启动无 FATAL。**ui 这一轮到此收官**（见审计 §3 的三条「不做」） |
| R40 | **死代码清扫**（子代理只读排查 → 我逐条 grep 复核 → 只删证据充分的）：①删 **19 行未用 import**（`Attachments.kt` 9 / `DshIcons.kt` 4 / `DshMenuIcons.kt` 6，都是搬文件后剩下的；用 `scripts/remove-unused-imports.py --apply`）②`LatexCore.atom` 的三个参数（`stops` / `stopCommand` / `stopRowBreak`）**函数体一次都没读过**（边界判定其实在 `sequence()` 里）→ 删参数 + 简化 4 个调用点 ③`AgentLoop` 里一段重复 KDoc（同一句「必须 flowOn(IO)」写了两遍）+ 6 个连续空行 ④过期注释：`TurnDecisions.kt` 写 `runTurnBody` 397 行（现 166）、`ChatDecisions.kt` 一处悬空 KDoc（它描述的常量在 `ChatBars.kt`） | 498 全绿；`remove-unused-imports.py` 复查 **0 行**；编译器 `never used` 警告 **0 条**；判为**保留**的三处（Hairline 刻度 / 调色板令牌镜像 / `modelId` 钩子）写进审计 §3「不做」表并就地注明理由；真机装机启动无 FATAL |
| R41 | 修粘滞 Ctrl 那一处的**熵减**：`TerminalPanel.onDraftChange` 里三条分支（粘滞 Ctrl / 换行即执行 / 普通编辑）连状态写入长在 Composable 里 → 判定搬成 `TerminalInput.kt` 的纯函数 `draftOutcome(before, after, ctrl): DraftOutcome`（sealed：`Control` / `Submit` / `Typed`），面板只剩一个穷尽 `when` 落副作用 | `onDraftChange` **26 → 24 行**但只剩「落副作用」（判定全在纯层）；**498 → 504 全绿**（+6 例：普通编辑/退格、Ctrl 取插入点那一个（含行中插入的 R38 回归）、Ctrl 遇删除不当控制键、回车提交并留最后一段、单独空回车也提交、多行里的空行不提交）；`check-move` 差异只有调用点重写；真机装机启动无 FATAL |
| R42 | **切会话不卡顿**：把「先收起抽屉、读完再换正文」改成**内容就位再收起** —— `ChatViewModel.switchConversation` 改成 `suspend`：缓存命中时正文这一帧就位（统计 / 上下文占用随后由协程补，不挡收起）；未命中时**先等正文读出来**（正常 1~5ms，上限 150ms，超时就先收、正文晚一帧到）；`openConversation` 新增 `knownMessages`，命中时不再重复读库。`AppRoot` 的 `onSelectConversation` 在一个协程里 `await switchConversation(id)` 之后再 `closeDrawer()` | 504 全绿；这是「切会话掉帧」的第一号来源（抽屉滑进来的那几帧里正文整块换掉 = 闪一下 / 卡一下）；本地基准：`buildChatItems` 200 条消息 ≈ 0.5ms、`parseMarkdown` 100 条长正文 ≈ 8ms（JVM 桌面），可见窗口约 8 条 → 切会话那一帧的纯计算不是瓶颈，瓶颈是**内容换入换出的时机**；真机装机启动无 FATAL |
| R43 | **换模块：设置页**（`SettingsScreen.kt` **2573 行、40+ composable、0 单测** —— 全库最大文件，也是最后一个没有任何测试的大户）。第一刀先把「容量字段」那两条纯函数从 composable 文件搬到新文件 `ui/SettingsModels.kt`（本模块纯逻辑的落脚点），并把三态语义写进 KDoc：`null` = 解析不了（调用点据此**不改**已存的值）/ `0` = 用提供方默认值 / 其余 = 具体容量 | `SettingsScreen.kt` **2573 → 2549 行**、新增 `SettingsModels.kt` 52 行；**504 → 515 全绿**（`SettingsModelsTest` 11 例：0 与负数没有写法、1024 的整数倍写 K（1536 这类按原样写）、M 优先于 K、1_000_000 这类十进制原样、写入读回同值、空串与 0 与负数都落 0、`1.5K` / `12x` / `K` 返回 null、K/M 大小写与前后空格、`+5`、**超出 Long 回绕成负数（现状，用例钉住不改）**）；`check-move` only_old=2（两行 `private fun`）/ only_new=3（两行 `internal fun` + 新文件的 package）——**正文一行未改**；**变异测试**：K 改 1000、M 的判定改十进制，两次都各 2 例失败（说明这张网会响）；真机装机启动无 FATAL。本模块后续（同一手法，一刀一提交）：添加卡片的就绪/提示算式 → 提供方卡保存 → 提供方清单增删 → 服务端模型清单的过滤与采纳 → 模型行的改名/删行 → 视体量再谈文件切缝 |
| R44 | 设置页第二刀：**添加卡片的就绪算式**（原先是 7 个局部量 + 一个 `when` 长在 `CustomProviderEditor` 里）→ `SettingsModels.kt` 的 `ProviderDraft` / `ProviderFormState` / `providerFormState()` / `providerCreated()`；「创建提供方」按钮那一坨 9 行 `ProviderDef(...)` 收成一次调用；「至少一个模型」文案提成常量 `PROVIDER_NEEDS_MODELS`（编辑卡保存时的报错下一刀要用同一句） | `CustomProviderEditor` 的 7 个局部量 → 两行（`draft` + `form`）；**515 → 526 全绿**（+11 例：没写 route 不报错也不提示 / route 不合法与已被占用各是一种错且不在卡底重复说 / 地址必须 http(s) 且判 **trim 之后** / 只有空格的地址算写错不算没写 / 地址提示优先于模型 / 手写的至少一个模型 / ID 全空白不算数 / **目录 route 允许先不写模型** / 填齐就绪 / 创建时的四处规范化 / 目录 route 不打自定义标签）；`check-move` 调用点侧 only_old=35 / only_new=13（全是表单改名与那坨构造点）、纯函数侧 only_old=0 / only_new=51（**只增不改**）；**变异测试** 3 处（目录 route 不再允许空模型 / hint 的 gate 去掉 baseInvalid / custom 恒 true）各 1 例失败；真机装机启动无 FATAL。**两处自查**：替换脚本二次命中把 `form.ready` 写成 `form.form.ready`（提交前 grep 抓到，编译器同样会拦）；初稿的 `ProviderFormState.hasModels` 三处用法全在被提纯的那段里 → 调用点一处不读，按「只写不读不进库」删掉字段 |
| R45 | 设置页第三刀：**编辑卡的「保存」转移**（原先写在按钮 onClick 里的 12 行：密钥留空 = 保持原密钥 / 地址 trim / 目录为空则**整条不保存**）→ `SettingsModels.kt` 的 `ProviderSave`（sealed：Ok/Failed）+ `providerSaved()`；顺带把目录头上那句「正在使用适配器默认模型 / 已自定义模型目录」的判据也提成 `providerOverridden()` | 保存的 onClick **12 行 → 6 行**，且是一个穷尽 `when`（「报错时不写库、也不清密钥框」变成**编译期**保证）；**526 → 534 全绿**（+8 例：密钥留空保持 / 填新值去空格、地址留空保持 / 填新值去空格、目录为空整条不保存且文案与创建同一句、保存不动身份字段、**保存不过滤 ID 全空白的行（与创建不一致 —— 现状钉住并已单独报给用户）**、内置用默认目录不算已自定义、自定义恒为已自定义、目录里加的提供方与 DeepSeek 默认目录不同因此也算已自定义）；`check-move` 调用点侧 only_old=11 / only_new=6、纯函数侧 only_old=0 / only_new=23（**只增不改**）；**变异测试** 3 处（忽略密钥框新值 / 地址留空时清掉地址 / 自定义不再恒为已自定义）各 1 例失败；真机装机启动无 FATAL、crash buffer 空 |
| R46 | 设置页第四刀：**提供方清单的增 / 存 / 删**（原先散在三个 lambda 里的清单转移 + 「当前提供方 / 当前模型」的兜底）→ `SettingsModels.kt` 的 `ProviderSelection`（两个可空字段是**「要不要写」的哨兵**，不是可能为空的取值）+ `providersSaved` / `providersDeleted` / `providersAdded`；`ModelsSection` 新增一个 `applySelection` 落副作用 | 三处调用点各收成一行（`check-move` 调用点侧 only_old=7 / only_new=7，**SettingsScreen 代码行 2140 → 2140**：逻辑出去、接线进来）、纯函数侧 only_old=0 / only_new=30（只增不改）；**534 → 544 全绿**（+10 例：保存替换同 id 且顺序不变、当前模型被删掉时落到该提供方第一条、模型还在就不写、目录空了不写、保存别的提供方不动当前模型、删非当前只改清单、删当前落到剩下第一条、删到一条不剩写空串、**删除不动当前模型（哪怕它正属于被删的那个提供方）**、新增追加到末尾）；**变异测试** 3 处（去掉保存的兜底 / 删除不回落 / 保存顺手写当前提供方）分别 1、2、3 例失败；真机装机启动无 FATAL、crash buffer 空 |
| R47 | **按 dsh 修「空白 ID 的模型行」**（用户点名：添加模型后不填 ID 不能保存、保存键变暗点了没反应）—— 先把 dsh 的原实现从 `app.asar` 里取出来核口径（`@deepseek-ai/dsh-client-ui-settings-models/lib/client.js` 的 `validateDeepSeekModels` 343-372 与 `EditorFooter.submitDisabled` 1828），再照它做：`SettingsModels.kt` 新增 `firstInvalidModel`（ID 空 → `ID_REQUIRED`；**trim 后**重复 → `ID_DUPLICATE`；只报第一处）与 `modelProblemText`（`模型 N: 原因`，N 从 1 数、半角冒号，zh 字典逐字）；编辑卡保存键改 `enabled = modelProblem == null` 并按 dsh 用**三级色 advancedHint** 在按钮上方说明是哪一行；添加卡的 `ready` 从「有一行非空」改成「有行就每行都得合法」（空目录仍按 ADSH 既有偏差只对目录 route 放行） | **544 → 553 全绿**（+10 例、改 2 例；被替换掉的正是 R45 里那条「保存把空 ID 行原样落盘（现状）」——那就是用户要求改的行为）；**变异测试** 4 处（空 ID 不拦 / 重复不去空格 / 有不合法的行也就绪 / `providerSaved` 不兜那道校验）分别 6、2、3、2 例失败；真机装机启动无 FATAL、crash buffer 空。**保留的一处差异**（写进 KDoc 免得被当成漏改）：dsh 的**编辑卡允许存一份空目录**（它靠「模型选择器中将不显示任何模型」那句空态提示），ADSH 仍保留「至少一个模型」守卫（`恢复默认模型` 与那句空态都建立在它上面），要与 dsh 完全对齐得单独一轮 |
| R48（工具） | `scripts/dsh-extract.py`：一条命令从 dsh 的 `app.asar` 里取原实现（asar 头自己解析：开头 8 字节 = [4, pickle 长度]，JSON 在 pickle[8:]，文件 offset 相对 8+pickle 长度）；`--list <关键字>` 列命中项、默认写到 `.git/dsh-<名字>` | 纯工具提交；用脚本重取 R47 手工取过的 `dsh-client-ui-settings-models/lib/client.js`（186760 字节），md5 **逐字节一致**；输出落在 `.git/` 下（不进工作区、不会被提交） |
| R49 | 设置页第五刀：**拉模型弹窗**的候选 / 过滤 / 全选 / 采纳 → `SettingsModels.kt` 的 `fetchCandidates` / `candidateFilter` / `allVisiblePicked` / `toggleAllVisible` / `adoptedModels`（弹窗里只剩接线）。动手前先把 dsh 原实现取出来核口径：`normalizedCandidateQuery`(client.js:713-714)、`toggleVisibleCandidates`(716-723)、`adoptPicked`(694-704)、`fetchModels`(660-688) | **553 → 561 全绿**（+8 例：候选排除目录里已有的且保持服务端顺序、搜索 trim 后大小写不敏感、全选按钮的文案判据、**全选是并集**、可见全选时点它=取消全选且连看不见的一起清、添加所选按服务端顺序、只取选中的、补内置档案（图片能力跟着回来））；**按 dsh 修了三处**：①搜索**先去前后空格**（原来输入「 gpt」一个都匹配不到）②全选从「替换成可见的」改成**并集**（原来会把被搜索过滤掉的已选行悄悄丢掉，接着点添加所选就少加一个模型）③采纳按**候选/服务端顺序**（原来是 `selected.sorted()` 按字母序）；并按 dsh 把新候选**默认全选**（dsh 的 `picked` 初值就是 found 里目录还没有的那些）；`check-move` 调用点侧 only_old=11 / only_new=7、纯函数侧 only_old=0 / only_new=13；**变异测试** 4 处（不去空格 / 替换式全选 / 字母序 / 不排除已有的）各 1 例失败；真机装机启动无 FATAL |
| R50 | **修用户实测报的 bug**：点「添加模型」添一行空 ID → 点「取消」→ 再点开，那一行还在。根因不是保存逻辑，是**草稿状态的生命周期**：编辑卡的五个草稿（key / baseUrl / models / fetching / saveError）写在 `ProviderCard` 顶层，而收起时这张卡**没有离开组合**（只是里面的 `if (editing)` 分支不画了）→ `remember(provider)` 自然记着草稿。修法：五个 key 都带上 `editing`（收起即回到已存档案、再点开重新起一份草稿）。dsh 里这件事由**组件边界**保证：编辑器是独立的 `ProviderEditor`（`client.js:1509`），草稿是它自己的 `useState`（1511），收起即卸载 | `check-move` only_old=5 / only_new=5（正好是五行的 key，代码行 2145 → 2145）；**561 全绿**（这条改不了单测 —— Compose 状态生命周期在纯 JVM 里没有对应物，验证靠真机手测）；真机装机启动无 FATAL；顺带一并修掉的两处同类问题：取消后**残留的保存报错**与**残留的「获取可用模型」弹窗**（`fetching` 也是草稿之一） |
| R51 | 设置页第六刀（**接着 R50 那个 bug 的熵减**，用户点名「改好 bug 后记得做一下熵减」）：模型目录的**行内编辑** → `SettingsModels.kt` 的 `modelEdited`（dsh 的 `update`）/ `modelsRemoved` / `reindexOnRemove`（dsh 的 `remove` 对 `expanded` 的处理）/ `toggleExpanded`；`ModelCatalogEditor` 里 5 处 `models.toMutableList().also { it[index] = model.copy(…) }` 与手写的展开切换全部换成调用 | 顺手修掉**第二个真 bug**：删行之后 `expanded` 里的下标不跟着挪 ——「展开第 3 行 → 删掉第 1 行」会变成展开错的行（dsh 的 `remove` 明确做了 reindex，ADSH 一直没做）；**561 → 567 全绿**（+6 例：改一行只动那一行、越界下标是空操作、删行保留顺序、删行后展开集合前移、被删那一行自己的状态丢掉、展开/收起）；`check-move` 调用点侧 only_old=11 / only_new=8、纯函数侧 only_old=0 / only_new=14；**变异测试** 3 处（不挪 expanded / 保留被删行的展开状态 / 删最后一行）分别 2、1、2 例失败；**变异测试还替我发现一处死代码**：给 `modelEdited` 加的 `if (index !in models.indices) return models` 拿掉后 567 例仍全绿 —— 因为 `mapIndexed` 结构上不可能越界，guard 已删、这个反例写进 KDoc；真机装机启动无 FATAL |
| R52 | 设置页**文件切缝第一刀**：**模型区整簇**（1005 行 —— `ModelsSection` / `ProviderCard` / `ModelCatalogEditor` / `FetchModelsDialog` / `CustomProviderEditor` / `AddProviderButton` / `AddProviderModeSwitch` / `DeleteProviderDialog` / `CapacityField` / `AddModelButton` / `DangerSmallButton` + 四个 MODE/Hint 常量）→ 新文件 `SettingsModelsSection.kt`；共用小件簇的 18 个声明与 `SectionColumn` 由 `private` 改 `internal`（同包跨文件的最低可见性）；`scripts/remove-unused-imports.py` 清掉两个文件共 50 行 import | `SettingsScreen.kt` **2543 → 1525 行**（-1018）、新增 `SettingsModelsSection.kt` 1070 行；**567 全绿**（纯搬运，判定一行没动）；`check-move` only_old=30 / only_new=69 —— **旧侧那 30 行全是函数头**（11 个搬走的 + 18 个小件 + `SectionColumn` 的可见性），新侧 = 同样 30 行改成 `internal` + 新文件的 38 行 import + package 行，**正文一字未改**；真机装机启动无 FATAL |
| R54 | 设置页**文件切缝第二刀**：**功能区整簇**（397 行 —— `FeaturesSection` / `TerminalPlugin` / `AgentLoopPlugin` / `WebSearchPlugin` / `WebSearchBackendDialog` / `WebBackendMark` + 两个品牌色常量）→ 新文件 `SettingsFeaturesSection.kt` | `SettingsScreen.kt` **1525 → 1123 行**（两刀合计 2543 → 1123，-56%）、新增 `SettingsFeaturesSection.kt` 442 行；**567 全绿**（纯搬运）；`check-move` 差异只有「7 个函数头的 private→internal + 新文件 import/package」；真机装机启动无 FATAL。**同时修掉 R52 的副作用**：`remove-unused-imports.py` 在 Windows 上把整文件写成 CRLF（见 R53-工具） |
| R55 | 设置页**文件切缝第三刀**：**共用小件整簇**（477 行 / 18 个声明：卡片与分隔线、药丸与标签、输入框、按钮、勾选框）→ 新文件 `SettingsParts.kt` | `SettingsScreen.kt` **1123 → 629 行**（**三刀合计 2543 → 629，-75%**）；**567 全绿**（纯搬运）；`check-move` 差异只有新文件的 import/package（小件的可见性在 R52 就已改成 internal，这一刀可见性改写 0 处）；行尾全部 `i/lf w/lf`；真机装机启动无 FATAL。**设置页到此分成五个文件**：外壳 + 通用设置 `SettingsScreen.kt` 629 / 模型区 `SettingsModelsSection.kt` 1070 / 功能区 `SettingsFeaturesSection.kt` 442 / 共用小件 `SettingsParts.kt` 541 / 纯逻辑 `SettingsModels.kt` 406 |
| R56 | **换模块：core/data 第一刀** —— `claimInjected` 的 name 改写（生产的 `ConversationRepository` 与测试替身 `TurnFakes` **各写一遍**、后者注释还写着「逐字一致」）→ 同包纯函数 `core/data/InjectedNaming.kt` 的 `injectedName(name, index, openTurn)`，两处调用点都改成调它（规则只剩一份） | **567 → 577 全绿**（`InjectedNamingTest` 10 例：三个 name 的字面值（落进历史 name 列，改了读不出旧会话结构）、有人接时原样保留、别的 name（权限切换通知）也原样保留、空 name 两条路径、没人接时插话去掉 steering 标记、第一条任务通知换唤醒形态、后面几条保持注入形态、**下标 0 是插话时后面那条仍是注入形态**（「第一条」是整批下标 0，不是「第一条通知」）、整批都是通知时只有第一条变、顺序不变）；`check-move` only_old=5（就是那 5 行分支）/ only_new=7（同样 4 条分支 + 函数头 + package + 新调用点）；**变异测试** 5 处（插话不清标记 / 每条都当轮首 / 判据用错常量 / else 不保留 / 有人接时也清插话）分别 3、4、2、5、2 例失败；真机装机无 FATAL。**诚实说明**：在 dsh 的 asar 里搜不到 `JOB_NOTICE`（这是 ADSH 自己的常量名），所以这一刀按现有 KDoc 记的 `ReactLoopInbox` 语义钉行为，没有 dsh 原实现可对 |
| R57 | core/data 第二刀：**会话搜索的两段纯逻辑** → 新文件 `core/data/SessionSearch.kt`：`likeLiteralPattern`（用户输入 → SQL LIKE 的字面量模式，**顺序敏感** —— 反斜杠必须最先转义）与 `snippetAround`（命中处前后各 48 字符、换行与连续空白压成单空格、省略号只在真的截断那一侧、找不到 needle 时退回开头且不加省略号） | **577 → 588 全绿**（`SessionSearchTest` 11 例：% 与 _ 都要转义、**顺序写反会多出一层反斜杠**（钉住 3 个 vs 4 个）、普通文字与中文原样、命中在中间/开头/结尾各自的省略号、正文比窗口短、空白压平、大小写不敏感但片段保留原文大小写、找不到 needle 退回开头、radius=0 时片段就是命中）；`check-move` only_old=2（LIKE 那 5 行块的首行 + `private fun snippetAround`）/ only_new=4（两个函数头 + package + 新调用点）—— Database.kt 代码行 672 → 674（5 行转义收成 1 行、snippetAround 原样搬走）；**变异测试** 5 处（转义顺序反了 / 不再转义 `_` / 片段改大小写敏感 / 找不到时返回空片段 / 两侧永远加省略号）分别 2、1、1、1、4 例失败 |
| R58 | core/data 第三刀：**落库时的标题判定** —— `titleFor` 的规则（已有标题什么时候不覆盖 / 首条用户消息用兜底标题 / 其它角色保持）→ `SessionTitle.kt` 的 `sessionTitleFor(existingTitle, content, role)`；同时把「新会话」这个字面量在 core/data 里出现的 **4 处**（建空会话写进去的那一处 + 判据里的三处）收成常量 `NEW_SESSION_TITLE`——写进去的值与比对的判据必须是同一个字符串，否则自动标题永远覆盖不上 | **588 → 595 全绿**（+7 例：已有标题且不是兜底值就保留、空串标题也算已有（现状）、首条用户消息用兜底、还是兜底值就允许重新生成、内容全空白退回兜底、非用户角色不生成标题、非用户角色保留已有标题、常量就是「新会话」三字）；`Database.kt` 的 `titleFor` **7 行 → 2 行**（一次 byId + 一次纯调用），`check-move` only_old=6（读库那行 + 三条分支 + `title = "新会话"`）；**变异测试** 3 处：①任何已有标题都不覆盖（1 例失败）②角色判据反过来（3 例失败）③**else 分支不再看已有标题（0 例失败）** → 证明那里的 `existingTitle ?: NEW_SESSION_TITLE` 是可证冗余（走到该分支时它只可能是 null 或兜底值本身），据此简化成直接给常量、证据写进 KDoc；真机装机无 FATAL |
| R59 | core/data 第四刀：**老扁平配置 → 提供方清单的迁移判定**（`SettingsStore.migrateProviders` 27 行、零用例，判错会让老用户的地址/密钥/勾过的模型消失）→ `Providers.kt` 的 `migratedProviders(legacyBase, legacyKey, legacyModels)`；`SettingsStore` 只剩 prefs 读取那一层 | **595 → 603 全绿**（`ProvidersMigrationTest` 8 例：官方地址只留内置且密钥照搬、**带尾斜杠也算等价且尾斜杠原样保留**、勾过的模型按内置目录补齐（显示名/容量/**图片能力**一起回来，未知 id 只剩 id）、没勾过就用内置默认目录、自定义网关包装（id=custom / 主机名 / 地址原样 / custom=true / 模型只有 id）、主机名解析不出来用「自定义提供方」、顺序恒是内置在前、前后带空格的地址会被当成自定义网关（现状））；`check-move` SettingsStore 侧 **only_old=21 / only_new=1**（整段迁移体 → 一行调用），SettingsStore.kt 代码行 **333 → 313**；**变异测试** 4 处（不忽略尾斜杠 / 不查目录 / 不取主机名 / 顺序反过来）分别 1、1、1、4 例失败；真机装机无 FATAL。**挖到一个真问题（已报用户、等口径）**：自定义网关分支把老的扁平密钥**同时**写进内置提供方，而升级用户的 `providerId` 兜底是「列表第一条」= 内置 → 拿着网关的密钥去请求 api.deepseek.com；两条用例已钉住现状，修法两选（①只把密钥给自定义那份 ②①+迁移时把当前提供方指向 custom） |
| R60 | core/data 第五刀：**`modelAcceptsImages` 的三岔**（`SettingsStore` 里 8 行、零用例）→ `Providers.kt` 的 `acceptsImagesFrom(models, modelId)`：①手动拍板（`ModelDef.imageInput` 非空）以它为准（关得掉目录说能收图的、开得起目录说不支持的）②行上的 `acceptsImages` 为真就放行 ③都不认识 → `SettingsStore.defaultImageInput`（**未知模型默认能收图**，第 83 轮用户口径） | **603 → 609 全绿**（ImageInputTest +6 例：手动关掉目录说能收图的、手动打开目录说不支持的、没拍板按行上条目、目录里没这个 id 走默认、**id 精确匹配**（带空格对不上，现状）、**行上的 acceptsImages 比目录默认优先**）；`check-move` SettingsStore 侧 only_old=6 / only_new=2；**变异测试** 3 处：①忽略手动开关（2 例失败）②**不看行上的 `acceptsImages`（第一次 0 例失败 = 我的网眼）** ③默认那条改成 `false`（2 例失败）—— ②补了一条「行说能收图、目录说不支持」的用例之后**它响了**（那条规则不是死代码，是没被断言到）；真机装机无 FATAL |
| R61 | **按 dsh 修 R59 挖到的迁移 bug**（用户点名「完全可以参照 dsh」）：dsh 的凭据是**按提供方路由各一份**（`deriveKeyRef(provider)` = `<PROVIDER>_API_KEY`，dsh-settings-models 的 `refFor`/1502）→ ①自定义网关分支里老的扁平密钥**只归自定义那份**，内置提供方不再拿别人的密钥；②`migratedProviders` 改成返回 `MigratedProviders(providers, providerId)`，迁移出自定义网关时把**当前提供方一起指过去**（否则 `providerId` 的兜底「清单第一条」= 内置，老配置等于没用上）；熵减部分：把这条兜底抽成 `currentProviderIdOf(stored, providers)`，`SettingsStore.providerId` 收成一行 | **609 → 613 全绿**（ProvidersMigrationTest：内置不再拿到网关密钥、网关密钥只归自己、迁移指当前提供方、官方分支不动当前提供方、存过的 id 还在就用它、**不在就落到第一条（含「第一条不是内置」那条能区分的）**、清单为空才回落 DeepSeek）；`check-move` SettingsStore 侧 only_old=8 / only_new=5；**变异测试** 4 处：①密钥又同时给内置（1 例失败）②迁移后不指当前提供方（1 例）③**兜底不再看第一条（第一次 0 例失败 = 网眼）** ④地址比较不忽略尾斜杠（1 例）—— ③补了「清单第一条不是内置」的用例后**它响了**；真机装机无 FATAL。**图片能力**按用户口径再核了一遍 dsh（`ModelInputTypes` 171-201：行上模态 → 目录 fallback → `["text"]`），ADSH 前两层一致、最后一层保持「未知默认能收图」，对照写进 KDoc |
| R62 | core/data 第六刀（**这个模块最后一块零测试面**）：网页搜索那几个「存在 prefs 里的值」的判定 → 新文件 `WebSearchSettings.kt`：`webStoredKey`（偏好键命名 —— 内置 DeepSeek 沿用历史键，**这是升级路径的一部分**）、`webSearchBaseUrlFor`（旧默认端点 `https://api.deepseek.com` 当作没设置过，比较忽略尾斜杠）、`webSearchApiKeyFor`（自己存过以它为准；内置没存过回落到 DeepSeek 提供方那把密钥 —— dsh 的会话凭据语义；**其它后端不回落**）、`webSearchKeyFollowsProvider`；`SettingsStore` 四处判定收成调用、私有 `webKey` 删除、`LEGACY_WEB_SEARCH_BASE_URL` 改 internal | **613 → 624 全绿**（`WebSearchSettingsTest` 11 例：键名两个分支、四个后端各自的默认地址、存过就用存的（不规范化用户写法）、**旧默认端点带尾斜杠也算**、纠正**只对 DeepSeek**、自己存过以它为准、内置没存过回落、**空白也算没存过**、其它后端不回落、**惰性**（不回落时一次都不读提供方清单）、跟随判定与其惰性）；`check-move` SettingsStore 侧 only_old=24 / only_new=18（旧侧是四处判定 + 私有 `webKey` + `webKey(` 的 7 个调用点，新侧是四处调用 + `webStoredKey(`），代码行 306 → 300；**变异测试** 5 处（不忽略尾斜杠 / 纠正不限定后端 / 空白当存过 / 其它后端也回落 / 键名不区分后端）分别 1、1、2、2、1 例失败；真机装机无 FATAL |
| R63 | **深度死代码清理**（用户点名「先不着急下一个模块」）。静态扫描器全是 0 候选（未用声明 / 文件内私有 / 未用 import / 未用资源 / 版本目录条目 / C 静态符号），于是换五个角度：①**编译器全部警告**（重跑后只有 2 条 deprecated，且注释里写明的**有意保留** —— minSdk 26 上只有那个兼容入口，不动）②六个图标文件 61 个图标逐个引用数（**0 个孤儿**）③**清单里的死声明**：`ACCESS_NETWORK_STATE`（全库没有 ConnectivityManager / NetworkCallback）、整段 `<queries>` 与 `FileProvider` + `res/xml/file_paths.xml`（「打开方式」这个功能在代码里已经没有了：全库没有 `queryIntentActivities` / `ACTION_VIEW`，工作区改走 SAF 树 URI）④死参数检测器（23 个候选**逐条看原文全是假阳性**：检测器把 `= when (x) { … }` 的选择器当成「没用到」，另有 `LinkedHashMap.removeEldestEntry` 这类 override 与接口要求的空实现 `Jobs.Hooks.cancel(reason)`）⑤孤儿文件（ui/ 下对外零引用：0 个） | 清单 **57 → 33 行**、`file_paths.xml` 删除；**624 全绿**（一个用例都没改）；真机装机启动无 FATAL、crash buffer 空。**没动**的两条 deprecated 写进提交信息（minSdk 26 的兼容入口 / WEBP 在 R 之前只有旧常量），理由留在代码注释里 |
| T0 | **把 /system/bin 放进 bash 的 PATH**（用户要「给 AI 一点操控手机的能力」，先做零权限的只读认知层）。改动一行 + 一段实测口径注释：`TermuxRuntime.environment()` 的 `PATH` 从 `p + "/bin"` 变成 `p + "/bin:/system/bin"`（前缀的 bin **在最前**，termux 自己的工具不会被系统同名工具盖掉） | 全库只有这一处 PATH 赋值；**624 全绿**；设备上以「termux-exec shim + 新 PATH」实测 `pm list packages` / `logcat` / `getprop` 都能跑（SDK 36）；注释里写明**哪些能用、哪些被系统拒绝**（`am start` / `monkey` / `input` / `screencap` / `settings get` 全是 shell 专属工具，失败信息会如实回到工具输出） |
| T1 | **phone 通道**（用户口径：只做 T0+T1、不碰硬件、不加提示词）：`$PREFIX/bin/phone` 脚本（ADSH 启动时写入、随版本覆盖）+ ADSH 侧轮询 `<filesDir>/phone/{requests,responses}`，用**应用自己的前台身份**做 bash 做不到的三件事：`phone open <网址\|包名\|域名>`（startActivity）、`phone info`（屏幕/电量/网络/ADSH 版本）、`phone clip get\|set`（剪贴板；Android 10+ 不在前台时如实报「读不到」）；`phone help` 是**发现入口**（提示词一个字没加）。协议层是纯函数（`core/phone/PhoneProtocol.kt`），Android 侧在 `runtime/phone/PhoneChannel.kt`，`AdshApp.onCreate` 接线；`ACCESS_NETWORK_STATE` 因为 `phone info` **重新加回**（R63 删它是因为当时确实没人用） | **624 → 630 全绿**（`PhoneProtocolTest` 6 例）；**设备端端到端实测**（run-as + termux-exec shim，与 ADSH 的 bash 同 UID）：`phone info` → 「屏幕：亮 / 电量：94% / 网络：已连接 / ADSH：0.2.0-debug（SDK 36）」rc=0；`phone clip set` → rc=0；`phone clip get` → 「读不到（应用不在前台时系统不允许读）」rc=1（如实）；**`phone open https://example.com` → rc=0，前台真的变成浏览器 `mark.via`**；未知命令 → 用法错；协议纯函数的变异测试 3 处各 1 例失败；真机装机无 FATAL |
| T1-熵减 | T1 那一刀的**熵减**（用户点名「做完记得做一下熵减」）：①删掉我为凑 `data class` 在 `PhoneRequest.ClipGet(val unused: Unit = Unit)` 里引入的**假字段** → `data object ClipGet`；②`phone open` 的分支优先级（网址 → 已安装的包 → 域名 → 不支持）提纯成 `resolveOpenTarget(target, packageInstalled)`（`PhoneOpenTarget` 四种结果；包管理器探针**惰性** —— 网址那条路一次都不查）；③`phone info` 的正文从 channel 里那段「取事实 + 拼字符串 + 一条提前 return 把前两行抄一遍」→ 纯函数 `phoneInfoText(screenOn, batteryPercent, networkConnected, versionName, sdk)`，channel 只剩取四个事实 | **630 → 636 全绿**（+6 例：带 scheme **不查包管理器**（惰性）、包已安装、没装但像域名就补 https、都不像、info 四行、没网时也报全）；**变异测试** 3 处（先查包管理器再看 scheme / 网络永远已连接 / 电量不写百分号）分别 1、1、2 例失败；**设备端回归**：重装后 `phone info` 仍返回「屏幕：亮 / 电量：94% / 网络：已连接 / ADSH：0.2.0-debug（SDK 36）」rc=0 |

| R64 | **换模块：runtime/termux 第一刀** —— `BootstrapInstaller`（572 行；整个 package 只有 `OutputCollectorTest` 一个用例文件）里的**五处判定** → 新文件 `BootstrapPlan.kt` 的纯函数：①`installPlan`（manifest 记的版本 / nativeLibraryDir + 当前两项 → **全量 / 只重链接 / 已装好**）②`secondStagePending`（184 个 postinst 补跑：脚本在 + lock 不在）③`execLinkAction`（`$PREFIX/bin` 一个槽位：建 / 不动 / 已经指对 —— **重链接期只修我们自己建的 `libbin_*.so` 链接**，第 115 轮那个 bug 的判据）④`shouldCreateSymlinkEntry`（SYMLINKS.txt 一条：重链接期只补缺）⑤`copyEntryKind`（回退拷贝一个目录项：**符号链接优先于目录**，第 74 轮那个 `NoSuchFileException` 的判据）。`installLocked` 的三岔从「两层嵌套 `if` + 两处 `return prefix`」收成一个 `return when (installPlan(…))`，全量体抽成 `fullInstall`；顺手把**三处手抄的「这是不是符号链接」**（`removeTree` 里的内联 readlink、`isSymlink`、`copyTree` 里的内联 readlink）收成一个 `readLink`，`isSymlink` 删除 | **636 → 658 全绿**（`BootstrapPlanTest` 22 例：manifest 缺失/读不出/版本不符/版本号文本比较/libdir 变了/键缺失/都对得上、脚本与 lock 的四种组合、安装期一律重建、非链接槽位、别人的链接、旧 nativeLibraryDir 的悬空链接、已指对、**只按 basename 认领**（现状钉住）、SYMLINKS 只补缺、指向目录的链接不递归）；`BootstrapInstaller.kt` 代码行 353 → 371（多出来的是 `fullInstall` 函数头与 KDoc）、新文件 153 行；`check-move` only_old=27 / only_new=84 —— **旧侧 27 行逐条核过**（两个嵌套 if、`sameVersion`/`sameLibDir`、`isSymlink` 与它的两个调用点、`copyTree` 的内联 readlink、三处 `props.` → `props?.`、两处 `return prefix` → `prefix`），没有一处是计划外的；**变异测试 8 处全响**（libdir 不看 / 版本不符也当已装好 / 不看脚本在不在 / 非链接槽位当已指对 / 别人的链接也重建 / 已指对的也重建 / 重链接期不再只补缺 / 拷贝先判目录），分别 2、3、1、1、2、1、1、1 例失败；**真机把两条分支都走到了**：`adb install -r` 让 nativeLibraryDir 从 `~~h3Ht7vZh…` 换成 `~~FtN7dxis…` → 走 Relink（`relinked after app update: symlinks=0, executables=284 in 329ms`，其中 `symlinks=0` + `left alone (not ours): 1213` 正是「只补缺」、dpkg 装的 `bin/xxhsum` 被 Skip 掉且 mtime 仍是 2026-09-19、`bash` 链接已指向新目录），再次启动走 Installed（`already installed: /data/data/com.termux/files/usr`）；两次 crash buffer 都是 0 条 FATAL |

| R65 | runtime/termux 第二刀：**子进程环境的清单装配** —— `TermuxRuntime.environment()`（104 行、零用例）整段搬进 `TermuxEnv.kt` 的纯函数 `shellEnvironment(ShellFacts)`：启动期读到的 17 个事实（prefix / home / 包名 / dataDir / filesDir / uid / pid / **Termux 版本号** / **App versionCode** / targetSdk / sdkInt / APK 来源 / debuggable / preload 清单 / scratch / 围栏基线模式 / 工作区）收成一个 `ShellFacts` 快照，装配只剩字符串拼接；`environment()` 收成「取事实 + 一行装配」。顺手把 `debuggableFlag()` 从「返回 "1"/"0" 的字符串」改成布尔（格式化搬进纯函数，这才测得到） | **658 → 677 全绿**（`TermuxEnvTest` 19 例，每条对着真机上踩过的坑：PATH 前缀优先（T0 口径）/ **路径值不带尾斜杠**（带尾斜杠会被 termux-exec 与 termux-tools 判非法并静默回退到编译期前缀）/ **LEGACY_DATA_DIR 与 DATA_DIR 分开给**（M0 里「termux-exec 实测无效」的真正原因）/ TERMUX__ROOTFS / DPKG_ADMINDIR / Termux 身份五项 / **ANDROID__BUILD_VERSION_SDK 必须导出**（termux-exec 的 postinst 原文要求）/ 三个兼容别名与主键一致 / debuggable 写 1 与 0 / **VERSION_NAME 是 Termux 版本号而 VERSION_CODE 是 App 的**（两个不同的东西）/ uid·pid·targetSdk / 四个临时目录变量一致 / **完全权限的围栏基线显式写 danger-full-access**（第 64 轮的 bug）/ preload 空就不写 LD_PRELOAD / 绑没绑工作区 / **键不重复**（`envInto` 按第一个 `=` 拆键值）/ 每项都是 KEY=VALUE）；`TermuxRuntime.kt` **863 → 785 行**、新文件 167 行；**`check-move` only_old=28 / only_new=69，旧侧 28 行逐条核过全是「读事实的那几行 + 签名 + 旧 debuggableFlag」**，其余 70 多行字面量与全部注释一字未动（这是等价性的主要证据）；**变异测试 8 处全响**（PATH 顺序反 / 两个 data dir 同拼写 / 基线写成 workspace-write / 空 preload 也写 / 没工作区也给 PWD / debuggable 恒 1 / VERSION_NAME 用 App 版本号 / 临时目录带尾斜杠）各 1~2 例失败；真机（SDK 36）装机启动无 FATAL、bootstrap 走 Relink 正常；**同一套值在设备上跑 `adsh-env-check`** 得到 `system_linker_exec=true`、`/proc/self/exe → linker64`、`LD_PRELOAD` 已加载、`TERMUX__PREFIX` 正确 —— 这组值确实驱动得动真机的 termux 用户态。**顺带记一条设备口径**：`adb shell` 后面**只能给一个参数**（给多个时 adb 用空格重拼、设备侧引号分组全丢，`sh -c 'pm list packages'` 会退化成打印用法），要跑复合命令就把整串（含内层引号）当**一个**参数传 |

| R66 | runtime/termux 第三刀：**围栏判定 + 杀进程树顺序 + /proc 解析** → 新文件 `RuntimeDecisions.kt`：①`fencePlan(mode, shimPresent, whitelist)` 的三条「不挂」路（完全权限 / shim 不在 / workspace-write 一个可写根都没有）+ `fenceRoots`（null、空串、`/` 丢掉再去重）+ `fenceVariables`（LD_PRELOAD / ROOTS / MODE / **ACTIVE** / MARK 的唯一装配点）②`killOrder(root, childrenOf)`（**先叶子后根**，另加一道只对畸形表生效的环闸）③`ppidFromStat`（从**最后一个** `)` 之后切） | **677 → 700 全绿**（`RuntimeDecisionsTest` 23 例：白名单三条 / 三岔六条 —— 含 **read-only 空白名单照样挂** 与 **workspace-write 空白名单不挂** 这处刻意的不对称、shim 不在与白名单无关、不认识模式不挂 / 变量清单三条（**空的 `ADSH_FENCE_ROOTS=` 项必须在**、冒号连接、MARK 最后）/ ACTIVE 只在真挂围栏时出现 / killOrder 五条（叶子在前、**每个父都晚于它的子**、查不到的子、同 pid 出现两次只杀一次、**表里有环不死循环**）/ stat 四条（含 comm 带空格与括号））；`TermuxRuntime.kt` **785 → 741 行**、新文件 128 行；`check-move` only_old=21（逐条核过全是搬走的判定行）/ only_new=41；**变异测试 10 处全响**（空白名单写平 / 不看 shim / 不看模式 / 根目录不丢 / 不去重 / 不给 ACTIVE / 不反转 / 去掉环闸 / 从第一个右括号切 / 取错字段）分别 1、1、2、2、1、3、4、2、1、3 例失败；**真机把四种变量形态逐个验了一遍**（用 APK 里真正的那个 shim，四组正是 `fenceVariables` 会产出的形态）：完全权限（没有任何围栏变量）写工作区外 rc=0；`workspace-write + ADSH_FENCE_ROOTS=<一个目录>` 目录内 rc=0、目录外 rc=1 且逐字回 `[sandbox: file access denied under workspace-write mode]`；`read-only + ADSH_FENCE_ROOTS=`（空表）连那个目录里也 rc=1（**证实空表 = 全拒**）；同一环境里 `pm list packages` 正常（T0 的能力没被 shim 破掉）；装机启动无 FATAL。**又一条踩坑**：`MANIFEST.properties` 里 `nativeLibraryDir` 的值含 `==`，按 `=` split 取值会**从中间截断**（截出来的假路径让 shim 报 `CANNOT LINK EXECUTABLE … not found`，差点被我当成真机的 bug 报上去） |

| R67 | runtime/termux **收官**（用户点名的熵减）：①搬进 `TermuxEnv.kt` 的函数体缩进从 8 空格改回 4（脚本按行改，`check-move` 报 **only_old=0 / only_new=0** —— 证明是纯缩进）；②**两处手抄收成一份**：termux-exec 的候选库名（`TermuxRuntime` 的私有 companion 常量 vs `BootstrapInstaller.secondStageEnv` 里内联的 `listOf(…)`，后者还靠一句「候选顺序与 preloadValue 一致」的注释维持 —— R56 同款的两份手抄）→ `TermuxEnv.kt` 的 `TERMUX_EXEC_LIBS`；`"/data/data/" + 包名` 这条规则（原先**三处**手写：运行期 environment、安装期 second stage、apt 缓存的两种拼写）→ `legacyDataDirOf(packageName)` | **700 → 702 全绿**（+2 例：候选顺序固定且 ld-preload 变体在前、旧形态 data dir 只有一条规则且装配出来那个值就是它的结果）；**`check-move` 三处逐条核过**：`BootstrapInstaller` only_old=2 / only_new=2（就是内联的 `listOf` 与那行拼接）、`TermuxRuntime` only_old=5 / only_new=1（两行候选 + `listOf(` + `private val TERMUX_EXEC_LIBS` + 那行拼接 → 一行调用）、`TermuxEnv` 0/0；模块账（主源码 2011 → 2389 行、TermuxRuntime 863 → 745、package 测试 1 文件 5 例 → 4 文件 71 例、全库 636 → 702）与真机证据一并写进审计 §8.2 收官 |

| R68 | **换模块：core/tools 第一刀**（审计 §8.3）—— `renderBash`（`Tools.kt` 里 17 行的私有函数，**模型真正读到的正文**）→ 新文件 `BashRender.kt`：`bashStatusMarkers`（三种标记的**互斥规则**：超时与「被人停掉」各自独立、**有任何一个就不再补退出码**、退出 0 不写）、`renderBashBody`（stdout → `[stderr]` 段 → 每行一条标记，两处换行口径）、`renderBash`（ExecResult 的完整渲染）；同时把这个格式的**第二个读者**也收进来成对维护：`bashStatusMarker` 替掉 `RunCodeTool.digestOf` 里手写的 `lastOrNull` + 两个 `startsWith`（写读两端同一份格式，改一头不会忘另一头）；正文里的 `"(no output)"` 改用 core/agent 已有的 `NO_OUTPUT` 常量 | **702 → 732 全绿**：`BashRenderTest` **18 例**（正常退出不写标记 / 非零写退出码 / 超时**不补退出码** / 被停**不补退出码** / **超时+被停两条都在且超时在前** / 超时且退出 0 只写超时 / stderr 段补换行的两种输入 / 只有 stderr 无前导空行 / 全空是 no output / 空正文+标记 / 标记自成一行 / 多条各占一行 / 读回取**最后**一条 / 行首空白也认 / **不认 stopped**（用户动作不是退出状态）/ 认不出 null / **写读两端一致**），`JobRenderTest` **12 例**（给原先零用例的 `renderJobDelta`/`renderPromoted` 补上：stdout+stderr 分段、LOG 不进模型、只有 LOG 时空串、lossy 与块上 gapBefore **两种**丢弃提示、正文空时提示自成一行、转后台那三行逐字）；`check-move`：Tools.kt only_old=6 / only_new=29（旧侧就是那个私有函数的头、`val markers`、`(no output)` 与三条标记字面量）、RunCodeTool only_old=3 / only_new=1；**变异测试 9 处全响**（停止标记排前 / 不管有没有别的标记都补退出码 / 退出 0 也写 / stderr 段前多补一个换行 / 空正文不写 no output / 标记不补换行 / 读回取第一条 / 读回也认 stopped / 读回不 trim）分别 1、3、1、1、2、5、1、1、1 例失败；真机装机启动无 FATAL。**诚实说明**：这段渲染要模型真的跑一次 bash 才走到，无人值守跑不了 —— 依据是 check-move 的逐字等价 + 30 条用例 |

| R69 | **core/tools 第二刀**（审计 §8.3 的备选，也是「国内站点整页乱码」的唯一防线）：`classifyContentType` / `parseCharset` / `sniffCharset`（`WebTools.kt` 里三个私有函数、零用例）→ 新文件 `WebEncoding.kt`；顺手把调用点那处「头部声明了不认识的 charset 就报错」的三行判定收成 `declaredCharset(contentType)` 的 sealed 结果（Absent / Known / **Unsupported** —— dsh 宁可让这次抓取失败，也不给模型吐乱码），调用点只剩一个 when | **732 → 752 全绿**（`WebEncodingTest` 20 例，**不联网**：类型六条（含 `mytext/plain` 不算、`application/jsonx` 不算、分号后面的参数不参与判定）、头部 charset 五条（常见写法 / 前面还有别的参数 / 等号两边空白与大小写 / 没有就是 null / 引号里带分号只取前一段）、三种下场 + 不认识就报错、meta 嗅探六条（GBK 的大写与单引号 Big5 / http-equiv 长写法 / 没有声明就是 UTF-8 / **不认识的编码回落 UTF-8**（与头部那层口径刻意不同）/ **4096 字节窗口**（声明落在窗口外不认）/ 中文正文截断不炸）；`check-move` only_old=8 / only_new=19 —— **旧侧那 8 行里三个函数只出现签名**（函数体逐行搬到新侧，只把 `private` 改成 `internal`），另 5 行是调用点那处判定；**变异测试 8 处全响**（contains 代替前缀 / 不认 +json 与 +xml / 不切分号参数 / 不转小写 / 等号不容空白 / 不认识的编码回落 UTF-8 / 嗅探看整份正文 / meta 回落 ISO-8859-1）各 1~3 例失败；真机装机启动无 FATAL。**诚实说明**：GBK 那条路要模型真的抓一个 GBK 页面才走到，没在设备上验（Android 与 JVM 都自带 GBK/GB2312/Big5；真缺了的表现是回落 UTF-8 显示乱码，不是崩溃）。**同一坑第二次踩**：新文件的 KDoc 里写了 `text/*`，`/*` 又开了一个嵌套注释 —— 这次是 **import 扫描器先报出来的**（它把整个文件当注释，于是把 `Charset` 那条 import 判成「没用到」），比编译器更早一步 |

| R70 | **文档与复核**（用户问「8.3 与 8.4 做完应该差不多了吧，后面还有模块吗」）：①审计 **§8.3** 从「建议」改成**收官账**（R68/R69 的落点与模块账；剩下没做的三类连理由一起写明：不在模型必需路径上的五处、动格式位的 `ToolSdk.section`、分页/检索这类碰既有行为的）②**§8.4 复核结论：那两刀在 R51 时早就落地了** —— 六项逐一给出实际落点（`fetchCandidates` / `candidateFilter` / `allVisiblePicked` / `toggleAllVisible` / `adoptedModels` / `modelEdited`+`modelsRemoved`+`reindexOnRemove`）与用例数，唯一没提纯的是「加行」那一行（`onModels(models + ModelDef(id = ""))`，按 §3 不捡）③新增 **§8.6 后面还有哪些模块**（按 package 重量了主源码与用例：`core/jobs` 523 行 **0 用例**、整个文件无 `android.` 引用 → 下一个模块；`ui/theme` / `runtime/phone` / `core/workspace` / ui 四个大文件 / `QuickJsRuntime` 各自的结论与理由） | 纯文档提交，门禁不变（**752 全绿**）。这一节的价值是**拦住下一个会话重做已经做完的 §8.4**（审计当时写晚了），以及把「提纯这条线最多再走一个 `core/jobs`」写成结论 |

| R71 | **换模块：core/jobs**（审计 §8.6 点的下一个、也是最后一个按模块提纯的大户）**第一刀**：`OutputRing` + `RingChunk`（原先嵌在 `Jobs` 里的两个私有类，零用例）→ 新文件 `OutputRing.kt`；配套两条口径也搬过去并起名字：`utf8Tail`（UTF-8 安全尾）与 **新提纯的** `settleRetainCap(total, modelCursor)`（结算保留上限 = `max(settledRetainBytes, total - modelCursor)`，原先写在 `settle` 里的一行表达式） | **752 → 768 全绿**（`OutputRingTest` 16 例：偏移是 **UTF-8 字节**不是字符数 / 空串不算一块 / 读出去的是新对象 / 通道与 gap 跟着块走 / 头部整块淘汰且**已分配的偏移不回退** / 只剩一块不再丢 / **单独一块超限只留安全尾 + gap + 偏移往后挪** / 安全尾不切断多字节字符 / cap 小于 1 按 1 算 / 从块中间读返回整块 / 读到末尾不算 lossy / **lossy 判据只在 from < earliest** / 空环 / 结算保留以模型游标没读过的那段为准 / utf8Tail 三种切法 / **切在续字节上往后跳**）；`Jobs.kt` **523 → 455 行**、新文件 109 行；`check-move` only_old=10 / only_new=16（旧侧 10 行就是两个类的头、`val chunks`/`val out`、`append`/`readFrom` 两行签名、utf8Tail 的头、以及 `settle` 里那行表达式）；**变异测试 8 处全响**（按字符数累加 / 丢到没有块 / 截尾后偏移不挪 / 读取时丢掉 from 之前的整块 / lossy 放宽到 `<=` / cap 不兜底 / 安全尾不跳续字节 / 结算保留不看游标）分别 1、4、2、1、3、1、2、1 例失败；真机装机启动无 FATAL。**诚实说明**：这一层要真的跑一条后台命令才走到（无人值守跑不了），依据是 check-move 的逐字等价 + 16 条用例 |

| R72 | core/jobs 第二刀：**栅栏与结算 detail 两处判定提纯** → 新文件 `JobRules.kt`：`visibleTo(owner, caller)`（带 owner 的任务只有同会话能看/读/杀，**unowned 对谁都开** —— 这条规则原先在 `list` 的过滤与 `expect` 的拒绝里**各写一遍**、一个肯定式一个否定式）、`mergedDetail(status, producerDetail, killReason)`（killed 的终局把 kill 原因并在生产者 detail **后面**；跑赢了 kill 的终局不带它）。`Jobs.View` 上的 `statusLine` / `observable` 本来就是纯的，这一刀只补用例 | **768 → 779 全绿**（`JobRulesTest` 11 例：unowned 谁都能看 / 同会话可见 / 别的会话不可见 / **没有 caller 时看不到有主的** / killed 两种拼接 / killed 没人杀过原样 / **非 killed 的终局不带 kill 原因** / statusLine 带与不带 detail / observable 四态（活着、终态但有输出、终态且无输出）/ 三个终态）；`Jobs.kt` 455 → **451 行**、新文件 34 行；`check-move` only_old=6 / only_new=9（旧侧 6 行 = 两处栅栏写法 + settle 里那四行 when）；**变异测试 7 处全响**（栅栏把 null caller 也放行 / 栅栏恒可见 / 拼接顺序反 / 不看终态 / 不看 kill 原因 / observable 不看输出 / statusLine 不写 detail）分别 1、2、1、1、1、1、1 例失败；真机装机启动无 FATAL |

| R73 | core/jobs 第三刀：**注册表本体的行为用例**（**没有改生产代码** —— R23 那种「先织网」的手法）。`Jobs` 是纯 Kotlin（整个文件没有一处 `android.` 引用）却一条用例都没有，而它管着后台命令的身份、状态机、输出账本与完成通知 | **779 → 797 全绿**（`JobsRegistryTest` 18 例：id 按 kind 独立计数 / kind 与 label 不能空 / **准入拒绝不消耗序号**（满了被拒 → 腾位 → 拿到的还是下一个号）/ list 只给本会话与 unowned 的 / 别的会话读·杀·删全拦（**没有会话的调用者只看得见 unowned 的**）/ 结算一次且终态后 kill 返回 false / kill 先叫停再改 stopping、killed 的 detail 并上 kill 原因 / **wait 拿到终态且这次结算算 awaited**（`hold` 那种前台形态）/ 没人等的发通知（**逐字断言那三行**）/ 模型自己 kill 不发 / unowned 不发 / read 推进游标 / **终局结果只给一次** / readAt 不动游标 / remove 只允许终态 / **owner 没了：取消 + 结算后丢记录** / cancelAll 覆盖所有 owner / wait 超时是成功的观察）；**反向验证 9 处变异全响**（放宽上限 / 先发号再判准入 / list 不筛 owner / awaited 不认持位（第 118 轮那个 bug）/ unowned 也发通知 / remove 不要求终态 / read 不推进游标 / 结果反复给 / teardown 不丢记录）；**一个发现**：`owner == null` 那条抑制**一半由编译器保证**（`Notice(owner)` 要求非空），第一版变异直接编译不过，换成 `job.owner ?: 0L` 才测到语义那一半；写用例时先被我写错两条（「没有会话的调用者谁都能看」是错的、`wait` 那条必须带 `hold`），都是用例本身的问题、被断言当场抓住 |

| R74 | core/jobs **收官 + 这条线的结论**（纯文档）：审计 §8.6 的 `core/jobs` 行改成「**0 → 45 例、R71–R73 收官**」，并补一段**收官账**（三个提交的落点、`Jobs.kt` 523 → 451 行、package 用例 0 → 45、全库 752 → 797）与**这条线的结论**：§8.1 / 8.2 / 8.3 三个模块 + §8.4（R51 就做完）+ `core/jobs` = **按模块提纯到此为止**，表里剩下的每一个都写明了原因（没有可断言的行为 / 离线测不了），要继续只能换手段（Robolectric / androidTest / 真机脚本） | 纯文档提交，门禁不变（**797 全绿**）；HANDOFF 加 R74 行并按事实刷新版本块（本地 tag 两个、远端 main 与两个 tag 的实际指向、本地领先多少提交） |

| R75 | **自查最近几次熵减**（用户点名的审查）：逐条读 R64–R74 的新增与改写，修掉**三处「为了可测而多出来的层」**和**一条只有用例才走得到的分支**：①`fenceVariables` 的入参类型收紧成 `FencePlan.On` —— 原先那个「Off 就给空表」的分支生产上永远走不到（调用点已经先判 Off 返回空数组了），现在「不挂」这条判据只剩一处；②`renderBashBody` 在生产里只有 `renderBash` 一个调用者 → **合并回 `renderBash`**（一个装配函数，参数面少一个）；③`shouldCreateSymlinkEntry` 的调用点把同一个 `relink` 传了两遍，而「安装期不探文件系统」只有注释保证 → 改成 `keepExistingEntry(relink) { exists(link) }`，探针是**惰性 lambda**、安装期一次调用都不做，且这条惰性现在有断言钉住；④`bashStatusMarker` 的参数名 `result` 与语义不符（那是正文，不是结果对象）→ 改名 `body` | **797 → 796 全绿**（删掉的那条用例断言的正是生产走不到的分支）；**反向验证 7 处变异全响**（围栏不再给 ACTIVE / 白名单用逗号连 / 安装期也去探 / 重链接期什么都不补 / 标记粘在正文后面 / 空正文不写 no output / 只有 stderr 时多一个前导换行）分别 3、1、1、2、5、2、1 例失败；三处改写都与改前逐条等价（惰性探针那条的等价式：`relink && present()` 等于「非（非 relink 或 非 present）」、类型收紧只是把调用点那次 early return 换成等价写法、合并只改了求值顺序而两者都是纯函数） |

| R76 | **深度死代码清理**（用户点名）：R63 之后又动过 13 个源文件，所以把六个现成角度重扫了一遍 —— **全是 0 候选**（未用声明 / 文件内私有 / 未用 import / 未用资源 / 版本目录条目 / C 静态符号），编译器**全部警告**只有两条 deprecated（代码里都带「有意的，别修」注释）；再加三个自建角度：**只被测试引用、生产零调用的顶层函数**全库 2 个（`toolRowSummary` 与 `whaleFrame`，KDoc 都写明是测试缝）、清单逐条核过（三个权限与 application / activity 都有真实用处）、**孤儿文件 0**（`AdshApp` 由清单引用、`SessionTitleRunner` 由 `ChatViewModel` 调用 —— 后者是我第一版扫描器正则漏了 `internal suspend fun` 的**误报**）、测试侧 62 个候选全是 JUnit 反射实例化的测试类 | **796 全绿**；**结论：没有可删的死代码**（与 R63 不同，那次删掉了三处清单声明）。这一刀的价值是**把「扫过什么、结论是什么」写进审计 §9**，并记下「扫描器正则要覆盖修饰符组合，否则会把有调用者的文件报成孤儿」这条教训 |

| R77 | **修复实测报告的第 4 / 8 / 9 点**（用户口径：确认属实再改、不加提示词）：①**第 4 点属实** —— `TermuxRuntime.spawn` 里那个「只有是目录才设 cwd」的写法，在路径不是目录时**静默不设 cwd**，子进程落在根目录上（dsh 那边由 Node 的 spawn 兜底：cwd 不存在 → ENOENT，调用直接失败）→ bash 工具解析出 workdir 后立刻校验 `isDirectory`，不是目录就回一条 `ToolResult.Error`（working directory does not exist: 路径）；②**第 8 点属实（提示词硬错误）** —— web_fetch 的 `body` 声明写「kind is html when the markup was converted to text」，而实现把**原始正文**放进 value（只有模型面正文过 HtmlToMarkdown），**dsh 的同一字段没有 description** → 改成事实陈述；③**第 9 点属实** —— `__adsh_handle` 只有 `then`，直接 `.catch()` / `.finally()` 抛 `not a function`，而 dsh 的绑定是 **async 函数（真 Promise）** → 按 Promise 语义补上这两个方法（调用仍只在 `then()` 里发生，惰性批量合并不受影响）。**同时更正报告的一处判断**：`code` / `retryable` **不是未接线的装饰** —— 宿主 `errorWire` 会带 `args` / `retryable` / `code`，web_fetch 等真的会设（传输失败 retryable=true、不支持的类型带 code），报告里那 8 类失败恰好都是默认值 | 门禁 **796 全绿**；`scripts/check-qjs-preamble.js` 新增 5 条断言（直接 `.catch` 穿透 / `.finally` 穿透且只跑一次 / 直接 `.catch` 抓到 ToolCallError / `.finally` 在拒绝时也跑且拒绝继续 / 直接 `.catch` 仍只派发一次）**ALL OK**，**反向验证**：拿掉 catch/finally → 检查退出码 1；真机装机启动无 FATAL、`phone info` 应答正常，且**三处改动的字符串都能从 APK 的 classes.dex 里 grep 到**；诚实说明：第 4 点没有离线用例（bash 工具要 ToolContext 加真机 TermuxRuntime），依据是编译与走查，需要真机重跑那四种非法 workdir 形态 |

| R78 | **run-as 深度测终端 / bash**（用户点名；方法：让临时测试把 `shellEnvironment` 的**真实输出**落盘成 39 个环境变量，再用 `sh -s` 把探针喂进设备 —— 与 App 同 UID、同 env、同 LD_PRELOAD；这一层是纯 JVM 单测碰不到的地方） | **实测矩阵（全部通过）**：①环境 39 项逐条正确，`pkg` / `git 2.55` / `python 3.14` / `dpkg（267 包）` / `apt-get -s` 都可用；②一次性命令：退出码原样（42）、stdout/stderr 分离、**stdin=/dev/null 不挂**；③`/tmp` 映射（shim）在 **bash** 里生效（写入落到 `$TMPDIR`）；④硬链接的两行 `[fs:]` 提示与副本语义（inode 不同、改动不传播）逐字符合文档；⑤脚本四种 shebang 都能跑（`/usr/bin/env bash`、`/bin/sh`、`/usr/bin/bash`（不存在 → termux-exec 改写）、绝对前缀），**npm 风格 `.bin` 符号链接 + `#!/usr/bin/env node` 也能跑**，`$ADSH_SCRATCH` 里可执行；⑥交互式（终端页那条路）：`bash -i` 跑命令与退出码、`~/.bashrc` 生效（用户自己的 `hxn` 函数可用）、**Ctrl-C 中断前台作业并回到提示符**、PS1 由环境变量生效（终端页给的 `\w \$` 就是屏幕上那个，不会被 `etc/bash.bashrc` 覆盖）；⑦`/proc/<pid>/stat` 在 App UID 下可读（`killOrder` 的数据源）；⑧后台作业与 `wait` 正常 | **结论：bash / 终端这条路没有真缺陷** —— 三条「疑似」追下去都是探针自身的假象（`/usr/bin/env` 只在**没有 termux-exec 的进程**里 ENOENT，App 的 bash 有；`script` 会覆盖 PS1（util-linux 行为）所以经它的 PTY 测不到 App 的 PS1；`/storage` 在 `runas_app` 域里 Permission denied，工作区那条路 run-as 测不到）。**唯一产出**：把真机抓到的原始字节（readline 的括号粘贴私有模式，问号开头 h 或 l 结尾那种 CSI）补成 `TerminalTextTest` 用例 —— 解析器本来就吃掉了，属**补网眼**而非修 bug；**796 → 797 全绿**；设备上我建的探针目录已清理 |

| R79 | **修用户报的 bug：DeepSeek 的会话标题没有总结** —— 根因不是「DeepSeek 不支持」，而是**标题那次小调用没有关掉思考**：`SessionTitleRunner` 的请求带着 `max_tokens = 64` 却**不发 thinking 字段**，会推理的模型（DeepSeek V4）默认先想 → 64 个 token 全被推理吃光 → `finish_reason = length`、`content` 为空 → `collectText` 只收 Delta（推理走 `ChatEvent.Reasoning`）→ 空标题 → 静默返回 null。别的模型不推理，所以看着是「只有 DeepSeek 不总结」。**真机实测（api.deepseek.com、deepseek-flash、与生产同形状的 stream + max_tokens=64 + stream_options）**：不加字段 → `finish=length`、content 0 字、reasoning **214 字**；加 `thinking: {type: disabled}` → `finish=stop`、content「DeepSeek模型会话标题未总结」、reasoning 0 字 | **修法**（不加提示词、按 dsh 口径）：①`TurnDecisions.noThinkFor(providerId, baseUrl, model)` —— 只对**认识这个字段的路由**发 `thinking = disabled`（判据复用既有的 `isDeepSeekRoute`，与 AgentLoop 同一条）；②调用点把它传给 `generateSessionTitleIfNeeded`，请求体提成纯函数 `titleRequest(model, text, thinking)` 让用例钉住那两个字段；③**空标题从静默 return null 改成抛错**（dsh 在这里就是抛 `title model produced no text`），调用点 `runCatching{}.onFailure { Log.w }` 留痕 —— 这次能查出来，正是因为原来看不到任何痕迹；**797 → 800 全绿**（+3 例），**反向验证 3 处变异全响**（不再关思考 / 关成 enabled / 请求不带 thinking）；真机装机无 FATAL。**死代码复核（用户点名，只查最近几轮，不重扫全库）**：两个扫描器 0 候选、未用 import 0；清掉三处**陈旧引用**：`RuntimeDecisionsTest` 里 R75 之后没用到的 `assertFalse` import、`BashRenderTest` 里已合并掉的 `[renderBashBody]`、`BootstrapPlanTest` 里已改名的 `[shouldCreateSymlinkEntry]` |

| R83 | **抽屉动画退回「直来直去」**（用户实测反馈：第 82 轮那套弹簧 + 手势初速度「动画末尾有抖动、很花哨，非常难受」）：归位就是**一条定长缓动**（`SLIDE_MS` 220ms + `FastOutSlowInEasing`，与覆盖页滑入滑出同一个时长与曲线）—— 定长缓动有明确终点，而弹簧是渐近逼近（尾巴一直拖）；且与手势速度无关。同时把动画末尾的**台阶**清掉：位移 / 圆角 / 投影改成跟着 `progress` 连续走（这三项都在 `graphicsLayer` 的延迟读取里，每帧改它们不触发重组），只有模糊半径仍按 1/8 台阶（它在组合期，不台阶就会每帧重建 RenderEffect） | 判定收缩成纯布尔 `drawerOpensAfterRelease(progress, velocityPxPerS)`（**速度不再进动画**，`DrawerSettle` 数据类 / 跨度参数 / `DRAWER_SETTLE_SPRING` 全删）；**顺带清掉因这次更改而死的代码**：`progress` 的 `coerceIn(0,1)`（tween 不会越界，那是弹簧过冲时代才需要的兜底）、圆角上的 `coerceIn(0,20)`、以及 4 条只对弹簧成立的用例（速度换算 / 跨度为 0 / 阻尼比 / 左甩接速度），`PANEL_SLIDE_MS` 与抽屉共用改名为 `SLIDE_MS`；**反向验证 3 处变异全响**（阈值改 `>=` / 正好一半算开 / 方向反过来）；**807 → 803 全绿**（删掉的 4 条是用不上的），三个扫描器 0 候选；release 包重打（44.6 MB，sha256 `51d801df…`）装机无 FATAL |

| R84 | **抽屉动画最终口径：不要缓动**（用户第二次否掉：「怎么和一开始的怪怪感一样了，不要有什么缓动，就是一个普普通通的动画就行，和一阶段一样简单」）：归位改成 `tween(SLIDE_MS, LinearEasing)` —— 定长、**匀速**、与手势速度无关；并把**模糊半径也改成跟着 `progress` 连续走**（它原来按 1/8 台阶，动画末尾每跳一档就是一次可见的「卡」，那正是「末尾抖动」的来源） | **熵减**：删掉 `blurStep` 的 `derivedStateOf` 与那处量化 —— 主内容层四个属性（位移 / 圆角 / 投影 / 模糊）现在**全部连续**；`DrawerSettle.kt` 的 KDoc 记下两版被否掉的方案（弹簧 + 手势初速度、`FastOutSlowInEasing` 缓动）与最终口径，避免下次再走一遍；判定纯函数与 3 条用例不变；**803 全绿**、三个扫描器 0 候选；release 包重打（44,603,154 字节，sha256 `522cb897…`）装机无 FATAL |

| R85 | **抽屉动画提速**（用户反馈「动画的速度慢了点」）：给抽屉一个**自己的时长** `DRAWER_SLIDE_MS = 170`（原先是与覆盖页共用的 220）—— 覆盖页是「点一下才出现」的整页，220ms 够看清它从哪滑进来；抽屉是**手指直接拉**的表面，手指已经推到某个位置了，松手只是补完剩下那一段，再走 220ms 就读成慢吞吞。曲线仍是 `LinearEasing`（不缓动），判定与其它属性都没动 | 常量拆开：`SLIDE_MS`（220，覆盖页）与 `DRAWER_SLIDE_MS`（170，抽屉），KDoc 写明**这是这套动画唯一该调的旋钮**（弹簧 / 缓动曲线 / 手势初速度三版都已被否，见 DrawerSettle.kt）；803 全绿、三个扫描器 0 候选；release 包重打（44,603,150 字节，sha256 `f19dc6cd…`）装机无 FATAL |

| R86 | **抽屉动画再提速：170 → 130ms**（用户实测「还是慢了」，点名 130）—— 只改 `DRAWER_SLIDE_MS` 一个数，曲线仍是 `LinearEasing`，其余一字未动。KDoc 里记下收敛过程：220（与覆盖页共用）→「慢了点」→ 170 →「还是慢了」→ **130** | 803 全绿；三个扫描器 0 候选；release 包重打（44,603,158 字节，sha256 `dc9d5f7c…`）装机无 FATAL；`dist/` 两个包都同步到这一版 |

| R87 | **用户点名的三件事**：①抽屉动画时长 **130 → 112ms**（只改 `DRAWER_SLIDE_MS`，曲线仍是 `LinearEasing` 不缓动）；②**模型重连向 dsh 靠齐**：UI 按 dsh 的 `ConnectionIndicator.module.css` 逐条重画（28dp 高 / 8dp 圆角 / 左右 8dp / 图标与文案 4dp / 12px·500 字 / warning 与 success 两套底色 + 同色 20% 描边 / 淡入淡出 150ms / 连接中用 dsh 的 `StateDot`、断开用刷新、已恢复用对勾 / **文案自己写着动作**、点接在文案后且宽度固定 1em），机制上补上 dsh 有而 ADSH 没有的那条 —— **`setNetworkAvailable`：断网挂起自动重试（不再盲目退避）、网络回来立刻重来且退避归零**，界面从此「断开」与「连接中」是两个状态；③**附件导入 UI**：图片只显示缩略图（去掉文件名）、叉压在图的右上角；文件卡片＝类型图标（复用文件浏览的 `FileTypeIcon`）+ 文件名 + 大小 + 右上角深色圆形叉徽标（照 DeepSeek app 的卡片）；④版本 **0.2.0 → 0.2.1（code 12 → 13）** | **熵减**：`StateDot` 从 `TerminalBlock` 提出来共用（新 `ui/DshStateDot.kt`）；**三份大小文案合成一份** `ui/FileSizeText.kt`（dsh 的规则：<10 一位小数、≥10 取整、数字与单位之间无空格）—— `DocumentPreview` 的私有 `formatSize`、`UserMessages` 的同名私有函数、附件卡片三处都改用它（后者还撞了签名）；删掉 14 行因搬家失效的 import；**反向验证 3 处变异全响**（断网挂起条件反转 → `awaitNetworkHoldsUntilTheNetworkComesBack` 失败；大小文案退回带空格 → `FileSizeTextTest` 失败；临时态漏掉断开 → `ConnectionStateTest` 失败）；**803 → 811 全绿**（+8 例）；真机：**设备这会儿没连上，`dist/ADSH-0.2.1-debug.apk` 还没装**（装上要验的是：断网时连接条显示「连接已断开，点此重试」而不是转圈，网络回来立刻恢复） |

| R88 | **用户点名的四件事（第 179 轮）**：①抽屉动画 **112 → 120ms**（只改 DRAWER_SLIDE_MS 一个常量，曲线仍是 LinearEasing 不缓动）；②**灵动岛（新功能，用户口径「后台保活机制」）**：五格状态（思考 / #代码 / ask_user / 输出中 / 已结束）由纯函数 islandWorkOf 从 ChatUiState 推出来（优先级：等用户 > 工具在跑 > 工具参数在流 > 思考 > 输出 > 刚起；展开态取最后三行、只看尾巴 2000 字符），宿主是新前台服务 IslandService（通知渠道 adsh-island、常驻低优先级通知、只在 agent 干活期间跑；跑完显示「已结束」1.2s → 演退场 320ms → stopSelf），窗口是 TYPE_APPLICATION_OVERLAY 悬浮窗（要 SYSTEM_ALERT_WINDOW），只在 App 退到后台时挂（AppVisibility 用 ActivityLifecycleCallbacks 计数前后台）；③**设置-功能页的权限卡**：四项（所有文件访问 / 电池优化 / 悬浮窗 / 通知）状态读系统真值、点一下跳对应系统页面（每档都有回落：单应用页 → 总列表页 → 应用信息页），从系统页回来 ON_RESUME 重算；按用户口径**不带小字介绍**（CardFrame 的 description 改成可空） | **形态与动效按用户第二轮口径重做**（第一版被否：「展开不是这样的、位置与大小也不对、动效一抽一抽、水平长度不该跟着文案变、结束后收起不丝滑」）：收起固定 **150×37dp** 胶囊（宽度不跟文案变）→ 点一下**同一块面**长成 300dp 卡片（宽 / 高 / 圆角一起走临界弹簧），展开块**固定三行槽位**（行数怎么变、块高都不变），思考与 #代码 两格带流光（与对话里扫光同周期 3.0s、同 ease-out，扫的是字面本身而不是盖一层底色），出现 / 退场是缩放 + 淡入淡出、窗口等动画演完才摘；**三条真机结论写进 KDoc**：(a) TYPE_APPLICATION_OVERLAY 那条线在状态栏之下 —— **触摸归 SystemUI**（点上去毫无反应）、**绘制也在 SystemUI 之下**：用户手机开着热点时，MIUI 自己的灵动岛正压在这个位置，我们的胶囊被整个盖住、点一下展开的是 MIUI 的热点卡片（真机截图确认）。于是位置的最终口径是**状态栏正下方 2dp**（整枚胶囊都在自己的窗口里、点得到，也不跟系统自己的岛打架），想更贴挖孔就只改 PILL_TOP_BIAS_DP 一个数（负值往上，代价是露出多少就只能点多少）；(b) 无 Activity 的浮窗里 **Compose 的 clickable 不触发**（事件确实进了本进程 ViewRootImpl），改成根 View（IslandRootView）的 dispatchTouchEvent 直接吃掉；(c) 通知只在文案变化时 notify —— 每帧一次 IPC 是「一抽一抽」的来源之一。**811 → 824 全绿**（+13 例 island/IslandStatusTest：五格优先级、工具跑完不再算工具、参数在流算工具、等待压过一切、只留最后三行非空、长行裁剪、只看尾巴、提问通道优先、批准显示等批准的工具名）；权限卡四项状态用 adb 逐条对过系统真值（cmd appops SYSTEM_ALERT_WINDOW / MANAGE_EXTERNAL_STORAGE、dumpsys deviceidle whitelist、通知开关）；岛的真机实测过：真发一轮 → 退到后台出现、回前台隐藏、dumpsys 里 isForeground=true / foregroundId=1717、通知渠道 adsh-island、收起态与「已结束」截图核对过；**这一版（固定宽 + 一块面变形 + 压状态栏中间 + 丝滑进出）只编译 + 装机，观感由用户自测**；本轮按用户要求**不跑死代码扫描、不做熵减** |

| R96 | **用户口径「你别自己瞎改啊，参照 dsh 做直接找根因解决。改好后熵减」→ 按 dsh 的进程隔离模型重做 PTC 运行时（R95 那版看门狗撤回）**。先把 dsh 源码里的事实读出来（`app.asar` 里抽出 `@deepseek-ai/dsh-ptc-runtime-node` 逐行看）：① `isolation = "process"`，**每次调用 spawn 一个新 Node 进程**（executionInstructions 原文：Each call runs in a fresh Node process）；② 宿主 `setTimeout(spec.timeoutMs)`（默认 12e4、`maxTimeoutMs` 6e5）到点 `controller.abort("execution deadline reached")`；③ 收尾 `handle.terminate()` **杀掉子进程**（`graceMs` 3s），失败信封 `{kind:"timeout", message:"execution deadline reached (Nms)"}`；④ run_code 的 `timeoutMs` 参数说明原文是 **including nested tool and approval waits** —— 第 91 轮那条「等工具不计入预算」的本地口径与 dsh 相反，且参数表里本来就有 `timeoutMs` 而我们漏了。**改造**：`core/ptc/` 拆成六件各管一件事 —— `QuickJsRuntime`（引擎，只跑程序：工具改成 `invoke` / `invokeAll` 两个同步回调）、`PtcWorkerService`（`android:process=":ptc"` 的宿主进程，每次调用一个新进程）、`PtcProcess`（主进程客户端：绑定 → 发程序 → 在主进程跑工具 → **到点 `Process.killProcess(workerPid)`**，即 dsh 的 terminate）、`PtcProtocol`（Messenger 协议 + 纯函数 `awaitPtcWorker` 判据）、`PtcToolRunner`（工具侧：ToolConcurrency 闸门 / 中断探针 / 子调用轨迹，原样搬过去）、`SubCall` 与 `CodeRunResult` 各自独立成文件；`run_code` 补上 dsh 的 `timeoutMs` 参数（clamp 到 (0, 600000]）；系统提示词那条预算说明改成 dsh 口径（含 `PromptAssemblerTest` 的旧断言，改成钉「预算包含等工具与等审批」）。**真机实测（本轮，都是我驱动的）**：`return 2 + 3` → **0.63 秒返回 5**（跨进程 RPC 通）；让模型自己给 `timeoutMs: 10000` 跑 `for (let i = 0; i < 1e11; i++)` → **整整 10 秒**返回 `code run failed (timeout): execution deadline reached (10000ms)`，`:ptc` 进程被杀、**那一轮继续**（模型随后照实复述结果）—— 正是第 183 轮把整轮挂了 13 分钟的那个程序。踩到并写进 KDoc 的两个坑：QuickJS 包装库要求**调用线程有 Looper**（没有就抛 `MessageQueue Looper.mQueue on a null object reference`）；`Handler(thread.looper)` **必须在 `HandlerThread.start()` 之后构造**（同样的 NPE，表现为每次 run_code 都是 213 字符的 worker-exit）。**熵减（用户同轮点名）**：968 行的 `QuickJsRuntime.kt` 缩到 420 行（工具 / 轨迹 / 看门狗全搬走，引擎只剩「喂程序 + 泵 microtask」）、`AdshApp` 的进程级初始化收口到主进程（`:ptc` 不再多起一份 phone 轮询线程与网络订阅）、删掉 R95 那套 `PtcWatchdog*`（连同它 9 例单测，由 `PtcDeadlineTest` 5 例替代）、扫描器报出来的 4 条新死代码（2 个 import + 2 个常量）当场删掉。 | **835 全绿**、`assembleDebug` 过、装机 Success、`dist/ADSH-0.2.1-debug.apk` 同步；`python scripts/deadcode.py` 与资源扫描器 **0 候选**；crash buffer 空。**R95 那版（宿主看门狗 + 放弃那条线程）已由本轮取代**：它的「放弃」只是不再等，被放弃的线程会一直转着烧一个核；dsh 的做法是**杀掉进程**，现在照做。 |
| R95 | **用户报「agent 挂了个后台任务，我切到后台，岛出现后任务完成，它却像没收到结果、一直等（近十分钟）」——真因不是任务通知，是 `run_code` 里的同步死循环**。证据链：会话 111 的最后一条落库消息是 16:43:21 的 `run_code`，它的程序里有 harness 自己写的 CPU 预算探针（`for (let i = 0; i < 1e11; i++) { x += (i % 7) }`，注释就写着「loop until the run is cut off」）；那之后 6 分钟里只有 UI 行事件（ADSH_TRACE row±），**没有任何 Step 落库** —— 说明那一轮卡在那次工具调用里；`dumpsys activity exit-info` 显示 16:57:00 pid=12895 被 **SwipeUpClean** 杀掉（用户上滑清理）。所以：岛一直亮着是**对的**（确实有轮在跑），只是它卡在那个程序里；后台任务（bash-49）的完成通知要等**下一个步边界**才可能被认领，而那个边界永远不会来 —— 用户看到的「没收到结果、一直在等」就是这个。**为什么卡**：QuickJS 一旦进同步死循环就再也不把执行权还给宿主，泵循环里那条超时判据（`elapsed - toolWait < budget`）与 cancel 探针**都没有机会执行**；包装库也没有中断接口（`javap -p` 确认：只有 createRuntime/createContext/evaluate/setMemoryLimit/setMaxStackSize/runGC…，没有 `JS_SetInterruptHandler`）。**修复**：`QuickJsRuntime.run` 把程序挪到**自己的线程**上（显式 2MB 栈 —— QuickJS 自记账的 256KB 必须小于真实栈，否则递归会吃穿栈变 SIGSEGV），宿主每 200ms 用**同一条公式**算「程序自己跑了多久」：超预算 +5s 就**放弃这次调用**并把「程序在 120000ms 内未结束：同步死循环…已放弃这次调用 —— 长循环请拆小，或改成后台任务」还给模型，那一轮继续往下走；用户按了停止再 +2s 同样放弃（按「已停止」收尾，界面画成 stopped）。放弃时 `future.cancel(true) + shutdown`（native 死循环收不到中断，那条线程会继续转 —— 已知代价，KDoc 里写明）。判据与调度抽成 `ptcWatchdogVerdict`（纯函数）+ `awaitPtcProgram`（可注入 body 的调度循环），**+9 例桌面单测**（4 例边界：正常跑不打断 / 预算+宽限才放弃 / 停止压过超时 / 等工具的时间不算预算；5 例调度：永不结束的 body 会被放弃且线程池收掉、等工具时间不误判、取消报 Cancelled、正常完成原样返回、线程池用完即 shutdown）。 | **839 全绿**（830 → 839）、`assembleDebug` 过、装机 Success、`dist/ADSH-0.2.1-debug.apk` 同步。**真机验证有一个缺口（如实记）**：我照原样复现（新会话里让模型跑同一个 for 循环），程序 17:03:06 起跑，**17:04:45 进程被 SwipeUpClean 杀掉**（exit-info #1，差一点没到 125s 的看门狗死线）—— 所以「看门狗真的会放弃」这条**没有在真机上看到**，判据落在单测里；复现配方：让它跑 `for (let i=0;i<1e11;i++){}`，约 2 分钟后应收到「同步死循环…已放弃这次调用」并且那一轮继续。另外顺带确认：这一轮的过程里没有任何崩溃（crash buffer 里还是 14:48 那条已修的旧栈）。**（同轮更正：这一版「宿主看门狗 + 放弃线程」已被用户否掉 ——「你别自己瞎改啊，参照 dsh 做直接找根因解决」；dsh 的做法是进程隔离 + 到点杀进程，见 R96，那套 `PtcWatchdog*` 已删。）** |
| R94 | **用户第 183 轮点名的两件事：①把 `DshSpacing.Hairline` 那 40 余处 `0.5.dp` 字面量换掉 ②用 run-as 深度测 bash（Termux）**。①**42 处**（19 个文件：border / HorizontalDivider / 描边宽度）一次性换成 `DshSpacing.Hairline`，值完全相同、纯机械替换；全库现在只剩 `DshSpacing.kt` 的定义那一处（KDoc 同步改成「这里是全库唯一允许出现 0.5dp 字面量的地方」）。**做法上踩了一脚**：第一版用 `kt_source.strip_comments_and_strings` 定位出现处 —— 它抹字符串**会改变长度**，于是偏移错位把 19 个文件切坏（出现 `DshSpacing.Hairlineer(0.5.dp, …)` 这种东西），当场 `git checkout -- app/src/main/java/com/adsh/app/ui/` 复原，改用**保长度**的 `strip_comments` 重做（注释抹成等长空格、字符串不动），替换后逐条 grep 复核（44 → 2 处，都是定义与讲它的 KDoc）。②**run-as 深度测**：探针脚本经 run-as 的 stdin 落进 App 私有目录（`scripts/runas-termux-probe.sh` + `runas-termux-runner.sh`，已进仓库可复用），runner 把环境**逐字照抄** `TermuxEnv.shellEnvironment` + `fenceEnv`（LD_PRELOAD 必须在 bash 启动前就在环境里，否则 termux-exec 不生效）。两遍结果：**danger-full-access ok=19 fail=0 skip=1**（bash 5.3.15；`/proc/self/maps` 里 libadshfence 与 libtermux-exec 都在；**三种 shebang 脚本直接执行全过** —— 这是 termux-exec 绕 W^X 的关键路径；管道/重定向/glob/子 shell 退出码/if-else/符号链接/**硬链接**/chmod/touch 全过；`dpkg -l` 272 行、`apt-get -s install --reinstall bash` 能出模拟结果；git 2.55.0 / python 3.14.6 / node / npm / curl / wget / jq / clang / make / ssh / login 全部在位（`rg` 不在 $PREFIX/bin —— 随包那份是 nativeLibraryDir 里的 librg.so，走工具层不用 PATH，符合设计）；scratch（f2fs）可写；`adsh-env-check` 跑通）。**workspace-write ok=23 fail=0 skip=2**：**写围栏真的在判决** —— 白名单外（$HOME）写入被拒，stderr 逐字是 dsh 的两行标记（`[sandbox: file access denied under workspace-write mode]` + 提权提示）；/tmp 映射到 $TMPDIR 生效；读 /system/etc/hosts、读前缀、列 $HOME 都不受影响。**两条 skip 都是 run-as 自身的域限制**：`runas_app` 域没有 /storage 访问（`ls` 工作区都是 Permission denied），而 **App 自身不受限** —— 同一分钟 logcat 里就有它往 `/storage/emulated/0/1/App/.adsh/attachments/110/` 导入图片与 PDF 的记录（MediaProvider 也能查到该目录）。顺带修掉探针自己的两条错判据（拿 0600 root 的 `/system/build.prop` 当「读不受影响」的反例；把域限制记成 FAIL）。③另外从 App 自己进程的启动自检（ADSH_ENVCHECK）确认：直接执行模式、shim 与 termux-exec 都加载、shim-diag 94 行、`enabled=0 roots=0`（完全权限不判决）、**crash buffer 没有新崩溃**（唯一一条是 14:48 那个已修的旧栈）。 | **830 全绿**（Hairline 替换后重跑）、`assembleDebug` 过、**装机 Success**（16:36 装的就是含替换的那一版）、`dist/ADSH-0.2.1-debug.apk` 同步；四个扫描器仍 **0 候选**；设备上探针的两个副本跑完即删（App 私有目录不留垃圾）。版本仍是 0.2.1（code 13）。 |
| R93 | **用户第 182 轮点名的两件事：①围绕四个点做熵减 ②深度优化死代码扫描器并深度清理**。①**文件 / 图片导入**：新增 `ui/ImageDecode.kt`（一份下采样解码 + 一份 32MB 的位图缓存）—— 解码原来在四个文件里各写了一遍（Composer 的附件缩略图 / UserMessages 的消息图 / DocumentPreview 的原图预览 / TurnRail·Markdown 隔着文件用 UserMessages 里的私有缓存），现在只有一处；顺带把缩略图那两处的判据从「宽高**都** ≥ 目标」改成「**最长边** ≥ 目标」—— 4000×200 的长截图以前会整张解码（3.2MB 位图）只为一个 72dp 方格；`WorkspaceActions.kt`（233 行、6 个互不相干的函数）按**关注点**拆成三份：`AttachmentImport.kt`（导入链：文件名净化 / 同名去重 / provider 授权 / 逐条失败反馈）、`SessionExport.kt`（Markdown 导出 + ZIP 打包 + toast）、`WorkspaceActions.kt` 只剩工作区那条链（目录 URI ↔ 绝对路径、目录授权长期保留、交付物路径、所有文件访问的读与跳）。**②会话切换**：`openConversation` 与「命中正文缓存」那条快路径各写一遍的「换正文 + 复位轮内字段 + 盖回在跑的那一轮」，合并成 `showConversationFrame(id, messages, meta)` 一处（meta = 工作区/统计/计划模式；null = 只换正文，统计由随后的 openConversation 补齐）—— 切会话的两条路现在**只有一处**写状态。**③权限卡**：三态文案 `permissionStatusLabel`（已授权/未授权/去设置）搬进 `PermissionActions.kt`，与读系统真值的 `permissionStatus` 同处 —— 「界面不许撒谎」这条规矩的两半不再拆在两地。**④灵动岛**：一帧的全部字符串（五格文案 / 最新一行 / 是否在干活）原来在服务里算了三遍（apply / updateSession / notification，active 还写成 `phase != DONE`），收进 `islandFrameOf`（IslandStatus.kt 的纯函数），服务只负责把一帧画出去。②**死代码扫描器重写**（判据从「名字在全语料出现几次」换成**词法级引用 + 作用域**）：新增 `scripts/kt_source.py`（注释剥离 + 字符串抹正文但**保留模板引用** + 词法级标识符流，区分裸名/成员访问/命名实参/import），`scripts/deadcode.py` 重新实现——**声明点表**（任何声明处不算引用：旧版最大漏报源）、**同名遮蔽**（函数体里的同名局部/参数把该名字的引用吃掉）、private 只数本文件 / internal·public 数 main 且 test 单算一栏、**置信度**（unused-import 100 / private-unused 100 / internal-unused 70 / internal-test-only 65 / public-unused 60 / public-test-only 55，来自 vulture）、**豁免表**（@Test/@SerialName/@Entity/@Keep/@Provides/@Preview…、override/actual/expect、operator（按符号调用的 + / get）、@Suppress("unused")、allowedNames（_ / ignored / expected / serialVersionUID，来自 detekt））、**清单里点到的类算被引用**（Android Lint MissingClass 的反方向）、**object 成员按顶层报**（单例不能被继承，只能按名字访问）；`--min-confidence` / `--json` / `--baseline`（每条例外必须写 reason）/ `--fail-on-new`；`scripts/deadcode-selftest.py` 用合成小仓库钉住 11 条判据（该报的必须报、不该报的必须不报）。**写这个自测时抓出扫描器自身 5 个 bug**：另一个同名声明被当成引用、局部遮蔽没进作用域（`not match` 把局部的 val 挡掉了）、@Suppress 的参数被字符串抹掉后判不出「抑制的是 unused」、全限定接收者把 `androidx.compose.foundation.layout.RowScope.ThemeCubeView` 拆成 `compose`、构造参数里声明的属性被当成顶层声明。`scripts/find-unused-resources.py` 同步重写（values 全类型 + mipmap/xml/layout/raw/font + 语料不含 .md 与 scripts/ + tools:keep 豁免 + 注释不算引用），并用**注入夹具**验证：临时加一个 string / color / drawable → 三个都报出来，复原后归零。被取代的三个脚本（find-unused-declarations / find-file-local-dead-code / remove-unused-imports）**删掉**（一个判据只留一处实现）。 | **①扫描器战果**：5 个死 import（`alpha`×4、`items`）+ 我自己这一轮重构又制造出来的 4 个（`asImageBitmap`×2、`imageMediaTypeOf`、`BitmapFactory`）全部删掉；4 条测试口（AgentLoop.sendWithTools / ToolConcurrency.holdingNow / WhaleTail.whaleFrame + WHALE_FRAME_COUNT / ToolConcurrency.resetForTest）进 `deadcode-baseline.json`，每条都写清为什么不能删（都是**测试口**：生产没有调用方是有意的）。**②Android Lint 当语义级那一半**（`./gradlew --offline :app:lintDebug`，第 182 轮第一次真跑）：它报的 4 个 error **全部修掉** —— (a) debug 清单点了不存在的 `com.adsh.app.dev.DevCheckActivity`（MissingClass，那个文件只有这一条 → 整个删掉）；(b) `EnvSelfCheck` 里 api 30 的 `Environment.isExternalStorageManager()` 没有版本闸（NewApi，api 26~29 的机器走到就是 NoSuchMethodError）；(c) `ChatBars` 的顶栏 `Row(` 整块多缩进 4 格（SuspiciousIndentation，55 行去缩进，行为不变）；(d) `local.properties` 的盘符转义（**本地文件、已 gitignore**，把 `sdk.dir` 写成 `D\:/WSN2005/Android1` 即可 —— 不进提交）。**修完 lint：4 errors → 0**。**③判定**：830 全绿（+2 例：`islandFrameOf` 一帧三件事、权限三态文案）、`assembleDebug` 过、装机 Success、`dist/ADSH-0.2.1-debug.apk` 同步、`python scripts/deadcode.py` **0 候选**、资源扫描 **0 候选**、扫描器自测**通过**。**没做的**：`DshSpacing.Hairline` 那 40 余处 `0.5.dp` 字面量（纯机械替换，留给专门一轮；KDoc 里把「共 5 处」这句过时的话改成实测数字），本次只让 `DshHairline()` 用上这个刻度。 |
| R92 | **用户第 181 轮的三条**：①**切会话仍然会「停」（第三次报）—— 这次找到了真根因**：`stashLiveRun()` 的判据是**屏幕上的**会话 id，而**命中正文缓存**的快路径会先把 `conversationId` 同步改成目标会话、紧接着才起协程跑 `openConversation` —— 于是「切走时抓帧」那次看到的已经是目标会话，它把**刚读出来的库快照**（sending=false、streaming=""）写进了正在跑那条会话的 `liveRuns` 格子：切回来 `restoreLiveRun` 接上的正是这份垃圾（流式尾巴 / 工具行 / 岛一起没了，而真实那一轮其实一秒没停）。按用户口径**改成 dsh 的登记表模型**（实时状态跟着会话走、不跟着「现在看哪一条」走）：一轮在跑期间它的状态一直住在 `liveRuns` 里，`updateTurn` 看它时同一份同时上屏、没看时只落登记表；「看哪一条」只是一层投影，切过去时 `resetTurnFields()` 复位轮内字段、`withLiveRun()` 把登记表那一份**盖**到刚从库里读出来的状态上（两个纯函数搬进新文件 `ui/LiveRunState.kt`，桌面单测直接打）；同一族的四个串会话一起修掉：`turnEvents` 原先不跟着复位（上一条会话的工具行画进新会话）、重连条直接写 `_state`（A 掉线画到 B 上）、`flushQueue()` 读「当前会话」（A 收尾时把 A 队列里那条发进 B）、`openTurnInputs` 的身份写入与 startTurn 的 error 也改成按会话路由；队列语义定为「某条会话的排队消息等它回到前台、且没有轮在跑时发出」。②**岛的 UI 优化**（媒体路线；真机 dumpsys 确认 MIUI 画的是**媒体模板**：胶囊=封面+波形，展开=媒体卡）：封面改成 256px 深底圆角方 + **按格着色的环** + 鲸（等模型 / 在跑 = 暗底淡环；等你回答 / 已结束 = 同色系暗底 + 亮环 —— 状态栏里一眼分得出「它在干活」和「它在等我」）；媒体元数据补 `DISPLAY_TITLE` / `DISPLAY_SUBTITLE`（不然有的 ROM 展开卡会去翻 ALBUM，副标题显示成「ADSH」）；补 `MediaSession.setSessionActivity`（点岛上那一格回 App 接着看）；通知从「只按格子去重」改成「**格子 + 最新一行**」去重 + `NOTIFY_MIN_INTERVAL_MS = 200` 最小间隔（展开大卡与通知栏里的正文跟着流式走，不再停在标题变化那一刻，也不至于每帧一次 IPC）。③**权限卡的行改成「卡体内的一行」**（用户口径「方框不要有气泡，风格和其他卡片一致」）：去掉 `bgModulePlatform` + 圆角 12 的方框，改成 `.5px` 分线分隔、13/20 Medium 标签 + 12/18 状态 + 倒角 —— 与终端 / 智能体循环那些卡里的 `ValueField` 同一套。 | **828 全绿**（+4 例 `ui/LiveRunStateTest`：切到别的会话轮内痕迹一件都不许跟过去 / 切回正在跑的会话尾巴·工具行·连接·那一轮的身份全接上（正文以库为准）/ 没有在跑的一轮原样返回 / 复位只动轮内字段）；assembleDebug 过、装机 Success、`dist/ADSH-0.2.1-debug.apk` 同步；**真机被动观察**（用户当时正在同一台机器上自测，我停掉了自动点击）：跑一轮时 `dumpsys activity services` 里 `isForeground=true foregroundId=1717`、`dumpsys notification` 里 id=1717 的记录在（flags 含 FOREGROUND_SERVICE、category=transport），MIUI 的 `DynamicIslandEventCoordinator` 把 `0\|com.termux\|1717\|null\|10394` 当岛更新（`islandProperty:2 / islandPriority:2`，布局是 album icon + musicWave），`MiuiIslandMediaViewBinder` 读到我们那一帧的文案与 `MediaAction: contentDesc=停止`；**切会话这次没有脚本化验证**（不再跟用户的手动测试抢设备），代码级判据在单测里；**实况窗 API 核对完成**：`sdk.dir=D:/WSN2005/Android1` 下是 `platforms/android-37.0`（上一轮写成 android-37 才没探到），javap 确认 `Notification$ProgressStyle`（setProgress / setProgressPoints / setProgressSegments / setProgressIndeterminate / setStyledByProgress）、`Notification$Builder.setRequestPromotedOngoing` / `setShortCriticalText`、`NotificationManager.canPostPromotedNotifications` 全在 —— 下一步（要设备实验，可能多出一条通知）在 API 36+ 且 `canPostPromotedNotifications()` 为真时改走实况窗，否则留媒体路线兜底；targetSdk 28 够不够资格被 promote 未证实。本轮按用户口径**不跑死代码扫描、不做熵减** |
| R91 | **用户复看第 180 轮的三条**：①焦点通知真机上 MIUI **不画**（多半要业务白名单）→ 撤回**已验过能显示**的媒体路线（MediaStyle + MediaSession，R89 截图确认 MIUI 会画），焦点那套 extras 与 org.json 依赖一起删掉；**实况窗（Android 16 Live Updates：ProgressStyle + setRequestPromotedOngoing）**留到下一步 —— 这一轮的 javap 探针把 SDK platforms 路径写错了（`sdk.dir=D:/WSN2005/Android1` 下没有 android-37），没探到 API 名字，核对完再上；②**切会话仍然会「停」**：真正的漏点是 `openConversation` **没有复位轮内字段** —— 新会话继承了 `sending` / `liveTurn`，于是「吃白饭中」那条工作提示画在切过去的新会话里（用户原话）；现在 openConversation 开头先 `stashLiveRun()`、copy 里把 `sending=false` / `liveTurnId=null` / `liveTurn=LiveTurn()` / `toolArgsFlowing=false` / `connection=Idle` / `runStartedAt=0` / `queuedCount=queueOf(id).size` 全部复位，正在跑那一轮的状态只住在 liveRuns，切回去由 restoreLiveRun 接上；③权限卡**撤掉「自启动」**（用户口径：不需要），清单回到三项：通知 / 电池优化 / 所有文件访问；④首次绑定工作区的提示词改成「要读到手机里的文件，请打开「所有文件访问」权限（设置 → 功能 → 权限 里可以一键跳转）」 | **824 全绿**、assembleDebug 过、装机 Success、dist 的 debug 包同步；待用户复测：岛的显示（媒体路线应恢复）、切会话不再「停」、权限卡三项、提示词 |
| R90 | **用户复看第 179 轮的四条**：①**岛的样式**（「怎么是波点音乐样式的，太怪了」）→ 按用户选择改走 **MIUI 焦点通知（超级岛）**：协议是从**这台机器上系统自己的热点通知**里 dump 出来的（extras: miui.focus.param 的 param_v2 JSON + miui.focus.pics/actions + miui.appIcon；通知 category=status、importance 3、ONGOING），我们照写：胶囊/大卡的文字 = 五格文案 + 最新一行，图 = 按格着色的鲸，大卡右上那颗「停止」= IslandBus.requestStop；媒体路线（MediaStyle + MediaSession，R89 真机已验过 MIUI 会画）整条换掉；②**权限卡清单**改成用户选定的四项：通知 / 电池优化（无限制）/ **自启动（MIUI 安全中心那个页面）** / 所有文件访问，撤掉已经用不到的悬浮窗；自启动系统没有可读开关，卡片写「去设置」而不是编一个「已授权」（界面不许撒谎）；③**切会话不再打断正在跑的那一轮**（用户口径：参照 dsh）—— 原来 switchConversation 里那句 `if (... \|\| sending) return` 直接拒绝切换，而抽屉的「新会话」没有这个判断（点它会把实时内容清掉、看着就是「被终止」）：现在切走时把那一轮的样子存进 liveRuns、切回来 restoreLiveRun 把还在流的内容接上；跟某一轮有关的状态写入全部走 updateTurn（看它就直接上屏，没看就留在自己格子里），队列也按会话各排各的，stop 与岛都按「正在跑的那条会话」路由；④**「新会话」永远排在工作区标题下面第一行**（用户口径，与 dsh 一致）：两处会话列表都按「当前会话是空白会话」排最前 | **824 全绿**、assembleDebug 过、装机 Success；**本轮真机验证有缺口**：焦点通知能不能被 MIUI 画出来还没确证（我的脚本这几次没能把消息发出去：服务没起来、岛自然不在；上一版媒体路线是截图确认过 MIUI 会画的）—— 若焦点通知不生效就回媒体路线；切会话 / 新会话置顶 / 权限卡三项也由用户复测 |
| R89 | **用户复看第 179 轮的两条反馈 + 一个致命 bug**：①**保活根本没生效**（「一切后台连通知都没了」）—— 根因是**崩溃**：自绘悬浮窗外层那只 IslandRootView 上没有 ViewTree 的 owner（setViewTreeLifecycleOwner 挂在了子 ComposeView 上），而 Compose 找 Recomposer 是**从加到 WindowManager 的那个根 View 往上找**，于是组合期抛 IllegalStateException: ViewTreeLifecycleOwner not found from IslandRootView —— 主线程异常 = 整个进程崩、FGS 与通知一起没（crash buffer 里 PID 32643 的栈就是它）。owner 改挂根 View，并且**自绘悬浮窗整条路按用户口径删掉**（IslandWindow.kt 删除，连带只服务它的 AppVisibility）；②**灵动岛改走 MIUI 的超级岛**（用户点名：波点音乐的灵动岛也能让别的状态信息腾位置、点击也有反应）—— 真机从波点音乐的通知里读出协议：android.template=android.app.Notification$MediaStyle + category=transport，于是前台通知改成**媒体通知**（平台 MediaSession + Notification.MediaStyle + 会话 token；标题=五格文案、副标题=最新一行、**封面=按格着色的鲸**：白=等模型 / 琥珀=等你回答 / 绿=已结束、播放态=STATE_PLAYING），MIUI 自己把胶囊画在状态栏那一条里（图标腾位置、点击展开、动效全归系统）；卡片/通知上的**停止** = IslandBus.requestStop → ChatViewModel.cancel()（与输入框右下角那颗停止键同一件事）；③**权限卡 UI**：加一行小字「快捷授予权限。」，四项各套一个**同款圆角长方框**（bgModulePlatform + 圆角 12，与字号步进器 / 主题立方同一套），卡片宽度与功能里别的卡一致 | **真机判据**：退到后台之后 dumpsys activity services 仍是 isForeground=true / foregroundId=1717（保活真的在）；我们的通知在 dumpsys notification 里是 category=transport + android.template=android.app.Notification$MediaStyle；截图确认 **MIUI 把我们的鲸 + 播放波形画在状态栏中间那颗胶囊里**；crash buffer 无新崩溃（只剩 14:48 那条旧栈）；**824 全绿**；点击展开与「停止」按钮留给用户复测（我用 adb shell cmd media_session dispatch pause 没能在真机上判定 —— 跑那条命令时那一轮的工具调用不在跑） |
**第二阶段踩到的八条方法论**（下一步会话直接用，别再交一遍学费）：

1. **审计报告的关键字计数不可直接当待办**。那一轮外部审计给出的五条里，三条经核实不成立：
   「39 处绕开 `Args` 的裸 `jsonPrimitive`」实际只有 2 处是工具入参（其余是供应商返回的 JSON 与
   ripgrep 输出）；「5 套截断实现」是五个不同的东西且都进模型可见面；「28 个常量散落」是就近定义。
   最危险的一条是「`ChatEvent.Usage`/`Finished` 都是死分支」—— **`Usage` 不是死的**，
   `AgentLoop.kt` 在读它喂 token 账本，删了会静默丢统计且没有任何编译或测试会拦。
   **凡"死代码/重复"结论，动手前先自己看代码。**
2. **改完必须自己验证，而不是相信替换脚本**。R1 的自动替换试了三版都会把声明插进 Modifier 链
   或 KDoc 注释（编译器一次抓出 332 个错）；R2 的图标迁移脚本出过 4 次自己的 bug（定义段删不净、
   表达式体函数体误判）。**每步都编译 + 逐文件看现场**才拦住了它们。
3. **编译通过 ≠ 正确**（R7 的教训，最贵的一次）。R7 用脚本重拼图标块，两次把实参拼错位
   （`chr(10).join` 漏逗号、多行 path 只取首行），**编译器两次都没报**——因为 `dshPart` 的参数
   全有默认值，错位后类型照样通过。最后是真机首帧 `IllegalArgumentException: Unknown command for: D`
   才暴露，而那条崩溃日志我第一遍只 `tail -5` 扫到过、当成了旧日志。**靠默认参数兜底的 API，
   编译通过证明不了任何事**；这类改写要额外写结构校验（`check-icon-structure.py` / `verify-padding.py`）。
4. **大段搬运用「按行区间切片 + 锚点断言」，别手抄**（R10）。脚本读原文、按行号切片，每段先断言首行
   内容（错一行立刻失败），块内只在 4 处做显式改写（每处都断言只匹配一次）—— 这把第 3 条那种
   「重拼把实参拼错位」的风险真正压下来了。但**函数收尾括号这类边界仍要单独核**：R10 的脚本漏了
   两个 `}`，编译器一秒就报出来（Kotlin 语法错误 + 一串下游 unresolved）。
   等价性用一条命令重放：把 R10 之前的 816 行与改后的四个文件做**代码行 / 注释行多重集比对**
   （报出「少掉的 46 行全是预期替换掉的旧写法，注释少的 18 行全是 // 变 KDoc」）。
5. **Kotlin 的块注释会嵌套**：KDoc 里写正则片段 `/*[!/]` 会开一个**嵌套**注释，报错出现在**文件末尾**
   （`Unclosed comment`），看着像别处的问题（R65 踩，见 `TermuxEnvTest` 尾斜杠那条注释；
   R69 又踩一次：KDoc 里写 `text/*` —— 那次是 import 扫描器先把整个文件当成注释报出来的）。
6. **变异 / 替换脚本的多行锚点用三引号，别用反斜杠 n 转义**：`+ "]\n    if (…` 这么写时中间少一个引号，
   变异体编译失败，现象是「变异没响」—— 很容易被误读成「这里没有用例覆盖」（R68 踩）。
   同类的还有：`sed` 替换串里的 `&` 是「整个匹配」、TS 模板串里的反引号要转义（R64 踩）。

7. **抽纯函数会切断 Kotlin 顺着 val 的智能转换**（R11）。`if (x.isEmpty() || !ptcEnabled) { …; return }`
   之后，编译器本来是顺着 `val ptcEnabled = toolContext != null` 把 `toolContext` 当非空的；
   条件换成 `turnEndsAfterStep(x.size, ptcEnabled)` 之后这条推断立刻断掉 —— 这次是**编译器报错**
   拦住的（不是 R7 那种静默出错）。处理：显式 `checkNotNull(toolContext)` + 注释说明「走到这里
   PTC 一定开着」；**别为了保住智能转换就放弃提纯，也别把 `!!` 当默认答案**。
8. **变异脚本同一时间只能跑一个，还原必须放在 `finally` 里**（R87 踩）：那次两个变异驱动并发
   跑在同样的文件上（一个是被中断后仍在后台跑的旧脚本），互相把对方的「还原」踩掉，还撞出
   Windows 的 `classes.jar` 文件占用（`bundleDebugClassesToRuntimeJar FAILED`）；更阴的是
   **被 `timeout` 杀掉时 `finally` 不执行**，改坏的源码留在工作区里 —— 编译照样通过，
   只有那条针对性用例能发现。做法：一次只跑一条、只跑受影响的测试类（`--tests <类>` 几秒完事）、
   **下一轮开工第一步先 grep 三处锚点确认上一轮还原干净**。

**换模块了（R43 起）：设置页**（`SettingsScreen.kt` 2573 行 / 0 单测）。已落地 R43–R46
（容量字段 → 添加卡片的就绪算式 → 编辑卡保存 → 提供方清单增删改，504 → 544 全绿），
落在新文件 `ui/SettingsModels.kt`；**还剩两刀**（服务端模型清单的过滤与采纳 → 模型行的改名/删行），
见 [docs/ENTROPY-AUDIT.md](docs/ENTROPY-AUDIT.md) §8.4。

**下一批候选模块的只读体检已落盘**（`core/data` / `runtime/termux` / `core/tools` 各自的第一刀与雷区）：
见审计 §8 —— 那是换模块时用一个只读子代理量的，我抽查了 6 处行号与语义，逐条对得上。

下一步候选（按当前判断排序）：

- `Tools.kt` 的 write/edit 升级流程那 12 行重复：**已判定保留**（是"各自必须写 toolName"的机械后果，
  抽出来只省 12 行却要新增返回类型，且有测试覆盖）。CPD 的 12 行就是它，别再当待办。
- 省略号三件套（`maxLines = 1 + TextOverflow.Ellipsis`，实测 76 处 / 12 文件）：**已判定不做** ——
  那是 Compose 的声明式 API 用法，不是本项目的视觉决定，包装只会把标准 API 藏起来。
- ~~R9 设置页「功能」分节拆解~~：**已完成**（245 → 18 行）。
- ~~R10 拆 `TerminalPanel`（538 行）~~：**已完成**（四个提交，见上表）。最长手写函数 538 → 321 行，
  新榜首是 `runTurnBody`（397 行）—— 终端页还没真机手测过的项见该提交的提交信息（Tab / Esc /
  PgUp / PgDn 是原始字节直通，本轮只冒烟了 Ctrl-C）。
- ~~R11 拆 `runTurnBody`（397 行）~~：**已完成**（判定提纯，8 个纯函数 + 14 用例）。函数本身只从
  397 降到 391 行 —— **这是有意的**：它不能再拆的前提是**没有集成测试**（`LlmClient` /
  `ConversationRepository` 都是具体类，离线环境又没有 Robolectric），硬拆只是把风险挪个地方。
  下一步若要继续，先做「可注入的接口 / 内存实现」这一步，再谈 StepRunner。
- ~~R12 状态提纯~~：**已完成**（浮层互斥 + dismissed → `ChatOverlays`，10 用例）。**方案里的
  R0–R12 全落地**。后面两件不在方案里：
  ① ~~`ChatScreen` 的文件切缝~~：**已完成**（R13 六刀，2651 → 1024 行）；
  ~~主函数的 state holder~~ **也已完成**（R14 五步：滚动状态 / 会话流 / 顶栏与底部那一摞 /
  回车意图 / 流式采样各自成文件或纯函数，**主函数 936 → 480 行**，全库排名从第 1 掉到第 2）；
  ② ~~`runTurnBody`（391 行）继续拆 —— 先做可注入的接口 / 内存实现~~：**接口与内存实现已完成（R23）** ——
  三个窄端口（`TurnLlm` / `TurnStore` / `TurnSettings`）+ 4 条黄金用例，`runTurnBody` 一行未动；
  下一刀就是拿这张网拆它（顺序：请求装配与落库收口 → 一轮的流式采样与记账 → 工具派发循环 → 中断落库）；
  ③ ~~`DshComposer`（495 行）~~ **已完成**（R15：两个弹层菜单 → `ComposerMenus.kt`，495 → 326）；
  ~~`AppRoot.kt` 的文件切缝~~ **也已完成**（R16：抽屉整簇 → `Drawer.kt`，1310 → 542）。
  **ui 模块还剩两个 300+ 行的函数**：~~`QuestionCard`（369）~~ → R18 拆成三段（215）、
  ~~`Drawer`（306）~~ → R17 拆成四个小节（214）、`ChatScreen`（440，R19 又减 40）；
  剩下 `ChatScreen`（440）与 `AppRoot`（391）—— 都是「状态 + 装配」同一类，
  继续照 R12 / R14 的手法做；做完换模块。
- **一条测量教训（R9 发现）**：我以前那张「最长函数」表是拿花括号计数配平的，而花括号会出现在字符串里，
  于是 `TerminalPanel`(538)、`AppRoot`(375)、`QuestionCard`(368)、`runTurnBody`(397)、
  `startTurn`(302) 全被算短了 —— `FeaturesSection` 从来不是全库最长的函数。
  **要长函数排名，先把注释与字符串挖空再配平**（口径写在 `docs/ENTROPY-AUDIT.md` §2.2）；
  另外 `ModelThinkingLevels.kt` 是生成的数据表（1252 行的 `qualified0` 只是装 662 条掩码的盒子），
  不参与排名。
- 版本：**0.2.0（versionCode 12）已发布**（2026-10-03）—— 快照 tag `v0.2.0-20261003`、
  资产 `dist/ADSH-0.2.0-release.apk`（R8 minify + shrinkResources）、tag 指向**单个 orphan 快照提交**
  （R0–R79 的历史在这一步按惯例压平，旧记录随之消失）。
- **tag**：本地/远端都只有 `v0.2.0-20261003`（本批）与 `v0.1.9-20261002`（上一版发布，
  **这次按用户口径保留**，它的 release 也留着）。旧的 `v0.2.0-modules` / `v0.2.0-phase2` 已清掉。
- **发布物**：`dist/ADSH-0.2.0-release.apk`（44.6 MB，签名与 debug 同一把：SHA-256
  `136e0b32…479f7`，`install -r` 可互相覆盖且不动数据）与 `dist/ADSH-0.2.0-debug.apk`（65.7 MB，
  装机调试用；**release 包 `run-as` 会被拒**）。设备上现在装的是 release 包。
- **第二阶段的经验已单独成文**：[docs/PHASE2-SUMMARY.md](docs/PHASE2-SUMMARY.md)
  （做了什么 + 四件套手法 + 踩过的坑与解法 + 必须做/绝对不能做）。
- **源码与 release 资产已按「覆盖」口径更新完**（2026-10-03，R86 之后）：远端 `main` = `2013d29`
  （单提交 orphan 快照，R0–R86 的历史在这一步压平）、同名 tag `v0.2.0-20261003` **强制移动**到它；
  release 资产用 `gh release upload --clobber` 换成新包（44,603,158 字节，sha256 `dc9d5f7c…`，
  与本地 `dist/` 那份逐字节一致），**说明与标题是用户自己在网页上写的、一字未动**（核对过 diff 为空）。
  **本地与远端已一致，没有待推提交**；`v0.1.9-20261002` 的 tag 与 release 按用户口径保留。
- **抽屉动画别再动「手感配方」了**：弹簧、缓动曲线两版都被用户否掉过，最终口径是
  `tween(DRAWER_SLIDE_MS, LinearEasing)`，用户实测 **130ms** 满意（220 → 170 → 130 的收敛过程
  写在 AppRoot 那个常量的 KDoc 里）。**真要调只调 `DRAWER_SLIDE_MS` 这一个数**；
  `SLIDE_MS`（220）是覆盖页的，两者已拆开，别再把它们合并成一个。
- **下次推远端 + 覆盖 release 资产时注意**：
  1. 快照按老流程压平 → **同名 tag `v0.2.0-20261003` 强制移动**到新提交（覆盖语义，不是发新版）；
  2. 推 `main` + tag 都要 `--force`；
  3. 资产用 `gh release upload v0.2.0-20261003 dist/ADSH-0.2.0-release.apk --clobber`
     —— **绝对不要 `gh release edit`**：0.2.0 的说明是**用户自己在网页上改过的**
     （标题改成了「## ADSH 0.2.0（第二阶段）」、加了「用户协议」等），clobber 只换资产、不动说明。
- **当前门禁基线 824 全绿**（R87 811 → R88 824）；设备上装的是 **0.2.1-debug（code 13）**；
  再往前那一次装的是 `0.2.0` release 包（`run-as` 会被拒，
  要调试就装 `dist/ADSH-0.2.0-debug.apk`）。
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
     `git@github.com` 直接 `Permission denied (publickey)`）。
     **第 81 轮（2026-10-03，发 0.2.0 那次）已把 `origin` 从 SSH 改成
     `https://github.com/yuyuyuyulike/ADSH.git`**，实测 `git ls-remote origin` 与
     `git push --dry-run origin main` 都直接可用（第 100 轮那次 DNS 抽风现在也好了）——
     平时就是 `git push origin main` + `git push origin <tag>`，需要覆盖时加 `--force`。
     **若哪天 DNS 又给出连不上的 `github.com`**（第 100 轮实测是 `20.205.243.166`，
     `Failed to connect … after 21s`；而 `api.github.com` / `codeload` / `uploads` 一直正常），
     再钉一个能连的 IP（`140.82.121.4` / `140.82.112.4` / `140.82.113.4` / `20.27.177.113`
     都试通过，同一个 IP 会时好时坏；`api.github.com` 一定要走正常 DNS，别把全局的 resolve 钉死）：
     `git -c http.curloptResolve=github.com:443:140.82.113.4 push origin main`。
     `/usr/bin/gh … No such file` 那行 stderr 是无害的（凭据已经拿到，只是最后 store 那一步走不通）；
     另外 `gh auth` 的 keyring token 会过期（第 81 轮就遇到过 `The token in keyring is invalid`，
     连 `git` 存的那个也一起失效）—— 重跑一次 `gh auth login -h github.com` 即可；
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
- **抽屉收起的起步必须等「内容换完」**（第 125 轮重做，旧的 30ms 延迟 `DRAWER_SELECT_DELAY_MS` 已删）：
  「选会话 / 新建 / 分叉 / 拖拽 / 点空白 / 返回键」全都走 `AppRoot.settleDrawer(0f)`，它先
  `withFrameNanos { }` **等两帧**（≈33ms，落在抽屉还完全盖着主界面的时候，感知不到）再 `animate`
  —— 等的是「这一帧的状态落进下一帧的组合」，所以**有没有内存缓存都一样**，也不再是固定延迟。
  这几条路径会同时换掉主界面里的会话内容，状态更新（读库 / 换绑工作区）必须落在滑动起步**之前**，
  否则主界面滑进来的那几帧里内容整块换掉，用户看到的就是「切会话时主界面闪一下」。
  这段等待必须待在**同一个 job** 里（另起协程去等会被下一次 cancel 抛下）；别把它改回固定 delay，
  也别当冗余删掉。
  **切会话还有第二道**：`ChatViewModel.switchConversation` 是 `suspend` 的，抽屉要**等内容就位再收起**
  （缓存命中 = 同步换正文；未命中 = 等正文读出来，上限 150ms）—— 收起动画期间不许再有正文换入换出。
- 界面：**自动滚动只有一个「跟随意图」**（第 122 轮整套重写，`AutoScroll.kt` 的 `ScrollFollow` = dsh 的
  `ScrollFollow.following` / `followingTail`）—— 只由三处写它：「读者真的移动过」（位置一变就按 25dp
  阈值重算 = dsh 的 `sample(metrics, movedByReader)`）、「读者动作 / 显式导航 → `pause()`」、
  「自己发消息 / 点回到底部 → `follow()`」。贴底请求统一走 `pinToBottom()`，**只由「变化」触发**
  （内容 = `items` 换了实例、视口 = 键盘）—— **不许回到「每重组一次贴一次底」**：会话停下来之后本屏
  仍会因为后台任务轮询 / 任务横窗 / 连接状态 / 用量重组，每重组一次贴一次就是「会话结束后自动滚动还在跑」。
  兜底补钉（`snapshotFlow { listState.layoutInfo }` → `scrollBy`）**位置变了就一律不补**（= dsh 的
  `movedByReader`：读者自己滚出来的位移只重算意图、不贴底），只补「视口没动、布局却变了」（图片 / LaTeX
  异步解码）—— 少了这条判据，读者「极小幅度快速上滑」会被一帧一帧拽回底部（第 122 轮用户报的抖动）。
  **两条贴底路径都先过 `pinAllowed`**：`following && !touching && !listState.isScrollInProgress` ——
  最后一项就是 dsh 的 `onScroll` 那道 `!movedByReader` 门（读者的拖拽 / 惯性还在走时一拍都不许贴）。
  少了它，流式期间内容每长高一帧都会把读者的上滑按回底部（第 125 轮真机反馈：agent 思考时极小幅度
  快速上滑被拉回、自动滚动没停；不思考时没有内容在长，所以只有流式期间看得出来）。
  **25dp 迟滞没动**：位移落在阈值以内仍算「在底部」，恢复跟随后继续贴底 —— 这是 dsh 的口径。
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

- **实况窗（Android 16 Live Updates）**：API 已在 `platforms/android-37.0` 上 javap 核对完
  （`Notification$ProgressStyle` / `Builder.setRequestPromotedOngoing` / `setShortCriticalText` /
  `NotificationManager.canPostPromotedNotifications`）。下一步是**设备实验**：API 36+ 且
  `canPostPromotedNotifications()` 为真时改走实况窗（可能多出一条通知、也可能 MIUI 不 promote），
  拿不准就留媒体路线兜底；targetSdk 28 够不够资格被 promote 未证实。
- **第 181 轮的切会话修复**待用户真机复测：切走再切回来，流式尾巴 / 工具行 / 岛要原样接上，
  上一条会话的「吃白饭中」也不许跑到新会话里（判据在 `ui/LiveRunStateTest`）。

