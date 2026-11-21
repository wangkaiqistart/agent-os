# 007 验收结果

- 日期：2026-11-21
- 分支：`specs/007-run-event-stream`
- 环境：JDK 21.0.9、Maven 3.9.14、Docker 29.9.2（Testcontainers `pgvector/pgvector:pg17`）
- 完整质量门：`mvn -B -ntp verify` → `BUILD SUCCESS`（格式、Checkstyle、SpotBugs、依赖禁令、ArchUnit、测试全部通过，规则未放宽）

## 测试数对比

| 模块 | 基线（implementation-baseline.md） | 007 完成后 | 失败 / 错误 |
| --- | --- | --- | --- |
| AgentOS-core | 98 | 130 | 0 / 0 |
| AgentOS-provider | 220 | 222 | 0 / 0 |
| AgentOS-tool | 147 | 164 | 0 / 0 |
| AgentOS-storage | 65 | 65（MigrationTest 内新增断言） | 0 / 0 |
| AgentOS-web | 14 | 14 | 0 / 0 |
| AgentOS-channel-cli | 6 | 14 | 0 / 0 |
| AgentOS-cli | 67 | 70 | 0 / 0 |
| AgentOS-boot | 108 | 121 | 0 / 0 |

过程中出现一次与本功能无关的偶发失败：`ToolCatalogModeIntegrationTest` 先用 `ServerSocket(0)` 取空闲端口再关闭、随后启动服务时端口已被占用（`PortInUseException`）。单独重跑与下一次完整质量门都通过，未修改。

## 成功标准

| 标准 | 结论 | 证据 |
| --- | --- | --- |
| SC-001 第一段文字 1 秒内显示 | 通过（实测 < 1 ms） | `ChatCommandStreamingTest`，us1-demo.md |
| SC-002 10 类场景事件序列合规 | 通过 | `RunEventContractIntegrationTest` 10 个用例（含取消），us3-demo.md |
| SC-003 截断 / 过滤 100% 非成功、工具执行 0 次、下一轮无截断文字 | 通过 | `ReActLoopFinishReasonTest`、`PromptHistoryTest`、`RetryPolicyTest`，us2-demo.md |
| SC-004 每个工具调用恰好一对开始 / 结束事件，对数等于审计行数 | 通过 | `RunEventContractIntegrationTest`、`GovernedToolEventsTest`、`ReActLoopEventsTest` |
| SC-005 三个时机取消后 2 秒内结束、之后 0 次模型 / 工具调用；副作用场景提示可能已生效 | 通过（实测 14～46 ms） | `TurnCancelIntegrationTest`、`AgentServiceCancelTest`，us4-demo.md |
| SC-006 `chat --message` 标准输出逐字节不变 | 通过 | `ChatCommandTest.singleMessageKeepsStdoutAndWritesToolProgressToStderr` |
| SC-007 事件中原始密钥出现 0 次 | 通过 | `RunEventContractIntegrationTest`（配置的 API key 作为测试密钥）、`GovernedToolEventsTest`、`AskUserToolsEventsTest`、`ToolApprovalGateTest` |
| SC-008 `mvn -B verify` 全部通过、门禁不放宽 | 通过 | 本文件开头 |

## 未完成与延期

- **CLI 的 Ctrl+C（FR-018、US4 场景 1 与 5，任务 T044 / T050 / T051）**：延期。拦截 SIGINT 需要 `sun.misc.Signal`，与 Checkstyle 的 IllegalImport 规则冲突；项目方 2026-11-21 决定本期不做。取消能力经核心层 `AgentService.cancel(turnId)` 交付并已验收。
- **真实模型手工演示**：US1 已由项目方在交互模式下实测（见 us1-demo.md，流式 + 工具调用 + 授权框经中转接口通过）；US2 的截断无法用真实模型触发（`primary` 中转接口不遵守 `max_completion_tokens`：上限 16 实际生成 509 个 token；DeepSeek 返回 402 余额不足），US4 的取消只能经测试；两项记为 NOT_RUN。

## 实施中对设计的调整

- **到期后的文字事件**：`message.started` / `message.delta` 在整轮到期后被拒绝（抛 `TurnDeadlineExceededException`，不使发布器失效），让不响应截止时间的模型流停下；过程事件仍可送达。已同步 data-model.md §3。
- **`possiblyExecuted` 的判定**：幂等工具失败时不提示可能已执行；第一次授权就被拒绝或取消的调用视为未执行（新增 `ToolExecutionScope.approvalGranted / approvalWithheld`）。已同步 contracts/run-events.md §3。
- **用户取消时工具的错误说明**：记为"已被用户取消"，不再显示"会话租约已失效"。
- **未经治理的回调**（只出现在 core 单元测试里）没有调用账本，由 `ToolExecutor` 按"跳过"补发一对事件，生产路径上所有工具都经 `GovernedToolCallback`。
- **既有测试的契约调整**：`ToolCapabilityMatrixIntegrationTest` 原先断言清理未确认时 `onToolResult` 不被调用；007 起工具结束事件按单个工具发出，改为断言收到状态与审计一致的 `tool.finished`。`ChatCommandTest` / `AgentOSCliTest` 的桩从 `process(request)` 改为 `process(request, sink)`。
