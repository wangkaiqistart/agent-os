# Implementation Plan：运行事件流

**Branch**: `specs/007-run-event-stream` | **Date**: 2026-11-21 | **Spec**: [spec.md](./spec.md)

**Input**: `specs/007-run-event-stream/spec.md`

setup-plan 返回规范化 BRANCH `007-run-event-stream`，实际 Git 分支如上。

## Summary

给每一轮对话定义一份有序、终态唯一、内容已脱敏的运行事件序列，作为所有入口观察执行过程的唯一契约；`AgentOS chat` 作为第一个消费方实时显示文字、单个工具的进度与终态，并支持 Ctrl+C 取消当前轮次。同时修正"截断 / 内容过滤被当成成功"的问题。

技术做法：
- **事件端口**：`TurnSink` 新增 `onEvent(TurnEvent)`，旧的 5 个回调由默认实现兼容。
- **发布器**：core 的 `ManagedTurnSink` 升级为事件发布器，用锁串行编号并投递，只有它能发终态。
- **工具事件**：`GovernedToolCallback`、`ToolApprovalGate`、`AskUserTools` 经 `ToolContext` 拿到发布器，按单个工具发事件。
- **截断识别**：`ReActLoop` 读取结束原因，`length` / `content_filter` 时不执行工具、以错误码失败结束。
- **取消**：`AgentService.cancel(turnId)` 记录取消原因并中断执行线程，持久化新的 `CANCELLED` 状态。
- **CLI**：`AgentOS-channel-cli` 新增终端渲染器，`AgentOS-cli` 用 `sun.misc.Signal` 拦截 Ctrl+C。

不交付：事件持久化与断线重放、SSE 端点、AG-UI 翻译、网页端确认接口、多实例通知、推理内容事件、工具参数逐段流式。这些按宪章 1.1.0 随 US-5 交付；本期契约已按 AG-UI 原文核对过可承接（[contracts/run-events.md §6](./contracts/run-events.md)）。

## Technical Context

**Language/Version**: JDK 21；中文注释，SLF4J 占位符日志。

**Primary Dependencies**: Spring Boot 4.1.2、Spring AI 2.0.2（`ChatModel.stream`、`MessageAggregator`、注入的 `ToolCallingManager`、`ToolContext`）、Picocli 4.7.7、JDK `jdk.unsupported` 模块的 `sun.misc.Signal`。不新增 Maven 依赖。

**Storage**: PostgreSQL 16+。新增迁移 `V3__turn_status_cancelled.sql`，只重建 `agent_turns.status` 的 CHECK 约束、加入 `CANCELLED`。截断 / 过滤原因写进已有的 `agent_turns.error_message`（以错误码开头），不加列、不加表。事件只在进程内传递。

**Testing**: JUnit 5；core 用桩 `ProviderService` 与记录型 `TurnSink`；provider 用 `StubModelServer`；应用级用 Testcontainers `pgvector/pgvector:pg17` + 真实注入的 `ToolCallingManager` + 治理链；CLI 用输入 / 输出替身。不调用真实模型。最终质量门 `mvn -B -ntp verify`。

**Target Platform**: 可信单机 Linux / macOS，JDK 21；Ctrl+C 拦截依赖 POSIX `SIGINT`，不支持时退化为现有行为。

**Project Type**: 九模块 Maven 单体；本期改动落在 core、tool、channel-cli、cli、storage（迁移），web 的调试接口通过兼容层继续工作、不改代码；不新增模块。

**Performance Goals**: 第一段文字到达后 1 秒内显示（SC-001）；取消后 2 秒内结束（SC-005）。发布器每条事件只做一次加锁和一次方法调用，不引入额外线程。

**Constraints**: 消费方投递受本轮 hard stop 约束；用户取消把 hard stop 收紧到 +2 秒（常量）；CLI 摘要限长 80 字符；事件内容复用审计的脱敏与限长（参数 10000 字符、结果 `max_result_chars`）。

