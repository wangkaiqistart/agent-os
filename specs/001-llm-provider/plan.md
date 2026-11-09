# Implementation Plan: US-1 对接 LLM（Provider 抽象）

**Branch**: `feat/us1-provider`（spec 目录 `001-llm-provider`） | **Date**: 2026-11-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-llm-provider/spec.md`

## Summary

`AgentOS-core` 定义 Provider 契约：`Profile`、`ProviderService`、调用记录 `LlmCallRecord` 与可替换的记录输出 `LlmCallRecorder`、向量化门面 `EmbeddingService`。`AgentOS-provider` 用 Spring AI 2.0.2 的 `spring-ai-openai` 实现：`openai` 与 `deepseek` 两种连接都编程式构建 `OpenAiChatModel`（关闭 SDK 重试、接入容器的 `ObservationRegistry`），`ProviderService` 按 `agentos.providers.<name>` 显式映射；外呼前校验、输出上限映射、重试与截止时间、调用记录都集中在 `ProviderService`。向量化由包装 `OpenAiEmbeddingModel` 的 `EmbeddingService` 提供，自己分批（每批 10 条），默认在应用就绪后后台校验维度，不阻塞启动（可选同步校验）。

Phase 0 用本地 HTTP 替身和真实服务做了实测（见 [research.md](research.md)），决定设计走向的结论：

- 2.0.2 的 `OpenAiChatModel` 不执行工具，只返回工具调用请求；它对 `ToolCallingManager` 只调用 `resolveToolDefinitions`。
- SDK 不自动补 `/v1`：base-url 必须写到 `/v1`（中转、百炼都要），否则请求打到 `/chat/completions` 而 404。
- 请求级 `OpenAiChatOptions.timeout` 能覆盖连接超时，单次请求超时 = min(连接超时, 本轮剩余时间) 可以直接下发。
- SDK 不给向量化分批：11 条只发 1 次请求，会被百炼拒绝。
- SDK 异常的 `getMessage()` 含原始响应体（可能带 key），调用记录的错误只能由状态码、错误类型、错误码拼出。

## Technical Context

**Language/Version**: Java 21（virtual thread）

**Primary Dependencies**: Spring Boot 4.1.2；Spring AI 2.0.2 `spring-ai-openai`（底层 OpenAI Java SDK + OkHttp）；Micrometer Observation；SLF4J 2 fluent API（结构化日志字段）

**Storage**: 无。调用记录写结构化日志；`llm_calls` 落库在 US-2

**Testing**: JUnit Jupiter 6、AssertJ、Spring `ApplicationContextRunner`；本地 HTTP 替身用 JDK 内置 `com.sun.net.httpserver.HttpServer`（不新增依赖）；ArchUnit；真实模型冒烟 `@Tag("llm")` + `@EnabledIfEnvironmentVariable`

**Target Platform**: JVM 21，私有部署的 Linux / macOS

**Project Type**: Maven 多模块中的库模块，由 US-2 的 ReAct 循环调用，无对外接口

**Performance Goals**: Provider 自身开销相对模型耗时可忽略；spec 未设吞吐目标

**Constraints**: 单次请求超时 = min(连接超时, 本轮剩余时间)；每次调用最多追加 1 次重试，剩余时间不少于 `retry-min-remaining`（默认 15 秒）才重试；日志不含凭证、Prompt、工具参数、图片；启用向量化时应用就绪后在后台多一次向量化请求，启动耗时不受向量化服务影响

**Scale/Scope**: 2 种连接类型 + 1 个向量化连接；core 新增约 10 个类型，provider 约 12 个类，约 12 个测试类

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 story 的检查点 | 结论 |
|------|------------------|------|
| I 技术栈单体 | JDK 21、Boot 4.1.2、Spring AI 2.0.2，依赖只来自 Maven Central；只用 `spring-ai-openai`，删除 deepseek / anthropic / ollama 依赖；无 `com.alibaba.cloud.ai`、无厂商 SDK；契约在 core、实现在 provider | 通过 |
| II 五大能力优先 | 只做能力一 + 文本向量化；fallback、hedge racing、熔断、多模态向量、Prometheus 不做；为 US-2 预留"记录输出"替换点 | 通过 |
| III 循环自实现，组件用 Spring AI | Provider 直接调 `ChatModel.call/stream`，不用 `ChatClient`；流式用 `MessageAggregator`；单次 LLM 超时受连接超时和整轮剩余时间约束；模型内置重试关闭（`maxRetries(0)`），重试只在 `ProviderService`；按名称显式映射 | 通过 |
| IV 工具只有一个执行入口 | Provider 只把 `ToolCallback` 定义随请求发出，不执行工具（实测 2.0.2 的 `ChatModel` 本身也不执行）；`ToolContext` 不进请求 JSON（实测）；构建模型时传入容器注入的 `ToolCallingManager`，它只用于解析工具定义 | 通过 |
| V Plugin Tool 三档 | 不涉及 | 不适用 |
| VI 存储与审计 | `llm_calls` 本 story 只写日志、代码标 TODO，US-2 落库（用户已确认，宪章要求的是核心阶段内完成）；调用记录字段覆盖技术方案 9.2，失败调用也记录；写入前脱敏 | 通过（延后项已登记） |
| VII 每个 user story 有 Demo | 本 story 无用户入口，阶段 Demo 1 与 US-2 一起演示（AiProgrammingGuide 4.1） | 通过 |
| VIII Agent 目录不是 Tool | 不涉及；Profile 由调用方直接构造，从 AGENT.md 派生在 US-2 | 不适用 |
| 技术约束：密钥 | key 全部 `${ENV_VAR}` 占位；日志、异常信息、调用记录不含 key（SDK 异常 message 不进记录） | 通过 |
| 技术约束：不用 ThreadLocal | 截止时间、关联标识经 `ProviderRequest` 显式传入；取消用线程中断 | 通过 |
| 技术约束：测试 | 自动化测试只用本地替身，不连共享库、不调真实模型；冒烟测试 `@Tag("llm")` 按环境变量开启 | 通过 |
| 质量门 | `mvn -B verify` 全绿；新增 ArchUnit 规则：只有 `com.agentos.provider` 可依赖 `org.springframework.ai.openai`；任何 `@Bean` 方法不得返回 `ChatModel` | 通过 |

无违反项，Complexity Tracking 为空。

## Project Structure

### Documentation (this feature)

```text
specs/001-llm-provider/
├── spec.md
├── plan.md              # 本文件
├── research.md          # Phase 0：实测结论与决策
├── data-model.md        # Phase 1：契约类型与字段
├── quickstart.md        # Phase 1：如何验证
├── contracts/
│   ├── provider-service.md   # ProviderService / EmbeddingService 的 Java 契约与错误语义
│   └── configuration.md      # agentos.providers / agentos.embedding 配置契约
├── checklists/requirements.md
└── tasks.md             # Phase 2：/speckit-tasks 生成
```

### Source Code (repository root)

```text
AgentOS-core/src/main/java/com/agentos/core/
├── error/                         # 已有：ErrorCode、AgentOSException
└── provider/                      # 新增：契约，不依赖任何模型实现
    ├── Profile.java               # 聊天模型与生成参数；model 必填
    ├── ProviderService.java       # call / stream
    ├── ProviderRequest.java       # Profile + 消息 + 工具 + ToolContext + 截止时间 + 关联标识
    ├── CallCorrelation.java       # sessionId / turnId / iteration，可为空
    ├── ProviderResult.java        # ChatResponse + 本次全部调用记录
    ├── ProviderCallException.java # PROVIDER_UNAVAILABLE，附带全部调用记录
    ├── LlmCallRecord.java         # 一次实际请求的审计信息（对应 US-2 的 llm_calls）
    ├── LlmCallRecorder.java       # 可替换的记录输出
    ├── CallMode.java / FailureKind.java
    └── embedding/
        ├── EmbeddingService.java  # 向量化门面：embed / status / dimensions
        └── EmbeddingStatus.java   # DISABLED / READY / UNAVAILABLE + 原因

