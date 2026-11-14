---

description: "US-1 对接 LLM（Provider 抽象）的实施任务"
---

# Tasks: US-1 对接 LLM（Provider 抽象）

**Input**: `specs/001-llm-provider/` 下的 spec.md、plan.md、research.md、data-model.md、contracts/、quickstart.md

**Prerequisites**: plan.md、spec.md 已 review；宪章 v1.0.0

**Tests**: spec FR-027、FR-028 和 quickstart.md 明确要求离线协议测试与真实模型冒烟测试，所以每个 user story 都先写测试、确认失败，再实现。

**Organization**: 按 spec.md 的 6 个 user story 分阶段。任务里的 `[US1]` ~ `[US6]` 指 **spec 内的 user story**（按名称调用、重试、调用记录、流式、向量化、图片），不是项目路线图里的 US-1 ~ US-5。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可以并行（不同文件，且不依赖未完成的任务）
- **[Story]**: 所属 spec user story
- 路径都相对仓库根目录；core 契约包缩写为 `core/provider/` = `AgentOS-core/src/main/java/com/agentos/core/provider/`，provider 实现包缩写为 `provider/` = `AgentOS-provider/src/main/java/com/agentos/provider/`，provider 测试包缩写为 `provider-test/` = `AgentOS-provider/src/test/java/com/agentos/provider/`

## 全局约束（每个任务都要遵守）

- 代码注释用中文；依赖用构造器注入；日志用 SLF4J 占位符或 fluent API，不拼字符串，不打印 key、Prompt、工具参数、图片数据与网址。
- 不注册任何 `ChatModel` / `EmbeddingModel` Bean；不调用 SDK 异常的 `getMessage()` 生成记录或错误信息（plan「Phase 1 设计后复查」）。
- 自动化测试只用 `StubModelServer`，不出网、不读 `.env`；真实模型测试只在 `smoke/` 下，带 `@Tag("llm")`。
- 每完成一个阶段跑一次 `mvn -B verify`，失败时修代码，不放宽规则。

---

## Phase 1: Setup（依赖与配置清理）

**Purpose**: 让依赖、配置、文档与 plan「非代码改动」一致

- [X] T001 删除 `spring-ai-deepseek`、`spring-ai-anthropic`、`spring-ai-ollama` 三个依赖，只保留 `AgentOS-core` 与 `spring-ai-openai`，同步更新 `<description>`（spec FR-005） in AgentOS-provider/pom.xml
- [X] T002 surefire 默认配置 `excludedGroups=llm`；新增 profile `llm`，只运行 `@Tag("llm")` 的测试（`groups=llm` 并清空 `excludedGroups`），供 `mvn -B verify -Pllm` 使用 in pom.xml
- [X] T003 [P] 修改配置：`primary.api-key` 改为 `${AGENTOS_LLM_API_KEY:}`（research R16）；`primary` 增加 `retry-min-remaining: 15s`、`retry-max-wait: 120s`；`embedding.base-url` 缺省值改为 `https://dashscope.aliyuncs.com/compatible-mode/v1`；增加 `embedding.startup-check: async`；`spring.ai.retry` 的注释删去 DeepSeek 模块的说明（research R2、contracts/configuration.md「目标形态」） in AgentOS-boot/src/main/resources/application.yaml
- [X] T004 [P] `AGENTOS_LLM_BASE_URL`、`AGENTOS_EMBEDDING_BASE_URL` 的示例值补上 `/v1`，注释改为"base-url 写到 /v1，SDK 不会自动补"，删除"OpenAI 模块会自己拼接"的说法（research R2） in .env.example
- [X] T005 [P] 变量表中 `AGENTOS_LLM_BASE_URL` 与 `AGENTOS_EMBEDDING_BASE_URL` 的说明改为"写到 `/v1`"（research R2） in CLAUDE.md
- [X] T006 [P] 不再自动拼 `/v1`：请求地址改为 `<base-url>/chat/completions` 与 `<base-url>/embeddings` in scripts/check_env.py

---

## Phase 2: Foundational（core 契约 + 测试替身 + provider 骨架）

**Purpose**: 所有 user story 共用的类型和基础设施。字段定义以 [data-model.md](data-model.md) 为准

**⚠️ CRITICAL**: 本阶段完成前不能开始任何 user story

### core 契约（`AgentOS-core`，不依赖任何模型实现）

