# ADSH 第二阶段 · 代码熵减方案（v2，待确认）

> 目标：**不加新功能**，只做熵减 —— 用更少的代码实现同样（或更稳）的行为。
> 验收不看"减了多少行"，看**行为不变量**：编译过、单测过、真机装得上且关键路径手测过。
> 参照对象：dsh（本地源在 D:/tmpdsh-work/gh-src，改行为前先核它）。v2 并入了只读审计与外部调研的实测数字。

## 0. 前置门禁（本阶段第一件事）

| 门禁 | 内容 | 为什么必须有 |
|---|---|---|
| G1 | 恢复单测为**默认门禁**：./gradlew.bat --offline :app:testDebugUnitTest（368 用例，第 117 轮起停跑） | 本阶段全是"行为不变"的改写，测试是唯一廉价的行为护栏 |
| G2 | 每改一个模块：:app:assembleDebug 过 + adb install -r 装机启动无 FATAL | 这个项目 124 轮里最贵的 bug 全是真机才出来的 |
| G3 | **保持离线**。GRADLE_USER_HOME 里没有 detekt/ktlint 缓存 → 静态检查只用 AGP 自带 lint + 仓库里的 python 脚本 | 不为"上工具"破坏离线构建 |
| G4 | HANDOFF 末尾那 30 条硬规矩是**不变量**；改动碰到哪条，就在那里留注释说明 | 那是 124 轮真机血泪，不是风格偏好 |

## 1. 模块账（实测：2026-10-02 / 0.1.9）

main 侧 **91 个 .kt / 39304 行**（注释占 21.5%），test 侧 53 文件 / 6235 行（368 用例）。

| 模块 | 文件 | 行数 | 熵的现状 |
|---|---|---|---|
| ui/ | 44 | **22012** | **56% 的代码、214 个 @Composable 全在这**；最大文件 2771 行 |
| core/tools/ | 12 | 5380 | 已有 Args 共用层（Tools.kt:183-186），但被绕开 39 次 |
| core/data/ | 6 | 3373 | 其中 1274 行是生成物（不是熵，不要动） |
| core/agent/ | 9 | 2713 | 结构健康：纯函数已拆出且有测试 |
| runtime/termux/ + ptc/ + cpp/ | 10 | ~3060 | 平台耦合重，本阶段基本不动 |
| llm / jobs / session / workspace | 8 | ~1600 | 小且干净 |

结论：**熵集中在 ui 层**；core 层已经自发演化出正确做法（决策提成纯函数 + 单测）。
所以本阶段战场是 ui，方法就照 core 已经证明有效的那套：**把逻辑从大文件里提成纯函数/小组件，让测试和编译器守住行为**。

## 2. 熵在哪（每条都有可复现依据）

1. **巨型 Composable**：ChatScreen() 主函数 **958 行**（323–1280，直接调用 24 个）。去掉主函数后，文件里有 **21 个连通分量**，其中 9 个已成型（见 §3 切缝表）。Composer.kt 同型（主函数 505 行 + 13 个分量）、AppRoot.kt 有 5 个。
2. **重复的交互底座**：无涟漪点击 **83 处**（`indication = null` 全库计数：51 处内联 interactionSource + 28 处变量形式），跨 22 文件；省略号三件套 40 处 / 13 文件；`CenterVertically + spacedBy(N)` 64 处 / 13 文件（8dp×22、6dp×19、4dp×8…，**无命名常量**）。三类合计约 **155 处**，而专责公共封装的 DshPrimitives.kt **一个都没有**（它只有 DshButton/DshIconButton/DshCheckbox/DshModal），全库仅 3 个局部封装。
3. **状态与入口铺得太开**：ChatUiState **32 个字段** + 47 处整对象 copy；ChatScreen **自持 20 个 UI 状态** + 4 个 rememberSaveable，全部 0 可测；ChatViewModel 对外 **51 个成员**，其中 **16 个纯转发**。
4. **死分支**：ChatEvent 12 个 case 里 **Usage / Finished 是死的**（LlmClient.kt:256/303/326/359/370/371 会发，ChatViewModel.kt:989 落进 `else -> Unit`）。另有同一 sealed 类型两个消费点、每个 Step 一次 refreshMessageTokens()（964）、每个 InboxClaimed 一次读库（969）。
5. **工具层"有 helper 却绕开"**：裸 jsonPrimitive 39 处 / 4 文件（WebTools.kt 21）；bool 解析两套语义；**15 处手拼 `?: return ToolResult.Error`**；**截断 5 套实现、3 种标记**；**上限常量 28 个散落 6 文件**；buildJsonObject 53 处。
6. **可测性覆盖低但路径已验证**：ui 下 **423 个顶层 fun/val 只有 38 个被 test 引用**；22 个 ui 测试文件测的全是纯函数。真正把可测逻辑压进 Context 的主要是 WorkspaceActions.kt（9 个顶层函数只有 1 个纯）。
7. **注释密度 21.5%**：是资产不是负债（"为什么 targetSdk 钉 28"必须留着），只有 HANDOFF 的 30 条硬规矩与代码注释在重复讲述。

