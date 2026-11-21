# Data Model: 运行事件流

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

本期只有一处持久化变化（`agent_turns.status` 新增 `CANCELLED`），其余都是进程内的 Java 类型。事件的线上格式与字段约束见 [contracts/run-events.md](./contracts/run-events.md)。

## 1. 运行事件 `TurnEvent`（core，进程内）

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `schemaVersion` | int | 固定为 `1`；新增事件类型或可选字段不升版本，删除或改语义才升 |
| `sessionId` | UUID | 非空；唯一例外是起步未确认时的 `run.started` / `run.failed`（R12） |
| `turnId` | UUID | 非空 |
| `seq` | long | 本轮内从 1 开始，每条加 1，无重复无空洞 |
| `createdAt` | Instant | 发布器分配序号时取当前时间 |
| `payload` | `TurnEventPayload` | 非空；事件类型由 payload 的具体类型决定 |

`type()` 由 payload 派生，返回契约里的类型名（如 `tool.started`），不单独存字段，避免二者不一致。

## 2. 事件负载 `TurnEventPayload`（sealed interface）

每种类型一个 record，字段全部是已脱敏、已限长的安全值。

| 类型名 | record | 字段 |
| --- | --- | --- |
| `run.started` | `RunStarted` | `agent`、`trigger` |
| `step.started` | `StepStarted` | `iteration` |
| `step.finished` | `StepFinished` | `iteration`、`outcome`（`SUCCEEDED` / `FAILED`）、`finishReason`（可空）、`requestedModel`、`actualModel`（可空）、`promptTokens`（可空）、`completionTokens`（可空）、`failureKind`（可空） |
| `message.started` | `MessageStarted` | `messageId`、`iteration` |
| `message.delta` | `MessageDelta` | `messageId`、`text`（非空） |
| `message.ended` | `MessageEnded` | `messageId` |
| `tool.started` | `ToolStarted` | `toolCallId`、`toolName`、`source`、`iteration`、`argumentsSummary` |
| `tool.finished` | `ToolFinished` | `toolCallId`、`toolName`、`status`、`durationMs`、`truncated`、`resultSummary`（可空）、`possiblyExecuted` |
| `approval.requested` | `ApprovalRequested` | `requestId`、`toolCallId`、`actionType`、`title`、`lines`、`sessionGrantAllowed` |
| `approval.resolved` | `ApprovalResolved` | `requestId`、`toolCallId`、`decision` |
| `input.requested` | `InputRequested` | `requestId`、`toolCallId`、`question` |
| `input.resolved` | `InputResolved` | `requestId`、`toolCallId`、`outcome` |
| `run.completed` | `RunCompleted` | `text`、`iterations`、`persistence` |
| `run.failed` | `RunFailed` | `code`、`status`、`message`、`iterations`、`persistence`、`possiblyExecuted` |
| `run.cancelled` | `RunCancelled` | `message`、`iterations`、`persistence`、`possiblyExecuted` |

枚举取值：

- `ToolFinished.possiblyExecuted`：规则见 [contracts/run-events.md §3](./contracts/run-events.md)；非幂等工具失败或中断且已进入执行时为 true，被拒绝、跳过或第一次授权就没通过时为 false。
- `ToolFinished.status`：`SUCCESS`、`FAILED`、`TIMEOUT`、`DENIED`、`SKIPPED`、`INTERRUPTED`、`UNCONFIRMED`。与 `tool_invocations.status` 的对应：`success`、`failed`、`timeout`、`sandbox_denied`、`limit_skipped`、`interrupted`、`unconfirmed`。
- `ApprovalResolved.decision`：`ALLOW_ONCE`、`ALLOW_SESSION`、`DENY`、`TIMED_OUT`、`UNATTENDED`、`CANCELLED`。由 `ApprovalDecision.Outcome` 映射，`UNSUPPORTED` → `UNATTENDED`，`EOF` → `CANCELLED`。
- `InputResolved.outcome`：`ANSWERED`、`TIMED_OUT`、`UNATTENDED`、`CANCELLED`、`TOO_LONG`。
- `RunFailed.code`：见 §4。
- `persistence`：`CONFIRMED`、`UNCONFIRMED`（沿用 `PersistenceStatus`）。

## 3. 发布器（core：接口 `TurnEventPublisher`，实现由现有 `ManagedTurnSink` 升级而来）

工具模块只看到 `TurnEventPublisher.publish(payload)`；终结方法 `finish(TurnResult)` 只对 `DefaultAgentService` 可见。

| 状态 | 含义 |
| --- | --- |
| `nextSeq` | 下一条事件的序号，初值 1 |
| `valid` | 消费方是否仍可用；消费方抛异常后置为 false |
| `terminal` | 是否已发出控制面事件 |

规则：

