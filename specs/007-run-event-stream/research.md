# Research: 运行事件流

**Feature**: [spec.md](./spec.md) | **Date**: 2026-11-21

本文件记录 Phase 0 的技术决策。所有结论来自阅读当前代码（`main` 分支 `6f24d84` 之后）与 AG-UI 规范原文（`ag-ui-protocol/ag-ui` 仓库 `docs/concepts/events.mdx`、`interrupts.mdx`、`serialization.mdx`）。

## R1 事件从哪里发、交给谁

**Decision**: 保留 `TurnSink` 作为唯一的消费方端口（宪章原则 II 点名的扩展点），新增一个方法 `onEvent(TurnEvent)`。核心层内部由 `ManagedTurnSink` 升级为**事件发布器**：分配序号、串行投递、守住"终态唯一"。核心代码只调用发布器，不再直接调用 `onDelta` / `onToolCall` / `onToolResult` / `onComplete` / `onError`。

`onEvent` 的默认实现把事件翻译回这 5 个旧回调，所以现有的 `AgentLoopDebugController.TraceSink`、测试里的 `RecordingTurnSink` 不改也能工作；新消费方（CLI 渲染器、后续的事件落库、SSE、AG-UI 翻译）直接覆写 `onEvent`。

**Rationale**:
- 只有一个端口，后续"新增消费方不改核心循环"（FR-010）才成立。
- 旧回调保留为兼容层，避免一次改动波及调试接口和十几个测试。
- 发布器放在 core，序号规则和终态规则只有一份实现。

**Alternatives considered**:
- 新增一个独立的 `RunEventListener` 接口：两个端口并存，入口要同时实现两套，否决。
- 直接删除 5 个旧回调：改动面大，且调试接口要同步重写，本期收益不大，否决。

## R2 序号与跨线程串行

**Decision**: 发布器内部用 `ReentrantLock` 保护"分配序号 + 调用消费方"这一整段，序号从 1 开始，`long` 自增。不使用 `synchronized`，避免 JDK 21 虚拟线程在持锁调用消费方时被钉住（pinning）。

现在的 `ManagedTurnSink` 用 `busy` 标志位检测并发进入，并发时直接抛 `LeaseLostException`。事件变多以后会出现合法的跨线程顺序发布：流式文字在 Reactor 线程，工具开始 / 结束在轮次线程，确认请求可能在工具的 worker 线程。这些发布在逻辑上是先后发生的，但线程不同，所以要改成"排队等锁"而不是"并发即失败"。等锁受本轮 hard stop 约束（`tryLock` 带剩余时间），等不到按输出端失效处理。

**Rationale**: FR-001 要求跨线程也连续编号；排队等锁是满足它的最简单方式。消费方慢只会拖慢本轮，不会打乱顺序。

**Alternatives considered**:
- 无锁队列 + 单独投递线程：多一个线程和生命周期管理；终态"在持久化之后发出"的顺序更难保证，否决。
- `AtomicLong` 只管序号、不管投递：序号有序但投递可能乱序，消费方要自己重排，违背 FR-001，否决。

## R3 工具事件的发射位置

**Decision**:
- **实际执行的工具**：`GovernedToolCallback.call(...)` 在 `recorder.begin(...)` 成功之后、真正执行之前发 `tool.started`；在 `recorder.complete(...)` 之后发 `tool.finished`。发布器经 `ToolContext` 的新键 `ToolContextKeys.TURN_EVENTS` 传入（约束第 7 条：不用 ThreadLocal）。
- **未执行的工具**：`ToolExecutor` 里两条路径（工具次数超限的 `limit_skipped`、参数不是合法 JSON 的整批拒绝）各补一对 `tool.started` / `tool.finished`，状态分别为 `SKIPPED`、`FAILED`。
- **`ReActLoop`** 不再在整批执行前后发工具事件。

这样模型请求的每一个工具调用都恰好有一对开始 / 结束事件（SC-004），并与 `tool_invocations` 的一行审计一一对应。

**Rationale**: 只有 `GovernedToolCallback` 知道单个工具什么时候真正开始、以什么状态结束；它已经是审计、超时、脱敏的唯一位置（约束第 3 条），事件放在同一处，内容与审计天然一致（FR-009）。

**Alternatives considered**: 在 `ReActLoop` 里根据 `ToolExecutor` 的返回结果补发：只能在整批结束后发，做不到"单个工具实时显示"（FR-006），否决。

