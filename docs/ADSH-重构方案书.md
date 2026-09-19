# ADSH 方案书 v2.0（可开发版）

> 项目：把 deepseek harness（dsh）按需重构为 Android 原生 App
> 工作区（唯一落盘位置）：`D:/WSN2005/Android1/App/ADSH`
> 依据：dsh 宿主/后端、Web 客户端、Termux/Android 平台约束三份源码级实测报告（结论见附录 A/B）
> 阅读顺序建议：§1 → §2 → §3 → §4 → §6（MVP），§5 供实现时查阅

---

## 0. 本次调整（v1.3 → v2.0）

| # | 你的要求 | 落地方式 | 影响章节 |
|---|---|---|---|
| 1 | 工具再加三个：两个 Web 工具 + ask user | 静态工具集 9 → **12 个**；新增 `web_search`、`web_fetch`、`ask_user_question`，并给出分工/时机/IO/兜底 | §2.3、§2.4、§2.5 |
| 2 | 文件系统不再限 SAF；绑定后 bash 要能完整操作文件 | 工作区改为**三形态**（私有 / 真实路径 / SAF 引用），主力是真实路径；新增**内置目录浏览器**作为主要绑定方式；给出 bash 操作清单与实现映射 | §3 全章 |
| 3 | 保留消息反馈、交付物、文档预览、右栏 | 撤销 v1.3 的移除决定，改为**四个可推入面板**，右上角入口 + 左覆盖动画 + 明确返回；不要求同屏常驻 | §4.3–§4.6、§4.11 |
| 5 | 未明确处按最合理方案、先可用后优化 | 全文给出**默认值**而非待定项；MVP 只做能跑通的闭环，其余进 §7 | §6、§7 |
| 6 | 结构要求 | 按「功能范围 / 工具设计 / 文件系统工作区 / 主页面与导航 / 技术实现 / MVP 步骤 / 后续优化」重组 | 全文 |

**v1.3 中被本版推翻的两条**（记录以免误用）：① 移除清单里"web 检索工具"和"消息反馈/交付物/文档预览/右栏"从**移除**改为**保留**；② 文件系统从"SAF + 所有文件访问二选一"改为"真实路径为主、SAF 降级为选择器与导入导出"。

### 0.1 已确认的决策（定稿，正文已按此固化）

| # | 决策 | 结论 | 影响 |
|---|---|---|---|
| 1 | exec 通道 | **方案 A**（可执行文件走 `nativeLibraryDir`）为准；PoC-1 失败即切 B（termux-exec），再不行切 C | §5.3、§8 R1 |
| 2 | 工作区形态与引导 | **W1 默认 → 引导绑 W2（真实路径）→ 拒绝退 W3（导入导出）** | §3.2、§3.3 |
| 3 | git | **不做**：不随包、命令不可用（Android 无系统 git） | §2.1、§3.5、§7 |
| 4 | **分发定位** | **侧载自用 + 开源，不上架** | 合规约束消失（§1.2、§8 R12）；GPL 义务变为必做项（§5.9） |

---

## 1. 功能范围

### 1.1 MVP 功能清单（P0 必须交付）

| 模块 | 功能 | 判定标准 |
|---|---|---|
| 会话 | 单/多会话、流式回复、取消、历史持久化 | 冷启动可对话；杀进程后历史仍在 |
| PTC | 模型只看到 `run_code`；程序内通过 SDK 调 12 个工具；嵌套调用可视化 | 「提问 → 模型产出 TS 程序 → 调 bash 与 web_search → 回填 → 最终回答」闭环 |
| 工具 | 12 个静态工具全部可用且有单测 | 参数错误/超时/取消/输出超限四类路径均返回结构化结果 |
| 文件系统 | 工作区三形态 + 绑定 + bash 全操作 | 断言清单见 §3.5（ls/mkdir/写读/mv/cp/rm/执行脚本 各一条 e2e） |
| Web | `web_search` + `web_fetch` | §2.3 的失败兜底矩阵逐条可复现 |
| 交互 | 提问卡（ask user）往返 | 程序内 `await tools.ask_user_question(...)` 能挂起并恢复 |
| 主页面 | 会话页 + 4 个可推入面板 + 抽屉 + 输入区 | §4.11 的导航与动画规格逐条通过 |
| 终端 | 设置 → 终端 → 进入终端；命令超时等选项 | 终端可跑全屏程序；改超时立即生效 |
| 设置 | 通用 / 模型 / 终端 三个一级入口 | 无插件、无预设、无模式选择残留 |
| 构建 | 命令行出 debug APK；开发标签 | `./gradlew :app:assembleDebug` 干净环境通过 |

### 1.2 明确不做（非目标）

- proot / rootfs / 文件系统快照 / 运行期下载可执行代码。
- 插件系统（cordis Loader / HMR / 插件清单 UI / `cordis_*` 工具）。
- 子代理（`subagent` / `subagent_fork` / `list_agents` / `send_message` / `interrupt_agent`）。
- 轨迹（trajectory）页面与事件时间轴。
- 四个 Agent 预设（standard / ptc / minimal / cordis）与预设 chip；只保留 **ptc** 语义。
- 输入框里的模式选择（访问模式下拉 `PermissionSelect` + 计划模式 `PlanChip`）。
- `pwsh` 系列、`workflow`、`ralph`、`goal`、`schedule`、`jobs`、`skill`、`mcp__*`、`read_image`。
- **Play 上架**：定位为**侧载自用 + 开源**，不安排 Play 发布、AAB、用途审查等任何上架工作（`MANAGE_EXTERNAL_STORAGE` 在侧载下没有用途审查）。release APK 仍自签名构建，但只用于自己与他人侧载。
- **git**：不随包（安装后 24 MB 且依赖复杂，Android 也无系统 git 可借），SDK 提示词里明确写为"不可用"。工作区内若存在 `.git` 目录，仍可作为 `projectRoot` 的 marker（只做存在性检测，不需要 git 可执行文件）。

### 1.3 完成定义（DoD）

1. 干净环境命令行构建成功（含 bootstrap 打包任务与 `execLibs`）。
2. 真机离线（除模型/搜索 API）跑通 §1.1 的全部判定标准。
3. 未绑定工作区时有引导；绑定后提示词里的 `cwd` 与目录信息随之变化，且 bash 能在该目录完成 §3.5 的全部操作。
4. 四个面板都能从会话页右上角进入、按规格返回，动画无跳变。
5. 键盘弹出时输入框上移、内容不被遮挡、光标可见。

---

## 2. 工具设计

### 2.1 静态工具总表（12 个）

| # | 工具名 | 作用 | 宿主能力 | 默认超时 | 输出上限 |
|---|---|---|---|---|---|
| 0 | `run_code` | PTC 唯一入口：执行模型写的可擦除 TypeScript 程序 | QuickJS | 120000 | 64 KiB |
| 1 | `bash` | 在 Termux bash 中执行命令（`bash -lc`） | SH + FS | 120000（上限 600000） | 64000 B |
| 2 | `read` | 读 UTF-8 文本，带行号 | FS | 30000 | 64 KiB |
| 3 | `write` | 创建或整体替换文件 | FS | 30000 | — |
| 4 | `edit` | 字面量替换（`old_string` → `new_string`） | FS | 30000 | — |
| 5 | `glob` | 按 glob 找文件（含隐藏/忽略文件） | FS | 30000 | 200 条 + 溢出提示 |
| 6 | `grep` | 正则搜内容（随包 ripgrep 或 Kotlin 实现） | FS | 30000 | 250 行 + 溢出提示 |
| 7 | `todo` | 结构化任务列表（`todo_write`） | 无 | 5000 | — |
| 8 | `present` | 声明文件为交付物（进 §4.4 交付物面板） | FS | 5000 | — |
| 9 | `web_search` | 关键词检索（不知道 URL 时） | NET | 30000 | 20 条结果 |
| 10 | `web_fetch` | 抓取指定 URL 并转文本（已知 URL 时） | NET | 30000 | 512 KiB |
| 11 | `ask_user_question` | 向用户提问并等待应答（UI 往返） | UI | 600000 | — |

> **静态化不等于"永远可见"**：12 个工具在编译期注册进注册表与 SDK 存根；运行期按配置**启用/禁用**（例如未配置搜索密钥时 `web_search` 从 SDK 目录隐藏并在设置页给出引导）。这是"编译期固定、运行期裁剪"，不违背工具静态化。

### 2.2 PTC 呈现与 run_code

