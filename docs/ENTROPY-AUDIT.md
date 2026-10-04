# ADSH 代码熵审计（第二阶段版）

> 这份文件取代第一轮由外部子代理产出的那份（那份已作废，其结论逐条核实在 §1）。
> 口径：每条都要能复现（给命令或给数字），且**动手前必须先自己看代码**。
> 时间：2026-10-02，HEAD = R8（快照 tag v0.1.9-phase2a 打在 R7b 上）。

## 1. 这几轮改了什么（第二阶段已落地）

| 提交 | 内容 | 判据 |
|---|---|---|
| R0 | 恢复单测门禁；立 CPD 基线 | 368 全绿；CPD 5 组 / 118 行 |
| R1 | 83 处无涟漪点击 → `Modifier.dshClickable` | 全库只剩封装自己 1 处 |
| R2 | 5 份图标私有工厂 → `DshIconFactory` | CPD 118 → 46 行 |
| R3 | 133 处 `spacedBy(N.dp)` → `DshSpacing` | 数值分布完全一致 |
| R4 | 审批卡 / 计划卡共用壳 → `DshWarnCard` | CPD 46 → 27 行 |
| R5 | 删 `ChatEvent.Finished` 整条链；`Args` 边界成文 | 368 全绿 |
| R6 | 抽屉两处 `SessionRow` → 局部 composable；文档核实 | CPD 27 → 12 行 |
| R7/R7b | `grouped` / `stroke` / 手写 builder 并入工厂；补结构校验脚本 | 全库只剩 1 处 `ImageVector.Builder` |
| R8 | `padding(...)` 实参里的 381 个裸 dp → `DshSpacing` 刻度名 | 258 个调用点逐实参比对 0 处不一致 |
| R9 | 设置页「功能」分节 245 行 → 18 行，按卡拆出三张插件卡 | 三张卡的 209 行代码逐字未变 |
| R10 | 拆 `TerminalPanel`（538 行）：输入行纯逻辑 → `TerminalInput.kt`；会话 / I/O → `TerminalSession.kt`；三块布局 → `TerminalParts.kt`；输入行接到纯函数 | 391 全绿；「代码行 / 注释行」多重集比对**无一行丢失**；真机冒烟（起 bash / 执行 / ↑↓ 历史 / Ctrl-C / 退出重开） |
| R11 | 提纯 `runTurnBody` 的判定 → `TurnDecisions.kt`（8 个纯函数：路由 / 思考字段 / 死循环 / 本轮结束 / 两种落库口径 / 收尾文案） | 405 全绿（+14 用例）；多重集比对无逻辑丢失；runTurnBody 397 → 391 行（**只提纯、不拆**，理由见 §4）；装机启动无 FATAL |
| R12 | 提纯会话页浮层的**互斥**与 dsh 的 **dismissed** 语义 → `ChatOverlays.kt`；ChatScreen 的四个自持状态收成一个 | 415 全绿（+10 用例）；ChatScreen 净减 21 行；装机后点顶栏统计浮窗正常 |
| R13 | 切缝 ChatScreen：先提纯滚动意图（`AutoScroll.kt`），再六刀搬出 6 个文件（提问卡 / 审批卡+计划卡 / 任务 dock / 消息群 / 浮条与空态 / 纯判定与常量）；顺带修掉 `longest-functions.py` 的测量 bug | ChatScreen **2651 → 1024 行**；425 全绿；每刀都做「代码行 / 注释行」多重集比对（差异只有可见性）；**主函数仍 936 行**，见 §2.2 |
| R14 | ChatScreen 主函数的 state holder（五步）：①滚动**状态** → `ChatScrollState` + `PinFixEffect`（AutoScroll.kt）②会话流 → `ChatTranscript.kt` ③顶栏与输入框上方那一摞 → `ChatBars.kt` ④回车意图 → `Palette.commandIntentOf` ⑤流式采样 → `StreamReveal.kt` | ChatScreen **1024 → 546 行**（**主函数 936 → 480**）；441 全绿（+16 用例）；五步都做「代码行多重集」比对，搬走的判定逐行对应 |
| R15 | `DshComposer` 里两个弹层菜单（模型 / 推理等级两级、权限预设）→ `ComposerMenus.kt`；6 个只服务它们的常量跟着代码走，`popupPanelMaxHeight` / `TriggerPill` 改成 internal | DshComposer **495 → 326 行**；441 全绿；「只在旧侧」只剩被改写的标识符 |
| R16 | 会话抽屉整簇（`Drawer` + 11 个私有件）→ `Drawer.kt` | AppRoot.kt **1310 → 542 行**（Drawer.kt 800 行）；441 全绿；正文一字未改（唯一差异 `private fun Drawer(` → `internal fun Drawer(`） |
| R17 | 抽屉主体拆成四个小节（字标 / 新会话按钮 / 工作区小节头含展开搜索 / 删除工作区确认框） | `Drawer` **306 → 214 行**；441 全绿；「只在旧侧」只剩参数化与三处接线改写 |
| R18 | 问题卡片拆成三段（卡片头 / 选项行 / 卡片脚） | `QuestionCard` **369 → 215 行**；441 全绿；草稿与校验逻辑未动 |
| R19 | 触发菜单的命令字典（五条命令）→ `Palette.sessionPaletteCommands` | ChatScreen 主函数 **480 → 440 行**；441 全绿；字典内容一字未改 |
| R20 | ChatScreen 的 items 装配 → `TurnList.rememberChatItems`；附件选择器 → `Composer.rememberAttachmentPicker` | `ChatScreen` **440 → 397 行**；441 全绿；两个新函数各 1 处非注释调用点（R17 之后新增的检查） |
| R21 | AppRoot 的画布（图层栈 + 四个覆盖页分支）→ `AppLayerStack` | `AppRoot` **391 → 356 行**；441 全绿；1 处非注释调用点；**ui 四个大户全部落地**（480/391/369/306 → 397/356/215/214） |
| R22 | 标题生成与压缩摘要各抄了一遍「流式收文本」→ `core/llm/LlmTextCollect.collectText` | 两处各去掉 11 行重复；449 全绿 |
| R23 | **甲方案第 1 步：先织网**：`AgentLoop` 的三个具体依赖 → 窄端口 `TurnLlm`（1 个方法）/ `TurnStore`（10 个方法）/ `TurnSettings`（9 项），生产实现直接 `: 端口`（无适配器层）；默认值搬到接口（override 不许重复默认值，调用方按静态类型继承）→ 现有调用点一字未改；补 `TurnFakes.kt` + `AgentLoopGoldenTest.kt` 4 条黄金用例 | **449 → 453 全绿**；`runTurnBody` 一行未动（先有网后拆）；`send` 的 debug 日志自检收进 `runCatching`（纯 JVM 下 `android.util.Log` not mocked，且它在 finally 里会盖掉真正的差异），改由测试直接断言 `SessionLog.violations` 为空；真机装机无 FATAL |

