# Data Model: US-2 ReAct 循环

**Plan**: [plan.md](plan.md) | **Spec**: [spec.md](spec.md)

表字段在技术方案 9.2 维护；本文件给出本 story 实际建的五张表，与 9.2 不同的地方标"新增"。时间列都用 `timestamptz`；租约这类要比较时间的列，由数据库的 `clock_timestamp()` 在取得所需行锁后取值，不在 Java 里取时间。

## sessions

| 列 | 类型 | 说明 |
|----|------|------|
| `session_id` | `uuid` PK，默认 `gen_random_uuid()` | |
| `profile_name` | `text` NOT NULL | Agent 名 |
| `channel` | `text` NOT NULL | 本 story 只有 `cli` |
| `user_id` | `text` NOT NULL | CLI 为本机登录用户名（FR-041） |
| `status` | `text` NOT NULL，默认 `active` | `active` / `archived`（CHECK） |
| `lease_owner` | `text` | 持有租约的轮次，空表示空闲 |
| `lease_until` | `timestamptz` | 数据库时钟计算：至少覆盖本轮deadline加30秒，150秒仅为最短TTL |
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
| `deadline_at` | `timestamptz` NOT NULL | startTurn取得连接/行锁后以数据库clock_timestamp()+本地单调剩余时长计算并返回；不能直接使用应用墙钟绝对时间，本地预算不因SQL或往返重新计时 |

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

业务字段与 US-1 的 `LlmCallRecord` 字段一一对应，另加持久化内部 `write_id`（见 [../001-llm-provider/data-model.md](../001-llm-provider/data-model.md)）。US-1 留给本 story 决定的两项：失败分类和状态码各用独立的列，方便按失败类别统计。

| 列 | 类型 | 可空 |
|----|------|------|
| `id` | `bigint` 自增 PK | 否 |
| `write_id` | `uuid` NOT NULL UNIQUE | 否；记录器一次record入口生成一次，同次数据库重试复用，解决提交结果未知时去重 |
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

唯一约束 `(turn_id, iteration, tool_call_id)`。空白ID/同一模型响应内重复ID在执行整批前拒绝，不自动生成替代ID。重试begin用相同唯一键，命中时比对脱敏后的immutable输入；一致返回已有记录，不一致报协议/数据冲突。complete按同一行条件更新，已有相同终态及结果视为重试成功，不覆盖不同终态。索引 `(session_id, turn_id)`；`(status) WHERE status = 'in_progress'`（启动收尾用）。

## 状态机

**轮次**：`RUNNING` → `SUCCEEDED` / `FAILED` / `TIMED_OUT` / `MAX_ITERATIONS` / `INTERRUPTED`，之后不再变化。`LIMIT_EXCEEDED` 保留在 CHECK 里，与技术方案一致，本 story 不会写入。

**工具调用记录**：先写 `in_progress`，再变成 `success` / `failed` / `timeout` / `sandbox_denied` 之一；崩溃遗留或终态父轮次遗留的 `in_progress`，由正常finally、启动或会话接管幂等收尾为 `interrupted`，不依赖父轮次仍为RUNNING。`limit_skipped` 没有执行动作，直接写这个终态。

**会话**：`active` → `archived`，不可逆（FR-040）。

## 核心类型（`AgentOS-core`）

