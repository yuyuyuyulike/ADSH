# 路线图

只写当前阶段与验收判据。历史在 CHANGELOG.md。行数上限 60。

## 目标

不加新功能，把地基打牢：结构清晰、边界由编译器守、无冗余、可验证。

## 阶段

| 阶段 | 内容 | 验收判据 | 状态 |
|---|---|---|---|
| 0 | 立规矩：`AGENTS.md` + `docs/{ARCHITECTURE,TECH-STACK,MODULES,ROADMAP}.md` + 文档预算门禁 | 六份文档在行数上限内；`check-doc-budget.py` 通过 | ✅ 完成 |
| 1a | 绿色基线 | 868 用例全过，作为重构前的对照 | ✅ 完成 |
| 1b | 工具链：build-logic 约定插件、Lint 进门禁、Robolectric+Compose ui-test+Turbine、包循环门禁脚本、Spotless 卫生级门禁 | `:app:lint` **0 error**；新界面测试能跑；门禁脚本可用 | ✅ 完成 |
| 2 | **解耦**：两个强连通分量归零 —— 界面环（island↔ui↔ui.panels）+ 核心环 8 条回边下沉 `seam.*` 并立起 `PtcRunner` 端口 | 重扫 **0 环**；**871 用例**全绿 | ✅ 完成 |
| 3a | 5 个 seam 模块（llm/exec 纯 JVM，model/tool/ptc 为 android library；后合并为 `:seam`，见阶段 8） | 边界由编译器守（已用「故意违规」探针验证）；871 用例全绿 | ✅ 完成 |
| 3b | `:ui:kit`（`ui.kit` + `ui.theme`，包名不变、零 import 改动）+ `ThemeMode` 下沉 `:seam` 的 `seam.model` + Spotless 收进约定插件 | `ui.theme → core.data` 归零；根 `spotlessCheck` 覆盖全部模块 | ✅ 完成 |
| 3c | **A**：core→Termux 实现依赖反转成 `:seam` 的 `seam.exec` `Shell` 端口。**B**：`runtime/termux` 抽成 `:platform:termux`（10 → 11 个工程），提供者从此只认 `:seam` | `core.*` 下对 `runtime.termux` 的代码引用 = 0；`runtime.termux` 对 `core.*/ui.*/island.*` 的引用 = 0（分层门禁盯着）；APK 两个原生库与 execLibs 齐全 | ✅ 完成 |
| 4 | 清注释：「第 N 轮」按 `AGENTS.md` 规矩重写（只留约束，历史进 CHANGELOG）；范围含 `.c/.h/.js/.pro/.sh` 与中文数字 / 区间写法 | `check-comment-history.py --strict` = 0 处；`find-unused-imports.py` / `deadcode.py` 干净 | ✅ 完成 |
| 5 | 真机修复：会话宿主只许一份（`singleTask`）+ 返回键只退到后台（`moveTaskToBack`） | Task `sz` 恒为 1；`MainActivityLaunchModeTest` 过；按返回键后进程号与 ActivityRecord 不变 | ✅ 完成 |
| 6 | **组合根**：`:app` 的 `AppContainer` 成为唯一创建对象的地方，`ChatViewModel` 改构造注入（`docs/ARCHITECTURE.md` §五） | 行为零改动（统一门禁 + 装机冒烟）；VM 不再自己 `new` 数据库 / 设置 / LLM / Termux / 工作区 / AgentLoop | ✅ 完成 |
| 7 | 后续批次：`:core:*` 切成模块（3c-B 的 `:platform:termux` 随之才有强制力）；顺序由 `docs/module-graph.md` 的拓扑序定 | 每步门禁全绿、行为零改动；模块图 `--check` 通过 | ✅ 完成（九个核心包全部成模块、用例总数全程 95 类 / 872；后合并见阶段 8） |
| 8 | **模块合并**：16 → 10 个 Gradle 工程（5 个 `:seam:*` → `:seam`；`:core:jobs`+`:core:phone` → `:core:base`；`:core:session` → `:core:llm`） | 每批 `:app:assembleDebug` + 全模块 `testDebugUnitTest`；各模块用例数与合并前逐项一致；模块图 / 包循环 / 文档预算门禁全绿 | ✅ 完成 |
| 9 | **真机 harness 回归**：用户报告 13 条，逐条与 dsh 比对（6 条判为 dsh 既有行为/有意偏离，不修）；修 P0 杀树广播 SIGTERM / 大文件回显 / read 行口径 / 文案 / value.path / spill 回收 / PTC 绑定失败 | 每项有回归用例；真机一轮 agent 程序 0 次 worker-exit；统一门禁全绿 | ✅ 完成 |
| 10 | **拆分 + 归并**：`:app` 34k 行拆出 `:ui:blocks`（渲染基础）与 `:feature:chat`（对话界面）；再把 4 个迷你 core 模块并掉（`:core:jobs`→`:core:tools`、`:core:llm`→`:core:data`、`:core:ptc`→`:platform:termux`、`:core:phone`→`:seam`） | **8 个工程**（功能优先，行数只当参考：max 15.9k、契约层 1.8k）；用例仍 99 类 / 889 例 0 失败；`:app` 单文件增量编译 7.1s → 4.2s、`:feature:chat` 3.7s | ✅ 完成 |

## 顺序约束

- **2 必须在 3 之前**：Gradle 不允许模块间循环依赖 —— 已完成，前置解除。
- **不做全仓格式化**：ktfmt 实测会给中文注释插 ASCII 空格、给 `when` 表达式多加缩进（证据写在 `app/build.gradle.kts` 的 spotless 块注释里）；Spotless 只做行尾空白 + 末尾换行。
- **Gradle 同一时刻只跑一个构建**（见 `AGENTS.md`）。
- 每个阶段结束时跑**统一门禁**（清单在 `HANDOFF.md`），并在 `CHANGELOG.md` 记一行结论。
