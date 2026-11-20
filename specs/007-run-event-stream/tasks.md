# Tasks: 运行事件流

**Input**: `specs/007-run-event-stream/` 下的 plan.md、spec.md、research.md、data-model.md、contracts/、quickstart.md

**Tests**: 需要。quickstart.md §2 指定了测试类名，`mvn -B verify` 是唯一质量门。每个故事先写测试并确认失败，再实现。

**Organization**: 按用户故事分阶段。执行顺序是 US2 → US3 → US1 → US4：三个 P1 故事中 US2 决定终态与错误码，US3 产出 US1 消费的事件，所以先做；US4 为 P2。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可并行（不同文件，且不依赖未完成任务）
- **[Story]**：所属用户故事（US1–US4）
- 路径相对仓库根目录；core 包为 `AgentOS-core/src/main/java/com/agentos/core/`，下文简写为 `core/`；测试同理 `core-test/` = `AgentOS-core/src/test/java/com/agentos/core/`

---

## Phase 1: Setup

**Purpose**: 确认基线，避免把既有失败算到本功能头上

- [X] T001 在 `specs/007-run-event-stream/` 分支上运行 `mvn -B -ntp verify`，把结果（通过 / 失败的模块与测试名）记录到 `specs/007-run-event-stream/implementation-baseline.md`；基线失败时先停下报告，不在本功能里修

---

## Phase 2: Foundational（阻塞所有故事）

**Purpose**: 事件契约类型、发布器、新状态与迁移。完成前不能开始任何故事。

### 测试（先写，确认失败）

- [X] T002 [P] 在 `core-test/turn/TurnEventPublisherTest.java` 编写发布器测试：序号从 1 开始逐条加 1；两个线程交替 `publish` 时序号仍连续且投递顺序与序号一致；`finish` 只生效一次且之后 `publish` 被拒绝；`publish` 传入控制面 payload 被拒绝；消费方抛异常后置失效、预算以 `OUTPUT_FAILED` 取消并抛 `LeaseLostException`；等锁超过 hard stop 时按消费方失效处理
- [X] T003 [P] 在 `core-test/turn/TurnSinkCompatibilityTest.java` 编写兼容层测试：只覆写旧回调的 `TurnSink` 经默认 `onEvent` 收到 `message.delta`→`onDelta`、`tool.started`→`onToolCall(id, name, argumentsSummary)`、`tool.finished`→`onToolResult(id, name, resultSummary)`、`run.completed`→`onComplete`、`run.failed`/`run.cancelled`→`onError`，其他类型被忽略（映射见 `contracts/core-ports.md` §1）
- [X] T004 [P] 在 `AgentOS-storage/src/test/java/com/agentos/storage/session/MigrationTest.java` 增加用例：迁移后 `agent_turns.status` 接受 `CANCELLED`，仍拒绝未列出的值

### 实现