## 3. 已核实的切缝（"互不调用"的连通分量，直接可搬）

| 文件 | 主函数 | 可独立成群的块（行号） |
|---|---|---|
| ChatScreen.kt (2771) | 958 行 / 24 调用 | 滚动状态 124–197、纯函数与常量 211–316、四条状态条 1294–1403、ConnectionBar+LoadingDots 1404–1484、CompactCard 1485–1533、用户消息群 1579–1916、ApprovalCard+PlanReviewCard 1968–2158、QuestionCard 群 2187–2589、TodoDock 群 2614–2771 |
| Composer.kt (1586) | 505 行 / 24 调用 | claim/提示 119–217、弹层+ContextMeter 268–578、WorkspaceChipRow 1095–1166、AttachmentCard 群 1173–1265、CircleIconButton 1266–1293、TriggerPill+PlanChip 1294–1351、SendButton 1352–1399、CommandPalette 1429–1586 |
| AppRoot.kt (1312) | 381 行 | PanelLayer 502–567、Drawer 群 558–886、WorkspaceGroupRow+SessionRow 887–993、搜索群 1002–1178、RenameDialog 1182–1236、DrawerPanelRow+DrawerEntry 1247–1312 |
| SettingsScreen.kt (2580) | 仅 44 行 | 只有控件库 2112–2580（17 个控件，本文件外**零引用**）是真信号；其余是 1 个连通块，**不进 R2** |

## 4. 怎么做（五把刀 + 不做的清单）

| 手法 | 判据 | 本次的目标 |
|---|---|---|
| 归一（同一视觉/交互只留一份） | 同一模式出现 ≥3 次 | 83 处无涟漪点击 → 1 个 `Modifier.clickableNoRipple()`；40 处省略三件套 → `DshTextEllipsis`；间距值 → 命名常量 |
| 提纯（决策从 Composable 提成纯函数） | 函数体里 if/when 与副作用混在一起 | 已有先例：shouldClearComposerFocus、foldLiveTurn、mergeTurnFiles、buildChatItems |
| 切缝（只按互不调用的缝拆文件，不改语义） | 连通分量 | 见 §3 表；ChatScreen 先拆 3–4 个文件 |
| 表格化（同形分支 → 一张表） | 复制粘贴的分支 ≥4 个 | ChatViewModel 16 个纯转发 / 6 个同形 setter |
| 删除（死代码/死资源） | 脚本或 lint 判定无引用 | ChatEvent 两个死分支；SettingsScreen 17 个零引用控件；scripts/find-unused-*.py 四件套 |

**本阶段明确不动**：
- core/data/ModelThinkingLevels.kt（生成物，头上写着"不要手改"）与 gen-reasoning-levels.py；
- PromptAssembler 的提示词正文与 ToolSdk.specs（对 dsh 逐字对齐，动它=改行为）；
- runtime/termux、cpp/、QuickJS 栈预算、写围栏、后台任务/收件箱/自动滚动那几条硬规矩；
- 任何"顺手把行为改好一点"的念头 —— 本阶段只做等价改写，改进挂到下阶段。

