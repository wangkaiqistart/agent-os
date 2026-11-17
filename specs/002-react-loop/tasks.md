---
description: "US-2 ReAct 循环（002-react-loop）的实施任务"
---

# Tasks: US-2 ReAct 循环（Agent 大脑）

**Input**: 本目录的 `spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`。

**Prerequisites**: spec 已澄清；plan 已生成。实施前须按宪章完成 plan 人工 review；本清单不代表已经完成实现或验收。

**Tests**: spec 的独立验收、SC-014 和 quickstart 明确要求自动化测试。按 TDD：先写测试，确认因目标行为未实现而失败，再实现；不能用编译失败代替行为红灯。契约类型可以先定义，但不能先补业务实现让测试直接变绿。

**Organization**: `[US1]` ~ `[US6]` **仅指本 spec 内部的六个用户故事**，整份任务属于项目五部分路线图的 **US-2（ReAct）**，不是再开发路线图 US-1 ~ US-6。

**2026-11-16 增量**：默认主 Agent 的任务追加为 T094–T109（文末 Phase 10，归属 US1/P1）。原 T001–T035 的完成状态及任务正文保留，原 T036–T093 不重编号；仅将未完成 T092 的最终验收计数从 17 条 SC 同步为 22 条。编号表示追加顺序，实际执行在原 US1 完成后、原 Phase 4 之前，见依赖图；生成清单本身不代表实施或 review；本次 Phase 10 的实际确认与实施证据见 quickstart 第 9.5 节。

## Format 与路径

所有任务使用 `- [ ] Tnnn [P?] [USn?] 描述 + 文件路径`。`[P]` 表示所属小节的共同前置条件已完成后，可以与同小节的其他独立任务并行；不是可以越过测试或依赖先做。

为避免长路径淹没任务，下面的前缀是**精确路径别名**，任务中 `CORE/turn/X.java` 即 `AgentOS-core/src/main/java/com/agentos/core/turn/X.java`：

| 前缀 | 仓库根目录相对路径 |
|------|--------------------|
| `CORE/` / `CORE-TEST/` | `AgentOS-core/src/main/java/com/agentos/core/` / `AgentOS-core/src/test/java/com/agentos/core/` |
| `TOOL/` / `TOOL-TEST/` | `AgentOS-tool/src/main/java/com/agentos/tool/` / `AgentOS-tool/src/test/java/com/agentos/tool/` |
| `STORE/` / `STORE-TEST/` | `AgentOS-storage/src/main/java/com/agentos/storage/` / `AgentOS-storage/src/test/java/com/agentos/storage/` |
| `CLI/` / `CLI-TEST/` | `AgentOS-cli/src/main/java/com/agentos/cli/` / `AgentOS-cli/src/test/java/com/agentos/cli/` |
| `CHANNEL/` / `CHANNEL-TEST/` | `AgentOS-channel-cli/src/main/java/com/agentos/channel/cli/` / `AgentOS-channel-cli/src/test/java/com/agentos/channel/cli/` |
| `BOOT/` / `BOOT-TEST/` | `AgentOS-boot/src/main/java/com/agentos/boot/` / `AgentOS-boot/src/test/java/com/agentos/boot/` |

## 全局约束与实施闸门

- 只做本 spec；不实现长期记忆、Skill 正文读取、MCP、其余内置工具、幂等工具重试、REST、SSE、定时、工具并行。Provider调用/重试逻辑不改，只读ProviderCatalog复用现有校验；使用可替换的 `LlmCallRecorder` 接入落库。
- 中文注释、构造器注入、SLF4J；MDC 只写不读。运行上下文走 `ToolContext`，不使用 ThreadLocal；只允许 `ToolExecutor` 调用**注入的** `ToolCallingManager.executeToolCalls(...)`。
- `core` 不依赖 `tool`、`storage`、`provider`、CLI 或 boot；需要工具名单时经 core 端口交互。测试 fixtures 放测试源码或测试 JAR，不进入正式产物。
- 模型返回工具调用不代表可以执行；没写 `tools` 默认无权限，未知/名单外工具只返回错误，不能经容器全局解析器获得真实工具。
- 审计先脱敏再写，工具先记后做；不吞记录器异常，不因审计写入失败重发模型请求或重复执行工具。事务不能包住模型、工具、退避等待、控制台读取。
- 每个用户故事结束执行 `mvn -B verify`。本机数据库测试若跳过须明确报告，不能称数据库层已经验证；CI 必须实际跑数据库测试。不得放宽质量门或新增排除项让构建通过。
- 数据库不可用时不能保证终态立即落库：必须停止后续模型/工具调用，尽力写终态和按 owner 释放租约，持续故障留给租约到期与启动收尾；不能声称“中止就保证审计永不缺行”。返回结果必须带turnId和UNCONFIRMED，不计为SC-007的审计完整成功；恢复行为在T070、T075核对。
- 并发、数据库响应丢失、整轮截止时间与审计重试组合是重点风险。遇到 design 未能支持 spec 的情况，先报告并修订获批设计，不修改宪章或静默降低验收标准。

---

## Phase 1: Setup（依赖、验证环境与设计一致性）

**Goal**: 确认实施边界和测试依赖，不启动真实模型或修改共享数据库。

- [X] T001 人工review并核对本次闭合设计：动态租约至少deadline+30s；TurnExecutionBudget/TurnRunner/TurnBudgetRegistry共享执行及最后1秒清理预算；只重试审计，llm write_id稳定去重、工具原唯一键幂等；持久故障返回persistenceStatus=UNCONFIRMED不冒报落库；无有效owner租约即回收、不等待deadline，终态孤儿工具记录独立清理；空/重复tool_call_id整批执行前FAILED；ToolCatalog/ProviderCatalog在core，账本响应后创建。设计已同步，不再把具体决定留到编码时猜；若review不同意，先修设计再实现，不改宪章 in specs/002-react-loop/plan.md、specs/002-react-loop/research.md、specs/002-react-loop/data-model.md、specs/002-react-loop/contracts/agent-service.md、specs/002-react-loop/contracts/configuration.md、docs/TechnicalSolution.md
- [X] T002 配置 Testcontainers 2.0.5 的 `testcontainers-postgresql`、`testcontainers-junit-jupiter` 测试依赖和 core 的测试 JAR 发布，供 storage 重用端口契约测试；storage 增加 core 的 `test-jar` 测试依赖，tool 需要的官方工具自动配置仅作测试依赖，Testcontainers版本沿用Boot BOM管理的2.0.5、不重复覆盖，core/tool各按需要增加官方工具自动配置的test scope依赖；不引入 H2/SQLite 或新增业务模块 in pom.xml、AgentOS-core/pom.xml、AgentOS-storage/pom.xml、AgentOS-tool/pom.xml
- [X] T003 [P] 编写数据库测试环境检测：Docker 可用性可注入，覆盖本地无 Docker 明确跳过、本地有 Docker 执行、`CI=true` 无 Docker 必须失败；检测在取容器连接前完成，不使用会在 CI 同样跳过的通用条件（research R9） in STORE-TEST/support/DatabaseTestEnvironmentTest.java
- [X] T004 实现数据库测试基类：镜像固定 `pgvector/pgvector:pg17` 并声明 `asCompatibleSubstituteFor("postgres")`，仅测试容器连接，通过 `@DynamicPropertySource` 配置；不读 `.env`，不连共享库，不以 `clean` 清理共享库；标签 `db` 不被默认 Surefire 排除，CI 能断言执行数非零（依赖 T002、T003） in STORE-TEST/support/DatabaseTestEnvironment.java、STORE-TEST/support/PostgresIntegrationTest.java
- [X] T005 [P] 更新 CI 让原有 `mvn -B verify` 同时执行数据库测试并检查数据库测试未全部跳过；保持格式、Checkstyle、SpotBugs、enforcer、ArchUnit 原规则，不另建放宽版命令（SC-014） in .github/workflows/ci.yml
- [X] T006 为后续自动配置登记资源路径与模块依赖边界，确认 `spring-ai-model` 已提供 token 估算、SnakeYAML 已在 core、boot 已依赖各实现模块；只加确实缺少的编译依赖，不改变 Provider 自动配置 in AgentOS-core/pom.xml、AgentOS-tool/pom.xml、AgentOS-storage/pom.xml、AgentOS-cli/pom.xml、AgentOS-channel-cli/pom.xml

**Checkpoint**: T001 的契约冲突有明确结论；T004 在有 Docker 时启动独立 PostgreSQL；无 Docker 时明确说明未验证 SQL，不能把 spike 标完成。

---

## Phase 2: Foundational（共享契约、测试夹具、迁移 spike）

**Goal**: 定义所有故事共享的类型和端口，不先把整条业务链实现完。

