# Contract: 运行事件（schemaVersion 1）

本契约描述一轮对话产生的事件序列。本期在进程内以 Java 对象传递给 `TurnSink.onEvent(...)`；后续事件落库、SSE 与 AG-UI 翻译使用本节的 JSON 形状，字段名与语义不变。

## 1. 公共字段

```json
{
  "schemaVersion": 1,
  "sessionId": "8a1f…",
  "turnId": "c3e2…",
  "seq": 7,
  "type": "tool.started",
  "createdAt": "2026-11-21T08:30:12.345Z",
  "data": { }
}
```

| 字段 | 规则 |
| --- | --- |
| `schemaVersion` | 当前为 `1`。新增类型、新增可选字段不升版本 |
| `sessionId` | 会话 UUID；仅起步未确认的两条事件允许为 `null` |
| `turnId` | 轮次 UUID |
| `seq` | 本轮内从 1 起连续递增；`(turnId, seq)` 唯一 |
| `type` | 见 §3；消费方遇到未知类型必须忽略 |
| `createdAt` | ISO-8601 UTC |
| `data` | 类型相关负载，见 §3 |

## 2. 顺序语法

```text
turn      := run.started  body*  terminal
body      := step | tool | interaction
step      := step.started  message?  step.finished
message   := message.started  message.delta+  message.ended
tool      := tool.started  interaction*  tool.finished
interaction := approval.requested approval.resolved
             | input.requested input.resolved
terminal  := run.completed | run.failed | run.cancelled
```

补充规则：

1. 每轮恰好一条 `run.started`（第一条）和一条终态事件（最后一条）。
2. `tool` 出现在产生它的 `step.finished` 之后、下一个 `step.started` 之前。
3. 同一 `messageId` / `toolCallId` / `requestId` 的开始与结束成对出现。终态之前异常结束时，已开始的消息、工具、交互可能没有结束事件，消费方以终态为准关闭它们。
4. 一次模型响应只有工具调用、没有文字时，`step` 中没有 `message`。
5. 终态事件只在轮次结果持久化收尾之后发出。

## 3. 事件类型

### 过程面

| type | data 字段 |
| --- | --- |
| `run.started` | `agent`（Agent 名）、`trigger`（如 `cli`） |
| `step.started` | `iteration`（从 1 起） |
| `step.finished` | `iteration`、`outcome`（`SUCCEEDED`/`FAILED`）、`finishReason`、`requestedModel`、`actualModel`、`promptTokens`、`completionTokens`、`failureKind`；未知值为 `null` |
| `tool.started` | `toolCallId`、`toolName`、`source`（与 `tool_invocations.source` 相同，如 `builtin`、MCP 来源名）、`iteration`、`argumentsSummary` |
| `tool.finished` | `toolCallId`、`toolName`、`status`、`durationMs`、`truncated`、`resultSummary`、`possiblyExecuted` |
| `approval.requested` | `requestId`、`toolCallId`、`actionType`（`ActionType` 枚举名，如 `FILE_WRITE`/`SHELL`）、`title`、`lines`（字符串数组，与 CLI 提示一致）、`sessionGrantAllowed` |
| `approval.resolved` | `requestId`、`toolCallId`、`decision` |
| `input.requested` | `requestId`、`toolCallId`、`question` |
| `input.resolved` | `requestId`、`toolCallId`、`outcome` |

### 数据面

| type | data 字段 |
| --- | --- |
| `message.started` | `messageId`、`iteration`、`role`（固定 `assistant`） |
| `message.delta` | `messageId`、`text`（非空，一个或多个字符） |
| `message.ended` | `messageId` |

### 控制面

| type | data 字段 |
| --- | --- |
| `run.completed` | `text`（最终回答）、`iterations`、`persistence` |
| `run.failed` | `code`、`status`（持久化的轮次状态）、`message`（面向用户的固定说明）、`iterations`、`persistence`、`possiblyExecuted` |
| `run.cancelled` | `message`、`iterations`、`persistence`、`possiblyExecuted` |

枚举取值见 [data-model.md §2、§4](../data-model.md)。

`possiblyExecuted` 的含义：`tool.finished` 中，成功或结果未确认时为 `true`；失败、超时、中断时只有**非幂等**工具且已进入执行才为 `true`（幂等工具重做无害，不提示）；被拒绝、被跳过、第一次授权就没通过的调用一律为 `false`。`run.failed` / `run.cancelled` 中，有未确认的提交或任一工具结束事件为"非成功且 `possiblyExecuted`"时为 `true`。

## 4. 内容安全

- `argumentsSummary`、`resultSummary`、`approval.requested.lines`、`input.requested.question` 与审计表使用同一个 `Redactor` 脱敏和同样的长度上限；`resultSummary` 就是模型看到的那份（已按 `max_result_chars` 截断）。
- 不包含：prompt、system 指令、模型原始响应、用户对确认的拒绝原因、用户对提问的答案正文、异常消息、密钥。
- `run.failed.message` / `run.cancelled.message` 是固定文案（可能追加"该操作可能已生效"），不含内部细节。
- 事件不写日志。

