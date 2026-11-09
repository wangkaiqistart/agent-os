# Data Model: US-1 对接 LLM（Provider 抽象）

**Plan**: [plan.md](plan.md) | 本 story 不建表。下面的类型是 core 契约，`LlmCallRecord` 的字段是 US-2 建 `llm_calls` 表的依据。

消息、工具、响应直接用 Spring AI 类型（`Message`、`ToolCallback`、`ChatResponse`），不另造 `ModelRequest` / `ModelResponse`（provider 页面"已有能力不要重新实现"）。

## Profile（core，record）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `provider` | `String` | 否 | 连接名称；为空时用 `agentos.default-provider` |
| `model` | `String` | 是 | 聊天模型；为空或空白时外呼前失败（FR-007） |
| `reasoningEffort` | `String` | 否 | 未设置时请求不带该字段 |
| `maxOutputTokens` | `Integer` | 否 | 正整数；按连接类型映射字段（FR-009） |
| `temperature` | `Double` | 否 | 未设置时请求不带该字段（FR-008） |

校验在构造时只做格式检查（`maxOutputTokens > 0`、`temperature` 在 0 ~ 2）；`model` 是否为空、`provider` 是否存在由 `ProviderService` 在外呼前检查，错误码 `INVALID_ARGUMENT`。

## ProviderRequest（core，record）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `profile` | `Profile` | 是 | |
| `messages` | `List<Message>` | 是 | 至少一条；只有 `UserMessage` 可以带图片 |
| `tools` | `List<ToolCallback>` | 否 | 为空时请求不带工具定义（FR-010） |
| `toolContext` | `Map<String, Object>` | 否 | 只交给工具，不进请求 JSON（FR-011） |
| `deadline` | `Instant` | 否 | 本轮截止时间；为空时只受连接超时约束 |
| `correlation` | `CallCorrelation` | 否 | 原样写进调用记录 |

`CallCorrelation`：`sessionId`（`UUID`）、`turnId`（`UUID`）、`iteration`（`Integer`），都可为空。

## ProviderService（core，接口）

```text
ProviderResult call(ProviderRequest request)
ProviderResult stream(ProviderRequest request, Consumer<String> textSink)
```

两个方法都阻塞到调用结束（同步执行模型，技术方案决策三）。完整契约见 [contracts/provider-service.md](contracts/provider-service.md)。

## ProviderResult（core，record）

| 字段 | 类型 | 说明 |
|------|------|------|
| `response` | `ChatResponse` | 最后一次成功尝试的响应；流式时为 `MessageAggregator` 聚合后的结果 |
| `calls` | `List<LlmCallRecord>` | 本次调用的全部尝试，已由 Provider 写出，只读 |

## ProviderCallException（core，extends AgentOSException）

错误码固定 `PROVIDER_UNAVAILABLE`；`getCalls()` 返回全部尝试的记录（FR-025）；`getMessage()` 只含脱敏摘要。外呼前的校验失败不用它，而是 `AgentOSException(INVALID_ARGUMENT, ...)`，此时没有调用记录。

## LlmCallRecord（core，record）——对应 `llm_calls` 一行

| 字段 | 类型 | 可空 | 来源 | `llm_calls` 列 |
|------|------|------|------|---------------|
| `sessionId` / `turnId` / `iteration` | `UUID` / `UUID` / `Integer` | 是 | `CallCorrelation` | `session_id` / `turn_id` / `iteration` |
| `provider` | `String` | 否 | 解析后的连接名 | `provider` |
| `requestedModel` | `String` | 否 | `Profile.model` | `requested_model` |
| `responseModel` | `String` | 是 | `ChatResponseMetadata.getModel()` | `response_model` |
| `responseId` | `String` | 是 | `ChatResponseMetadata.getId()` | `response_id` |
| `mode` | `CallMode` | 否 | `CALL` / `STREAM` | `mode` |
| `attempt` | `int` | 否 | 1 或 2 | **新增** `attempt` |
| `finishReason` | `String` | 是 | `Generation.getMetadata().getFinishReason()` | `finish_reason` |
| `toolCallCount` | `Integer` | 是 | 响应里的工具调用数 | `tool_call_count` |
| `imageCount` | `int` | 否 | 请求中的图片数 | **新增** `image_count` |
| `promptTokens` / `completionTokens` / `totalTokens` | `Integer` | 是 | `Usage`；`EmptyUsage` 时为空 | 同名 |
| `cacheReadTokens` / `cacheWriteTokens` | `Long` | 是 | `Usage.getCacheRead/WriteInputTokens()` | 同名 |
| `requestsRemaining` / `tokensRemaining` | `Long` | 是 | `RateLimit`；`EmptyRateLimit` 时为空 | 同名 |
| `outputCapHonored` | `Boolean` | 是 | 只在 Profile 设了上限且有 completionTokens 时有值 | **新增** `output_cap_honored` |
| `success` | `boolean` | 否 | | `success` |
| `failureKind` | `FailureKind` | 是 | 成功时为空 | 并入 `error_message` 或新增列，US-2 定 |
| `httpStatus` | `Integer` | 是 | SDK 异常的状态码 | 同上 |
| `errorSummary` | `String` | 是 | 由 failureKind、状态码、type、code 拼成，不含原始响应 | `error_message` |
| `startedAt` | `Instant` | 否 | | `created_at` |
| `durationMs` | `long` | 否 | 单调时钟 | `duration_ms` |

