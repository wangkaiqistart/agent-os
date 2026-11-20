# US3 演示：所有入口共用一份可信的事件契约

- 日期：2026-11-21
- 方式：PostgreSQL（Testcontainers）+ 真实注入的 `ToolCallingManager` + 治理链 + 本地模型替身，不依赖真实模型
- 命令：`mvn -B -ntp -pl AgentOS-boot -am -Dtest='RunEventContractIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
- 原始输出：`AgentOS-boot/target/007-demo/run-events/*.txt`（每类场景一份，已脱敏）

## 9 类场景结果（取消场景在 US4 启用，见 us4-demo.md）

每类都通过：顺序语法（首条 `run.started`、末条唯一终态、`seq` 从 1 连续、消息 / 工具 / 交互成对）；终态与 `agent_turns.status` 一致；工具开始 / 结束事件对数等于 `tool_invocations` 行数；事件中不出现测试密钥。

| 场景 | 库内状态（错误码） | 终态事件 | 要点 |
| --- | --- | --- | --- |
| `01-success` | SUCCEEDED | `run.completed` | 共 7 条事件，工具 0 个 |
| `02-tool-failure` | SUCCEEDED（工具 FAILED） | `run.completed` | 共 11 条事件，工具 1 个 |
| `03-approval-denied` | SUCCEEDED（工具 DENIED，确认 DENY） | `run.completed` | 共 13 条事件，工具 1 个 |
| `04-unattended` | SUCCEEDED（工具 DENIED，确认 UNATTENDED） | `run.completed` | 共 13 条事件，工具 1 个 |
| `05-timeout` | TIMED_OUT（`TIMED_OUT`） | `run.failed` | 共 9 条事件，工具 1 个 |
| `06-max-iterations` | MAX_ITERATIONS（`MAX_ITERATIONS`） | `run.failed` | 共 6 条事件，工具 1 个 |
| `07-tool-limit` | SUCCEEDED（第 3 个工具 SKIPPED） | `run.completed` | 共 15 条事件，工具 3 个 |
| `08-truncated` | FAILED（`OUTPUT_TRUNCATED`） | `run.failed` | 共 7 条事件，工具 0 个 |
| `09-filtered` | FAILED（`CONTENT_FILTERED`） | `run.failed` | 共 4 条事件，工具 0 个 |

（库内状态与终态的对应由测试断言：`run.completed` ↔ SUCCEEDED，`run.failed.status` ↔ 对应失败状态。）

## 摘录：带确认的工具调用（用户拒绝）

```text
1 run.started RunStarted[agent=main, trigger=test]
2 step.started StepStarted[iteration=1]
3 step.finished StepFinished[iteration=1, outcome=SUCCEEDED, finishReason=TOOL_CALLS, requestedModel=test-model, actualModel=test-model, promptTokens=1, completionTokens=2, failureKind=null]
4 tool.started ToolStarted[toolCallId=c-write, toolName=write_file, source=builtin, iteration=1, argumentsSummary={"path":"x.txt","content":"***"}]
5 approval.requested ApprovalRequested[requestId=85c1cac7-6028-4e4a-8b76-06596da47b57, toolCallId=c-write, actionType=FILE_WRITE, title=写入文件, lines=[文件  x.txt, 方式  新建, 大小  24 字节], sessionGrantAllowed=true]
6 approval.resolved ApprovalResolved[requestId=85c1cac7-6028-4e4a-8b76-06596da47b57, toolCallId=c-write, decision=DENY]
7 tool.finished ToolFinished[toolCallId=c-write, toolName=write_file, status=DENIED, durationMs=8, truncated=false, resultSummary=DENIED：用户拒绝了该操作；不要重复同样的请求，可换一种做法或询问用户；操作可能已经执行, possiblyExecuted=false]
8 step.started StepStarted[iteration=2]
9 message.started MessageStarted[messageId=148b53d7-f6f0-4898-a3e1-d34cfdee4c62, iteration=2, role=assistant]
10 message.delta MessageDelta[messageId=148b53d7-f6f0-4898-a3e1-d34cfdee4c62, text=已放弃写入]
11 message.ended MessageEnded[messageId=148b53d7-f6f0-4898-a3e1-d34cfdee4c62]
12 step.finished StepFinished[iteration=2, outcome=SUCCEEDED, finishReason=STOP, requestedModel=test-model, actualModel=test-model, promptTokens=1, completionTokens=2, failureKind=null]
13 run.completed RunCompleted[text=已放弃写入, iterations=2, persistence=CONFIRMED]
```

`content` 参数里的测试密钥在 `argumentsSummary` 中显示为 `***`；拒绝原因正文不进入事件。

## 摘录：截断

```text
1 run.started RunStarted[agent=main, trigger=test]
2 step.started StepStarted[iteration=1]
3 message.started MessageStarted[messageId=c0f9b1eb-437d-40cb-bfe6-cc16d1bc1fbe, iteration=1, role=assistant]
4 message.delta MessageDelta[messageId=c0f9b1eb-437d-40cb-bfe6-cc16d1bc1fbe, text=半截]
5 message.ended MessageEnded[messageId=c0f9b1eb-437d-40cb-bfe6-cc16d1bc1fbe]
6 step.finished StepFinished[iteration=1, outcome=SUCCEEDED, finishReason=LENGTH, requestedModel=test-model, actualModel=test-model, promptTokens=1, completionTokens=2, failureKind=null]
7 run.failed RunFailed[code=OUTPUT_TRUNCATED, status=FAILED, message=模型输出达到上限被截断，可调大 max_output_tokens 或拆分任务, iterations=1, persistence=CONFIRMED, possiblyExecuted=false]
```

截断那次响应里的 `read_file` 调用没有出现任何 `tool.*` 事件，模型只被调用 1 次。

## 观察到的既有问题（不在本功能范围）

被沙箱拒绝的工具，返回给模型的错误说明末尾带"操作可能已经执行"（见上面 seq 7 的 `resultSummary`）。原因是治理层在调用工具前就把执行状态标为"可能已开始"，这是 005 以来的行为；事件里的 `possiblyExecuted` 已按"拒绝即未执行"给出 `false`。