净效果：**CPD 118 → 12 行**（-90%）；四个公共入口（`dshClickable` / `DshIconFactory` /
`DshSpacing` / `DshWarnCard`）。R1–R7b 是 42 文件 +1100 / -1215 行的纯搬运；R8 是 24 文件
223 行原地改名（增删行数相等，因为刻度名比数字长，代价记在 §2.1）。R10 把 816 行的
`TerminalPanel.kt` 拆成四块（379 / 308 / 289 / 93 行），全库最长的手写函数从 538 行降到 321 行
（新榜首是 `runTurnBody`）。R11 又把 `runTurnBody` 的判定提成 8 个纯函数（+14 用例），
它自己 397 → 391 行 —— **行数没怎么动是有意的**：它缺的不是切缝而是集成测试，见 §4。

**方法论（比上面的数字重要）**：

1. **审计的关键字计数不能直接当待办**。第一轮审计五条里三条经核实不成立（详见 §3），
   而且把三类不同的东西算成了一类。最危险的一条是「`ChatEvent.Usage` 也是死分支」——
   它其实在 `AgentLoop` 喂 token 账本，删了会静默丢统计且**没有任何编译或测试会拦**。
2. **改完必须自己验证，不能相信替换脚本**。R7 我用脚本重拼图标块，两次把实参拼错位
   （`chr(10).join` 漏逗号、多行 path 只取首行），**编译器两次都没报**（`dshPart` 参数全有默认值），
   最后是真机首帧 `IllegalArgumentException: Unknown command for: D` 才暴露。
   现在有 `scripts/check-icon-structure.py` 按结构盯这类错。
3. **判断"不做"与判断"做"同样重要**。已判定不做并写明理由的有：工具层三项"重复"、
   省略号三件套、`Tools.kt` write/edit 那 12 行（见 §3）。

## 2. 现在的熵在哪（实测）

### 2.1 内边距刻度与间距刻度的**两套口径**（R8 已收，留档）

R3 把 `Arrangement.spacedBy` 全收敛了（133 处），却把 `padding(...)` 留在裸数字上 ——
同一个 `4.dp`，`spacedBy` 里写 `DshSpacing.Md`、`padding` 里写 `4.dp`。**R8 就是收这一处。**

R8 的实测口径（`scripts/tokenize-padding.py`，258 个调用点）：

```
padding 实参里的 dp 字面量共 446 个
  落在 DshSpacing 已有刻度上 394 个（88.3%）→ 替换 381 个
  刻度外 52 个                                   → 一个没动
```

**为什么 394 只换了 381**：差掉的 13 个是表达式实参里的字面量（`if (closable) 4.dp else 10.dp`、
`if (compact) 19.dp else 20.dp`、`(10 + node.depth * 18).dp`、`gutterWidth + 12.dp`）。
它们不是「某处内边距是多少」的声明，而是一段计算或分支，换成刻度名只会让表达式更难读，故原样保留。

**刻度外的 52 个为什么不动**：两簇，混在一起才看得清 ——

```
微调簇 5/7/9/11/19dp（十几处）＝ 落在刻度旁的视觉补偿（例如 horizontal = 8.dp, vertical = 7.dp）
留白簇 18/22/24/30/40/48/72dp  ＝ 卡片内边距、缩进列宽、空态留白，各有各的用途
0.dp 6 处                     ＝ 这一侧不要内边距，不是刻度
```

把 7dp 改写成 `DshSpacing.Lg + 1.dp`、或把 22dp 塞进刻度，都是**改观感**去换「看起来整齐」，
与 R8「只改名不改数值」的口径冲突。真要收，得先改设计，那是另一件事。

**R8 的代价（实测，不藏）**：刻度名比数字长，24 个文件里**新越过 120 字符的行 22 行**
（仓库原有 1899 行如此，占新增的 0.9%）。没有为此折行 —— 四处 `start/end/top/bottom` 全写成刻度名
再拆成 6 行，行数短了，可读性反而更差。这条当已知代价记着。

### 2.2 巨型函数：一屏内联组件（ChatScreen）+ 真正最长的几个（已重算）

（先说清楚：这一条是对我自己早先判断的**纠正** —— 我以前一直说"ChatScreen 是 958 行的函数"，
按函数体量统计后发现那是 24 个内联组件堆在一个作用域里，不是一段 958 行的逻辑。）

先纠正一个我自己早先的说法：`ChatScreen()` 虽然横跨 958 行，但它**不是**一个 958 行的函数体，
而是 24 个内联组件/分支堆在同一个作用域里。

**然后是这份审计自己的一个测量错误（R9 期间才发现，已重算）**：我原先那张「最长函数」表用的脚本
是拿花括号计数配平的，而花括号会出现在字符串里（Compose 的 `Text(…{…}…)` 之类），
于是它把不少函数**算短了**。重算口径：先把注释与字符串/字符字面量挖空（保留换行），再配平。

```
手写代码里最长的函数（已排除生成物 ModelThinkingLevels.kt；R31 之后重跑 scripts/longest-functions.py）：
  397 行  ChatScreen.kt:67       ChatScreen          顶层   ← ui 四个大户收尾后的榜首（R14/R19/R20）
  366 行  AppRoot.kt:73          AppRoot             顶层
  326 行  Composer.kt:567        DshComposer         顶层
  322 行  TerminalPanel.kt:58    TerminalPanel       顶层   ← 会话 / I/O 与三块布局已搬出（R10）
  259 行  TurnRail.kt:511        RailToolRow         顶层
  247 行  QuickJsRuntime.kt:91   run                 嵌套
  235 行  TurnList.kt:267        buildChatItems      顶层
  234 行  SettingsScreen.kt:1433 CustomProviderEditor 顶层
  （startTurn 已掉出前 15：R31–R33 三刀 **307 → 44 行**，纯投影 + 三段编排各起名字，见 §4）

生成物（不参与排序，是烘出来的数据表，函数只是装数据的盒子）：
  1252/1099/946/793/640 行  ModelThinkingLevels.kt  qualified0..4（662 条掩码）
```

原表里的 `FeaturesSection` 是 245 行（今已拆到 18 行）、`SearchBlock` 125、`FileTree` 113、
`ReadBlock` 103、`TodoDock` 100 —— 后四个在重算里位置不变，说明错的是**文件里带花括号字符串的那几个**
（`TerminalPanel`、`AppRoot`、`QuestionCard`、`runTurnBody`、`startTurn` 全被算短了）。

**这也改了优先级**：R9 收掉的是「一张卡里塞三套状态」，收益是真的（245 → 18），
但它**从来不是全库最长的函数**。R10 把 `TerminalPanel` 从 538 行拆到 321 行，R11 把
`runTurnBody` 的判定提成纯函数 —— 现在榜首是 `ChatScreen`（397 行），`runTurnBody` 以 391 行居第二。
它当时不能拆的原因是**没有集成测试**，R23 把缺口补上（三个窄端口 + 4 条黄金用例），R24/R25 随即落下两刀（391 → 251 行）：装配与落库收口、记账与收流各自成函数/成类。

