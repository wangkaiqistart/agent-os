---
title: Architecture
lang: en-US
description: AgentOS ingress, ReAct engine, shared capabilities, PostgreSQL, and workspace.
---

# Architecture {#architecture}

AgentOS is designed to place many business Agents inside one governed Java runtime. CLI, REST, and scheduled triggers share one `AgentService` instead of implementing three separate Agent runtimes.

## Layered view {#layers}

<img src="/diagrams/architecture.en.svg" alt="AgentOS layered architecture" />

### Ingress layer {#ingress}

- **CLI Channel**: local interaction and debugging;
- **Web Service**: REST triggers from existing business systems;
- **AgentScheduler**: cron-driven Turns declared in `AGENT.md`.

All three enter `AgentService.process(...)`.

### Engine layer {#engine}

- `PromptBuilder` assembles system instructions, memory, history, and the current user message;
- `ReActLoop` owns the iteration between the model and tools;
- `ToolExecutor` invokes the injected Spring AI `ToolCallingManager` rather than creating a second tool entry point.

### Shared capabilities {#capabilities}

- `ProviderService`: explicit provider-name mappings for `ChatModel` and an independent `EmbeddingModel`;
- `MemoryService`: one interface for conversational and long-term memory;
- `ToolRegistry`: manages `ToolCallback` instances wrapped by `GovernedToolCallback` for audit, timeouts, retries, truncation, and redaction.

### Storage and workspace {#storage}

PostgreSQL stores sessions, turns, messages, scheduling state, and both audit streams. pgvector stores knowledge, memory, and tool-description vectors. `.AgentOS/` stores user-maintained `AGENT.md`, Bootstrap files, Skills, scripts, and Markdown memory.

## Module boundaries {#modules}

The core stage contains 9 Maven modules:

| Module | Responsibility |
|---|---|
| `AgentOS-core` | Core abstractions, ReAct, prompts, Turns, and Scheduler contracts |
| `AgentOS-provider` | Programmatic ChatModel / EmbeddingModel provider mapping |
| `AgentOS-memory` | MemoryService and long-term memory backends |
| `AgentOS-tool` | ToolCallback, MCP, built-in tools, Sandbox, and notifications |
| `AgentOS-storage` | PostgreSQL, JPA, Flyway, audit, and ShedLock |
| `AgentOS-web` | Spring MVC REST API and OpenAPI |
| `AgentOS-channel-cli` | CLI Channel |
| `AgentOS-cli` | Picocli root command and standalone CLI JAR |
| `AgentOS-boot` | Spring Boot startup and dependency aggregation |

Contracts live in core; implementations live in concrete modules. New Channels and Tools should extend the edges, not the core engine.

## Important boundaries {#boundaries}

1. The main path does not use `ChatClient`’s automatic tool loop. AgentOS drives the loop and the injected `ToolCallingManager` executes tools.
2. Session and Agent context is passed through `ToolContext`, not ThreadLocal.
3. `AGENT.md` and Skills are context sources, not Tools; Skill bodies are read on demand.
4. Transactions never span an LLM call, tool call, or synchronous HTTP wait; `spring.jpa.open-in-view=false`.
5. PostgreSQL is the only relational database in the core stage; SQLite/H2 are not supported.

## Design basis {#sources}

- [Technical solution (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Requirements (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
