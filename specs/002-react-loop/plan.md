# Implementation Plan: US-2 ReAct 循环（Agent 大脑）

**Branch**: `feat/us-2-react`（spec 目录 `002-react-loop`） | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-react-loop/spec.md`

## Summary

统一入口 `AgentService.process` 接收一条消息：找到或创建活跃会话、取得会话租约、开始轮次，然后由 `ReActLoop` 迭代。`PromptBuilder` 组装提示词，`ProviderService`（US-1）调用一次模型；响应没有工具调用就结束，有就交给 `ToolExecutor`，它调用容器注入的 `ToolCallingManager` 执行，结果追加进历史再进入下一次迭代。契约放 `AgentOS-core`；工具与沙箱在 `AgentOS-tool`；会话、轮次、消息和两张审计表的 JPA 实现与迁移脚本在 `AgentOS-storage`；`AgentOS init` 与 `AgentOS chat` 在 `AgentOS-cli` 与 `AgentOS-channel-cli`；`AgentOS-boot` 只负责装配和启动上下文。US-1 的 `AgentOS-provider` 不需要改动。

Phase 0 对 Spring AI 2.0.2 的工具执行做了实测（见 [research.md](research.md)），决定设计走向的结论：

- `ToolCallingManager` 在调用线程上顺序执行工具，没有超时；遇到不认识的工具名会让整批调用抛异常，已执行的结果也丢。
- 默认解析器会执行"声明成 Bean 但不在 Agent 名单里"的工具。必须换成只返回错误的解析器并打开解析回退，才能守住名单（FR-030）。
- 回调里拿不到模型给的调用 ID，审计要靠一个按工具名排队的账本来对应。
- 自定义回调直接抛异常时，密钥原文会连同异常消息一起冒出来，所以所有工具异常都要在治理层先包装、脱敏。
- 本机 Docker Desktop 已安装但没有运行，依赖数据库的测试要有不依赖 Docker 的分层。

## Technical Context

**Language/Version**: Java 21（virtual thread）

**Primary Dependencies**: Spring Boot 4.1.2；Spring AI 2.0.2（`ToolCallingManager`、`ToolCallback`、`MessageAggregator`、`JTokkitTokenCountEstimator`）；Spring Data JPA（Hibernate 7.4）；Flyway；PostgreSQL JDBC；SnakeYAML；Picocli；JDK `HttpClient`；SLF4J 与 MDC

**Storage**: PostgreSQL 16+，五张表由 Flyway 迁移创建：`sessions`、`agent_turns`、`session_messages`、`llm_calls`、`tool_invocations`；不依赖 pgvector

**Testing**: JUnit 5、AssertJ；循环与提示词用端口的内存实现测试；SQL 用 Testcontainers（`pgvector/pgvector:pg17`）；模型用 US-1 的 `StubModelServer`；ArchUnit；真实模型 `@Tag("llm")`

**Target Platform**: JVM 21，私有部署的 Linux / macOS；命令行进程

**Project Type**: Maven 多模块；本 story 新增 CLI 入口，没有对外网络接口

**Performance Goals**: 循环自身开销相对模型耗时可忽略；spec 未设吞吐目标

**Constraints**: 整轮超时之后最多超出 1 秒；工具调用先记后做；审计写入重试总时长约 0.7 秒；数据库事务不跨模型调用与工具调用；日志不含密钥、完整提示词和工具参数

**Scale/Scope**: 单进程、单用户的 CLI；`core` 约 30 个新类型，`tool` 约 8 个，`storage` 约 14 个，`cli` 与 `channel-cli` 约 8 个；约 25 个测试类

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本 story 的检查点 | 结论 |
|------|------------------|------|
| I 技术栈单体 | JDK 21、Boot 4.1.2、Spring AI 2.0.2，依赖只来自 Maven Central；新增只有 Testcontainers 2.0.5 的两个测试模块，其余是已有的 JPA、Flyway、Picocli、SnakeYAML；无厂商 SDK；契约在 core、实现在 tool / storage / cli | 通过 |
| II 五大能力优先 | 只做能力二及其必需的最小支撑；不做 SSE 端点、MCP、记忆、Skill、并行工具、上下文压缩；`TurnSink`、`Sandbox` 只留接口和最小实现 | 通过 |
| III 循环自实现 | `ReActLoop` 自己驱动迭代，通过 `ProviderService`（它直接调 `ChatModel.call/stream`）调用模型，主链路不走 `ChatClient`；两道上限、三层超时按 spec 落实（第四层 HTTP 同步等待属于 US-5）；运行上下文经 `ToolContext` 传 `TurnContext`；流式用 `MessageAggregator`（US-1 已实现） | 通过 |
| IV 工具唯一执行入口 | 只有 `ToolExecutor` 调 `executeToolCalls`，用容器注入的管理器，不自建、不用 `ToolCallingAdvisor`；每个工具套 `GovernedToolCallback`；`ToolRegistry` 校验重名；失败经自定义处理器反馈给模型，`TurnDeadlineExceededException` 重抛。额外：自定义解析器加解析回退，守住 Agent 的工具名单（R2） | 通过 |
| V Plugin Tool 三档 | 不涉及 MCP；`http_get` 是内置工具 | 不适用 |
| VI 存储与审计 | PostgreSQL，表结构只由 Flyway 管理，`ddl-auto=validate`；两张审计表落库，失败也写，写入前脱敏，带 `session_id`、`turn_id`、`iteration`；迁移不依赖 pgvector；不使用 `ChatMemory` Advisor | 通过 |
| VII 每个 story 有 Demo | 阶段 Demo 1：`AgentOS chat` 查北京天气并给穿衣建议（[quickstart](quickstart.md) 第 3 节） | 通过 |
| VIII Agent 目录不是 Tool | `AGENT.md` 正文只进系统指令，不注册进 `ToolRegistry`；Skill 与记忆的位置预留 | 通过 |
| 技术约束 | 没有新密钥；`Sandbox` 接口先行，只有 `WhitelistSandbox`；CLI 与后续入口共用 `AgentService.process`；`open-in-view=false`；每个仓库操作是独立的短事务，不跨模型调用和工具调用；不用线程局部变量传上下文，MDC 只写不读 | 通过 |
| 测试环境 | 自动化测试默认 Testcontainers，不连共享库；没有 Docker 时 `db` 测试跳过，CI 不允许跳过（R9） | 通过（带说明，见 Complexity Tracking） |
| 质量门 | `mvn -B verify` 全绿，不新增排除项 | 通过 |

无违反项。设计之后的复查见文末。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── spec.md
├── plan.md              # 本文件
├── research.md          # Phase 0：实测结论与决策
├── data-model.md        # Phase 1：表、状态机、核心类型、端口
├── quickstart.md        # Phase 1：如何验证
├── contracts/
│   ├── agent-service.md      # 处理入口、轮次、循环、提示词
│   ├── tool-governance.md    # http_get、沙箱、治理装饰器、脱敏、审计
│   ├── workspace-and-cli.md  # init、AGENT.md、chat
│   └── configuration.md      # 新增配置与启动校验
├── checklists/requirements.md
└── tasks.md             # Phase 2：/speckit-tasks 生成
```