**R13 之后（切缝的实账）**：ChatScreen.kt 从 2651 行拆到 **1024 行**，搬出去六块：
提问卡 468 / 消息群 401 / 浮条与空态 335 / 任务 dock 232 / 审批卡+计划卡 186 / 纯判定与常量 172
（另有 R13 第一步的 `AutoScroll.kt`）。**但主函数本身一行没变小**：函数体仍是 **936 行**、
全库最长 —— 拆文件只是把周围搬空。

**R14 之后（state holder 的实账）**：五步把主函数里的**状态与装配**正面解掉 ——
`AutoScroll.ChatScrollState`（跟随意图 / 手指 / 方向 / 两个触发指纹 + 兜底补钉 `PinFixEffect`）、
`ChatTranscript.kt`（252 行：会话流 + 读者手势 + 快捷导航）、`ChatBars.kt`（顶栏 + 输入框上方那一摞）、
`Palette.commandIntentOf`（回车意图，5 个用例）、`StreamReveal.kt`（125 行：采样状态机 + 三条 effect）。
**主函数 936 → 480 行（-49%）**，ChatScreen.kt 1024 → 546 行，全库排名从第 1 掉到第 2
（榜首 `DshComposer` 495 行）。每一步都做「代码行多重集」比对：搬走的部分两侧逐行对应，
差异只有可见性、`follow.pause()` → `scroll.pause()` 这类改写，或把 `overlays = …` 换成回调。

**R15 / R16 之后（同一手法的第二轮）**：排行榜第 5 名 `DshComposer`（495 行）里那两个弹层菜单
（模型 / 推理等级、权限预设）搬进 `ComposerMenus.kt`（**495 → 326 行**）；`AppRoot.kt`（1310 行）里
的抽屉整簇（`Drawer` + 11 个私有件，625 行代码）搬进 `Drawer.kt`（**1310 → 542 行**）。
两次都做代码行多重集比对：R15 的「只在旧侧」只剩被改写的标识符，R16 的只剩
`private fun Drawer(` 一行 —— 即正文一字未改。
**文件级的收益与函数级的收益要分开看**：R16 不改任何函数的长度（`Drawer` 仍 306 行、`AppRoot` 仍 391 行），
它买到的是**文件职责**（AppRoot.kt 只剩画布 / 浮层栈 / 引导 / 返回处理）；R15 两者都买到了。

**顺带修掉一个测量 bug**：`scripts/longest-functions.py` 原先**漏掉带默认值 lambda 参数的函数**
（`onX: () -> Unit = {}` 里的花括号让配平提前收尾）—— ChatScreen（936 行）与 DshComposer（495 行）
因此从没上过那张榜。现在改成「先按圆括号跳过参数表、再从函数体的 `{` 配平」，并补了表达式体
判据；修完之后榜上第一行就是 ChatScreen（936）。

### 2.3 状态：ChatScreen 自持 20 个 UI 状态

`ChatUiState` 有 32 个字段（单一数据源，形态是对的），但**界面侧另有 20 个状态**留在 ChatScreen 的
`remember` 里：草稿与改写计数、三个互斥弹层的开关、命令面板的静音标记、展开态 map……
它们既不可测、也不可保存，还让 ChatScreen 必须停在 958 行（状态在哪，读取它的 UI 就得在哪）。

**R12 动了其中四个**：触发菜单的两份草稿（launcher / dismissed）、输入框的三个弹层、顶栏的统计
浮窗收成一个纯状态 `ChatOverlays`（互斥与 dismissed 语义各有断言，10 个用例），ChatScreen 那一段
净减 21 行；**R14 又把两处自持状态正面解掉**：自动滚动那五个数组 → `ChatScrollState`，
流式采样两个值 + 三条 effect → `StreamReveal`（`rememberStreamReveal` 返回对象，但**读仍在
ChatScreen 那一层** —— 这条硬规矩不能破，见那里的注释）。其余按原样留着：展开态 map 与草稿
改写计数各自绑在 LazyColumn item 与输入框上。

### 2.4 可测性覆盖低（这是前三条共同的结果）

实测（正则匹配 `^(internal |private |public )?(val|fun) 名字`，再在 test/ 全文里找名字）：

```
                     顶层 fun/val   被 test 引用
0.1.9 基线                423            39   （ 9.2%）
R12 之后                  445            49   （11.0%）
R14 之后                  455            56   （12.3%）
```

（三行是同一条命令在三个快照上重跑的：`git show` 出 `app/src/main/java/com/adsh/app/ui/` 下所有 kt
的顶层 `fun/val` 名字，再在 `app/src/test/` 全文里按词边界找。上一版这里的 433 / 44 与 455 / 54
是更早一次口径略有出入的跑法 —— **以这三行为准**，基线仍是 `08616e1`。）
也就是说：**布局与状态的决定仍然基本靠真机目视** —— R10–R12 把终端页与单轮循环的判定搬进纯函数
之后，这个比例只从 9.2% 抬到 11.0%（分母是 ui 层的顶层声明，搬文件不改分母）。**R14 说明抬它的
办法不是继续搬文件，而是把 ChatScreen 里的状态搬出来**：这一轮 +7 个"被测试引用"的顶层声明
（`ChatScrollState` / `shouldLatch` / `StreamReveal` / `commandIntentOf` / `PinFixEffect` …），
比例抬到 12.3%，同时主函数减半。

**core/agent 的循环路径以前是 0 覆盖**（`runTurnBody` 391 行里只有"判定"被提成纯函数测到）：R23 把
`AgentLoop` 的三个具体依赖抽成窄端口（`TurnLlm` / `TurnStore` / `TurnSettings`），整条回合现在能在
纯 JVM 下跑完 —— `AgentLoopGoldenTest` 的 4 条黄金用例把「请求 / 库行 / 事件 / 用量」逐字钉住，
`FakeTurnLlm` 的脚本耗尽会直接抛错（漏写脚本必须看得见）；R25 又把记账搬进 `TurnMeter` 并补了
7 个用例（`TurnMeterTest`），token / 时延口径第一次有了表；R27 又把工具世界收成 `TurnTools` 端口，
**工具轮**（schema / call-result 成对 / 工具行落库 / 死循环判据）也有了 `AgentLoopToolRoundTest`。

## 3. 已判定「不做」的（别再捡起来）