- [X] T007 [P] 新建 `AgentProfile`、`AgentSettings`、`LoadedAgent`：包装而不修改 US-1 的 `Profile`；字段覆盖 data-model；默认值逐字落实 `maxIterations（10）`、`maxHistoryTurns（20）`、`maxHistoryTokens（32000）`、`turnTimeout（300s）`；工具名单不可变且缺省为空，先补值对象测试并确认行为失败，再按契约校验设置值的合法边界（maxIterations≥1、turnTimeout>0、history上限均≥0，任一0不带历史，正token预算按90% floor）（FR-025） in CORE/agent/AgentProfile.java、CORE/agent/AgentSettings.java、CORE/agent/LoadedAgent.java、CORE-TEST/agent/AgentSettingsTest.java
- [X] T008 定义 `SessionKey`、会话/历史轮次快照、`ProcessRequest`、`TurnResult`、`TurnStatus`、`TurnContext`：结果字段 `turnId,status,text,iterations,persistenceStatus`（CONFIRMED/UNCONFIRMED）；上下文“不可变，forIteration(i) 返回副本”；终态保留 `SUCCEEDED/FAILED/TIMED_OUT/MAX_ITERATIONS/INTERRUPTED/LIMIT_EXCEEDED`，存储还需 `RUNNING`；不要把 Provider Profile 改成运行态（FR-001、FR-003、FR-011） in CORE/turn/TurnExecutionBudget.java、CORE/turn/TurnBudgetRegistry.java、CORE/session/SessionKey.java、CORE/session/SessionSnapshot.java、CORE/session/HistoryTurn.java、CORE/turn/ProcessRequest.java、CORE/turn/TurnResult.java、CORE/turn/TurnStatus.java、CORE/turn/TurnContext.java
- [X] T009 定义持久化端口 `SessionManager`、`TurnStore`、`MessageStore`，行为按 contracts/agent-service：会话只读查找/空闲归档、startTurn内创建、原子startTurn（含回收、取租约、建轮次、用户消息）与finishAndRelease（含终态/工具收尾/owner释放）、owner条件续租、仅追加消息、整轮读取历史与启动收尾；返回快照不返回 JPA 实体，区分忙/丢失租约/数据库异常（FR-002 ~ FR-006、FR-032、FR-038） in CORE/session/SessionManager.java、CORE/session/TurnStore.java、CORE/session/MessageStore.java
- [X] T010 定义工具相关core契约与只读ProviderCatalog.validate(Profile)（provider复用RequestValidator实现并装配，不改call/stream）：`ToolCatalog` 只暴露 Agent 工具名单校验和允许的 callbacks，不依赖 tool 实现；`ToolPolicy(idempotent,timeout,maxResultChars)`；`ToolInvocationRecorder.begin/complete/skipped`；`Sandbox.enforce(SandboxAction)` 和四值 `ActionType(FILE_READ,FILE_WRITE,SHELL,HTTP)`；定义整轮超时、租约丢失、审计写入失败、沙箱拒绝异常（FR-008、FR-026、FR-028、FR-035） in CORE/provider/ProviderCatalog.java、AgentOS-provider/src/main/java/com/agentos/provider/chat/DefaultProviderCatalog.java、AgentOS-provider/src/main/java/com/agentos/provider/config/ProviderAutoConfiguration.java、AgentOS-provider/src/test/java/com/agentos/provider/chat/ProviderCatalogTest.java、CORE/tool/ToolCatalog.java、CORE/tool/ToolPolicy.java、CORE/tool/Sandbox.java、CORE/tool/SandboxAction.java、CORE/tool/ActionType.java、CORE/tool/SandboxViolationException.java、CORE/audit/ToolInvocationRecorder.java、CORE/audit/ToolInvocationRecord.java、CORE/audit/AuditWriteException.java、CORE/turn/TurnDeadlineExceededException.java、CORE/turn/LeaseLostException.java
- [X] T011 定义 `TurnSink`：`streaming()` 默认 false，五个事件 `onDelta/onToolCall/onToolResult/onComplete/onError` 与 `NOOP`；明确process(request)委托process(request,NOOP)，双参数入口要求非空sink且共用执行链路；过程错误取消，终结通知失败仅记安全日志、不改已确认结果，回调等待受同一hardStop；添加记录事件的测试实现，不增加正式 SSE 端点（FR-013） in CORE/turn/TurnSink.java、CORE-TEST/support/RecordingTurnSink.java
- [X] T012 在 core 测试源码实现 `InMemorySessionManager`、`InMemoryTurnStore`、`InMemoryMessageStore`、`CollectingToolInvocationRecorder`、脚本模型端口替身和 Agent 临时文件夹夹具，线程安全、可控时钟/闸门、可注入写入故障，不复制生产代码（依赖 T007 ~ T011） in CORE-TEST/support/InMemorySessionManager.java、CORE-TEST/support/InMemoryTurnStore.java、CORE-TEST/support/InMemoryMessageStore.java、CORE-TEST/support/CollectingToolInvocationRecorder.java、CORE-TEST/support/ScriptedProviderService.java、CORE-TEST/support/AgentFixtures.java
- [X] T013 [P] 编写共享端口的抽象契约测试及内存子类，初始只覆盖创建、终态、顺序追加、一次只追加新增消息；并发、归档和异常场景在对应故事追加；测试类由 T002 的测试 JAR 供 storage 使用（FR-032） in CORE-TEST/contract/SessionManagerContractTest.java、CORE-TEST/contract/TurnStoreContractTest.java、CORE-TEST/contract/MessageStoreContractTest.java、CORE-TEST/support/InMemoryContractsTest.java
- [X] T014 [P] 编写迁移与 JSON 映射 spike：空库迁移、JPA validate、jsonb 工具请求/结果/metadata 写回一致，显式 null 与 0 区分；五张表、关键 CHECK/索引均可查询，无 `CREATE EXTENSION vector`。先确认红灯，不能以 Docker 被跳过宣称 spike 完成（FR-033、research R7） in STORE-TEST/session/JsonbMappingTest.java、STORE-TEST/MigrationTest.java
- [X] T015 实现 `V1__core_tables.sql`：逐列照 data-model，`sessions` 的“uuid PK，默认 gen_random_uuid()”“active / archived（CHECK）”和唯一索引 `(channel,user_id,profile_name) WHERE status='active'`；`agent_turns` 的 session FK、iterations“NOT NULL，默认 0”、deadline“NOT NULL”、全部规定状态 CHECK；`session_messages` 的两个 FK、iteration“用户消息为 0”、type“USER / ASSISTANT / TOOL（CHECK）”、三个 jsonb 和 `(session_id,id)` 索引；系统指令不入库（依赖 T014，FR-003、FR-032、FR-033） in AgentOS-storage/src/main/resources/db/migration/V1__core_tables.sql
- [X] T016 补齐同一迁移的审计表并实现五个实体及 JSON 编解码：`llm_calls`业务列按US-1 LlmCallRecord映射，新增write_id UUID NOT NULL UNIQUE，单次record入口生成一次，所有重试复用；关联字段“NOT NULL”，mode“CALL / STREAM（CHECK）”，attempt“1 或 2（CHECK）”，用量“服务端没返回为 NULL，不是 0”；`tool_invocations` 的关联、ID、name、source“NOT NULL”，状态全套 CHECK，`result_truncated`“NOT NULL，默认 false”，`attempts`“NOT NULL，默认 0”，created/updated“NOT NULL”，唯一约束 `(turn_id,iteration,tool_call_id)`；非法 JSON 参数“存成一个 JSON 字符串值，不丢弃”。通过实体实现使 T014 的 spike 从红灯转绿，JSON 映射失败则仅 JSON 列走已规划的 JdbcClient 兜底，不能全仓放弃 JPA（FR-034、FR-035） in AgentOS-storage/src/main/resources/db/migration/V1__core_tables.sql、STORE/session/SessionEntity.java、STORE/session/AgentTurnEntity.java、STORE/session/SessionMessageEntity.java、STORE/session/LlmCallEntity.java、STORE/session/ToolInvocationEntity.java、STORE/session/MessageCodec.java
- [X] T017 先写配置绑定/非法值行为测试再实现属性：workspace .AgentOS、lease-ttl150s仅为最短值、lease-grace30s动态覆盖deadline、cleanup固定1s、audit最多重试2次/200与500ms/attempt-timeout≤250ms；工具30s/10000、HTTP白名单空/UA/0~3跳/1048576字节。验证非法范围拒绝启动、环境变量AGENTOS_TOOLS_HTTP_ALLOWEDDOMAINS能绑定白名单；删除按单次Provider timeout+30s的WARN策略。回退开关只在T047与安全resolver一起启用 in CORE/config/WorkspaceProperties.java、CORE/config/TurnProperties.java、CORE/config/AuditProperties.java、TOOL/http/HttpToolProperties.java、CORE-TEST/config/CorePropertiesTest.java、TOOL-TEST/http/HttpToolPropertiesTest.java、AgentOS-boot/src/main/resources/application.yaml

**Checkpoint**: 契约完整、内存测试夹具可用；数据库 spike 与迁移校验通过（或明确阻塞于 Docker，不能进入落库实现的完成验收）。每张表类型、可空性和 CHECK 以 data-model 与技术方案为依据，不擅自新增业务表。

---

## Phase 3: [US1] 与 Agent 对话并得到回答（P1，最小纵向链路）

**Goal**: 一个 Agent，一条纯文本消息，一次模型调用，最终回答和记录完整保存。

**Independent Test**: 内存端口 + 脚本 Provider 验证 1 轮、1 次调用、2 条消息；容器数据库 + 本地 HTTP 模型替身验证真实落库。`init` 不需要数据库。

### Tests（先写并确认行为失败）

- [X] T018 [P] [US1] 编写 Agent 文件测试：name 必填且等于目录名、`provider.model` 必填、未写生成参数保持空、没写 tools 为空并打 INFO；默认 settings；Agent名不能含路径分隔符/点点或解析后逃出agents根目录，--profile ../x应失败；不存在/损坏 frontmatter/非目录 Agent 错误指出文件与字段，解析使用安全 YAML，不允许构造任意类型；三个 Bootstrap 顺序和缺失 WARN（FR-024 ~ FR-027） in CORE-TEST/agent/AgentLoaderTest.java、CORE-TEST/agent/ContextLoaderTest.java
- [X] T019 [P] [US1] 编写第一次 Prompt 测试：正文→Bootstrap→数据安全说明→当地日期→当前 UserMessage；工具 schema 不重复塞系统指令，长期记忆和 Skill 不加载，系统指令不写消息库（FR-020、FR-027） in CORE-TEST/turn/PromptBuilderTest.java
- [X] T020 [P] [US1] 编写最小循环与入口测试：纯文本响应仅 1 次模型调用、iterations=1、2 条消息；空白消息无写入，空助手文本 FAILED；未知 provider/工具名单先校验再调用；不同trigger走同一入口；ProviderCatalog在建轮次前验证，不调用模型试探；先写上述startTurn四分支、正常TIMED_OUT+CONFIRMED和未确认结果的红灯测试，补单/双参数入口委托和非空sink测试，再验证CallCorrelation与ToolContext，不泄露到 prompt（FR-001、FR-003、FR-007、FR-011、FR-026、FR-042；SC-002） in CORE-TEST/turn/ReActLoopTest.java、CORE-TEST/turn/AgentServiceTest.java
- [X] T021 [P] [US1] 编写 init 与 CLI 基线测试：临时目录创建规定文件、重复执行不覆盖、绝不调用 ContextLauncher；chat 的 --profile/--message、单/多/无 Agent 选择、EOF、/exit、空行、用户名会话键、错误输出与退出码；用假 launcher，不启动 Spring（FR-039 ~ FR-042；SC-013） in CLI-TEST/InitCommandTest.java、CLI-TEST/ChatCommandTest.java、CHANNEL-TEST/CliChannelTest.java、CHANNEL-TEST/CliIdentityTest.java
- [X] T022 [P] [US1] 编写 JPA 端口契约子类与最小模型审计测试：会话/轮次/消息落库、按 id 读回、旧记录不重写；LlmCallRecord 全部字段（含 null、0、cache、attempt、image、cap、failure/status）映射；关联字段为空报错。先写应用/数据库墙钟偏差及连接/锁/网络往返耗预算的测试；持锁连接跨过lease/deadline后释放，另事务取锁后clock_timestamp判断须拒绝过期续租/追加，证明不使用事务起点now()；先于T025验证deadline换算，暂不执行真实模型（FR-032 ~ FR-034） in STORE-TEST/session/JpaSessionManagerContractTest.java、STORE-TEST/session/JpaTurnStoreContractTest.java、STORE-TEST/session/JpaMessageStoreContractTest.java、STORE-TEST/audit/JpaLlmCallRecorderTest.java

