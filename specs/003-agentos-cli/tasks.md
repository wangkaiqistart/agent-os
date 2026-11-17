---
description: "Actionable implementation tasks for AgentOS CLI entry and session behavior"
---

# Tasks: AgentOS CLI 入口与会话

**Input**: Design documents from /specs/003-agentos-cli/

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 本规格包含可执行验收场景；测试任务按测试先行原则列出，先观察失败再实现。

**Organization**: 任务按用户故事分组；每个故事完成后都应能独立运行其验收矩阵。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 准备 CLI 行为测试、配置投影夹具和可重复验收的执行边界。

- [X] T001 [P] 为 CLI 功能测试建立临时工作区、stdout/stderr 捕获、可关闭运行上下文和可控 AgentService 共享夹具，文件：AgentOS-cli/src/test/java/com/agentos/cli/support/CliTestSupport.java
- [X] T002 [P] 为 Provider 名称投影建立只含非敏感 key 的 YAML 夹具，至少包含 primary 和一个自定义名称，并放入带敏感值占位符的配置样本，文件：AgentOS-cli/src/test/resources/application-provider-names.yaml
- [X] T003 [P] 将命令矩阵、固定退出码、20 次统计口径和 PostgreSQL/模型替身前置条件写入执行指南，文件：specs/003-agentos-cli/quickstart.md

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 建立所有用户故事共用的 OS 身份、Provider 名称来源和依赖注入边界。

**⚠️ CRITICAL**: 本阶段完成前不得开始用户故事实现。

- [X] T004 定义可注入的 CLI 使用者身份端口和默认 OS 账号实现，文件：AgentOS-cli/src/main/java/com/agentos/cli/CliIdentity.java、AgentOS-cli/src/main/java/com/agentos/cli/SystemCliIdentity.java
- [X] T005 定义只暴露 Provider 名称集合的校验端口，文件：AgentOS-cli/src/main/java/com/agentos/cli/ProviderNameCatalog.java
- [X] T006 实现全局配置路径解析和配置投影加载器：显式 --config 路径最高优先级且失败不回退；未指定时依次查找当前目录 application.yaml、config/application.yaml、$XDG_CONFIG_HOME/agentos/application.yaml、~/.config/agentos/application.yaml、classpath application.yaml；只提取最终 agentos.providers map 的 key，不绑定或保存 type、URL、凭据、模型值，不读取 .env，文件：AgentOS-cli/src/main/java/com/agentos/cli/AgentOSCommand.java、AgentOS-cli/src/main/java/com/agentos/cli/ProviderNameProjectionLoader.java
- [X] T007 实现 LocalProviderNameCatalog，以 T006 的 key 投影为唯一生产来源；名称目录缺失、格式错误或不可读时返回可分类配置错误，且不启动 Spring、访问数据库或发起网络请求，文件：AgentOS-cli/src/main/java/com/agentos/cli/LocalProviderNameCatalog.java
- [X] T008 [P] 先写 Provider 来源和安全边界测试：验证 --config 优先级、默认搜索顺序、显式路径失败不回退、能读到 primary/自定义 key、忽略敏感值、拒绝错误 YAML/缺失目录、零 Spring/网络调用，文件：AgentOS-cli/src/test/java/com/agentos/cli/ProviderNameProjectionLoaderTest.java、AgentOS-cli/src/test/java/com/agentos/cli/LocalProviderNameCatalogTest.java
- [X] T009 接入全局 --config、身份、Provider 名称目录和现有 ContextLauncher 到命令树构造，并让 boot/独立 CLI 使用同一配置路径和 Provider 名称来源，文件：AgentOS-cli/src/main/java/com/agentos/cli/AgentOSCli.java、AgentOS-cli/src/main/java/com/agentos/cli/AgentOSCommand.java、AgentOS-cli/src/main/java/com/agentos/cli/InitCommand.java、AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java、AgentOS-boot/src/main/java/com/agentos/boot/AgentOSApplication.java
- [X] T010 [P] 为身份、Provider 名称端口和启动器补充隔离测试替身，文件：AgentOS-cli/src/test/java/com/agentos/cli/support/FakeCliIdentity.java、AgentOS-cli/src/test/java/com/agentos/cli/support/FakeProviderNameCatalog.java、AgentOS-cli/src/test/java/com/agentos/cli/support/FakeContextLauncher.java

**Checkpoint**: 身份与 Provider 名称校验可在不启动完整应用的情况下独立测试，且生产来源已指向同一 ConfigData 的非敏感 agentos.providers key 投影。

