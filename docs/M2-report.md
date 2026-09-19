# M2 进展报告：LLM 客户端、Agent 循环、持久化、会话页

日期：2026-09-14　状态：完成并通过真机验证（流式 + 键盘 + 持久化）

## 一、交付

| 组件 | 位置 | 说明 |
|---|---|---|
| `SseParser` | `core/llm/` | 增量 SSE 解析（多行 data 拼接、注释/event/id 忽略、EOF 收尾），纯逻辑可单测 |
| `LlmClient` | `core/llm/` | OpenAI 兼容流式客户端：HttpURLConnection + `Flow<ChatEvent>`，支持取消、HTTP 错误体回传、usage 解析、tool_call 增量拼接（M3 用） |
| `ChatModels` | `core/llm/` | 消息 / 请求 / 事件模型（kotlinx.serialization） |
| `AdshDatabase` + `ConversationRepository` | `core/data/` | Room 会话与消息表（自动改名、级联删除、按会话查询） |
| `SettingsStore` | `core/data/` | baseUrl / apiKey / model / 回环开关 / 系统提示词后缀；**未配密钥自动进回环模式** |
| `AgentLoop` | `core/agent/` | 单轮：写用户消息 → 组装 system+history → 流式收集 → 落库助手消息（异常也保住已生成部分） |
| `ChatViewModel` + `ChatScreen` | `ui/` | Compose 会话页：消息列表（自动吸底）、流式气泡、错误条、输入区、**imePadding 键盘适配**；启动后台预热 Termux 运行时 |
| `MainActivity` | `app` | `ComponentActivity` + `enableEdgeToEdge()` + Compose；debug 下顶栏保留「技术自检」入口 |

## 二、验证证据

**1) 单元测试（`:app:testDebugUnitTest` 通过）**
```
SseParserTest：单事件 / 多行 data 拼接 / 注释与 event/id 忽略 / EOF 收尾
                content delta / reasoning + finish_reason / usage / tool_call 增量
```

**2) 键盘适配（docs/screenshots/m2-keyboard.png）**
输入框随键盘上移，光标可见、发送键可见、内容不被遮挡。

**3) 端到端流式（docs/screenshots/m2-after.png）**
输入 `hello-adsh` → 用户气泡（右，primaryContainer）+ 助手气泡（左，surfaceVariant）渲染回环流式响应，输入框已清空。

**4) 持久化（docs/screenshots/m2-persist.png）**
`am force-stop` 后重启 App，两条消息仍在（Room 读回），`files/usr/MANIFEST.properties` 存在（运行时已预热）。

## 三、关于回环（mock）模式

没有 API Key 时 `ProviderConfig.mock = true`，`LlmClient` 用本地分片流模拟 SSE 增量输出。这不是玩具：
它让 UI、取消、落库、自动吸底这些链路可以**在无密钥情况下被端到端验证**，也是后续 CI/回归的基础。
配置 API Key 后自动切真实模型（顶栏副标题会从「回环演示」变为模型名）。

## 四、待你决定的一件事（不阻塞）

**真实模型链路需要一把 DeepSeek API Key**（设置里可填，M4 才有设置页；当前可通过 `adb` 或临时入口写入）。
M3 的 PTC 闭环（模型产出 `run_code` 程序 → 工具执行 → 回填）**建议用真实模型验证一次**，
因为回环模式无法覆盖"模型写出的程序长什么样"这类真实行为。你方便时给一把 key 即可；
不给也不影响 M3 开发（单测 + 回环可覆盖大部分逻辑），只是最终验收会缺一环。

## 五、下一步（M3）

1. QuickJS 运行时（`wang.harlon.quickjs`）+ 工具桥（Kotlin 异步回调 ↔ JS Promise）。
2. 可擦除 TypeScript 的类型擦除（宿主侧）。
3. `run_code` 工具 + 静态 SDK 存根生成（编译期）。
4. 补齐工具到 12 个：`todo`、`present`、`web_search`、`web_fetch`、`ask_user_question`。
5. Agent 进入 PTC 模式：只暴露 `run_code`，嵌套子调用进 UI 与日志（不回灌上下文）。