### Implementation

- [X] T023 [P] [US1] 实现 `AgentLoader`：安全解析 frontmatter 与正文，Agent名/路径先规范化并限制在agents根目录，处理软链接越界；保持 US-1 Profile 不变；强制 model、name=目录名、tools 默认空、settings 默认与合法性、配置错误路径指向字段；认识但未支持的键按 contract 打 WARN，不实现其能力（依赖 T018，FR-025、FR-026） in CORE/agent/AgentLoader.java
- [X] T024 [US1] 实现 `ContextLoader` 与初版 `PromptBuilder`：本轮重新读取正文与三个 Bootstrap、日期使用可注入 Clock、遵守 T019 顺序；本阶段先不读取跨轮历史，但保留 MessageStore 依赖和后续扩展点（依赖 T018、T019、T023；FR-020、FR-024、FR-027） in CORE/agent/ContextLoader.java、CORE/turn/PromptBuilder.java
- [X] T025 [US1] 实现会话/轮次/消息仓库及端口适配的基本路径：startTurn事务内会话创建用 `INSERT ... ON CONFLICT DO NOTHING`+查询，轮次与用户消息开始、终态写入、消息 JSON 编解码和按 id 读取；每次仓库操作短事务，查询在事务内转快照而不是暴露懒加载；读取已有会话不能提前更新last_active_at造成忙请求写入。租约必须用 owner 条件更新，初版也不能用“先查空闲再更新”；数据库deadline在取连接/行锁后先捕获db_now=clock_timestamp()（所需行锁已取得，本操作统一时间值）再加本地单调剩余时长计算并返回，普通创建时间可用now()，本地不重新计时；取租约/建轮次/初始用户消息同一短事务且owner有效到本轮deadline+30s，失败全部回滚；高级竞争测试在 US6 完善（依赖 T022，FR-002、FR-003、FR-032、FR-038） in STORE/session/SessionRepository.java、STORE/session/AgentTurnRepository.java、STORE/session/SessionMessageRepository.java、STORE/session/JpaSessionManager.java、STORE/session/JpaTurnStore.java、STORE/session/JpaMessageStore.java
- [X] T026 [P] [US1] 实现基础 `JpaLlmCallRecorder`，一次尝试一行，按data-model全字段映射并保留确认成功后的结构化llm_call日志，校验非空correlation，不写提示词、不把 SDK 异常作为 cause 保存，不在此重试模型；审计写入重试在 US5 完善，暂时写入失败直接传播；record入口生成一次write_id UUID并在单次SQL重试复用，ON CONFLICT(write_id)读取相同payload才确认成功，不自造时间戳键（依赖 T022，FR-034） in STORE/audit/LlmCallRepository.java、STORE/audit/JpaLlmCallRecorder.java
- [X] T027 [US1] 实现 `TurnGuard` 与初版 `ReActLoop`：使用TurnRunner与共享TurnExecutionBudget有界运行到deadline+1s，AgentService注册/移除TurnBudgetRegistry；每次模型请求传同一个整轮截止时间、iteration从1开始、允许列表通过 ToolCatalog；纯文本成功追加一次助手消息，空文本FAILED；complete/error终结事件由AgentService在收尾结果确定后唯一发送，不在循环中提前报成功；调用模型前 checkpoint，Provider 失败变安全终态，不反射取得模型实现；工具路径在 US2 接入，过渡期遇到工具请求明确失败而不是悄悄忽略（依赖 T020、T023 ~ T026；FR-007、FR-017） in CORE/turn/TurnRunner.java、CORE/turn/TurnExecutionBudget.java、CORE/turn/TurnBudgetRegistry.java、CORE/turn/TurnGuard.java、CORE/turn/ReActLoop.java
- [X] T028 [US1] 实现 `AgentService` 基础编排：校验→加载 Agent→开会话→原子取租约→建轮次与用户消息→循环→终态与owner释放；按业务终态与必要审计/收尾确认独立返回：正常超时且持久化确认是TIMED_OUT+CONFIRMED，业务失败且确认是FAILED+CONFIRMED；仅必要持久化未确认才UNCONFIRMED；process(request,sink)为唯一执行入口、单参委托NOOP；终结通知在受管任务中只等待hardStop剩余时间，失败/阻塞仅记安全日志，不改业务status/persistenceStatus、不补第二终结事件，无预算或过程回调未退出时不重入sink；try/finally从原子startTurn提交确认后开始；事务内任一步失败全部回滚，不留下已取得lease；startTurn提交未知按turnId查证：已提交且有效继续、已提交但失效收尾、确认原事务结束且未提交抛安全启动错误、仍未知返回FAILED+turnId+UNCONFIRMED；不把暂时查无行当未提交，不机械重试，不使用分离的begin/append跨事务拼起步；清理失败不能遮盖原始错误或继续外呼；MDC 会话/轮次/Agent/iteration 在 finally 清除；不加覆盖外呼的 @Transactional（依赖 T020、T027；FR-001、FR-003、FR-004、FR-006、FR-012、FR-038、FR-042） in CORE/turn/AgentService.java
- [X] T029 [P] [US1] 实现 `WorkspaceInitializer`、`InitCommand`：用不存在才创建的语义生成 agents/skills/output/memory/logs、MEMORY.md 与三个中文 Bootstrap 模板，报告新建/跳过；不覆盖文件、不启动容器、不连接数据库；模板放 CLI resources（依赖 T021，FR-039） in CLI/WorkspaceInitializer.java、CLI/InitCommand.java、AgentOS-cli/src/main/resources/workspace/AGENTS.md、AgentOS-cli/src/main/resources/workspace/SOUL.md、AgentOS-cli/src/main/resources/workspace/USER.md
- [X] T030 [P] [US1] 实现 `CliChannel`、`CliIdentity` 与 `ContextLauncher` 契约：I/O 构造器注入方便测试；渠道 cli、登录用户名、Agent 共同确定 SessionKey，读空行不创建轮次、/exit 或 EOF 结束，不把 CLI 输入处理写到 core（依赖 T021、T028；FR-040 ~ FR-042） in CHANNEL/CliChannel.java、CHANNEL/CliIdentity.java、CLI/ContextLauncher.java
- [X] T031 [US1] 实现 `ChatCommand` 并注册 init/chat：启动前选 Agent、--message 单条调用、交互循环、输出安全说明和 contract 退出码；保留 --new 参数待 US4 完成，不能假装已实现；原 --help/--version 行为不变，独立 CLI 无 launcher 时 chat 给明确错误（依赖 T029、T030） in CLI/ChatCommand.java、CLI/AgentOSCommand.java、CLI/AgentOSCli.java
- [X] T032 [US1] 实现 core/storage 自动配置：仓库、实体与端口显式装配；storage排在DataSource/JPA配置之后、ProviderAutoConfiguration之前，并以DataSource/EntityManager存在为条件，仅一个落库LlmCallRecorder；显式扫描com.agentos.storage实体和仓库，不能依赖com.agentos.boot默认包；core 服务通过 ToolCatalog 端口装配，US1 无工具时提供空目录；防止自动配置和用户组件扫描重复注册（依赖 T025 ~ T028、T031，research R16） in CORE/config/CoreAutoConfiguration.java、STORE/StorageAutoConfiguration.java、AgentOS-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports、AgentOS-storage/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- [X] T033 [US1] 实现 boot 的 `SpringContextLauncher` 并从 AgentOSApplication 传入 CLI：chat 以 NONE 启动且可关闭上下文；init/help/version 不启动 Spring；按 contract 控制控制台日志且不关闭文件日志；装配前处理缺失DB变量的安全错误；启动前控制台抑噪但错误由CLI输出，boot不访问System.out/err；核实NONE上下文不会装配依赖Servlet的WebConfig，不新增数据库自动配置排除项（依赖 T031、T032，FR-001、FR-039、FR-042） in BOOT/SpringContextLauncher.java、BOOT/AgentOSApplication.java
- [X] T034 [US1] 编写 boot 装配集成测试：本地 HTTP 模型替身+Testcontainers 空库验证一次纯文本 chat 的会话、轮次、2 条消息和模型记录；检测恰好一个落库 LlmCallRecorder、没有 ChatModel/EmbeddingModel Bean、init 不碰数据库；fixtures 在 boot 测试源码，不让 core 依赖 provider（FR-032 ~ FR-034；SC-002） in BOOT-TEST/ReActApplicationTest.java、BOOT-TEST/support/LocalModelServer.java
- [X] T035 [US1] 按 US1 独立验收跑测试与质量门，记录纯文本纵向链路、init 幂等、默认无工具和数据库测试实际执行/跳过情况；此时还不是可发布的完整 ReAct，不执行带工具的真实 Demo in specs/002-react-loop/quickstart.md

**Checkpoint**: 无工具的 CLI→AgentService→Provider→PostgreSQL 闭环可用；没有引入第二个模型或工具执行入口。

---

## Phase 4: [US2] 调工具完成任务（P1）

**Goal**: 一次工具请求只执行一次，结果完整回填，拒绝/失败也能被模型理解。

**Independent Test**: 本地模型先返回 http_get、后返回建议；HTTP 替身返回天气 JSON，断言两次模型调用、一次工具调用、四条消息及先记后做。

### Tests

