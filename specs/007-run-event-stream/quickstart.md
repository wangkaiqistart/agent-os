# Quickstart：007 验收与演示指南

本指南列出证明 007 可用的验证路径。设计依据见 [plan.md](./plan.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)。

## 1. 前置条件

- JDK 21、Maven、Docker（应用级测试用 `pgvector/pgvector:pg17` Testcontainers）。
- 默认测试使用脚本化模型（core 的桩 `ProviderService`、provider 模块的 `StubModelServer`），不连接共享库、不调用真实模型。
- 真实模型演示只在本地 `.env` 已配置 `AGENTOS_LLM_*` 时进行，不输出任何环境变量值。

## 2. 定向测试

实施时建立以下同名测试类，不允许"指定测试不存在"被当作通过：

```bash
# 事件契约、序号、终态、截断/过滤、取消状态映射、上下文说明
mvn -B -ntp -pl AgentOS-provider -am -Dtest='RetryPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test   # 截断 / 过滤不触发 Provider 重试
mvn -B -ntp -pl AgentOS-core -am -Dtest='TurnEventPublisherTest,TurnSinkCompatibilityTest,ReActLoopEventsTest,ReActLoopFinishReasonTest,AgentServiceCancelTest,PromptHistoryTest' -Dsurefire.failIfNoSpecifiedTests=false test

# 单个工具事件、确认 / 提问事件、取消时的工具状态
mvn -B -ntp -pl AgentOS-tool -am -Dtest='GovernedToolEventsTest,ToolApprovalGateTest,AskUserToolsEventsTest' -Dsurefire.failIfNoSpecifiedTests=false test

# 终端渲染
mvn -B -ntp -pl AgentOS-channel-cli -am -Dtest='TerminalTurnRendererTest' -Dsurefire.failIfNoSpecifiedTests=false test

# chat 装配、Ctrl+C 取消、--message 输出不变
mvn -B -ntp -pl AgentOS-cli -am -Dtest='ChatCommandStreamingTest,ChatCommandTest' -Dsurefire.failIfNoSpecifiedTests=false test

# 应用级：PostgreSQL + 真实 Manager + 治理链，10 类场景的事件序列，CANCELLED 落库
mvn -B -ntp -pl AgentOS-boot -am -Dtest='RunEventContractIntegrationTest,TurnCancelIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

## 3. 按用户故事验收

| 故事 | 验证方式 | 通过标准 |
| --- | --- | --- |
| US1 CLI 实时显示 | `TerminalTurnRendererTest` 喂入事件序列，断言输出文本；`ChatCommandStreamingTest` 用桩模型分段返回并注入延迟 | 第一段文字到达后 1 秒内出现在输出中（SC-001）；工具行顺序与执行顺序一致；回答不重复打印；`--message` 标准输出与改动前逐字节一致（SC-006） |
| US2 截断 / 过滤 | `ReActLoopFinishReasonTest`：桩模型返回 `length` / `LENGTH` / `content_filter`，同步与流式各一次，含"截断 + 工具调用"组合 | 状态 `FAILED`、错误码正确；工具真实执行 0 次；`session_messages` 无该轮助手消息；下一轮 prompt 中只出现固定说明（SC-003） |
| US3 事件契约 | `RunEventContractIntegrationTest` 跑 10 类场景（成功、工具失败、确认被拒、无人值守拒绝、超时、迭代上限、工具次数超限、截断、过滤、取消），用记录型 `TurnSink` 校验 | 每类都满足 [contracts/run-events.md §2](./contracts/run-events.md) 的顺序语法；`seq` 从 1 连续；终态唯一且在最后（SC-002）；模型请求的每个工具调用恰好一对开始 / 结束（SC-004）；注入密钥样本后事件中原始密钥出现 0 次（SC-007） |
| US4 取消 | `AgentServiceCancelTest`（单元）与 `TurnCancelIntegrationTest`（PostgreSQL），在模型输出中、只读工具中、有副作用工具中、等待确认中四个时机调用 `AgentService.cancel(turnId)` | 模型输出中、只读工具中、等待确认中三个时机 2 秒内结束（SC-005）；取消后新的模型调用、工具调用次数为 0；`agent_turns.status = CANCELLED`；有副作用工具场景终态带"可能已生效"；等待确认场景工具真实执行 0 次；取消与完成竞态时库内与事件终态一致 |

## 4. 演示

每个故事都有一份不依赖真实模型的演示记录，由测试捕获的输出整理而成：`us1-demo.md`（chat 终端输出）、`us2-demo.md`（截断 / 过滤样本）、`us3-demo.md`（事件序列）、`us4-demo.md`（四个取消时机）。生成方式见 tasks.md 的 T022、T035、T041、T052。

以下手工演示为可选，需要真实模型；结果追加到对应的演示记录，未做记 NOT_RUN。

```bash
mvn -B -ntp -DskipTests package
java -jar AgentOS-boot/target/AgentOS.jar chat
```

1. 输入一个需要调用工具的问题，观察文字边生成边显示、工具行与确认提示的顺序。
2. 空闲或执行中按 Ctrl+C，确认与改动前一样退出（CLI 取消本期延期）。
3. 在 Agent 的 `AGENT.md` 里把 `max_output_tokens` 设为很小的值，确认本轮以"回答因长度上限被截断"结束。

## 5. 完整质量门

```bash
mvn -B -ntp verify
```

全部通过，既有门禁不放宽（SC-008）。

## 6. 已知限制

- 同步（非流式）模型调用期间取消，网络读取不一定立即中断，最长等到单次请求超时或 2 秒 hard stop；交互式 chat 使用流式调用，不受影响（research R7）。
- 事件不持久化：进程重启或 CLI 退出后无法重放；断线重放随 US-5 的 Web 流式接口交付。
- 在等待确认时取消，006 的输入 broker 仍持有当前这一行：取消后用户输入的第一行会被当作迟到回答丢弃，并提示"请重新输入"。这与授权超时的现有行为一致，留待后续改进 broker。
- CLI 的 Ctrl+C 取消本期不做（项目方决定，见 spec Clarifications）：Ctrl+C 仍然直接退出 chat；取消能力只经核心层 `AgentService.cancel(turnId)` 提供，由测试与后续入口调用。
