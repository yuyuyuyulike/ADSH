# M3 进展报告：PTC 运行时、12 个静态工具、PTC 主循环

日期：2026-09-14　状态：核心完成并通过真机验证

## 一、本轮交付

| 组件 | 位置 | 说明 |
|---|---|---|
| `QuickJsRuntime` | `core/ptc/` | QuickJS 沙箱；**泵 + 同步宿主桥**的执行模型（见 §二） |
| `RunCodeTool` | `core/tools/` | PTC 唯一入口；程序内 `await tools.<name>(args)` 调其它工具 |
| `AgentLoop`（重写） | `core/agent/` | PTC 主循环：只暴露 `run_code` → 执行 → tool 消息回填 → 下一轮（上限 6 轮） |
| 12 个工具 | `core/tools/` | bash / read / write / edit / glob / grep / todo / present / web_search / web_fetch / ask_user_question / run_code |
| `UserQuestionChannel` + 提问卡 | `core/tools/`、`ui/` | 工具挂起，UI 渲染选项卡，作答后恢复；超时/跳过返回结构化错误 |
| Room v2 | `core/data/` | 消息表增加 `toolCallsJson` / `toolCallId` / `name`，支撑 PTC 多轮往返 |

## 二、关键设计：为什么 PTC 用「泵 + 同步桥」

真机探针（`ADSH_M3`）先摸清了 QuickJS 包装库的能力边界：

| 探针 | 结果 | 含义 |
|---|---|---|
| `1+2` / 可选链 / 空值合并 | 正常 | ES2020 语法可用 |
| `(async () => 7)()` | 返回不透明对象 `json={}` | **单次 evaluate 内 promise 不会 settle** |
| `Promise.resolve(1).then(() => { __hit = 42 })` 后立刻读 `__hit` | `0` | 同一次 evaluate 内 microtask 未执行 |
| 再 evaluate 一次后读 `__hit` | **`42`** | **microtask 在下一次 evaluate 时被排空** |

（该包装库确实没有 `executePendingJob`，与源码注释一致。）

于是采用：程序包进 async IIFE → 结果写入 `globalThis.__adsh_state` → **反复 evaluate 空表达式泵动 microtask**，直到 settled 或超时；
宿主函数 `__adsh_call__` 是**同步阻塞**的（内部 `runBlocking` 调工具），所以 `await tools.x(...)` 在下一个泵周期即可继续。

## 三、真机验证（ADSH_M3PTC）

**PTC 闭环**（回环提供方发出真实 `run_code` 调用）：
```
Finished(tool_calls)
ToolStarted(run_code) args={"program":"const r = await tools.bash({...}); console.log(...); const files = await tools.glob(...); return {...}"}
ToolFinished(run_code, isError=false)
  --- console --- 'bash -> mock-ptc-ok /storage/emulated/0/adsh-ws --- exit=0'
  --- result --- {"firstLine":"mock-ptc-ok","files":"notes/hello.md"} --- done in 106ms ---
第二轮：模型看到工具结果 → 输出最终答复
```
落库形态：`user` → `assistant(toolCalls=true)` → `tool(toolCallId=call_mock_1, name=run_code)` → `assistant(最终)`。

**12 个工具清单与抽查**：
```
bash, read, write, edit, glob, grep, todo, present, web_search, web_fetch, ask_user_question, run_code  （共 12 个）
todo   -> OK: 已更新任务列表（2 项，1 项完成）： - [x] 验证 PTC 闭环 - [/] 补齐 12 个工具
present-> OK: 已登记交付物：hello.md
web_search(未配置) -> ERR: {"error":"web_search_unavailable","reason":"no_api_key","retryable":false}
web_fetch(example.com) -> OK: {"url":"https://example.com","statusCode":200,"contentType":"text/html","content":"Example Domain ..."}   ← 曾成功抓取真实页面
web_fetch(127.0.0.1) -> ERR: 命中间私网/环回地址，已拦截：127.0.0.1                                   ← SSRF 防护
ask_user_question(自动应答) -> OK: q1: 自动应答 A                                                  ← 通道往返
```

**提问卡 UI**：`docs/screenshots/m3-question.png`（选项、跳过/提交按钮均渲染正常，选中态可见）。

## 四、过程中发现并修掉的两个真机问题

1. **缺 `INTERNET` 权限**：回环模式掩盖了它；`web_fetch` 报 DNS 解析失败才暴露——**真实 LLM 调用同样会失败**。已补 `INTERNET` + `ACCESS_NETWORK_STATE`。
2. **`InetAddress.getAllByName` 没有超时**：SSRF 校验里直接调用会在 DNS 异常时长时间挂起（真机复现：卡住 >40s）。已改为 **5s 限时**，超时返回 `{"reason":"dns_timeout","retryable":true}`。

## 五、与 dsh ptc 的两处有意收窄（已记录，P1 可补）

| 项 | dsh | 当前 ADSH | 原因 / 补齐路径 |
|---|---|---|---|
| 程序语言 | 可擦除 TypeScript（宿主侧 strip） | **纯 JavaScript**；用 TS 类型标注会得到明确的语法错误提示 | 该 QuickJS 无 WASM（amaro 不可用）、跑 Babel standalone 需额外 2.7MB 资源与首次解析开销。P1 可引入 Babel standalone 作为可选擦除器 |
| 子调用并发 | 并行（`maxParallelSubCalls` 默认 10） | **串行**（同步桥） | 单次 evaluate 内 promise 不 settle，真异步需要自己驱动 job 队列。P1 可换成异步桥 + 泵循环 |

其余 PTC 语义保持一致：只有 `run_code` 对模型可见；嵌套子调用只进 UI 与日志、不回灌上下文；程序返回值与 `console` 日志构成 tool 结果。

## 六、下一步（M4）

四面板（消息反馈 / 交付物 / 文档预览 / 右栏）+ 抽屉（视差+虚化+手势）+ 设置页三入口 + 终端页；交付物面板直接消费 `DeliverableRegistry`，消息反馈区消费工具事件。
