# 模块化方案

这份文件回答「现在怎么切、判据是什么、靠什么守」。分层与边界规则看 [ARCHITECTURE.md](ARCHITECTURE.md)，阶段与验收看 [ROADMAP.md](ROADMAP.md)。行数上限 120。

## 一、现状：8 个 Gradle 工程

行数是 main + test 的 `.kt` 行数（2026-10 实测，最近一次核对在模块梳理轮后）：

| 模块 | 内容（包） | 行数 | 插件 |
|---|---|---|---|
| `:app` | 壳 / 导航 / 设置 / 终端 / 灵动岛 / `runtime.phone` / 组合根 | 9.9k | application + Compose |
| `:feature:chat` | `ui.chat`：对话界面（会话宿主 / 会话屏 / 输入区 / 轮次渲染 / 面板） | 17.8k | android library + Compose |
| `:ui:blocks` | `ui.blocks` + `ui.kit` / `ui.theme`：调色板 / 主题 / 间距 / 图标 / Markdown / LaTeX / 代码块 / 工具结果块 / 文件预览 | 12.2k | android library + Compose |
| `:core:agent` | `core.agent`：Agent 主循环与轮次装配 | 5.0k | android library |
| `:core:data` | `core.data` + `core.llm` + `core.session`：持久化（Room/KSP，`schemas/` 在模块里）+ LLM 客户端 + 会话事件日志 | 6.0k | android library + serialization |
| `:core:tools` | `core.tools`（含 `core.workspace`）+ `core.jobs`：工具运行时 + 工作区 + 后台任务 | 10.5k | android library |
| `:platform:termux` | `runtime.termux` + `platform.ptc` + `cpp/`：Termux 进程 / PTY / 原生 shim / bootstrap 安装 + `:ptc` 独立进程与 QuickJS 运行时 | 5.0k | android library（含 CMake） |
| `:seam` | `seam.{llm,exec,model,tool,ptc,phone}`：纯契约与数据类型 | 1.9k | android library + serialization |

**判据：功能优先，行数只当参考**（2026-10 实测 8 工程：最大 `:feature:chat` main+test 17.8k；非契约模块里 `:core:agent` 5.0k / `:platform:termux` 5.0k 最小）。工程数也不设硬性目标（用户口径：8–12 之间都行，**别为凑行数硬拆/硬并**）。唯一的结构性例外是 `:seam` —— 它是「服务定义」层（接口 + 数据类型），**天生小**，但必须独立：五个能力缝 + 电话线协议的契约面只有放在这里，才能规定 provider「只许依赖它」。`:feature:chat` 不再往下切（会话宿主 / 轮次渲染 / 输入区拆开会让「同一屏内的重组协调」跨模块，判过收益不抵成本）。

## 二、从 16 到 8：怎么并的

只做两件事：**没有第二个消费者的包并回消费者**；**同一个契约面的包并回同一模块**。

| 原模块 | 并入 | 依据 |
|---|---|---|
| `:seam:{llm,exec,model,tool,ptc}` | `:seam` | 同一个契约面（服务定义） |
| `:core:phone` | `:seam` 的 `seam.phone` | 脚本 ↔ App 的线协议，属缝层（包名改） |
| `:core:jobs` | `:core:tools` | 唯一消费者是工具层与组合根 |
| `:core:workspace` | `:core:tools` | 89 行，只有 `:app` 构造它 |
| `:core:llm`（含 `core.session`） | `:core:data` | LLM 客户端与会话仓储是同一份公开面（`LlmClient` / `ChatEvent` 只经 `:core:data` 出去） |
| `:core:ptc` | `:platform:termux` | 同属平台实现（进程 / 原生 / QuickJS），都只认 `:seam` 端口（包名改） |
| `:app` 34k 行 | `:ui:blocks` + `:feature:chat` | 渲染基础与功能界面各自成层；局部增量编译从「全仓一刀」变成按模块 |

两处包名随模块改了，别按旧名找：`com.adsh.app.seam.phone`、`com.adsh.app.platform.ptc`。

## 三、三个判据（仍在用）

1. **插件看依赖闭包**，不是看自己有没有 android import：闭包里只要有一个 Android library，纯 JVM 消费者就选不出变体 —— 8 个工程全是 Android library。
2. **api 还是 implementation**：类型出现在公开签名上才 `api`，默认 `implementation` 挡住传递依赖。
3. **包 → 模块是显式表**：包名推不出模块名，映射在 `scripts/check-module-graph.py` 的 `PACKAGE_MODULES`（最长前缀优先）。新包在那里补一行，别回退到按名字猜。

## 四、靠什么守住 + 已知边界

**门禁**（全机器可查）：
- 模块边界首先是**构建脚本本身**：`:core:agent` 的 build 文件里没有 `:ui:blocks`，想 import 就编不过。
- `scripts/check-package-cycles.py`：包级 Tarjan + 分层规则（`scripts/layering-rules.json`）。
- `scripts/check-module-graph.py`：`--write` 生成 `docs/module-graph.md`、`--check` 新鲜度、`--strict` 根包符号、`--selftest` 反例。
- **`:app:assembleDebug` 必须进门禁**：变体匹配 / 打包类回归只有组装才看得见（踩过 `side` 变体 `No matching variant`，测试/lint/spotless 全绿）。
- **库不许读自己的 `BuildConfig`**：宿主决策由组合根注入（`side` 变体对库的 matchingFallbacks 是 debug）。

**已知边界**（都判过「现在不做」）：
1. `OutputRing` 留在 `core.jobs`：它是 `Jobs` 的实现细节（internal），没有第二个消费者；搬进 `:seam` 是假缝。
2. `:core:agent` 仍依赖 `:core:tools`（`ToolContext` / `Workspace`）：端口化会让缝变万能层或再造一层，对单消费者是假缝；触发条件是出现第二个工具消费者。
3. `:app` 里留 `runtime.phone`：它要 `seam.phone` 线协议 + `BuildConfig.VERSION_NAME`，搬进平台层要么平台依赖 core、要么再造缝。
4. `:platform:termux` 的三个 APK 事实不要挪：`useLegacyPackaging` 是 **APK 级**配置（必须在 `:app`）、`side` 变体只在有 keystore 时存在、`execLibs` 的 287 个 ELF 必须原样且被解压成真实可执行文件 —— 只有真机验证得到。
5. 可见性：搬进模块的 `internal` 别的模块看不见 —— 只把需要 internal 的测试跟着搬；跨模块真要用时按「契约面」放宽并补 KDoc。

## 五、明确不做

- **不再为「名字整齐」拆模块**：8 个已到用户上限，模块多不等于结构好，**边界正确**才是。
- **不做 `interface`/`impl` 双模块**：对单人项目是纯配置开销。
- **不引入 Hilt**：触发条件是「装配点 >15 个 / 需要按作用域注入 / 引入 WorkManager」，**不是模块数**（见 [TECH-STACK.md](TECH-STACK.md)）。
- **不为速度盲目拆**：先量测（`compileDebugKotlin` 单模块增量：`:app` 4.2s、`:feature:chat` 3.7s，拆分前 `:app` 全仓 7.1s）。