## 5. 路线（一小步一提交，每步独立可回滚）

- **R0 护栏**：全量单测记录基线；把"熵值口径"固化成 scripts/entropy-report.py（>800 行文件数、`indication = null` 计数、Composable 平均行数、test 引用率）。重复块用 **PMD CPD**（支持 Kotlin、按 token 阈值；detekt 没有重复检测规则）本地跑，不引 Gradle 插件。产出：一份数字基线。
- **R1 交互底座归一**（低风险，先摘果子）：83 处无涟漪点击 + 40 处省略三件套 + 64 处行间距 → DshPrimitives.kt 与一组命名间距常量。判据：脚本计数 83 → 1（全库只剩封装自己），手测点击/长按/选中不受影响。**注意**：已有的 DshButton/DshIconButton/DshCheckbox 是"按钮"，新增的是"不带涟漪的可点区"，两者不合并。
- **R2 ChatScreen 切缝**（中风险，收益最大）：按 §3 表把 9 个成型块搬成 3–4 个文件，ChatScreen() 本体只留状态接线与列表骨架。判据：流式、贴底/回到底部、折叠展开手测与改前一致。
  **口径修正**（外部依据）：拆分标准不是行数，是**参数数量与内聚**；而同一来源还有一句更狠的 —— **"若正确性依赖精确的重组边界，别的地方一定错了"**。ADSH 现在正是这种状态（流式采样状态必须留在 ChatScreen 这一层，靠注释警告后人别抽出去）。所以 R2 **只搬运**，这种依赖留到 R3 用 state holder 正面解掉，而不是继续用注释兜着。
- **R3 状态接线归一**：ChatViewModel 的 16 个纯转发与 6 个同形 setter 表格化；ChatScreen 20 个自持 UI 状态里能提纯的（展开收起判定、滚动意图）提成纯函数并补测试；34 个回调收成一个 ChatActions 数据类（只搬不改）。判据：设置项逐项手测 + 新增纯函数单测全绿。
- **R4 工具层顺手清**（低风险）：39 处绕开 Args 的裸 jsonPrimitive 收回 helper；bool 解析两套语义合一；**截断 5 套实现收成 1 套**（保留模型看得懂的那种标记，不许改提示词承诺）；28 个上限常量收到一处；两个 sourceLabel / 两个 sha256Of 合一。Args 够用就别再造新抽象。
- **R5 删除与静态扫描**（先从已知的开刀）：ChatEvent 的 Usage/Finished 两个死分支；SettingsScreen 的 17 个零引用控件 —— **先对比视觉再决定"合并还是删"**（Ericsson 那条：克隆消除不是机械操作）；跑四个 find-unused-*.py + lint UnusedResources/UnusedPrivateMember；把可脚本化的判据写进 scripts/ 而不是文档。
- **R6 文档收敛**：HANDOFF 的 30 条硬规矩 → 正文留在代码/测试里，文档只留索引与"为什么"；目标 -30%，允许不减（准确优先）。

排序理由：R1/R4 低风险，先建手感与自动化；R2 收益最大但要等 R1 的脚手架；R5/R6 放最后，免得与前面的改动互相干扰。

## 6. 进度与订正（随做随更）