| 候选 | 判定 | 理由 |
|---|---|---|
| 工具层"39 处绕开 Args" | 不做 | 实际只有 2 处是工具入参（已收回）；其余是供应商响应与 rg 输出，是别人定的方言 |
| 工具层"5 套截断" | 不做 | 五个不同的东西，且都进模型可见面，统一 = 改工具输出 |
| 工具层"28 个常量散落" | 不做 | 就近定义是对的；真正重复的是提示词里又写了一遍数字（已写进 ToolSdk 导航注释） |
| `Tools.kt` write/edit 12 行 | 不做 | 是"各自必须写 toolName"的机械后果，抽出来只省 12 行却要新增返回类型，且有测试覆盖 |
| 省略号三件套（76 处） | 不做 | `maxLines = 1 + TextOverflow.Ellipsis` 是 Compose 的声明式 API 用法，不是本项目的视觉决定 |
| 图标 path 数据 | 不动 | 生成物（"不要手改 path 本身"已写进各文件头） |
| 六个图标对象合并成一个 | 不做 | 实测各被 2~13 个文件引用（DshIcons 13、SettingIcons 7、SidebarIcons 4、ToolIcons 3、DockIcons 2、MenuIcons 2）；合并 = 把 61 个图标塞进一个对象 + 改所有引用点的 import，那是把"按用途分层"当成了重复 |
| `AppRoot`（366）/ `DshComposer`（326）/ `TerminalPanel`（322）再拆函数 | 不做 | 只读体检（R35）：剩余熵**不是函数太长** —— ChatScreen 253 个代码行里 94 行是参数与转发、约 45 行是 remember/effect，已经没有任何 ≥20 行的逻辑块；TerminalPanel 是 14 个 `var by remember` + PTY/IME 时序，AppRoot 是抽屉与图层帧语义。能提纯的判定只剩 3~10 行，继续拆 = 搬家 |
| 把 30 个回调收成一个 `ChatActions` 值对象 | 不做（留触发条件） | 代价：新增一个 30 字段类型 + AppRoot/ChatScreen/DshComposer 三处改 + Compose 稳定性只能真机验 + **0 个新用例**；收益只是把同一份名单从两处写变成一处，而编译期保障反而更弱（一个字段一个默认值就退化成同样的坑）。触发条件：R35 的拆默认值之后仍出现漏接线类 bug，或真出现第二个调用场景 |
| `DshSpacing.Hairline`（0.5dp，只被脚本的「数值→名字」表提到） | 保留 | 它是**刻度**的一份，不是「没人用的常量」：`border(0.5.dp, …)` / `HorizontalDivider(thickness = 0.5.dp)` 共 5 处仍在写字面量（R8 只统一了 `padding(...)` 的实参）。删掉刻度只会让下一个人再发明一种写法 —— 已在 KDoc 里写明「刻度先留着」 |
| `DshPalette.dangerHover` / `accentHover`（只写不读） | 保留 | `DshPalette` 是 **dsh 设计令牌的镜像**（逐项取自 dsh-client-ui-theme），不是「只留用得到的」；同族的 `codeString` 也曾长期无人读，后来被 DocumentPreview 用上了。两处 KDoc 已注明「当前没有读取点」 |
| `Reasoning.defaultEffortFor` 的 `modelId` 参数 | 保留（已在 KDoc 注明） | dsh 那份 catalog 是 per-model 的，这个参数是将来「按模型取默认档」的钩子；删了要连改 4 个调用点与用例，而它现在不读是**写明了的**，不是暗坑 |
| `PromptAssembler.kt`（688 行）再分文件 | 不做 | 它没有巨型函数（最大 `androidEnvText` 86 行 / `buildParts` 68 行），体量来自提示词文本本身；而 `PromptAssembler.<成员>` 被 **13 个文件引用约 150 处（23 个成员）** —— 把文本常量搬出这个 object 就得改这 150 处限定名，是纯churn。菜单口径与该文件头注释一致：稳定性优先 |

| core/data 的 15 条 Room 迁移 | 不做（离线测不了） | 本机没有 Robolectric、也没有 `androidTest`（`testImplementation` 只有 junit），迁移必须真跑 Room 才能验。本轮做的是**把它们周围的判定**落地：迁移链 2→17 为什么必须完整、`MIGRATION_13_14` 为什么"最像死代码却绝不能删"（§8.1 已带行号写明） |
| `Balance.fetch` / `SessionTitleRunner` | 不做（网络 / 调模型） | 前者真 HTTP，后者要跑一次小模型；它们的**纯逻辑**部分已被入口函数覆盖（`fallbackSessionTitle` / `normalizeSessionTitle` / `sessionTitleFor` 共 19 个用例），剩下的就是 IO 编排 |
| `webSearchMaxUsesOf` 的 `coerceAtLeast(1)` / `webSearchProvider` 的未知 id 兜底 / `isCandidateBackend` | 不做 | 各 1~2 行、没有判定内容；同族的四处判定已在 R62 落地（`WebSearchSettings.kt` + 11 个用例），这三处继续留着只会制造"为了提纯而提纯"的函数 |

## 4. 建议的下一步（按"收益 ÷ 风险"排序）

0. ~~R8 内边距刻度~~ —— **已完成**（381 处纯改名，见 §1 / §2.1）。
0. ~~R9 设置页「功能」分节拆解~~ —— **已完成**（245 → 18 行，三张卡代码逐字未变，见 §1）。
0. ~~R10 拆 `TerminalPanel`（538 行）~~ —— **已完成**（4 个提交：输入行纯逻辑 / 会话层 /
   三块布局 / 输入行接线；真机冒烟过，见 §1）。
0. ~~R11 拆 `runTurnBody`（397 行）~~ —— **已完成**（判定提纯：8 个纯函数 + 14 用例，见 §1）。
   函数本身只从 397 降到 391 行，**这是有意的**：它不能再往下拆的前提是**没有集成测试** ——
   `LlmClient` / `ConversationRepository` 都是具体类，离线环境里又没有 Robolectric，
   硬拆只是把风险挪个地方。真要拆，先做可注入的接口 / 内存实现（那本身就是一步）。
0. ~~R12 状态提纯~~ —— **已完成**（会话页浮层的互斥与 dsh 的 dismissed → `ChatOverlays` +
   10 个用例，ChatScreen 净减 21 行，见 §1）。**方案里的 R0–R12 到此全部落地。**

**不在方案里、但排在后面的两件事**（都不是「熵」问题，是「可测性 / 可读性」问题）：

- ~~`ChatScreen` 的文件切缝~~ —— **已完成**（R13）；~~主函数的 state holder~~ —— **R14 也已完成**
  （五步，**主函数 936 → 480 行**，见 §2.2）；~~`DshComposer` 的两个弹层菜单~~ —— **R15**（495 → 326）；
  ~~`AppRoot.kt` 的抽屉~~ —— **R16**（1310 → 542）。
  ~~**ui 模块还剩四个 300+ 行的函数**~~ —— **R17–R21 已全部落地**（`ChatScreen` 480 → 397、
  `AppRoot` 391 → 366、`QuestionCard` 369 → 222、`Drawer` 306 → 214）。共性与 R12 / R14 一样
  （自持状态 + 装配混在一个 composable 里），手法也一样：判定能提纯的提纯、剩下的搬进 state holder。
  ui 层现在还剩三个 300+ 的函数：`ChatScreen` 397 / `DshComposer` 326 / `TerminalPanel` 322。
