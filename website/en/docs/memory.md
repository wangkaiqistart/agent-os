---
title: Memory and retrieval
lang: en-US
description: Conversational memory, long-term memory, Embeddings, and pgvector in AgentOS.
---

# Memory and retrieval {#memory}

AgentOS exposes one `MemoryService` so the ReAct engine does not need to understand Sessions, Markdown, PostgreSQL, and vector storage as separate concepts.

## Three layers of memory {#three-layers}

| Layer | Purpose | Core stage |
|---|---|---|
| Conversational | Complete messages and tool calls in the current Session | PostgreSQL, one message per row |
| Long-term | Preferences, facts, and project context across Sessions | `MEMORY.md` by default; PostgreSQL/Mem0 are alternatives |
| Episodic | Decisions and process learned during one task | Extension stage |

## Turn-based history {#turn-history}

Sessions use UUID primary keys. A partial unique index protects one active Session for a Channel, user, and Profile. Each message is stored in `session_messages`, using Spring AI’s `UserMessage`, `AssistantMessage`, and `ToolResponseMessage` shapes.

Prompts read recent complete Turns and obey both a Turn limit and a token budget. Failed Turns remain complete in audit storage, while later prompts keep only their user message and a failure explanation.

## Long-term memory partitions {#long-term}

The default file has two regions:

- **Core memory**: stable facts, never truncated and not searched;
- **Archival memory**: growing records, truncated by a character budget and used for keyword/semantic retrieval.

The Agent explicitly calls `save_memory`; each entry is size-limited. Memory is placed in a separate block at the end of the system prompt and labeled as data, not instructions, reducing the chance that external content becomes an instruction.

## Embeddings and pgvector {#embedding}

Embeddings are configured independently from the main model. The test environment uses Alibaba Cloud Model Studio’s OpenAI-compatible `text-embedding-v4` at 1024 dimensions. Three `PgVectorStore` instances serve:

- `kb_vectors`: knowledge chunks;
- `memory_vectors`: long-term memory;
- `tool_vectors`: tool-description search.

Flyway owns vector tables and HNSW indexes. Startup makes one real Embedding call to validate dimensions. Model Studio accepts at most 10 inputs per request, so batching limits both token count and item count.

When Embeddings are not configured or unavailable, long-term memory falls back to keyword and time retrieval. A failed index update must not block the source write.

## Why not ChatMemory Advisor? {#boundary}

The main path calls `ChatModel` directly instead of relying on `ChatClient`’s `ChatMemory` Advisor. This lets AgentOS preserve complete tool messages, truncate by Turn, represent failed Turns, and control database transaction boundaries. A future migration can be evaluated if an official Spring AI JDBC implementation meets those contracts.

## Design basis {#sources}

- [Technical solution, Chapter 5 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Technical solution, Sections 3.4 and 9.1 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
