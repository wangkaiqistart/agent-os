# Research: US-1 对接 LLM（Provider 抽象）

**Date**: 2026-11-08 | **Plan**: [plan.md](plan.md)

所有结论都来自实测：本地 JDK `HttpServer` 替身 + Spring AI 2.0.2 `spring-ai-openai`，以及对三个真实服务（中转 `gpt-5.6-luna`、DeepSeek `deepseek-flash`、百炼 `text-embedding-v4`）的最小请求。实验代码是一次性的，没有进仓库。

## R1 `ChatModel` 会不会自己执行工具

- **Decision**: Provider 直接调用 `OpenAiChatModel.call/stream`，把 `ToolCallback` 放进 `OpenAiChatOptions.toolCallbacks(...)`。构建模型时传入容器注入的 `ToolCallingManager`。
- **Rationale**: 2.0.2 的 `OpenAiChatModel` 字节码里，对 `ToolCallingManager` 只有一处调用：`resolveToolDefinitions`。替身返回工具调用时，回调执行次数为 0，结果里原样带回 `ToolCall[id, name, arguments]`，结束原因 `TOOL_CALLS`。所以不存在第二个工具执行入口，宪章原则四只需要防住 `ChatClient` 路径（已由 `tool-calling.enabled=false` 和 ArchUnit 规则覆盖）。
- **Alternatives**: 不传 `ToolCallingManager`，让模型用内部默认实例解析工具定义。没选：那样会出现一个容器外的 `ToolCallingManager`，与 CLAUDE.md 约束 2 的精神不符。

## R2 base-url 要不要带 `/v1`

- **Decision**: 所有 base-url 都写到 `/v1`。中转为 `<host>/v1`，百炼为 `https://dashscope.aliyuncs.com/compatible-mode/v1`，DeepSeek 为 `https://api.deepseek.com`（带不带 `/v1` 都可用）。代码不自动拼接。
- **Rationale**: SDK 把 `chat/completions`、`embeddings` 直接接在 base-url 后面。替身记录到：base-url 为根地址时路径是 `/chat/completions`，为 `/v1` 时是 `/v1/chat/completions`。真实服务上，中转 `/v1` 可用；百炼不带 `/v1` 返回 404；DeepSeek 两种都返回 200。技术方案 3.5 节"模块会自己拼 `/v1`"的说法适用于旧版本，已过时。
- **Alternatives**: 代码统一补 `/v1`。没选：provider 页面要求"不能统一追加"，DeepSeek、Kimi 等兼容端点的前缀不一致。
- **Follow-up**: `.env.example`、CLAUDE.md、`check_env.py`、技术方案 3.4 / 3.5 同步修改；用户本机 `.env` 需手动补 `/v1`。

## R3 `deepseek` 类型怎么实现

- **Decision**: 与 `openai` 共用 `OpenAiChatModel`，区别只在参数映射：输出上限 `openai` 发 `max_completion_tokens`，`deepseek` 发 `max_tokens`。
- **Rationale**: `spring-ai-deepseek` 的 `ChatCompletionMessage.content` 只有 `String`，图片会被丢掉。`OpenAiChatOptions` 同时有 `maxTokens(...)` 和 `maxCompletionTokens(...)`，替身确认只设其中一个时请求体里只出现对应字段。真实 DeepSeek 经 `OpenAiChatModel` 调用：同步、流式、图片都返回 200，响应里的 `reasoning_content` 不影响解析；`max_tokens=20` 时结束原因为 `LENGTH`。
- **Alternatives**: 保留 DeepSeek 专用模块、对图片报错；或自己重写它的消息转换。都已在 clarify 中被用户否决。

## R4 请求字段

- **Decision**: 请求级选项只放本次 Profile 设置过的字段：`model`、`reasoningEffort`、输出上限、`temperature`、`toolCallbacks`、`toolContext`、`timeout`、`streamUsage`（仅流式）。连接级选项只放 `baseUrl`、`apiKey`、`maxRetries(0)`、`timeout`。
- **Rationale**: 替身记录到：不设选项时请求体只有 `{"messages":[...],"model":"m"}`，没有 `temperature`、`top_p` 等默认值；`toolContext` 的键和值都不出现在请求体里；不传工具时没有 `tools` 字段。满足 FR-008、FR-010、FR-011。