- [X] T007 [P] 新建枚举 `CallMode`（`CALL`、`STREAM`）与 `FailureKind`（`RATE_LIMITED`、`SERVER_ERROR`、`CONNECTION`、`AUTH`、`INVALID_REQUEST`、`PROTOCOL`、`CANCELLED`），Javadoc 写明"可重试的只有前三个，且 SERVER_ERROR 只限 500 / 502 / 503 / 504" in core/provider/CallMode.java、core/provider/FailureKind.java
- [X] T008 [P] 新建 record `Profile(provider, model, reasoningEffort, maxOutputTokens, temperature)`：`provider` 可空（"为空时用 `agentos.default-provider`"）；`model` 必填但构造时不校验空白（由 ProviderService 外呼前检查）；构造时只做格式检查："`maxOutputTokens > 0`、`temperature` 在 0 ~ 2"，违反抛 `AgentOSException(INVALID_ARGUMENT)` in core/provider/Profile.java
- [X] T009 [P] 新建 record `CallCorrelation(UUID sessionId, UUID turnId, Integer iteration)`，三个字段都可为空，提供 `none()` 静态方法 in core/provider/CallCorrelation.java
- [X] T010 新建 record `ProviderRequest(profile, messages, tools, toolContext, deadline, correlation)`：`profile` 必填；`messages` 必填且"至少一条"；`tools`、`toolContext`、`deadline`、`correlation` 可空，构造时把 null 归一为空集合 / `CallCorrelation.none()`，并对集合做不可变拷贝（依赖 T008、T009） in core/provider/ProviderRequest.java
- [X] T011 新建 record `LlmCallRecord`，字段与可空性逐项照抄 data-model.md 的表：`sessionId`/`turnId`/`iteration` 可空；`provider`、`requestedModel`、`mode`、`attempt`（1 或 2）、`imageCount`、`success`、`startedAt`、`durationMs` 不可空；`responseModel`、`responseId`、`finishReason`、`toolCallCount`、三项 token、两项缓存 token（`Long`）、两项限流余量（`Long`）、`outputCapHonored`、`failureKind`、`httpStatus`、`errorSummary` 可空；Javadoc 写明"空值与 0 不混用"与"不含 key、Prompt、工具参数、图片数据与网址、原始响应体"（依赖 T007、T009） in core/provider/LlmCallRecord.java
- [X] T012 新建接口 `LlmCallRecorder { void record(LlmCallRecord record); }`，Javadoc 写明"每次尝试结束时、把结果或异常交给调用方之前调用；实现抛出的异常不能被吞掉，也不能触发重发请求"；依赖 T011 in core/provider/LlmCallRecorder.java
- [X] T013 新建 record `ProviderResult(ChatResponse response, List<LlmCallRecord> calls)`（`calls` 不可变）与 `ProviderCallException extends AgentOSException`：错误码固定 `PROVIDER_UNAVAILABLE`，`getCalls()` 返回不可变列表，消息只放安全摘要；禁止把原始 SDK 异常作为 cause，避免后续打印堆栈泄露响应体或凭证（依赖 T011） in core/provider/ProviderResult.java、core/provider/ProviderCallException.java
- [X] T014 新建接口 `ProviderService`：`ProviderResult call(ProviderRequest)`、`ProviderResult stream(ProviderRequest, Consumer<String> textSink)`，Javadoc 引用 contracts/provider-service.md 的处理顺序与保证（依赖 T010、T013） in core/provider/ProviderService.java
- [X] T015 [P] 新建 `EmbeddingStatus`（state 取 `DISABLED` / `CHECKING` / `READY` / `UNAVAILABLE` / `MISCONFIGURED`，加 `reason`）与接口 `EmbeddingService`：`List<float[]> embed(List<String>)`、`float[] embed(String)`、`EmbeddingStatus status()`、`int dimensions()` in core/provider/embedding/EmbeddingStatus.java、core/provider/embedding/EmbeddingService.java

### 测试替身与 provider 骨架（`AgentOS-provider`）

- [X] T016 新建测试支持组件：`StubModelServer` 基于 JDK `com.sun.net.httpserver.HttpServer` 监听随机端口，按脚本依次返回状态码、响应头、JSON 或 SSE 分片，支持延迟与"永不响应"，记录请求方法/路径/请求头/请求体/请求次数并实现 `AutoCloseable`；同时新建 `TestSpringAiConfiguration`，提供官方自动配置所需的 `ToolCallingManager` 与 `TestObservationRegistry`（或官方 test observation 实现），测试中禁止 `ToolCallingManager.builder().build()`（research R14、constitution IV） in provider-test/support/StubModelServer.java、provider-test/support/TestSpringAiConfiguration.java
- [X] T017 [P] 新建 `ProviderProperties`（`@ConfigurationProperties("agentos")`）：`defaultProvider`；`providers` 为 `Map<String, Connection>`，`Connection` 含 `type`（先按 String 绑定，后续自行校验）、`baseUrl`、`apiKey`、`timeout`（Duration）、`retryMinRemaining`（Duration，缺省 15s）、`retryMaxWait`（Duration，缺省等于 timeout） in provider/config/ProviderProperties.java
- [X] T018 [P] 新建 `EmbeddingProperties`（`@ConfigurationProperties("agentos.embedding")`）：`enabled`、`baseUrl`、`apiKey`、`model`、`dimensions`、`maxBatchSize`、`timeout`、`startupCheck`（`async` / `sync`，缺省 `async`） in provider/config/EmbeddingProperties.java
- [X] T019 [P] 新建枚举 `ConnectionType { OPENAI, DEEPSEEK }`，提供"把输出上限写进请求选项"的方法：`OPENAI` 只写 `maxCompletionTokens`，`DEEPSEEK` 只写 `maxTokens`（spec FR-009、research R3） in provider/chat/ConnectionType.java
- [X] T020 新建 record `ProviderConnection(name, type, timeout, retryMinRemaining, retryMaxWait, available, envVarName, chatModel)`：`available=false` 时 `chatModel` 为 null（依赖 T019） in provider/chat/ProviderConnection.java
- [X] T021 新建 `ChatModelFactory`：按连接编程式构建 `OpenAiChatModel`，连接级选项只放 `baseUrl`、`apiKey`、`maxRetries(0)`、`timeout`；构建时传入容器注入的 `ToolCallingManager` 与 `ObservationRegistry`；不注册 Bean（research R1、R4、R13，依赖 T020） in provider/chat/ChatModelFactory.java
- [X] T022 新建 `LoggingLlmCallRecorder implements LlmCallRecorder`：用 SLF4J fluent API 写 INFO 事件 `llm_call`，`LlmCallRecord` 每个字段一个 key-value，空值照样输出；**不负责** `outputCapHonored` WARN（该告警由 ProviderService 发出，见 T039）；类注释加 `TODO(US-2): 由写 llm_calls 表的实现替换`（research R15） in provider/chat/LoggingLlmCallRecorder.java
- [X] T023 新建 `ProviderAutoConfiguration` 骨架（`@AutoConfiguration` + `@EnableConfigurationProperties`），用 `@ConditionalOnMissingBean(LlmCallRecorder.class)` 注册 `LoggingLlmCallRecorder`，注释写明"不注册任何 ChatModel / EmbeddingModel Bean"；登记到 `AgentOS-provider/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` in provider/config/ProviderAutoConfiguration.java

