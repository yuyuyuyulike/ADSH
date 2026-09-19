# M5 进展报告（收尾）与项目终态

日期：2026-09-14　状态：M0–M5 全部完成，验收运行 12/12 通过

## 一、M5 交付

| 组件 | 位置 | 说明 |
|---|---|---|
| `PromptAssembler` | `core/agent/` | 系统提示词动态装配：persona(cwd) + 文件策略行 + 工作区顶层目录 + **AGENTS.md/CLAUDE.md 的 root→cwd 指令链**（65536 字节预算）+ **重绑定时的基线替换** |
| 内置目录浏览器 | `ui/panels/DirectoryBrowserPanel.kt` | 真实路径选择（上一级/内部存储/根目录/选择此目录），替代手输路径 |
| 验收运行 | `dev/AcceptanceCheck.kt` | 逐条核对方案书 §13.3 中可自动判定的条目 |
| 开源交付物 | `LICENSE` / `README.md` / `docs/THIRD_PARTY_NOTICES.md` / `docs/third-party/SBOM.json` | Apache-2.0 + GPL 组件声明 + 机器可读 SBOM |

## 二、验收运行结果（真机，ADSH_ACCEPT）

```
[PASS] A3 12 个静态工具已注册 — bash,read,write,edit,glob,grep,todo,present,web_search,web_fetch,ask_user_question,run_code
[PASS] A3 run_code 绑定不含自身（防递归）
[PASS] A4 提示词含工作区 cwd
[PASS] A4 提示词含文件策略行
[PASS] A4 重绑定输出基线替换
[PASS] A3 工具参数/文件错误返回结构化结果
[PASS] A3 路径越界被拒绝
[PASS] A2 模型产出 run_code 调用
[PASS] A1/A2 程序内 bash 实际执行
[PASS] A2 工具结果回填并产出最终答复
[PASS] A2 落库含 tool 消息
[PASS] A1 会话消息已持久化 — 共 4 条

结果：12 / 12 项通过
```

## 三、目标达成核对

| 目标要素 | 证据 |
|---|---|
| 工作区内落地 v2.0 方案书 | `docs/ADSH-重构方案书.md` |
| 按 M0→M5 推进 | `docs/M0-report.md` … `docs/M5-report.md` + 3 份实测记录 + 19 张真机截图 |
| 可在真机运行 | 每次里程碑均安装到 `FQJZF6U4TCVC7DB6`（Android 16 / SDK 36）并截图验证 |
| PTC 闭环 | `run_code` 程序内 `await tools.bash(...)`/`tools.glob(...)` 真实执行并回填（M3） |
| 12 个静态工具 | 注册表 + 逐项抽查（M3）+ 验收 A3 |
| Termux bash | bootstrap 重定位 + 符号链接农场 + 终端页 PtySession（M0/M1/M4） |
| 工作区绑定 | 三形态 + shell 实测校验 + 目录浏览器 + 提示词动态注入（M1/M5） |
| 四面板 | 消息反馈/交付物/文档预览/右栏 + 抽屉视差虚化 + 覆盖式导航（M4） |
| debug APK 命令行构建 | `./scripts/build-debug.sh`（Windows 侧 Gradle，JDK 用 Android Studio JBR） |

## 四、与方案书的偏差（全部为「先可用后优化」的主动选择）

| 项 | 方案书 | 实现 | 理由 |
|---|---|---|---|
| Gradle 多模块 | §5.7 拆 `:core:*`/`:runtime:*`/`:ui` | 先按**包边界**组织在 `:app` 内 | 结构稳定后再机械拆分；边界已用包名固化 |
| bootstrap 补丁时机 | 构建期 `PatchBootstrapTask` | 运行期安装器（1.0–1.1s） | prefix 依赖 applicationId；解压本就要读全部字节 |
| PTC 程序语言 | 可擦除 TypeScript | 纯 JavaScript | 该 QuickJS 无 WASM；Babel standalone 需额外 2.7MB（P1） |
| PTC 子调用 | 并行（≤10） | 串行（同步桥） | 单次 evaluate 内 promise 不 settle（P1 可改异步桥） |
| 终端 | terminal-view 全屏 VT | PTY + 行式输入 | `vmi`/`htop` 待接入（依赖已在，P1） |
| 交付物登记 | present + write/edit + bash 扫描 | present + write/edit | 避免昂贵的目录扫描（P1） |

## 五、后续优化项（P1/P2，来自方案书 §7）

**P1**：审批闸门三档、终端全屏 VT + 快捷键条、文档预览语法高亮与搜索、交付物 bash 产出扫描、宽屏右栏常驻、多会话管理（置顶/重命名/搜索）、上下文压缩、成本统计、Babel 版 TS 擦除、PTC 并行子调用、抽屉边缘手势（`setSystemGestureExclusionRects`）。
**P2**：release 流水线（签名/R8/资源压缩/ABI 拆分/Baseline Profile）、附件与图片、skill 静态支持、主题与多语言、bootstrap 裁剪与体积优化。