## R5 单次请求超时

- **Decision**: 每次尝试把 `min(连接超时, 截止时间 - 当前时间)` 写进请求级 `OpenAiChatOptions.timeout`。截止时间已过则不发请求。
- **Rationale**: 替身 3 秒后才响应，连接超时 5 秒、请求级超时 1 秒时，调用在 1.0 秒后抛 `OpenAIIoException`。不需要在外面再套一层 `Future`。
- **Alternatives**: 外层 `CompletableFuture.get(timeout)` 加取消。实测也能在 1 秒拿回控制权，但底层请求不会立即停止，还多一个线程。作为兜底不需要。

## R6 失败分类与重试

- **Decision**: 关闭 SDK 重试（`maxRetries(0)`），由 `ProviderService` 按 SDK 异常类型分类：

  | SDK 异常 | FailureKind | 重试 |
  |----------|-------------|------|
  | `RateLimitException`（429） | RATE_LIMITED | 是 |
  | `InternalServerException` 且状态码为 500 / 502 / 503 / 504 | SERVER_ERROR | 是 |
  | 其他 5xx | SERVER_ERROR | 否 |
  | `OpenAIIoException` 且线程未被中断（连接失败、读超时） | CONNECTION | 是 |
  | `UnauthorizedException`、`PermissionDeniedException` | AUTH | 否 |
  | `BadRequestException`、`NotFoundException`、`UnprocessableEntityException` | INVALID_REQUEST | 否 |
  | 响应没有候选结果 | PROTOCOL | 否 |
  | 线程被中断 | CANCELLED | 否 |

- **Rationale**: `maxRetries(0)` 时替身在 429 后只收到 1 次请求，SDK 不会偷偷重试。各状态码对应的异常类型已实测。
- **Retry-After**: 从 `OpenAIServiceException.headers().values("Retry-After")` 读取（实测可取到 `[2]`），按秒解析；没有、为负数或格式无法解析时使用 500 毫秒；HTTP-date 暂不支持，解析失败走同一兜底。
- **重试门槛（2026-11-09 修订）**: 先计算"等待 Retry-After 后的预计剩余时间"，若小于连接的 `retry-min-remaining`（默认 15 秒）则不等待、不重试；只有预算足够才等待，等待后再用单调时钟复核 deadline。重试请求的超时取 min(连接超时, 剩余时间)。没有截止时间时最多重试 1 次，但 Retry-After 等待不能无限期阻塞：超过连接的 `retry-max-wait` 时不重试。原方案要求剩余时间 ≥ 一个完整连接超时（120 秒），会让一轮后半段的暂时故障直接失败；新规则保留最小安全预算，同时允许剩余 15~119 秒时尝试缩短超时的重试。
- **Alternatives**: 仅判断剩余时间大于 0。没选：剩余 1 秒时重试几乎必然超时；使用历史平均耗时动态估算门槛。没选：US-1 尚无稳定统计，固定可配门槛更容易理解和测试。

## R7 错误信息脱敏

- **Decision**: 调用记录的错误摘要只由 `FailureKind`、HTTP 状态码、经过安全过滤的 SDK `type()` / `code()` 组成。不调用 SDK 异常的 `getMessage()`，也不记录原始响应体或原始异常 cause。`type()` / `code()` 只保留 `[A-Za-z0-9_.-]`，最长 64 字符；缺失或不符合规则时使用固定占位符。
- **Rationale**: 替身返回 401，错误体里放了 `sk-SECRET`，`UnauthorizedException.getMessage()` 原样包含它；服务端错误体的 type/code 也属于不可信输入，必须限字符、限长并与 SDK 原始异常隔离。

## R8 用量与限流