**Checkpoint**: `mvn -B verify` 通过；core 契约完整，替身可用，user story 可以开始

---

## Phase 3: User Story 1 - 按名称调用已配置的模型 (Priority: P1) 🎯 MVP

**Goal**: 按 provider 名称选连接、组装请求、调用一次模型，返回文本、工具调用请求和结束原因；不执行工具

**Independent Test**: 两个同协议连接分别指向两个替身，按名称调用，请求只到达对应替身，请求体符合 Profile

### Tests for User Story 1 ⚠️（先写、先失败）

- [ ] T024 [P] [US1] 编写 `ProviderRoutingTest`：两个 `openai` 连接指向两个替身，以 `primary` 调用时只有对应替身收到请求（SC-001）；Profile 不写 provider 时走 `default-provider`；未知 provider、连接不可用、`model` 为空白时抛 `AgentOSException(INVALID_ARGUMENT)`，消息分别为"未知的 provider：<名称>""provider 不可用：<名称>，缺少环境变量 <变量名>""Profile 缺少聊天模型"，两个替身请求数都为 0（US1-4、SC-002） in provider-test/chat/ProviderRoutingTest.java
- [ ] T025 [P] [US1] 编写 `RequestMappingTest`：最终路径为 `/v1/chat/completions`（base-url 写到 `/v1`）；Profile 设了推理强度和输出上限、没设温度时，请求体有 `reasoning_effort`、无 `temperature`（SC-008）；两种连接都用 OpenAI 兼容实现，输出上限按连接类型映射：`openai` 发 `max_completion_tokens`、`deepseek` 发 `max_tokens`（SC-015）；不传工具时无 `tools` 字段；`toolContext` 的键和值都不出现在请求体（FR-010、FR-011）；替身返回 2 个工具调用时结果按顺序原样带回 ID、名称、参数，计数回调执行 0 次（US1-3、SC-009）；替身返回空 `choices` 时抛 `ProviderCallException`，记录 `failureKind=PROTOCOL` in provider-test/chat/RequestMappingTest.java
- [ ] T026 [P] [US1] 编写 `StartupValidationTest`（`ApplicationContextRunner`，仅验证静态配置与 Provider 校验，不负责 ApplicationReadyEvent 生命周期）：逐条覆盖 contracts/configuration.md「启动校验」表——默认连接不存在、默认连接 key 为空（错误含连接名和从 `${...}` 取出的变量名）、类型不是 `openai`/`deepseek`（含"暂不支持"）、`timeout` 非正数、`base-url` 为空、`retry-min-remaining` 非正数或大于 `timeout`、`retry-max-wait` 非正数或小于 `retry-min-remaining`、`Primary` 与 `primary` 并存时启动失败；可选连接 key 为空时启动成功且标记不可用；只补上 `primary` 后启动成功（US1-6、SC-011）；容器里没有 `ChatModel` / `EmbeddingModel` Bean in provider-test/chat/StartupValidationTest.java

### Implementation for User Story 1