- [X] T005 [P] 新建 `core/turn/TurnFailureCode.java`：枚举值按 `data-model.md` §4 的顺序与拼写，共 19 个（`OUTPUT_TRUNCATED` … `INTERNAL`），类注释写明"只追加、不重命名"
- [X] T006 [P] 新建 `core/turn/TurnEventPayload.java`：sealed interface，内含 `data-model.md` §2 的 15 个 record 与枚举（`ToolStatus`、`ApprovalDecisionKind`、`InputOutcome`、`StepOutcome`），每个 record 提供 `type()` 返回契约类型名（如 `"tool.started"`）；`MessageDelta.text` 构造时拒绝 null 与空串；可空字段按 §2 标注；附静态方法 `ToolStatus.fromAudit(String)` 完成 `success`/`failed`/`timeout`/`sandbox_denied`/`limit_skipped`/`interrupted`/`unconfirmed` 的映射，`ApprovalDecisionKind.from(ApprovalDecision.Outcome)` 完成 `UNSUPPORTED`→`UNATTENDED`、`EOF`→`CANCELLED` 的映射
- [X] T007 新建 `core/turn/TurnEvent.java`：record（`schemaVersion` 固定 `1`、`sessionId`、`turnId`、`seq`、`createdAt`、`payload`），`type()` 由 payload 派生；`sessionId` 只在 payload 为 `RunStarted` 或 `RunFailed(code=START_UNCONFIRMED)` 时允许为空（依赖 T005、T006）
- [X] T008 新建 `core/turn/TurnEventPublisher.java`：接口，只有 `void publish(TurnEventPayload payload)`，注释说明工具模块只能调用它、控制面事件只能由 `DefaultAgentService` 经发布器实现的 `finish` 发出（依赖 T006）
- [X] T009 修改 `core/turn/TurnSink.java`：新增 `default void onEvent(TurnEvent event)`，默认实现按 T003 的映射转调旧回调（`run.completed`/`run.failed`/`run.cancelled` 用事件 data 重建 `TurnResult`）；保留 `streaming()` 和 5 个旧回调；类注释改为"核心只调用 onEvent"（依赖 T007）
- [X] T010 修改 `core/turn/TurnExecutionBudget.java`：新增嵌套枚举 `CancelReason { USER, SHUTDOWN, OUTPUT_FAILED, INTERNAL }`、`cancel(CancelReason)`、`cancelReason()`；第一次的原因生效；`USER` 把 `cancellationStop` 收紧到"现在 + 2 秒"，其他原因收紧到"现在 + 1 秒"；原 `cancel()` 等价于 `cancel(INTERNAL)`；2 秒写成私有常量，不开放配置
- [X] T011 修改 `core/turn/TurnStatus.java` 新增 `CANCELLED`（注释：用户主动取消，与进程关闭导致的 `INTERRUPTED` 区分）；修改 `core/turn/TurnResult.java` 新增可空字段 `failureCode`（`TurnFailureCode`），保留原 5 参数构造器（`failureCode` 取空）
- [X] T012 修改 `core/turn/ManagedTurnSink.java` 实现 `TurnEventPublisher`：用 `ReentrantLock`（不用 `synchronized`）保护"分配序号 + 调用 `delegate.onEvent`"；`tryLock` 等待上限取 `budget.remainingHardStopNanos()`；消费方异常或等锁超时 → 置失效、`budget.cancel(OUTPUT_FAILED)`、抛 `LeaseLostException`；`finish(TurnResult)` 把结果转成 `RunCompleted`/`RunFailed`/`RunCancelled` 并沿用现有"有界等待、只尝试一次、失败只记日志"规则投递；删除 `onDelta`/`onToolCall`/`onToolResult` 的直接转发，改为对外只暴露 `publish` 与 `finish`；构造参数改为 `(TurnSink delegate, TurnExecutionBudget budget, UUID turnId)`，新增 `bindSession(UUID sessionId)` 在 `startTurn` 成功后调用，绑定前发布的事件 `sessionId` 为空（只用于起步未确认，research R12）；`streaming()` 继续委托给 delegate；`publish` 只检查发布器是否已失效、是否已发过终态，不检查整轮截止时间（超时场景下 `tool.finished` / `step.finished` 仍能送达），投递时长只受 hard stop 约束，删除现有 `emit` 里的 `budget.expired()` 检查（依赖 T008–T011，使 T002 通过）
- [X] T013 修改 `core/turn/ToolContextKeys.java` 新增 `TURN_EVENTS = "agentos.turnEvents"`，注释说明值为 `TurnEventPublisher`、不会经 MCP 外发
- [X] T014 [P] 先修改 `docs/TechnicalSolution.md`：第 9 章 `agent_turns.status` 取值加入 `CANCELLED`，`error_message` 说明"截断 / 过滤失败以错误码开头"，7.4 节轮次状态列表加入 `CANCELLED`；再新建 `AgentOS-storage/src/main/resources/db/migration/V3__turn_status_cancelled.sql`：照 V2 的写法 `ALTER TABLE agent_turns DROP CONSTRAINT agent_turns_status_check;` 再 `ADD CONSTRAINT agent_turns_status_check CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','TIMED_OUT','MAX_ITERATIONS','INTERRUPTED','LIMIT_EXCEEDED','CANCELLED'));`（使 T004 通过）
- [X] T015 修改现有 `budget.cancel()` 调用点的原因：`core/turn/DefaultAgentService.java` 的 `close()` 用 `SHUTDOWN`，起步未确认与 `finally` 收尾用 `INTERNAL`；`core/turn/TurnRunner.java` 两处用 `INTERNAL`；发布器内的输出端失效已在 T012 用 `OUTPUT_FAILED`
- [X] T016 运行 `mvn -B -ntp -pl AgentOS-core,AgentOS-storage -am test`，确认 T002–T004 通过、既有测试不回归

**Checkpoint**: 事件类型、发布器、`CANCELLED` 状态与迁移就绪

---

## Phase 3: User Story 2 - 截断或被过滤的回答不算成功 (Priority: P1)