**Scale/Scope**: 4 个用户故事、21 条功能要求、8 条成功标准；15 种事件类型、19 个失败错误码。

## Constitution Check

门禁在 Phase 0 前和 Phase 1 后各复核一次。依据宪章 1.1.0。

| 原则 / 门禁 | 设计落实 | 研究前 | 设计后 |
| --- | --- | --- | --- |
| I：版本、模块、Central | 无新依赖、无新模块；契约在 core，实现在 tool / channel-cli / cli | PASS | PASS |
| II：核心能力边界（1.1.0） | 交付的是"随 ReAct 循环交付的运行事件契约"；无 SSE 端点、无事件存储；核心循环只经 `TurnSink` 输出，不感知 HTTP / SSE / AG-UI | PASS | PASS |
| III：循环自实现、官方组件 | `ReActLoop` 仍直接调 `ChatModel.call/stream`；流式聚合仍用 `MessageAggregator`；发布器经 `ToolContext` 传递，不用 ThreadLocal；两道上限、四层超时不变 | PASS | PASS |
| IV：唯一执行入口 | 工具仍只经 `ToolExecutor` → 注入的 `ToolCallingManager` 执行；事件在 `GovernedToolCallback` 内发出，不新增执行路径 | PASS | PASS |
| V：插件 / MCP | MCP 仍用 `noOp()` 转换器，新的 `ToolContext` 键不外发 | PASS | PASS |
| VI：PostgreSQL / 审计 | 迁移由 Flyway 管理、只改 CHECK 约束；`tool_invocations` / `llm_calls` 写入不变，事件是其安全投影，不替代审计 | PASS | PASS |
| VII：可演示 Demo | 每个故事一份不依赖真实模型的演示记录（us1–us4-demo.md，由测试捕获输出生成）；真实模型手工演示可选 | PASS | PASS |
| VIII：Agent 目录非 Tool | 不涉及 | PASS | PASS |
| 密钥 / Sandbox / 事务 | 事件内容经 `Redactor`；不记日志；发布与取消不在 `@Transactional` 内；Sandbox 规则不变 | PASS | PASS |
| 流程 / 质量门 | plan 人工 review → tasks → 实施 → analyze；`mvn verify` 不放宽 | PASS | PASS |

`sun.misc.Signal` 属于 JDK 自带的 `jdk.unsupported` 模块，不是新依赖，`--release 21` 可编译；ArchUnit 只允许 `com.agentos.cli..` / `com.agentos.channel.cli..` 访问标准输出，新类都在这两个包内。无宪章豁免。

编译 `sun.misc.Signal` 会产生一条"内部专用 API"的 javac 警告（无法用注解压制）。项目未开启 `-Werror`，不影响质量门；该用法集中在一个类里，并在类注释说明原因与替代方案（research R8）。

## Project Structure

### Documentation (this feature)

```text
specs/007-run-event-stream/
├── spec.md
├── plan.md                 # 本文件
├── research.md             # Phase 0：R1–R14 决策
├── data-model.md           # Phase 1：事件、发布器、错误码、状态迁移
├── quickstart.md           # Phase 1：验收与演示路径
├── contracts/
│   ├── run-events.md       # 事件契约（顺序语法、字段、安全、AG-UI 映射）
│   ├── core-ports.md       # TurnSink / AgentService / TurnResult / 预算的接口变化
│   └── cli-output.md       # chat 的终端输出与 Ctrl+C 行为
├── checklists/requirements.md
├── tasks.md                # Phase 2（/speckit-tasks 生成）
└── us1-demo.md … us4-demo.md  # 实施时生成：测试捕获输出整理的演示记录
```

### Source Code (repository root)