### Source Code (repository root)

```text
AgentOS-core/src/main/java/com/agentos/core/
├── agent/      AgentProfile、AgentSettings、LoadedAgent、AgentLoader（SnakeYAML）、ContextLoader
├── turn/       AgentService、ReActLoop、PromptBuilder、ToolExecutor、TurnGuard、TurnContext、
│               TurnResult、TurnStatus、ProcessRequest、TurnSink、ToolCallLedger、TurnReaper 逻辑
├── session/    SessionManager、TurnStore、MessageStore（端口）、SessionKey
├── audit/      ToolInvocationRecorder（端口）、RetryingAuditWriter、AuditWriteException
├── tool/       Sandbox、SandboxAction、ActionType、SandboxViolationException、ToolPolicy、Redactor
└── turn/       TurnDeadlineExceededException、LeaseLostException

AgentOS-tool/src/main/java/com/agentos/tool/
├── http/       HttpTools（http_get）、HttpToolProperties
├── sandbox/    WhitelistSandbox
├── govern/     GovernedToolCallback、AgentOsToolExecutionExceptionProcessor、UnknownToolResolver
├── registry/   ToolRegistry（名单解析、validateToolCallbacks、未注册工具名报错）
└── ToolAutoConfiguration.java

AgentOS-storage/src/main/java/com/agentos/storage/
├── session/    五个 JPA 实体、仓库、JpaSessionManager、JpaTurnStore、JpaMessageStore
├── audit/      JpaLlmCallRecorder、JpaToolInvocationRecorder
├── TurnReaperRunner.java（启动时执行一次）、StorageAutoConfiguration.java
└── resources/db/migration/V1__core_tables.sql

AgentOS-channel-cli/src/main/java/com/agentos/channel/cli/   CliChannel（读标准输入、写标准输出）、CliIdentity
AgentOS-cli/src/main/java/com/agentos/cli/                   InitCommand、ChatCommand、WorkspaceInitializer、ContextLauncher
AgentOS-boot/src/main/java/com/agentos/boot/                 AgentOSApplication（传入 ContextLauncher）、SpringContextLauncher
```