- [X] T036 [P] [US2] 编写 `RedactorTest`：URL 的 key/token/access_token/sign、JSON 和头/键值的 authorization/api_key/apikey/token/secret/password、已知密钥原值、混合大小写、转义、异常消息；不能把审计写入错误详情重新泄露（FR-036、FR-037；SC-008） in CORE-TEST/tool/RedactorTest.java
- [X] T037 [P] [US2] 编写 `ToolExecutorTest`、账本测试：注入的真实官方 Manager 只执行一次、同名重复调用按 ID 排队、多个不同工具顺序、完整历史/returnDirect；先写正常流式delta→toolCall→toolResult→后续delta及预算充足时唯一complete、失败唯一error；无通知预算可跳过并记未送达；过程sink异常停止回调、终结onComplete/onError抛异常及阻塞不延长hardStop/不改已确认终态/不双发的行为红灯测试，避免T048先实现T060才首次测试；core测试用core契约的假resolver/collector，不引用tool实现；名单外真实Bean场景放T049的tool集成测试；不能通过比较模型文本猜 ID（FR-008 ~ FR-011、FR-021、FR-030；SC-003） in CORE-TEST/turn/ReActLoopStreamingTest.java、CORE-TEST/turn/ToolExecutorTest.java、CORE-TEST/turn/ToolCallLedgerTest.java
- [X] T038 [P] [US2] 编写 Sandbox/HTTP 测试：精确和通配域名语义、默认空名单全拒绝、非 HTTP Action 拒绝、只允许 http/https；重定向逐跳校验、3 跳边界、跳向白名单外/内网零请求、相对 Location、无 Location、非 2xx、安全错误、响应体大小上限；全部本地替身，不访问公网（FR-028；SC-016） in TOOL-TEST/sandbox/WhitelistSandboxTest.java、TOOL-TEST/http/HttpToolsTest.java、TOOL-TEST/support/LocalHttpServer.java
- [X] T039 [P] [US2] 编写治理与异常处理器测试，并在本任务前置ToolRegistryTest/ToolAutoConfigurationTest安全行为红灯（真实resolver、名单外Bean零调用、空名单、重名拒绝、唯一processor/resolver）；这些测试先于T047，不等T049首次编写：begin 必须先于真实回调、终态写入和 source、id/iteration 对应；业务异常/非法 JSON/沙箱拒绝回安全错误；独立线程执行、MDC 从 TurnContext 重建并清除；结果 10000 字符截断、原长和脱敏；整轮超时/租约丢失/审计异常穿过 Manager 必须重抛（FR-012、FR-029、FR-030、FR-035 ~ FR-037；research R3、R6） in TOOL-TEST/registry/ToolRegistryTest.java、TOOL-TEST/ToolAutoConfigurationTest.java、TOOL-TEST/govern/GovernedToolCallbackTest.java、TOOL-TEST/govern/AgentOsToolExecutionExceptionProcessorTest.java
- [X] T040 [P] [US2] 编写工具记录落库测试：先 in_progress、再原行更新终态，created/updated 时刻、attempts 0→1、重复 ID 不插入第二行、非法 JSON 存 JSON 字符串，所有 null/false 字段与记录状态按 data-model（FR-035；SC-007） in STORE-TEST/audit/JpaToolInvocationRecorderTest.java

### Implementation

- [X] T041 [US2] 实现 `Redactor`，仅接收安全密钥集合 Supplier，不依赖 ProviderProperties；本层不得打印原值、原 URL 或 Prompt。boot/tool 装配时提供 Provider key、数据库密码，未来 ConfigLoader 可替换，不新增配置加载体系（依赖 T036，FR-036、FR-037） in CORE/tool/Redactor.java
- [X] T042 [US2] 实现 `ToolCallLedger`、`ToolExecutor`：整批ID非空且本响应内唯一验证通过后才建账本，违规整批零执行FAILED；统一内部键常量，带TurnContext与账本重新构造给Manager的Prompt；Provider请求阶段无调用ID、不预建本轮账本，给Manager保留本轮累计历史与同一批治理callbacks；只对Manager正常返回取完整conversationHistory；致命中止不追加本次未完成的助手/工具消息，之前迭代和已执行工具审计保留；暴露本次新增消息；工具 ID、输出顺序、returnDirect 按官方结果；不自行循环执行 callback，超限补记录在 US3 添加（依赖 T037、T041，FR-008 ~ FR-011、FR-021） in CORE/turn/ToolCallLedger.java、CORE/turn/ToolExecutor.java
- [X] T043 [US2] 实现 `WhitelistSandbox`：只开放 HTTP Action，空列表全部拒绝，精确域名与 `*.example.com` 不匹配根域，IP 需精确授权，URI 不支持 scheme/user-info 等危险输入明确拒绝；错误只给已脱敏域名/类型，不返回敏感 URL（依赖 T038，FR-028） in TOOL/sandbox/WhitelistSandbox.java
- [X] T044 [US2] 实现 `HttpTools.http_get`：用 JDK HttpClient 禁自动重定向，连接/读取/整段响应消费受较小预算限制；每一跳先 Sandbox，最多 3 跳；读取最多 1048576 字节，超出按 contract 丢弃并安全关闭 body；User-Agent 配置化，非 2xx 不回显响应体；不实现 http_post 或工具重试（依赖 T038、T043，FR-018、FR-028） in TOOL/http/HttpTools.java
- [X] T045 [US2] 实现 Jpa 工具记录器：begin 插 in_progress、complete 仅更新本记录、skipped 直接终态；关联 ID“NOT NULL”，唯一 `(turn_id,iteration,tool_call_id)`，所有状态/nullable 字段依 T016；每次写独立短事务，参数先脱敏，外部执行不在事务内（依赖 T040、T041，FR-035 ~ FR-038） in STORE/session/ToolInvocationRepository.java（复用已有仓库）、STORE/audit/JpaToolInvocationRecorder.java
- [X] T046 [US2] 实现 `GovernedToolCallback` 与异常处理器：checkpoint→取 ID→脱敏参数写 begin→虚拟线程执行→截断/脱敏→更新终态，安全包装成 ToolExecutionException；默认工具超时30s/结果10000字符，幂等标记 true 但本 story 不重试工具；租约/整轮/审计异常直接结束轮次，必须实测重抛名单，不把 SDK/HTTP cause 泄露给用户（依赖 T039、T041、T042、T044、T045，FR-029、FR-030） in TOOL/govern/GovernedToolCallback.java、TOOL/govern/AgentOsToolExecutionExceptionProcessor.java
- [X] T047 [US2] 实现 `UnknownToolResolver` 与 `ToolRegistry`（实现 ToolCatalog），注册 http_get 的治理 callback、处理器与解析器，ToolAutoConfiguration必须先于官方ToolCallingAutoConfiguration注册自定义resolver/processor；validateToolCallbacks 校验重名，Profile 名单未注册则外呼前失败；开启 `spring.ai.tools.resolution.fallback.enabled=true` 只能与“只返回安全错误且受审计的 fallback”同次启用。避免 resolver→registry→manager 循环依赖，不把未知 callback 暴露给模型 in TOOL/govern/UnknownToolResolver.java、TOOL/registry/ToolRegistry.java、TOOL/ToolAutoConfiguration.java、AgentOS-tool/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports、AgentOS-boot/src/main/resources/application.yaml
- [X] T048 [US2] 接通ReAct同步/流式完整工具分支及五类TurnSink事件，过程事件由循环驱动，complete/error由AgentService收尾后按剩余hardStop最多尝试一次；过程sink异常取消后不再回调，终结通知异常/超时不改已确认结果、不补onError；调用 ToolExecutor、保存新增助手/工具消息、沿完整历史继续、全部 returnDirect 提前收口；失败只在允许的工具错误上继续，不能捕获审计异常当工具结果；不重复追加原历史和用户消息（依赖 T042、T046、T047，FR-007、FR-009、FR-021、FR-030） in CORE/turn/ReActLoop.java
- [X] T049 [US2] 运行T039已前置并确认红灯的注册安全测试，补充集成装配复验（不在此首次编写行为测试）：请求级 callbacks 优先、fallback 永远不解析真实 Bean、空 tools 发给模型无 definitions、重名注册拒绝、未知名单报文件和字段；确认只有一个注入的 Manager/processor/resolver，原 spring.ai.tools.limits.* 生效；测试中真正装配tool的resolver和治理callback，不能放在core跨模块引用实现 in TOOL-TEST/registry/ToolRegistryTest.java、TOOL-TEST/ToolAutoConfigurationTest.java
- [X] T050 [US2] 在 boot 集成测试追加两次模型+一次工具的完整落库闭环，按数据库 ID 查询消息、核对第二次模型收到工具结果、审计 ID 与模型调用 ID 一致；沙箱拒绝仍可给模型解释；测试输出不得包含 key/URL token in BOOT-TEST/ReActApplicationTest.java、BOOT-TEST/ToolAuditRedactionTest.java
- [X] T051 [US2] 按 US2 独立测试执行质量门，先记录本地替身天气闭环结果；真实天气 Demo 只能在 US3 安全上限与 US5 审计恢复完成后执行，避免发布无硬上限的循环 in specs/002-react-loop/quickstart.md

**Checkpoint**: 工具只经 Manager 执行，名单外工具无权限，先记后做，完整工具闭环可用。

---

## Phase 5: [US3] 循环有上限（P2）

**Goal**: 限模型调用数、限工具调用数、硬截止时间，任何慢调用都有界。

**Independent Test**: 模型持续请求工具时恰好调用 10 次（覆盖配置 3）；同名和总次数超限只回错误；慢 HTTP、持续响应、审计等待组合不超过截止时间+1秒。

- [X] T052 [P] [US3] 编写迭代上限测试：默认10、覆盖3，最后一次响应有工具请求时不发第11/4次模型调用，输出明确说明与 MAX_ITERATIONS；已完成响应不被误判为上限（FR-014、FR-015；SC-004） in CORE-TEST/turn/ReActLoopLimitsTest.java
- [X] T053 [P] [US3] 编写 Manager 限次测试：一次响应多调用、跨迭代累计、单工具与总次数各自生效；跳过者不进入 callback，每个 ID 都有一条 limit_skipped；不同轮次不串计数；将日志文本判定排除在识别算法之外（FR-014、FR-016；SC-005、SC-007） in CORE-TEST/turn/ToolCallLimitsTest.java
- [X] T054 [P] [US3] 编写截止时间测试：调用前已过不外呼，Provider在deadline处结束后，最终模型审计/工具timeout更新可用共享最后1秒，数据库正常时记录存在且TIMED_OUT+CONFIRMED；挂起时按hardStop返回UNCONFIRMED；不能因执行预算归零直接丢最终记录；终结回调阻塞/抛异常仍按hardStop返回，已确认数据库终态不被回调失败改写；慢工具自己的超时回模型、被整轮剩余时间压短则结束轮次；迭代/checkpoint/审计等待共享预算；总耗时 ≤ 预算+1秒，不用整段固定 sleep 猜时序（FR-017 ~ FR-019；SC-006） in CORE-TEST/turn/TurnDeadlineTest.java、TOOL-TEST/govern/ToolDeadlineTest.java
- [X] T055 [P] [US3] 补 HTTP I/O 时间测试：首字节不返、响应头到后 body 挂起、慢速持续 body、连续重定向消耗共享预算；取消后不再读/重试，执行线程关闭，确保 HttpRequest.timeout 之外的 body 消费也受界限约束（FR-018；SC-006） in TOOL-TEST/http/HttpToolsDeadlineTest.java
- [X] T056 [US3] 实现 ReAct 模型调用计数与 MAX_ITERATIONS 安全回复、iteration 开始写日志序号；沿同一截止时间检查并优先归类真实整轮超时；不额外发一次“总结”模型请求（依赖 T052、T054，FR-014、FR-015、FR-017、FR-019） in CORE/turn/ReActLoop.java、CORE/turn/TurnGuard.java
- [X] T057 [US3] 实现 ToolExecutor 的超限补记：只把 Manager 正常返回后的账本剩余调用写 limit_skipped；管理器中途致命异常导致未处理的调用不能误标成超限；保存完整本轮历史，不能跨轮重置官方计数（依赖 T053、T056，FR-016） in CORE/turn/ToolExecutor.java、CORE/turn/ToolCallLedger.java
- [X] T058 [US3] 强化治理层剩余预算、Future 取消、整轮超时重抛、资源清理：超时后不再发下一工具；终态审计不能吞掉超时或遮盖租约丢失；checkpoint 与工具执行都必须符合较小预算，不把每次跳转当新30秒（依赖 T054、T055，FR-017 ~ FR-019） in TOOL/govern/GovernedToolCallback.java、TOOL/http/HttpTools.java
- [X] T059 [US3] 配置启动时校验并完成边界测试：默认限次10/30、RETURN_ERROR_RESPONSE、不改变全局禁用 ToolCallingAdvisor；动态租约至少deadline+30s、TTL仅最短值、非法时长/超范围重试/跳转参数失败，错误不含 key（依赖 T017、T056 ~ T058） in CORE/config/CoreAutoConfiguration.java、TOOL/ToolAutoConfiguration.java、BOOT-TEST/ReActConfigurationTest.java、AgentOS-boot/src/main/resources/application.yaml
- [X] T060 [US3] 对T037已先写红灯、T048已实现的行为做真实Provider HTTP替身集成复验：正常流式delta→工具调用→工具结果→后续delta→预算充足时唯一complete、失败唯一error；无通知预算可跳过并记未送达；过程sink异常后停止回调；终结回调阻塞/抛异常不延长返回时限、不双发、不改CONFIRMED结果；同步/流式最终结果一致；缩短deadline穿过整条循环、持续分片不能续命；账本限次与 Manager 配置真实装配，不能只用 mock 证明上限（FR-013、FR-017 ~ FR-019；SC-006） in BOOT-TEST/ReActDeadlineIntegrationTest.java
- [X] T061 [US3] 完成 US3 质量门和超时证据，分别记录模型次数、实际工具执行数、被跳过记录数、预算和实际耗时；不能仅按最终状态判断满足截止时间 in specs/002-react-loop/quickstart.md