1. `publish(payload)`：持锁 → 检查 `valid` 且未终结 → 分配序号 → 调用 `delegate.onEvent(event)` → 释放锁。消费方抛异常时置 `valid=false`、`budget.cancel(OUTPUT_FAILED)`，抛 `LeaseLostException`（沿用现有规则，US3 场景 7）。
2. 等锁和投递只受 hard stop 约束，超时按消费方失效处理；过程事件不检查整轮截止时间，超时后仍要送达已开始的工具、步骤的结束事件。只有 `message.started` / `message.delta` 在整轮到期后被拒绝（抛 `TurnDeadlineExceededException`，不使发布器失效），让不响应截止时间的模型流停下。
3. `finish(TurnResult)`：只执行一次；把 `TurnResult` 转成 `run.completed` / `run.failed` / `run.cancelled` 之一并发布，之后任何 `publish` 都被拒绝。终结投递沿用现有"有界等待、只尝试一次、失败只记日志"的规则。
4. 控制面 payload 只能由 `finish` 发布，`publish` 拒绝控制面类型。

## 4. 失败错误码 `TurnFailureCode`（core 枚举）

`OUTPUT_TRUNCATED`、`CONTENT_FILTERED`、`EMPTY_RESPONSE`、`TIMED_OUT`、`MAX_ITERATIONS`、`TOOL_LIMIT_EXCEEDED`、`PROVIDER_RATE_LIMITED`、`PROVIDER_SERVER_ERROR`、`PROVIDER_CONNECTION`、`PROVIDER_AUTH`、`PROVIDER_INVALID_REQUEST`、`PROVIDER_PROTOCOL`、`LEASE_LOST`、`OUTPUT_FAILED`、`SHUTDOWN`、`AUDIT_UNCONFIRMED`、`TOOL_CLEANUP_UNCONFIRMED`、`START_UNCONFIRMED`、`INTERNAL`。

来源映射见 [research.md R11](./research.md#r11-失败错误码)。只追加、不重命名；后续网页端与 AG-UI 的 `RUN_ERROR.code` 直接使用这些值。

## 5. 轮次状态 `TurnStatus` 与持久化

新增 `CANCELLED`。

```text
RUNNING ──成功────────────────→ SUCCEEDED
   │──截断/过滤/空回答/内部错误─→ FAILED（截断 / 过滤时 error_message 以错误码开头）
   │──整轮超时─────────────────→ TIMED_OUT
   │──达到 max_iterations──────→ MAX_ITERATIONS
   │──工具次数超限（THROW）────→ LIMIT_EXCEEDED
   │──用户取消─────────────────→ CANCELLED      ← 本期新增
   └──进程关闭/租约丢失/输出端失效→ INTERRUPTED
```

- 只有 `RUNNING` 能转出，且只转一次（`JpaTurnStore.finish` 现有规则）。
- `CANCELLED` 与 `INTERRUPTED` 的区别只在于取消原因是否为 `USER`。
- **迁移** `V3__turn_status_cancelled.sql`：删除并重建 `agent_turns_status_check`，取值增加 `CANCELLED`。

`TurnStatus` → 控制面事件：`SUCCEEDED` → `run.completed`；`CANCELLED` → `run.cancelled`；其余 → `run.failed`。

## 6. 取消原因 `CancelReason`（core 枚举，属于 `TurnExecutionBudget`）

`USER`、`SHUTDOWN`、`OUTPUT_FAILED`、`INTERNAL`。第一次取消的原因生效，之后的取消只能收紧 hard stop，不改原因。

| 原因 | hard stop 收紧到 | 调用方 |
| --- | --- | --- |
| `USER` | 现在 + 2 秒 | `AgentService.cancel(turnId)` |
| `SHUTDOWN` | 现在 + 1 秒 | `DefaultAgentService.close()` |
| `OUTPUT_FAILED` | 现在 + 1 秒 | 发布器（消费方失效） |
| `INTERNAL` | 现在 + 1 秒 | `TurnRunner`、起步未确认、收尾 `finally` 等现有调用点 |

## 7. `TurnResult` 变化

新增可空字段 `failureCode`（`TurnFailureCode`），成功和取消时为空。保留现有 5 参数构造器（`failureCode` 取空），现有调用点不必全部修改。

## 8. 上下文中的未完成说明（`PromptBuilder.safeReason`）

| 状态 / 错误码 | 固定说明 |
| --- | --- |
| `CANCELLED` | 本轮已被用户取消 |
| `FAILED` + `OUTPUT_TRUNCATED` | 上一轮回答因长度上限被截断 |
| `FAILED` + `CONTENT_FILTERED` | 上一轮回答被内容策略过滤 |
| 其他 | 沿用现有文案 |

错误码从 `HistoryTurn.errorMessage()` 的前缀读取（R6）。
