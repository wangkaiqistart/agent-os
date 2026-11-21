# US4 演示：中途取消当前轮次

- 日期：2026-11-21
- 方式：PostgreSQL（Testcontainers）+ 真实 Manager + 治理链 + 本地模型 / HTTP 替身，从另一个线程调用 `AgentService.cancel(turnId)`；不依赖真实模型
- 命令：`mvn -B -ntp -pl AgentOS-boot -am -Dtest='TurnCancelIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`；核心层另有 `AgentServiceCancelTest`（含模型流式输出中取消）
- 原始输出：`AgentOS-boot/target/007-demo/cancel.txt`

**范围说明**：CLI 的 Ctrl+C 本期不做（项目方 2026-11-21 决定，原因是拦截 SIGINT 需要的 `sun.misc.Signal` 与 Checkstyle 的 IllegalImport 规则冲突）。取消能力经核心层入口 `AgentService.cancel(turnId)` 交付，后续网页停止按钮或终端方案直接复用。

## 应用级结果（`TurnCancelIntegrationTest`，3 个用例全部通过）

每个场景都断言：本轮返回 `CANCELLED`；取消后 2 秒内结束；取消后模型调用次数仍为 1；`agent_turns.status = CANCELLED`；被中止的工具 `tool_invocations.status = interrupted`；会话租约已释放；事件序列满足契约（末条为 `run.cancelled`）。

```text
## 等待确认中
取消到结束：46 ms；agent_turns.status=CANCELLED；tool_invocations.status=interrupted；返回文本：本轮已取消
1 run.started → 2 step.started → 3 step.finished → 4 tool.started → 5 approval.requested → 6 approval.resolved → 7 tool.finished → 8 run.cancelled

## 有副作用工具执行中
取消到结束：17 ms；agent_turns.status=CANCELLED；tool_invocations.status=interrupted；返回文本：本轮已取消；该操作可能已生效
1 run.started → 2 step.started → 3 step.finished → 4 tool.started → 5 approval.requested → 6 approval.resolved → 7 tool.finished → 8 run.cancelled

## 只读工具执行中
取消到结束：25 ms；agent_turns.status=CANCELLED；tool_invocations.status=interrupted；返回文本：本轮已取消
1 run.started → 2 step.started → 3 message.started → 4 message.delta → 5 message.ended → 6 step.finished → 7 tool.started → 8 tool.finished → 9 run.cancelled

```

- **等待确认中**：第一次授权就被取消，`write_file` 没有写入（`never.txt` 不存在），返回文本不提示"可能已生效"。
- **有副作用工具执行中**：已获授权的 `shell sleep 20` 被中止，返回文本与 `run.cancelled.possiblyExecuted` 都提示"该操作可能已生效"。
- **只读工具执行中**：`http_get` 被中止，不提示"可能已生效"；同一会话随后立即开始下一轮并成功。

## 核心层结果（`AgentServiceCancelTest`，4 个用例全部通过）

| 时机 | 结果 |
| --- | --- |
| 模型流式输出中 | 执行线程被中断，流式调用立即返回；`CANCELLED`、模型只调用 1 次、2 秒内结束；重复取消仍返回 true |
| 只读工具执行中 | `CANCELLED`，取消后不再调用模型 |
| 有副作用工具执行中（登记了未确认提交） | `CANCELLED`，文本含"提交可能已生效"，`run.cancelled.possiblyExecuted = true` |
| 未知或已结束的轮次 | `cancel` 返回 false |

## 事件契约中的取消场景

`RunEventContractIntegrationTest.userCancelEndsCancelled`（第 10 类场景）：只读工具中途取消，工具结束事件状态 `INTERRUPTED`，终态 `run.cancelled`，见 `AgentOS-boot/target/007-demo/run-events/10-cancelled.txt`。