- [ ] T027 [P] [US1] 新建 `RequestValidator`：解析连接（`profile.provider` 为空取默认连接，名称不区分大小写匹配）、检查连接可用、检查 `model` 非空白，失败抛 `AgentOSException(INVALID_ARGUMENT)`，消息与 T024 一致 in provider/chat/RequestValidator.java
- [ ] T028 [US1] 新建 `DefaultProviderService implements ProviderService` 并实现 `call`（单次尝试）：检查 deadline 已过时抛 `ProviderCallException(PROVIDER_UNAVAILABLE)` 且不记录、不发请求；其他外呼前校验 → 组装请求级 `OpenAiChatOptions`，只写 Profile 设置过的字段（`model`、`reasoningEffort`、按 `ConnectionType` 映射的输出上限、`temperature`）以及 `toolCallbacks`、`toolContext`，`timeout = min(连接超时, deadline − now)` → `chatModel.call(prompt)` → 无候选结果按 PROTOCOL 失败 → 每次尝试结束后立刻 `recorder.record(...)`，记录先填 correlation、provider、requestedModel、responseModel、responseId、mode、attempt、finishReason、toolCallCount、imageCount、success、startedAt、durationMs（单调时钟）；`stream` 先抛 `UnsupportedOperationException`，US4 实现（依赖 T021、T022、T027） in provider/chat/DefaultProviderService.java
- [ ] T029 [US1] 完成 `ProviderAutoConfiguration`：按 contracts/configuration.md「启动校验」表校验 `ProviderProperties`；US-1 临时由 Spring placeholder 解析环境变量、本类负责 Provider 结构和必填校验，代码标 `TODO(US-5): 迁入 ConfigLoader`；环境变量名从配置原文的 `${...}` 取出（通过 `Environment` 的原始属性源，不在代码里写死，research R16）；用 `ChatModelFactory` 建好不可变的 `Map<String, ProviderConnection>`；可选连接不可用时打一条 INFO；注册 `DefaultProviderService` 为 `ProviderService` Bean（依赖 T023、T028） in provider/config/ProviderAutoConfiguration.java
- [ ] T030 [P] [US1] 追加 2 条 ArchUnit 规则：只有 `com.agentos.provider..` 可以依赖 `org.springframework.ai.openai..`；任何 `@Bean` 方法的返回类型都不能是 `ChatModel` 或 `EmbeddingModel` 的子类型（plan「质量门」、FR-004、FR-030） in AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java
- [ ] T031 [P] [US1] 编写冒烟测试 `PrimarySmokeTest`、`DeepSeekSmokeTest`（`@Tag("llm")` + `@EnabledIfEnvironmentVariable`，分别按 `AGENTOS_LLM_API_KEY`、`AGENTOS_DEEPSEEK_API_KEY` 判断，模型名取 `AGENTOS_LLM_MODEL`、`AGENTOS_DEEPSEEK_MODEL`）：同步调用返回非空文本，记录里实际模型和 token 用量有值；`DeepSeekSmokeTest` 设很小的输出上限时结束原因为 `LENGTH`；`PrimarySmokeTest` 只检查请求字段、不检查结束原因（中转接口不遵守上限，spec SC-015） in provider-test/smoke/PrimarySmokeTest.java、provider-test/smoke/DeepSeekSmokeTest.java

**Checkpoint**: `mvn -B verify` 通过；按名称调用可用，US-2 的 ReAct 循环可以开始依赖 `ProviderService.call`

---

## Phase 4: User Story 2 - 失败时有限重试，并在截止时间内结束 (Priority: P2)

**Goal**: 只对暂时性故障追加 1 次重试，遵守 `Retry-After`、`retry-min-remaining` 与无 deadline 时的 `retry-max-wait`，任何调用都不超过本轮截止时间

**Independent Test**: 替身按脚本返回 429、503、401 等，统计请求次数、间隔和最终错误类型

### Tests for User Story 2 ⚠️

- [ ] T032 [P] [US2] 编写 `RetryPolicyTest`：429 + `Retry-After: 1` 后成功 → 2 次请求、间隔 ≥ 1 秒、拿到成功结果（US2-1）；503 两次 → 2 次请求后 `ProviderCallException`（US2-2）；401、400、501 → 只 1 次请求（US2-3、SC-003）；连接被拒（替身已关闭）→ 按 CONNECTION 重试 1 次；无 `Retry-After` 时等待约 500 毫秒；没有截止时间时可重试失败一律重试 1 次；调用线程被中断 → 不重试、记 CANCELLED、中断标志被恢复 in provider-test/chat/RetryPolicyTest.java
- [ ] T033 [P] [US2] 编写 `DeadlineTest`（时间按比例缩短，例如连接超时 6 秒、最少剩余 2 秒）：预计等待 `Retry-After` 后剩余时间小于 `retry-min-remaining` → 不等待、不重试、立即失败（US2-4、SC-021）；`Retry-After` 大于本轮剩余时间 → 不等待、不重试且总耗时不超过预算（SC-004）；剩余时间够门槛但不够一个连接超时 → 发出第 2 次请求，其超时缩短到剩余时间，替身不响应时在截止时间失败（US2-6、SC-021）；连接超时 6 秒、剩余 1.5 秒、替身不响应 → 约 1.5 秒失败（US2-5）；所有场景总耗时 ≤ 剩余时间 + 1 秒（SC-004）；调用前截止时间已过 → `ProviderCallException`，`calls` 为空、请求数为 0 in provider-test/chat/DeadlineTest.java

### Implementation for User Story 2