```text
AgentOS-core/src/main/java/com/agentos/core/
├── turn/
│   ├── TurnEvent.java                 # 新增：事件公共字段 + payload
│   ├── TurnEventPayload.java          # 新增：sealed interface 与 15 个 record
│   ├── TurnEventPublisher.java        # 新增：工具侧可见的发布接口（publish）
│   ├── TurnFailureCode.java           # 新增：失败错误码
│   ├── ManagedTurnSink.java           # 改：升级为发布器（锁、序号、终态唯一）
│   ├── TurnSink.java                  # 改：新增 onEvent，旧回调变兼容层
│   ├── ReActLoop.java                 # 改：步骤 / 消息事件、结束原因判断、不再整批发工具事件
│   ├── ToolExecutor.java              # 改：放入 TURN_EVENTS；跳过 / 拒绝的工具补事件
│   ├── ToolContextKeys.java           # 改：新增 TURN_EVENTS
│   ├── TurnExecutionBudget.java       # 改：CancelReason、cancel(reason)
│   ├── DefaultAgentService.java       # 改：run.started、cancel(turnId)、CANCELLED 映射、错误码
│   ├── AgentService.java              # 改：新增 cancel(UUID)
│   ├── TurnRunner.java                # 改：记录执行线程，供取消时中断
│   ├── TurnStatus.java / TurnResult.java  # 改：CANCELLED、failureCode
│   └── PromptBuilder.java             # 改：CANCELLED 与截断 / 过滤的固定说明
AgentOS-tool/src/main/java/com/agentos/tool/
├── govern/GovernedToolCallback.java   # 改：tool.started / tool.finished；用户取消记 interrupted
├── sandbox/ToolApprovalGate.java      # 改：approval.requested / resolved
└── interaction/AskUserTools.java      # 改：input.requested / resolved
AgentOS-channel-cli/src/main/java/com/agentos/channel/cli/
└── TerminalTurnRenderer.java          # 新增：终端渲染 TurnSink
AgentOS-cli/src/main/java/com/agentos/cli/
├── ChatCommand.java                   # 改：装配渲染器、交互模式流式、--message 进度写标准错误
└── InterruptCanceller.java            # 新增：Ctrl+C → AgentService.cancel
AgentOS-storage/src/main/resources/db/migration/
└── V3__turn_status_cancelled.sql      # 新增
```

测试按 [quickstart.md §2](./quickstart.md) 的类名放在各模块 `src/test/java` 对应包下。

**Structure Decision**: 沿用现有九模块。契约（事件、端口、错误码、取消原因）全部放 core；工具侧事件在 tool 的治理与交互类里发出；终端渲染与信号处理分别放 channel-cli 与 cli。web 的 `AgentLoopDebugController` 依靠 `TurnSink` 兼容层继续工作，本期不改。

## 实施顺序（供 /speckit-tasks 拆分）

1. **基础（阻塞后续）**：`TurnEvent` / payload / 错误码 / `CancelReason`；`TurnSink.onEvent` 与兼容层；`ManagedTurnSink` 升级为发布器；`TurnStatus.CANCELLED`、`TurnResult.failureCode`；V3 迁移。
2. **US2 截断 / 过滤（P1）**：`ReActLoop` 结束原因判断、错误码、`error_message` 前缀、`PromptBuilder` 说明。先做，因为它决定终态与错误码。
3. **US3 事件契约（P1）**：`ReActLoop` 的步骤 / 消息事件；`DefaultAgentService` 的 `run.started` 与终态映射；`ToolExecutor` / `GovernedToolCallback` / `ToolApprovalGate` / `AskUserTools` 的工具与交互事件；应用级 10 类场景契约测试。
4. **US1 CLI 显示（P1）**：`TerminalTurnRenderer`、`ChatCommand` 装配；`--message` 输出回归。
5. **US4 取消（P2）**：`AgentService.cancel`、执行线程中断、`CANCELLED` 映射、工具 `interrupted`；`InterruptCanceller`；取消集成测试。
6. **收尾**：同步技术方案（4.4 `TurnSink` 事件模型、7.4 状态列表、第 9 章 `agent_turns.status` 取值）；`mvn -B -ntp verify`；`/speckit-analyze`。

## Complexity Tracking

无宪章违规，本节无条目。