**Goal**: 结束原因为 `length` / `content_filter` 时本轮以对应错误码失败，不执行该响应中的工具，截断文字不进入后续上下文

**Independent Test**: 桩模型分别返回 `length`、`LENGTH`、`content_filter`，同步与流式各一次，并含"截断 + 工具调用"组合（quickstart §3 US2）

### 测试（先写，确认失败）

- [X] T017 [P] [US2] 在 `core-test/turn/ReActLoopFinishReasonTest.java` 用 `core-test/support/ScriptedProviderService.java` 编写：`length`（含大小写变体）与 `content_filter` 在同步、流式下都返回 `TurnStatus.FAILED` 且 `failureCode` 分别为 `OUTPUT_TRUNCATED`、`CONTENT_FILTERED`；"截断 + 工具调用"时工具执行次数为 0；该轮不向 `MessageStore` 追加助手消息；`ProviderService` 调用次数为 1（不重试）；`stop` 与 `tool_calls` 行为不变。另在 `AgentOS-provider/src/test/java/com/agentos/provider/chat/RetryPolicyTest.java` 增加用例：`StubModelServer` 返回 `finish_reason=length` 与 `content_filter` 时，`DefaultProviderService` 只发 1 次请求、只写 1 条调用记录且 `success=true`
- [X] T018 [P] [US2] 在 `core-test/turn/PromptHistoryTest.java` 增加用例：上一轮 `FAILED` 且 `errorMessage` 以 `OUTPUT_TRUNCATED` 开头时，prompt 中该轮只有用户消息与"上一轮回答因长度上限被截断"；`CONTENT_FILTERED` 对应"上一轮回答被内容策略过滤"；`CANCELLED` 对应"本轮已被用户取消"；其他失败文案不变

### 实现

- [X] T019 [US2] 修改 `core/turn/ReActLoop.java`：拿到 `generation` 后、判断工具调用之前读取 `generation.getMetadata().getFinishReason()`，忽略大小写匹配 `length` → `Result(FAILED, "模型输出达到上限被截断，可调大 max_output_tokens 或拆分任务", code=OUTPUT_TRUNCATED)`，`content_filter` → `Result(FAILED, "回答被内容安全策略过滤", code=CONTENT_FILTERED)`；两种情况都不追加助手消息、不执行工具；空回答分支补 `EMPTY_RESPONSE`、迭代上限分支补 `MAX_ITERATIONS`；`ReActLoop.Result` 增加可空字段 `failureCode`（使 T017 通过）
- [X] T020 [US2] 修改 `core/turn/DefaultAgentService.java` 的正常收尾分支：`outcome.failureCode()` 为 `OUTPUT_TRUNCATED` 或 `CONTENT_FILTERED` 时，`finishAndRelease` 的 `errorMessage` 写成 `<错误码>：<固定文案>`（例如 `OUTPUT_TRUNCATED：模型输出达到上限被截断`）；其他错误码不改变现有 `errorMessage`；所有非空 `failureCode` 都带进 `TurnResult`
- [X] T021 [US2] 修改 `core/turn/PromptBuilder.java` 的 `safeReason`：签名改为接收 `HistoryTurn`；`CANCELLED` → "本轮已被用户取消"；`FAILED` 时按 `errorMessage` 前缀匹配 `OUTPUT_TRUNCATED` / `CONTENT_FILTERED` 给出 T018 的两条文案；不把 `errorMessage` 原文放进 prompt（使 T018 通过）
- [X] T022 [US2] 运行 `mvn -B -ntp -pl AgentOS-core,AgentOS-provider -am -Dtest='ReActLoopFinishReasonTest,PromptHistoryTest,ReActLoopTest,ReActLoopStreamingTest,RetryPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test`；把截断、过滤两个样本的轮次状态、错误码、工具执行次数、下一轮 prompt 中的说明文字整理成演示记录 `specs/007-run-event-stream/us2-demo.md`（来自测试输出，不依赖真实模型）

**Checkpoint**: 截断 / 过滤被正确识别；错误码可用于终态事件

---

## Phase 4: User Story 3 - 所有入口共用一份可信的事件契约 (Priority: P1)

**Goal**: 每轮产生符合 `contracts/run-events.md` §2 顺序语法的完整事件序列；工具与交互事件按单个工具发出；内容已脱敏

**Independent Test**: 记录型 `TurnSink` 在 10 类场景下校验事件序列（quickstart §3 US3）

### 测试（先写，确认失败）