**Checkpoint**: 无无限循环、无绕过工具限次；有消息增量也不会续命整轮截止时间。

---

## Phase 6: [US4] 多轮历史与新会话（P2）

**Goal**: 重开 chat 继续历史，整轮截断，失败历史安全恢复，--new 不破坏旧数据。

**Independent Test**: 预置含工具的成功轮次和各种失败轮次，检查下一轮 Prompt；改变日期/Agent 文件，下一轮立即更新；归档后新会话不带旧历史。

- [X] T062 [P] [US4] 编写 Prompt 历史测试：20轮/32000 token 两道限制、边界相等、最旧整轮丢弃、单轮过大丢历史但当前用户保留；工具请求/响应不拆；所有失败终态只取用户+安全未完成说明；系统指令顺序稳定、日期跨零点更新（FR-020、FR-022、FR-023；SC-011） in CORE-TEST/turn/PromptHistoryTest.java
- [X] T063 [P] [US4] 追加契约/CLI 测试：recentTurns 按轮次和消息顺序；重新启动 chat 沿同一 active；--new 空会话不报错、旧行/审计零修改、归档时间被写、新 UUID、旧历史零进入；正文和 Bootstrap 改完下一轮生效（FR-002、FR-024、FR-032、FR-040、FR-041；SC-012、SC-017） in CORE-TEST/contract/MessageStoreContractTest.java、CORE-TEST/contract/SessionManagerContractTest.java、CLI-TEST/ChatCommandTest.java、CORE-TEST/agent/ContextLoaderTest.java
- [X] T064 [US4] 实现 PromptBuilder 的历史恢复和按完整轮次双限截断，使用 JTokkitTokenCountEstimator；明确历史预算只作用于历史、不偷偷截断系统/当前用户；未完成原因脱敏简短，数据库原消息不改（依赖 T062，FR-020、FR-022、FR-023） in CORE/turn/PromptBuilder.java
- [X] T065 [US4] 实现 JpaMessageStore 的 recentTurns：先选轮次再读全轮消息，不能对消息行直接 LIMIT 截断；消息 codec 保留工具调用ID/参数/结果/metadata；exclude 当前 RUNNING 避免重复带入本条用户（依赖 T063、T064，FR-022、FR-032） in STORE/session/JpaMessageStore.java、STORE/session/MessageCodec.java
- [X] T066 [US4] 实现 archiveIfIdle 与 CLI --new：归档仅从 active 到 archived，不可逆，不改旧记录；归档用 SQL 条件保证只在空闲生效，不能先读再写；默认不开新会话，--new 与 --message 组合工作（依赖 T063，FR-002、FR-040、FR-041；SC-017） in STORE/session/JpaSessionManager.java、CLI/ChatCommand.java、CHANNEL/CliChannel.java
- [X] T067 [US4] 确保每一条新用户消息重新加载 Agent 与 Bootstrap、重新校验工具名单，不在同轮每次迭代重读文件改变运行配置；不缓存 LoadedAgent 到整个 CLI 生命周期（依赖 T063、T064、T066，FR-024） in CORE/turn/AgentService.java、CORE/agent/ContextLoader.java、CLI/ChatCommand.java
- [X] T068 [US4] 编写数据库重启/新上下文测试：在同一容器保留数据、关第一个上下文再开第二个，接回旧会话；--new 之后查询旧记录一致、Prompt 不带旧历史；重跑 JPA/内存两版契约（FR-032、FR-040；SC-017） in BOOT-TEST/ConversationResumeTest.java、STORE-TEST/session/JpaMessageStoreContractTest.java、STORE-TEST/session/JpaSessionManagerContractTest.java
- [X] T069 [US4] 通过 US4 独立验收并更新多轮、热更新、--new 的验证步骤与实际结果，历史上限0、正token预算90% floor均有证据，用户标识不写死到测试用例外 in specs/002-react-loop/quickstart.md

**Checkpoint**: 历史工具调用/结果零拆分；失败历史不会把半截工具链再次送给模型。

---

## Phase 7: [US5] 审计完整性与故障恢复（P2）

**Goal**: 实际调用记录可追溯，失败重试不重复执行；崩溃遗留有状态可查。

**Independent Test**: 注入数据库瞬时故障、响应丢失与持续故障，对照调用次数；构造过期 RUNNING/in_progress 记录并启动收尾；查数据库核对状态与脱敏。

- [X] T070 [P] [US5] 编写审计重试测试：总3次、间隔200/500ms、只重试瞬时故障、每次新事务、不把不合法数据当瞬时故障；恢复后仅一行、持续失败明确中止；begin 失败真实工具零执行，complete 失败提示可能已执行；事务提交结果不确定时不得制造重复行或重发外部调用；截止时间耗尽不能继续睡眠；在途调用最终审计与全部cleanup共用最后1秒，每项最终审计最多一次写尝试、提交未知仅预算内查证、不退避重放；SQL/连接/锁等待故障都纳入，UNCONFIRMED不当已落库（FR-031；SC-015） in BOOT-TEST/TransactionBoundaryTest.java、STORE-TEST/audit/AuditTransactionTest.java、CORE-TEST/audit/RetryingAuditWriterTest.java、TOOL-TEST/govern/AuditFailureTest.java
- [X] T071 [P] [US5] 编写模型审计集成测试：429后成功2行、最终失败保留失败行、correlation/attempt/cache/cap/nullable字段完整，RecordException 不触发模型重试；断言数据库用量 null 与0，审计/日志无Prompt与key（FR-034、FR-036、FR-037；SC-007、SC-008） in BOOT-TEST/LlmAuditIntegrationTest.java
- [X] T072 [P] [US5] 编写 TurnReaper 与结束失败测试：没有匹配owner有效租约的旧RUNNING被中断（即使deadline未过），关联 in_progress 工具一起更新；有效owner/未过期租约不被清掉，终态不二次改写；终态FAILED/INTERRUPTED父轮次的in_progress孤儿记录独立收尾；接管也清理，不仅启动；重入清理幂等；数据库写终态/释放失败也停止外呼并留恢复证据（FR-006、FR-031；SC-010） in STORE-TEST/TurnReaperTest.java、CORE-TEST/turn/AgentServiceFailureTest.java
- [X] T073 [US5] 实现 `RetryingAuditWriter` 并接入两类记录器：退避等待在事务外、总3次尝试、执行预算不足时不再普通重试，但在途调用最终审计可用剩余cleanup预算一次写尝试；hardStop到立即停止；stable 工具唯一键确保 begin 重试不双插、complete 仅更新原行；模型记录record入口生成write_id UUID、同次重试复用，事务成功响应丢失时按唯一键和相同payload确认，不以吞唯一键异常掩盖字段不一致（依赖 T070、T071，FR-031、FR-034、FR-035；SC-015） in CORE/audit/RetryingAuditWriter.java、CORE/audit/AuditWriteException.java、STORE/audit/JpaLlmCallRecorder.java、STORE/audit/JpaToolInvocationRecorder.java
- [X] T074 [US5] 完成工具异常/限次/更新失败的统一落库与 ERROR 报告：所有审计路径包括未知工具、非法参数、被拒/跳过均覆盖，不重试工具；用户说明区分执行前/执行后写失败，原始数据库异常与凭证不外露；线程与 MDC 清理有 finally（依赖 T070 ~ T073，FR-030、FR-031、FR-035 ~ FR-037） in TOOL/govern/GovernedToolCallback.java、TOOL/govern/UnknownToolResolver.java、CORE/turn/ToolExecutor.java、CORE/turn/AgentService.java
- [X] T075 [US5] 实现启动收尾 core 逻辑、JpaTurnStore 原子清理与一次性 Runner：只处理没有有效owner租约的旧RUNNING（不等deadline）、终态父轮次in_progress孤儿；RUNNING→INTERRUPTED，工具→interrupted；启动与接管共享同一幂等收尾，终态/工具/lease清理一次短事务；不能清新owner、不能把正常终态改中断；启动Runner仅在chat上下文触发，会话创建/接管由TurnStore.startTurn事务内调用相同收尾逻辑，SessionManager只读查询/空闲归档，不让纯Recorder测试和无DataSource开发上下文误装配；不实现周期任务/ShedLock调度（依赖 T072，FR-006、FR-035；SC-010） in CORE/turn/TurnReaper.java、STORE/session/JpaTurnStore.java、STORE/TurnReaperRunner.java、STORE/StorageAutoConfiguration.java
- [X] T076 [US5] 完善 context 关闭时在途轮次收尾：CLI Ctrl-C/launcher close 取消在途等待、尽力中断轮次和释放自身租约，不能清他人租约；不能依赖关闭钩子“必然完成”，强杀恢复交给 T075；明确 daemon/background 资源生命周期（依赖 T072、T075，FR-006） in BOOT/SpringContextLauncher.java、CORE/turn/AgentService.java、CHANNEL/CliChannel.java
- [X] T077 [US5] 运行T070已先写并确认红灯的事务边界集成测试，补充全链路复验：模型阻塞、工具等待、审计退避时确认没有包住网络调用的事务/持有连接；小连接池下独立审计写入仍可用，数据库超时消耗也计入截止时间；不要仅用注解反射判断（FR-019、FR-038；SC-006） in BOOT-TEST/TransactionBoundaryTest.java、STORE-TEST/audit/AuditTransactionTest.java
- [X] T078 [US5] 核对并完成记录器替换装配测试与审计日志约定：恰好一个落库 recorder，Provider 调用逻辑无修改；落库确认后恰好一条脱敏llm_call日志，SQL重试不重打；失败写ERROR并带脱敏结构化调用元数据、write_id和persistenceStatus=UNCONFIRMED，不输出原响应/Prompt/key，也不能当审计落库成功，既有 LlmCallJsonLogTest 仍验证独立日志实现；全链路敏感信息断言含 throwable、结构化字段与MDC（FR-012、FR-034、FR-036、FR-037；SC-008） in BOOT-TEST/AuditAutoConfigurationTest.java、BOOT-TEST/ToolAuditRedactionTest.java、AgentOS-boot/src/test/java/com/agentos/boot/LlmCallJsonLogTest.java
- [X] T079 [US5] 跑 US5 全部故障路径与质量门，记录先记后做、数据库恢复后的行数、不确定提交的去重证据、启动收尾和持久故障限制；共享库不可用于破坏性故障测试 in specs/002-react-loop/quickstart.md