- ~~`runTurnBody`（391 行）的进一步拆分：先做可注入的接口 / 内存实现~~ —— **已完成（R23）**：
  三个窄端口（`TurnLlm` / `TurnStore` / `TurnSettings`）+ `TurnFakes` + 4 条黄金用例，449 → 453 全绿，
  而 `runTurnBody` **一行未动** —— 网先织好，**下一刀才拆它**（顺序：请求装配与落库收口 →
  一轮的流式采样与记账 → 工具派发循环 → 中断落库），拆完轮到 `ChatViewModel.startTurn`（307 行）。
  **R24 已落地**：请求装配 → `assembleRoundRequest`、assistant 这一步的落库与日志 →
  `addAssistantRow` / `appendAssistantStep`（三处写入点、三处日志点各收成一处形状），391 → 342 行。
  **R25 也落地（最大一刀）**：记账与流式采样 → `core/agent/TurnMeter.kt`（纯 Kotlin，7 个用例）
  + `AgentLoop.collectRound`，342 → 251 行；453 → 460 全绿。
  **R26 第三刀**：工具派发循环 → `runToolCalls` + `core/agent/ToolCallGuard.kt`，251 → 166 行
  （三刀合计 391 → 166，-58%）。`runTurnBody` 剩下的 166 行就是"这一步走到哪了"的编排本身。
  **R27 / R28 / R29 继续同一模块**：工具世界 → `TurnTools` 端口（工具轮第一次进 JVM 单测）；
  `buildMessages` 150 → 43 行（快照行 / assistant 组 / 工具结果 / 用户行 四个小函数）；
  请求装配整簇再搬成 `core/agent/RequestMessages.kt`（`AgentLoop.kt` 1021 → **762 行**，
  回到 800 行线以下，且这个类自己就能在纯 JVM 单测里打表）；
  `Attachments.kt` 538 行按关注点三分（附件表示与探测 242 / 图片进请求 169 / 工具结果里的图片 161）。
  **R31 换到 ui 侧第一刀**：`ChatViewModel.startTurn` 307 → **189 行** —— 先把 104 行「事件 → 界面状态」
  的纯判定搬成 `ui/TurnStreamState.kt`（无状态函数，**不是** state holder：`_state` 在 startTurn
  之外还有 6+ 个写者，holder 的副本会被写旧）+ 18 个纯 JVM 用例，再谈剩下的顺序编排。

## 5. 验证手段的盲区（必须承认的）

纯 JVM 单测加载不了 `ImageVector` / `PathParser`（离线缓存里没有 Robolectric），所以：

- **图标、布局、间距这类视觉产物，正确性最终靠真机目视**。脚本能保证的只有"结构等价"与"数值未变"；
- 因此每一次视觉改动，都要在提交信息里写清**具体看哪几处**，由用户在真机上核对；
- 已经固化的脚本护栏：`scripts/check-icon-structure.py`（图标结构）、`scripts/remove-unused-imports.py`、
  `scripts/find-unused-*.py` 四件套、PMD CPD（`--language kotlin`，本地 CLI）、
  `scripts/tokenize-padding.py` + `scripts/verify-padding.py`（R8 的改与验，验的一方逐实参比对）。
- ~~**R23 划出的新边界**：工具轮的集成测试要等能给 `ToolContext` 做替身~~ —— **R27 已补上**：
  工具世界收成窄端口 `TurnTools`（`workspace` + `run(name, argsJson, callId, execToken)`），
  假的实现就能让**工具轮**（请求带 schema / 日志 call-result 成对 / 工具行落库 / 死循环判据收尾）
  跑在纯 JVM 里，`AgentLoopToolRoundTest` 两条用例钉住它。
  **仍然测不到的**：真实工具实现内部（审批弹窗、PTC 子调用轨迹、图片与交付物编码）——
  那些要 Android / Termux，仍靠真机手测与各自的纯函数测试。
- **一条 R8 学到的**：改完的等价性要能用一条命令重放。R8 的验证脚本同时报了「0 处不一致」，
  和「改完之后还有哪些裸 dp 没动、为什么」—— 后者比前者更能防下一轮重复劳动。

## 6. 复现命令（审计自己也要可复现）

```bash
# 重复度（基线口径：minimum-tokens=100）
"D:/WSN2005/Android/jbr/bin/java" -cp "D:/WSN2005/Android1/tools/pmd-bin-7.28.0/lib/*" \
    net.sourceforge.pmd.cli.PmdCli cpd --minimum-tokens 100 --language kotlin \
    --dir D:/WSN2005/Android1/App/ADSH/app/src/main/java --format text
# 图标结构
python scripts/check-icon-structure.py
# R8 间距刻度：改（--apply 才落盘）与验（退出码 0 = 每个 padding 实参都没变）
python scripts/tokenize-padding.py
python scripts/verify-padding.py <旧目录> app/src/main/java
# 搬运式重构的等价性（R24–R33 每一刀都用的口径，现在是一行命令；--allow-* 可当门禁）
python scripts/check-move.py --old app/src/main/java/com/adsh/app/core/agent/AgentLoop.kt \
    --new app/src/main/java/com/adsh/app/core/agent/AgentLoop.kt \
          app/src/main/java/com/adsh/app/core/agent/RequestMessages.kt --rev HEAD~6
# 门禁
JAVA_HOME="D:\\WSN2005\\Android\\jbr" PATH="/d/WSN2005/Android/jbr/bin:$PATH" \
    ./gradlew.bat --offline :app:testDebugUnitTest
```

## 7. R23–R38 审查（做完再看一遍）

**方法**：门禁只证明「没坏」，证明不了「网有用」。所以这一轮做了三件事：
①逐条复算文档里声称的数字；②扫「引用了已搬走/改名符号」的过期注释与文档；③**变异测试**
（故意把 main 改坏，看哪张网会响）。三条都可复现，命令写在每节末尾。

**① 复算数字**（`python scripts/longest-functions.py` + 直接量函数体）：

| 文档里声称 | 实测 |
|---|---|
| `runTurnBody` 166 行 | 166 ✓ |
| `startTurn` 44 行 | 44 ✓ |
| `AgentLoop.kt` 762 行 | 762 ✓ |
| `RequestMessages.kt` 302 行 / `TurnMeter.kt` 152 行 | ✓ |
| CPD 1 组 / 12 行（阶段终值） | ✓ —— 这一轮重构**没有制造新重复** |
| `TurnStreamState.kt` 185 行 | **181**（R31 建时确实是 185，R36 复用后收缩；已改成不带数字的说法） |

**② 过期引用（都已修）**：

- HANDOFF 的硬规矩里还写着「抽屉自动收起有 30ms 起跑延迟（`DRAWER_SELECT_DELAY_MS`）」——
  那个机制在 R25（提交 `7677d14`）已经删掉，改成 `settleDrawer` 先等两帧；代码里 0 引用。
  **这是最危险的一类过期文档：它明确写着「别当冗余删掉」。** 现在这条规矩改成了当前口径
  （等两帧、同一个 job、别改回固定 delay）。