- 模型侧工具表**只有** `run_code`；其余 11 个工具以 TypeScript SDK 形式注入程序运行时（对齐 dsh 的 `ToolPresentationMode = 'ptc'`：直呼非 `run_code` 的工具会被拒绝）。
- 程序是**可擦除 TypeScript**（无 `enum`/`namespace`）；宿主侧先擦除类型再交给 QuickJS（对齐 dsh 用 `stripTypeScriptTypes` 的语义）。
- 子调用 id 形如 `<callId>:ptc:<n>`，并行上限 `maxParallelSubCalls` 默认 **10**（可在设置里改）。
- 返回值：程序 `return` 值 + `console` 日志；失败抛 `CODE_RUN_FAILED`。嵌套调用只进**日志与 UI**，不回灌模型上下文（对齐 dsh 的 `tool/ptc-dispatch` 语义）。

### 2.3 两个 Web 工具（分工 / 时机 / IO / 兜底）

#### 2.3.1 分工（一句话规则，会写进 SDK 提示词）

| 场景 | 用哪个 |
|---|---|
| 只有问题、没有 URL | `web_search` |
| 已有确切 URL | `web_fetch` |
| 工作区文件能回答 | 都不用（先 `glob` / `grep` / `read`） |
| 想"猜"一个 URL 去 fetch | 禁止；先 search 拿 `url` 再 fetch |
| 需要把网页结果存下来 | fetch 之后用 `write`，web 工具本身不写文件 |

#### 2.3.2 调用时机（写进 SDK 提示词的边界）

联网**仅当**：需要版本号/发布日期/API 变更等时效性事实、用户明确要求查网、或本地无相关信息。稳定知识优先用模型自身，避免无谓联网。

#### 2.3.3 web_search

```ts
web_search({
  queries: string[],        // 1..4 条，非空；多条用于并列对比
  maxResults?: number,      // 默认 5，上限 20
  recency?: 'any' | 'day' | 'week' | 'month' | 'year'   // 默认 'any'（P1 生效）
}): Promise<{
  content?: string,                                        // provider 提供的聚合答案
  results: { title: string, url: string, snippet?: string, publishedAt?: string }[],
  sources: number,
  truncated: boolean
}>
```

- **provider**：默认复用 DeepSeek 搜索（`DEEPSEEK_API_KEY`，与聊天同 key、端点可单独覆盖）；设置页「模型」里可配自定义端点。对齐 dsh 的 `dsh-web-search-deepseek`。
- **超时**：30 s（dsh 基线 `searchTimeoutMs: 60000`；我们取 30 s 因为移动网络更需快速失败，设置页可改）。
- **预算**：单轮 web 调用总数（search + fetch 合并）默认上限 **8**，对应 dsh `WebSearchCard` 的 `maxUses`。
- **失败兜底矩阵**：

| 失败 | 返回 | 模型应如何继续 |
|---|---|---|
| 未配置密钥 | `{error:'web_search_unavailable',reason:'no_api_key',retryable:false}` | 改用 `web_fetch`（若有 URL）或直接说明无法联网 |
| 超时 | `reason:'timeout',retryable:true` | 最多再用更窄的查询试 1 次 |
| 429 / 5xx | `reason:'rate_limited'/'http_5xx',retryable:true` | 退避后重试 1 次；仍失败则改 fetch |
| 0 结果 | `{results:[],sources:0}`（不算错误） | 换同义词或转 `web_fetch` 已知站点 |
| 超预算 | `{error:'web_budget_exhausted',retryable:false}` | 停止联网，用已有信息作答 |

#### 2.3.4 web_fetch

```ts
web_fetch({ url: string }): Promise<{
  url: string,                 // 最终 URL（跟随重定向后）
  statusCode: number,
  contentType: string,
  body: { kind: 'html' | 'text', content: string },
  truncated: boolean
}>
```

- **只接受 http/https**，匿名访问（不带 Cookie/凭据）；跟随重定向 ≤ 5 跳；gzip/br 解压。
- **正文纯文本化**：去 `script/style`，HTML 转文本（保留链接为 `text (url)` 形式，便于模型引用）。
- **大小上限 512 KiB**：超出即截断并置 `truncated: true`（同时是上下文保护）。
- **非文本类型**（image/pdf/zip/octet-stream）→ `{error:'unsupported_content_type'}`，并提示改用其他方式。
- **SSRF 防护（必须实现）**：DNS 解析后校验目标 IP 不属于 `10/8`、`172.16/12`、`192.168/16`、`127/8`、`169.254/16`、`::1`、`fc00::/7`、`fe80::/10`；禁止 `file://` 与带凭据的 URL；禁止访问本机端口。
- **失败兜底**：

| 失败 | 返回 | 处理 |
|---|---|---|
| 命中间私网/环回 | `reason:'ssrf_blocked',retryable:false` | 不重试，提示模型换来源 |
| http 且站点支持 https | 自动升级为 https 重试 1 次 | 仍是 http 才返回 |
| 超时 | `reason:'timeout',retryable:true` | 同 URL 最多重试 1 次 |
| 超过 512 KiB | 截断返回，非错误 | 提示优先抓具体文档页而非首页 |
| 非文本类型 | `reason:'unsupported_content_type'` | 不重试 |
| DNS 失败 / 4xx | `reason:'dns'/'http_4xx'` | 不重试，提示换来源 |

#### 2.3.5 两者共同的契约

- **都不写文件、都不改工作区**；需要落盘时由模型显式调 `write`。
- **都计入同一预算**（默认 8 次/轮），超预算硬失败，避免单轮把上下文打爆。
- **返回体里永远带结构化 `error` 字段**，不抛异常穿透 agent loop。
- 结果进 UI 的**消息反馈区**（§4.3），同时作为 `run_code` 的程序内返回值。

### 2.4 ask_user_question

#### 2.4.1 契约

```ts
ask_user_question({
  questions: [{
    id: string,                 // 稳定 id，应答时回传
    question: string,
    header?: string,            // 短标题，如「确认」「选择模式」
    options?: { label: string, description?: string }[],
    multi_select?: boolean      // 默认 false
  }]
}): Promise<{
  answers: { id: string, selected: string[], custom?: string }[]
}>
```

失败（非异常，走返回值）：`{error:'user_no_answer', reason:'timeout'|'skipped'|'cancelled'}` 或 `{error:'unavailable'}`（App 不在前台/无 Activity，fail-closed，与 dsh 一致）。

#### 2.4.2 交互实现（这是它和别的工具最大的不同）

- **输入框接管**：提问期间，输入区被「提问卡」接管（对齐 dsh 的 `conversation.composer` chain 接管语义）。卡片自上而下：`header` → `question` → 选项按钮（单选/多选）→ 自定义输入框 → 「提交」。
- **暂存语义**（与我在本机 dsh 环境里看到的行为一致）：用户**必须先提交或跳过**，卡片才消失；单选即有默认推荐项时，推荐项排第一并标注「（推荐）」。
- **提交后**：卡片消失；问答对写入会话并出现在**消息反馈区**；被挂起的 Promise resolve，程序继续。
- **超时/跳过**：默认等待 **10 分钟**（可配 1–60 分钟）；超时或用户点「跳过」→ 返回 `user_no_answer`，程序自行决定继续或收尾。
- **并发**：同一轮最多 1 个待答问题；多余的在队列里等待。
- **后台**：等待期间保持前台服务，通知标题「等待你的回答」。
- **不该用的场景**（写进 SDK 提示词）：能从工作区查到的（问代码在哪、现状是什么）不要问；只在"用户拥有的选择或材料性歧义"时问。

### 2.5 工具注册表与 SDK 静态生成

- 工具实现是 Kotlin 类，编译进 `:core:tools`；**无反射扫描、无动态注册**。
- 编译期由 Gradle 任务（`GenToolSdkTask`）从注册表生成 `sdk.d.ts`（含上表的签名与调用规则），作为 asset 注入提示词。
- 单测强制「注册表 ↔ SDK 存根 ↔ 本篇 §2.1 总表」三者一致（新增工具必须同时改三处，防止漂移）。
- 运行期裁剪：按 `ToolAvailability`（是否有搜索密钥、是否绑定工作区、是否在前台）决定哪些工具出现在 SDK 目录里。

---

## 3. 文件系统与工作区设计

### 3.1 目标能力（本版的核心变化）

绑定工作区后，**bash 必须能完整操作文件**，而不只是"应用内能读能写"：