**Checkpoint**: 瞬时故障不丢/不重记；持续故障不静默继续，已执行工具的记录在故障恢复后可识别。

---

## Phase 8: [US6] 会话并发与租约安全（P3）

**Goal**: 同一会话不能交错处理，旧owner丢租约后不能继续写，新owner不受清理影响。

**Independent Test**: 使用闸门让首轮停在模型/工具上；第二条消息或 --new 必须被拒绝且不写入；独立事务抢租约、模拟丢租约，旧轮次立刻中断。

- [X] T080 [P] [US6] 编写并发端口契约：多个startTurn竞争创建同一会话只有一个active、多个owner抢租约只有一个成功、续租仅匹配owner、释放不能清新owner；旧owner发现数据库已回收为INTERRUPTED且审计/工具/lease均收敛时CONFIRMED，不同终态以数据库为准、查询未知才UNCONFIRMED；过期租约可重获、archiveIfIdle 与开始轮次的竞争原子化；内存和JPA都跑相同用例（FR-002、FR-004 ~ FR-006；SC-009、SC-017） in CORE-TEST/contract/SessionManagerContractTest.java、CORE-TEST/contract/TurnStoreContractTest.java
- [X] T081 [P] [US6] 编写入口并发/丢租约测试：同会话第2条零写入、原轮次继续；每次迭代和每个工具前续租；finishAndRelease更新0行先查证：已有终态且已收敛返回数据库终态+CONFIRMED，不覆盖、不误报数据库故障；lease丢失终态INTERRUPTED、此后零消息/零外呼；Provider两次长请求+等待均被动态lease覆盖，不会中途被接管；--new 在租约被占用时零归档；不同会话能各自进展（FR-004 ~ FR-006、FR-040；SC-009、SC-010、SC-017） in CORE-TEST/turn/AgentServiceConcurrencyTest.java、CORE-TEST/turn/LeaseLossTest.java、CLI-TEST/ChatCommandTest.java
- [X] T082 [US6] 完善数据库租约 SQL 与事务竞争：显式取得所需行锁后捕获db_now=clock_timestamp()，本操作的deadline/租约/过期与owner检查共用该值；不用事务起点now()做租约实时判断；取租约/建轮次/写初始用户消息边界防并发重复；归档不能和新轮次同时成功，忙请求不写消息或轮次；不靠进程内锁（依赖 T080，FR-002、FR-004、FR-040） in STORE/session/JpaSessionManager.java、STORE/session/JpaTurnStore.java
- [X] T083 [US6] 完善 TurnGuard、AgentService 与治理层丢租约终止：先deadline再租约检查，续租失败不变普通工具错误；消息追加前检查owner约束，finally仅释放自己的租约，拒绝请求安全返回SESSION_BUSY而不是500（依赖 T081、T082，FR-004 ~ FR-006、FR-042） in CORE/turn/TurnGuard.java、CORE/turn/AgentService.java、TOOL/govern/GovernedToolCallback.java、STORE/session/JpaMessageStore.java
- [X] T084 [US6] 运行多连接 PostgreSQL 竞争集成测试，不把同一个测试事务共享给并发线程；阻塞模型期间第二条忙、抢租约后旧owner不能写、new-owner租约不被旧finally清理、--new竞争可重复（FR-004、FR-005、FR-040） in BOOT-TEST/SessionConcurrencyIntegrationTest.java、STORE-TEST/session/JpaTurnStoreContractTest.java
- [X] T085 [US6] 在 CLI 端补“上一条消息还在处理”输出、--message退出3、交互模式可继续下一次输入；--new 没有active直接开始，终端关闭后同一用户名恢复会话，不打印SQL/内部堆栈（依赖 T081 ~ T084，FR-040 ~ FR-042） in CLI/ChatCommand.java、CHANNEL/CliChannel.java、CLI-TEST/ChatCommandTest.java
- [ ] T086 [US6] 完成 US6 独立验收与质量门，记录并发时零被拒消息写入、owner切换和归档保留旧历史的证据；不能用单线程顺序测试替代数据库竞争测试 in specs/002-react-loop/quickstart.md

**Checkpoint**: 同会话串行、多会话互不阻塞；旧owner不能污染新owner的消息或租约。

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T087 补充并运行架构规则：现有 core 依赖倒置、唯一 ToolExecutor、禁止自建Manager/ThreadLocal/MDC读取/ToolCallingAdvisor、构造器注入继续生效；增加不让工具/模型外呼被事务包裹的静态辅助规则但以 T077 动态验证为准，不降低已有规则（SC-014） in AgentOS-boot/src/test/java/com/agentos/boot/ArchitectureRulesTest.java
- [ ] T088 同步设计事实源：技术方案9.2的工具in_progress/interrupted/updated_at和llm failure_kind/http_status/write_id已随分析修订同步，本任务核对实现与这些字段一致；核对Tool先记后做、动态lease和接管收尾；plan/research已修事实不重复保留“model可选”等旧风险；更新CLI用法与 --new，不动辅助调用未决设计或宪章 in docs/TechnicalSolution.md、docs/AiProgrammingGuide.md、specs/002-react-loop/plan.md、specs/002-react-loop/research.md
- [ ] T089 编写真实模型闭环测试，`@Tag("llm")` 且按 AGENTOS_LLM_API_KEY 条件启用：真实primary+本地天气替身+Testcontainers库，检验SUCCEEDED、工具调用和消息/审计关联，执行次数是否精确按脚本行为核对；费用与外部数据发送在运行前说明，不把跳过当通过（SC-001、SC-003、SC-007） in BOOT-TEST/smoke/ReActWeatherSmokeTest.java
- [ ] T090 在临时工作目录执行真实天气 Demo：使用met.no HTTPS和白名单、手写weather Agent、chat --message与 --new；先确认目标数据库与迁移授权，不在共享库做clean或故障注入；记录真实模型、真实数据源、最终建议、工具/模型/消息行数与脱敏证据。说明模型可能多次取数，不能未经实测宣称必为2次模型调用（SC-001） in specs/002-react-loop/quickstart.md
- [ ] T091 更新官网中英文状态和运行文档，只把实际已验收项标已实现，区分US-2、后续Memory/Tool/Web；不把本地测试SSE写成正式API，保留真实Demo尚未运行时的说明 in website/docs/react-loop.md、website/en/docs/react-loop.md、website/docs/quick-start.md、website/en/docs/quick-start.md、website/docs/roadmap.md、website/en/docs/roadmap.md
- [ ] T092 运行 `mvn spotless:apply`、`mvn -B verify`；有Docker时数据库测试实际执行、CI不跳过；运行官网 `npm run verify`；按需求核对42条FR和22条SC，生成明确通过/失败/未运行结果，任何失败不勾完成（SC-014） in specs/002-react-loop/quickstart.md、specs/002-react-loop/tasks.md
- [ ] T093 检查spec/plan/tasks/实现漂移、全部待办和临时探针、产物里无密钥与正式SSE接口；运行 `/speckit-analyze` 形成只读报告，必要修复须另获授权。当前不自动提交、推送、合并，用户要求后再做 in specs/002-react-loop/spec.md、specs/002-react-loop/plan.md、specs/002-react-loop/tasks.md

---

## Dependencies & Execution Order

### 阶段依赖

```text
Setup T001~T006
  └─ Foundational T007~T017
       └─ US1 T018~T035（纯文本 CLI MVP）
            └─ US1 增量 T094~T109（默认 main；追加编号，优先执行）
                 └─ US2 T036~T051（工具闭环）
                      └─ US3 T052~T061（硬上限与超时）
                           └─ US4 T062~T069（历史与 --new）
                                └─ US5 T070~T079（故障恢复审计）
                                     └─ US6 T080~T086（并发加固）
                                          └─ Polish T087~T093（最终覆盖22条SC）
```

- 阶段内先测试再实现。T039包含T047安全注册的前置测试，T037包含T048正常流式前置测试，T070包含T073审计事务前置测试；T049/T060/T077只做集成复验。T007~T011 按引用类型先后定义（T008需要T007，T009需要T008），不标成无条件并行；T012 依赖这些契约，T013/T014 依赖 T012/T004。T015/T016 必须顺序改同一个迁移文件。T017 需要 T007~T010 的类型，但不依赖运行循环。
- Setup 的 T002 是测试依赖前置；T003/T005 可并行，T006 串行核对 pom 以免覆盖 T002，T004 等 T003 红灯确认后实现。
- `[P]` 测试任务在共同夹具完成后并行；各组写同名文件的“追加测试”必须顺序执行，例如 US2 和 US3 都会修改 ToolExecutorTest 所属链路，不能同时覆盖。
- US2 的 T036~T040 可并行；实现中的 T041/T043/T045 在各自测试红灯与基础设施就绪后可独立推进，T046/T047 等共同依赖完成再装配。
- US3、US4、US5、US6 理论上有部分独立文件，但都修改 ReActLoop/AgentService/存储端口，默认按上述顺序串行，不为了并行复制一条执行链。
- US1/US2 是开发阶段的纵向检查点，不是提前交付全部生产保证；真实外部工具演示必须等 US3 和 US5 闸门通过。
- 默认 main 增量只依赖已完成的 Setup、Foundational、US1，不依赖 T036–T093；先执行 T094–T109，再恢复原 Phase 4，不能因它写在文末就等到全部 Polish 之后。增量不实现 `--new`，原 US4 任务继续负责归档语义。