AgentOS-provider/src/main/java/com/agentos/provider/
├── config/
│   ├── ProviderProperties.java    # @ConfigurationProperties("agentos")：default-provider、providers
│   ├── EmbeddingProperties.java   # @ConfigurationProperties("agentos.embedding")
│   └── ProviderAutoConfiguration.java  # 组装 Bean；不注册任何 ChatModel / EmbeddingModel Bean
├── chat/
│   ├── ConnectionType.java        # openai / deepseek：输出上限字段映射
│   ├── ProviderConnection.java    # 名称、类型、是否可用、OpenAiChatModel
│   ├── ChatModelFactory.java      # 按连接编程式构建 OpenAiChatModel
│   ├── DefaultProviderService.java # 外呼前校验、请求组装、重试、截止时间、记录
│   ├── RequestValidator.java      # provider / model / 媒体类型校验
│   ├── FailureClassifier.java     # SDK 异常 → FailureKind，生成脱敏错误摘要
│   └── LoggingLlmCallRecorder.java # US-1 的记录输出；TODO(US-2) 换成 llm_calls
└── embedding/
    ├── DefaultEmbeddingService.java # 分批、重试、日志、每次调用的维度检查、状态机
    └── EmbeddingProbe.java          # 维度校验与失败分类；async 时在应用就绪后运行，UNAVAILABLE 时退避重新校验