- **Decision**: `EmptyUsage` 时 token 三项记为空；`getCacheReadInputTokens()` / `getCacheWriteInputTokens()` 原样记录（`null` 就是空）；`EmptyRateLimit` 时限流余量为空，`OpenAiRateLimit` 时取 `getRequestsRemaining()` / `getTokensRemaining()`。
- **Rationale**: 替身确认：没有 usage 时得到 `EmptyUsage`，`getPromptTokens()` 返回 0 而不是 null，必须按类型判断（FR-023）；带 `cached_tokens` 时 `cacheRead` 有值、`cacheWrite` 为 null；带 `x-ratelimit-remaining-*` 头时得到 `OpenAiRateLimit`。真实服务：两家都没有限流头；DeepSeek 同步调用缓存 token 为 0，流式时为 null，按 FR-023 原样记录。

## R9 流式

- **Decision**: `ChatModel.stream` 外面套 `MessageAggregator`，文本增量交给调用方的输出端；流式请求总是设 `streamUsage(true)`。是否已交出增量，以输出端是否收到过非空文本为准（FR-019）。
- **Rationale**: 替身按 3 个分片发文本时，`stream` 逐段交出 `北京`、`今天`、`晴`。工具调用的参数被拆成 3 个分片时，SDK 只交出 1 个已拼好的结果（`{"city":"北京"}`），不会出现半截 JSON。`streamUsage(true)` 时请求体带 `stream_options`，最后一个分片带用量，聚合后用量不重复累加。真实 DeepSeek 流式 22 个分片，聚合正常。

## R10 图片

- **Decision**: 只处理 `UserMessage.getMedia()`；MIME 类型为 `image/*` 的放行，其他（音频、视频、文件）外呼前失败。内嵌数据和网址都交给 SDK 转换。
- **Rationale**: 替身确认：内嵌字节变成 `data:image/png;base64,...`，`URI` 原样作为 `image_url`，文本、内嵌图、网址图的顺序与原消息一致。真实服务：中转识别出红色；DeepSeek 带图片时 prompt_tokens 从 34 变为 223，图片确实发出。

## R11 向量化

- **Decision**: 不注册 `EmbeddingModel` Bean。`DefaultEmbeddingService` 内部持有至多一个 `OpenAiEmbeddingModel`，自己按 `max-batch-size`（10）分批、按顺序合并结果、按 R6 重试，并作为 core 的 `EmbeddingService` 暴露。US-3 构建 `PgVectorStore` 时从 `EmbeddingService` 取底层模型。
- **Rationale**: 替身确认 `embed(11 条)` 只发 1 次请求，SDK 不分批；百炼单次超过 10 条返回 400。门面能在"未启用"时给出明确错误（FR-031），而"没有 Bean"做不到；也保证禁用时向量化实例数为 0（SC-014）。真实百炼经 SDK、base-url 带 `/v1` 时返回 1024 维。
- **Alternatives**: 注册一个包装好的 `EmbeddingModel` Bean。没选：禁用时调用方只能拿到"找不到 Bean"，错误不明确；而且 Spring AI 的向量库自动配置会因为出现 `EmbeddingModel` Bean 而被激活。

## R12 向量化启动校验（2026-11-09 修订为默认后台校验）

- **Decision**: 启用且 key 非空时，向量化一次 `"ping"`，用返回长度与配置的维度比对。`agentos.embedding.startup-check` 取 `async`（默认）或 `sync`：
  - `async`：应用就绪后（`ApplicationReadyEvent`）在一个虚拟线程上校验，状态先为 `CHECKING`，启动不等待。校验完成前的 `embed` 调用等待校验结果，最多一个校验超时；超时仍未完成则报"校验中"。
  - `sync`：在 `DefaultEmbeddingService` 初始化时校验，维度不一致抛异常、拒绝启动（原方案的行为）。
  - 结果：维度一致 → `READY`；维度不一致 → `MISCONFIGURED`（ERROR 日志写明配置值和实际值），不再自动重试，修正配置并重启才恢复；IO 异常为"不可达"、401 / 403 为"鉴权失败"、错误码含 `Arrearage` 为"欠费"、其余为"其他"，都标记为 `UNAVAILABLE` 并打 WARN。
  - 后台重新校验：`UNAVAILABLE` 且原因不是"鉴权失败"时，按 1 → 2 → 4 … 分钟退避，最长 30 分钟一次，成功后转为 `READY`。鉴权失败的 key 来自环境变量，运行期间不会变，重试没有意义。重新校验的定时任务在应用关闭时停止。
  - 每次 `embed` 都检查返回向量的长度，不一致时本次调用失败、不返回任何向量，并转为 `MISCONFIGURED`，防止服务端换了模型后把错误维度的向量写进向量表。
  - 校验请求的超时取 min(向量化超时, 10 秒)。状态用 `AtomicReference<EmbeddingStatus>` 保存，校验和调用并发时不需要加锁。
