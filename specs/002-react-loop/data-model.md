# Data Model: US-2 ReAct 循环

**Plan**: [plan.md](plan.md) | **Spec**: [spec.md](spec.md)

表字段在技术方案 9.2 维护；本文件给出本 story 实际建的五张表，与 9.2 不同的地方标"新增"。时间列都用 `timestamptz`；租约这类要比较时间的列，由数据库的 `now()` 赋值，不在 Java 里取时间。

## sessions

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | `uuid` PK，默认 `gen_random_uuid()` | |
| `profile_name` | `text` NOT NULL | Agent 名 |
| `channel` | `text` NOT NULL | 本 story 只有 `cli` |
| `user_id` | `text` NOT NULL | CLI 为本机登录用户名（FR-041） |
| `status` | `text` NOT NULL，默认 `active` | `active` / `archived`（CHECK） |
| `lease_owner` | `text` | 持有租约的轮次，空表示空闲 |
| `lease_until` | `timestamptz` | 租约到期时间 |
| `created_at` / `last_active_at` | `timestamptz` NOT NULL | |
| `archived_at` | `timestamptz` | `chat --new` 归档时写入（FR-040） |

唯一索引：`(channel, user_id, profile_name) WHERE status = 'active'`（FR-002）。

## agent_turns

| 列 | 类型 | 说明 |
|----|------|------|
| `turn_id` | `uuid` PK | 由 Java 生成：写用户消息之前就要用 |
| `session_id` | `uuid` NOT NULL，FK | |
| `trigger` | `text` NOT NULL | 本 story 为 `cli`；`web`、`scheduler` 留给后续 story |
| `status` | `text` NOT NULL | 见下文状态机（CHECK） |
| `iterations` | `int` NOT NULL，默认 0 | 实际迭代次数 |
| `final_text` | `text` | 最终回复，或给使用者的说明 |
| `error_message` | `text` | 脱敏后的简短原因；下一轮提示词里的"未完成原因"取自它（FR-023） |
| `started_at` / `finished_at` | `timestamptz` | |
| `deadline_at` | `timestamptz` NOT NULL | 整轮截止时间 |

索引：`(session_id, started_at DESC)`；`(status) WHERE status = 'RUNNING'`（启动收尾用）。

## session_messages

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | `bigint` 自增 PK | 同一会话内即消息顺序 |
| `session_id` | `uuid` NOT NULL，FK | |
| `turn_id` | `uuid` NOT NULL，FK | |
| `iteration` | `int` NOT NULL | 产生它的迭代序号，用户消息为 0 |
| `type` | `text` NOT NULL | `USER` / `ASSISTANT` / `TOOL`（CHECK） |
| `content` | `text` | 文本；只有工具调用的助手消息可以为空 |
| `tool_calls` | `jsonb` | 助手消息里的工具调用请求：id、name、arguments |
| `tool_responses` | `jsonb` | 工具消息里的结果：id、name、responseData |
| `metadata` | `jsonb` | |
| `created_at` | `timestamptz` NOT NULL | |

索引 `(session_id, id)`。只追加，不更新、不删除（FR-032）。系统指令不入库，每轮重新组装。

## llm_calls

与 US-1 的 `LlmCallRecord` 字段一一对应（见 [../001-llm-provider/data-model.md](../001-llm-provider/data-model.md)）。US-1 留给本 story 决定的两项：失败分类和状态码各用独立的列，方便按失败类别统计。

| 列 | 类型 | 可空 |
|----|------|------|
| `id` | `bigint` 自增 PK | 否 |
| `session_id`、`turn_id`、`iteration` | `uuid`、`uuid`、`int` | 否 |
| `provider`、`requested_model`、`mode` | `text` | 否；`mode` 为 `CALL` / `STREAM`（CHECK） |
| `attempt` | `smallint` | 否；1 或 2（CHECK） |
| `image_count`、`duration_ms` | `int`、`bigint` | 否 |
| `success` | `boolean` | 否 |
| `created_at` | `timestamptz` | 否；取 `startedAt` |
| `response_model`、`response_id`、`finish_reason` | `text` | 是 |
| `tool_call_count` | `int` | 是 |
| `prompt_tokens`、`completion_tokens`、`total_tokens` | `int` | 是；服务端没返回为 NULL，不是 0（US-1 FR-023） |
| `cache_read_tokens`、`cache_write_tokens`、`requests_remaining`、`tokens_remaining` | `bigint` | 是 |
| `output_cap_honored` | `boolean` | 是 |
| `failure_kind`、`http_status` | `text`、`int` | 是；成功时为 NULL |
| `error_message` | `text` | 是；脱敏后的摘要，不含原始响应 |

US-1 的记录里 `session_id`、`turn_id`、`iteration` 可空，落库时这三列 NOT NULL：`AgentService` 总是传入，为空说明调用方有 bug，按审计写入失败处理（FR-031）。辅助调用没有会话，等它们需要落库时再放宽。

