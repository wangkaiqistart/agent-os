---
title: Tools and security
lang: en-US
description: ToolCallback, MCP, Sandbox, and governance boundaries in AgentOS.
---

# Tools and security {#tools-security}

A Tool is an external capability an Agent can call. AgentOS uses Spring AI 2.0 `ToolCallback` for built-in Tools, Java Beans, and MCP Tools instead of maintaining separate execution models.

## One execution entry point {#one-entry-point}

After the model requests a tool call, the path is fixed:

```text
ChatModel → ToolExecutor → injected ToolCallingManager → GovernedToolCallback → ToolCallback
```

The main path does not use `ChatClient`’s `ToolCallingAdvisor`, and it never creates a separate `ToolCallingManager` with `ToolCallingManager.builder().build()`. This keeps `spring.ai.tools.limits.*`, exception handling, and observations on one configured path.

## GovernedToolCallback {#governed}

Every ToolCallback is wrapped by a governance decorator that:

- reads TurnContext from `ToolContext` and checks the deadline;
- renews the Session lease before execution;
- applies a tool-level I/O timeout;
- retries only Tools declared idempotent;
- truncates results with `max_result_chars`;
- redacts URLs, Authorization, tokens, and passwords through `Redactor`;
- writes `tool_invocations`.

`notify`, `http_post`, `write_file`, and `shell` are not retried by default because a failure can happen after the side effect has already occurred.

## Nine built-in Tools {#built-ins}

| Group | Tools | Purpose |
|---|---|---|
| Files | `read_file` / `write_file` / `list_dir` | Restricted to workspace path allowlists |
| Shell | `shell` | Restricted executable allowlist; process timeout; no shell interpretation |
| HTTP | `http_get` / `http_post` | Domain allowlist and HTTP timeouts |
| Memory | `save_memory` / `recall_memory` | Write and query long-term memory |
| Notifications | `notify` | Resolve a named notification channel from the database |

## Three extension levels {#extension-levels}

1. **Zero code**: write `AGENT.md` and reuse an existing MCP server;
2. **Light code**: write an MCP server in any language;
3. **Full code**: annotate Spring Bean methods with `@Tool` and `@ToolParam`.

MCP Tools, `@Tool` Beans, and built-ins all become `ToolCallback`. Names must be validated before a Tool set is exposed to a Profile because direct `ChatModel` calls do not perform duplicate-name validation automatically.

## MCP {#mcp}

AgentOS uses Spring AI’s `spring-ai-starter-mcp-client` for STDIO, SSE, and Streamable-HTTP. Clients are created programmatically, with independent timeouts for initialization and `tools/list`. One unavailable MCP server must not block application startup; it can reconnect in the background.

`TurnContext` must not be sent to an MCP server. Internal context is removed with `ToolContextToMcpMetaConverter.noOp()`, and the governance wrapper removes AgentOS-private keys for MCP-originated callbacks as a second boundary.

## Sandbox {#sandbox}

The core stage provides a `Sandbox` contract and `WhitelistSandbox`:

- file access: normalize paths and check allowed roots to prevent `../` traversal;
- Shell: exact executable allowlist; forcibly destroy timed-out processes;
- HTTP: parse the host and check the domain allowlist;
- notifications: reuse the HTTP allowlist and resolve secrets only from configuration.

An application allowlist is an anti-misoperation boundary, not a full isolation layer for untrusted code. Containers and microVMs are future evolution paths.

## Design basis {#sources}

- [Technical solution, Chapter 6 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Requirements, Section 5.6 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