规则：

- 每一次实际发出的 HTTP 请求产生一条，重试单独一条；外呼前就失败的不产生（FR-022）。
- 空值与 0 不混用：只有服务端明确返回 0 才记 0（FR-023）。
- 不含 key、Prompt、工具参数、图片数据与网址、原始响应体（FR-024）。

`FailureKind`：`RATE_LIMITED`、`SERVER_ERROR`、`CONNECTION`、`AUTH`、`INVALID_REQUEST`、`PROTOCOL`、`CANCELLED`，可重试的只有前三个（且 SERVER_ERROR 只限 500 / 502 / 503 / 504）。

## LlmCallRecorder（core，接口）

```text
void record(LlmCallRecord record)
```

每次尝试结束时由 `ProviderService` 调用，在把结果或异常交给调用方之前。US-1 的实现 `LoggingLlmCallRecorder` 写一条 INFO 级结构化日志（事件名 `llm_call`）；`outputCapHonored == false` 时另写一条 WARN。US-2 提供写 `llm_calls` 的实现后，provider 的默认实现自动让位（`@ConditionalOnMissingBean`）。记录输出抛出的异常不能吞掉，也不能触发重发请求。

## ProviderConnection（provider，内部）

| 字段 | 说明 |
|------|------|
| `name` | 连接名，即 `agentos.providers` 的键 |
| `type` | `ConnectionType`：`OPENAI` → 输出上限发 `max_completion_tokens`；`DEEPSEEK` → 发 `max_tokens` |
| `timeout` | 连接超时，正数 |
| `retryMinRemaining` | 重试最少剩余时间，默认 15 秒，正数且不大于 `timeout`；等待 `Retry-After` 后剩余时间少于它就不重试 |
| `available` | key 非空为 true |
| `chatModel` | `OpenAiChatModel`，不可用时为空；不注册为 Bean |

启动时一次性建好，放进不可变的 `Map<String, ProviderConnection>`，运行期间不变。

## EmbeddingService / EmbeddingStatus（core）

```text
List<float[]> embed(List<String> texts)   // 空列表直接返回空列表，不发请求
float[] embed(String text)
EmbeddingStatus status()
int dimensions()
```

`EmbeddingStatus`：`state` + `reason`：

| state | reason | 含义 |
|-------|--------|------|
| `DISABLED` | 未启用 / 未配置 key | 不创建 `OpenAiEmbeddingModel` |
| `CHECKING` | — | 后台校验进行中（`startup-check=async`） |
| `READY` | — | 可用 |
| `UNAVAILABLE` | 不可达 / 鉴权失败 / 欠费 / 其他 | 暂时不可用；除鉴权失败外会在后台重新校验 |
| `MISCONFIGURED` | 配置维度与实际维度 | 维度不一致；不自动恢复，修正配置并重启 |

`embed` 在各状态下的行为见 [contracts/provider-service.md](contracts/provider-service.md)。

状态转换（research R12）：

```text
enabled=false 或 key 为空                 → DISABLED（终态）
启用（async）                             → CHECKING，应用就绪后在后台校验
启用（sync）                              → 启动时直接校验，维度不一致则拒绝启动
校验成功且维度一致                         → READY
校验成功但维度不一致（async）              → MISCONFIGURED + ERROR 日志
校验失败（不可达 / 鉴权 / 欠费 / 其他）     → UNAVAILABLE + WARN
UNAVAILABLE 且原因不是鉴权失败             → 按 1 → 2 → 4 … 分钟退避（最长 30 分钟）重新校验 → READY / MISCONFIGURED / UNAVAILABLE
READY 时某次调用返回的维度不一致           → MISCONFIGURED，本次调用失败
```

状态保存在 `AtomicReference<EmbeddingStatus>` 中，读写不加锁；`CHECKING` 时的等待通过校验任务的 `CompletableFuture` 实现，最多等一个校验超时。

向量化日志事件名 `embedding_call`，字段：模型、文本条数、请求次数、token 用量、成功与否、耗时；不含原文和向量值（FR-035）。