- `ContextTokens.kt` / `ContextTokensTest.kt` 指向 `[AgentLoop.buildMessages]` → `RequestMessages.build`（R29 搬的）；
- `TurnList.kt` 两处指向 `AgentLoop.persistTurnUsage` → `TurnMeter.persist`（R25 收的）。

**③ 变异测试（这一节的重点）**：

| 故意改坏 | 期望哪张网响 | 实际 |
|---|---|---|
| `TurnStreamState`：Stats 不再累加 | `TurnStreamStateTest` | **响** ✓ |
| `AgentLoop.collectRound`：`StreamReset` 不清 `reasoning` | 黄金用例 | **没响** ← 网眼 |
| `RequestMessages`：空工具输出不写 `(no output)` | 工具轮用例 | **没响** ← 网眼 |

两条网眼已补用例（`streamResetDiscardsTheAbandonedAttempt` / `anEmptyToolResultIsSentAsNoOutput`），
补完再跑同样的变异：**两条都响了**。结论：没有变异测试，「补了测试」这句话是没法证伪的。

**诚实账**：整个第二阶段 main 侧 +8822 / -6614 行、test 侧 +2511 行（含 R1–R21 的 ui 轮）。
R23–R38 这一段自己的账：`core/agent` 2807 → 3221 行（多出来的是端口、两个小类与它们的 KDoc）、
`startTurn` 307 → 44、`ChatScreen` 397 → 383、单测 449 → 498。**整体行数没有下降，降的是
「最长函数」与「零测试的路径」** —— 与方案开头写的验收口径一致（不看减了多少行，看行为不变量）。

## 8. 下一批候选模块的只读体检（R43 换模块时做的）

**方法**：换到设置页之后，用一个只读子代理按同一口径（`scripts/longest-functions.py` 的
blank_noncode + span_end）量了剩下三个「还没有测试的大户」，我抽查了其中 6 处行号与语义
（破坏性迁移、MIGRATION_13_14 的注释、`claimInjected` 在 `TurnFakes.kt` 里的手抄、
`renderBash` 的三个互斥标记、GBK 三判定、`SettingsStore.providers` 的 getter）——**逐条对得上**。
下面按「收益 ÷ 踩雷」排序，数字都带 `文件:行号`。

### 8.1 `core/data`（9 个手写文件 2520 行；`ModelThinkingLevels.kt` 是生成物，不参与）

最长函数都不是巨型（`addMessage` 38 / `Balance.fetch` 34 / `claimInjected` 30 / `searchSessions` 29），
但**零测试面最大**：`Database.kt` 1076 行、`SettingsStore.kt` 632 行的实例逻辑全部没有用例。

- **第一刀（建议）**：`Database.kt:888-893` 的 name 改写（`claimInjected` 的 when 四支）→ 同包纯函数 ——
  它现在是**两份手抄**（`TurnFakes.kt:208-214` 注释自称「逐字一致」），而它决定插话 / 任务通知
  落在会话的哪个位置（第 120 轮用户实测的「注入位置会跳」就是这条语义）。
- 第二刀：`snippetAround`(`Database.kt:692-699`，省略号与空白压缩) + LIKE 转义(665-668，
  **反斜杠必须最先替换**的顺序敏感) + `titleFor`(1052-1059) / `forkAt` 的 20 字符标题(985)。
- **雷**：`Database.kt:522` `fallbackToDestructiveMigration(dropAllTables = true)` —— 2→17 的链条
  完整才有意义；`MIGRATION_13_14`(442-461) 只为保住 13→15 路径，**最像死代码但绝不能删**
  （434-441 的注释已写明后果）。`readMessages`(796-801) 是唯一合法的整份读路径（绕过会闪退）。
  收件箱是内存 `ConcurrentHashMap`(850)，认领即 remove(882)：「整队出队 → 逐条写库」不能拆开。

### 8.2 `runtime/termux` 收官（R64–R67，实测）

**§8.2 原来点名的两刀都落地了**，另外把 `killOrder` 与 `/proc` 解析也一起收了（同一类东西）：

| 提交 | 内容 | 落点 |
|---|---|---|
| R64 | 安装器的五处判定：安装三岔（全量 / 只重链接 / 已装好）、second stage 补跑、`$PREFIX/bin` 一个槽位（第 115 轮那个 bug 的判据）、SYMLINKS.txt 只补缺、回退拷贝的目录项（第 74 轮） | `BootstrapPlan.kt` 155 行 + `BootstrapPlanTest` **22 例** |
| R65 | 子进程环境的清单装配（17 个事实 → `ShellFacts` 快照；含 PATH 前缀优先、路径不带尾斜杠、两个 data dir 分开给、围栏基线） | `TermuxEnv.kt` 189 行 + `TermuxEnvTest` **21 例** |
| R66 | 围栏三条「不挂」路 + 白名单规范化 + 五个变量的唯一装配点 / `killOrder` 叶子优先（含环闸）/ `ppidFromStat`（最后一个右括号） | `RuntimeDecisions.kt` 126 行 + `RuntimeDecisionsTest` **23 例**（R75 收紧入参后少了 1 行） |
| R67 | 熵减：搬进 `TermuxEnv.kt` 的函数体缩进 8 → 4（`check-move` 0/0 证明是纯缩进）；**两处手抄收成一份** —— termux-exec 候选库名（原先两个文件各写一份、靠注释声明「顺序一致」）与 `/data/data/<pkg>` 这条规则（原先三处手写） | `TermuxEnv.kt` + 2 例 |

**模块账（换模块时 → 现在）**：主源码 5 文件 2011 行 → **8 文件 2389 行**（多出来的是三个纯函数文件
与小节 KDoc：155 + 189 + 127）；`TermuxRuntime.kt` **863 → 745 行**、`BootstrapInstaller.kt`
**572 → 588 行**（多的是 `fullInstall` 的函数头与 KDoc）；这个 package 的测试从 **1 个文件 5 例**
变成 **4 个文件 71 例**，全库用例 636 → **702**。

**真机证据**（每条都在提交信息里）：R64 把 Relink 与 Installed 两条分支都走到了（`adb install -r`
换 nativeLibraryDir → `symlinks=0, executables=284`、dpkg 装的 `bin/xxhsum` 被 Skip 且 mtime 未变）；
R65 用同一套值在设备上跑 `adsh-env-check`（`system_linker_exec=true`、`/proc/self/exe → linker64`）；
R66 用 APK 里那个 shim 把四种变量形态逐个验了（完全权限放行、workspace-write 区外逐字带标记拒、
read-only 空表全拒、系统 CLI 仍可用）。