- [X] T023 [P] [US3] 在 `core-test/support/RecordingTurnSink.java` 增加 `onEvent` 记录与断言辅助方法：`assertWellFormed()` 按 `contracts/run-events.md` §2 校验（首条 `run.started`、末条唯一终态、`seq` 从 1 连续、`messageId`/`toolCallId`/`requestId` 成对、`tool.*` 位于 `step.finished` 之后）；另提供 `assertBodyWellFormed()`：只校验步骤、消息、工具、交互事件的成对与顺序以及 `seq` 连续，不要求首条为 `run.started`、末条为终态，供直接测试 `ReActLoop` 时使用
- [X] T024 [US3] 在 `core-test/turn/ReActLoopEventsTest.java`（使用 T023 的 `assertBodyWellFormed()`）编写：纯文字回答、"文字 + 两个工具 + 最终回答"、只有工具调用无文字、流式首段文字前 Provider 重试成功（只有一份消息事件）、Provider 失败（`step.finished.outcome=FAILED` 且带 `failureKind`）五种情况的事件序列；同步调用产生 `message.started` + 一条全文 `message.delta` + `message.ended`；空片段不产生 `message.delta`；另在 `core-test/turn/AgentServiceTest.java` 新增一条用例，经 `DefaultAgentService` 跑"文字 + 工具 + 最终回答"，用 `assertWellFormed()` 校验含 `run.started` 与终态的完整序列
- [X] T025 [P] [US3] 在 `AgentOS-tool/src/test/java/com/agentos/tool/govern/GovernedToolEventsTest.java` 编写：成功、业务失败、超时、沙箱拒绝、次数超限各产生一对 `tool.started` / `tool.finished`，`status` 与 `tool_invocations.status` 一一对应；`argumentsSummary` / `resultSummary` 与审计记录同值且已脱敏（注入 `sk-` 开头的假密钥，事件中出现 0 次）；`recorder.begin` 失败时不发 `tool.started`
- [X] T026 [P] [US3] 在 `AgentOS-tool/src/test/java/com/agentos/tool/sandbox/ToolApprovalGateTest.java` 增加用例：需要确认时 `approval.requested` 在调用 `InteractionPort.approve` 之前发出，带 `requestId`、`toolCallId`、`actionType`、`title`、`lines`、`sessionGrantAllowed`；`approval.resolved.decision` 覆盖 `ALLOW_ONCE`、`ALLOW_SESSION`、`DENY`、`TIMED_OUT`、`UNATTENDED`、`CANCELLED`；拒绝原因正文不进事件；本会话已授权时不发确认事件；注入 300ms 的确认等待后，`approval.resolved.createdAt - approval.requested.createdAt` 不小于 300ms（等待期间整轮计时暂停不影响事件时间戳）
- [X] T027 [P] [US3] 在 `AgentOS-tool/src/test/java/com/agentos/tool/interaction/AskUserToolsEventsTest.java` 编写：`input.requested` 带脱敏后的 `question`；`input.resolved.outcome` 覆盖 `ANSWERED`、`TIMED_OUT`、`UNATTENDED`、`CANCELLED`、`TOO_LONG`；答案正文不进事件
- [X] T028 [US3] 新建 `AgentOS-boot/src/test/java/com/agentos/boot/support/RunEventAssertions.java`（boot 测试拿不到 core 的测试类，按 T023 的同一套规则另写一份校验），并在 `AgentOS-boot/src/test/java/com/agentos/boot/RunEventContractIntegrationTest.java`（复用 `BootDatabaseTestEnvironment` 与 `ToolCapabilitiesFixture` 的脚本化模型）跑 10 类场景：成功、工具失败、确认被拒、无人值守拒绝、超时、迭代上限、工具次数超限、截断、过滤、取消（取消场景在 Phase 6 完成后启用）；每类调用 `RunEventAssertions.assertWellFormed(...)`；终态与 `agent_turns.status` 一致；模型请求的每个工具调用（含被跳过、被拒绝的）恰好一对事件，且对数与 `tool_invocations` 行数相等；全部事件序列化后原始假密钥出现 0 次（SC-002、SC-004、SC-007）

### 实现

