# 模块依赖图（包依赖 + 包到模块的映射）

由 python scripts/check-module-graph.py --write 生成；--check 比对新鲜度（CI 用）。

口径：各模块 src/main 下的 .kt，剥掉注释与字符串后逐行找 com.adsh.app.*（内联全限定名与 import 同等看待），目标包按「已声明的包名」最长前缀解析；core.* 按包的第一段归并成单元。包名不再能推出模块名 —— 包 -> 模块是脚本里的显式表（见 §六）。词法器直接复用 scripts/check-package-cycles.py。

## 一、九个核心包的体量

| 单元 | 文件 | 行 | 模块 |
|---|---|---|---|
| core.agent | 21 | 3101 | :core:agent |
| core.data | 17 | 4477 | :core:data |
| core.jobs | 3 | 705 | :core:tools |
| core.llm | 6 | 902 | :core:data |
| core.phone | 0 | 0 | None |
| core.ptc | 0 | 0 | None |
| core.session | 1 | 140 | :core:data |
| core.tools | 16 | 6357 | :core:tools |
| core.workspace | 1 | 90 | :core:tools |

## 二、core.* 出边（谁依赖谁）

| 来源单元 | 目标 | 次数 | 样本（文件:行） |
|---|---|---|---|
| core.agent | core.data | 19 | core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:3<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:4<br>core/agent/src/main/java/com/adsh/app/core/agent/ContextTokens.kt:3 |
| core.agent | core.llm | 27 | core/agent/src/main/java/com/adsh/app/core/agent/AgentHooks.kt:3<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:5<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:6 |
| core.agent | core.session | 6 | core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:8<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:9<br>core/agent/src/main/java/com/adsh/app/core/agent/ToolRound.kt:5 |
| core.agent | core.tools | 6 | core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:10<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:11<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:12 |
| core.agent | core.workspace | 6 | core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:82<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:166<br>core/agent/src/main/java/com/adsh/app/core/agent/PromptAssembler.kt:4 |
| core.agent | seam.exec | 1 | core/agent/src/main/java/com/adsh/app/core/agent/TurnContext.kt:11 |
| core.agent | seam.llm | 1 | core/agent/src/main/java/com/adsh/app/core/agent/TurnDecisions.kt:4 |
| core.agent | seam.model | 7 | core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:13<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:14<br>core/agent/src/main/java/com/adsh/app/core/agent/AgentLoop.kt:15 |
| core.agent | seam.ptc | 2 | core/agent/src/main/java/com/adsh/app/core/agent/SubCallTrimmer.kt:3<br>core/agent/src/main/java/com/adsh/app/core/agent/ToolRound.kt:7 |
| core.agent | seam.tool | 9 | core/agent/src/main/java/com/adsh/app/core/agent/RequestMessages.kt:10<br>core/agent/src/main/java/com/adsh/app/core/agent/RequestMessages.kt:11<br>core/agent/src/main/java/com/adsh/app/core/agent/RequestMessages.kt:219 |
| core.data | core.llm | 16 | core/data/src/main/kotlin/com/adsh/app/core/data/Database.kt:771<br>core/data/src/main/kotlin/com/adsh/app/core/data/Database.kt:796<br>core/data/src/main/kotlin/com/adsh/app/core/data/Database.kt:800 |
| core.data | seam.exec | 1 | core/data/src/main/kotlin/com/adsh/app/core/data/SettingsStore.kt:7 |
| core.data | seam.llm | 9 | core/data/src/main/kotlin/com/adsh/app/core/data/Balance.kt:12<br>core/data/src/main/kotlin/com/adsh/app/core/data/Balance.kt:13<br>core/data/src/main/kotlin/com/adsh/app/core/data/Providers.kt:4 |
| core.data | seam.model | 5 | core/data/src/main/kotlin/com/adsh/app/core/data/AttachmentStore.kt:8<br>core/data/src/main/kotlin/com/adsh/app/core/data/AttachmentStore.kt:9<br>core/data/src/main/kotlin/com/adsh/app/core/data/Database.kt:944 |
| core.jobs | seam.exec | 1 | core/tools/src/main/kotlin/com/adsh/app/core/jobs/Jobs.kt:3 |
| core.llm | core.session | 1 | core/data/src/main/kotlin/com/adsh/app/core/llm/ChatModels.kt:223 |
| core.llm | seam.llm | 3 | core/data/src/main/kotlin/com/adsh/app/core/llm/ChatModels.kt:9<br>core/data/src/main/kotlin/com/adsh/app/core/llm/ChatModels.kt:10<br>core/data/src/main/kotlin/com/adsh/app/core/llm/ChatModels.kt:11 |
| core.session | seam.ptc | 1 | core/data/src/main/kotlin/com/adsh/app/core/session/SessionLog.kt:3 |
| core.tools | core.data | 6 | core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:56<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:65<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:70 |
| core.tools | core.jobs | 2 | core/tools/src/main/kotlin/com/adsh/app/core/tools/JobTools.kt:3<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:8 |
| core.tools | core.workspace | 1 | core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:10 |
| core.tools | seam.exec | 10 | core/tools/src/main/kotlin/com/adsh/app/core/tools/Approval.kt:5<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/Approval.kt:8<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/BashRender.kt:4 |
| core.tools | seam.model | 4 | core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:3<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:5<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/Tools.kt:1426 |
| core.tools | seam.ptc | 10 | core/tools/src/main/kotlin/com/adsh/app/core/tools/PtcToolRunner.kt:3<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/PtcToolRunner.kt:4<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/PtcToolRunner.kt:5 |
| core.tools | seam.tool | 17 | core/tools/src/main/kotlin/com/adsh/app/core/tools/Approval.kt:3<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/BashRender.kt:3<br>core/tools/src/main/kotlin/com/adsh/app/core/tools/ExtraTools.kt:11 |
| core.workspace | seam.exec | 1 | core/tools/src/main/kotlin/com/adsh/app/core/workspace/WorkspaceManager.kt:4 |

