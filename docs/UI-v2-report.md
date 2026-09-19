# UI v2 —— 主界面对齐 DeepSeek App、输入框对齐 dsh

> 触发：2026-09-14 用户反馈。本文记录改动、**真机复现到的 3 个 bug**、验证证据与遗留项。
> 说明：用户随反馈发来的两张截图属于另一个已安装应用 `com.dsharnessmobile.shell`（DSH 的 Android 壳，
> 本机并无其源码）；但反馈里描述的三类问题在本项目 `com.adsh.app.debug` 上**同样存在**，且已逐条复现并修复。

---

## 一、改动前的三个真问题

### B1 键盘弹出后，输入框与键盘之间空出一大段（用户原话 1:1 复现）

- **根因**：`MainActivity` 没有声明 `android:windowSoftInputMode`，默认的 `adjustUnspecified` 被系统判定为
  **adjustPan**：键盘弹出时窗口整体上移；与此同时 Compose 又按 `imePadding` 把输入框上移了一次 → 双重位移。
  一输入文字布局重算，pan 被重置，于是「输完字就恢复正常」。
- **证据**：`docs/screenshots/ui2-bug-adjustpan.png`（顶栏被顶出屏幕，输入框悬在屏幕中间）。
- **修复**：显式 `android:windowSoftInputMode="adjustResize"`；输入区改用
  `WindowInsets.ime ∪ navigationBars` 的**并集**（取 max，天然避免导航栏重复内边距）。
- **验证**：`docs/screenshots/ui2-keyboard.png` —— 输入框紧贴键盘，顶栏在位，中间零间隙。

### B2 返回键收不起键盘

- **根因**：`AppRoot` 里留了一个「根页面交还系统」的空 `BackHandler(enabled = true) {}`，
  它并不「交还」——返回事件被它吞掉了。
- **修复**：删除该占位处理器，返回键恢复系统语义（先收键盘、再退出）。
- **仍然存在**：左边缘滑动打开抽屉会被系统返回手势抢走（平台行为，见「遗留」）。

### B3 真实模型：要么 400，要么「什么都不返回」

- **现象 A**：`HTTP 400 … messages[6]: missing field \`type\``。
- **根因 A**：kotlinx.serialization 默认 `encodeDefaults = false`，于是
  `ToolCall.type = "function"` 与 `ChatRequest.stream = true` **这两个带默认值的字段根本没进 JSON**。
- **现象 B**：修掉 A 之后请求成功（HTTP 200 / text/event-stream）但界面没有任何回复。
- **根因 B**：配置的 `deepseek-flash` 是**推理模型**，前若干包只有 `reasoning_content`、`content` 为 null；
  而客户端只把 content 当答案，空回复又被静默丢弃 —— 用户看到的就是「毫无反应」。
- **修复**：
  1. LLM 客户端 `encodeDefaults = true`（保证 `type` / `stream` 一定发出）；
  2. 兼容**非 SSE** 的整包 JSON（网关忽略 `stream=true` 时用 `choices[0].message`）；
  3. 流结束若「一个事件都没解出来」，直接抛错并带出原始首行，杜绝静默空流；
  4. 请求历史做**成组校验**：assistant 必须至少有 content 或 tool_calls；带 tool_calls 的必须紧跟**完整**的
     tool 结果；孤儿 tool 消息丢弃。此前库里一条脏记录（取消/崩溃残留）会让整个会话**永久 400**。
- **验证**：`ADSH_LLM` 日志 `HTTP 200 type=text/event-stream`；
  `docs/screenshots/ui2-reasoning.png` 展示折叠的「已思考 ⌄」+ 正文。

---

## 二、界面改动

| 区域 | 改动前 | 改动后 |
|---|---|---|
| 顶栏 | 「ADSH」+ 模型·工作区副标题 + 4 个图标（通知/列表/信息/工具）| ☰ + 会话标题（居中）+ ⊕；其余入口全部收进抽屉与输入框菜单 |
| 空态 | 一片空白 | 「deepseek HARNESS」字标 + 「你好，有什么我能帮你的吗？」居中（对齐 DeepSeek App）|
| 输入框 | OutlinedTextField + 纸飞机图标 | 圆角卡片 + dsh 语义占位「发消息或创建任务，/ 调用指令」+ ＋ 菜单 + 圆形上箭头（有内容变蓝、发送中变停止）|
| 消息 | 全部套气泡、还带「ADSH」小标签 | 用户＝右侧蓝色气泡；助手＝**无气泡纯文本**；推理折叠为「已思考 ⌄」；工具结果折叠为可点开的等宽块 |
| 抽屉 | 标题 + 一串并列按钮 | 字标 + 「＋ 新会话」+「面板」分组（终端/交付物/消息反馈/运行信息）+ 工作区 + 底部设置 |
| 主题 | M3 默认紫 | DeepSeek 蓝 `#4D6BFE` 中性色板；新增 `values-night` 主题（修掉深色模式下状态栏图标与窗口底色不一致）|
| 指令 | 无 | 输入 `/` 弹出 `/new` `/term` `/files` `/feed` `/info` `/settings`，选中即跳转——让占位文案的承诺是真的 |

涉及文件：`ui/ChatScreen.kt`（重写）、`ui/AppRoot.kt`（抽屉与调用点）、`ui/Brand.kt`（新增字标）、
`ui/theme/Theme.kt`、`res/values{,-night}/themes.xml`、`AndroidManifest.xml`。

---

## 三、验证

| 项 | 结果 |
|---|---|
| 命令行构建 | `./scripts/build-debug.sh` ✅ BUILD SUCCESSFUL |
| 回归自检 | `ADSH_ACCEPT` **12 / 12** ✅（并已把自检内部强制回环，不再受「是否配了密钥」影响）|
| 真实模型 | 真实 Key + `deepseek-flash`：HTTP 200 流式；推理与正文均落库 ✅ |
| 真实模型下的 PTC | 模型产出 `run_code` → 本地执行 → 结果回填 → 产出最终答复，全链路走通 ✅ |
| 键盘 | 顶栏在位、输入框紧贴键盘、发送键随内容启用 ✅ |
| 截图 | `ui2-final-empty`（空态）、`ui2-keyboard`（键盘）、`ui2-drawer`（抽屉）、`ui2-commands`（斜杠指令）、`ui2-reasoning`（推理+正文）、`ui2-bug-adjustpan`（修复前的 pan bug 证据）|

---

## 四、遗留（按优先级）

1. 左边缘滑动开抽屉仍会被系统返回手势抢走；稳定入口是左上角 ☰（可用 `setSystemGestureExclusionRects` 争取，属平台博弈）。
2. 抽屉还没有**会话列表**（多会话管理仍是 P1）。
3. 消息级操作缺失：复制、重发、编辑、（用户消息）时间戳。
4. 占位文案只承诺了「/ 调用指令」；`@ 文件或对话` 未实现，故未写进文案。
5. 字标目前是文字排版（deepseek + HARNESS 徽标），未做图形 Logo 与动态取色。