- [X] T029 [US3] 修改 `core/turn/ReActLoop.java`：`run(...)` 入口取得发布器（`sink instanceof TurnEventPublisher p ? p : new ManagedTurnSink(sink, budget, turn.turnId())` 并 `bindSession`）；每次迭代前发 `StepStarted(iteration)`；流式调用时用一个按迭代新建的消息跟踪器包装文字回调：首段非空文字发 `MessageStarted(messageId=UUID, iteration, role)`，之后每段发 `MessageDelta`，模型返回后有文字则发 `MessageEnded`；同步调用拿到全文后依次发三条；模型返回后发 `StepFinished`（字段取 `ProviderResult.calls()` 最后一条 `LlmCallRecord`）；捕获 `ProviderCallException` 时发 `StepFinished(outcome=FAILED, failureKind)` 后重新抛出；删除整批工具执行前后对 `sink.onToolCall` / `sink.onToolResult` 的调用；把发布器传给 `ToolExecutor`（使 T024 通过）
- [X] T030 [US3] 修改 `core/turn/ToolExecutor.java`：`execute(...)` 增加发布器参数并放入 `ToolContextKeys.TURN_EVENTS`；`limit_skipped` 补发的每个调用发一对 `ToolStarted` / `ToolFinished(status=SKIPPED)`；`rejectBatch` 的每个调用发一对 `ToolStarted` / `ToolFinished(status=FAILED)`；摘要用已有的 `redactor.summarize(..., 10000)`
- [X] T031 [US3] 修改 `AgentOS-tool/src/main/java/com/agentos/tool/govern/GovernedToolCallback.java`：从 `ToolContext` 取 `TURN_EVENTS`（缺失时不发事件，兼容直接调用的旧测试）；`recorder.begin` 成功后、执行前发 `ToolStarted(toolCallId, toolName, source, iteration, argumentsSummary=safeInput)`；`recorder.complete(end)` 成功后发 `ToolFinished(status=ToolStatus.fromAudit(end.status()), durationMs, truncated, resultSummary=result, possiblyExecuted)`，`possiblyExecuted` 取"状态为 `unconfirmed`，或失败且 `scope.executionState()==POSSIBLY_STARTED`，或成功"；发布抛出的 `LeaseLostException` 原样上抛（使 T025 通过）
- [X] T032 [US3] 修改 `AgentOS-tool/src/main/java/com/agentos/tool/sandbox/ToolApprovalGate.java`：在 `capabilities.interaction().approve(...)` 前发 `ApprovalRequested`（`requestId` 复用即将传入的 `ApprovalRequest.requestId()`，`lines` 取 `subject.lines()`）；用 `try/finally` 在返回或异常后发 `ApprovalResolved`，异常路径决定记 `CANCELLED`；会话已授权直接放行的分支不发事件（使 T026 通过）
- [X] T033 [US3] 修改 `AgentOS-tool/src/main/java/com/agentos/tool/interaction/AskUserTools.java`：在 `capabilities.interaction().ask(...)` 前后按 T032 的方式发 `InputRequested`（`question` 经 `Redactor.redact`）/ `InputResolved`（使 T027 通过）
- [X] T034 [US3] 修改 `core/turn/DefaultAgentService.java`：创建发布器时传入 `turnId`；`startTurn` 成功后 `bindSession` 并在 `executeStarted` 的 `try` 块内发 `RunStarted(agent, trigger)`；起步未确认分支先发 `RunStarted`（`sessionId` 为空）再 `finish`；各 `catch` 分支为 `TurnResult` 填 `failureCode`（超时 `TIMED_OUT`、租约丢失 `LEASE_LOST`、输出端失效 `OUTPUT_FAILED`、关闭 `SHUTDOWN`、工具清理 `TOOL_CLEANUP_UNCONFIRMED`、审计 `AUDIT_UNCONFIRMED`、`ProviderCallException` 按最后一次尝试的 `FailureKind` 映射为 `PROVIDER_*`、`LIMIT_EXCEEDED` 状态为 `TOOL_LIMIT_EXCEEDED`、其他 `INTERNAL`），映射表见 research R11
- [X] T035 [US3] 运行 `mvn -B -ntp -pl AgentOS-core,AgentOS-tool -am test` 与 `mvn -B -ntp -pl AgentOS-boot -am -Dtest='RunEventContractIntegrationTest,ToolApprovalIntegrationTest,AskUserAcceptanceIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`；确认 `AgentLoopDebugController` 相关测试经兼容层不回归；`RunEventContractIntegrationTest` 把 10 类场景的事件序列（类型、`seq`、关键字段，已脱敏）写到 `target/007-demo/run-events/*.txt`，摘录"带确认的工具调用"与"截断"两个场景到演示记录 `specs/007-run-event-stream/us3-demo.md`

**Checkpoint**: 事件契约完整，可被任意消费方使用

---

## Phase 5: User Story 1 - 在 CLI 中实时看到执行过程 (Priority: P1) 🎯 MVP 可演示点