- [ ] T034 [P] [US2] 新建 `FailureClassifier`：按 research R6 的表把 SDK 异常映射为 `FailureKind`（`RateLimitException`→RATE_LIMITED；`InternalServerException` 且状态码 500/502/503/504→可重试的 SERVER_ERROR，其他 5xx 不可重试；线程未中断的 `OpenAIIoException`→CONNECTION；401/403→AUTH；400/404/422→INVALID_REQUEST；线程中断→CANCELLED）；从 `Retry-After` 按秒解析，缺失/负数/非法格式用 500ms，HTTP-date 暂不支持；无 deadline 时 Retry-After 超过连接的 `retry-max-wait` 应直接放弃重试；不调用 `getMessage()`；新增安全摘要方法：type/code 只接受 `[A-Za-z0-9_.-]` 且最多 64 字符，不符合时使用固定占位，Connection/Protocol/Cancelled 没有状态字段时使用固定模板 in provider/chat/FailureClassifier.java
- [ ] T035 [US2] 在 `DefaultProviderService.call` 加入重试：首次失败交 `FailureClassifier` 分类；先计算“等待 Retry-After 后的预计剩余时间”，若小于 `retryMinRemaining` 或 Retry-After 会超过剩余预算则不等待、不重试，立即失败；无 deadline 时若 Retry-After 超过连接的 `retry-max-wait` 也不等待；Retry-After 缺失、负数或非法格式按 500ms 兜底；预算足够才等待，等待后用单调时钟复核 deadline，再以 min(连接超时, 剩余时间) 发第 2 次请求；最多 2 次请求；失败时抛 `ProviderCallException`，附带全部记录；等待或请求中被中断时记 CANCELLED、不重试、恢复中断标志；不把原始 SDK 异常作为 cause（spec FR-017 ~ FR-021，依赖 T028、T034） in provider/chat/DefaultProviderService.java

**Checkpoint**: 重试与截止时间测试全部通过，US1 的测试仍然通过

---

## Phase 5: User Story 3 - 每次请求都留下可追溯的调用记录 (Priority: P2)

**Goal**: 每次实际请求（含重试、失败）一条记录，空值与 0 不混用，不泄露凭证、Prompt、工具参数

**Independent Test**: 替身返回带完整用量、缺用量、缺限流头等响应，检查记录字段和条数

### Tests for User Story 3 ⚠️

- [ ] T036 [P] [US3] 编写 `CallRecordTest`（用一个收集型 `LlmCallRecorder`）：429 后成功 → 2 条记录，第 1 条 `success=false` 且带 `failureKind`、`httpStatus`、`errorSummary`，第 2 条带用量（US3-1、SC-005）；响应无 usage → 三项 token 为 null，不是 0；无缓存字段、无限流头 → 缓存 token 与限流余量为 null（US3-2、SC-006）；带 `cached_tokens` → `cacheReadTokens` 等于该值、`cacheWriteTokens` 为 null（US3-3）；带 `x-ratelimit-remaining-*` → 限流余量有值；传入 `CallCorrelation` 时原样带上，不传时为 null（US3-5）；完整断言 `provider`、`requestedModel`、`responseModel`、`responseId`、`mode`、`attempt`、`finishReason`、`toolCallCount`、`durationMs`；Profile 设了上限且 completionTokens 超过上限 → `outputCapHonored=false` 且 ProviderService 发出 WARN（收集型 Recorder 不影响告警），未超过 → `true`，Profile 没设上限 → null（SC-016）；记录条数等于替身收到的请求数；容器的 `ObservationRegistry` 收到了模型调用观测（FR-026） in provider-test/chat/CallRecordTest.java
- [ ] T037 [P] [US3] 编写 `RedactionTest`：用 Logback 的 `ListAppender` 捕获全部日志，替身在 401 错误体的 `error.type`、`error.code` 和 5xx body 中放 `sk-SECRET-TEST`，并覆盖连接异常与 cause chain；请求带 API key、完整对话和工具参数；断言日志、记录的 `errorSummary`、`ProviderCallException.getMessage()`、异常 cause/堆栈中都找不到 key 原文、Prompt 原文和工具参数（US3-4、SC-007） in provider-test/chat/RedactionTest.java

### Implementation for User Story 3

- [ ] T038 [P] [US3] 在 `FailureClassifier` 增加错误摘要方法：只由 `FailureKind`、HTTP 状态码、SDK 的 `type()` / `code()` 拼成，不含原始响应体（research R7） in provider/chat/FailureClassifier.java
- [ ] T039 [US3] 在 `DefaultProviderService` 补齐记录字段：`EmptyUsage` 时三项 token 为 null，否则原样取值；`getCacheReadInputTokens()` / `getCacheWriteInputTokens()` 原样记录；`EmptyRateLimit` 时限流余量为 null，`OpenAiRateLimit` 时取 `getRequestsRemaining()` / `getTokensRemaining()`；计算 `outputCapHonored`；失败记录填 `failureKind`、`httpStatus`、安全 `errorSummary`；当 `outputCapHonored=false` 时由 ProviderService 发 WARN（不放在 Recorder）；`recorder.record` 抛出的异常向上抛、不重发请求；异常 cause 不携带原始 SDK 异常（research R8，依赖 T035、T038） in provider/chat/DefaultProviderService.java

**Checkpoint**: 调用记录字段与 data-model.md 一致，日志不含敏感信息

---