| 操作类别 | 用户要求 | MVP 判定 |
|---|---|---|
| 查看 | 列出、浏览、看属性 | `ls -la` / `find . -maxdepth 2` / `stat` 可用 |
| 创建 | 建目录、建空文件 | `mkdir -p a/b` / `touch f` 可用 |
| 读取 | 看内容（含大文件分段） | `cat` / `head -n 50` / `wc -l` 可用 |
| 写入 | 覆盖、追加 | `printf ... > f` / `tee` / `>>` 可用 |
| 移动 | 改名、移动 | `mv a b` 可用 |
| 复制 | 文件与目录递归 | `cp -r a b` 可用 |
| 删除 | 文件与目录 | `rm` / `rm -rf` 可用 |
| 执行脚本 | 跑用户/模型写的脚本 | `chmod +x s.sh && ./s.sh`、`sh s.sh` 可用 |
| 文本处理 | 搜索、替换、比较、排序 | `grep -rn` / `sed` / `awk` / `diff` 可用 |
| 归档 | 打包/解包 | `tar -czf` / `gzip` / `xz` 可用 |

### 3.2 工作区三形态（这是"不再限 SAF"的落地方式）

| 形态 | 根路径 | bash 能力 | 用户可见性 | 何时用 |
|---|---|---|---|---|
| **W1 私有工作区**（默认） | `/data/data/<pkg>/files/workspaces/<name>` | **全部**（含 chmod +x 执行） | 仅 App | 首启默认；用户拒绝任何授权时的兜底 |
| **W2 真实路径工作区**（推荐） | `/storage/emulated/0/<子目录>` 或 `/storage/<VOLID>/<子目录>` | **全部**（sdcardfs 上 chmod 语义有限，但读写移删与执行脚本均可） | 系统文件管理器可见 | 需要与手机文件互通、需要 git/脚本时 |
| **W3 SAF 引用工作区**（保底） | shell 侧仍是 `files/workspaces/<name>`；SAF 只做进出通道 | 仅在私有区全部可用；**用户目录不可直接 shell 访问** | 通过导入/导出 | 用户只肯给 SAF 授权时 |

**默认处理**：首启创建 W1 并立即可用；引导页提供「选择手机文件夹（推荐）」跳到 §3.3；用户拒绝"所有文件访问"时自动停留在 W1，并把 W3 作为可选补充，UI 明确说明差异。

### 3.3 绑定方式：两种并存，主推内置目录浏览器

| 方式 | 实现 | 优点 | 限制 |
|---|---|---|---|
| **A. 内置目录浏览器**（默认推荐） | 自绘 Miller 分栏（面包屑 + 可编辑路径 + 前缀过滤 + 新建文件夹）；在 `MANAGE_EXTERNAL_STORAGE` 下遍历 `/storage/emulated/0`、`/storage/<VOLID>`、`files/`；直接返回**真实路径** | 绕开 SAF 的 VFS 权限问题，绑定即可被 shell 使用；形态与 dsh 的 `ui-directory-picker-browse` 一致 | 需要"所有文件访问"权限 |
| **B. 系统 SAF 选择器**（保底） | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`；解析 `documentId`（`primary:Download` → `/storage/emulated/0/Download`；`39BD-07C5:foo` → `/storage/39BD-07C5/foo`） | 系统 UI、用户熟悉、无需特殊权限 | **官方无 API 拿绝对路径**；且 SAF 只给 ContentProvider URI 权限，shell 的 `open()` 在 scoped storage 下必然 EACCES |

**绑定流程（两条路合流）**：

```
入口（首启引导 / 抽屉底部工作区状态 / 设置→通用→工作区）
   │
   ├─ 若未授予"所有文件访问" → 说明页（为什么需要 + 能力对比 + 撤销方法）
   │      └─ 跳 Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION → 返回后复查
   │
   ├─ 方式 A：目录浏览器选目录 ─┐
   └─ 方式 B：SAF 选目录 ───────┤
                                ▼
                        解析出候选真实路径
                                ▼
                   实测校验：bash -lc 'test -d P && test -w P && echo ok'
                       │通过                     │失败
                       ▼                          ▼
                绑定为 W2（active）          提示二选一：
                                            ① 去开启"所有文件访问"
                                            ② 降级为 W3（导入/导出）
