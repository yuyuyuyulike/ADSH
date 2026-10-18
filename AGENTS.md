# AGENTS.md — ADSH 改动规矩

改任何代码前先读这一页。它是**约束**，不是历史；历史在 CHANGELOG.md，稳定事实在 docs/ARCHITECTURE.md。
（注释规矩照抄参考项目 dsh：`D:\tmpdsh-work\AGENTS.md` 第 175、151 行。）

## 注释

- 只写**契约与上下文**，不写推理过程、不复述代码、不留评审历史。
  原文：*Comments and docs state complete contracts and context, not reasoning transcripts. Do not narrate control flow or tests, preserve review history, or restate code.*
- **不设硬性行数上限**，但长度要有理由。判据是这两条：① 删掉这条注释，下一个人会不会做出错误的改动？会 → 留；② 这段解释有没有复述代码、讲改动历史、或铺陈"为什么没选另一条路"？有 → 砍掉那部分。
  一条注释如果超过十来行，通常说明该抽进 `docs/`（留一句链接）或该改代码结构 —— 但**平台事实与不安全用法可以长**（例如 `targetSdk` 必须 28 的因果链），只要句句是结论、没有叙事。
- 代码里**禁止出现「第 N 轮」**。历史归 CHANGELOG.md 与 commit message。
- **只保留**：契约（前置/后置/不变量）、失败语义、时序与所有权、平台事实与不安全用法。
  写成结论式一句，例：`targetSdk 必须 28 —— 内嵌 Termux 能直接 exec 的前提`。
- 提示词字符串（`PromptAssembler`/`ToolSdk`）里禁止出现中文旁注、轮次、对 dsh 的对照说明 —— 模型会直接看到。

## 依赖方向

- `seam`（服务定义）**零应用依赖**，且**不许反向依赖它的消费者**。
- 允许：`feature → ui:kit / seam`；`provider → seam`；`app → 任何`。
- 禁止：`core/ui` 依赖 `seam` 之外的具体实现；`platform` 依赖 `core` 上层；任何**模块级循环**（Gradle 直接编不过）。
- 加依赖前先问：**现有 seam 是否已经覆盖这个能力？** 覆盖了就别加。

## 跑 Gradle

- **同一时刻只允许一个 Gradle 构建**。并发构建会互相写坏输出（实测：`testDebugUnitTest` 任务级 `EOFException`、`compileDebugKotlin` 报 `Unable to delete …/built_in_kotlinc/…classes`），而 XML 结果其实全是绿的 —— 踩了会白查一轮。
- 要跑门禁或编译，**一律走 `scripts/gradle-gate.ps1`** —— 它自己排队（抢不到会打印持锁者并等待，跑完自动交回）。不需要再向 Lead 要门；编辑文件本来就不需要门。
- 门禁与编译命令（**先设 JAVA_HOME**）：
  `$env:JAVA_HOME='D:\WSN2005\Android\jbr'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"`

## 改前必查

改动前逐条过 `docs/ARCHITECTURE.md` 的「硬约束清单」。那份清单是唯一真相，不要在这里重复它。

## 文档

- **一个事实只有一个家**。同一句话不许在两份文档里各写一遍，只留链接。
- 行数上限（由 `scripts/check-doc-budget.py` 门禁）：`AGENTS.md` 80、`docs/ARCHITECTURE.md` 150、`docs/TECH-STACK.md` 120、`docs/MODULES.md` 120、`docs/ROADMAP.md` 60、`HANDOFF.md` 100。
- 只写**当前状态**，不写演变过程。