- **Rationale**: spec FR-032 与 2026-11-09 clarify。同步校验让启动耗时依赖外部服务：多实例滚动重启时，每个实例都要等一次外网请求，服务慢时还会碰到 10 秒上限。默认后台校验后，启动只依赖本地配置；维度不一致仍然不会产生坏数据，因为向量化调用在 `MISCONFIGURED` 时全部拒绝，且每次调用都校验长度。需要"配错就起不来"的环境（例如 CI 部署验收）选 `sync`。欠费的错误码来自早先用旧 key 的实测（HTTP 400，`Arrearage`）。
- **Alternatives**: 完全不做启动校验、只在第一次调用时检查。没选：问题要等到第一次写入才暴露，运维看不到状态。把同步校验作为默认。没选：这会让每次启动依赖外部 Embedding 服务，慢服务会拖住多实例滚动重启；当前默认选择 `async`，只有需要“维度错误就拒绝启动”的环境才配置 `sync`。让 `async` 模式下维度不一致时直接退出。没选：多实例下会导致所有实例反复崩溃重启，而拒绝向量化调用已经足够防止坏数据。

## R13 观测

- **Decision**: `OpenAiChatModel` / `OpenAiEmbeddingModel` 构建时传入容器的 `ObservationRegistry`。Prompt 和回复内容不进观测：`spring.ai.chat.observations.log-prompt/log-completion=false` 已配置。
- **Rationale**: 两个构建器都提供 `observationRegistry(...)`；容器里的 `ObservationRegistry` 在关闭模型自动配置后仍然存在（已实测）。

## R14 HTTP 替身

- **Decision**: 测试用 JDK 内置 `com.sun.net.httpserver.HttpServer` 写一个 `StubModelServer`：按脚本依次返回状态码、响应头、JSON 或 SSE，记录每个请求的路径和请求体，可设置延迟。
- **Rationale**: Phase 0 的全部实验都用它完成，覆盖了同步、SSE、429、响应头、延迟。不新增依赖，也不会和 Boot 4 的依赖版本冲突。
- **Alternatives**: WireMock（依赖重，引入 Jetty）、OkHttp MockWebServer（要额外的测试依赖）。

## R15 结构化日志

- **Decision**: `LoggingLlmCallRecorder` 用 SLF4J 2 的 fluent API：`log.atInfo().setMessage("llm_call").addKeyValue(...)`，每个字段一个键；向量化日志同理。值为空的字段照样输出 `null`。
- **Rationale**: Spring Boot 4.1.2 的 ECS 格式化器会输出 SLF4J 的 key-value（字节码里有 `KeyValuePair` 处理），文件日志里每个字段是一个 JSON 键。符合 CLAUDE.md"日志用 SLF4J 占位符，不拼字符串"。

## R16 默认连接的启动校验

- **Decision**: `application.yaml` 里默认连接的 key 写成 `${AGENTOS_LLM_API_KEY:}`（缺省为空），由 `ProviderAutoConfiguration` 校验：默认连接不存在或 key 为空时启动失败，错误写明连接名和环境变量名；可选连接 key 为空时标记为不可用。连接类型先按字符串绑定，再自己校验，不支持的类型给出"连接名 + 类型 + 暂不支持"。
- **Rationale**: 不写缺省值时，环境变量缺失会得到 Spring 的通用报错"无法解析占位符"，不满足 FR-003 的"指明连接名和缺少的环境变量"。环境变量名从配置原文里的 `${...}` 取，不在代码里写死。