### 并行执行示例（每个故事）

| 故事 | 可同时启动的任务 | 共同前置/合流 |
|------|------------------|---------------|
| US1 | T018 Agent解析、T019 Prompt、T020循环、T021 CLI、T022 JPA测试 | Phase2完成；红灯后T023/T029等分别实现，T032装配合流 |
| US1 默认 main 增量 | T095 模型来源测试、T096 Agent 本地校验测试 | T094完成；文件不同可并行，T097/T098各在对应行为红灯后实现；之后初始化、选择、启动器按文末依赖串行合流 |
| US2 | T036脱敏、T037 Manager/账本、T038HTTP、T039治理、T040记录器测试 | US1完成；T046需工具/审计/脱敏均就绪 |
| US3 | T052迭代上限、T053工具限次、T054截止时间、T055HTTP body预算 | US2完成；T060全链路验证 |
| US4 | T062历史预算、T063存储/CLI追加测试 | US3完成；T068重启/归档数据库验证 |
| US5 | T070重试、T071模型审计、T072崩溃恢复 | US4完成；T077事务测试、T078装配合流 |
| US6 | T080数据库竞争契约、T081入口并发场景 | US5完成；T084多连接集成验证 |

## Implementation Strategy

### MVP First

1. Setup、Foundational（数据库spike真实通过）。
2. US1：init和纯文本chat，1次模型调用、1个成功轮次、2条消息、1条模型审计落库；这就是最小纵向MVP。
3. US1 默认 main 增量：T094–T109，无需手写第一个 Agent，初始化后直接 chat；仍为无工具纯文本 MVP，不要求先完成 US2–US6。
4. 停下验证，不在这一刻执行未受约束的工具循环。

### 增量交付

US2 加本地替身工具闭环 → US3 加全部次数/时间上限 → US4 加历史和新会话 → US5 加持久故障和崩溃恢复 → US6 加数据库竞争验证 → 真实天气阶段Demo与全质量门。

每个阶段完成后更新任务复选框和真实验证结果。数据库测试、真实模型和真实天气演示是三类不同证据：完成其中一类不代表另外两类通过。

---

## Phase 10: [US1] 初始化默认主 Agent（P1，2026-11-16 增量；执行于 Phase 3 后、Phase 4 前）

**Goal**: `init` 生成普通、无工具权限的 `main`，已有外部连接配置可用时无需手写 Agent 或指定 profile 即可聊天；自定义工作区从初始化到实际 Agent/Bootstrap 加载保持一致。

**Independent Test**: 临时目录 + 合成模型输入完成离线初始化；再经真实 CLI init/chat、真实 boot launcher、本地模型替身和 Testcontainers PostgreSQL 完成一条纯文本消息，核对 main 身份、指定目录指令及 1 session / 1 successful turn / 2 messages / 1 llm_call / 0 tool_invocations。无需真实模型、真实天气或后续用户故事实现。

**前置与边界**：依赖已完成 T001–T035；实施前由 T094 核对本次增量 plan 的人工 review。参考 plan“默认主 Agent 增量设计”、research R20–R24、data-model 增量节、workspace/configuration 契约和 quickstart 第 9 节。不新增表/迁移、通用 ConfigLoader、Profile 管理命令、默认模型常量或第二条模型/工具执行入口；不实现 `--new`。

### 设计核对与模型来源（先确认行为红灯）

- [X] T094 [US1] 核对默认 main 增量 plan 的人工 review 与实施范围，确认模型来源优先级、空工具权限、无模型零写入、create-new 幂等、Path 启动器、高优先级工作区绑定和隔离验收已明确；旧 T001 的 review 不替代本次 review，缺少确认或发现宪章冲突时先报告，不擅自改变设计；在验收记录中注明核对结果，不重开旧任务（依赖 T035） in specs/002-react-loop/plan.md、specs/002-react-loop/quickstart.md
- [X] T095 [P] [US1] 编写模型来源/初始化选项测试：`--model` > 进程 `AGENTOS_LLM_MODEL` > 执行目录合成 `.env`，区分未提供与无效所选值，空白/占位符不回退、高优先级已提供则不读低优先级；覆盖可选非空白 provider、UTF-8 properties 转义/续行/重复键最后生效、无 shell/export/插值语义、I/O 与语法错误安全分类、仅投影目标键及秘密标记不泄露。新类型仅允许先补最小可编译契约，必须观察断言行为失败而非编译失败；测试不读真实 `.env`、不依赖本机环境（依赖 T094；US1-9/14，SC-019/022） in CLI-TEST/InitModelResolverTest.java、CLI/InitModelResolver.java、CLI/DefaultAgentOptions.java
- [X] T096 [P] [US1] 追加 Agent 本地校验测试，固定默认 main 文件的“name 必须等于 main”“model 为具体非空白值且无未解析占位符”；其他 Agent 仍按 name 等于各自目录名校验。覆盖损坏 frontmatter、目录/文件类型冲突、末级链接及合法自定义 Agent；对尚未拒绝的模型占位符先观察行为红灯，不调用 Provider 或数据库、不修改 Profile 签名（依赖 T094；US1-10/11，FR-025/026，SC-019） in CORE-TEST/agent/AgentLoaderTest.java
- [X] T097 [US1] 实现 `DefaultAgentOptions` 与 `InitModelResolver`：字段仅 `String model`、可空 `String providerName`，“只包含非敏感值，不含环境 Map、连接串、key、密码”；模型“具体非空白值且无未解析占位符”，可选 provider 提供时非空白。注入环境查询函数和执行目录，按 T095 矩阵惰性解析，JDK Properties + UTF-8 Reader 仅保留 `AGENTOS_LLM_MODEL` 投影，不执行 shell、不展开变量、不持有/输出其他配置或底层异常原文；不得新增通用配置系统或 dotenv 依赖（依赖 T095；FR-039，research R21） in CLI/DefaultAgentOptions.java、CLI/InitModelResolver.java
- [X] T098 [US1] 在既有 AgentLoader 补齐未解析模型占位符的本地拒绝规则，错误只指明文件/字段；保留既有名称、类型、链接和设置校验，不进行环境展开、远端连接探测，不修改 US-1 Profile 或 Provider call/stream/retry（依赖 T096；FR-025/026/039，research R20/R22） in CORE/agent/AgentLoader.java

### 默认 main 文件与 init 入口

- [X] T099 [US1] 先扩展初始化/命令行为测试：空工作区生成可加载 main；名称固定 main、中文通用指令、`tools: []`，未提供 --provider 时省略 provider.name，始终不写温度和连接凭据；特殊 YAML 输入 round-trip；缺模型时任何工作区写入为 0；已有合法 main 不读取新模型来源、传不同模型也不覆盖；损坏 main 返回 2、路径/链接冲突与 I/O 返回 1；create-new 竞争保留赢家、可控中途失败及重试只补缺项。验证新建/跳过输出、无 Spring/模型/数据库调用和合成秘密标记不泄露；更新原“无模型 init 成功”断言为合法前提。必要时仅补测试注入点/空签名，确认行为红灯（依赖 T097、T098；US1-4/7/9/10/14，SC-013/018/019/022） in CLI-TEST/WorkspaceInitializerTest.java、CLI-TEST/AgentOSCliTest.java、CLI/WorkspaceInitializer.java、CLI/InitCommand.java
- [X] T100 [US1] 扩展 WorkspaceInitializer：所有路径类型/已有 main 本地校验及必要模型解析先于任何写入；仅 main 缺失时使用 DefaultAgentOptions，已有合法 main 直接保留；结构化 YAML 序列化 `name: main`、具体 model、可选 provider.name 和 `tools: []`，附中文正文、不写连接/凭据/温度。目录必须是目录、文件必须是普通文件，拒绝受管理链接冲突，逐文件 CREATE_NEW，竞争者已创建则校验后跳过或报错；中途失败保留成功内容，不伪事务回滚或自动覆盖坏文件。Result 保留 `List<Path> created`、`List<Path> skipped`，“不放文件正文或配置”；CLI 直接引用 SnakeYAML 时显式声明既有受管依赖，不新增库或版本（依赖 T099；FR-039，research R22） in CLI/WorkspaceInitializer.java、AgentOS-cli/pom.xml
- [X] T101 [US1] 为 InitCommand 接入 `--model`/`--provider`、可注入模型来源和执行目录，`--workspace` 默认 `.AgentOS`、相对执行目录规范化为绝对根；已有 main 时跳过新的模型解析，无模型且 main 缺失则写入前安全失败。明确成功 0、无效输入/坏 Agent/非法 properties 2、文件 I/O 1，输出新建/跳过与仅本地文件已准备，不拼接异常原文、值、密钥或堆栈；保留 help/version 和独立 CLI，不启动 Spring（依赖 T100；FR-039/042，research R21/R24） in CLI/InitCommand.java、CLI/AgentOSCli.java、CLI/AgentOSCommand.java

### Agent 选择与工作区装配（测试先于对应实现）