## tool_invocations

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | `bigint` 自增 PK | |
| `session_id`、`turn_id`、`iteration` | `uuid`、`uuid`、`int` NOT NULL | 同 `llm_calls`，落库时不允许为空 |
| `tool_call_id` | `text` NOT NULL | 模型给的调用 ID（research R5） |
| `tool_name` | `text` NOT NULL | |
| `tool_source` | `text` NOT NULL | 本 story 只有 `builtin` |
| `status` | `text` NOT NULL | `in_progress` / `success` / `failed` / `timeout` / `sandbox_denied` / `limit_skipped` / `interrupted`（CHECK）。**新增** `in_progress`、`interrupted`，技术方案 9.2 里还没有 |
| `input_json` | `jsonb` | 脱敏后的参数；参数不是合法 JSON 时，存成一个 JSON 字符串值，不丢弃 |
| `result_text` | `text` | 脱敏、截断之后的结果 |
| `result_chars` | `int` | 截断之前的原始长度 |
| `result_truncated` | `boolean` NOT NULL，默认 false | |
| `error_message` | `text` | 脱敏后的原因 |
| `idempotent` | `boolean` NOT NULL | 执行时的工具策略 |
| `attempts` | `smallint` NOT NULL，默认 0 | 本 story 没有重试，执行了就是 1 |
| `duration_ms` | `bigint` | |
| `created_at` | `timestamptz` NOT NULL | 写 `in_progress` 的时刻 |
| `updated_at` | `timestamptz` NOT NULL | **新增**：更新为终态的时刻 |

唯一约束 `(turn_id, iteration, tool_call_id)`。索引 `(session_id, turn_id)`；`(status) WHERE status = 'in_progress'`（启动收尾用）。

## 状态机

**轮次**：`RUNNING` → `SUCCEEDED` / `FAILED` / `TIMED_OUT` / `MAX_ITERATIONS` / `INTERRUPTED`，之后不再变化。`LIMIT_EXCEEDED` 保留在 CHECK 里，与技术方案一致，本 story 不会写入。

**工具调用记录**：先写 `in_progress`，再变成 `success` / `failed` / `timeout` / `sandbox_denied` 之一；崩溃遗留的 `in_progress` 由启动收尾改为 `interrupted`。`limit_skipped` 没有执行动作，直接写这个终态。

**会话**：`active` → `archived`，不可逆（FR-040）。

## 核心类型（`AgentOS-core`）

| 类型 | 字段与说明 |
|------|-----------|
| `AgentProfile` | `name`、`description`、`Profile model`（US-1 的 `Profile`，不改动）、`List<String> tools`、`AgentSettings settings` |
| `AgentSettings` | `maxIterations`（10）、`maxHistoryTurns`（20）、`maxHistoryTokens`（32000）、`turnTimeout`（300s） |
| `LoadedAgent` | `AgentProfile`、`instructions`（`AGENT.md` 正文）、`bootstrap`（文件名到内容）；每轮重新加载 |
| `TurnContext` | `sessionId`、`turnId`、`LoadedAgent`、`trigger`、`iteration`、`deadline`；不可变，`forIteration(i)` 返回副本 |
| `ProcessRequest` / `TurnResult` | 见 [contracts/agent-service.md](contracts/agent-service.md)；`TurnResult(turnId, status, text, iterations)` |
| `TurnStatus` | `SUCCEEDED`、`FAILED`、`TIMED_OUT`、`MAX_ITERATIONS`、`INTERRUPTED`、`LIMIT_EXCEEDED` |
| `TurnSink` | `onDelta`、`onToolCall`、`onToolResult`、`onComplete`、`onError`，默认空实现；`streaming()` 默认 `false`；常量 `NOOP` |
| `ToolCallLedger` | 同名工具的调用 ID 先进先出队列；`next(name)`、`remaining()` |
| `ToolPolicy` | `idempotent`、`timeout`、`maxResultChars` |
| `Sandbox`、`SandboxAction`、`ActionType` | 见 [contracts/tool-governance.md](contracts/tool-governance.md) |
| `Redactor` | `redact(String)`；已知密钥由构造时传入的 `Supplier` 提供 |
| 异常 | `TurnDeadlineExceededException`、`LeaseLostException`、`AuditWriteException`、`SandboxViolationException`。会话忙、Agent 不存在、`AGENT.md` 不合法用现有的 `AgentOSException` 加错误码，不新增类型 |

## 端口（`AgentOS-core` 定义，`AgentOS-storage` 实现）

| 端口 | 方法 |
|------|------|
| `SessionManager` | `openActive(key)`：找到或创建；`archiveIfIdle(key)`：没有轮次在运行才归档 |
| `TurnStore` | `acquireLease`、`renewLease`、`releaseLease`；`begin(session, trigger, deadline)`；`finish(turn, status, text, error)`；`reapInterrupted()`：启动收尾，同时处理遗留的 `in_progress` 工具记录 |
| `MessageStore` | `append(turn, iteration, messages)`；`recentTurns(session, maxTurns)`：返回每个轮次的状态、`error_message` 与消息 |
| `ToolInvocationRecorder` | `begin(...)` 写 `in_progress`；`complete(...)` 更新终态；`skipped(...)` 直接写 `limit_skipped` |
| `LlmCallRecorder` | US-1 已有；本 story 在 storage 里提供落库实现 |

端口方法的失败，统一经 `RetryingAuditWriter` 按 FR-031 重试后，抛 `AuditWriteException`。