| 步骤 | 状态 | 实际结果 |
|---|---|---|
| R0 门禁 + 基线 | ✅ | 368 全绿（1 分 16 秒）；CPD 5 组 / 118 行。两个红用例是测试自己的缺陷（过期断言 + `contains("")` 恒真） |
| R1 无涟漪点击 | ✅ | **83 处**（不是 80）→ 1 个入口 `Modifier.dshClickable`；`dshInteraction()` 消掉 57 处内联 `remember`；净减 195 行 |
| R2 图标集 | ✅ | 5 份私有工厂 → `DshIconFactory`；CPD 118 → 46 行；119 段 path 多重集逐字未变 |
| R3 间距刻度 | ✅ | **133 处**（不是 64，审计只数了组合写法）→ `DshSpacing`；数值分布完全一致（纯改名） |
| R4 两张警示卡 | ✅ | → `DshWarnCard`；CPD 46 → 27 行 |
| R5 工具层 | ⚠️ **多数结论不成立** | 见下 |
| R6 文档 | ✅ | HANDOFF 增「第二阶段」一节；方案本节 |
| R7/R7b 图标工厂残留 | ✅ | `grouped` / `stroke` / 手写 builder 并入 `dshIcon`；补 `scripts/check-icon-structure.py` |
| R8 内边距刻度 | ✅ | 381 处纯改名（刻度外 52 个不动）；`scripts/verify-padding.py` 逐实参 0 处不一致 |
| R9 设置页「功能」分节 | ✅ | 245 → 18 行；三张卡的 209 行代码逐字未变 |
| R10 拆 `TerminalPanel` | ✅ | 538 → 321 行；816 行的文件拆成四块（379 / 308 / 289 / 93）；391 全绿；真机冒烟过 |
| R11 提纯 `runTurnBody` | ✅ | 判定 → `TurnDecisions.kt`（8 个纯函数 + 14 用例）；405 全绿；函数 397 → 391 行（缺的是集成测试，不是切缝，见审计 §4） |
| R12 提纯会话页浮层 | ✅ | 互斥 + dismissed → `ChatOverlays.kt`（10 用例）；415 全绿；ChatScreen 净减 21 行 |
| CPD 终值 | — | **1 组 / 12 行**（仍剩 `Tools.kt` write/edit，已判定保留） |
| R17–R23 | ✅ | **活表在 [HANDOFF.md](../HANDOFF.md) 的「第二阶段」一节**（本节停更于 R16，别再在这里续写）：R17–R21 把 ui 四个大户全部拆完（480/391/369/306 → 397/366/222/214），R22 把标题与摘要的流式收文本合成 `LlmTextCollect`，**R23 = 甲方案第 1 步**：core/agent 的三个窄端口 + `TurnFakes` + 4 条黄金用例（449 → 453 全绿），`runTurnBody` 先不动、网织好再拆 |

（方案外的后续：**R13 已完成** —— 先提纯滚动意图（`AutoScroll.kt`，9 个用例），再六刀把
ChatScreen 从 2651 行拆到 1024 行（`QuestionCard` / `ApprovalCards` / `TodoDock` /
`UserMessages` / `ChatBars` / `ChatDecisions`），并修掉 `longest-functions.py` 的测量 bug。
**R14 也已完成** —— 给主函数做了 state holder（五步：滚动状态 / 会话流 / 顶栏与底部那一摞 /
回车意图 / 流式采样），**主函数 936 → 480 行**、ChatScreen 1024 → 546 行，441 全绿。
**R15 / R16 继续同一手法**：`DshComposer` 的两个弹层菜单 → `ComposerMenus.kt`（495 → 326 行）；
`AppRoot.kt` 的抽屉整簇 → `Drawer.kt`（1310 → 542 行）。版本号随之升到 **0.2.0**（code 12）。
详见 [ENTROPY-AUDIT.md](ENTROPY-AUDIT.md) §2.2 / §4 与 HANDOFF。）

**R5 的订正（三条审计结论经核实不成立，别再当待办）**：

1. 「39 处绕开 `Args` 的裸 `jsonPrimitive`」——实际只有 **2 处**是工具入参（`Approval` 的
   `sandbox_permissions` / `justification`，已收回）。其余是**供应商返回的 JSON**（`WebTools` 的
   `item["url"]` 等）与 **ripgrep 的结构化输出**（`Tools.parseGrepMatches`），那些键是别人定的方言。
2. 「截断 5 套实现 3 种标记」——是**五个不同的东西**（bash 中段省略池 / WebFetch 正文尾部+页脚 /
   Fetch 来源前缀 / ripgrep 单行字节截断 / 子调用参数展示），且都进模型可见面，统一 = 改工具输出。