**MCP 隔离**: MCP 工具用 `ToolContextToMcpMetaConverter.noOp()`，`ToolContext` 不会发给 MCP server；新增的键同样不会外泄（约束第 5 条）。

## R4 确认与提问事件

**Decision**: `ToolApprovalGate.approve(...)` 在调用 `InteractionPort.approve(...)` 之前发 `approval.requested`，在 `finally` 里发 `approval.resolved`；等待被取消或抛异常时，决定记为 `CANCELLED`。本会话已授权、直接放行的动作不发确认事件（没有发生交互）。无人值守入口返回 `UNSUPPORTED` 时照常发一对事件，决定为 `UNATTENDED`。

`AskUserTools` 用同样的方式发 `input.requested` / `input.resolved`。提问内容（由模型写出、本来就展示给用户）经脱敏后放进事件；用户的答案正文和拒绝原因不进事件（FR-007）。

**Rationale**: 确认是后续网页端"类 Codex 操作确认"的核心交互，必须有独立事件和请求标识。请求标识用现有的 `ApprovalRequest.requestId()` / `InteractionRequest.requestId()`，将来就是 AG-UI interrupt 的 `id`。

## R5 截断与内容过滤的识别

**Decision**: `ReActLoop` 在拿到模型响应后读取 `generation.getMetadata().getFinishReason()`，**忽略大小写**比较：
- `length` → 失败，错误码 `OUTPUT_TRUNCATED`
- `content_filter` → 失败，错误码 `CONTENT_FILTERED`

判断放在"是否有工具调用"之前，所以截断那次响应里的工具调用不会执行（FR-011）；截断的文字也不追加到 `session_messages`。

**依据**: 现有测试已经证明 Spring AI 2.0.2 在同步与流式两种方式下都保留结束原因，且大小写不固定（`CallRecordTest`、`StreamingTest` 用 `isEqualToIgnoringCase` 断言 `length` / `stop`，`StreamingTest:284` 断言流式与同步的结束原因相同）。DeepSeek 的冒烟测试也断言了 `length`。

**不重试**: 截断与过滤不是 `ProviderCallException`，`ProviderService` 的重试分类不会命中（FR-013）。

**Alternatives considered**: 在 `ProviderService` 里把截断转成异常：截断是模型的正常输出，不是调用失败；放在 Provider 会让审计里的 `success=false` 语义混乱，否决。

## R6 截断原因如何进入下一轮上下文

**Decision**: 不改表结构。失败轮次的 `agent_turns.error_message` 以错误码开头写入，例如 `OUTPUT_TRUNCATED：模型输出达到上限被截断`。`HistoryTurn` 已经带 `errorMessage`，`PromptBuilder.safeReason(...)` 先看状态，状态为 `FAILED` 时再看错误码前缀，给出"上一轮回答因长度上限被截断"或"上一轮回答被内容策略过滤"这两条固定说明（FR-012）。

**Rationale**: 项目方已确认截断 / 过滤不新增持久化状态；`error_message` 已有、已脱敏、已被读回，不需要迁移。

## R7 取消：入口、原因与时间窗

**Decision**:
1. **入口**：`AgentService` 新增 `boolean cancel(UUID turnId)`，默认返回 `false`。`DefaultAgentService` 在进程内的在途表里找到该轮预算，调用 `budget.cancel(CancelReason.USER)`，并中断该轮的执行线程（不是调用方线程）。找不到或已结束返回 `false`。CLI 与后续网页停止按钮共用这个入口（FR-021）。
2. **原因**：`TurnExecutionBudget.cancel()` 增加原因参数 `CancelReason { USER, SHUTDOWN, OUTPUT_FAILED, INTERNAL }`，第一个原因生效。现有调用点按语义改为对应原因（`close()` → `SHUTDOWN`，输出端失效 → `OUTPUT_FAILED`，其余 → `INTERNAL`）。
3. **终态**：`DefaultAgentService` 现有的超时、租约丢失、通用异常三个 `catch` 分支里，只要 `budget.cancelReason() == USER`，状态一律改为新的 `TurnStatus.CANCELLED`，文本为"本轮已取消"（有副作用工具可能已生效时由 `submissionAwareText` 追加提示）。
4. **时间窗**：用户取消把 hard stop 收紧到"现在 + 2 秒"（常量，不开放配置），而不是现有 `cancel()` 的 1 秒。循环在中断后通常 100 毫秒内退出（`TurnRunner` 每 100 毫秒重读余量，流式 `blockLast()` 响应中断），剩余时间留给 `finishAndRelease` 持久化"已取消"。2 秒同时也是 SC-005 的上限。
5. **竞态**：`JpaTurnStore.finish(...)` 只更新仍为 `RUNNING` 的轮次，终态只写一次；控制面事件由最终 `TurnResult` 生成，所以存储与事件一致（FR-020）。如果取消到达时循环已经拿到成功结果，成功生效。
6. **工具状态**：用户取消导致的 `TurnDeadlineExceededException`，在 `GovernedToolCallback` 里记为 `interrupted` 而不是 `timeout`。