```

**校验必须用 shell 实测**，不能用 Kotlin 的 `File.canWrite` 代替 —— Kotlin 侧与 shell 子进程的权限路径不同，只有 `test -w` 才能证明"bash 真的能写"。

### 3.4 路径解析与权限

- 真实路径解析只做两件事：① `primary` 卷映射到 `/storage/emulated/<userId>`；② 其他卷用 `documentId` 的卷 UUID 拼 `/storage/<VOLID>`。第三方 provider（Drive / OneDrive / Downloads）**没有文件系统路径**，直接判失败。
- 被系统限制的路径（Android 11+）不解析、不当工作区：`Android/data`、`Android/obb`、`Android/sandbox`。
- 权限矩阵：

| 能力 | W1 私有 | W2 真实路径（有"所有文件访问"） | W3 SAF |
|---|---|---|---|
| Kotlin 文件工具 read/write/edit/glob/grep | 可用 | 可用 | 可用（走 ContentResolver） |
| bash 直接 cd 并操作 | 可用 | 可用 | 不可用 |
| 执行脚本 | 可用 | 可用 | 不可用 |
| git / tar / rg 在此目录工作 | 可用 | 可用 | 不可用 |
| 与手机文件管理器互通 | 不可用 | 可用 | 手动导入导出 |

### 3.5 bash 操作清单 → 实现映射（评审用断言表）

| 操作 | 断言命令（e2e 用例） | 依赖的随包工具 |
|---|---|---|
| 查看 | `ls -la; find . -maxdepth 2 -type f` | coreutils / findutils |
| 创建 | `mkdir -p a/b && touch a/b/f.txt` | coreutils |
| 读取 | `head -n 20 a/b/f.txt` | coreutils |
| 写入 | `printf 'hello' > a/b/f.txt; printf 'x' >> a/b/f.txt` | coreutils |
| 移动 | `mv a/b/f.txt a/f.txt` | coreutils |
| 复制 | `cp -r a a2` | coreutils |
| 删除 | `rm -rf a2` | coreutils |
| 执行脚本 | `printf 'echo hi' > s.sh && chmod +x s.sh && ./s.sh` | bash + chmod |
| 文本处理 | `grep -rn hello .`；`sed -i 's/hello/world/' a/f.txt`；`awk '{print $1}' a/f.txt` | grep / sed / gawk |
| 比较 | `diff -u a/f.txt a2/f.txt` | diffutils |
| 归档 | `tar -czf a.tgz a && tar -tzf a.tgz` | tar / gzip |
| 属性与统计 | `stat a/f.txt`；`wc -l a/f.txt`；`du -sh a` | coreutils |

**明确不可用**（写进 SDK 提示词，避免模型浪费时间）：`git`（**本方案不随包 git，Android 也没有系统 git** —— 不要尝试 `git status`；需要历史/版本信息就自己读 `.git` 目录里的文件，或直接问用户）、`chown/chgrp`、`sudo/su`、`mount`、`apt/dpkg`、`systemctl`、访问 `/dev/block/*`、读其他 App 的 `/data/data/<other>`、绑定小于 1024 的端口。这些是平台边界与产品取舍，不是实现问题。

### 3.6 提示词动态注入（工作区相关内容的完整清单）

每次请求前重建，**不缓存**：

| 片段 | 内容 | 对齐 dsh |
|---|---|---|
| persona | `Your working directory is <cwd>.` | `dsh-persona` 的 `{{cwd}}` |
| 文件策略 | `Current DSH file policy: <mode>. ... may modify files under the session workspace: "<root>"` | `sandbox:policy` |
| 工作区上下文 | 工作区名、绝对路径、顶层目录清单、是否可写 | `dsh-agent-instructions` 的工作区段 |
| 指令文件 | 沿 `projectRoot → cwd` 的包含式目录链收集 `AGENTS.md` / `CLAUDE.md`，按 65536 字节预算截断；刷新时机：首请求加载、深层 read/write/edit 后纳入、resume 时对账 | `dsh-agent-instructions` |
| 基线替换 | 重绑定工作区时注入替换语义，明确作废旧基线 | `REPLACEMENT_WORKSPACE_CONTEXT_INTRO` |

未绑定时注入：「当前为应用私有工作区 `<path>`，用户尚未绑定手机文件夹；不要臆测外部路径」。

### 3.7 失效与降级（默认处理，均不打断轮次）

| 场景 | 检测 | 默认处理 |
|---|---|---|
| 授权被撤销 | 每次前台启动 + 每次工具调用前 `checkUriPermission` / `isExternalStorageManager()` | 标记 `NeedsRebind`；工具返回 `{error:'workspace_unavailable',reason:'permission_revoked'}`；UI 顶部横幅 + 一键重绑 |
| 目录被删 / 移动 | 探针文件 `.adsh_probe` 读写失败 | 同上，提示重新选择 |
| SD 卡 / USB 移除 | 路径不可达 | 同上 |
| "所有文件访问"被回收 | `Environment.isExternalStorageManager()` 为假 | 自动降级到 W1 或 W3，UI 明确告知"bash 现在只能操作私有目录" |
| 未绑定 | 启动即知 | 用 W1，全部工具可用，显示引导卡片 |

### 3.8 安全边界与默认策略

- **禁止绑定**：`/`、`/system`、`/data`（App 自身 data 除外）、`/proc`、`/sys`，以及 `Android/data`、`Android/obb`、`Android/sandbox`。
- **默认放行策略**：MVP 固定 `danger-full-access`（读/写/删/执行全部放行），以最快跑通闭环；但在**消息反馈区**对 `rm -rf`、`chmod +x`、工作区外路径写入做红色标记，便于事后审计。
- **审批闸门**（P1）：三档 `read-only(ask)` / `workspace-write(ask)` / `danger-full-access(never)`，与 dsh permission presets 对齐；MVP 只留接口与日志，不做 UI。

---

## 4. 主页面与导航交互

### 4.1 导航骨架

```
MainActivity（单 Activity，enableEdgeToEdge）
└─ NavHost
   ├─ ConversationScreen            「会话页」= 根（不参与覆盖动画）
   │   ├─ TopAppBar：抽屉按钮 | 标题 | [反馈] [交付物] [信息]   ← 右上角三个入口
   │   ├─ MessageList（LazyColumn，虚拟化，自动吸底）
   │   ├─ Composer（底部输入区；被 ask_user 提问卡接管时替换）
   │   └─ [Drawer] 会话列表 / 新建 / 工作区状态 / 设置入口
   │
   ├─ MessageFeedScreen              ← 「消息反馈」（覆盖推入）
   ├─ DeliverablesScreen             ← 「交付物」（覆盖推入）
   ├─ DocumentPreviewScreen(fileRef) ← 「文档预览」（从交付物或消息内文件链接推入）
   ├─ RightPanelScreen               ← 「右栏」（覆盖推入；≥600dp 时改为常驻右列）
   ├─ TerminalScreen                 ← 「终端」（从 设置→终端 推入）
   └─ SettingsScreen                 ← 「设置」（抽屉进入；一级只有 通用/模型/终端）
```

### 4.2 入口、切换与返回（这就是你要求的"点击右上角切新页面 + 往左覆盖动画"）

**入口**：右上角三个图标按钮，语义固定不随上下文变化：

| 按钮 | 进入 | 图标语义 |
|---|---|---|
| 反馈 | `MessageFeedScreen` | 系统消息 / 工具结果 / 错误 / 交互反馈 |
| 交付物 | `DeliverablesScreen` | 本次会话产出的文件与结果 |
| 信息 | `RightPanelScreen` | 工具状态 / 上下文 / 工作区 / 会话信息 |

**切换动画（统一一套，所有覆盖页共用）**：

```kotlin
// 覆盖式 push：新页从右缘滑入并覆盖当前页；当前页向左退让 1/4 并轻微变暗
AnimatedContent(
    targetState = destination,
    transitionSpec = {
        if (targetState.isPushedOver(initialState)) {
            (slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it } +
             fadeIn(tween(220))) togetherWith
            (slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 4 } +
             fadeOut(tween(220, targetAlpha = 0.85f)))
        } else {
            (slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(220))) togetherWith
            (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(220)))
        }
    }
)
```

- 新页左缘加 1dp 分隔线 + 8dp 阴影，强化"覆盖"观感。
- 时长 300 ms，缓动 `FastOutSlowInEasing`；系统"减弱动画"开启时退化为 150 ms 淡入淡出。
- **同一套进度**同时驱动内容位移与（面板之间的）遮罩透明度，避免手势结束时跳变。

**返回**（三条路径都能用，且指向同一状态机）：

1. 系统返回键 / 手势 / **预测性返回**：用 `PredictiveBackHandler` 驱动上面同一套 transition 进度，可中断、可取消。
2. 左上角返回箭头（仅覆盖页有）。
3. **不做自定义"右边缘滑动返回"**——它与系统返回手势冲突，系统返回即等价操作。

**返回优先级**（逐层退，不一次跳回根）：预览页内的二级视图（如切换文件）→ 预览页 → 来源面板（交付物/反馈）→ 会话页；抽屉打开时返回先关抽屉；生成进行中时先弹"取消生成？"。

### 4.3 保留模块①：消息反馈区（MessageFeedScreen）

**定位**：把对话页里零散的内联卡片，聚合成一条可检索的时间线。二者**共享同一数据源**（Room 的 `tool_call` / `session_event` 表），不重复存储。

| 内容类型 | 具体条目 | 交互 |
|---|---|---|
| 系统消息 | 连接中/重连、首次引导、bootstrap 解压进度、工作区状态变化 | 点击可跳转相关设置页 |
| 工具调用与结果 | 每个工具调用一行（名称、参数摘要、耗时、状态）；`run_code` 展开后按 `subCallId` **缩进显示嵌套子调用** | 展开看完整入参/出参；点"定位"跳到对话页对应消息 |
| 错误与警告 | 网络失败、工具失败、权限失效、web 预算耗尽、QuickJS 异常 | 展开看结构化错误体与修复建议 |
| 用户交互反馈 | `ask_user_question` 的问答对、危险命令标记、审批结果（P1） | 展开看完整问题与所选答案 |

- 过滤条：全部 / 工具 / 错误 / 交互（默认全部）。
- 每条含：时间、类型图标、标题、摘要、可展开详情；危险项（`rm -rf`、`chmod +x`、工作区外写入）红色标记。
- 分页：LazyColumn + 每页 200 条，向上滚动加载更早。

### 4.4 保留模块②：交付物面板（DeliverablesScreen）

**来源（MVP 只做前两条，避免昂贵的目录扫描）**：

1. `present` 工具显式声明的最终交付物；
2. `write` / `edit` 成功写出的文件自动登记；
3. （P1）会话期间对工作区做轻量 mtime 扫描，登记 bash 产出的文件。

| 字段 | 说明 |
|---|---|
| 文件名 / 相对路径 | 相对当前工作区根，避免暴露绝对路径 |
| 大小 / 修改时间 | 来自 `stat` |
| 来源 | present / write / edit / bash(P1) |
| 状态 | 新建 / 修改 / 删除 |

**操作**：打开（→ 文档预览）、用系统应用打开（FileProvider + `ACTION_VIEW`）、分享、复制路径、导出到用户目录（W2/W3 用 `cp`，SAF 场景用 ContentResolver 写回）。

**筛选**：本次会话 / 本轮 / 仅最终结果（present 标记）。

### 4.5 保留模块③：文档预览（DocumentPreviewScreen）

| 类型 | 处理 | 优先级 |
|---|---|---|
| 纯文本 / 日志 | 等宽 + 行号，> 1 MiB 只加载首 512 KiB 并提供"继续加载" | P0 |
| Markdown | 渲染（标题/列表/代码块/表格），可切换源码视图 | P0 |
| 代码 | 按扩展名高亮 | P1 |
| JSON / CSV | 格式化 / 表格视图 | P1 |
| 图片 / PDF / 二进制 | 不用内置预览，直接"用系统应用打开" | P0（给出入口即可） |

通用能力：字号跟随设置、行号开关、横向滚动开关、复制全部/选中、搜索与行跳转（P1）。

### 4.6 保留模块④：右栏（RightPanelScreen）

- **手机**：作为覆盖页推入（右上角「信息」）。
- **宽屏（≥600dp）**：改为常驻右列（此时右上角「信息」变成显示/隐藏开关），复用同一套 Composable。

分区（可折叠卡片）：

| 卡片 | 内容 | 刷新源 |
|---|---|---|
| 工具状态 | 运行中的 bash / 子调用、耗时、取消按钮 | agent 运行态 Flow |
| 上下文 | token 用量、当前模型、压缩状态 | 会话 Flow |
| 工作区 | 路径、形态（W1/W2/W3）、可写性、磁盘占用、重新授权入口 | WorkspaceManager |
| 会话 | 消息数、创建时间、系统提示词预览（debug 开关） | 会话 Flow |

### 4.7 抽屉（会话列表）

- 视差 + 虚化 + 手势：主界面随抽屉右移并虚化（`haze`），见下。
- **与面板导航的关系**：抽屉只在会话页可打开；覆盖页打开时禁用左边缘手势（避免歧义）。
- 内容：新建会话、会话列表（置顶/重命名/删除，P1）、底部工作区状态行与设置入口。

```kotlin
val progress = drawerProgress()                        // 0f 关闭 → 1f 打开
Box {
    ConversationContent(
        Modifier
            .offset { IntOffset((progress * maxShiftPx).roundToInt(), 0) }
            .graphicsLayer { scaleX = 1f - progress * 0.06f; scaleY = 1f - progress * 0.06f }
            .hazeSource(hazeState)
    )
    DrawerPanel(Modifier.offset { IntOffset(((progress - 1f) * drawerWidthPx).roundToInt(), 0) })
    Box(Modifier.fillMaxSize()
          .background(Color.Black.copy(alpha = 0.32f * progress))
          .hazeBlur(input = HazeInput.Sources(hazeState), style = blurStyle))
}
```

- 触摸起点在左边缘 24 dp 内才接管，避免与消息横向选择、表格滚动冲突。
- 关闭：抽屉内左滑 / 返回键 / 点遮罩；低端机或关闭模糊时遮罩退化为纯色。

### 4.8 输入区（Composer）与键盘

- 底部固定、多行自增高（最大高度后内部滚动）、发送键常驻；**无模式选择控件**（已移除 `PermissionSelect` 与 `PlanChip`）。
- 键盘：`Modifier.imePadding()` + Manifest `android:windowSoftInputMode="adjustResize"` + `enableEdgeToEdge()`；消息列表 `weight(1f)`，键盘弹出时压缩可视区而非整体上推；用 `bringIntoViewRequester` 保证光标可见。
- 被 `ask_user_question` 接管时，同一位置渲染提问卡（§2.4.2）。
- 附件按钮位预留（P2 实装）。

### 4.9 终端页

- 复用 `com.termux.termux-app:terminal-view` + 自写 PTY JNI（`open("/dev/ptmx")` + `grantpt/unlockpt/ptsname_r` + `fork()` + `TIOCSWINSZ`，照抄 Termux `termux.c`）。
- 环境：`PREFIX/HOME/PATH/LD_LIBRARY_PATH/TERM=xterm-256color/TMPDIR/LANG`。
- MVP 单标签；多标签与快捷键条（Esc/Ctrl/Tab/方向键）进 P1。

### 4.10 设置页（一级只有三个入口）

| 一级 | 行项 |
|---|---|
| **通用** | 外观（主题 / 字号）、显示（模糊开关、显示 token 用量、思考折叠）、**工作区**（当前形态与路径、绑定 / 解绑 / 重新授权）、会话（默认行为）、关于（版本、构建号、bootstrap 版本、许可声明） |
| **模型** | 服务商（DeepSeek 内置 / OpenAI 兼容自定义 baseUrl）、API Key（加密存储、只显示"已设置"）、模型清单、生成参数；**Web 搜索**（provider、密钥、单轮调用上限默认 8） |
| **终端** | bootstrap 状态与重新解压、命令超时（120000）、最大超时（600000）、输出上限（64000 B）、shell 与登录式、附加环境变量、**PTC 子调用并行上限（10）**、**ask_user 等待超时（600000）**、[进入终端] |

### 4.11 动画与状态机规格（可直接照着写）

| 项 | 规格 |
|---|---|
| 覆盖动画时长 | 300 ms（减弱动画时 150 ms） |
| 缓动 | `FastOutSlowInEasing` |
| 新页位移 | 从 `+width` → 0 |
| 旧页位移 | 从 0 → `-width/4` |
| 旧页透明度 | 1.0 → 0.85 |
| 新页左缘 | 1 dp 分隔线 + 8 dp 阴影 |
| 抽屉位移 | 0 → `+0.25 * width`（主内容），抽屉自身 `-width` → 0 |
| 抽屉虚化半径 | 18 dp（可关） |
| 抽屉遮罩 | 黑色 `alpha = 0.32 * progress` |
| 返回手势 | 系统返回 / 预测性返回，驱动同一 progress |
| 边缘分区 | 左边缘 24 dp = 抽屉；不做自定义右边缘返回 |

### 4.12 优先级标注

| 模块 | P0（MVP 必须有） | P1 | P2 |
|---|---|---|---|
| 会话页 + 消息列表 + 输入区 + 键盘 | ✅ | | |
| 抽屉（视差 / 虚化 / 手势 / 返回键） | ✅ | | |
| 四个面板：入口 + 推入 + 返回 + 覆盖动画 | ✅ | | |
| 消息反馈区：时间线 + 展开详情 + 定位跳转 | ✅ | 过滤、分页 | |
| 交付物面板：present/write 登记 + 打开 + 导出 | ✅ | bash 产出扫描、仅最终结果筛选 | |
| 文档预览：文本/Markdown + 大文件分段 | ✅ | 高亮、搜索、行跳转、JSON/CSV | |
| 右栏：手机推入页 | ✅ | 宽屏常驻、系统提示词预览 | |
| 设置页三入口 + 终端页 | ✅ | 多标签、快捷键条 | |
| 提问卡（ask_user） | ✅ | 多问题分组、模板 |

---

## 5. 技术实现要点

### 5.1 技术选型

| 维度 | 选型 | 理由 | 备选 / 风险 |
|---|---|---|---|
| UI | Jetpack Compose + Material 3，单 Activity | 动画（覆盖推入）、手势、IME 支持最好 | 多 Activity：与动画方案冲突 |
| 导航 | Navigation3 + 自定义 `AnimatedContent` 过渡 | 覆盖动画需自控 transition | 纯状态机：页面少也能做，但返回栈要自己写 |
| 内核 | Kotlin 原生重写最小核心（agent loop + 12 工具） | 无 Node，冷启动与内存最优，天然静态化 | nodejs-mobile：体积 +30~50 MB、worker_threads 不确定 |
| PTC 沙箱 | QuickJS（`wang.harlon.quickjs:wrapper-android`） | 先例工程已验证；进程内、~1 MB | WebView/V8：重且异步桥复杂（接口已抽象，可后换） |
| TS 擦除 | 随包纯 JS 擦除器（擦除式、位置保持），宿主侧调用 | 与 dsh 的 `stripTypeScriptTypes` 语义一致 | Kotlin 自写擦除器：边界语法风险 |
| Shell | Termux bootstrap + 可执行文件走 `nativeLibraryDir` | 唯一 Play 合规路线，与"零下载"契合 | linker64 + LD_PRELOAD（兜底，非合规） |
| 终端 | `com.termux.termux-app:terminal-view` + 自写 PTY JNI | 先例已验证 | 自绘终端：成本高 |
| 网络 | OkHttp（LLM SSE + web 工具共用连接池） | 成熟；SSE 手写解析 | Ktor：亦可 |
| HTML→文本 | jsoup | 成熟、体积可控 | 自写正则：易错 |
| 持久化 | Room + DataStore + EncryptedSharedPreferences | 会话/工具事件量需要 Room | 纯文件：查询分页难 |
| 模糊 | `dev.chrisbanes.haze` | 先例在用，可关可降级 | `Modifier.blur`：不采样背景 |
| DI | 手写 ServiceLocator（P0）→ Koin（P1） | 页面少时不引注解处理 | Hilt：构建耗时 |

### 5.2 PTC 运行时

- 接口对齐 dsh 的 `CodeRuntime` 接缝（`language='typescript'`、`isolation='quickjs'`），实现可替换。
- 每次执行：新建 QuickJS context → 注入 `console` 与 `tools` 命名空间 → 擦除类型 → 包成 async 函数体 → eval → 收集返回值与日志 → 销毁 context。
- 异步桥：Kotlin 侧为每个工具调用创建 Promise；工具在协程中执行，完成后 resolve/reject；`run_code` 的取消信号同时 abort 所有在飞子调用（对齐 dsh：settle 时排空未启动项）。
- 超时：程序级 120 s（默认），单工具按 §2.1 的 timeoutMs；超时返回 `CODE_RUN_FAILED`。
- 防护：禁用 `fetch`/`XMLHttpRequest`（网络只能走 web 工具，便于审计与限流）；单次程序输出 64 KiB 截断；`console` 日志最多 500 行。

### 5.3 Shell 与 bootstrap

- **exec 通道（方案 A，已真机验证）**：把需要的可执行文件以 `lib*.so` 放进 `app/src/main/execLibs/arm64-v8a/`（显式 `packaging { jniLibs { useLegacyPackaging = true } }`）；首启解压 bootstrap 到 `files/usr` 后，把 `$PREFIX/bin` 下的真实文件**替换成指向 `nativeLibraryDir` 的符号链接**。共享库留在 `files/usr/lib`（dlopen/mmap 只需 execute），运行期统一导出 `LD_LIBRARY_PATH`。
  - **关键机制（PoC-3 实测）**：内核 execve 时**先解析符号链接**再对最终文件做 SELinux 检查，所以「app 私有目录里的符号链接 → nativeLibraryDir」**可执行**；而直接放在 app 私有目录的真实文件**不可执行**（PoC-1）。两条结论合起来构成当前设计。
  - **不需要 linker64，也不需要 termux-exec**：`linker64 <prefix>/bin/bash` 虽然可行，但实测 Termux 自带 termux-exec 预载**会被加载却不改写 exec**（三种变体 + `MODE=force` 均无效），故不做依赖；装的是「符号链接农场」。
  - **实测规模**：解压 3478 文件 / 706 ms，前缀改写命中 276 个文本文件，重建 1213 条符号链接，186 个 ELF 可执行文件进 execLibs（14.7 MB），整轮安装 **1042 ms**；端到端已验证 查看/创建/读取/写入/删除/查找/文本处理。详见 `docs/bootstrap-spike.md`。
- **合规不再是约束**（项目不上架）：A 仍是首选（花招最少、最稳、与"零下载"最契合）；若 PoC-1 在个别机型失败，**可直接切 B**（`linker64` + Termux 现成 termux-exec），不必为"Play 不允许"而迂回；C（targetSdk 28）也真正可用。
- **bootstrap 打包**：构建期 Gradle 任务下载官方 zip（校验 SHA-256）→ 解压 → 重写 shebang 与 `SYMLINKS.txt` 的 20 条绝对路径 → 裁剪（apt/dpkg/termux-am/man/doc/include/多余 terminfo）→ 重新打包为 asset。产物带 `MANIFEST.json`（版本 / prefix / 文件数 / SHA）。
- **运行期初始化**：`NotInstalled → Extracting → Verifying → Ready`；解压到 `files/usr-staging` 后**同分区 rename** 到 `files/usr`（原子）；版本变化即整体重解压（无快照、无增量）。解压在前台服务里跑并显示进度。
- **执行**：交互式走 PTY；工具走 `bash -lc`（非交互，超时 120 s、输出上限 64000 B、cwd = 工作区真实路径）；超时/取消 → SIGTERM → 300 ms → SIGKILL。
- **不做**：运行期下载、proot、快照、`apt install`、多版本共存。

### 5.4 网络层

| 用途 | 实现要点 |
|---|---|
| LLM 对话 | `POST /chat/completions` + `stream=true`；手写 SSE 解析（`data:` 行、多行 data 合并、`[DONE]`）；协程取消即断连接；重试策略：仅对连接错误重试 1 次（带退避），不重试 4xx |
| 工具调用 | PTC 模式下只需支持 `tools` 里的 `run_code` 单工具；`tool_calls` 增量拼接按 `index` |
| web_search | 可配置端点 + 密钥；30 s 超时；结果映射到 §2.3.3 的 schema；失败一律返回结构化 `error` |
| web_fetch | 只允许 http/https；DNS 解析后做 **SSRF 校验**；跟随重定向 ≤ 5；gzip/br 解压；正文走 jsoup 转文本；512 KiB 上限；非文本类型直接拒绝 |
| 预算 | 单轮 web 调用（search+fetch）合计上限 8，超出返回 `web_budget_exhausted` |
| 通用 | 统一 UA、统一超时表、统一结构化错误；所有网络失败都不抛异常穿透 agent loop |

### 5.5 数据模型

| 存储 | 表 / 键 | 关键字段 |
|---|---|---|
| Room | `conversation` | id、title、createdAt、updatedAt、workspaceId、model |
| Room | `message` | id、conversationId、role、content、reasoning、createdAt、seq |
| Room | `tool_call` | id、messageId、parentCallId（PTC 嵌套）、name、argsJson、resultJson、status、durationMs、isError |
| Room | `deliverable` | id、conversationId、relPath、absPath、source（present/write/edit/bash）、size、mtime、status |
| Room | `session_event` | id、conversationId、type、payloadJson、createdAt（喂消息反馈区） |
| DataStore | 通用 | theme_mode、enable_blur、workspace_id / workspace_path / workspace_form(W1/W2/W3) |
| DataStore | 终端 | default_timeout_ms=120000、max_timeout_ms=600000、max_output_bytes=64000、shell、login_shell、extra_env、enter_terminal |
| DataStore | 模型 | providers、selected_model、web_search_provider、web_search_max_uses=8 |
| DataStore | 高级 | max_parallel_tool_calls=10、ask_user_timeout_ms=600000、debug_show_raw_events |
| 加密 | Keystore / EncryptedSharedPreferences | LLM API Key、搜索 API Key（只存不显） |

### 5.6 关键接口

```kotlin
interface CodeRuntime {                      // 对齐 dsh 的 ctx.codeRuntime 接缝
    val language: String                     // "typescript"
    val isolation: String                    // "quickjs"
    suspend fun run(request: CodeRunRequest): CodeRunResult
}

interface Tool<A : Any, R : Any> {
    val name: String                         // 见 §2.1 总表
    val description: String
    val argsSchema: JsonSchema
    val timeoutMs: Long?
    val availability: (ToolAvailability) -> Boolean   // 运行期裁剪（§2.5）
    suspend fun execute(args: A, ctx: ToolContext): R
}

interface WorkspaceResolver {
    suspend fun current(): Workspace            // form: W1/W2/W3 + realPath?
    suspend fun bind(selection: WorkspaceSelection): BindResult   // path | safUri
    suspend fun revalidate(): WorkspaceHealth   // active | needsRebind | degraded
    fun bashEnvironment(): Map<String, String>  // PREFIX/HOME/PATH/LD_LIBRARY_PATH/TERM/TMPDIR
}

interface WebSearchProvider { suspend fun search(q: WebSearchQuery): WebSearchResult }

interface UserQuestionChannel { suspend fun ask(q: List<Question>, timeoutMs: Long): UserAnswer }

interface ExecBackend {                         // 屏蔽 nativeLibraryDir / linker 差异
    suspend fun run(spec: ExecSpec): ExecResult
    fun openPty(spec: PtySpec): PtyHandle
}
```

### 5.7 模块与目录结构（关键部分）

```
ADSH/
├─ build-logic/            PatchBootstrapTask / GenToolSdkTask / DebugLabelPlugin
├─ scripts/                fetch-bootstrap / gen-local-properties / build-debug / verify-bootstrap
├─ assets-src/bootstrap/   bootstrap-aarch64.zip（构建期下载，带 SHA 校验）
├─ app/
│  ├─ src/main/execLibs/arm64-v8a/   libbash.so librg.so libjq.so libfiles.so …
│  └─ src/main/assets/               bootstrap/usr.zip（打补丁后） sdk/sdk.d.ts
├─ core/
│  ├─ llm/         DeepSeekClient, SseParser, ChatModels, ModelCatalog
│  ├─ agent/       AgentLoop, Session, Message, PromptAssembler, ApprovalGate
│  ├─ ptc/         CodeRuntime(接缝), PtcRunner, TypeStrip, ToolSdk
│  ├─ tools/       Tool, ToolRegistry, BashTool, ReadTool, WriteTool, EditTool,
│  │               GlobTool, GrepTool, TodoTool, PresentTool,
│  │               WebSearchTool, WebFetchTool, AskUserTool
│  ├─ workspace/   WorkspaceManager, DirectoryBrowser, SafBinding, PathResolver, InstructionScanner
│  ├─ web/         WebSearchProvider, WebFetchClient, SsrfGuard, HtmlToText
│  └─ data/        Room(AppDatabase/DAO), SettingsStore, SecretStore
├─ runtime/
│  ├─ quickjs/     QuickJsRuntime, JsBridge, PromiseBridge
│  └─ termux/      TermuxRuntime, BootstrapInstaller, ExecBackend,
│                  src/main/cpp/{pty_bridge.c, CMakeLists.txt}
├─ ui/             components/{MessageList, ToolCard, Drawer, Composer, QuestionCard,
│                  CoverNav, CodeBlock, DiffView}
│                  pages/{conversation, feed, deliverables, preview, rightpanel,
│                         settings, terminal, workspace}
└─ docs/           ADSH-重构方案书.md, bootstrap-patching.md, exec-escape-spike.md
```

### 5.8 构建与命令行

```bash
./scripts/gen-local-properties.sh                 # Windows: .\scripts\gen-local-properties.ps1
./gradlew :app:prepareBootstrap                   # 构建期拉取 + 打补丁 + 裁剪 → assets
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a
./gradlew :app:installDebug                       # 或 adb install -r app/build/outputs/apk/debug/*.apk
./gradlew test && ./gradlew :app:lintDebug
```

- **debug 标签**：applicationId `com.adsh.app.debug`、应用名「ADSH Debug」、versionName `0.1.0-debug+<git>`、图标红色角标、关于页显示构建时间与 git hash。
- **release 预留**（自签名侧载用，不涉及 Play/AAB）：工作区内 `keystore/adsh-release.jks` + `keystore.properties`（两者都 gitignore，只提交 `.example`）；R8 需 keep QuickJS JNI 回调、`Java_com_termux_*`、Room 实体、`@Serializable`；`isShrinkResources=true`；ABI 拆分（arm64-v8a + x86_64 + universal）；bootstrap 以 `noCompress` 存放；目标 arm64 单 ABI < 120 MB。
- **开源交付物**：仓库根 `LICENSE`（Apache-2.0）、`README.md`（构建与运行说明）、`THIRD_PARTY_NOTICES.md`、`docs/third-party/SBOM.json`。
- **切换 release 的门槛**：P0 验收全过 + exec 通道在 Android 13/15 稳定 + 混淆后 PTC 闭环回归通过。

### 5.9 许可证与开源分发

**定位**：侧载自用 + 开源、不上架。这一条取消了所有"为 Play 而做的取舍"，同时把 GPL 义务从"风险"变成"必做但容易做"。

| 事项 | 决定 | 说明 |
|---|---|---|
| 项目自身许可 | **Apache-2.0**（建议；MIT 亦可） | 我们的 Kotlin 代码与随包的 GPL 二进制是 **fork/exec 的独立进程关系**，属于**聚合分发**（mere aggregation），不构成衍生作品 → 项目自身许可**不受 GPL 传染** |
| GPL-3.0+ 组件 | bash、coreutils、grep、sed、gawk、diffutils、tar | 未修改地分发 → 必须提供"对应源码"的获取途径 |
| 宽松许可组件 | ripgrep（MIT 或 Unlicense）、fd（MIT 或 Apache-2.0）、jq（MIT） | 只需保留版权与许可声明 |
| GPL-2.0 组件（若引入） | busybox、git（**git 已决定不引入**） | 同 GPL 义务 |
| **合规三件套** | ① `THIRD_PARTY_NOTICES.md`：逐组件名称/版本/许可/上游 URL；② `docs/third-party/SBOM.json`：机器可读清单（含 SHA-256）；③ `scripts/fetch-bootstrap.sh` 固定 release tag + sha256 → 任何人可复现同一份二进制 | 因为分发的二进制与上游 Termux release **完全一致**，对应源码就是同版本包的源码；三件套构成可复现的获取路径 |
| 仓库卫生（.gitignore 必含） | `local.properties`、`keystore.properties`、`*.jks`、`assets-src/bootstrap/*.zip`（32 MB，靠脚本拉取）、`build/`、`.gradle/`、`.cxx/` | 不把密钥与大二进制塞进 git |
| 隐私说明（README 写明） | API Key 只存本机（Keystore / EncryptedSharedPreferences）；**不采集遥测**；网络只访问用户配置的 LLM/搜索端点，以及模型通过 `web_fetch` 显式抓取的公网 URL | 开源项目的信任基础 |

---

## 6. MVP 开发步骤

### M0 — 技术验证（1 周，先做，决定 D3）

| 步骤 | 做什么 | 验收 |
|---|---|---|
| PoC-1（半天） | 静态 ripgrep（15.2.0 musl）改名 `librg.so` 放 `execLibs` + 显式 `useLegacyPackaging=true`；真机 exec | Android 13/15 都能跑起来；`unzip -v` 确认 APK 内 .so 是 `Defl` 而非 `Stored` |
| PoC-2 | 照抄 Termux `termux.c` 建 PTY JNI，跑起 shell，读写与控制窗口尺寸 | 交互终端可用；改 rows/cols 后触发 SIGWINCH |
| PoC-3 | 解压裁剪后的 bootstrap，重写 shebang 与 20 条绝对符号链接，处理 RUNPATH | `$PREFIX/bin/bash -lc 'ls'` 可用；`termux-am` 已剔除 |
| PoC-4 | 申请"所有文件访问"，验证 shell 子进程能 `cd /storage/emulated/0` 并读写 | 通过；记录拒绝授权时的降级行为 |

### M1 — 骨架与 shell（1 周）

工程骨架（多模块 + 约定插件 + 版本目录 + 脚本）→ `PatchBootstrapTask` → `TermuxRuntime`（解压/环境/执行/超时/截断/取消）→ `bash` 工具。
**验收**：单测覆盖 bash 工具的四类失败路径；真机能跑 §3.5 的前 6 条断言。

### M2 — 对话闭环（1 周）

DeepSeek 客户端（SSE + 取消 + 计费）→ AgentLoop → PromptAssembler → Room 持久化 → 会话页（消息列表 + 输入区 + 键盘）。
**验收**：能连续多轮对话；杀进程重启后历史仍在；键盘弹出时输入框上移、光标可见。

### M3 — PTC 与工具（1 周）

`CodeRuntime`（QuickJS + 擦除 + 桥）→ `GenToolSdkTask` → 先实现 6 个工具（bash/read/write/edit/glob/grep），再补 todo/present/web_search/web_fetch/ask_user_question。
**验收**：模型产出程序 → 调 bash 与 web_search → 回填 → 最终回答；嵌套子调用在 UI 可见；每个工具有单测。

### M4 — 主页面与面板（1 周）

覆盖导航组件（`CoverNav`）→ 四个面板（消息反馈 / 交付物 / 文档预览 / 右栏）→ 抽屉（视差 + 虚化 + 手势）→ 设置页三入口 + 终端页。
**验收**：§4.11 的动画与返回规格逐条通过；四面板入口可达、返回可预期。

### M5 — 工作区与联调（1 周）

目录浏览器 + SAF 兜底 → 三形态切换与校验（`test -w`）→ 提示词动态注入 → 失效降级 → 端到端联调与真机打磨。
**验收**：§3.5 断言表全绿；未绑定/已绑定/失效三态行为正确；提示词中 `cwd` 随绑定变化有可断言测试。

### 里程碑总量

6 周（M0–M5），单人；M0 结论决定 D3 走 A 还是兜底 B/C。

---

## 7. 后续优化项

| 优先级 | 项目 | 触发条件 |
|---|---|---|
| P1 | 审批闸门 UI（三档权限预设） | 开始把 App 用于真实项目时 |
| P1 | 终端多标签 + 快捷键条（Esc/Ctrl/Tab/方向键） | 终端使用频率上来后 |
| P1 | 文档预览：语法高亮、搜索、行跳转、JSON/CSV 视图 | 预览大文件变频繁 |
| P1 | 交付物：bash 产出扫描（mtime diff）、"仅最终结果"筛选 | 文件数变多 |
| P1 | 宽屏右栏常驻、平板布局 | 有平板/折叠屏 |
| P1 | 多会话管理（置顶/重命名/搜索）、消息重试与编辑 | 会话数 > 20 |
| P1 | 上下文压缩（工具结果剪枝 → 摘要） | 单会话 token 逼近上限 |
| P1 | 成本统计页、连接重试与断流恢复、崩溃日志导出 | 日常使用后 |
| P1 | **开源工程化**：LICENSE、README（构建说明）、THIRD_PARTY_NOTICES + SBOM、可选 CI 构建 | 首次公开仓库前（§5.9） |
| P2 | release 流水线（签名/混淆/压缩/Baseline Profile/ABI 拆分） | P0 验收全过后 |
| P2 | 附件与图片、文档解析（PDF/DOCX） | 有输入材料需求 |
| P2 | skill 静态支持、后台任务（jobs 子集） | 明确需要时 |
| P2 | 主题自定义、多语言、启动性能（startup profile） | 发布前 |
| P2 | 体积优化（bootstrap 再裁剪、按需分包） | 安装包超预期 |

---

## 8. 关键风险与默认处理方式

| # | 风险 | 默认处理 |
|---|---|---|
| 1 | **exec 通道失败**（`useLegacyPackaging` 未生效 → `errno 13`，或个别机型 SELinux 收紧） | M0 的 PoC-1 先钉死；显式设置并用 `unzip -v` 核对；兜底 linker64 + Termux 现成 termux-exec；最末 targetSdk 28 |
| 2 | bootstrap 重定位遗漏（shebang / 符号链接 / RUNPATH） | 构建期全树扫描 + 补丁后校验 + `MANIFEST.json`；真机跑 §3.5 断言表 |
| 3 | QuickJS 性能或内存不足（长程序、大输出） | 程序与输出双上限；接口已抽象为 `CodeRuntime`，必要时换 WebView/V8 沙箱，不改上层 |
| 4 | TS 擦除正确性（可擦除语法边界） | 用成熟擦除库；`enum` / `namespace` 等**宿主侧提前报错**（与 dsh 行为一致） |
| 5 | 真实路径不可得（第三方 provider / 用户拒绝"所有文件访问"） | 降级为 W1 或 W3，UI 明确告知"bash 只能操作私有目录"；不生硬报错 |
| 6 | **GPL 分发义务**（bash/coreutils/grep/sed/tar/findutils = GPL-3.0+）。已确认开源，属必做但容易做 | 按 §5.9 三件套（NOTICES + SBOM + 固定 tag/sha256 的拉取脚本）履行；项目自身许可用 Apache-2.0（聚合分发，不受传染） |
| 7 | APK 体积（bootstrap 32.8 MB / 解压 90 MB） | 裁剪 apt/dpkg/man/doc/include/多余 terminfo；ABI 拆分；R8；目标 arm64 < 120 MB |
| 8 | 16 KB 页对齐（Play 2025-11-01 起对 Android 15+ 的硬要求） | 用 NDK r27+/29 构建 PTY 桥；`readelf -l` 核对 LOAD 段 Align 为 0x4000 |
| 9 | web 工具滥用 / 打爆上下文 | 单轮预算 8；结果条数上限；fetch 512 KiB 截断；SSRF 防护；失败一律结构化返回 |
| 10 | ask_user 无人应答导致挂起 | 默认 10 分钟超时；超时/跳过返回结构化错误；App 不在前台直接 fail-closed |
| 11 | 长任务被杀 | 前台服务 + 通知；轮次粒度可恢复（已完成的工具结果落库） |
| 12 | ~~Play 审核~~ **已消除**：项目不上架（侧载自用 + 开源），`MANAGE_EXTERNAL_STORAGE` 无用途审查 | 仅需在应用内清晰说明用途与撤销方式 |

---

## 附录 A：dsh 关键事实（源码级，供对齐用）

| 事实 | 证据 |
|---|---|
| PTC 是**呈现层开关**：取值 `native / ptc / both`，部署默认 `native`，preset 用 `dsh-agent-tool-presentation` 的 `{mode: ptc}` 覆盖；ptc 下只有 `run_code` 可直呼 | `dsh-tools/lib`、`dsh-agent-tool-presentation/lib` |
| 代码执行是**可替换接缝** `CodeRuntime.run()`（`language='typescript'`、`isolation` 可换） | `dsh-code-runtime/lib/types/index.d.ts` |
| 现实现用 Node `stripTypeScriptTypes` 宿主侧擦除（仅可擦除语法），包成 async 函数体后交 worker | `dsh-code-runtime-worker-thread/lib/worker.cjs` |
| 子调用 id = `<callId>:ptc:<n>`，并行上限 `maxParallelSubCalls` 默认 10；过程只写日志事件（`tool/ptc-dispatch-start` / `tool/ptc-dispatch`），**不进模型上下文** | `dsh-tools/lib`、`dsh-tools/lib/types/ptc.js` |
| 四个预设 `standard / ptc / minimal / cordis`；ptc 与 standard 仅两处差异（`workflow` disabled + `tool-presentation`） | `dsh-agent-presets/presets/*` |
| 工具包 20 个（bash/fs/fs-search/str-replace-editor/jobs/goal/present/skill/web/todo/subagent*/workflow/ralph/cordis/pwsh*/ask-user/call-timeout-policy） | `dsh-tool-*` |
| 命令超时在 **`shell` 设置命名空间**：`timeoutMs=120000`、`maxTimeoutMs=600000`、`maxOutputBytes=64000`；UI 在 Settings→插件→Shell 卡片 | `dsh-bash-local/lib`、`dsh-shell/lib`、`dsh-client-ui-settings-plugins/lib/client.js` |
| 提示词装配器 `dsh-system-prompt`（按 order 拼接、`{{var}}`）；`cwd` 唯一来源是 `session.header.cwd`；AGENTS.md 链 `maxBytes=65536` | `dsh-system-prompt/lib`、`dsh-agent-loop/lib`、`dsh-agent-instructions/lib` |
| 「输入框模式选择」= `InputBar` 里 `.modes` 的**访问模式下拉 `PermissionSelect`**（read-only/workspace-write/danger-full-access），同容器另有 `PlanChip`；`AgentPresetSeat` 不在 composer | `dsh-client-ui-conversation/lib/client.js`、`dsh-client-ui-plan/lib` |
| dsh **无抽屉**：左栏是 CSS Grid 持久列，<1024px 收成 56px 轨道；聊天列表 `ChatNodeList` **非虚拟化** | `dsh-client-ui-layout/lib/types/client/columns.d.ts`、`dsh-client-ui-chat/lib` |
| dsh 自身**没有任何运行期下载**（ripgrep/原生插件都在 npm 安装期） | `@vscode/ripgrep/lib`、`dsh-subprocess-local/scripts` |