## 三、core.* 入边（谁依赖它；core→core 的入边把上一节反过来读）

| 目标单元 | 来源 | 次数 | 样本（文件:行） |
|---|---|---|---|
| core.agent | com.adsh.app | 4 | app/src/main/java/com/adsh/app/AppContainer.kt:6<br>app/src/main/java/com/adsh/app/AppContainer.kt:7<br>app/src/main/java/com/adsh/app/AppContainer.kt:8 |
| core.data | com.adsh.app | 3 | app/src/main/java/com/adsh/app/AppContainer.kt:10<br>app/src/main/java/com/adsh/app/AppContainer.kt:11<br>app/src/main/java/com/adsh/app/AppContainer.kt:12 |
| core.llm | com.adsh.app | 2 | app/src/main/java/com/adsh/app/AppContainer.kt:13<br>app/src/main/java/com/adsh/app/AppContainer.kt:14 |
| core.workspace | com.adsh.app | 1 | app/src/main/java/com/adsh/app/AppContainer.kt:15 |
| core.data | host | 1 | app/src/main/java/com/adsh/app/host/ToolingHost.kt:4 |
| core.tools | host | 1 | app/src/main/java/com/adsh/app/host/ToolingHost.kt:5 |
| core.workspace | host | 1 | app/src/main/java/com/adsh/app/host/ToolingHost.kt:6 |
| core.data | ui | 15 | app/src/main/java/com/adsh/app/ui/AppRoot.kt:55<br>app/src/main/java/com/adsh/app/ui/Drawer.kt:59<br>app/src/main/java/com/adsh/app/ui/Drawer.kt:60 |
| core.llm | ui | 1 | app/src/main/java/com/adsh/app/ui/SettingsModelsSection.kt:51 |
| core.agent | ui.chat | 11 | feature/chat/src/main/java/com/adsh/app/ui/chat/ChatUiState.kt:162<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:6<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:7 |
| core.data | ui.chat | 34 | feature/chat/src/main/java/com/adsh/app/ui/chat/AttachmentImport.kt:5<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatDecisions.kt:3<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatDecisions.kt:108 |
| core.jobs | ui.chat | 7 | feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:524<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:622<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:627 |
| core.llm | ui.chat | 13 | feature/chat/src/main/java/com/adsh/app/ui/chat/ChatUiState.kt:5<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:15<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:16 |
| core.session | ui.chat | 12 | feature/chat/src/main/java/com/adsh/app/ui/chat/ChatUiState.kt:67<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:1049<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:1142 |
| core.tools | ui.chat | 13 | feature/chat/src/main/java/com/adsh/app/ui/chat/ChatBars.kt:47<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatBars.kt:48<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatScreen.kt:49 |
| core.workspace | ui.chat | 5 | feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:18<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:332<br>feature/chat/src/main/java/com/adsh/app/ui/chat/ChatViewModel.kt:1253 |