3. 「28 个上限常量散落 6 文件」——就近定义是对的（`GLOB_MAX_RESULTS` 只在 glob……）。
   真正重复的是**提示词里又写了一遍这些数字**，已在 `ToolSdk` 写下这条耦合与四处核对结果。

**另外两条自己判定的"不做"**（写在这里，免得下一轮又捡起来）：

- `Tools.kt` write/edit 那 12 行：是「两个工具各自必须写自己的 toolName」的机械后果，抽辅助函数
  只省 12 行却要新增返回类型，且有 `ToolConcurrencyTest` 覆盖分叉 —— 保留。
- 省略号三件套（实测 **76 处 / 12 文件**，审计说 40 处）：`maxLines = 1 + TextOverflow.Ellipsis`
  是 Compose 的声明式 API 用法，不是本项目的视觉决定，**没有可共享的可变决策**，包装只会把标准
  API 藏起来。与"无涟漪点击"（带项目级不变量）性质不同，故不做。

**方法论的订正**：审计报告的关键字计数不能直接当待办。它把三类东西算成一类（工具入参 / 供应商
响应 / rg 输出），把五个不同的东西算成"一套重复"，还漏掉了 `ChatEvent.Usage` 的真实消费者
（`AgentLoop` 的 token 账本 —— 差点被误删且无任何编译或测试会拦）。**"死代码 / 重复"结论动手前
一律自己复核。**
## 7. 团队怎么用（开工后）

- 写入面按文件/目录切，同一文件同一时刻只有一个 owner；R2 期间 ChatScreen.kt 是独占写区，别人只读。
- 每个任务在描述里写死"验收命令 + 手测清单"；完成判据是**门禁过**，不是"改完了"。
- 每轮结束由 Lead 跑一次全量门禁并留一份熵值报告（R0 脚本），看趋势不看单点。

## 8. 外部依据（研究子代理抓取，2026-10-02）

- **Google Compose**：状态提升（无状态 composable + 事件上抛，业务状态交 state holder）、**单一 UiState 单一数据源**、不暴露可变 state；List/Map 参数用 @Immutable/@Stable 包（Kotlin 2.0 起 strong skipping 默认开）；每个 composable 有自己的重组域，拆小可把 state 读取限制在更小范围。
- **Kotlin 等价重写**：sealed interface + 穷尽 when 替代字符串/布尔状态；@JvmInline value class 零开销消歧；接口委托 by 替代手写转发；单表达式/when 替代临时变量；**无 lambda、无 reified 的 inline 应删**（官方 NOTHING_TO_INLINE）；作用域函数别过度使用、别嵌套。
- **工具**：detekt complexity（LongMethod 60 行 / LongParameterList 5-6 / NestedBlockDepth 4）+ style 的 UnusedPrivateMember；**detekt 无重复检测 → 用 PMD CPD**；Android Lint 的 UnusedResources 与 UnrememberedMutableState(Error)；detekt 与 lint 都支持 baseline **只拦新增**。
- **案例**：Mercari 用 Compose 重建砍掉约 35.5 万行（-69%）；Ericsson 的工业实证提醒**克隆消除不是机械操作**，该抽象还是该保留要按个案判断；重构的定义就是"不改变外部行为"，Extract Function 是最基本的机械步骤，适合小步提交。
- 验收口径：*"能表达意图、易测、易维护、性能够好，就是好代码"*（Adam Powell）—— 不是行数少就是好代码。

## 9. 要你拍板的事

1. **门禁**：本阶段恢复"每步跑 368 个单测"吗？（代价：每步多几分钟；收益：等价改写唯一的安全网）
2. **工具**：允许联网引入 detekt + PMD CPD 吗？默认不引（保持离线，只用 lint + 现有脚本 + R0 自建口径）。
3. **粒度**：一次提交一个手法（如 R1、R3 各一次），还是一个模块一口气做完？建议前者。
4. **文档**：HANDOFF 那 30 条硬规矩允许"搬进代码注释、文档只留索引"吗？
5. **节奏**：R2 先只拆文件、状态归属留到 R3 —— 认可吗？
