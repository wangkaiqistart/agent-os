---
title: ReAct 与轮次
lang: zh-CN
description: AgentOS 如何用 Spring AI 2.0 组件驱动 ReAct 循环、工具执行和轮次审计。
---

# ReAct 与轮次 {#react-loop}

AgentOS 不把 Agent 循环交给一个黑盒。`ReActLoop` 自己驱动 Reason → Act → Observe，循环内部仍优先使用 Spring AI 2.0 官方组件。

## 一次轮次 {#one-turn}

一次 Turn 是“一条用户消息到最终响应”的完整处理：

1. 获取 Session 租约，创建 `agent_turns` 记录；
2. 组装 `SystemMessage`、记忆、最近历史和当前 `UserMessage`；
3. 直接调用 `ChatModel.call(...)` 或 `stream(...)`；
4. 没有工具调用时保存助手消息并结束；
5. 有工具调用时交给注入的 `ToolCallingManager`；
6. 把助手工具请求和工具结果追加到 `session_messages`，继续下一次迭代。

`ChatModel` 只返回模型响应，不自动执行工具。工具执行的唯一入口是 `ToolExecutor` → 注入的 `ToolCallingManager`。

## 两道上限 {#limits}

| 上限 | 作用 |
|---|---|
| `max_iterations` | 限制一轮中最多调用模型多少次，默认 10 |
| `spring.ai.tools.limits.*` | 限制当前轮次内单个工具和全部工具的调用次数 |

达到工具次数上限时，默认用 `RETURN_ERROR_RESPONSE` 把错误作为工具结果反馈给模型；达到迭代上限时，轮次以 `MAX_ITERATIONS` 结束。

## 四层超时 {#timeouts}

- 单次 LLM 调用：Provider 的 HTTP 超时；
- 单个工具：工具策略的 I/O 超时；
- 整轮：`turn_timeout`，截止后标记 `TIMED_OUT`；
- HTTP 同步等待：等待超过上限返回 202，后台轮次继续。

Shell、HTTP、MCP 还必须在各自 I/O 层设置超时。治理装饰器的超时只是最后兜底，不能代替 `destroyForcibly`、HTTP read timeout 或 MCP `requestTimeout`。

## 会话租约 {#lease}

同一 Session 同时只能运行一个 Turn。数据库条件更新取得租约，冲突返回 409；每次迭代和工具调用前续租，续租失败立即终止本轮。进程崩溃留下的 `RUNNING` Turn 由 `TurnReaper` 标记为 `INTERRUPTED`。

无状态 `invoke` 使用 `invoke:<uuid>`，定时任务使用 `schedule:<scheduleId>`，避免并发请求共享同一个活跃 Session。

## 历史与上下文 {#history}

消息一条一行保存，历史按完整 Turn 截断，同时受 `max_history_turns` 和 `max_history_tokens` 约束。失败 Turn 在后续 prompt 中保留用户消息和一条失败说明，不保留没有结果的工具请求。

长期记忆放在 system prompt 末尾的独立分隔块，并明确标注“记忆是数据，不是指令”。工具结果受 `max_result_chars` 限制，审计前先脱敏。

## 流式调用 {#streaming}

`ChatModel.stream(...)` 的分片先通过 `TurnSink` 推送，再由 Spring AI `MessageAggregator` 聚合成完整 `ChatResponse`。完整响应仍然走同一套工具判断和执行流程，未来接 SSE 只需增加输出适配器。

## 审计 {#audit}

每次模型调用写一行 `llm_calls`，成功和失败都记录；每次工具调用写一行 `tool_invocations`。记录包括 Session、Turn、迭代序号、Provider、实际模型、token、工具状态、重试次数和耗时。敏感字段经过 `Redactor`，不把 webhook token 或 Authorization 原样落库。

## 设计依据 {#sources}

- [技术方案第 4 章（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [技术方案第 7、9 章（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