测试：`core` 下 `ReActLoopTest`、`PromptBuilderTest`、`ToolExecutorTest`、`AgentServiceTest`、`ContextLoaderTest`、`RedactorTest`、`RetryingAuditWriterTest`，以及端口的内存实现和契约测试基类；`tool` 下 `HttpToolsTest`、`WhitelistSandboxTest`、`GovernedToolCallbackTest`、`ToolRegistryTest`；`storage` 下契约测试的 JPA 子类、`MigrationTest`、`TurnReaperTest`（`@Tag("db")`）；`cli` 下 `InitCommandTest`、`ChatCommandTest`；`boot` 下 `ArchitectureRulesTest` 补充规则与装配测试。

**Structure Decision**: 沿用现有 9 个模块，不新增模块。`AgentOS-provider` 不需要改动：它已经支持截止时间、可替换的记录输出、调用关联信息。`AgentOS-memory`、`AgentOS-web` 不动。

## 需求到设计的对应

| 需求 | 设计落点 |
|------|---------|
| FR-001 ~ FR-006 入口、会话、轮次、租约 | `AgentService`、`SessionManager`、`TurnStore`（[agent-service.md](contracts/agent-service.md)，SQL 见 research R8）；`TurnReaper` 逻辑做启动收尾 |
| FR-007 ~ FR-013 循环 | `ReActLoop`、`ToolExecutor`（唯一执行入口，FR-008）；顺序执行与按调用 ID 回填由管理器保证（FR-010，research R1）；`TurnContext` 经 `ToolContext` 传递（FR-011）；MDC 只写不读（FR-012）；`TurnSink`（FR-013） |
| FR-014 ~ FR-019 上限与超时 | `spring.ai.tools.limits.*`（FR-014、FR-016，R4）；`ReActLoop` 的迭代计数（FR-015）；三层超时：Provider 的 `timeout`、`GovernedToolCallback`、`TurnGuard`（FR-017 ~ FR-019，R6） |
| FR-020 ~ FR-024 提示词与历史 | `PromptBuilder`、`ContextLoader`（[agent-service.md](contracts/agent-service.md)）；token 用 `JTokkitTokenCountEstimator`（R11） |
| FR-025 ~ FR-027 Profile 与 Agent 目录 | `AgentLoader`、`AgentProfile`（[workspace-and-cli.md](contracts/workspace-and-cli.md)，R13） |
| FR-028 ~ FR-030 工具与治理 | `HttpTools`、`WhitelistSandbox`、`GovernedToolCallback`、`UnknownToolResolver`、处理器（[tool-governance.md](contracts/tool-governance.md)，R2、R3） |
| FR-031 ~ FR-038 持久化与审计 | 五张表与端口（[data-model.md](data-model.md)）；`RetryingAuditWriter`（R14）；`Redactor`；迁移只建这五张表、不依赖向量扩展（FR-033，`V1__core_tables.sql`）；每个仓库操作是独立的短事务（FR-038） |
| FR-039 ~ FR-042 命令行 | `InitCommand`、`ChatCommand`、`CliChannel`（[workspace-and-cli.md](contracts/workspace-and-cli.md)，R12） |
| SC-001 阶段 Demo | [quickstart.md](quickstart.md) 第 3 节；自动化的替身版见第 4 节 |
| SC-002 ~ SC-017 | 逐条对应 [quickstart.md](quickstart.md) 第 1 节的测试表；SC-017 由 `ChatCommandTest` 的 `--new` 用例验证 |

