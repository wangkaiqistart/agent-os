---
description: "Task list template for AgentOS tool registration and secure execution"
---

# Tasks: AgentOS 工具注册与安全执行

**Input**: Design documents from `/specs/004-agentos-tool-system/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 本规格明确要求注册、治理、HTTP、限次、超时、审计和恢复测试；测试任务按测试先行顺序列出。数据库测试必须使用 PostgreSQL Testcontainers，不得改用 H2/SQLite。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 确认模块边界、测试替身和配置入口，为所有用户故事提供可运行骨架。

- [X] T001 检查并补齐 AgentOS-core、AgentOS-tool、AgentOS-storage、AgentOS-boot 的 Maven 依赖与自动配置导入，保持 Tool 只存在于 AgentOS-tool，文件：pom.xml、AgentOS-core/pom.xml、AgentOS-tool/pom.xml、AgentOS-storage/pom.xml、AgentOS-boot/pom.xml、AgentOS-tool/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- [X] T002 [P] 统一工具默认配置与敏感值占位，写入模型迭代 10、单工具 10、本轮工具 30、整轮 30 秒、HTTP 重定向 3 次、响应上限 1 MiB 和普通结果上限 10000 字符，文件：AgentOS-boot/src/main/resources/application.yaml、AgentOS-core/src/main/java/com/agentos/core/config/TurnProperties.java、AgentOS-tool/src/main/java/com/agentos/tool/http/HttpToolProperties.java
- [X] T003 [P] 整理本地 HTTP 替身、模型替身和 PostgreSQL Testcontainers 的测试基座，确保默认测试不读取真实凭证、不访问共享库，文件：AgentOS-tool/src/test/java/com/agentos/tool/support/LocalHttpServer.java、AgentOS-boot/src/test/java/com/agentos/boot/support/LocalModelServer.java、AgentOS-storage/src/test/java/com/agentos/storage/support/DatabaseTestEnvironment.java、AgentOS-boot/src/test/java/com/agentos/boot/BootDatabaseTestEnvironment.java

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 固化跨用户故事共享的端口、状态、审计和安全输出边界；本阶段完成前不得开始故事实现。

- [X] T004 固化工具执行所需的 core 端口和显式上下文契约，确认 ToolCatalog、Sandbox、SandboxAction、ToolInvocationRecorder、ToolContextKeys 不依赖具体工具实现，文件：AgentOS-core/src/main/java/com/agentos/core/tool/ToolCatalog.java、AgentOS-core/src/main/java/com/agentos/core/tool/Sandbox.java、AgentOS-core/src/main/java/com/agentos/core/tool/SandboxAction.java、AgentOS-core/src/main/java/com/agentos/core/audit/ToolInvocationRecorder.java、AgentOS-core/src/main/java/com/agentos/core/turn/ToolContextKeys.java
- [X] T005 [P] 完成工具调用审计表、实体和唯一键的 PostgreSQL 迁移契约，要求 `(turn_id, iteration, tool_call_id)` 唯一、输入/结果/错误脱敏、状态包含 `in_progress`、`success`、`failed`、`timeout`、`sandbox_denied`、`interrupted`、`limit_skipped`、`unconfirmed`，文件：AgentOS-storage/src/main/resources/db/migration/V1__core_tables.sql（保持已发布内容）、AgentOS-storage/src/main/resources/db/migration/V2__tool_invocation_unconfirmed_status.sql、AgentOS-storage/src/test/java/com/agentos/storage/session/MigrationTest.java、AgentOS-storage/src/main/java/com/agentos/storage/session/ToolInvocationEntity.java、AgentOS-storage/src/main/java/com/agentos/storage/session/ToolInvocationRepository.java
- [X] T006 [P] 完善共享轮次预算、取消和调用账本，使模型 10 次、单工具 10 次、总工具 30 次和整轮 30 秒共用同一截止时间；允许的幂等重试也必须消耗工具预算，文件：AgentOS-core/src/main/java/com/agentos/core/turn/TurnExecutionBudget.java、AgentOS-core/src/main/java/com/agentos/core/turn/ToolCallLedger.java、AgentOS-core/src/main/java/com/agentos/core/turn/TurnGuard.java
- [X] T007 [P] 固化脱敏和错误边界，确保日志、用户错误和审计摘要不输出 prompt、凭证、完整工具参数或未受限响应正文，文件：AgentOS-core/src/main/java/com/agentos/core/tool/Redactor.java、AgentOS-core/src/main/java/com/agentos/core/error/ErrorCode.java、AgentOS-boot/src/main/java/com/agentos/boot/ToolRedactionConfiguration.java
- [X] T008 建立模块级架构规则和质量门检查，禁止 ToolCallingAdvisor、ThreadLocal 业务上下文、自建 ToolCallingManager、H2/SQLite 和越过 core 端口的依赖，文件：AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java、pom.xml、scripts/check_db_test_reports.py

**Checkpoint**: core 端口、PostgreSQL 审计表、共享预算、脱敏边界和架构门禁就绪；US1–US4 可开始独立开发。

---

## Phase 3: User Story 1 - 为 Agent 配置最小工具集合 (Priority: P1) 🎯 MVP

**Goal**: Agent 只向模型暴露自己声明且已注册的工具，未知配置在模型外呼前失败，运行时未授权名称只得到安全拒绝。

**Independent Test**: 使用本地模型替身加载合法、空集合、未知、重名和缺少定义的工具配置；核对模型可见工具精确子集、声明顺序、零模型外呼和零底层执行。

### Tests for User Story 1

- [X] T009 [P] [US1] 先编写并确认失败的工具目录契约测试，覆盖唯一名称、描述、可解析 inputSchema、returnDirect 元数据、重名拒绝、空集合和声明顺序，文件：AgentOS-tool/src/test/java/com/agentos/tool/registry/ToolRegistryTest.java
- [X] T010 [P] [US1] 先编写并确认失败的安全 resolver 与自动配置测试，覆盖请求级 callback 优先、未知名称不解析真实 Bean、拒绝结果可回传模型且底层执行次数为 0、只装配一个 Manager/resolver/processor，文件：AgentOS-tool/src/test/java/com/agentos/tool/ToolAutoConfigurationTest.java、AgentOS-tool/src/test/java/com/agentos/tool/govern/UnknownToolResolverTest.java
- [X] T011 [P] [US1] 先编写并确认失败的 Boot 授权集成测试，覆盖 Agent 配置未知工具在模型请求前失败、配置来源和工具名出现在安全错误中、空工具集合不发送 tools 定义；真实 CLI 验证零新增记录，CLI 单测验证异常 cause 和其他参数错误不回显，文件：AgentOS-boot/src/test/java/com/agentos/boot/ReActApplicationTest.java、AgentOS-boot/src/test/java/com/agentos/boot/ToolAuthorizationIntegrationTest.java、AgentOS-boot/src/test/java/com/agentos/boot/CliAcceptanceIntegrationTest.java、AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java

### Implementation for User Story 1

- [X] T012 [US1] 实现 ToolRegistry 注册校验和按 Agent 声明顺序返回精确 callback 子集，调用官方重名校验并拒绝缺少 name/description/inputSchema 的定义；未知工具由专用配置异常携带文件、字段和工具名，CLI 定向展示，文件：AgentOS-tool/src/main/java/com/agentos/tool/registry/ToolRegistry.java、AgentOS-core/src/main/java/com/agentos/core/tool/ToolCatalog.java、AgentOS-core/src/main/java/com/agentos/core/tool/UnknownAgentToolException.java、AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java
- [X] T013 [US1] 实现 UnknownToolResolver 安全兜底，只返回治理后的拒绝 callback，禁止查询 ApplicationContext、Function 或其他同名 Bean，文件：AgentOS-tool/src/main/java/com/agentos/tool/govern/UnknownToolResolver.java、AgentOS-tool/src/main/java/com/agentos/tool/ToolAutoConfiguration.java
- [X] T014 [US1] 在 Agent 加载和轮次开始前接入工具名单校验，未知工具不得创建轮次、消息、模型调用或工具审计记录，文件：AgentOS-core/src/main/java/com/agentos/core/turn/ReActLoop.java、AgentOS-core/src/main/java/com/agentos/core/turn/DefaultAgentService.java、AgentOS-core/src/main/java/com/agentos/core/agent/AgentLoader.java
- [X] T015 [US1] 接通 ToolCallback 元数据到 Provider 请求，确保空集合不发送工具定义、合法集合只发送治理 callback、returnDirect 元数据不丢失，文件：AgentOS-core/src/main/java/com/agentos/core/provider/ProviderRequest.java、AgentOS-core/src/main/java/com/agentos/core/turn/ReActLoop.java、AgentOS-provider/src/main/java/com/agentos/provider/chat/ChatModelFactory.java
- [X] T016 [US1] 运行 US1 独立验证并记录模型可见工具、未知配置零外呼、未授权调用零执行证据，文件：specs/004-agentos-tool-system/quickstart.md、AgentOS-boot/src/test/java/com/agentos/boot/ReActApplicationTest.java

**Checkpoint**: Agent 工具授权 MVP 可独立演示；可进入 US2 或并行开展 US3 的纯 HTTP 单元测试。

---

## Phase 4: User Story 2 - 在副作用前完成安全治理和审计 (Priority: P1)

**Goal**: 所有工具都先检查轮次/租约/预算、确认 `in_progress` 审计，再执行并更新原行；审计未确认时不执行或不重跑副作用。

**Independent Test**: 使用可计数替身工具覆盖成功、参数错误、沙箱拒绝、执行前审计失败、执行后审计失败、工具超时和租约丢失，核对执行次数、审计状态、错误传播和历史回填。

### Tests for User Story 2

- [ ] T017 [P] [US2] 先编写并确认失败的治理回调行为测试，覆盖账本 FIFO 领取、JSON 参数校验、begin 先于 delegate、工具超时取消、普通工具默认 10000 字符截断、结果脱敏、幂等工具仅在 `executionStarted=false` 的瞬时失败时最多重试 1 次、完成状态未知禁止重试、普通错误可继续、致命错误重抛，文件：AgentOS-tool/src/test/java/com/agentos/tool/govern/GovernedToolCallbackTest.java
- [ ] T018 [P] [US2] 先编写并确认失败的审计故障测试，覆盖 begin 失败零执行、complete 未确认进入 `unconfirmed` 并提示可能已执行、唯一键查证不重复插入、interrupted 终态不被迟到完成覆盖，文件：AgentOS-tool/src/test/java/com/agentos/tool/govern/AuditFailureTest.java、AgentOS-storage/src/test/java/com/agentos/storage/audit/JpaToolInvocationRecorderTest.java、AgentOS-storage/src/test/java/com/agentos/storage/audit/AuditTransactionTest.java
- [ ] T019 [P] [US2] 先编写并确认失败的 ToolExecutor/ReAct 历史测试，覆盖完整 conversationHistory 回填、调用 ID 关联、returnDirect 收口和过程 sink 异常停止后续通知，文件：AgentOS-core/src/test/java/com/agentos/core/turn/ToolExecutorTest.java、AgentOS-core/src/test/java/com/agentos/core/turn/ReActLoopStreamingTest.java
- [ ] T020 [P] [US2] 先编写并确认失败的 Boot PostgreSQL 闭环测试，按数据库 ID 核对 begin/complete 状态、工具消息、调用 ID、敏感信息缺失和审计未确认结果，文件：AgentOS-boot/src/test/java/com/agentos/boot/ReActApplicationTest.java、AgentOS-boot/src/test/java/com/agentos/boot/ToolAuditRedactionTest.java

### Implementation for User Story 2

- [ ] T021 [US2] 实现 GovernedToolCallback 的统一治理顺序：检查 TurnContext/租约/预算、领取调用 ID、begin 审计、可取消执行、普通结果默认 10000 字符截断、幂等工具瞬时失败最多 1 次重试、完成状态未知禁止重试、complete 审计和错误分类，文件：AgentOS-tool/src/main/java/com/agentos/tool/govern/GovernedToolCallback.java、AgentOS-core/src/main/java/com/agentos/core/turn/ToolCallLedger.java
- [ ] T022 [US2] 实现 JpaToolInvocationRecorder 的独立短事务、有限瞬时故障重试、唯一键查证和 `unconfirmed` 持久化语义，要求 complete 只更新原 `in_progress` 行，文件：AgentOS-storage/src/main/java/com/agentos/storage/audit/JpaToolInvocationRecorder.java、AgentOS-storage/src/main/java/com/agentos/storage/audit/AuditTransactions.java、AgentOS-core/src/main/java/com/agentos/core/audit/RetryingAuditWriter.java
- [ ] T023 [US2] 实现工具异常处理器，将普通业务错误、参数错误、未授权工具、沙箱拒绝和工具自身超时转成安全工具结果；审计失败、租约丢失和整轮超时继续抛出，文件：AgentOS-tool/src/main/java/com/agentos/tool/govern/AgentOsToolExecutionExceptionProcessor.java、AgentOS-core/src/main/java/com/agentos/core/turn/TurnDeadlineExceededException.java
- [ ] T024 [US2] 完善 ToolExecutor 对官方 Manager 的唯一调用、完整历史切片和 limit_skipped 补记，禁止自行遍历 callback 或构造 Manager，文件：AgentOS-core/src/main/java/com/agentos/core/turn/ToolExecutor.java
- [ ] T025 [US2] 将治理 callback、审计 recorder、resolver 和异常处理器接入唯一自动配置，确认请求级工具优先且 fallback 永不暴露真实未授权 Bean，文件：AgentOS-tool/src/main/java/com/agentos/tool/ToolAutoConfiguration.java、AgentOS-tool/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports、AgentOS-boot/src/main/resources/application.yaml
- [ ] T026 [US2] 运行 US2 独立闭环并记录先记后做、执行后未确认、历史回填、脱敏和 PostgreSQL 行状态证据，文件：specs/004-agentos-tool-system/quickstart.md、AgentOS-boot/src/test/java/com/agentos/boot/ReActApplicationTest.java、AgentOS-boot/src/test/java/com/agentos/boot/ToolAuditRedactionTest.java

**Checkpoint**: 任一内部工具均可复用统一治理和审计链；底层副作用没有审计许可不得发生。

---

## Phase 5: User Story 3 - 安全调用受白名单保护的 HTTP 工具 (Priority: P1)

**Goal**: `http_get` 只访问明确授权的 HTTP/HTTPS 地址，逐跳受白名单和共享截止时间约束，超限只返回安全错误。

**Independent Test**: 使用本地 HTTP 替身覆盖白名单、协议、user-info、根域/子域/IP、重定向、非 2xx、超大正文和慢速正文；核对请求数、结果和总耗时。

### Tests for User Story 3

- [ ] T027 [P] [US3] 先编写并确认失败的 WhitelistSandbox 契约测试，覆盖默认空白、大小写、精确域名、子域通配符、根域拒绝、精确 IP、非法协议、user-info 和非 HTTP 动作，文件：AgentOS-tool/src/test/java/com/agentos/tool/sandbox/WhitelistSandboxTest.java
- [ ] T028 [P] [US3] 先编写并确认失败的 HTTP GET 行为测试，覆盖 2xx 正文、非 2xx 安全状态、白名单拒绝零请求、逐跳重定向、缺失/非法 Location、最多 3 次跳转和工具 Schema，文件：AgentOS-tool/src/test/java/com/agentos/tool/http/HttpToolsTest.java、AgentOS-tool/src/test/java/com/agentos/tool/http/HttpToolPropertiesTest.java
- [ ] T029 [P] [US3] 先编写并确认失败的 HTTP 截止时间测试，覆盖首字节等待、响应头后慢正文、持续分片不续命、正文超过 1 MiB 只返回安全错误、取消后零重试，文件：AgentOS-tool/src/test/java/com/agentos/tool/http/HttpToolsDeadlineTest.java、AgentOS-tool/src/test/java/com/agentos/tool/support/LocalHttpServer.java

### Implementation for User Story 3

- [ ] T030 [US3] 实现 WhitelistSandbox 的 URL 解析与默认拒绝规则，要求绝对 HTTP/HTTPS、无 user-info、精确域名/精确 IP和仅子域通配，文件：AgentOS-tool/src/main/java/com/agentos/tool/sandbox/WhitelistSandbox.java、AgentOS-core/src/main/java/com/agentos/core/tool/SandboxAction.java
- [ ] T031 [US3] 实现 HttpToolProperties 的配置归一化和边界校验，默认重定向 3、正文 1 MiB、超时 30 秒，拒绝非法范围和非正超时，文件：AgentOS-tool/src/main/java/com/agentos/tool/http/HttpToolProperties.java
- [ ] T032 [US3] 在 T030 和 T031 完成后实现 HttpTools 的只读 GET、禁用自动跳转、逐跳沙箱检查、有界 BodySubscriber、共享单调 deadline、超限安全错误和非 2xx 安全结果，文件：AgentOS-tool/src/main/java/com/agentos/tool/http/HttpTools.java
- [ ] T033 [US3] 将 http_get 以 GovernedToolCallback 注册，并确保 ToolContext 不进入模型 Schema、HTTP 结果经过统一脱敏和治理长度限制，文件：AgentOS-tool/src/main/java/com/agentos/tool/ToolAutoConfiguration.java、AgentOS-tool/src/main/java/com/agentos/tool/http/HttpTools.java
- [ ] T034 [US3] 运行 US3 独立 HTTP 验证并记录白名单零请求、重定向边界、正文超限不回传、持续分片截止时间和非 2xx 脱敏证据，文件：specs/004-agentos-tool-system/quickstart.md、AgentOS-tool/src/test/java/com/agentos/tool/http/HttpToolsTest.java、AgentOS-tool/src/test/java/com/agentos/tool/http/HttpToolsDeadlineTest.java

**Checkpoint**: Agent 声明 `http_get` 后可通过本地替身完成受治理读取；未授权地址和超大响应不产生越权结果。

---

## Phase 6: User Story 4 - 在有界预算内完成连续工具调用 (Priority: P2)

**Goal**: 同步/流式 ReAct 工具闭环同时受模型迭代、单工具、总工具和共享截止时间约束，正常最终答案不被误判为限次。

**Independent Test**: 使用模型替身持续产生工具调用，覆盖默认值、配置覆盖、超限跳过、同步/流式事件、慢模型/工具/审计和正常最终收口，核对次数、事件和总耗时。

### Tests for User Story 4

- [ ] T035 [P] [US4] 先编写并确认失败的 ReAct 模型迭代限制测试，覆盖默认 10、覆盖 3、最后一次工具请求不发送下一次模型、正常完成不误报上限、不额外发送总结调用，文件：AgentOS-core/src/test/java/com/agentos/core/turn/ReActLoopLimitsTest.java
- [ ] T036 [P] [US4] 先编写并确认失败的工具调用限次测试，覆盖单工具、总工具、跨迭代累计、多个同名调用、limit_skipped 一一对应和致命异常不误标超限，文件：AgentOS-core/src/test/java/com/agentos/core/turn/ToolCallLimitsTest.java、AgentOS-core/src/test/java/com/agentos/core/turn/ToolExecutorTest.java
- [ ] T037 [P] [US4] 先编写并确认失败的整轮 deadline 与流式事件测试，覆盖同步/流式一致、持续 delta 不续命、过程 sink 异常停止回调、最终通知最多一次、工具/审计等待共享预算和 hard stop 收尾；对慢模型、慢工具、慢正文、审计等待和持续流式响应各重复 20 次，核对每类 p95 在预算加 1 秒内返回，文件：AgentOS-core/src/test/java/com/agentos/core/turn/TurnDeadlineTest.java、AgentOS-core/src/test/java/com/agentos/core/turn/ReActLoopStreamingTest.java、AgentOS-tool/src/test/java/com/agentos/tool/http/HttpToolsDeadlineTest.java
- [ ] T038 [P] [US4] 先编写并确认失败的 Boot 全链路限次/截止时间集成测试，使用真实装配的官方 Manager、治理 callback、数据库和本地模型/HTTP 替身核对实际调用数、跳过数和耗时，文件：AgentOS-boot/src/test/java/com/agentos/boot/ReActDeadlineIntegrationTest.java、AgentOS-boot/src/test/java/com/agentos/boot/ReActApplicationTest.java

### Implementation for User Story 4

- [ ] T039 [US4] 实现 ReActLoop 的模型迭代计数、MAX_ITERATIONS 安全收口、同步/流式工具闭环、完整历史追加和最终事件收口，文件：AgentOS-core/src/main/java/com/agentos/core/turn/ReActLoop.java、AgentOS-core/src/main/java/com/agentos/core/turn/ManagedTurnSink.java、AgentOS-core/src/main/java/com/agentos/core/turn/TurnSink.java
- [ ] T040 [US4] 接入并校验 Spring AI 工具限次配置，默认单工具 10、总工具 30、超限返回错误结果，且计数跨迭代不重置；幂等重试的每次底层尝试同样计入限次，文件：AgentOS-boot/src/main/resources/application.yaml、AgentOS-tool/src/main/java/com/agentos/tool/ToolAutoConfiguration.java、AgentOS-core/src/main/java/com/agentos/core/turn/ToolExecutor.java
- [ ] T041 [US4] 强化 TurnExecutionBudget、ToolCallLedger、GovernedToolCallback 和 HttpTools 的共享 deadline、Future/HTTP 取消、资源清理和租约丢失终止，文件：AgentOS-core/src/main/java/com/agentos/core/turn/TurnExecutionBudget.java、AgentOS-core/src/main/java/com/agentos/core/turn/ToolCallLedger.java、AgentOS-tool/src/main/java/com/agentos/tool/govern/GovernedToolCallback.java、AgentOS-tool/src/main/java/com/agentos/tool/http/HttpTools.java
- [ ] T042 [US4] 运行 US4 独立验收并记录模型次数、底层工具次数、limit_skipped 数量、过程/终结事件数量和实际耗时，文件：specs/004-agentos-tool-system/quickstart.md、AgentOS-boot/src/test/java/com/agentos/boot/ReActDeadlineIntegrationTest.java

**Checkpoint**: 同步和流式工具闭环均有硬上限；持续响应不能续命，正常最终回答不触发额外模型调用。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 完成跨故事质量门、文档和可演示证据。

- [ ] T043 [P] 更新 Tool 代码导读、配置说明和边界文档，准确反映已实现的 HTTP、审计、限次和未实现的远程工具/复杂策略范围，文件：docs/code-reading/AgentOS-tool.md、docs/TechnicalSolution.md
- [ ] T044 [P] 增补架构、脱敏和数据库测试报告中的 Tool 证据索引，文件：AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java、AgentOS-boot/src/test/java/com/agentos/boot/ToolAuditRedactionTest.java、scripts/check_db_test_reports.py
- [ ] T045 运行 Spotless、全量 Maven 质量门和数据库测试执行检查，修复失败但不得新增排除项或跳过 PostgreSQL 测试，文件：pom.xml、specs/004-agentos-tool-system/quickstart.md
- [ ] T046 [P] 在显式 `llm` profile 下运行真实模型天气冒烟；缺少凭证时标记 NOT_RUN，不在默认验证中发送真实请求，文件：AgentOS-boot/src/test/java/com/agentos/boot/smoke/ReActWeatherSmokeTest.java、specs/004-agentos-tool-system/quickstart.md
- [ ] T047 完成本 Feature 的三类工具 Demo/验收证据：合法 `http_get`、白名单拒绝且零网络请求、超时/正文超限/审计未确认；逐项核对 FR-001–FR-025、FR-003a、FR-011a、SC-001–SC-009、审计状态和敏感信息扫描，文件：specs/004-agentos-tool-system/quickstart.md、specs/004-agentos-tool-system/tasks.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖；T001–T003 可并行。
- **Foundational (Phase 2)**: 依赖 Setup；T005–T008 可并行，T004 为共享端口基线。
- **User Stories (Phase 3–6)**: 均依赖 Foundational 完成。
- **Polish (Phase 7)**: 依赖目标用户故事完成；T043、T044、T046 可并行，T045/T047 最后执行。

### User Story Dependencies

- **US1 (P1)**: 依赖 Phase 2；可独立交付 MVP，不依赖其他用户故事。
- **US2 (P1)**: 依赖 Phase 2；治理测试可用替身 callback 独立执行，Boot 集成依赖 US1 的注册装配。
- **US3 (P1)**: HTTP 沙箱和 HTTP 工具单测可在 US2 之前并行；治理接入与 Boot 闭环依赖 US2。
- **US4 (P2)**: 依赖 Phase 2 和 US2 的治理/审计错误语义；限次、ReAct 和流式测试可先行编写。

### Within Each User Story

- 测试任务必须先编写并确认红灯，再实现对应代码。
- 数据模型/状态契约先于服务和装配。
- 每个故事的独立测试通过后，才能进入下一个故事的集成任务。
- 任何数据库测试必须以 PostgreSQL Testcontainers 实际执行；Docker 不可用只能报告 NOT_RUN。

### Parallel Opportunities

- Setup：T001–T003 可并行。
- Foundational：T005–T008 可并行，T004 完成后作为端口基线。
- US1：T009–T011 可并行；T012–T013 可并行，T014–T015 依赖目录实现。
- US2：T017–T020 可并行；T021–T023 可并行，T024–T025 依赖治理语义。
- US3：T027–T029 可并行；T030 与 T031 可并行，T032 依赖 T030/T031，T033 依赖治理装配。
- US4：T035–T038 可并行；T039–T041 依赖对应红灯测试。
- Polish：T043、T044、T046 可并行；T045 依赖代码与测试完成。

---

## Parallel Example: User Story 1

```text
并行编写红灯测试：
- T009 ToolRegistry 契约测试：AgentOS-tool/src/test/java/com/agentos/tool/registry/ToolRegistryTest.java
- T010 UnknownToolResolver/自动配置测试：AgentOS-tool/src/test/java/com/agentos/tool/ToolAutoConfigurationTest.java
- T011 Boot 授权集成测试：AgentOS-boot/src/test/java/com/agentos/boot/ToolAuthorizationIntegrationTest.java