**Goal**: 交互式 chat 边生成边显示文字，每个工具显示开始 / 结束行，终态一行；`--message` 输出不变

**Independent Test**: 渲染器喂入事件序列断言输出；chat 用桩模型分段返回并注入延迟（quickstart §3 US1）

### 测试（先写，确认失败）

- [ ] T036 [P] [US1] 在 `AgentOS-channel-cli/src/test/java/com/agentos/channel/cli/TerminalTurnRendererTest.java` 按 `contracts/cli-output.md` §1 编写：文字增量原样输出不换行；`tool.started` 前补换行；工具行格式、中文状态、"（可能已执行）"后缀；参数 / 结果摘要去换行并限长 80 字符、超出以 `…` 结尾；`approval.*` / `input.*` 不输出；成功且调用过工具时打印终态行、未调用工具时不打印；失败且已显示文字时先打印"（回答未完成）"；`run.cancelled` 带 `possiblyExecuted` 时打印"已取消；该操作可能已生效"；输出中不含 ESC 字符（`\u001b`）；`progressOnly` 模式下不输出文字增量、工具行与终态行写入指定的错误输出
- [ ] T037 [P] [US1] 在 `AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandStreamingTest.java` 编写：交互模式下 `TurnSink.streaming()` 为 `true`；桩模型分三段、每段间隔 300ms 返回时，第一段在到达后 1 秒内出现在输出中（SC-001）；成功时回答正文只出现一次；`returnDirect` 工具的结果仍被打印
- [ ] T038 [P] [US1] 在 `AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java` 增加回归：`--message` 成功时标准输出与改动前逐字节一致（只有最终回答一行），工具行写标准错误，退出码不变（SC-006）

### 实现

- [ ] T039 [US1] 新建 `AgentOS-channel-cli/src/main/java/com/agentos/channel/cli/TerminalTurnRenderer.java` 实现 `TurnSink`：构造参数 `(PrintWriter out, PrintWriter err, boolean interactive)`；`streaming()` 返回 `interactive`；覆写 `onEvent` 按 `contracts/cli-output.md` §1 渲染；维护"当前行是否有未换行文字"、本轮工具次数、模型调用次数（`step.started` 计数）、起始时间；对外提供 `boolean finalAnswerStreamed()`（最后一条消息的累计文字等于 `run.completed.text` 时为 true）与 `Optional<UUID> currentTurnId()`（取自 `run.started`，终态后清空）；不输出 ANSI 控制序列（使 T036 通过）
- [ ] T040 [US1] 修改 `AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java`：交互模式为每条消息创建 `TerminalTurnRenderer(out, err, true)` 并传给 `agentService.process(request, renderer, capabilities)`；成功时仅在 `!renderer.finalAnswerStreamed()` 时打印 `result.text()`；`--message` 模式用 `TerminalTurnRenderer(out, err, false)`，其余输出与退出码逻辑保持不变；`TurnStatus.CANCELLED` 与其他非成功状态一样返回退出码 3（使 T037、T038 通过）
- [ ] T041 [US1] 运行 `mvn -B -ntp -pl AgentOS-channel-cli,AgentOS-cli -am test`；`ChatCommandStreamingTest` 把桩模型驱动的一次完整 chat 终端输出（含文字分段、两条工具行、确认提示、终态行，以及每段文字的到达与显示时间差）写到 `target/007-demo/chat-transcript.txt`，摘录到演示记录 `specs/007-run-event-stream/us1-demo.md`；真实模型下按 quickstart §4 第 1 步的手工演示为可选，结果追加到同一文件，未做记 NOT_RUN

**Checkpoint**: CLI 能实时显示执行过程

---

## Phase 6: User Story 4 - 中途取消当前轮次 (Priority: P2)

**Goal**: Ctrl+C 只取消当前轮次，以 `CANCELLED` 持久化，2 秒内结束；空闲时行为不变

**Independent Test**: 在模型输出中、只读工具中、有副作用工具中、等待确认中四个时机调用 `AgentService.cancel(turnId)`（quickstart §3 US4）

### 测试（先写，确认失败）