## Complexity Tracking

| 偏离 | 为什么需要 | 更简单的做法为什么不行 |
|------|-----------|----------------------|
| 按工具名排队的调用 ID 账本 | 审计要关联模型给的调用 ID（FR-035），回调拿不到它（R5） | 按参数匹配：参数相同的调用会混在一起 |
| 自定义 `ToolCallbackResolver` 加打开解析回退 | 守住 Agent 的工具名单；名单外的名字不能让整批抛异常（FR-030、R2） | 默认解析器：声明成 Bean 的工具会被执行，未知名字整批抛异常 |
| 回调放到虚拟线程上执行 | 管理器同步执行、没有超时，整轮时限只能在回调里兑现（R6） | 调用线程上直接执行：阻塞的读不响应超时 |
| 租约与会话的几处用原生 SQL，其余用 JPA | 条件更新要原子，先读后写有竞态（R8） | 全用 JPA 实体：读改写会让两个并发请求都拿到租约 |
| 没有 Docker 时 `db` 测试跳过，CI 不允许跳过 | 宪章要求 Testcontainers，禁止 H2 和共享库（R9） | 嵌入式数据库：违反宪章；共享库：违反宪章 |

## Constitution Check（Phase 1 设计后复查）

设计没有引入新模块，也没有新的工具执行路径。两处在实施时要守住：

- `UnknownToolResolver` 永远不能返回真实工具，否则名单外的 Bean 工具又会被执行；`ToolExecutorTest` 用"声明成 Bean 但不在名单里"的工具固定它。
- 落库的 `LlmCallRecorder` 必须压过 provider 的日志记录器（R16），由装配测试固定。

## 风险与后续

- **`jsonb` 映射没有实测**：本机没有数据库。第一个存储任务是 Testcontainers 的 spike，不通过就把 `jsonb` 列改用 `JdbcClient`（R7）。
- **账本依赖管理器的顺序语义**：用测试固定"同名工具第 k 次执行对应第 k 个 ID"；升级 Spring AI 后若失败，说明前提变了（R5）。
- **处理器重抛路径没有实测**：整轮超时、租约丢失、审计写入失败三类异常能否穿过管理器冒到循环，要用测试验证（R3）。
- **跨迭代的次数上限**：交给管理器的 prompt 要带累计历史，否则上限每次迭代都被重置（R4）。
- **崩溃后的租约等待**：进程被强杀后，在租约到期（默认 150 秒）之前再发消息会提示"上一条消息还在处理"。关闭钩子尽力缓解，来不及时靠到期自愈。SC-010 说的"启动完成后标为已中断"针对的是已过截止时间且租约已过期的轮次。
- **DNS 重绑定**：沙箱只校验主机名，不校验解析后的地址。只信任白名单内的公共接口，完整防护留到后续阶段。
- **共享库首次启动会自动建表**：可接受，但宪章禁止 Flyway `clean`；共享库需要 SSL。
- **文档矛盾**：`TechnicalSolution.md` 第 111、126、130 行关于"自动配置的 `OpenAiChatModel` 用于辅助调用"仍与 `chat: none` 矛盾；本 story 不涉及辅助调用，留到用到它时再定。`DemandAnalysis.md` 5.2 的"model 可选"已过时（R13）。
- **天气数据源依赖外网**：met.no 不通时换 7timer；自动化测试用本地替身，不受影响。
