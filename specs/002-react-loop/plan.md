# Implementation Plan: US-2 ReAct 循环（Agent 大脑）

**Branch**: `feat/us-2-react`（spec 目录 `002-react-loop`） | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-react-loop/spec.md`

**Updated**: 2026-11-16，增量规划 FR-039/FR-040“初始化默认主 Agent”。保留 2026-11-15 的设计、研究与人工 review 历史；该历史 review 不等于批准本次新增设计。增量状态：本次 `/speckit-implement Phase 10` 已确认范围并实施，文件/CLI/真实启动器及 PostgreSQL 验收通过；证据见 quickstart 第 9.5 节，真实模型未运行。`tasks.md` 的 T001–T035 完成记录及后续未完成任务原样保留，不重新编号或反向取消完成状态。

## Summary

统一入口 `AgentService.process` 接收一条消息：找到或创建活跃会话、取得会话租约、开始轮次，然后由 `ReActLoop` 迭代。`PromptBuilder` 组装提示词，`ProviderService`（US-1）调用一次模型；响应没有工具调用就结束，有就交给 `ToolExecutor`，它调用容器注入的 `ToolCallingManager` 执行，结果追加进历史再进入下一次迭代。契约放 `AgentOS-core`；工具与沙箱在 `AgentOS-tool`；会话、轮次、消息和两张审计表的 JPA 实现与迁移脚本在 `AgentOS-storage`；`AgentOS init` 与 `AgentOS chat` 在 `AgentOS-cli` 与 `AgentOS-channel-cli`；`AgentOS-boot` 只负责装配和启动上下文。Provider调用/重试逻辑不改；新增只读ProviderCatalog实现，复用RequestValidator在轮次开始前验证Profile。

本次增量使 `init` 从“只建骨架”变为“同时生成 `agents/main/AGENT.md`”：仅在缺少该文件时从参数、进程环境或执行目录 `.env` 解析具体模型名，生成通用中文助手且 `tools: []`。`chat` 按显式 Agent → `main` → 旧工作区规则选择，并将同一规范化工作区传到 Spring 实际加载链路。初始化仍不启动 Spring、不探测数据库或模型、不落盘凭据；没有新表、迁移或模块，不引入新的第三方库或版本。设计细节见文末“默认主 Agent 增量设计”，新增研究为 R20–R24。

Phase 0 对 Spring AI 2.0.2 的工具执行做了实测（见 [research.md](research.md)），决定设计走向的结论：

- `ToolCallingManager` 在调用线程上顺序执行工具，没有超时；遇到不认识的工具名会让整批调用抛异常，已执行的结果也丢。
- 默认解析器会执行"声明成 Bean 但不在 Agent 名单里"的工具。必须换成只返回错误的解析器并打开解析回退，才能守住名单（FR-030）。
- 回调里拿不到模型给的调用 ID，审计靠响应之后建立的按工具名排队账本；ID非空/唯一在整批执行前校验。
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

**Constraints**: 整轮超时之后最多超出 1 秒；工具调用先记后做；审计最多3次、200/500ms只是退避时间；每次数据库调用有界且受本轮剩余预算限制，收尾共用最后1秒；数据库事务不跨模型调用与工具调用；日志不含密钥、完整提示词和工具参数

**Scale/Scope**: 单进程、单用户的 CLI；`core` 约 30 个新类型，`tool` 约 8 个，`storage` 约 14 个，`cli` 与 `channel-cli` 约 8 个；约 25 个测试类

**本次增量技术边界**：沿用上述版本。新增 CLI 的 `InitModelResolver` 与 `DefaultAgentOptions` 两个小类型；扩展 `WorkspaceInitializer`、`InitCommand`、`ChatCommand`，调整 CLI 的 `ContextLauncher` 和 boot 的实现，必要时在 `AgentLoader` 补齐未解析模型占位符的本地校验。模型配置为 UTF-8 本地文件输入，运行状态仍在 PostgreSQL。测试沿用 JUnit 5、`@TempDir`、Mockito、现有本地模型替身和 Testcontainers；不以当前机器环境变量或真实 `.env` 作为自动化夹具。

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

设计按本次修订与宪章对齐；技术方案9.2字段与租约/回收事实源同步更新。2026-11-15，用户逐项确认分析修订后，提交78d2bd5，并明确要求实施Phase 1，作为本版计划的人工review确认；T001实施核对仍须确认没有新的设计冲突。设计之后的复查见文末。

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
│               TurnResult、TurnStatus、ProcessRequest、TurnSink、ToolCallLedger、TurnReaper、TurnRunner、TurnExecutionBudget、TurnBudgetRegistry
├── session/    SessionManager、TurnStore、MessageStore（端口）、SessionKey
├── audit/      ToolInvocationRecorder（端口）、RetryingAuditWriter、AuditWriteException
├── tool/       Sandbox、SandboxAction、ActionType、SandboxViolationException、ToolPolicy、Redactor、ToolCatalog
├── provider/   ProviderCatalog（新增只读验证端口，Profile不改）
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

**Structure Decision**: 沿用现有 9 个模块，不新增模块。Provider只新增ProviderCatalog的现有校验适配和Bean，不改call/stream/retry实现。`AgentOS-memory`、`AgentOS-web` 不动。

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
| FR-039/FR-040 增量：默认主 Agent | `InitModelResolver`、`DefaultAgentOptions`、`WorkspaceInitializer`；`ContextLauncher.launch(Path)` → boot 高优先级 `agentos.workspace.root`；见下文及 research R20–R24 |
| SC-001 阶段 Demo | [quickstart.md](quickstart.md) 第 3 节；自动化的替身版见第 4 节 |
| SC-002 ~ SC-017 | 逐条对应 [quickstart.md](quickstart.md) 第 1 节的测试表；SC-017 由 `ChatCommandTest` 的 `--new` 用例验证 |
| SC-018 ~ SC-022 | [quickstart.md](quickstart.md) 第 9 节：文件初始化、解析/选择矩阵、自定义工作区、敏感信息与真实 CLI 装配验证；`--new` 仍由原 US4 任务负责 |

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
- **租约覆盖与回收**：动态租约至少到本轮deadline+30s（150s只作最短TTL），不用心跳，代价是强杀等待更久。没有有效owner租约的RUNNING轮次在启动或接管时立即回收，不额外要求deadline过期；终态轮次孤儿工具记录独立清理。
- **DNS 重绑定**：沙箱只校验主机名，不校验解析后的地址。只信任白名单内的公共接口，完整防护留到后续阶段。
- **共享库首次启动会自动建表**：可接受，但宪章禁止 Flyway `clean`；共享库需要 SSL。
- **已更正与延期事项**：9d3cf6e已经修复技术方案111/126行与DemandAnalysis的model必填说明；仅技术方案130行辅助ChatClient的模型来源仍留到实际使用时设计，不作为本story已实现能力。
- **天气数据源依赖外网**：met.no 不通时换 7timer；自动化测试用本地替身，不受影响。

## 分析修订后的闭合设计（2026-11-15）

- 只对审计瞬时写故障重试；llm_calls新增write_id UUID唯一键，单次record重试复用；工具记录复用原唯一键并校验payload。消息追加、lease与建轮次不统一重试（R14）。
- ToolCatalog和ProviderCatalog为core端口；ToolRegistry和现有Provider校验分别实现。账本在模型响应后才建，给Manager的Prompt含累计历史与内部上下文。
- TurnRunner/TurnExecutionBudget/TurnBudgetRegistry使用同一单调deadline，调用方有界等到deadline+1s；正常审计受执行预算限制，收尾最后1秒共用，数据库连接/锁/语句/socket也有界。预算查找显式按turnId，移除后迟到审计拒绝。
- 数据库持续不可用是不能即时保证持久化的物理边界：停止外呼、返回安全失败/turnId/UNCONFIRMED，不称终态已确认，也不承诺恢复模型审计缺行。恢复后经租约失效收尾数据库状态。CONFIRMED只在终态和释放事务确认时返回。
- 异常工具ID整批执行前失败；致命中止未开始的后续工具不记作limit_skipped、不纳入实际执行分母；本次未完整返回的助手/工具消息不保存，保留工具审计与此前迭代消息。
- 流式正常路径五类事件与唯一终结回调有单独测试；history上限0与正token预算90%规则可测。真实模型/真实天气调用次数不硬编码，脚本替身才断言精确次数。

## 七项复查修订（2026-11-15）

- deadline附近结束的在途调用，其最终模型审计/工具终态可以使用共享最后1秒；清理阶段不再退避重放写入，仅有界尝试并查证，所有收尾仍共用hardStop。
- 业务status与persistenceStatus独立：TIMED_OUT或FAILED也可CONFIRMED，只有必要审计/终态/清理未确认才UNCONFIRMED。旧owner发现数据库已有收敛终态时返回数据库终态，不误报更新0行为数据库故障。
- startTurn提交未知的已提交有效/已提交失效/确认未提交/仍未知四分支按agent-service契约处理，不能把暂时查无行当未提交，不重放起步事务。
- 数据库clock_timestamp()+本地剩余时长计算数据库deadline；本地单调预算不重新起算，连接/SQL/网络往返都扣预算。两端时间条件同时满足才可追加消息。
- T037/T039/T070先写行为红灯，分别早于T048/T047/T073；T060/T049/T077仅作后续集成复验。会话接管统一由TurnStore.startTurn事务负责。

## 本轮复查修订：实际时间与输出端（2026-11-15）

- 租约/deadline/接管/消息有效性采用取锁后的db_now=clock_timestamp()，避免PostgreSQL事务起点now()在锁等待后过时；默认创建时间仍可用now()。T022先测锁等待跨有效期，T025/T082实现。
- AgentService提供process(request,sink)与委托NOOP的单参数入口，输入不带回调；两种调用共用循环和持久化链路。
- 终结通知与结果分离：complete/error只尝试一次且有界等到相同hardStop；失败不改业务终态或确认状态，也不补第二终结事件。无预算、sink已失效或过程回调仍阻塞时跳过通知并写安全日志。T037先红灯，T028/T048实现，T054/T060验证硬截止边界。

## 默认主 Agent 增量设计（2026-11-16）

### 当前事实与本轮范围

本节方案已按 Phase 10 实施。研究时的基线是：初始化器只建共享模板，CLI 没有 main 优先级，启动器不接收工作区。现在 init 创建普通 main，chat 优先选择 main，并通过 `ContextLauncher.launch(Path workspaceRoot)` 将实际根目录传给共享的 `WorkspaceProperties.root`。新增 `DefaultAgentCliIntegrationTest` 经真实 CLI init/chat、真实启动器、本地模型替身和 PostgreSQL 验证闭环；原 `ReActApplicationTest` 保留为先前纵向链路证据。研究基线与决策见 [research.md](research.md) R20–R24，运行证据见 [quickstart.md](quickstart.md) 第 9.5 节。

旧文中的 Docker 状态、spike 风险和类型数量属于原版规划快照；已完成内容以既有 tasks 和 quickstart 的实测记录为准。本次不改写那些历史结论；本次运行验证另记于 quickstart 第 9.5 节，不把它当成原 Phase 4–9 的验收。

### Phase 0：研究结论

- 无 Spring 初始化没有可复用的通用 `ConfigLoader`，只新增一个限定于模型名输入的解析器，不提前实现全局配置系统（R20/R21）。
- 直接复用 `AgentLoader` 做已有 Agent 的本地校验；它不连接数据库、不调用 Provider，且不会读取 Bootstrap。已补上拒绝未解析占位符的测试与校验，不改变 US-1 `Profile` 或 Provider 调用接口（R20/R22）。
- `ContextLauncher` 接收显式工作区，boot 以命令行属性传入 Spring；不使用低优先级默认属性，也不依赖全局系统属性（R23）。
- 文件创建采用 create-new 语义，生成内容按 YAML 字符串规则转义；退出码由命令入口明确返回，不靠 Picocli 的默认异常码碰巧匹配（R22/R24）。

### Phase 1：初始化与文件设计

1. `InitCommand` 将 `--workspace`（默认 `.AgentOS`）相对执行目录转成规范化绝对路径。先检查已有路径类型和已有 `main/AGENT.md`：合法就保留；损坏则返回 2，错误只指明文件/字段。对其他自定义 Agent 不做无关校验，避免阻断初始化。
2. 只有目标 `main/AGENT.md` 缺失时才调用 `InitModelResolver`。输入显式参数、可注入的环境变量读取函数和执行目录；按 `--model` > 进程 `AGENTOS_LLM_MODEL` > 执行目录 `.env` 的同名项惰性解析。所选值为空白或含未解析占位符直接返回 2；所有来源缺失同样返回 2，且此前不创建任何工作区目录或文件。
3. `.env` 使用 JDK `Properties` 的语法解析、UTF-8 Reader，仅保留/返回 `AGENTOS_LLM_MODEL` 的白名单投影；解析器不接收 Provider 凭据、数据库配置或 Spring Environment。不执行 shell，不展开变量，不将整个文件或异常原文输出。`--provider` 省略时不写 `provider.name`；提供时只作为非敏感连接名保存。详细输入规则见 [configuration.md](contracts/configuration.md)。
4. `DefaultAgentOptions` 只带 model 和可选 providerName。初始化器用结构化 YAML 序列化构造 frontmatter，固定 `name: main`、`tools: []` 和通用中文助手正文，不写温度、连接地址或密钥。沿用已有 SnakeYAML，不新增 dotenv 库；CLI 若直接引用 SnakeYAML 类型，应在自己的 POM 显式声明现有受管依赖，不依赖偶然的传递性。序列化必须验证特殊字符的 round-trip，不能用未转义字符串直接拼 YAML。
5. 预检查成功后补齐目录、现有共享模板和缺失的 `main`。目录必须是目录，文件必须是普通文件；继承加载器对 Agent 末级软链接的限制，并检查受管理路径以避免经链接写入其他目录。文件用 `CREATE_NEW` 写入，遇到竞争创建不覆盖，重新校验赢家文件/目录。整个工作区不做伪事务回滚；I/O 失败返回 1，保留已创建内容，重试只补缺项。部分写坏的已有 `main` 不自动修复，按字段/文件错误提示人工处理。
6. 成功输出新建/跳过清单和“仅本地文件已准备”的说明；错误输出安全失败原因及补齐方式，不打印配置原值、生成正文或堆栈。合法 `main` 已存在时不重新解析模型来源，传入另一个模型名也仅跳过，不是更新配置命令。

`WorkspaceInitializer` 的路径检查、文件写入与结果统计保持在 CLI；core 不反向依赖 CLI。初始化参数不是持久化 Profile，生成文件仍由现有加载器派生 Profile，见 [data-model.md](data-model.md) 的增量类型。

### Phase 1：选择与真实工作区传递

- `ChatCommand` 先确定候选：显式 `--profile`；否则存在 `main` 路径就选它并校验；否则扫描直接子目录，唯一则选中、多项列名单、零项提示 init。不能先丢弃损坏 `main` 再静默选择其他 Agent。候选扫描关闭 `Files.list` 的 Stream，目录判断与 `AgentLoader` 的链接限制一致。
- 选中的 Agent 用 `AgentLoader(workspace/agents).load(name)` 本地校验；失败返回 2、不启动 launcher、不发模型请求。只校验选中的 Agent。Provider 是否已配置/可用仍由现有运行时校验负责，不以联网试探代替校验。
- 将 CLI 端口唯一抽象方法改为 `ContextLauncher.launch(Path workspaceRoot)`，同步迁移仓库内所有调用者、假 launcher 和测试；不保留会忽略参数的无参回退。工作区只在入口规范化一次，两次加载均使用该目录。
- `SpringContextLauncher` 在 `WebApplicationType.NONE` 的 `.run(...)` 中传入单个 `--agentos.workspace.root=<绝对路径>` 配置参数。这样显式/缺省 CLI 工作区都不会被较低优先级环境配置改写；不调用 `System.setProperty`，不把路径值解释为 shell 命令。现有 core 装配由同一 root 构建 Agent/Bootstrap 加载器，无需改造循环。
- boot 为测试提供包内可注入的启动器构建工厂：测试可设置仅含本地测试配置的高优先级属性源，并关闭 `.env` 导入；工厂不得改变生产 `NONE`、工作区参数和关闭行为。该入口不作为用户 CLI 配置功能。集成测试需使用真实 `SpringContextLauncher`，不能替换成仅捕获路径的假 launcher。
- 本次不实现 `--new`；保留原参数、FR-040 已有归档约束和对应 US4 未完成任务，不把默认 main 验收当作会话归档验收。

### 验证分层与任务衔接

| 验证层 | 已扩展/新增测试 | 验证的行为 |
|--------|-----------------|----------------|
| CLI 纯文件/参数 | 现有 `WorkspaceInitializerTest`、`AgentOSCliTest`，新增 `InitModelResolverTest` | 首次 main、来源优先级/惰性读取、缺失零写入、已有 main 幂等、特殊 YAML 值、部分失败及安全退出码；不碰真实 `.env` |
| Agent 本地校验 | 现有 `AgentLoaderTest` | 缺少 model、未解析占位符、目录/文件冲突和末级链接被拒绝 |
| CLI 选择/端口 | 现有 `ChatCommandTest` | main 优先、显式覆盖、旧工作区选择、无效 Agent 零 launcher 调用；传入规范化工作区，成功/失败均关闭已打开上下文 |
| boot 真实装配 | 新增 `DefaultAgentCliIntegrationTest`（`@Tag("db")`） | CLI init 创建 main 后通过 CLI chat + 真实 launcher + 本地模型替身 + PostgreSQL 完成一轮，核对同一 root 的 Agent/Bootstrap 和 1 session/1 turn/2 messages/1 llm_call |
| 全仓回归 | 原有质量门及数据库报告脚本 | Provider、工具唯一执行入口、core 依赖倒置、原任务回归；Docker 不可用须区分跳过与通过，CI 不得跳过 |

真实装配测试已扩展 boot 测试源码中的 `support/LocalModelServer`，捕获合成模型请求，断言模型名、无工具和 Bootstrap 标记。请求捕获只存在于测试夹具，不新增生产 prompt 日志。

测试先行顺序：解析/文件与选择行为红灯 → CLI/core 最小实现 → 工作区端口及 boot 装配红灯 → 真实链路实现 → 全仓 verify 和数据库报告。规划轮只完成设计；后续 `/speckit-tasks` 已将增量追加为 T094–T109 及其依赖，不重开 T029/T031/T033/T034/T035，不挤占原 Phase 4–9 的任务编号。该顺序已执行，行为红灯、修复及质量门结果见 quickstart 第 9.5 节。

### 增量 Constitution Check（设计前/设计后）

| 闸门 | 设计前核对 | Phase 1 设计后结论 |
|------|------------|-------------------|
| I / II 技术栈与范围 | 沿用现有模块、依赖，不做配置向导或 Profile 管理 | 通过；新增代码仅位于 CLI、boot 和必要的 core 本地校验 |
| III / IV 工具与调用链 | 默认工具名单为空，循环和 Provider 调用逻辑不变 | 通过；没有新增模型/工具执行入口或 ThreadLocal |
| VI / 技术约束 存储与密钥 | init 仅文件；聊天沿用 PostgreSQL 与审计 | 通过；零表结构变化，不保存凭据，不以 H2/共享库替代测试 |
| VII / VIII Demo 与 Agent | main 是普通 Agent、共享 AGENTS 仍是指令 | 通过；新增纯文本 CLI 验收，原天气 Demo 继续使用显式 weather Agent |
| 开发流程与质量门 | 按用户明确的 Phase 10 范围实施，不重写任务完成历史 | 通过；T094 确认范围，测试先行，`mvn -B -ntp verify` 320 项测试通过，数据库无跳过 |

新增研究没有待澄清项，未发现宪章冲突；无需增加 Complexity Tracking 例外。原设计轮未运行 Maven、数据库或真实模型；Phase 10 实施后 Maven 和容器数据库已实测通过，真实模型仍未运行。设计审查与运行证据分别记录。