## Phase 6: User Story 4 - 流式调用 (Priority: P3)

**Goal**: 文本增量按序交给调用方，结束时交出与同步调用等价的完整结果；交出增量后不重试

**Independent Test**: 替身分片返回文本和参数被拆开的工具调用，比对聚合结果并检查增量顺序

### Tests for User Story 4 ⚠️

- [ ] T040 [P] [US4] 编写 `StreamingTest`：替身按 SSE 分 3 段发文本 → `textSink` 依次收到 3 段；工具调用参数拆成 3 个分片 → 聚合结果里参数完整，计数回调执行 0 次（US4-1、SC-009）；请求体带 `stream_options.include_usage`；首字节超时、已发分片后持续慢速越过 deadline → 在 deadline + 1 秒内取消订阅并失败，已发增量后不重试；未发增量前遇到 503 → 重试 1 次；正常流式只有 1 条记录、`mode=STREAM`、用量不重复累加；`textSink` 抛异常或线程中断 → 记为 CANCELLED、恢复中断标志（US4-2 ~ US4-4、SC-004） in provider-test/chat/StreamingTest.java

### Implementation for User Story 4

- [ ] T041 [US4] 实现 `DefaultProviderService.stream`：复用 `call` 的校验、请求组装、重试与记录逻辑，请求总是设 `streamUsage(true)`；`chatModel.stream(prompt)` 外套 `MessageAggregator`，非空文本增量按序交给 `textSink`，用一个标志记录"是否已交出增量"；对聚合 Flux/订阅增加按剩余 deadline 的显式 timeout/cancel，不能只依赖 SDK read timeout；已交出后失败不重试；`textSink` 抛出的异常或线程中断视为调用方取消并恢复中断标志；返回聚合后的 `ChatResponse`（research R9，依赖 T039） in provider/chat/DefaultProviderService.java
- [ ] T042 [P] [US4] 在两个冒烟测试中增加流式调用：返回非空文本，聚合结果里的工具调用完整（SC-010） in provider-test/smoke/PrimarySmokeTest.java、provider-test/smoke/DeepSeekSmokeTest.java

**Checkpoint**: 同步与流式两条路径的结果等价

---

## Phase 7: User Story 5 - 文本向量化 (Priority: P3)

**Goal**: 全局唯一的向量化服务，自动分批、每次检查维度，默认后台校验、不阻塞启动

**Independent Test**: 替身模拟向量化服务，检查地址、模型、维度参数、分批次数、结果顺序和状态转换

### Tests for User Story 5 ⚠️

- [ ] T043 [P] [US5] 编写 `EmbeddingServiceTest`：向量化 1 段文本 → 1 个 1024 维向量，请求路径为 `/v1/embeddings`、请求体显式带 `dimensions`（US5-1）；11 条、`max-batch-size=10` → 2 次请求、11 个向量、顺序与输入一致（US5-2、SC-013）；空列表 → 0 次请求；未启用或 key 为空 → 不创建 `OpenAiEmbeddingModel`，调用抛 `AgentOSException(PROVIDER_UNAVAILABLE, "向量化未启用")`（US5-3、SC-014）；校验通过后某次响应维度不一致 → 本次调用失败、不返回任何向量、状态转为 `MISCONFIGURED`（US5-8、SC-012）；503 重试 1 次、401 不重试（FR-034）；`embedding_call` 日志含模型、条数、请求次数、token、成功与否、耗时，不含原文与向量值（FR-035） in provider-test/embedding/EmbeddingServiceTest.java
- [ ] T044 [P] [US5] 编写 `EmbeddingStartupTest`：使用非 Web `SpringApplicationBuilder` 或可控事件触发器，不使用只刷新上下文的 `ApplicationContextRunner` 测量 ApplicationReadyEvent；`async` 时替身延迟 5 秒响应，上下文启动不等待，状态为 `CHECKING`（US5-7、SC-020）；`CHECKING` 期间的调用等到校验结果后成功；校验超时仍未完成 → 调用报"向量化校验中"；维度不一致 → `MISCONFIGURED` + ERROR 日志写明配置值和实际值，之后调用报"向量化配置错误"（US5-4）；不可达、401、错误码含 `Arrearage` → `UNAVAILABLE`，reason 分别为"不可达 / 鉴权失败 / 欠费"，并打 WARN（US5-5）；不可达或欠费后替身恢复 → 后台重新校验后变为 `READY`（使用可注入的退避策略/时钟，测试间隔缩短为毫秒级）；401 后不重新校验；`sync` 时维度不一致 → 启动失败，`sync` 时不可达 → 启动成功且为 `UNAVAILABLE`；`dimensions`、`max-batch-size` 非正数或 `startup-check` 取值非法 → 启动失败；上下文关闭后重新校验任务停止 in provider-test/embedding/EmbeddingStartupTest.java

### Implementation for User Story 5