事实：core.* 对外的出边只到 seam.exec、seam.llm、seam.model、seam.ptc、seam.tool（没有 runtime.* / ui.* / island.* 的边）。

## 四、切割顺序（依赖在前）

规则：边 A → B 表示 A 依赖 B，所以 B 必须先就绪。同一批里的单元互不依赖，顺序可任意。

第 1 批：:core:tools（core.jobs，已建（:core:tools 已注册））、None（core.phone，无模块映射）、None（core.ptc，无模块映射）、:core:data（core.session，已建（:core:data 已注册））、:core:tools（core.workspace，已建（:core:tools 已注册））
第 2 批：:core:data（core.llm，已建（:core:data 已注册））
第 3 批：:core:data（core.data，已建（:core:data 已注册））
第 4 批：:core:tools（core.tools，已建（:core:tools 已注册））
第 5 批：:core:agent（core.agent，已建（:core:agent 已注册））

## 五、环（强连通分量，size > 1）

- 无：core.* 之间是 DAG。

## 六、包到模块的映射（脚本里的显式表）

包名前缀匹配、最长前缀优先 —— 一条包依赖落到哪个模块只看这张表。

| 包前缀 | 模块 |
|---|---|
| core.agent | :core:agent |
| core.data | :core:data |
| core.jobs | :core:tools |
| core.llm | :core:data |
| core.session | :core:data |
| core.tools | :core:tools |
| core.workspace | :core:tools |
| platform.ptc | :platform:termux |
| runtime.termux | :platform:termux |
| seam | :seam |
| ui.blocks | :ui:blocks |
| ui.chat | :feature:chat |
| ui.kit | :ui:blocks |
| ui.theme | :ui:blocks |

| 单元 | 模块 | 状态 | 三方库 import 前缀 | Room/KSP |
|---|---|---|---|---|
| core.agent | :core:agent | 已建（:core:agent 已注册） | kotlinx.coroutines<br>kotlinx.serialization | 否 |
| core.data | :core:data | 已建（:core:data 已注册） | kotlinx.serialization<br>android.content<br>android.graphics<br>android.media<br>android.os<br>androidx.room | 是 |
| core.jobs | :core:tools | 已建（:core:tools 已注册） | kotlinx.coroutines | 否 |
| core.llm | :core:data | 已建（:core:data 已注册） | kotlinx.coroutines<br>kotlinx.serialization<br>android.util | 否 |
| core.phone | None | 无模块映射 | （无三方依赖） | 否 |
| core.ptc | None | 无模块映射 | （无三方依赖） | 否 |
| core.session | :core:data | 已建（:core:data 已注册） | （无三方依赖） | 否 |
| core.tools | :core:tools | 已建（:core:tools 已注册） | kotlinx.coroutines<br>kotlinx.serialization | 否 |
| core.workspace | :core:tools | 已建（:core:tools 已注册） | android.content | 否 |

## 七、settings.gradle.kts 里已注册的模块

- :app
- :core:agent
- :core:data
- :core:tools
- :feature:chat
- :platform:termux
- :seam
- :ui:blocks

core.* 的九个包落在这些模块里：:core:agent、:core:data、:core:tools、None

## 八、非 :app 模块引用根包符号

规则：:seam / :ui:blocks / :feature:chat / :core:* 里不许出现 com.adsh.app.{BuildConfig,R,MainActivity,AdshApp}（它们是 :app 的入口与生成代码，被下方模块引用就是反向依赖）；:app 自己引用不算。命中即 --strict 失败。

| 模块 | 符号 | 文件:行 | 那行 |
|---|---|---|---|
| — | — | — | 0 处 |

