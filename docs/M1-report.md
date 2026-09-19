# M1 进展报告：运行时抽象、工作区三形态、前 6 个静态工具

日期：2026-09-14　状态：核心完成并通过真机验证

## 一、本轮交付

| 组件 | 位置 | 说明 |
|---|---|---|
| `TermuxRuntime` | `runtime/termux/` | 统一 $PREFIX 环境、bash 入口、命令执行（超时 / 输出上限 / 截断标记） |
| `BootstrapInstaller` | `runtime/termux/` | 解压 + 前缀改写 + 符号链接农场 + **APK 升级后重链接** |
| `Pty` / `PtySession` | `runtime/termux/` | PTY JNI 门面（终端页 M4 用） |
| `WorkspaceManager` | `core/workspace/` | 三形态（PRIVATE / REAL_PATH / SAF_REFERENCE）、绑定、**shell 实测校验**、失效检测、越界保护 |
| `Tools.kt` | `core/tools/` | `Tool` 契约 + `ToolRegistry` + 6 个工具：bash / read / write / edit / glob / grep |
| `MainActivity` | `app` | 骨架屏（M2 替换为会话页） |
| `DevCheckActivity` | `app/src/debug/` | 仅 debug 构建，导出以便 adb 回归（release 不含） |
| `M1ToolsCheck` | `app/src/debug/` | 工作区 + 工具自检 |

## 二、真机自检结果（ADSH_M1，全部通过）

```
bindRealPath -> OK
workspace.form = REAL_PATH  root = /storage/emulated/0/adsh-ws
revalidate     = Active
registry = bash, read, write, edit, glob, grep

[1] bash   -> /storage/emulated/0/adsh-ws  /  BASH-OK  /  exit=0
[2] write  -> 已写入 /storage/emulated/0/adsh-ws/notes/hello.md（24 字符）
[3] read   ->      1	line1 / 2	line2 / 3	hello world
[4] edit   -> 已替换 1 处：notes/hello.md
[5] read   ->      3	hello ADSH
[6] glob   -> notes/hello.md
[7] grep   -> /storage/emulated/0/adsh-ws/notes/hello.md:3:hello ADSH
[8] bash   -> ls -la + cat 正常，exit=0
[9] 越界保护 -> ERR: 路径越界（不在工作区内）：../../../etc/adsh-escape.txt
[10] timeoutMs 参数生效
```

关键点：工具直接读写**真实共享存储**（文件属主 `u0_a221 media_rw`），与 shell 共享同一工作区根。

## 三、两处与方案书的偏差（已记录，均为"先可用后优化"）

1. **Gradle 多模块暂缓**：目前按 `runtime.termux` / `core.workspace` / `core.tools` / `dev` 的**包边界**组织在 `:app` 内，尚未拆成独立 Gradle 模块。理由：现在拆模块只会增加构建复杂度而不带来功能收益；边界已用包名固化，等 M3 结构稳定后机械化拆分（纯移动文件 + 各自的 build.gradle.kts）。
2. **bootstrap 补丁改在运行期做**（方案书原计划构建期 `PatchBootstrapTask`）：prefix 依赖 applicationId（debug/release 不同），运行期改写反而更简单；且解压本就要读全部字节，实测整轮安装仅 **1.0–1.2s**，增量成本可忽略。构建期任务作为后续优化项保留。

## 四、下一步（M2）

1. 接入 LLM 客户端（DeepSeek 兼容，SSE 流式 + 取消）。
2. `AgentLoop` + 会话/消息持久化（Room）。
3. 会话页 UI：消息列表 + 输入区 + 键盘适配（`imePadding` + `adjustResize`），替换当前骨架屏。
4. 抽屉（视差 + 虚化 + 手势）。