- [ ] T042 [P] [US4] 在 `core-test/turn/AgentServiceCancelTest.java` 编写：在模型输出中、只读工具中、等待确认中三个时机取消后 `TurnResult.status == CANCELLED`、2 秒内返回、之后桩模型调用与工具调用次数为 0，终态事件为 `run.cancelled`；在有副作用工具执行中取消时断言 `possiblyExecuted == true`、文本带"可能已生效"，持久化结果为 `CANCELLED`（工具响应了中断）或 `persistence == UNCONFIRMED`（工具未在 2 秒内退出）；等待确认场景 `approval.resolved.decision == CANCELLED` 且工具真实执行 0 次；`cancel` 对未知或已结束的轮次返回 `false`、重复调用幂等；取消与成功同时到达时只有一个终态且与存储一致；`close()` 关闭导致的取消仍是 `INTERRUPTED`
- [ ] T043 [P] [US4] 在 `AgentOS-tool/src/test/java/com/agentos/tool/govern/GovernedToolEventsTest.java` 增加用例：预算以 `USER` 原因取消时，工具审计状态为 `interrupted`（不是 `timeout`），`tool.finished.status == INTERRUPTED`
- [ ] T044 [P] [US4] 在 `AgentOS-cli/src/test/java/com/agentos/cli/InterruptCancellerTest.java` 编写（用可注入的信号源替身，不发送真实信号）：有轮次时调用 `cancel(turnId)` 一次并打印"（正在取消…）"；取消进行中再次触发被忽略；`run.started` 之前触发时记为待取消、收到轮次标识后立即取消；空闲时调用安装前的原处理器；安装失败时只记警告
- [ ] T045 [US4] 在 `AgentOS-boot/src/test/java/com/agentos/boot/TurnCancelIntegrationTest.java`（PostgreSQL）验证：取消后 `agent_turns.status = 'CANCELLED'`、`tool_invocations` 中被中止工具为 `interrupted`、会话租约已释放、同一会话可立即开始下一轮；并启用 T028 的取消场景

### 实现

- [ ] T046 [US4] 修改 `core/turn/AgentService.java` 新增 `default boolean cancel(UUID turnId) { return false; }`，Javadoc 按 `contracts/core-ports.md` §2
- [ ] T047 [US4] 修改 `core/turn/TurnRunner.java`：新增 `run(Callable<T> task, TurnExecutionBudget budget, Consumer<Thread> onWorkerStart)` 重载（任务开始时把 `Thread.currentThread()` 交给回调），原 `run(task, budget)` 委托给它并传空回调；保持现有分段等待与 hard stop 逻辑不变
- [ ] T048 [US4] 修改 `core/turn/DefaultAgentService.java`：`InFlight` 增加执行线程字段，只在包住 `loop.run(...)` 的那次 `runner.run(...)` 调用里经 T047 的回调登记，起步、查证、收尾的 `runner.run(...)` 不登记（避免取消时打断持久化）；循环结束后清空该字段；实现 `cancel(turnId)`：找到在途轮次 → `budget.cancel(USER)` → 中断执行线程（只中断一次）→ 返回 `true`；超时、租约丢失、通用异常三个 `catch` 分支在 `budget.cancelReason() == USER` 时改用 `TurnStatus.CANCELLED`、文本 `budget.submissionAwareText("本轮已取消")`、`failureCode` 为空；日志按现有规则只记状态与耗时（使 T042 通过）
- [ ] T049 [US4] 修改 `AgentOS-tool/src/main/java/com/agentos/tool/govern/GovernedToolCallback.java`：捕获 `TurnDeadlineExceededException` 时，若 `budget.cancelReason() == USER`，状态记 `interrupted`（使 T043 通过）
- [ ] T050 [US4] 新建 `AgentOS-cli/src/main/java/com/agentos/cli/InterruptCanceller.java`：用 `sun.misc.Signal.handle(new Signal("INT"), …)` 安装处理器并保存原处理器；通过构造参数接收"信号安装器"接口以便测试替换；按 T044 的行为实现；类注释说明选择 `sun.misc.Signal` 的原因与 JLine 替代方案（research R8）；`close()` 恢复原处理器（使 T044 通过）
- [ ] T051 [US4] 修改 `AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java`：交互模式在读取循环外创建 `InterruptCanceller`，每轮把当前 `TerminalTurnRenderer` 的轮次标识交给它；`--message` 模式不安装；退出时 `close()`
- [ ] T052 [US4] 运行 `mvn -B -ntp -pl AgentOS-core,AgentOS-tool,AgentOS-cli -am test` 与 `mvn -B -ntp -pl AgentOS-boot -am -Dtest='TurnCancelIntegrationTest,RunEventContractIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`；`TurnCancelIntegrationTest` 把四个取消时机的耗时、`agent_turns.status`、工具审计状态与 CLI 终态行写到 `target/007-demo/cancel.txt`，摘录到演示记录 `specs/007-run-event-stream/us4-demo.md`；真实模型下按 quickstart §4 第 2、3 步的手工演示为可选，结果追加到同一文件，未做记 NOT_RUN