## Phase 3: User Story 1 - 初始化可用的工作区 (Priority: P1) 🎯 MVP

**Goal**: 使用者显式提供模型后，离线幂等地生成可加载的默认 main Agent；未提供模型或 Provider 无效时零写入失败。

**Independent Test**: 在临时空目录运行 init --model local-test-model，检查目录、main/AGENT.md、provider.name: primary 和 tools: []；重复运行验证字节不变；缺失模型、未知 Provider 或名称目录不可用均返回 2 且目标目录没有不完整文件。

### Tests for User Story 1

- [X] T011 [P] [US1] 先写模型输入测试：只接受显式 --model，缺失/空白/占位符返回配置错误，绝不访问环境变量或执行目录 .env，文件：AgentOS-cli/src/test/java/com/agentos/cli/InitModelResolverTest.java
- [X] T012 [P] [US1] 先写初始化行为测试：新建默认 Agent 写入 provider.name: primary；显式已配置 Provider 可写入；未知 Provider 和名称目录不可用在任何目录创建前失败，文件：AgentOS-cli/src/test/java/com/agentos/cli/WorkspaceInitializerTest.java
- [X] T013 [P] [US1] 先写入口退出码测试：缺失模型、未知 Provider 和未知选项返回 2，路径/I/O 错误返回 1，已有合法 main 不覆盖，初始化不调用 ContextLauncher，文件：AgentOS-cli/src/test/java/com/agentos/cli/InitCommandBehaviorTest.java

### Implementation for User Story 1

- [X] T014 [US1] 修改 InitModelResolver 仅接受显式 --model，删除环境变量和执行目录 .env 回退，并保留安全输入校验，文件：AgentOS-cli/src/main/java/com/agentos/cli/InitModelResolver.java
- [X] T015 [US1] 修改 WorkspaceInitializer 在所有目录/文件写入前完成模型、Provider、路径类型和已有 main 预检；新 main 明确写入 provider.name: primary、具体模型和 tools: []，文件：AgentOS-cli/src/main/java/com/agentos/cli/WorkspaceInitializer.java
- [X] T016 [US1] 修改 InitCommand 注入 ProviderNameCatalog，将参数/配置错误映射为 2、路径/I/O 错误映射为 1，输出只包含新建/跳过路径和安全摘要，文件：AgentOS-cli/src/main/java/com/agentos/cli/InitCommand.java
- [X] T017 [US1] 更新初始化契约，写明 Provider 名称投影来源、primary 默认值、未知 Provider 零写入和离线边界，文件：specs/003-agentos-cli/contracts/workspace-and-profile.md、specs/003-agentos-cli/contracts/cli.md
- [X] T018 [US1] 离线初始化阶段 Demo：使用 --config 指定共享非敏感配置，临时目录运行 init、验证 primary/tools/[]、重复运行字节不变，并记录退出码与零写入失败，文件：specs/003-agentos-cli/quickstart.md、AgentOS-cli/src/test/java/com/agentos/cli/InitCommandBehaviorTest.java

**Checkpoint**: US1 可独立通过离线文件测试和命令烟测，不依赖数据库、Provider 网络或完整 Spring 上下文。

## Phase 4: User Story 2 - 通过单条消息获得回答 (Priority: P1)

**Goal**: 使用者选择工作区和 Agent，提交一条消息后得到回答、正确输出流和稳定退出码。

**Independent Test**: 使用本地模型替身执行 chat --message；有效请求返回 0，启动失败返回 1，无效 Agent/空消息返回 2，轮次失败或持久化未确认返回 3，且无效选择不会启动上下文。

### Tests for User Story 2

- [X] T019 [P] [US2] 先写轻命令测试：--help、--version 返回 0 且不启动 launcher，未知选项和显式配置路径错误返回 2，init 不调用 launcher，文件：AgentOS-cli/src/test/java/com/agentos/cli/AgentOSCliTest.java
- [X] T020 [P] [US2] 先写单条消息退出码测试：成功 0、运行时启动失败 1、Agent/空消息用法错误 2、轮次失败/持久化未确认 3，验证回答只写 stdout、诊断只写 stderr，文件：AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java
- [X] T021 [P] [US2] 先写 Agent 选择和工作区一致性测试：显式 --profile 优先、默认 main、唯一旧工作区 Agent 兼容、损坏选中 Agent 不回退、启动器收到同一规范化绝对路径，文件：AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java、AgentOS-boot/src/test/java/com/agentos/boot/SpringContextLauncherTest.java
- [X] T022 [P] [US2] 先写真实 CLI 装配测试：指定 workspace 的 Agent/Bootstrap 被加载，回答落 stdout，数据库产生一条 session/turn/message 审计链且不读取冲突默认目录，文件：AgentOS-boot/src/test/java/com/agentos/boot/DefaultAgentCliIntegrationTest.java

