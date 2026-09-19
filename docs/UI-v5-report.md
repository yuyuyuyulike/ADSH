# UI v5 —— 输入框继续对齐 dsh（6 条反馈）

源码依据（全局安装的 dsh）：
`ui-conversation/lib/client.js` 的 `skeleton/InputBar.js`（工具区/权限/模型/发送）、`skeleton/ContextMeter.js`、
`context-occupancy.js`；`ui-permission-presets`；`ui-model-selection`；`dsh-llm-deepseek`（`reasoning_effort`）。

| # | 反馈 | 状态 | 实现 |
|---|---|---|---|
| 2 | ＋ 与 / 打开命令面板；去掉旧按钮；**彻底删除交付物/消息反馈/运行信息** | ✅ | 命令面板按 dsh 形态重做：圆角浮层 +「指令」标题 + `名称  说明` 两列（`model / permission / export / settings / new`）；`＋` 与输入 `/` 打开同一个面板。三个功能与页面入口一并删除（`Dest.Feed/Deliverables/Info` 已移除）。`＋` 改为**圆形半透明底 + Rounded 字形**。见 `ui5-palette.png` |
| 4 | 权限审批三档 + 各自图标 + 可旋转倒角 | ✅ | `仅可查看 / 工作区内修改 / 完全权限`，各自图标（Visibility / Edit / Warning）+ 打开时旋转 180° 的倒角；选「完全权限」弹确认框（文案取自 dsh 的 `confirm.*`）。**真的接了策略**：read_only 下 bash/run_code/write/edit 直接拒绝，系统提示词里的 file policy 也随档位变（`read-only / workspace-write / danger-full-access`）。见 `ui5-permission.png` |
| 5 | 模型窗口：模型名 + 思考等级两个子菜单、可旋转倒角 | ✅ | 输入框里只留图标（Memory）+ 倒角；点开是「模型」与「推理等级」两行，各自展开子菜单。推理等级按 dsh 的字段名 `reasoning_effort` 发请求（默认档位不发该字段），已核对 `dsh-llm-deepseek` 源码确认字段名 |
| 6 | 发送按钮方形蓝涟漪、切换生硬、打断差 | ✅ | 去掉涟漪（`indication = null`），底色与图标色用 `animateColorAsState` 过渡，图标用 `AnimatedContent`（淡入+缩放）切换；**打断时不再丢内容**：AgentLoop 捕获取消，把已生成的部分落库并追加「（已停止）」（对齐 dsh 的 `message.stopped`） |
| 3 | 文件导入 | ✅ | `📎` 改为圆形半透明底 + Rounded 回形针；点击打开**系统文件管理器**（SAF 多选），文件复制到「工作区/.adsh/attachments/<会话 id>/」，并把路径以 `@路径` 追加进输入框（Agent 可直接 read）；删会话时递归删除该目录；导出的 ZIP 也带上附件。**未做**：输入框上方的附件 chip（缩略图/逐个移除）与图片多模态——附件目前以 `@路径` 的形式出现在草稿里，其余语义（随会话保存/删除、参与导出）都已闭环 |
| 1 | 上下文占用标识（ContextMeter） | ✅ | 按 dsh 的 `context-occupancy` 做：药丸「上下文已用 X%」，点开是圆角浮层 = 标题行（`上下文已用 X%` + `~已用 / 窗口`）+ 进度条 + 三行彩色明细（系统提示词 / 工具定义 / 对话消息，色块对应 dsh 的 colorSystem/colorTools/colorMessages）。token 估算：CJK 1 token/字、其余 4 字符/token；系统提示词用 PromptAssembler 真实装配后估算，工具定义取 schema 量级。见 `ui5-context.png`（实测 `~469 / 128.0K`：系统 ~110、工具 ~240、对话消息 ~119） |

## 验证

- 构建 ✅；截图 `ui5-palette.png`（命令面板 + 新输入栏外观）、`ui5-permission.png`（三档权限菜单）。
- 权限档位在验证时被误点成「仅可查看」，已改回「完全权限」。
- 回归自检：**12 / 12**（删除三个面板的可组合函数后复跑）。
- 交付物 / 消息反馈 / 运行信息：连 `Panels.kt` 里的可组合函数一起删掉了（不只是入口）。

## 遗留

1. 附件 chip（缩略图 + 逐个移除）与图片多模态。
2. 权限的 `workspace_write` 与 `full_access` 目前只差「提示词里的 file policy + 写操作的静态约束」；bash 本身没有真正的沙箱（需要类似 dsh sandbox 的机制），这点在报告里如实标注。