**为什么要中断执行线程**: 现有 `cancel()` 只设标志并把 hard stop 收紧到 1 秒，正在进行的流式模型请求要等到 hard stop 才被 `future.cancel(true)` 打断，那时已没有时间持久化，结果会变成"持久化未确认"。直接中断执行线程，`blockLast()` 立即抛出、被归类为 `CANCELLED`，循环有时间正常收尾。`DefaultAgentService` 需要在 `TurnRunner` 提交任务时记下执行线程。

**已知限制**: 同步（非流式）模型调用由 SDK 的 HTTP 客户端执行，线程中断不一定立即打断网络读取，最长要等到单次请求超时或 2 秒 hard stop。交互式 chat 使用流式调用，不受影响；记录在 quickstart 的已知限制里。

## R8 CLI 捕获 Ctrl+C

**Decision**: 用 `sun.misc.Signal.handle(new Signal("INT"), ...)`（JDK 的 `jdk.unsupported` 模块，`--release 21` 可编译）安装处理器，封装在 `AgentOS-cli` 的一个小类里：
- 有轮次在执行：调用 `agentService.cancel(turnId)`，打印"正在取消…"。轮次标识来自 `run.started` 事件；如果 Ctrl+C 早于 `run.started` 到达，先记下"待取消"，收到 `run.started` 时立即取消。
- 没有轮次在执行：调用安装前的原处理器，行为与现在一致（JVM 正常关闭、Spring 上下文关闭）（FR-018）。
- 安装失败（平台不支持该信号）：记一条警告，退化为现有行为。

**Rationale**: 纯 JDK 里只有这个办法能在不退出进程的前提下拦截 SIGINT；关闭钩子（shutdown hook）只能在进程退出时运行，不能"取消退出"。项目没有 JLine，不为一个信号处理引入新依赖。

**Alternatives considered**:
- 引入 JLine：功能更全（行编辑、补全），但新增依赖、要改输入 broker，超出本期范围，否决。
- 约定输入 `/cancel` 命令：执行期间 broker 的读取线程不读聊天输入，做不到即时取消，且不符合用户习惯，否决。

## R9 CLI 渲染方式

**Decision**: 在 `AgentOS-channel-cli` 新增终端渲染器 `TerminalTurnRenderer implements TurnSink`：
- **交互模式**：`streaming()` 返回 `true`；文字增量直接 `print` 并 `flush`；工具开始、工具结束各打印一行（参数、结果摘要在 CLI 上再限长到 80 个字符、去掉换行）；打印工具行或终态行之前，如果当前行有未换行的文字，先补一个换行，避免与 broker 打印的确认提示混在一行（FR-016）。
- **`--message` 模式**：`streaming()` 返回 `false`；不打印文字增量；工具行与终态行写标准错误；最终回答仍由 `ChatCommand` 打印到标准输出，与现在一致（FR-015）。
- **不输出任何 ANSI 控制序列**。FR-017 只要求非终端时不输出；本期干脆都不输出，以后要加颜色再按 `System.console()` 判断。
- **终态**：非成功、或调用过工具时打印一行终态（FR-014）。`ChatCommand` 成功时只在"最终回答没有被流式显示过"时打印回答，例如 `returnDirect` 工具直接返回结果的情况。

**Rationale**: 渲染属于终端适配，放在 channel 模块，与输入 broker 同处；`ChatCommand` 只负责装配和退出码。ArchUnit 规则只允许 `com.agentos.cli..` 和 `com.agentos.channel.cli..` 直接接触标准输出，两处都在允许范围内。

## R10 步骤与消息事件的数据来源