## 附录 B：Android 平台关键约束

| 约束 | 结论 | 来源 |
|---|---|---|
| W^X | Android ≥ 10 且 targetSdk ≥ 29：`neverallow ... app_data_file:file execute_no_trans` → 不能 exec 自己 app data 里的文件（dlopen/mmap 仍允许）；targetSdk ≤ 28 落在 `untrusted_app_27` 仍可 exec | AOSP `app_neverallows.te` |
| 例外 | `nativeLibraryDir` 下（`apk_data_file`）允许 exec → 唯一 Play 合规通道 | `app.te` |
| 打包 | 必须显式 `useLegacyPackaging = true`（AGP 对 minSdk ≥ 23 默认"不压缩不解压"，会导致 `errno 13`）；可执行文件必须以 `.so` 结尾 | AGP 文档 + 实证 PR |
| bootstrap 实测 | zip 32,799,409 B；解压 90,081,197 B / 3773 条目；顶层即 `$PREFIX`；`SYMLINKS.txt` 1213 行含 20 条绝对路径；274 个文本文件含 `/data/data/com.termux` 字面量；`PT_INTERP` 已是 `/system/bin/linker64`（不需 patchelf），`DT_RUNPATH` 写死但可被 `LD_LIBRARY_PATH` 屏蔽 | 实测 |
| 静态链接 | **不能绕过 SELinux**（检查的是被 execve 的文件本身）；价值仅在于零依赖 | 实测 |
| SAF | 官方无 API 拿绝对路径；**即便解析出路径，shell 也无 VFS 权限**（ContentProvider URI 权限 ≠ 文件系统权限）→ 不能用于 shell | AOSP + issue 138601434 |
| 共享存储 | `MANAGE_EXTERNAL_STORAGE` 是让 shell 真正操作用户目录的唯一实用解；Play 仅限特定核心用途 | 官方文档 |
| PTY | 用 `open("/dev/ptmx")` + `grantpt/unlockpt/ptsname_r` + `fork()` + `TIOCSWINSZ`；rows/cols 变化内核自动发 SIGWINCH | Termux `termux.c` |
| 无 proot 的边界 | 无 mount / chroot / namespace / UID 变更；`sudo`、`mount`、块设备、`/data/data/<other>` 一律不可用 | 平台约束 |

---

## 结语：定稿状态与下一步

本轮确认的四项决策（正文已全部固化）：

| # | 决策 | 结论 |
|---|---|---|
| 1 | exec 通道 | **方案 A**（nativeLibraryDir）为准；PoC-1 失败即切 B（termux-exec），再不行切 C。不上架 → B/C 无非合规顾虑 |
| 2 | 工作区形态与引导 | **W1 默认 → 引导绑 W2（真实路径）→ 拒绝退 W3（导入导出）**，按此实现 |
| 3 | git | **不做**：不随包、命令不可用；已写入 SDK 的"明确不可用"清单，避免模型空试 |
| 4 | 分发定位 | **侧载自用 + 开源，不上架**：不做 Play/AAB 相关工作；GPL 义务按 §5.9 三件套履行；项目自身许可建议 Apache-2.0 |

> 方案正文（§1–§8 + 附录）已按以上结论固化，**可直接进入 M0**。