**剩下没做的（诚实）**：Full（全量重装）那条分支**没在真机上走过** —— 它要 `removeTree(prefix)`，
不该为了验证去删用户的前缀（判定本身有用例）；`EnvSelfCheck.kt` / `AdshShot.kt` 没动（带
`android.util.Log`，提纯只能出新文件，收益低）；`BootstrapInstaller` 里两处「失败日志限流」写法
仍不一致（`if (linked == 0)` vs `if (failed <= 3)`）—— 只影响日志文本，按「行为保持」没动。

**雷（原样保留，动手前必读）**：提纯不能改变调用顺序（解压→建链→removeTree→rename）；`INSTALLER_VERSION`
一动就强制重解压；路径字符串与 339 个 ELF 的编译期前缀逐字一致，任何「顺手规范化」都会打断
termux-exec；`MANIFEST.properties` 的 `nativeLibraryDir` 值里含 `==`（按 `=` 取值会截断）。

### 8.3 `core/tools` 收官（R68–R69，实测）

§8.3 点的**第一刀与那记「更便宜的备选」都落地了**：

| 提交 | 内容 | 落点 |
|---|---|---|
| R68 | bash 结果正文的渲染 + **它的读回端**：`bashStatusMarkers`（三种标记的互斥规则）、`renderBash`、`bashStatusMarker`；同时给原先零用例的 `renderJobDelta` / `renderPromoted` 补上用例 | `BashRender.kt` **73 行** + 18 例；`JobRenderTest` 12 例（R75 把 `renderBashBody` 合并回 `renderBash`） |
| R69 | 网页正文的类型与编码判定（`classifyContentType` / `parseCharset` / `sniffCharset`）+ 调用点那处「不认识的 charset 就报错」的三行判定 → `declaredCharset` 的 sealed 结果 | `WebEncoding.kt` 76 行 + 20 例 |

**模块账**：用例 702 → **752**；`Tools.kt` 里少了一个私有函数（代码行 1038 → 1061 是**两个文件合计**，
新文件自己 80 行）；`WebTools.kt` 1354 → 1365（同样含新文件 76 行）。
R68 还顺手收掉一处「同一个值写两遍」：正文里的 `(no output)` 改用 core/agent 的 `NO_OUTPUT`。

**剩下没做的（都写清了为什么）**：
- `readEnvelope` 的分页脚注、`previewLine`/`renderGrep`/`parseGrepMatches`、`ReadImageTool` 的
  扩展名与三个上限、`mapResponse`/`merge`、`TodoTool` 的校验 —— 这些**都不在模型的必需路径上**
  或改动会碰分页/检索的既有行为，收益 ÷ 风险不如上面两刀，等有真实回归再说。
- `ToolSdk.section` 是**系统提示词的基线**：动格式位会改既有会话的基线，属于「不能顺手改」那一类。

**雷（原样保留）**：bash 的**围栏语义写了三遍**（Tools.kt 的三处），是第 64 / 113 轮两次事故的产物，
合并时方向改反 = 只读预设下放行写权限；`WebFetchTest` 有两条**真联网**用例，新单测别混进去。

### 8.4 设置页那两刀：**R51 时就已经落地**（R70 复核，这一节原样留档）

复核结论（不是「做完了」，是「本来就做完了，这一节写晚了」）：§8.4 点名的两处早已是
`ui/SettingsModels.kt` 里的纯函数，且都有用例：

| §8.4 说的 | 实际落点 | 用例 |
|---|---|---|
| 服务端清单 → 候选（已有的不列） | `fetchCandidates(fetchedIds, existing)` | `SettingsModelsTest`（8 例段落） |
| 过滤（去空格 + 大小写不敏感） | `candidateFilter(candidates, query)` | 同上 |
| 全选那一枚按钮的文案判据 | `allVisiblePicked(selected, visible)` | 4 例（含「可见为空时不算全选」） |
| 全选 / 取消全选 | `toggleAllVisible`（**并集**，不是替换 —— 旧写法会悄悄丢掉先勾的） | 同上 |
| 采纳（按候选顺序 + 补内置档案） | `adoptedModels(candidates, selected)` | 3 例（含图片能力靠 `knownModel` 补齐） |
| 行内改一行 / 删一行 / 展开集合跟着挪 | `modelEdited` / `modelsRemoved` / `reindexOnRemove` | R51 的 6 例（越界是空操作、删行后展开前移） |

**唯一没提纯的是「加行」**：`SettingsModelsSection.kt:512` 的 `onModels(models + ModelDef(id = ""))`
—— 一行、没有判定内容，按 §3 的口径属于「不捡」。


### 8.5 `core/data` 收官（R56–R62，实测）

§8.1 点的两刀 + 另外几处判定都落地了，落点与新增用例：

| 提交 | 内容 | 落点 |
|---|---|---|
| R56 | `claimInjected` 的 name 改写（原先**两份手抄**） | `InjectedNaming.kt` + 10 例 |
| R57 | LIKE 转义（**顺序敏感**）+ 命中片段 | `SessionSearch.kt` + 11 例 |
| R58 | 落库标题判定 + 「新会话」常量（4 处字面量收成一个） | `SessionTitle.kt` + 7 例 |
| R59 | 老扁平配置 → 提供方清单的迁移（**代价最高**的一处） | `Providers.kt` + 8 例 |
| R60 | 图片能力三岔（手动 → 行上 → 未知默认开） | `Providers.kt` + 6 例 |
| R61 | **按 dsh 修 R59 挖到的迁移 bug** + 当前提供方兜底提纯 | `Providers.kt`（`currentProviderIdOf`） |
| R62 | 网页搜索的三个存储值判定 | `WebSearchSettings.kt` + 11 例 |

模块账（v0.2.0-phase2 标签 → 现在）：`Database.kt` 1077 → **1055**、`SettingsStore.kt` 633 → **600**、
`Providers.kt` 236 → **360**（多出来的是四段纯函数与它们的 KDoc）、三个新文件
（InjectedNaming 35 / SessionSearch 46 / WebSearchSettings 74）；core/data 的测试文件 4 → **8** 个，
全库用例 567 → **624**。

剩下的是 §3 新加的三条「不做」（15 条 Room 迁移离线测不了、两处网络/调模型的编排、
三处 1~2 行没有判定内容的东西）。

### 8.6 后面还有哪些模块（R70 只读体检，用户问「应该差不多了吧」）

§8.1 / 8.2 / 8.3 三个模块与 §8.4 那两刀都收掉之后，把「还没有测试的大户」重新量了一遍
（口径：主源码行数 / 该 package 的用例数，脚本见 §6）：