**Decision**:
- `step.started` 在每次模型调用前发，带迭代序号。
- `step.finished` 在模型返回后发，结束原因、请求模型、实际模型、token 用量取自 `ProviderResult.calls()` 的最后一条 `LlmCallRecord`；调用失败（`ProviderCallException`）时同样发 `step.finished`，结果为失败并带失败分类，然后重新抛出。
- `message.started` 在流式收到**第一段非空文字**时发；同步调用在拿到完整文字后依次发 `message.started`、一条包含全文的 `message.delta`、`message.ended`。只有工具调用、没有文字的响应不发消息事件。
- Provider 在首段文字之前重试成功时，用户只看到一份消息事件；重试次数体现在审计记录里，本期不发"重试中"事件。

**Rationale**: 用量和模型信息已经在审计记录里算好，直接复用，不重复解析 Spring AI 的响应结构（FR-008）。

## R11 失败错误码

**Decision**: core 新增枚举 `TurnFailureCode`，由 `DefaultAgentService` 在生成最终 `TurnResult` 时确定，写入控制面事件：

| 来源 | 错误码 |
| --- | --- |
| 结束原因 `length` / `content_filter` | `OUTPUT_TRUNCATED` / `CONTENT_FILTERED` |
| 模型返回空回答 | `EMPTY_RESPONSE` |
| 整轮超时 | `TIMED_OUT` |
| 达到 `max_iterations` | `MAX_ITERATIONS` |
| 工具次数超限（THROW 模式） | `TOOL_LIMIT_EXCEEDED` |
| `ProviderCallException` | 按最后一次尝试的 `FailureKind`：`PROVIDER_RATE_LIMITED`、`PROVIDER_SERVER_ERROR`、`PROVIDER_CONNECTION`、`PROVIDER_AUTH`、`PROVIDER_INVALID_REQUEST`、`PROVIDER_PROTOCOL` |
| 租约丢失 / 输出端失效 | `LEASE_LOST` / `OUTPUT_FAILED` |
| 进程关闭 | `SHUTDOWN` |
| 审计未确认 / 工具清理未确认 | `AUDIT_UNCONFIRMED` / `TOOL_CLEANUP_UNCONFIRMED` |
| 起步未确认 | `START_UNCONFIRMED` |
| 其他 | `INTERNAL` |

`TurnResult` 增加可空字段 `failureCode`（成功与取消为空），提供不带该字段的兼容构造器，避免改动所有现有调用点。

## R12 起步未确认时的事件

**Decision**: `startTurn` 未确认（`DefaultAgentService` 现有的 `START_UNCONFIRMED` 分支）时，会话标识未知。此时发 `run.started`（`sessionId` 为空）和 `run.failed`（`START_UNCONFIRMED`）两条事件，保证"首条为轮次开始、终态唯一"的不变量对所有轮次都成立。这是唯一允许 `sessionId` 为空的情况，写进契约。

## R13 数据库迁移

**Decision**: 新增 `V3__turn_status_cancelled.sql`，按 V2 的做法删除并重建 `agent_turns.status` 的 CHECK 约束，加入 `CANCELLED`。不加列、不加表。技术方案第 9 章 `agent_turns.status` 的取值、7.4 节的状态列表同步更新。

## R14 与后续 Web 流式接口的衔接（本期不实现，只确认契约可承接）

对照 AG-UI 规范原文核对过的映射（详见 [contracts/run-events.md](./contracts/run-events.md) 第 6 节）：

- `sessionId` / `turnId` → AG-UI `threadId` / `runId`；`message.*` → `TEXT_MESSAGE_START/CONTENT/END`；`tool.*` → `TOOL_CALL_START/ARGS/END/RESULT`（参数一次性放进一条 `ARGS`，规范允许）；`step.*` → `STEP_STARTED/FINISHED`。
- AG-UI 没有取消事件，`run.cancelled` 翻译为 `RUN_ERROR`（`code=CANCELLED`）。
- AG-UI 的 interrupt 规定"本次 run 以 `RUN_FINISHED{outcome: interrupt}` 结束，客户端带 `resume` 发起新 run"。AgentOS 的确认是轮次内阻塞等待（006）。翻译层可以在 `approval.requested` 时对 AG-UI 客户端结束当前 run，收到 `resume` 后把决定交给仍在等待的轮次，后续事件放进新的 `runId`。所以本期的 `approval.requested` 必须带稳定的请求标识和工具调用标识，`approval.resolved` 必须能由外部决定触发。这两点本期契约已满足。
- 断线重放依赖 `turnId + seq`，本期序号规则即为后续事件落库的唯一键。
