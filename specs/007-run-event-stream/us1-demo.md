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

## 真实模型手工演示

NOT_RUN：本次没有在真实模型下运行 `java -jar AgentOS-boot/target/AgentOS.jar chat`。
