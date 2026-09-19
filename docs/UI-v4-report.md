# UI v4 —— 按 dsh **源码**逐条返工

> 这一轮的准则：不再凭 locale 串猜，直接读全局安装的 dsh 源码
> `node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-*/lib/client.js`。
> 关键源码位置：输入栏 = `ui-conversation/lib/client.js` 的 `skeleton/InputBar.js`；
> 会话过程 = `ui-chat` 的 `chat/TurnProcessNodeView.js`、`chat/ReasoningRow.js`；
> 统计 = `chat/StatsPills.js`、`chat/stat-dialog.js`；侧栏 = `ui-sidebar` `SidebarRoot.js`；
> 文件面板 = `ui-sidebar-files` `FilesBody.js`；打开方式 = `ui-open-in-app` `OpenInAppAction.js`。

## 逐条对照

| # | 你的反馈 | 源码里的实际形态 | 现在的实现 |
|---|---|---|---|
| 2 | 输入框功能/图标少了、有的不对 | `InputBar` = 工具区 `＋(input.commands=指令)` + `📎(file.attach=添加附件)` ｜ 中段访问模式 ｜ 尾部 `conversation.input.model`（**仅图标**） + 发送/停止（`input.send=发送消息` / `input.stop=停止生成`）；上方还有 `conversation.composer.dock`（任务面板 TodoPanel） | 输入框改为 `[＋ 指令] [📎 附件] … [模型图标] [发送/停止]`；上方新增**任务 dock**（任务 · N 已完成 · M 进行中 · K 待处理，可展开）；占位文案改用 dsh 原文 `发消息或创建任务, / 调用指令, @ 文件或对话`。**修正**：原来我把 `＋` 当成面板菜单，dsh 里 `＋` 是**指令**菜单、`📎` 才是附件。见 `ui4-conversation.png` |
| 3 | 两个图标合并成一个，点开要像 dsh | `StatsPills` 用 `IconDatabaseOutline16`/`IconGaugeOutline16`；点开是 `stat-dialog`：分组 + `dl/dt/dd` 定义列表（`stats.dialog.usageTitle` Token 用量、`llmTime` 模型用时、`toolTime` 工具调用用时、`ttft` 首 token 平均、`speed` 输出速度；`message.turnUsage.*` 未缓存输入/缓存读取/缓存写入/输出/缓存命中） | 左上角合并为**一个**数据库图标 → 打开「会话统计」**页面**（不再是扁平弹窗）：轮次卡片（轮/步、提供方/模型）、Token 用量卡片（总计/未缓存输入/缓存读取/缓存写入/输出/缓存命中）、速度与时延卡片（TPS/TTFT/模型用时/工具用时）。见 `ui4-stats.png` |
| 4 | 思考与工具调用展示不对；去掉轨迹 | 会话里 **没有**「轨迹」tab：每一轮渲染 `TurnProcessNodeView` —— 一行摘要 `已思考 · N 次工具调用 · 用时 X`（`message.turnProcess.*`），展开后是思考正文（`message.think` IconThink 图标）与逐个工具调用行（名称 + 参数摘要 + `已完成/执行中…/失败`，即 `command.done/running/failed`） | 删除 `TraceView.kt` 与 对话/轨迹 两个 tab；改为每轮一个可展开的**过程行** `已思考 · N 次工具调用`，展开显示思考正文 + 工具行（工具名 + 参数摘要 + 状态），工具行可再展开看程序与结果。见 `ui4-process.png` |
| 5 | 右滑不跟手、要长滑 | dsh 侧栏是按钮切换，没有移动端抽屉手势可抄 | 手势改成 `draggable`：拖动**抽屉宽度的 55%** 即开满（原来要 100%），并带**速度吸附**（快速短滑直接打开）。实测 400px 短滑即可完全拉出 |
| 6 | 抽屉去掉全局面板与工作区路径 | `SidebarRoot`：品牌行 /「新会话」(`session.new`) / 工作区下挂**会话列表** / 底部设置 | 抽屉改为：品牌行 →「＋ 新会话」→「工作区」+ **会话列表**（标题 + 相对时间「刚刚 / N 分钟 / N 小时 / N 天」，当前会话高亮，长按删除）→ 技术自检(debug) → 设置。全局面板与裸路径行已删除。配套实现了**多会话**（新建/切换/删除）。见 `ui4-drawer.png` |
| 7 | 设置太扁平简陋 | `dsh-client-ui-settings-plugins`：每项=一张卡（标题 + 一句说明 + 字段带 hint + 保存/放弃修改/恢复默认） | 三个页面全部改成卡片：标题 + 说明 + 字段 + `FieldHint` + 卡片内 `保存 / 恢复默认`。见 `ui4-settings.png` |
| 8 | 右上角三个功能与文件浏览有问题 | `ui-open-in-app`：菜单项含 `app.filemanager` 文件管理器 / `app.explorer` 文件资源管理器 / `app.terminal` **终端**，tip `在本地打开`；`ui-sidebar-files`：标题「工作区文件」+ `reload` 重新读取 + 空目录/截断/错误文案 + `FileTypeIcon` | 打开方式菜单补上**终端**，其余项由 FileProvider 枚举本机能打开该目录的应用（实测这台机器列出 WPS Office / 保存到夸克网盘 / 发送到电脑 / 质感文件 / 另存为）；工作区文件面板改为 dsh 形态：标题「工作区文件」+ `上一级 / 重新读取` + 目录/文件各带类型图标 + `空目录` 文案。见 `ui4-files.png`、`ui4-openin.png` |
| 9 | 少开子代理、子代理不得再开子代理 | —— | 本轮**没有调用任何 subagent**（上一轮也没有）；全部工作在本会话内完成 |

## 其他修正

- 空态按 dsh 的 hero 重做：鲸鱼 + `hero.headline`「探索未至之境」+ `hero.preview`「预览版」（见 `ui4-hero.png`）。
- 统计页第一行原来把 dsh 的 i18n 模板串 `{turns} 轮 {steps} 步` 当标题显示了，已修。
- 删掉 `TraceView.kt`（不再需要轨迹页）。

## 验证

| 项 | 结果 |
|---|---|
| 命令行构建 | ✅ BUILD SUCCESSFUL |
| 回归自检 | ✅ `ADSH_ACCEPT` **12 / 12**（多会话改动后复跑） |
| 截图 | `ui4-hero.png`、`ui4-conversation.png`、`ui4-process.png`、`ui4-stats.png`、`ui4-drawer.png`、`ui4-settings.png`、`ui4-files.png`（工作区文件）、`ui4-openin.png`（打开方式，含终端）|
| 附带证据 | `ui4-openin.png` 里同时能看到桌面上的 **ADSH Debug 图标已是白底黑鲸鱼**（补上了上一轮没截到的应用图标验证）|

## 还欠着的（下一轮）

1. 访问模式选择（dsh 的 `input.accessMode`）本项目没有权限分级，暂缺；要不要做需要你定。
3. 附件目前是「插入工作区文件路径」的形态（dsh 是真上传 + 图片/文档附件），要真附件需要另做一轮。
4. 统计仍只在本次会话内累计（未落库到会话表）。