## 5. 示例：一次带确认的工具调用

```text
seq 1  run.started        {agent:"main", trigger:"cli"}
seq 2  step.started       {iteration:1}
seq 3  message.started    {messageId:"m1", iteration:1, role:"assistant"}
seq 4  message.delta      {messageId:"m1", text:"我先把结果写入文件。"}
seq 5  message.ended      {messageId:"m1"}
seq 6  step.finished      {iteration:1, outcome:"SUCCEEDED", finishReason:"tool_calls", …}
seq 7  tool.started       {toolCallId:"call_1", toolName:"write_file", argumentsSummary:"{\"path\":\"output/a.md\",…}"}
seq 8  approval.requested {requestId:"r1", toolCallId:"call_1", actionType:"FILE_WRITE", title:"写入文件", lines:[…], sessionGrantAllowed:true}
seq 9  approval.resolved  {requestId:"r1", toolCallId:"call_1", decision:"ALLOW_ONCE"}
seq 10 tool.finished      {toolCallId:"call_1", status:"SUCCESS", durationMs:12, truncated:false, resultSummary:"已写入…", possiblyExecuted:true}
seq 11 step.started       {iteration:2}
seq 12 message.started    {messageId:"m2", iteration:2, role:"assistant"}
seq 13 message.delta      {messageId:"m2", text:"已完成"}
seq 14 message.delta      {messageId:"m2", text:"，文件在 output/a.md。"}
seq 15 message.ended      {messageId:"m2"}
seq 16 step.finished      {iteration:2, outcome:"SUCCEEDED", finishReason:"stop", …}
seq 17 run.completed      {text:"已完成，文件在 output/a.md。", iterations:2, persistence:"CONFIRMED"}
```

截断示例：`step.finished.finishReason` 为 `length`，紧接着是 `run.failed {code:"OUTPUT_TRUNCATED", status:"FAILED"}`，中间不会出现 `tool.*`。

## 6. 与 AG-UI 的映射（后续 Web 流式接口使用，本期不实现）

已对照 AG-UI 规范原文（`docs/concepts/events.mdx`、`interrupts.mdx`）核对事件名与必填字段。

| AgentOS | AG-UI | 说明 |
| --- | --- | --- |
| `sessionId` / `turnId` | `threadId` / `runId` | 一个轮次可能对应多个 AG-UI run，见下方"确认" |
| `run.started` | `RUN_STARTED` | |
| `step.started` / `step.finished` | `STEP_STARTED` / `STEP_FINISHED` | `stepName` 取 `iteration-N` |
| `message.started` / `delta` / `ended` | `TEXT_MESSAGE_START` / `CONTENT` / `END` | `messageId`、`role` 直接对应；`delta` 非空的要求一致 |
| `tool.started` | `TOOL_CALL_START` + `TOOL_CALL_ARGS` + `TOOL_CALL_END` | 参数一次性放进一条 `ARGS` |
| `tool.finished` | `TOOL_CALL_RESULT` | `content` 取 `resultSummary`，`messageId` 由翻译层生成 |
| `approval.requested` | `RUN_FINISHED{outcome:{type:"interrupt", interrupts:[{id:requestId, reason:"approval", toolCallId, message:title}]}}` | 翻译层对 AG-UI 客户端结束当前 run；AgentOS 轮次仍在等待 |
| （客户端 `resume`） | `RunAgentInput.resume[{interruptId, status, payload}]` | 翻译层把决定交给仍在等待的确认，后续事件进入新的 `runId` |
| `approval.resolved` | 新 run 的 `RUN_STARTED` 之后继续 | 无直接对应事件，可放进 `CUSTOM` 供审计 |
| `input.requested` / `resolved` | 同 `approval.*`，`reason:"input"`，`responseSchema` 为字符串 | |
| `run.completed` | `RUN_FINISHED{outcome:{type:"success"}, result:text}` | |
| `run.failed` | `RUN_ERROR{code, message}` | `code` 直接使用 `TurnFailureCode` |
| `run.cancelled` | `RUN_ERROR{code:"CANCELLED", message}` | AG-UI 没有取消事件 |
| 刷新页面 | `MESSAGES_SNAPSHOT` | 由 `session_messages` 生成 |

断线重放使用 `(turnId, seq)`：客户端带上最后收到的 `seq`，服务端补发之后的事件，不重新调用模型。

## 7. 兼容规则

- 消费方必须忽略未知 `type` 和未知 `data` 字段。
- 生产方只追加类型与可选字段；改名、删除、改语义需要升 `schemaVersion`。
- `TurnFailureCode` 只追加。