### Implementation for User Story 2

- [X] T023 [US2] 复核并实现 ChatCommand 的 workspace 规范化、显式/默认 Agent 选择和启动前 AgentLoader 校验，文件：AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java
- [X] T024 [US2] 复核 ChatCommand.process 的 ProcessRequest(channel="cli", userId=OS账号, agentName, message, trigger="cli") 映射、stdout/stderr 分流和 0/1/2/3 退出码，文件：AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java
- [X] T025 [US2] 验证 SpringContextLauncher 只启动 WebApplicationType.NONE、传递同一 workspace root 和 --config 对应的 spring.config.additional-location，并在命令结束时关闭上下文，文件：AgentOS-cli/src/main/java/com/agentos/cli/ContextLauncher.java、AgentOS-boot/src/main/java/com/agentos/boot/SpringContextLauncher.java、AgentOS-boot/src/main/java/com/agentos/boot/AgentOSApplication.java
- [X] T026 [US2] 单条消息阶段 Demo：使用本地模型替身验证回答 stdout、诊断 stderr、退出码 0/1/2/3 和同一 workspace root，文件：specs/003-agentos-cli/quickstart.md、AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java

**Checkpoint**: US2 可独立执行单条消息，且 CLI 不自行调用模型、工具、数据库或历史拼接。

## Phase 5: User Story 3 - 进行可恢复的交互式对话 (Priority: P1)

**Goal**: 交互式聊天复用同一运行时上下文和活跃会话，支持空行、/exit、EOF、跨进程历史恢复和身份隔离。

**Independent Test**: 注入多行输入完成两轮，验证空行不建轮次、失败后仍可输入、/exit/EOF 最终返回 0；重启后同一 OS 账号和 Agent 能恢复历史，更换账号或 Agent 不串线。

### Tests for User Story 3

- [X] T027 [P] [US3] 先写交互输入测试：空行只写 stderr、/exit/EOF 关闭上下文、单轮失败后继续读入且正常结束返回 0，文件：AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java
- [X] T028 [P] [US3] 先写身份与会话键测试：使用注入 OS 账号构造 (cli, userId, profileName)，账号或 Agent 变化时隔离历史，并证明不存在 --user 覆盖入口，文件：AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java
- [X] T029 [P] [US3] 先写跨进程恢复集成测试：重建 Spring 上下文后同一账号/Agent 可回读历史，至少执行 20 次重启恢复并记录 20/20 结果，文件：AgentOS-boot/src/test/java/com/agentos/boot/DefaultAgentCliIntegrationTest.java
- [X] T030 [US3] 在既有 channel-cli 测试中保留空行、EOF、/exit 适配回归断言；生产输入适配器不新增或改动，文件：AgentOS-channel-cli/src/test/java/com/agentos/channel/cli/CliChannelTest.java

### Implementation for User Story 3

- [X] T031 [US3] 修改 ChatCommand 使用注入的 CliIdentity，交互循环复用单个 RunningContext，忽略可恢复 process 返回值但保留错误输出，正常 /exit/EOF 返回 0，文件：AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java
- [X] T032 [US3] 复核 CLI 到 core/storage 的会话恢复调用路径，确保 CLI 只调用 AgentService.process，历史、轮次和审计仍由 PostgreSQL 现有存储恢复，文件：AgentOS-core/src/main/java/com/agentos/core/turn/DefaultAgentService.java、AgentOS-storage/src/main/java/com/agentos/storage/session/、AgentOS-boot/src/test/java/com/agentos/boot/DefaultAgentCliIntegrationTest.java
- [X] T033 [US3] 交互恢复阶段 Demo：注入多行 stdin 验证空行、失败后继续、/exit/EOF 返回 0，并重启验证同一身份历史恢复，文件：specs/003-agentos-cli/quickstart.md、AgentOS-boot/src/test/java/com/agentos/boot/DefaultAgentCliIntegrationTest.java

**Checkpoint**: US3 可独立完成多轮交互和重启恢复，且同一命令进程不会为每条消息重复启动 Spring。

## Phase 6: User Story 4 - 安全地开始新会话 (Priority: P2)

