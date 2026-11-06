---
title: ReAct and turns
lang: en-US
description: How AgentOS drives ReAct, tool execution, and audited Turns with Spring AI 2.0 components.
---

# ReAct and turns {#react-loop}

AgentOS does not hide the Agent loop behind a black box. `ReActLoop` drives Reason → Act → Observe itself while using Spring AI 2.0 components wherever possible.

## One Turn {#one-turn}

A Turn is the complete run from one user message to a final response:

1. Acquire a Session lease and create an `agent_turns` record;
2. assemble the `SystemMessage`, memory, recent history, and current `UserMessage`;
3. call `ChatModel.call(...)` or `stream(...)` directly;
4. save the assistant message and finish if there are no tool calls;
5. hand tool calls to the injected `ToolCallingManager`;
6. append the assistant tool request and tool result to `session_messages`, then iterate.

`ChatModel` returns model output but does not execute tools. The only tool path is `ToolExecutor` → injected `ToolCallingManager`.

## Two limits {#limits}

| Limit | Purpose |
|---|---|
| `max_iterations` | Caps model calls in one Turn; default 10 |
| `spring.ai.tools.limits.*` | Caps per-tool and total tool calls in the current Turn |

When a tool limit is reached, `RETURN_ERROR_RESPONSE` feeds the failure back as a tool result. When the iteration limit is reached, the Turn ends as `MAX_ITERATIONS`.

## Four timeouts {#timeouts}

- Per LLM call: Provider HTTP timeout;
- Per tool: the tool policy’s I/O timeout;
- Per Turn: `turn_timeout`, after which the Turn is `TIMED_OUT`;
- HTTP synchronous wait: return 202 when the wait budget expires while the background Turn continues.

Shell, HTTP, and MCP clients also set their own I/O timeouts. The governance wrapper is only a final safety net; it does not replace `destroyForcibly`, an HTTP read timeout, or MCP `requestTimeout`.

## Session leases {#lease}

Only one Turn may run in a Session at a time. A conditional database update acquires the lease; a conflict returns 409. The lease is renewed before every iteration and tool call. Losing the lease terminates the Turn. After a process crash, `TurnReaper` marks stale `RUNNING` Turns as `INTERRUPTED`.

Stateless `invoke` uses `invoke:<uuid>`. Scheduled tasks use `schedule:<scheduleId>`, so concurrent requests never share one active Session accidentally.

## History and context {#history}

Messages are stored one row at a time. History is truncated by complete Turns and constrained by both `max_history_turns` and `max_history_tokens`. A failed Turn contributes its user message and one failure explanation to later prompts, not incomplete tool requests.

Long-term memory is placed in a separate block at the end of the system prompt and labeled as data, not instructions. Tool results are limited by `max_result_chars`, and audit records are redacted before persistence.

## Streaming {#streaming}

`ChatModel.stream(...)` sends chunks to `TurnSink` and uses Spring AI’s `MessageAggregator` to produce a complete `ChatResponse`. The completed response goes through the same tool decision and execution path; SSE only needs an output adapter later.

## Audit {#audit}

Every model call writes an `llm_calls` row, including failures. Every tool call writes a `tool_invocations` row. Records include Session, Turn, iteration, Provider, actual model, token usage, tool status, retry count, and duration. `Redactor` removes webhook tokens and Authorization values before persistence.

## Design basis {#sources}

- [Technical solution, Chapter 4 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Technical solution, Chapters 7 and 9 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