- [ ] T045 [P] [US5] 新建 `EmbeddingProbe`：向量化一次 `"ping"`，超时取 min(向量化超时, 10 秒)；比对返回长度与 `dimensions`；按 research R12 分类失败（IO 异常→不可达、401/403→鉴权失败、错误码含 `Arrearage`→欠费、其余→其他）；只返回校验结果，不修改状态 in provider/embedding/EmbeddingProbe.java
- [ ] T046 [US5] 新建 `DefaultEmbeddingService implements EmbeddingService`：内部至多持有一个 `OpenAiEmbeddingModel`（`maxRetries(0)`、显式维度、传入 `ObservationRegistry`，不注册 Bean）；状态存 `AtomicReference<EmbeddingStatus>`，状态转换必须防止过期探测把 `MISCONFIGURED` 覆盖回 `READY`；`embed` 按 `maxBatchSize` 顺序分批、每批用 `FailureClassifier` 最多重试 1 次、检查每个向量长度；各状态下的行为照 contracts/provider-service.md 的表实现，`CHECKING` 时用校验任务的 `CompletableFuture` 最多等一个校验超时；写 `embedding_call` 结构化日志，错误摘要复用安全摘要规则（依赖 T034、T045） in provider/embedding/DefaultEmbeddingService.java
- [ ] T047 [US5] 在 `ProviderAutoConfiguration` 注册 `EmbeddingService` 并接入启动校验：校验 `EmbeddingProperties`；`async` 时监听 `ApplicationReadyEvent`，在虚拟线程上运行 `EmbeddingProbe`；`sync` 时在初始化阶段运行，维度不一致抛异常拒绝启动；`UNAVAILABLE` 且原因不是鉴权失败时按 1 → 2 → 4 … 分钟退避重新校验（最长 30 分钟），退避策略/时钟通过构造器或包级测试入口可替换，应用关闭时停止；代码标 `TODO(US-5): 迁入 ConfigLoader`，US-1 临时由 Spring placeholder + 本模块校验；测试用注入的退避策略/时钟将间隔缩短，且通过可控的 ApplicationReadyEvent 触发器验证生命周期（依赖 T029、T046） in provider/config/ProviderAutoConfiguration.java
- [ ] T048 [P] [US5] 编写冒烟测试 `EmbeddingSmokeTest`（`@Tag("llm")`，按 `AGENTOS_EMBEDDING_API_KEY` 判断）：后台校验完成后状态为 `READY`，向量化返回 1024 维向量 in provider-test/smoke/EmbeddingSmokeTest.java

**Checkpoint**: 向量化可用；未启用时实例数为 0；维度错误不会把错误向量交给调用方

---

## Phase 8: User Story 6 - 聊天调用附带图片 (Priority: P3)

**Goal**: 用户消息可附带图片（内嵌或网址），两种连接类型行为一致；非图片媒体外呼前失败

**Independent Test**: 替身记录请求，检查图片与文本是否按原顺序一起发出

### Tests for User Story 6 ⚠️

- [ ] T049 [P] [US6] 编写 `ImageInputTest`：一段文字 + 一张内嵌 PNG，分别经 `openai`、`deepseek` 连接调用 → 请求里都有 `data:image/png;base64,...`，且与文本顺序一致（US6-1、SC-017）；图片网址原样作为 `image_url` 发出，不预先下载（US6-2）；附带 `audio/*`、`video/*` 或其他非图片媒体 → `AgentOSException(INVALID_ARGUMENT, "不支持的媒体类型：<MIME>")`，请求数为 0（US6-3、SC-018）；记录的 `imageCount` 等于图片数，日志里找不到 base64 数据和网址（US6-4、SC-019）；服务对图片返回 400 → 按 INVALID_REQUEST 失败、不重试（FR-038） in provider-test/chat/ImageInputTest.java

### Implementation for User Story 6

- [ ] T050 [US6] 在 `RequestValidator` 检查媒体：`UserMessage` 只允许 `image/*`，其他媒体外呼前失败；对任何非 UserMessage 携带的媒体也必须失败，不能静默丢弃或转发；同时统计图片数量，供记录的 `imageCount` 使用（research R10） in provider/chat/RequestValidator.java
- [ ] T051 [US6] 新建独立 `ImageSmokeTest`，不与 US4 并行修改 Primary/DeepSeek 冒烟测试：用纯红色图片询问颜色，回答包含"红色"（US6-5、SC-017） in provider-test/smoke/ImageSmokeTest.java