| package | 主源码 | 该包用例 | 结论 |
|---|---|---|---|
| `core/jobs` | 523 行（1 文件） | **0 → 45** | **R71–R73 收官**（见下）：整个文件**没有一处 `android.` 引用**（纯 Kotlin），环保留窗口与游标、gap / lossy、owner 作用域、wait/kill 结算、roster 通知这些判定现在都有用例 |
| `ui/theme` | 359 行 | 0 | 设计常量，不捡（改错只有肉眼能发现，没有可断言的行为） |
| `runtime/phone` | 205 行 | 0 | 协议层（`core/phone`）已有 12 例；剩下的是 Android 侧轮询与用前台身份做的动作，离线测不了 |
| `core/workspace` | 89 行 | 0 | 只有 `isForbidden` 那几行是判定，其余要 `Context` / prefs；收益小 |
| `ui/ChatViewModel.kt` | 1718 行 | ui 共 267 例 | 纯 JVM 这块**已经到顶**：状态判定都提纯成 `TurnStreamState` / `TurnList` / `LiveTurn` 等了，剩下的编排要 Robolectric 或真机 |
| `ui/Composer.kt` 1409 / `TurnRail.kt` 1243 / `Drawer.kt` 858 / `TurnList.kt` 783 | | 同上 | 同上：Compose 组件的装配，离线没有可断言的东西 |
| `core/ptc/QuickJsRuntime.kt` | 766 行 | 20（同包） | `run` 247 行，但跑的是 native QuickJS；能提纯的 `ProgramDiagnostics` / `SubCallOrder` 已经提了 |

**一句话**：`core/jobs` 之后，剩下的熵要么在 Compose 装配里（离线测不了）、要么是 §3 已经判定
「不做」的那些（15 条 Room 迁移、两处网络/调模型的编排、`ToolSdk.section` 这种基线、1~2 行的碎片）。
换句话说：**「按模块提纯」这条线最多再走一个 `core/jobs`**，之后要继续只能换手段
（Robolectric / androidTest / 真机脚本），那是另一件事。

**`core/jobs` 收官账（R71–R73，实测）**：

| 提交 | 内容 | 落点 |
|---|---|---|
| R71 | 有界输出环 `OutputRing` / `RingChunk` 搬出 `Jobs`，配套的 `utf8Tail` 一起走；`settle` 里那行保留上限提纯成 `settleRetainCap(total, modelCursor)` | `OutputRing.kt` 109 行 + 16 例 |
| R72 | owner 栅栏 `visibleTo`（原先在 `list` 与 `expect` 里**各写一遍**）与 `mergedDetail`（killed 的终局并上 kill 原因） | `JobRules.kt` 34 行 + 11 例 |
| R73 | **没改生产代码**：注册表 18 条行为用例（身份与准入 / 栅栏 / 状态机 / 通知抑制 / 游标 / teardown），并用 9 处变异反向验证这张网 | `JobsRegistryTest.kt` |

模块账：`Jobs.kt` **523 → 451 行**、新增两个纯函数文件（109 + 34）与三个测试文件；
这个 package 的用例 **0 → 45**（16 + 11 + 18），全库 **752 → 797**。

**这条线的结论**：§8.1（core/data）/ §8.2（runtime/termux）/ §8.3（core/tools）三个模块、
§8.4（R51 时就做完）、`core/jobs` —— **按模块提纯到此为止**。上面那张表里剩下的每一个
（`ui/theme`、`runtime/phone`、`core/workspace`、ui 的四个大文件与 `ChatViewModel`、
`QuickJsRuntime`）都写明了原因：要么没有可断言的行为，要么离线测不了。
要继续只能换手段（Robolectric / androidTest / 真机脚本），那是另一件事。

## 9. 深度死代码清理（R76，实测）

R63 之后又动过 13 个源文件（R64–R75），所以这一轮把「还有没有死代码」按六个现成角度重扫了一遍：

| 角度 | 工具 | 结果 |
|---|---|---|
| 未用声明（名字在全语料只出现一次） | `scripts/find-unused-declarations.py` | **0 候选** |
| 文件内没人用的 private 声明 | `scripts/find-file-local-dead-code.py` | **0 候选** |
| 未用 import | `scripts/remove-unused-imports.py` | **0 行** |
| 未用资源 / 版本目录条目 | `scripts/find-unused-resources.py` / `find-unused-catalog-entries.py` | **0 / 0** |

> 第 182 轮（R93）起上面那四个 Kotlin/资源脚本**合并重写**了：判据从「名字在全语料出现几次」
> 换成词法级引用 + 作用域，工具是 `scripts/kt_source.py` + `scripts/deadcode.py`
> （自测 `deadcode-selftest.py`、例外表 `deadcode-baseline.json`），
> 资源那半是重写过的 `find-unused-resources.py`；语义级的交叉验证交给 Android Lint
> （`./gradlew --offline :app:lintDebug`）。本表记录的是 R76 当时的结果，工具名保留原样。
| C 静态符号 | `scripts/find-unused-c-symbols.py` | **0** |
| 编译器**全部**警告（`--rerun-tasks`） | Gradle | 只有 2 条 deprecated，且代码里都带着「有意的，别修」的注释（`WEBP` 在 R 之前只有旧常量；`ViewCompat.getWindowInsetsController` 是 minSdk 26 上唯一的入口） |

再加三个自建角度（脚本是一次性的，跑完不留）：

- **只被测试引用、生产零调用的顶层函数**：全库只有 2 个 —— `ui/TurnRail.kt` 的 `toolRowSummary`
  （KDoc 就写着「单测从这里进」）与 `ui/WhaleTail.kt` 的 `whaleFrame`（写着「测试用：第 i 个关键帧
  的坐标」）。两处都是**有意的测试缝**，不是死代码。
- **清单死声明**：`INTERNET` / `ACCESS_NETWORK_STATE`（`phone info` 读网络状态）/
  `MANAGE_EXTERNAL_STORAGE`（工作区的真实路径）与 application / activity 逐条都有真实用处。
- **孤儿文件**（顶层声明在别的文件里零引用）：`AdshApp.kt` 由清单的 `android:name` 引用；
  `SessionTitleRunner.kt` 由 `ChatViewModel` 调用。后者是我第一版扫描器的正则漏了
  `internal suspend fun` 这种修饰符组合造成的**误报** —— 记一笔：**扫描器的正则要覆盖修饰符组合，
  否则会把「有调用者的文件」报成孤儿**（这条比结果本身值钱）。
- **测试侧死声明**：62 个候选**全是 JUnit 反射实例化的测试类**（`class XxxTest` 本来就不会被
  别处按名字引用），逐个看过，没有真死代码。

**结论：这一轮没有可删的死代码。** 与 R63 那次不同（那次删掉了三处清单声明：
`ACCESS_NETWORK_STATE`、整段 `<queries>`、`FileProvider` + `file_paths.xml`）—— 这一轮的价值是
**把「扫过什么、结论是什么」写下来**，免得下次再扫一遍；顺带记下 R75 自己删掉的那三处
「只有用例才走得到的分支与层」。

```bash
python scripts/longest-functions.py                     # 最长函数与行数
python scripts/check-move.py --old <文件> --new <文件…>  # 搬运等价性
# 变异测试：把上面表里的三处各改坏一次，跑 :app:testDebugUnitTest，看哪条失败
```