**Goal**: --new 只归档空闲会话并保留旧历史；忙会话拒绝归档且不产生破坏性写入。

**Independent Test**: 对空闲会话执行 chat --new 后检查旧会话 archived、新会话 active、旧消息/审计仍在；对运行中会话执行并发 --new 返回 3 且旧状态不变。

### Tests for User Story 4

- [X] T034 [P] [US4] 先写 --new 行为测试：无活跃会话直接开始、空闲会话归档成功、旧记录保留、忙会话返回 3 且不调用模型，文件：AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java
- [X] T035 [P] [US4] 先写数据库并发测试：有效租约下 --new 与正在运行轮次互斥，至少重复 20 次，20/20 拒绝且零新增 turn/message/audit，文件：AgentOS-boot/src/test/java/com/agentos/boot/SessionConcurrencyIntegrationTest.java、AgentOS-storage/src/test/java/com/agentos/storage/session/JpaStorageContractTest.java

### Implementation for User Story 4

- [X] T036 [US4] 复核 ChatCommand.archiveCurrentSession 使用 OS 账号和选中 Agent 构造 SessionKey，并将 SESSION_BUSY 安全映射为退出码 3，文件：AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java
- [X] T037 [US4] 复核 SessionManager.archiveIfIdle 的条件归档边界，确保只更新会话状态/时间/租约，不删除或改写旧轮次、消息和审计，文件：AgentOS-core/src/main/java/com/agentos/core/session/SessionManager.java、AgentOS-storage/src/main/java/com/agentos/storage/session/
- [X] T038 [US4] 新会话阶段 Demo：分别验证空闲 --new 归档保留历史，以及忙 --new 返回 3 且零新增记录，文件：specs/003-agentos-cli/quickstart.md、AgentOS-boot/src/test/java/com/agentos/boot/SessionConcurrencyIntegrationTest.java

**Checkpoint**: US4 可独立证明新会话不会删除历史或打断有效轮次。

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 补齐 C1/C2/C3 验收统计、同步旧契约并完成质量门。

- [X] T039 [P] 同步旧 ReAct 规格中的 CLI 契约，移除与本功能冲突的环境变量/.env 模型回退和 Provider 省略规则，文件：specs/002-react-loop/contracts/workspace-and-cli.md、specs/002-react-loop/contracts/configuration.md
- [X] T040 [P] 为 C1 建立固定命令矩阵验收记录：help/version=0 且 launcher 调用次数为 0，unknown option 和显式配置路径错误=2，init=0 且 launcher 调用次数为 0，chat startup failure=1，文件：AgentOS-cli/src/test/java/com/agentos/cli/AgentOSCliTest.java、specs/003-agentos-cli/quickstart.md
- [X] T041 [P] 为 C2 建立可重复的 20 次初始化统计脚本/测试：预热 3 次，独立临时目录执行 20 次，记录 p50/p95/最大耗时，断言至少 19 次小于 2 秒；重复初始化 20 次并断言覆盖次数为 0；再执行 20 次未知 Provider，断言每次退出 2 且零写入；脚本输出 JSON/Markdown 统计，文件：scripts/cli_init_acceptance.py、specs/003-agentos-cli/quickstart.md
- [X] T042 [P] 为 C3 建立可重复的会话验收统计：20 次关闭/重启恢复、至少 20 对账号或 Agent 隔离、空闲 --new 20/20 成功、忙 --new 20/20 拒绝且零新增 turn/message/audit；脚本输出 JSON/Markdown，并在 Docker 不可用时输出 NOT_RUN 和原因，文件：scripts/cli_session_acceptance.py、specs/003-agentos-cli/quickstart.md
- [X] T043 运行受影响的 CLI、channel-cli 回归和 boot 集成测试，确认未修改 AgentOS-channel-cli/src/main/java/com/agentos/channel/cli/CliChannel.java，文件：AgentOS-cli/src/test/、AgentOS-channel-cli/src/test/、AgentOS-boot/src/test/java/com/agentos/boot/
- [X] T044 运行项目质量门 mvn -B -ntp verify，确认未引入 H2/SQLite/厂商 SDK、ArchUnit 约束和密钥泄露问题，文件：pom.xml、相关模块源码和测试报告
- [X] T045 运行 python3 scripts/check_db_test_reports.py，核对 PostgreSQL/pgvector 测试确实执行且未被静默跳过，文件：scripts/check_db_test_reports.py、AgentOS-boot/target/surefire-reports/
- [X] T046 按 quickstart.md 完成离线初始化、单条消息、交互恢复、身份隔离和 --new 验收，填入 C1/C2/C3 的实际统计与未运行原因，文件：specs/003-agentos-cli/quickstart.md、specs/003-agentos-cli/spec.md

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 Setup: 无代码依赖，可先建立测试夹具和验证输入。
- Phase 2 Foundational: 依赖 Setup；身份、Provider 名称来源和测试端口阻塞所有用户故事。
- Phase 3 US1: 依赖 Foundational；可先独立完成并作为 MVP。
- Phase 4 US2: 依赖 Foundational；建议在 US1 的工作区 fixture 可用后实施。
- Phase 5 US3: 依赖 US2 的单条消息与 ChatCommand 运行时路径。
- Phase 6 US4: 依赖 US2 的 ChatCommand 和 US3 的会话身份注入。
- Phase 7 Polish: 依赖所有目标用户故事完成；C1/C2/C3 统计在对应测试稳定后执行。