AgentOS-provider/src/test/java/com/agentos/provider/
├── support/StubModelServer.java   # JDK HttpServer 替身：按脚本返回、记录请求
├── chat/  ProviderRoutingTest、RequestMappingTest、RetryPolicyTest、DeadlineTest、
│          CallRecordTest、RedactionTest、StreamingTest、ImageInputTest、StartupValidationTest
├── embedding/  EmbeddingServiceTest、EmbeddingStartupTest
└── smoke/  PrimarySmokeTest、DeepSeekSmokeTest、EmbeddingSmokeTest   # @Tag("llm")

AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java   # 追加 2 条规则
```

**Structure Decision**: 沿用现有 Maven 多模块，只动 `AgentOS-core`、`AgentOS-provider`、`AgentOS-boot` 三个模块，不新增模块。契约放 core（依赖倒置，宪章原则一），US-2 的 `ReActLoop` 只依赖 core 的 `ProviderService`。

### 非代码改动

| 文件 | 改动 | 依据 |
|------|------|------|
| `AgentOS-provider/pom.xml` | 删除 `spring-ai-deepseek`、`spring-ai-anthropic`、`spring-ai-ollama` | spec FR-005 |
| 根 `pom.xml` | surefire 默认排除 `llm` 组；新增 `-Pllm` profile 只跑冒烟 | CLAUDE.md「环境与密钥」 |
| `application.yaml` | 默认 key 改为 `${AGENTOS_LLM_API_KEY:}`（交给启动校验给出明确错误）；向量化默认地址补 `/v1`；`spring.ai.retry` 注释更新（DeepSeek 模块已删） | FR-003、research R2 |
| `.env.example`、CLAUDE.md 变量表 | base-url 改为"写到 `/v1`" | research R2 |
| `scripts/check_env.py` | 不再自动拼 `/v1` | research R2 |
| 本机 `.env` | 用户自己把 `AGENTOS_LLM_BASE_URL`、`AGENTOS_EMBEDDING_BASE_URL` 补上 `/v1`（不提交，实施时提醒） | research R2 |
| `docs/TechnicalSolution.md` 3.2 / 3.5 / 8.8 / 9.2 | 模型由 Profile 提供；base-url 带 `/v1`；`DEEPSEEK_API_KEY` 改名；`llm_calls` 增加尝试序号、上限是否生效、图片数量 | spec Assumptions |

## 需求到设计的对应

| 需求 | 设计落点 |
|------|---------|
| FR-001 ~ FR-005 连接配置 | `ProviderProperties`、`ProviderAutoConfiguration` 启动校验（[configuration.md](contracts/configuration.md)）；密钥只走 `${ENV_VAR}`（FR-002）；`ConnectionType`（FR-005） |
| FR-006 ~ FR-012 Profile 与请求组装 | `Profile`、`ProviderRequest`（[data-model.md](data-model.md)）；`RequestValidator`（FR-007）；请求选项只写已设字段、输出上限按类型映射（research R3、R4）；单次超时 = min(连接超时, 剩余时间)（FR-012，research R5） |
| FR-013 ~ FR-016 调用与结果 | `ProviderService.call/stream` 返回 `ProviderResult`（FR-013、FR-014）；不执行工具（FR-015，research R1）；无候选结果按 PROTOCOL 失败（FR-016） |
| FR-017 ~ FR-021 重试与失败 | `FailureClassifier` + `DefaultProviderService`（research R6）：可重试集合（FR-017）、Retry-After 与 `retry-min-remaining` 门槛（FR-018）、交出增量后不重试（FR-019）、不可重试类型（FR-020）、`ProviderCallException` / `INVALID_ARGUMENT`（FR-021） |
| FR-022 ~ FR-026 调用记录 | `LlmCallRecord`、`LlmCallRecorder`、`LoggingLlmCallRecorder`（research R7、R8、R15）；观测接入 `ObservationRegistry`（FR-026，research R13） |
| FR-027、FR-028 验证 | `StubModelServer` 离线测试（FR-027，research R14）；`smoke/` 下 `@Tag("llm")` 冒烟测试（FR-028） |
| FR-029 ~ FR-035 向量化 | `EmbeddingProperties`（含 `startup-check`）、`EmbeddingService`、`DefaultEmbeddingService` 状态机、`EmbeddingProbe`（research R11、R12） |
| FR-036 ~ FR-038 图片输入 | `RequestValidator` 放行 `image/*`、拒绝其他媒体（FR-036、FR-037，research R10）；服务拒绝图片按 INVALID_REQUEST 不重试（FR-038） |
| SC-001 ~ SC-019 | 逐条对应 [quickstart.md](quickstart.md) 的测试表；SC-010 由 `PrimarySmokeTest`、`DeepSeekSmokeTest` 验证 |

## Complexity Tracking

无。Constitution Check 没有违反项。

## Constitution Check（Phase 1 设计后复查）

设计没有引入新的依赖、模块或执行路径，复查结论不变。两处需要在实施时守住：

- `ProviderAutoConfiguration` 不得把 `OpenAiChatModel` 或 `OpenAiEmbeddingModel` 注册成 Bean：否则会出现"扫描容器里的 ChatModel"的可能，也会让向量化实例数在禁用时不为 0。由 ArchUnit 规则和 `StartupValidationTest` 共同保证。
- `FailureClassifier` 只能读取 SDK 异常的状态码、`type()`、`code()`、`headers()`，不得调用 `getMessage()` 写记录；由 `RedactionTest` 保证。

## 风险与后续

- **中转接口会附加隐藏的系统提示**：实测一次很短的请求 prompt_tokens 为 4397，其中缓存命中 4224；之前一次音频测试的回答像是编程助手的口吻。US-2 的 Agent 指令可能被它干扰，需要在阶段 Demo 1 观察。
- **中转接口不遵守输出上限**：按 spec 打告警、在记录上标出；花费靠 US-2 的迭代上限和整轮超时控制。
- **`llm_calls` 字段**：技术方案 9.2 还没有尝试序号、上限是否生效、图片数量三列，US-2 建表时补齐（以 [data-model.md](data-model.md) 为准）。
- **向量化后台校验需要对外可见**：`async` 模式下维度错误不再阻止启动，只能通过 ERROR 日志和 `EmbeddingService.status()` 发现。US-5 的 `GET /api/v1/health` 应把 `MISCONFIGURED` 报为异常、`CHECKING` / `UNAVAILABLE` 报为降级，否则运维可能长时间察觉不到。
- **`retry-min-remaining` 的取值**：15 秒按测试环境中转接口的典型耗时（同步 7 ~ 8 秒、流式最长约 35 秒）选定。流式请求通常更慢，若阶段 Demo 中流式重试经常在截止时超时，可以单独为该连接调大。