测试确认红灯后：
- T012 实现 ToolRegistry
- T013 实现 UnknownToolResolver
```

## Parallel Example: User Story 2

```text
并行编写红灯测试：
- T017 GovernedToolCallbackTest
- T018 AuditFailureTest 与 JpaToolInvocationRecorderTest
- T019 ToolExecutor/ReActLoopStreamingTest
- T020 Boot PostgreSQL 闭环测试

测试确认红灯后：
- T021 实现治理回调
- T022 实现 PostgreSQL 审计 recorder
- T023 实现异常处理器
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 Setup。
2. 完成 Phase 2 Foundational。
3. 完成 Phase 3 US1。
4. 停止并运行 US1 独立测试：合法 Agent 精确看到工具、未知配置零模型外呼、运行时未授权工具零底层执行。
5. 只有 US1 通过后才演示或进入 US2/US3 集成。

### Incremental Delivery

1. Phase 1–2：建立端口、审计和预算基础。
2. US1：交付最小授权目录。
3. US2：交付先记后做和可恢复审计。
4. US3：交付受白名单保护的 HTTP GET。
5. US4：交付同步/流式有界 ReAct 闭环。
6. Polish：运行全量质量门并整理 Demo/验收证据。

### Parallel Team Strategy

1. 所有人共同完成 Phase 1–2。
2. Foundational 完成后：
   - 开发者 A：US1 工具目录和授权。
   - 开发者 B：US2 治理和审计。
   - 开发者 C：US3 HTTP 沙箱与替身测试。
3. US2 稳定后由 A/C 合并 US4 的 ReAct 限次和截止时间闭环。
4. 最终由一名维护者执行 Phase 7 质量门和证据整理。

## Notes

- 每条任务都使用 `- [ ] Txxx`，并在用户故事任务上标注 `[USx]`。
- `[P]` 仅用于可在不同文件上并行且不依赖未完成任务的工作。
- 任务路径均为仓库内精确文件路径；新增测试文件必须在实现任务前创建。
- 本任务清单不创建 worktree、不修改 constitution、不自动提交/推送/合并/发布。