### User Story Dependencies

- US1 (P1): 仅依赖 Foundational；独立交付离线初始化 MVP。
- US2 (P1): 依赖 Foundational；使用预置合法工作区 fixture，可不依赖 US1 的实现完成。
- US3 (P1): 依赖 US2 的聊天提交路径；增加交互、OS 账号和跨进程恢复。
- US4 (P2): 依赖 US2 的聊天上下文与 US3 的身份键；增加归档控制。

### Parallel Opportunities

- Phase 1 的 T001–T003 可并行。
- Phase 2 的 T004–T008 可并行；T009 等待端口、投影和测试替身完成。
- US1 的 T011–T013 可并行；US2 的 T019–T022 可并行；US3 的 T027–T030 可并行；US4 的 T034–T035 可并行。
- US1 与 US2 在 Foundational 完成后可由不同人员并行，但共享 AgentOSCli.java 和 ChatCommand.java 时按文件串行。
- T039–T042 可并行；T043–T046 需在实现完成后按依赖串行执行。

## Parallel Example: User Story 1

    Task T011: InitModelResolverTest.java —— 显式 --model、无 env/.env 回退、输入校验
    Task T012: WorkspaceInitializerTest.java —— primary、Provider 零写入、幂等与文件安全
    Task T013: InitCommandBehaviorTest.java —— 退出码、离线和不启动 launcher

## Parallel Example: User Story 2

    Task T019: AgentOSCliTest.java —— help/version/unknown option/init 的 C1 矩阵
    Task T020: ChatCommandTest.java —— 单条消息退出码与输出流
    Task T022: DefaultAgentCliIntegrationTest.java —— 本地模型替身 + PostgreSQL 真实装配

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1 Setup 和 Phase 2 Foundational。
2. 完成 US1 的测试与实现，确保显式 --model、primary、幂等和零写入失败成立。
3. 按 US1 独立验收矩阵执行离线命令和测试后停止，作为可演示 MVP。

### Incremental Delivery

1. 加入 US2 单条消息与 Agent 选择，验证统一运行时和退出码。
2. 加入 US3 交互输入、OS 账号会话和跨进程历史恢复。
3. 加入 US4 --new 空闲归档与忙会话保护。
4. 执行 C1/C2/C3 统计、文档同步、完整质量门和数据库测试报告检查。

### Notes

- 所有任务均采用 - [X] T### 格式；用户故事阶段任务包含 [US#] 标签。
- [P] 仅用于不同文件且没有未完成依赖的任务。
- 每个故事的测试任务应先执行并观察失败，再实现对应行为。
- AgentOS-channel-cli 在本功能只保留已有回归测试；不修改其生产输入适配器。
- 不新增数据库迁移、不引入 H2/SQLite、不读取或提交真实密钥、不修改 constitution。

## Phase 8: Convergence

- [X] T047 [US3] 补充至少 20 对 OS 账号或 Agent 的真实隔离验收矩阵：使用注入身份/不同 Agent 发送带唯一标记的消息，断言会话历史与 `ProcessRequest` 不串线，并在 `scripts/cli_session_acceptance.py` 与 `specs/003-agentos-cli/quickstart.md` 输出隔离次数和泄漏次数，满足 SC-005（partial）。
- [X] T048 [US4] 增加真实 CLI + PostgreSQL/本地模型替身的 `--new` 集成验收：空闲会话重复 20 次验证旧记录保留且新活跃会话建立，忙会话重复 20 次验证退出码 3 且 turn/message/audit 零新增；更新 `scripts/cli_session_acceptance.py` 读取实际报告并满足 SC-006（partial）。

