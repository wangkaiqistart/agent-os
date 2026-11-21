# US1 演示：在 CLI 中实时看到执行过程

- 日期：2026-11-21
- 方式：脚本化的 `AgentService` 按真实顺序投递事件，驱动真实的 `ChatCommand` 与 `TerminalTurnRenderer`；不依赖真实模型
- 命令：`mvn -B -ntp -pl AgentOS-channel-cli,AgentOS-cli -am test`
- 原始输出：`AgentOS-cli/target/007-demo/chat-streaming.txt`、`chat-tools.txt`

## 文字边生成边显示（`ChatCommandStreamingTest`）

脚本每隔 300ms 投递一段文字。终端输出（回答只出现一次，整轮结束后不再重复打印）：

```text
第一段第二段第三段

--- 每段文字从到达到显示的时间差 ---
第一段: 192 µs
第二段: 140 µs
第三段: 78 µs
```

SC-001 要求 1 秒内显示，实测不到 1 毫秒。

## 工具进度与终态行（`ChatCommandStreamingTest.directToolResultIsStillPrinted`）

工具直接返回结果（`returnDirect`）时，最终回答没有被流式显示过，命令照常打印：

```text
查一下
  ⚙ weather  {}
  ✓ weather  成功 · 5ms · sunny
  ── 完成 · 模型调用 1 次 · 工具 1 次 · 0ms
DIRECT_RESULT
```

## 其他已验证的输出规则（`TerminalTurnRendererTest`，8 个用例）

| 规则 | 结果 |
| --- | --- |
| 工具开始前补换行，不与文字挤在一行 | 通过 |
| 参数 / 结果摘要去掉换行、限 80 个字符，超出以 `…` 结尾 | 通过 |
| `approval.*` 不重复打印（确认框由 006 的输入 broker 打印） | 通过 |
| 只有文字、没调用工具的成功轮次不打印终态行 | 通过 |
| 已显示部分文字后失败：先打印"（回答未完成）"再打印失败行 | 通过 |
| 取消且有副作用工具可能已生效：终态行带"该操作可能已生效" | 通过 |
| 模型文字里的控制字符（如 `ESC[31m` 的 ESC）不写到终端 | 通过 |
| `--message` 模式不打印文字，工具行与终态行写标准错误 | 通过 |

## `--message` 输出不变（`ChatCommandTest.singleMessageKeepsStdoutAndWritesToolProgressToStderr`）

标准输出只有 `answer` 一行（与 007 之前逐字节一致）；`⚙ http_get`、`✓ http_get  成功`、`── 完成 · 模型调用 2 次 · 工具 1 次` 写在标准错误；退出码 0。

## 确认提示的位置

确认框仍由 006 的 `TerminalInputBroker` 打印，格式不变。它出现在 `⚙ <工具名>` 行之后、`✓/✗ <工具名>` 行之前：事件顺序由 `RunEventContractIntegrationTest`（`approval.*` 位于 `tool.started` 与 `tool.finished` 之间）保证，渲染器在工具开始行之前结束未换行的文字，见 us3-demo.md 的"带确认的工具调用"摘录。

## 真实模型手工演示（2026-11-21，`primary` 中转接口，交互模式 `chat --profile weather --new`）

由项目方在终端实际运行，下面是对输出的整理。

| 项 | 结果 |
| --- | --- |
| 流式调用经中转接口 | 通过。审计行为 `mode=STREAM`，第 1 次 `finish=TOOL_CALLS toolCalls=1`（流式工具调用可用），第 2 次 `finish=STOP`；两次都有用量（`tokens=5045/44`、`8081/108`） |
| 文字边生成边显示 | 通过。第 2 次调用的回答在 `模型调用审计` 日志行出现之前就已打印在终端上 |
| 顺序 | 通过：`⚙ http_get` → 授权框 → 输入 `1` → `✓ http_get 成功 · 9.4s` → 回答 → `── 完成 · 模型调用 2 次 · 工具 1 次 · 23.7s` |
| 回答只出现一次 | 通过 |
| 授权等待不计入整轮时间 | 通过。`budgetPausedMs=5919`，与授权等待 5.9 秒一致 |
| 同一会话的第二轮 | 工具和授权框同样出现；`historyMessages` 从 2 增到 6，历史按轮次带入 |

**第二轮的失败**：回答已经完整打印后，写 `llm_calls` 审计时远程 PostgreSQL 读超时（`SocketTimeoutException`，审计单次等待 2 秒），轮次按既有规则以 `FAILED`、持久化未确认结束。同一次运行里应用启动用了 38.5 秒（当天早些时候是 6 秒），说明到共享库的网络当时很差。这是 004 以来的审计规则（审计写不进去就不能宣称成功），不是 007 引入的。

终端上这次失败的显示暴露出一个重复：渲染器已经打印 `── 失败：审计写入未确认，操作可能已经执行`，`chat` 命令又打印了一遍同样的说明。已修复：渲染器已经说明原因时，命令只补 `持久化未确认，turnId=…`，对应测试 `ChatCommandTest.interactiveUnconfirmedFailureDoesNotRepeatTheTerminalLineText`。