**Checkpoint**: 取消可用，10 类契约场景全部启用

---

## Phase 7: Polish & Cross-Cutting

- [ ] T053 [P] 同步 `docs/TechnicalSolution.md`：4.4 节 `TurnSink` 段落改为事件模型（`onEvent`、发布器、序号、终态唯一、兼容层），并链接 `specs/007-run-event-stream/contracts/run-events.md`（7.4 节与第 9 章已在 T014 更新）
- [ ] T054 [P] 在 `CLAUDE.md`「工程基线」的运行日志约定里补一句："`DefaultAgentService` 记录用户取消的受理（只记 turnId 与是否受理）；`InterruptCanceller` 信号处理器安装失败记一条 WARN（只记信号名与异常类型）；事件发布器与终端渲染器不打日志。"其他条目不改
- [ ] T055 运行 `mvn spotless:apply` 后执行完整质量门 `mvn -B -ntp verify`，与 T001 的基线对比，结果写入 `specs/007-run-event-stream/acceptance-results.md`（含 SC-001–SC-008 逐条结论；需要真实模型的手工演示记 NOT_RUN 或实测结果）
- [ ] T056 运行 `/speckit-analyze` 检查 `specs/007-run-event-stream/` 下 spec、plan、tasks 与实现的漂移，发现偏离先修正再收尾

---

## Dependencies & Execution Order

### 阶段依赖

- **Phase 1 Setup**：无依赖
- **Phase 2 Foundational**：依赖 Phase 1；阻塞所有故事
- **Phase 3 US2**：依赖 Phase 2
- **Phase 4 US3**：依赖 Phase 2；`ReActLoop.java` 与 `DefaultAgentService.java` 与 US2 改同一文件，所以排在 US2 之后
- **Phase 5 US1**：依赖 US3（渲染器消费 US3 的事件）
- **Phase 6 US4**：依赖 US3（取消的终态事件、工具事件）与 US1（`ChatCommand` 装配、渲染器的轮次标识）
- **Phase 7 Polish**：依赖所有故事完成

### 故事内顺序

测试先写并确认失败 → 类型 / 接口 → core 实现 → tool / cli 实现 → 本阶段的运行命令

### 同文件串行提示

以下文件被多个任务修改，必须按编号顺序执行，不能并行：
- `core/turn/ReActLoop.java`：T019 → T029
- `core/turn/DefaultAgentService.java`：T015 → T020 → T034 → T048
- `AgentOS-tool/.../GovernedToolCallback.java`：T031 → T049
- `AgentOS-cli/.../ChatCommand.java`：T040 → T051
- `AgentOS-tool/.../GovernedToolEventsTest.java`：T025 → T043

---

## Parallel Examples

```text
# Phase 2：三类测试与两个独立类型可同时写
T002 TurnEventPublisherTest   T003 TurnSinkCompatibilityTest   T004 MigrationTest
T005 TurnFailureCode          T006 TurnEventPayload            T014 V3 迁移

# Phase 4：tool 模块的三个测试互不依赖
T025 GovernedToolEventsTest   T026 ToolApprovalGateTest        T027 AskUserToolsEventsTest

# Phase 5：渲染器测试与 chat 测试在不同模块
T036 TerminalTurnRendererTest T037 ChatCommandStreamingTest    T038 ChatCommandTest

# Phase 6：三个模块的测试可同时写
T042 AgentServiceCancelTest   T043 GovernedToolEventsTest      T044 InterruptCancellerTest
```

---

## Implementation Strategy

### 最小可演示增量

1. Phase 1–2 完成后，事件契约的类型与发布器就绪，但还没有入口能看到效果。
2. 完成 Phase 3（US2）即可交付"截断 / 过滤不再被当作成功"这一正确性修复，可单独合并。
3. 完成 Phase 4（US3）+ Phase 5（US1）是第一个用户可见的 MVP：CLI 实时显示执行过程。
4. Phase 6（US4）在此基础上加 Ctrl+C 取消。

### 每个检查点

运行该阶段末尾的命令；失败时修代码，不放宽质量门、不加排除项（宪章"开发流程与质量门"）。每完成一个阶段提交一次，提交信息沿用仓库的 `feat(core)` / `feat(tool)` / `feat(cli)` / `test(boot)` 前缀。