| 类型 | 字段与说明 |
|------|-----------|
| `AgentProfile` | `name`、`description`、`Profile model`（US-1 的 `Profile`，不改动）、`List<String> tools`、`AgentSettings settings` |
| `AgentSettings` | maxIterations默认10且≥1；maxHistoryTurns默认20、maxHistoryTokens默认32000，均≥0，任一0不带历史；正token预算按90% floor使用；turnTimeout默认300s且正数 |
| `LoadedAgent` | `AgentProfile`、`instructions`（`AGENT.md` 正文）、`bootstrap`（文件名到内容）；每轮重新加载 |
| `TurnContext` | `sessionId`、`turnId`、`LoadedAgent`、`trigger`、`iteration`、`deadline`；不可变，`forIteration(i)` 返回副本 |
| `ProcessRequest` / `TurnResult` | 输入不含sink；process(request)委托process(request,NOOP)，双参数入口共用链路，见 [contracts/agent-service.md](contracts/agent-service.md)；`TurnResult(turnId, status, text, iterations, persistenceStatus)`，后者为CONFIRMED/UNCONFIRMED，不是数据库轮次状态；正常超时并确认收尾为TIMED_OUT+CONFIRMED |
| `TurnStatus` | `SUCCEEDED`、`FAILED`、`TIMED_OUT`、`MAX_ITERATIONS`、`INTERRUPTED`、`LIMIT_EXCEEDED` |
| `TurnSink` | `onDelta`、`onToolCall`、`onToolResult`、`onComplete`、`onError`，默认空实现；`streaming()` 默认 `false`；常量 `NOOP`；过程错误取消，终结通知最多一次且受同一hardStop限制，通知失败不修改已确认结果 |
| `TurnExecutionBudget` / `TurnRunner` | 单调时间的执行deadline与hardStop=deadline+1s；有界运行与取消，在途调用最终审计与所有收尾共享最后1秒；不重新计时 |
| `TurnBudgetRegistry` | 按turnId存取同一预算，不使用ThreadLocal；AgentService注册/移除；记录器查无预算或hardStop已到则拒绝写入；deadline到但hardStop未到时允许在途调用最终审计，不能把执行截止当作全局取消；不给迟到任务新预算 |
| `ToolCatalog` / `ProviderCatalog` | core只读端口：工具名单校验/获取callbacks；ProviderProfile外呼前验证。分别由tool/provider实现 |
| `ToolCallLedger` | 同名工具的调用 ID 先进先出队列；`next(name)`、`remaining()` |
| `ToolPolicy` | `idempotent`、`timeout`、`maxResultChars` |
| `Sandbox`、`SandboxAction`、`ActionType` | 见 [contracts/tool-governance.md](contracts/tool-governance.md) |
| `Redactor` | `redact(String)`；已知密钥由构造时传入的 `Supplier` 提供 |
| 异常 | `TurnDeadlineExceededException`、`LeaseLostException`、`AuditWriteException`、`SandboxViolationException`。会话忙、Agent 不存在、`AGENT.md` 不合法用现有的 `AgentOSException` 加错误码，不新增类型 |

## 端口（`AgentOS-core` 定义，`AgentOS-storage` 实现）

| 端口 | 方法 |
|------|------|
| `SessionManager` | `archiveIfIdle(key)`：原子回收失效旧轮次后仅空闲归档；`findActive(key)`：只读查询；创建会话由TurnStore.startTurn同一事务执行 |
| `TurnStore` | `startTurn(turnId, sessionKey, trigger, userMessage, budget)`：一个短事务内找到/创建会话、回收失效旧轮次、取得租约、建轮次、写用户消息；`renewLease`；`finishAndRelease(turn, status, text, error)`：一个短事务写终态/工具收尾/owner释放；`reapInterrupted()`：回收没有匹配owner有效租约的RUNNING轮次（不要求deadline到期），独立收尾终态轮次的in_progress工具记录；取过期租约时也调用同一回收逻辑 |
| `MessageStore` | `append(turn, iteration, messages)`；`recentTurns(session, maxTurns)`：返回每个轮次的状态、`error_message` 与消息 |
| `ToolInvocationRecorder` | `begin(...)` 写 `in_progress`；`complete(...)` 更新终态；`skipped(...)` 直接写 `limit_skipped` |
| `LlmCallRecorder` | US-1 已有；本 story 在 storage 里提供落库实现 |

只有模型/工具审计写入经RetryingAuditWriter重试，最多3次且每次独立事务；其他端口操作不自动重试。write_id在单次record入口生成一次，去重只在明确匹配相同写入与payload时成功，不能吞全部唯一键异常。消息追加、租约/归档与建轮次由短事务原子性保证，不机械重放。消息追加有deadline、租约、RUNNING状态及owner条件；终态写入有轮次状态和owner约束，允许在cleanup预算内执行；过期owner不能续租或追加。

轮次状态与租约清理用一次短事务：更新终态→收尾该轮in_progress工具→释放匹配owner租约；数据库操作失败整体回滚，无法查证时返回UNCONFIRMED。条件更新0行先查询：若已是数据库终态、本owner不再持租约、旧轮次工具清理与必要审计均确认，则视为已收敛，返回数据库终态与CONFIRMED；已有终态不覆盖，仅允许清理旧轮次自己的遗留记录，不清新owner租约。数据库持续故障无法承诺立即落库或恢复模型审计缺行，恢复后回收失效RUNNING以及终态孤儿工具记录。

startTurn提交未知按预生成turnId在同一预算内查证：确认已提交且仍有效则继续；已提交但过期/取消/丢租约则不外呼、直接收尾；确认原事务已结束且未提交时抛安全启动失败；仅暂时查无行不足以确认未提交，仍未知则本地FAILED+turnId+UNCONFIRMED，不重放startTurn。详见agent-service契约。
