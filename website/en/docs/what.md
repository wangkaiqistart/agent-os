---
title: What is AgentOS
lang: en-US
description: AgentOS is a Java-native, private, auditable Agent OS for enterprise workloads.
---

# What is AgentOS {#what-is-agentos}

> **Current status: design stage.** The repository has a Maven multi-module skeleton, an executable JAR, and CLI version entry points. This page describes the target AgentOS 1.0 runtime; capabilities that are not implemented yet are labeled explicitly.

## One-line definition {#definition}

**AgentOS is a Java-native, private, auditable Agent OS for enterprise workloads.**

It runs on the company’s own servers or Kubernetes and hosts multiple business Agents in one process. Each Agent is defined by a directory. Agents share model access, a ReAct engine, memory, tools, sandboxing, scheduling, and audit trails.

<img src="/diagrams/architecture.en.svg" alt="AgentOS target architecture: three triggers, one AgentService, a self-owned ReAct engine, shared capabilities, PostgreSQL, and a workspace" />

## Why a shared runtime is needed {#why}

Making one Agent answer a prompt is not the hard part. Running Agents continuously and safely in an enterprise is: models must be replaceable, tools need boundaries, sessions must recover, failures need context, and every call needs an audit trail.

Without a shared runtime, every team rebuilds:

- model adapters and retry policies;
- tool registration, permissions, timeouts, and error handling;
- session and long-term memory storage;
- scheduled triggers and background execution;
- structured audit records and secret redaction.

AgentOS moves those concerns into one runtime so teams can focus on defining Agents and connecting business tools.

## What AgentOS addresses {#problems}

### Agents are long-running runtimes, not one-off scripts {#problem-runtime}

A business Agent can be triggered by the CLI, REST, or a cron schedule. It needs one consistent model for Sessions, Turns, Leases, and deadlines instead of three independent execution paths.

### Tool calls must not be ungoverned {#problem-governance}

A model may request a tool call, but the application must execute it through policy: allowlists, I/O timeouts, idempotent retries, result limits, redaction, and auditing. AgentOS is designed so every tool call passes through one governed entry point.

### Enterprises need private, traceable execution {#problem-enterprise}

Models and business data should not be locked inside a SaaS platform. AgentOS uses Spring Boot, Spring AI, PostgreSQL, and familiar Java operations so data can stay inside enterprise infrastructure.

## How AgentOS solves it {#how}

### One directory defines one Agent {#directory-agent}

`.AgentOS/agents/<name>/AGENT.md` describes the Provider, tools, channels, schedules, and runtime limits in frontmatter, while its body contains the task instructions. Skills, references, and scripts are loaded on demand rather than flooding every prompt.

### One loop connects three triggers {#one-loop}

CLI, REST, and AgentScheduler all enter `AgentService.process(...)`. The `ReActLoop` then calls the model, executes tools, feeds results back, and continues until the model answers or a limit is reached.

### Spring AI handles protocols; AgentOS owns the runtime {#spring-ai-boundary}

The main path uses Spring AI 2.0 `ChatModel`, `ToolCallback`, `ToolCallingManager`, `ToolContext`, and `MessageAggregator`. AgentOS drives the loop, the injected `ToolCallingManager` executes tools, and `GovernedToolCallback` applies governance and audit.

## What it is — and what it is not {#positioning}

| AgentOS | What it provides | What it does not replace |
|---|---|---|
| Agent OS runtime | Private, continuous, auditable execution for many Agents | The underlying LLM provider |
| Agent definition layer | Markdown and a directory instead of one backend service per Agent | Your business systems |
| Tool and memory governance | Shared sessions, memory, timeouts, tools, and audit | A business connector that has not been built |
| REST/CLI runtime entry points | Triggers for people and existing systems | Visual workflow orchestration |

AgentOS can sit underneath Dify or Coze as a runtime backend, or be called directly by an existing business system. Its relationship with Spring AI and LangChain4j is reuse: frameworks provide primitives, while AgentOS provides an executable enterprise runtime.

## Core concepts {#concepts}

| Concept | Meaning |
|---|---|
| Agent | A business Agent defined by `.AgentOS/agents/<name>/` |
| Profile | Runtime configuration derived from `AGENT.md` frontmatter |
| Provider | A named, replaceable chat-model configuration |
| Turn | One processing run from a user message to a final response |
| Session | A context container for messages, turns, and leases |
| Tool | An Agent capability represented by Spring AI `ToolCallback` |
| Skill | Reusable context, not a Tool and not part of ToolRegistry |
| Memory | The unified interface for conversational and long-term memory |
| Sandbox | The boundary checked before file, command, or network access |

## Target capabilities {#capabilities}

The table describes the 1.0 design target; it does not mean every capability is implemented today:

| Capability | Design | Current status |
|---|---|---|
| LLM access | Spring AI 2.0 `ChatModel` with explicit Provider mapping | Design complete; main path pending |
| ReAct loop | An AgentOS-owned loop using Spring AI components | Design complete; pending |
| Three-layer memory | PostgreSQL sessions, Markdown/PostgreSQL long-term memory, pgvector semantic retrieval | Design complete; pending |
| Tool system | 9 built-in Tools, MCP, `@Tool` Beans, and a governance wrapper | Design complete; pending |
| Web Service | 10 core REST endpoints | Design complete; pending |
| Scheduled triggers | CronTrigger, ShedLock, and audited Turns | Design complete; pending |
| Engineering skeleton | Maven 9 modules and CLI `--help` / `--version` | Established |

## Continue by role {#continue}

- **Agent authors**: start with [Define an Agent](/en/docs/agent), then read [Tools and security](/en/docs/tools).
- **Platform and operations**: start with [Quick start](/en/docs/quick-start), then read [Models and configuration](/en/docs/models) and [CLI and REST reference](/en/docs/reference).
- **Contributors**: start with [Architecture](/en/docs/architecture), then read [ReAct and turns](/en/docs/react-loop) and [Roadmap and contributing](/en/docs/roadmap).

## Design basis {#sources}

This page is based on the repository’s Chinese design documents:

- [Industry research (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/IndustryResearch.md)
- [Requirements (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
- [Technical solution (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
