# UI v3 —— 按 dsh 逐项对齐（用户 9 条反馈）

> 原则：凡是用户标注「详情见 dsh」的，都以本机安装的 dsh 前端产物为准
> （`node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-*/lib/client.js`），
> 包括图标路径、文案 key、信息架构。

## 逐条对照

| # | 反馈 | 状态 | 实现 / 证据 |
|---|---|---|---|
| 1 | 主页中间的「deepseek HARNESS」换成黑色鲸鱼 | ✅ | 鲸鱼路径取自 dsh 前端产物里的 `FISH_LOGO_PATH`（viewBox `0 0 23.16 17.04`），做成 `res/drawable/ic_dsh_whale.xml`；浅色主题为黑、深色主题自动转白（和 dsh 的 currentColor 行为一致）。见 `ui3-empty.png` |
| 2 | 输入框按 dsh 精简：去掉模型名、去掉模式选择 | ✅ | 工具栏只剩 `＋`、模型（**仅图标**，点开是模型下拉，当前项打勾）、发送。见 `ui3-conversation.png` |
| 3 | 顶栏换成 dsh 的两组统计 + 右上角三件套 | ✅ | 左上 `⟲`（轮/步 + TPS）与 `⛁`（token + 缓存命中），点开是「会话统计」明细（Token 用量 / TPS / TTFT / 模型用时 / 工具用时，字段与 dsh 的 `stats.dialog.*` 一一对应，见 `ui3-stats-dialog.png`）；右上「打开方式 / 工作区文件 / ⋯（导出日志）」，对应 dsh 的 `open-in-app`、`sidebar-files`、`session-log-export`。**没有对话时整条顶栏不渲染** |
| 4 | 右滑拉抽屉，主界面右移，模糊减轻 | ✅ | 手势挂在**中间内容区**（边缘留给系统返回），拖到哪停到哪、松手按 0.4 阈值吸附；主界面位移 244dp；模糊 18dp → **7dp**，遮罩 32% → **18%** |
| 5 | 抽屉内容照搬 dsh，去掉没用的，保留技术自检 | ✅ | 品牌行（鲸鱼 + 字标）/「＋ 新会话」/「全局面板」（终端、工作区文件、交付物、消息反馈）/ 工作区分组 / 「技术自检（debug）」/「设置」。删掉了原来的副标题、「关闭」按钮、「运行信息」等冗余项。见 `ui3-drawer.png` |
| 6 | 对话/思考/工具展示照搬 dsh；取消 6 轮中断 | ✅ | ①**6 轮硬中断已移除**（`MAX_ROUNDS` 6 → 200，只作死循环兜底）；②新增 **对话 / 轨迹** 两个 tab，轨迹页把一轮摊平成 `输入 / 思考 / 代码 / 工具子调用（缩进） / 输出`，点任意一行展开全文——与 dsh 的 trajectory 结构一致。子调用明细真的落库了（`messages.subCallsJson`，DB v2 → v3 带迁移）。见 `ui3-trace.png`、`ui3-tabs.png` |
| 7 | 设置页改顶部横滑三页（通用/模型/功能） | ✅ | 顶部横向可滑动的三个 tab（可点可滑，HorizontalPager）：「通用设置」工作区/系统提示词附录/关于；「模型」提供方+模型+回环；「功能」网页搜索 + 终端。终端项**整行可点进入**，没有单独的进入按钮。见 `ui3-settings-general/models/features.png` |
| 8 | 应用图标改黑色鲸鱼 | ✅ | 白底 + 黑色鲸鱼自适应图标（`mipmap-anydpi-v26/ic_launcher(.round).xml` + `ic_launcher_foreground.xml`，含 monochrome 供 Android 13+ 主题图标用） |
| 9 | 去掉点击气泡动效，点击范围=图标，隐藏背景 | ✅ | 新增 `IconTap`：`indication = null`（无涟漪）、尺寸就是图标本身（不再是 IconButton 的 48dp 触摸区+涟漪），全应用统一使用 |

## 顺带修掉的问题

- **真实模型下的会话统计**：DeepSeek 的 `usage` 里带 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`，已解析并算出「缓存命中 x%」；每次请求的 TTFT、模型用时、工具用时、步数都在 AgentLoop 里就地计量。
- **「步」的定义**：与 dsh 一致 = 工具调用次数；PTC 程序内每个 `await tools.x()` 也算一步（`QuickJsRuntime` 里的 `__adsh_call__` 计数 + `ToolStepCounter`）。
- `adjustResize` 之后不再需要边缘手势兜底，删掉了会抢返回键的空 `BackHandler`。

## 验证

| 项 | 结果 |
|---|---|
| 命令行构建 | ✅ BUILD SUCCESSFUL |
| 回归自检 | ✅ `ADSH_ACCEPT` **12 / 12** |
| 真机截图 | `ui3-empty.png`（鲸鱼空态）、`ui3-conversation.png`（顶栏+输入框）、`ui3-drawer.png`（右滑抽屉+轻微模糊）、`ui3-stats-dialog.png`（会话统计） |

## 设置与功能页真正接上的配置

功能页的两个插件设置不是摆设，都接到了运行链路上：

- **终端**：`命令超时（毫秒）` / `单流输出上限（字节）` → `ToolContext.bashTimeoutMs / bashMaxOutputBytes` → bash 工具与 TermuxRuntime（默认 120000 / 64000，对齐 dsh 的 `shell.timeoutMs`、`shell.maxOutputBytes`）。
- **网页搜索**：`接口地址` / `API 密钥` → `ToolContext.webSearchBaseUrl / webSearchApiKey` → `web_search` 工具（未配置时仍返回结构化错误）。
- API 密钥字段默认用 `PasswordVisualTransformation` 打码，右侧「显示/隐藏」手动切换（之前是明文回显，已修）。

## 遗留 / 下一轮

1. 统计目前只在**本次会话内累计**（打开历史会话时只有「轮」数有意义）；要跨会话保留需要把 `SessionStats` 落到会话表。
2. 应用图标：资源与清单已在 APK 内验证（`mipmap-anydpi-v26/ic_launcher(.round).xml` + 鲸鱼前景），启动器里的最终外观由启动器缓存决定，重装后可见。
3. 鲸鱼是 DeepSeek 的品牌图形（路径来自 dsh 产物）。自用无碍；**将来若公开分发，请先确认商标授权**，或换回自绘标识。