- [X] T102 [US1] 扩充 ChatCommand 选择与退出码测试：显式 profile > main > 无 main 时唯一 Agent，多个列候选、零个提示 init；损坏/缺文件 main、显式无效 Agent 和路径片段不回退、不调用 launcher/service；只校验选中项，其他坏 Agent 不阻断显式有效选择。验证空消息不建轮次、已有单消息/交互行为、启动失败 1、用法错误 2、轮次不成功 3、已打开上下文正常/异常均关闭；使用临时合法 Agent 文件修正原目录空夹具，确认新增选择行为红灯（依赖 T101；US1-11/12，FR-040/042，SC-020） in CLI-TEST/ChatCommandTest.java、CLI-TEST/AgentOSCliTest.java
- [X] T103 [US1] 实现 main 优先及旧工作区兼容选择；main 路径存在即校验而不因损坏过滤后回退，只校验最终选中 Agent，复用 AgentLoader、关闭 Files.list Stream、候选遵守末级链接限制；选择/文件错误在启动器前返回 2，运行时错误按既有契约安全映射，保持消息/交互处理及 close。保留 `--new` 参数与原任务，不在本增量实现或冒报归档功能（依赖 T102；FR-040/042，research R23/R24） in CLI/ChatCommand.java、CLI/AgentOSCli.java
- [X] T104 [US1] 编写工作区端口和真实 launcher 绑定的前置测试，验证 Path 非空/规范化、CLI 传入同一根、NONE 模式与关闭行为、CLI 工作区优先于冲突普通配置；扩展测试 LocalModelServer 捕获合成请求。可先将 ContextLauncher 契约迁移到 `launch(Path workspaceRoot)` 并同步仓库内全部调用者/假实现，以及 boot 包内构建工厂的最小注入接口，使测试可编译；此时 boot 保持原启动行为，不提前实现 `.run` 工作区覆盖，必须观察实际绑定行为红灯。工厂仅供测试隔离 Environment，配置源低于 commandLineArgs、高于普通环境/文件，关闭 `.env` 导入；禁止新增用户测试选项或全局系统属性（依赖 T103；FR-040，research R23） in CLI/ContextLauncher.java、CLI/ChatCommand.java、CLI-TEST/ChatCommandTest.java、CLI-TEST/AgentOSCliTest.java、BOOT/SpringContextLauncher.java、BOOT-TEST/SpringContextLauncherTest.java、BOOT-TEST/support/LocalModelServer.java
- [X] T105 [US1] 编写默认 main 的真实 CLI 装配测试并确认行为红灯：使用已有 db 环境检测、`pgvector/pgvector:pg17` 和本地模型替身，经 CLI init 创建 main 后不手写 main、不传 profile 调 CLI chat + 真实 SpringContextLauncher；提供不同的低优先级 workspace 与冲突默认目录，断言实际请求模型、无 tools、所选 main/Bootstrap 标记及默认目录未读写；空库核对 main 的 1 session、1 SUCCEEDED turn、2 messages、1 llm_call、0 tool_invocations、持久化确认与资源关闭。禁用真实 `.env`/Embedding/外部连接，不以假 launcher 收到 Path 或直接调 AgentService 替代；Docker 被跳过不算红灯或通过（依赖 T104；US1-8/13，SC-018/021） in BOOT-TEST/DefaultAgentCliIntegrationTest.java、BOOT-TEST/support/LocalModelServer.java、BOOT-TEST/BootDatabaseTestEnvironment.java
- [X] T106 [US1] 落实 Path 启动器的实际传递：CLI 只规范化一次并传同一根，boot 在 NONE `.run(...)` 传单个 `--agentos.workspace.root=<绝对路径>`，优先于普通环境/文件，不用低优先级 builder 默认属性或 System.setProperty；移除忽略路径的无参回退。完成包内构建工厂的隔离测试支持但保持生产行为，core 两个加载器继续共享 WorkspaceProperties.root，无需改造循环或存储；让 T104/T105 绑定与真实请求断言转绿（依赖 T104、T105；FR-040，research R23/R24） in CLI/ContextLauncher.java、CLI/ChatCommand.java、BOOT/SpringContextLauncher.java、BOOT/AgentOSApplication.java

### 独立验收与文档

- [X] T107 [US1] 按 quickstart 第 9 节执行临时目录初始化烟测及新增自动化矩阵，运行 `mvn -B -ntp verify`、`python3 scripts/check_db_test_reports.py`，核对新增数据库集成测试实际出现在报告中且执行未跳过；不重复发送真实模型请求。逐项记录 SC-018–SC-022 的 PASS/FAIL/NOT_RUN、测试名称/数量、退出码与数据库执行/跳过原因；无 Docker 时可记录文件层通过，但真实装配仍 NOT_RUN，不能勾完整验收；CI 不得跳过。仅在代码变化/失败修复后重跑受影响验证（依赖 T097、T098、T101、T103、T106；SC-014/018–022） in specs/002-react-loop/quickstart.md、scripts/check_db_test_reports.py、AgentOS-boot/target/surefire-reports/TEST-com.agentos.boot.DefaultAgentCliIntegrationTest.xml
- [X] T108 [US1] 基于实际实现与 T107 证据更新 CLI/boot 代码导读、结构图及技术方案 8.1/8.2 的初始化用法，明确共享 AGENTS 是指令、main 是普通 Profile、模型来源只用于创建、init 仅本地就绪和实际 workspace 传递；新增生产代码具备中文意图/边界注释，不把未验证行为标已实现。保留 quickstart 2.1 历史和旧完成记录，说明默认 main 无工具、weather 需显式选择；同步本次增量的设计/实施状态，不宣称整个 ReAct 已交付（依赖 T107） in docs/code-reading/AgentOS-cli.md、docs/code-reading/AgentOS-boot.md、docs/code-reading/ClassStructure.md、docs/TechnicalSolution.md、specs/002-react-loop/plan.md、specs/002-react-loop/contracts/workspace-and-cli.md、specs/002-react-loop/quickstart.md
- [X] T109 [US1] 在本次增量实施后运行 `/speckit-analyze` 并核对 FR-039/FR-040、US1-7–14、SC-018–SC-022 的 spec/plan/data-model/contracts/tasks/代码与实测证据；本增量新增漂移须修复并重验受影响检查，原 Phase 4–9 未完成项单列，不当成新增 main 已完成或因此改勾历史。确认无密钥/通用配置系统/新迁移/新工具入口/冒报 --new，记录收口结论，不自动提交、推送或发布（依赖 T108） in specs/002-react-loop/spec.md、specs/002-react-loop/plan.md、specs/002-react-loop/data-model.md、specs/002-react-loop/contracts/workspace-and-cli.md、specs/002-react-loop/contracts/configuration.md、specs/002-react-loop/tasks.md、specs/002-react-loop/quickstart.md

**Checkpoint**: T107 的真实 CLI + 替身 + PostgreSQL 验收实际通过，且 T109 漂移检查闭合后，默认主 Agent 的最小增量可独立验收；Phase 6 已完成，T070–T093 仍保持各自待办，不视作完整 ReAct 完成。

### 增量依赖、覆盖与并行

```text
T035 → T094
         ├─ T095 → T097 ─┐
         └─ T096 → T098 ─┴─ T099 → T100 → T101 → T102 → T103
T103 → T104 → T105 → T106 → T107 → T108 → T109
```

T095 与 T096 在 T094 完成后可并行，分别写 CLI 和 core 的测试文件，不共享修改。其余任务默认串行，尤其 T099/T102/T104 都追加 CLI 测试，不能同时覆盖；T104 的签名迁移一次同步所有引用，避免跨模块半迁移。

| 验收范围 | 测试任务 → 实现/验证 | 对应需求 |
|----------|----------------------|----------|
| 模型来源、合法输入与字段约束 | T095/T096 → T097/T098 | FR-039、FR-025/026；SC-019 |
| 首次 main、幂等、无模型零写入、类型/I/O/竞争与敏感数据 | T099 → T100/T101 → T107 | US1-7/9/10/14；FR-039；SC-013/018/019/022 |
| 显式/main/旧工作区选择与错误安全 | T102 → T103 → T107 | US1-11/12；FR-040/042；SC-020 |
| 同根真实加载、无需手写 Agent 的 CLI→模型→数据库闭环 | T104/T105 → T106 → T107 | US1-8/13；FR-040；SC-018/021 |
| 运行证据、教学文档与实施后漂移 | T107 → T108 → T109 | SC-014/018–022；不替代原完整故事验收 |

本次新增 16 项全部归属 US1，Phase 10 已完成；随后 Phase 4（T036–T051）、Phase 5（T052–T061）、Phase 6（T062–T069）和 Phase 7（T070–T079）已完成并独立验证，证据见 quickstart 第 10–13 节。当前全表共 109 项（完成 95、待办 14）。US1 共 34 项、US2 16 项、US3 10 项、US4 8 项、US5 10 项、US6 7 项；其余 Setup/Foundational/Polish 共 24 项。真实模型普通文本对话及跨进程历史恢复已验收（见 quickstart 第 13.8 节），真实天气 Demo 未运行；Phase 8 并发隔离专项与 Phase 9 Polish 继续保持待办。


**Phase 4 实施记录（2026-11-16）**：T036–T051 已完成，前置行为红灯与最终 359 tests / 0 failures / 0 errors / 0 skipped、数据库回读及实施后分析见 quickstart 第 10 节。保留 T001–T035、T094–T109 历史记录；未勾选 T052–T093。T051 的真实天气演示按原要求延期到 US3 安全上限与 US5 审计恢复完成后，本次只做本地替身天气闭环。

**Phase 5 实施记录（2026-11-16）**：T052–T061 已完成。实现模型调用上限、官方 Manager 工具限次与 `limit_skipped` 审计、共享 deadline/hard stop、HTTP body 消费截止和动态租约；新增 core/tool deadline 与限次测试、真实 Boot Manager/慢工具集成场景、配置断言及治理收尾测试。完整质量门已通过；本机 Docker 未运行，storage 9 项和 boot 6 项 Testcontainers 测试按项目约定跳过，数据库质量门未验证，CI 仍须实际执行。未提交、推送或发布。


**Phase 6 实施记录（2026-11-16）**：T062–T069 已完成。PromptBuilder 按完整轮次同时应用 `max_history_turns` 与 90% floor 的 token 预算，工具请求/结果不拆分；RUNNING 轮次排除且不占已完成历史配额，失败轮次只恢复用户消息和固定安全说明。JPA 消息存储保留工具调用 ID、参数、结果和 metadata；CLI `--new` 使用原子空闲归档并在无活跃会话时直接开始，重启后的新 Spring 上下文沿同一 active 会话恢复历史。新增 core、storage、CLI 和 boot 集成测试；本机 Docker 未运行，PostgreSQL/真实 boot 重启验收按项目约定跳过，详见 quickstart 第 12 节。未提交、推送或发布。


**Phase 7 实施记录（2026-11-16）**：T070–T079 完成。411 tests / 0 failures / 0 errors / 0 skipped，真实 PostgreSQL 故障注入、启动恢复和数据库报告检查通过，见 quickstart 第 13 节。原定 TransactionBoundaryTest/AuditAutoConfigurationTest 的动态边界与装配断言集中在 LlmAuditIntegrationTest、ReActApplicationTest；TurnReaperTest 位于 storage/session/，另有真实启动的 TurnReaperStartupTest。保留所有历史完成记录，不勾选 T080–T093；不自动提交、推送或运行真实模型。


**Phase 7 扩大验证（2026-11-17）**：修复启动回收、普通存储预算及审计往返延迟问题后，427 tests / 0 failures / 0 errors / 0 skipped，数据库报告检查通过。真实模型与数据库完成首次三轮、重启后两轮，累计五条 CONFIRMED 模型审计，历史跨进程恢复；真实天气 Demo 尚未执行。详细证据见 quickstart 第 13.8 节与 docs/testing/phase7-validation-2026-11-17.md。


**Phase 7 实际远程提交超时回归（2026-11-17）**：后续日志仍出现COMMIT及查证读超时，当前修复用默认2秒/可配置1ms～5秒替代250ms上限，保留共享deadline+1秒和未知提交不重放。新增连接设置恢复、迟到配置拒写及提交响应延迟查证证据，见quickstart第13.9节与docs/testing/database-safety-2026-11-17.md；保留全部历史任务记录，本修复不改变其他阶段任务状态。