**Checkpoint**: 6 个 user story 全部可独立验证

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T052 [P] 同步技术方案：3.2 / 3.5 改为"聊天模型由 Profile 提供、连接不配模型"；3.4 / 3.5 / 8.8 改为"base-url 写到 `/v1`"并删除"模块会自己拼 `/v1`"；8.8 把 `DEEPSEEK_API_KEY` 改为 `AGENTOS_DEEPSEEK_API_KEY`；9.2 的 `llm_calls` 增加 `attempt`、`image_count`、`output_cap_honored` 三列；补充 `retry-min-remaining` 与向量化后台校验（spec Assumptions、research R2、R6、R12） in docs/TechnicalSolution.md
- [ ] T053 运行 `mvn spotless:apply` 后执行 `mvn -B verify`，Spotless、Checkstyle、SpotBugs、enforcer、ArchUnit 和全部离线测试必须通过；失败时修代码，不放宽规则或加排除项 in pom.xml
- [ ] T054 按 quickstart.md「人工确认」逐项检查：`AgentOS-provider/pom.xml` 只剩 `spring-ai-openai` 一个模型依赖；`LoggingLlmCallRecorder` 有指向 US-2 的 TODO；通过 boot 的结构化日志测试确认 `llm_call` 事件是一行 JSON，字段与 data-model.md 的 `LlmCallRecord` 一致（不把未配置的本地 `.AgentOS/logs` 当作强制产物） in specs/001-llm-provider/quickstart.md
- [ ] T055 仅提醒用户（不修改 `.env`）：把本机 `AGENTOS_LLM_BASE_URL`、`AGENTOS_EMBEDDING_BASE_URL` 按 contracts 写到 `/v1`，然后运行 `set -a; source .env; set +a; mvn -B verify -Pllm -pl AgentOS-provider -am`，确认 3 个冒烟测试的结果符合 quickstart.md 第 2 节 in specs/001-llm-provider/quickstart.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**：无依赖。T001、T002 改 pom，先做；T003 ~ T006 互相独立
- **Foundational（Phase 2）**：依赖 Setup 中的 T001；阻塞所有 user story
- **US1（Phase 3）**：依赖 Foundational；是 MVP，也是 US-2 ReAct 循环的前置
- **US2 → US3 → US4（Phase 4 ~ 6）**：都修改 `DefaultProviderService`，必须按此顺序串行
- **US5（Phase 7）**：依赖 Foundational、US1 的 T029（自动配置）和 US2 的 T034（`FailureClassifier`）；与 US3、US4 可以并行
- **US6（Phase 8）**：依赖 US1；`RequestValidator` 与 US2 ~ US4 不冲突，可以在 US1 之后任意时间做；T051 为独立 ImageSmokeTest，可与 US4 并行
- **Polish（Phase 9）**：依赖所有 user story

### User Story Dependencies

```text
Setup → Foundational → US1 ─┬→ US2 → US3 → US4
                            ├→ US6
                            └→ (US2 的 T034 完成后) US5
```

### Within Each User Story

- 测试先写，确认失败后再实现
- core 契约 → provider 内部类 → `DefaultProviderService` → 自动配置
- 每个阶段结束跑 `mvn -B verify`，按逻辑分组提交

### Parallel Opportunities

- Setup：T003、T004、T005、T006 可以同时做
- Foundational：T007、T008、T009、T015 互相独立；T012 依赖 T011；T016 ~ T019 互相独立，也可与 core 契约同时做
- 每个 user story 的测试任务互相独立，可以同时写
- US5 与 US3、US4 可以由不同的人并行推进；US6 也可以在 US1 之后插入

---

## Parallel Example: User Story 1

```bash
# 同时写 US1 的三组测试：
Task: "ProviderRoutingTest in provider-test/chat/ProviderRoutingTest.java"
Task: "RequestMappingTest in provider-test/chat/RequestMappingTest.java"
Task: "StartupValidationTest in provider-test/chat/StartupValidationTest.java"

# 实现阶段，RequestValidator 与 ArchUnit 规则、冒烟测试互不依赖：
Task: "RequestValidator in provider/chat/RequestValidator.java"
Task: "ArchUnit 规则 in AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java"
Task: "PrimarySmokeTest、DeepSeekSmokeTest in provider-test/smoke/"
```

## Parallel Example: User Story 5

```bash
# 测试：
Task: "EmbeddingServiceTest in provider-test/embedding/EmbeddingServiceTest.java"
Task: "EmbeddingStartupTest in provider-test/embedding/EmbeddingStartupTest.java"

# 实现：EmbeddingProbe 与冒烟测试可以先做，DefaultEmbeddingService 依赖 EmbeddingProbe
Task: "EmbeddingProbe in provider/embedding/EmbeddingProbe.java"
Task: "EmbeddingSmokeTest in provider-test/smoke/EmbeddingSmokeTest.java"
```

---

## Implementation Strategy

### MVP First（只做 User Story 1）

1. 完成 Phase 1 Setup 与 Phase 2 Foundational
2. 完成 Phase 3（US1）：按名称同步调用、请求字段正确、不执行工具、启动校验
3. **停下验证**：`mvn -B verify` 通过，`PrimarySmokeTest` 用真实模型跑通
4. 此时 US-2 的 ReAct 循环已经可以开始开发

### Incremental Delivery

1. US1 → 能调用模型（MVP）
2. US2 → 故障时有限重试，不拖过截止时间
3. US3 → 调用记录完整、脱敏（US-2 落库 `llm_calls` 的前提）
4. US4 → 流式调用
5. US5 → 向量化（US-3 记忆检索的前提）
6. US6 → 图片输入

每一步都不破坏之前的测试。

---

## Notes

- `[P]` = 不同文件且没有未完成依赖；同一文件的任务（如 `DefaultProviderService`）一律串行
- `[US1]` ~ `[US6]` 对应 spec.md 的 user story 编号，用于追溯
- 每个阶段结束是一个可以停下验证的检查点
- 不要为假想需求增加抽象（宪章原则七）；fallback、熔断、多模态向量不在本 story 范围
