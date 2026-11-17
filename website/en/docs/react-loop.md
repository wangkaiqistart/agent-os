---
title: ReAct and turns
lang: en-US
description: Implemented ReAct, tool execution, session history, leases and auditing, with explicit extension boundaries.
---

# ReAct and turns {#react-loop}

AgentOS drives Reason → Act → Observe with its own `ReActLoop` and Spring AI 2.0 components. US-2 implements CLI chat, HTTP GET, session history, leases, timeouts and PostgreSQL auditing. Long-term memory, MCP, REST, scheduling and SSE remain future work. Validation uses isolated PostgreSQL; see the validation section for real-model and weather evidence.

## One turn {#one-turn}

1. Validate the Agent, Provider and tool list; atomically acquire a lease, start a turn and store its user message.
2. Build Agent instructions, Bootstrap, the data safety notice, date, complete history turns and the current message.
3. Call `ChatModel.call` or `stream` through the Provider and audit each attempt.
4. Save a final answer, or pass tool requests through the sole `ToolExecutor` to the injected `ToolCallingManager`.
5. Persist `in_progress` before executing each tool, update its outcome, append assistant/tool messages and iterate.
6. Persist the turn outcome, finish orphan tool records and release this owner's lease.

Tools run in response order, return results by call ID and are not automatically retried.

## Two limits {#limits}

| Limit | Behavior |
|---|---|
| `settings.max_iterations`, default 10 | Ends as `MAX_ITERATIONS` without an extra model summary request |
| `spring.ai.tools.limits.*` | Accumulates across iterations; skipped calls return an error and receive `limit_skipped` audit rows |

## Timeouts {#timeouts}

Model, tool and whole-turn timeouts share the remaining turn budget. The default turn lasts 300 seconds. At its deadline, no new external calls start; final in-flight audits, turn completion and lease cleanup share one final second. Database work and output callbacks consume this same budget.

HTTP synchronous waiting with a 202 response belongs to the future Web Service. Shell and MCP I/O limits belong to future tool implementations.

## Session leases {#lease}

One session runs one turn at a time. A second CLI message is rejected without writes. The owner renews before each iteration and tool call; the lease covers at least the turn deadline plus 30 seconds. Database validity uses actual time after acquiring locks.

Lease loss stops execution. Startup and expired-session takeover recover stale RUNNING turns and orphan tool records while preserving valid and new owners. A killed process may retain a valid lease until expiry. No heartbeat or periodic reaper is implemented yet.

## History and context {#history}

Complete turns satisfy both default limits:20 turns and 32000 estimated tokens. History uses 90% of the token budget; either limit set to 0 disables history. Failed turns contribute only user text and a safe explanation; stored records remain intact. Agent and Bootstrap files reload each turn.

`chat --new` archives an idle session and starts another. Old turns, messages and audits remain unchanged, and old history does not enter the new prompt. Long-term memory and Skill injection have placeholders only.

## Streaming {#streaming}

Tests cover `TurnSink` deltas, tool requests/results, completion and errors. The Provider uses `MessageAggregator`. CLI chat is synchronous and no production SSE API exists. A terminal callback runs at most once; notification failure does not change a confirmed outcome.

## Audit {#audit}

`llm_calls` records each model attempt. Tool records are created before execution and updated in place. Transient audit failures have bounded retries; stable keys prevent duplicates. Unknown commits are verified without replaying model or tool calls. Redaction runs before persistence; logs omit full prompts and tool arguments/results.

`CONFIRMED` means required audits, the terminal state and owner cleanup were confirmed; it does not imply business success. Persistent database failure stops execution and returns a turnId with `UNCONFIRMED`. Missing model audits are not guaranteed to be reconstructed.

## Validation {#validation}

Validated on 2026-11-17: real primary with local weather passed (2 model calls,1 tool execution,4 messages). Two separate CLI processes with real primary and met.no also passed; the second used `--new` (4 model calls,2 tool calls,8 messages total, with old records unchanged). These are measured samples; future real-model runs may fetch more than once. Scripted models check fixed exact counts. Consult the [repository validation record](https://github.com/wangkaiqistart/agent-os/blob/feat/us-2-react/specs/002-react-loop/quickstart.md) for PASS/NOT_RUN states and measured counts. A real Demo without recorded evidence is not considered passed.

## Design basis {#sources}

- [Technical solution (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Feature specification and contracts](https://github.com/wangkaiqistart/agent-os/tree/feat/us-2-react/specs/002-react-loop)
