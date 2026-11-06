---
title: 总体架构
lang: zh-CN
description: AgentOS 的接入层、ReAct 引擎、共享能力和 PostgreSQL 工作区。
---

# 总体架构 {#architecture}

AgentOS 的目标是把多个业务 Agent 放在一个可治理的 Java 运行时里。CLI、REST 和定时任务共享同一个 `AgentService`，不会各自实现一套 Agent 逻辑。

## 分层架构 {#layers}

<img src="/diagrams/architecture.zh.svg" alt="AgentOS 分层架构" />

### 接入层 {#ingress}

- **CLI Channel**：本地交互与调试；
- **Web Service**：业务系统通过 REST 触发 Agent；
- **AgentScheduler**：按 `AGENT.md` 中的 cron 规则触发任务。

三者都进入 `AgentService.process(...)`。

### 引擎层 {#engine}

- `PromptBuilder` 组装系统指令、记忆、历史和当前用户消息；
- `ReActLoop` 自己驱动模型与工具之间的迭代；
- `ToolExecutor` 调用注入的 Spring AI `ToolCallingManager`，不创建第二个工具执行入口。

### 共享能力层 {#capabilities}

- `ProviderService`：按 provider name 显式映射 `ChatModel` 和独立的 `EmbeddingModel`；
- `MemoryService`：统一会话记忆与长期记忆；
- `ToolRegistry`：管理 `ToolCallback`，外层使用 `GovernedToolCallback` 做审计、超时、重试、截断和脱敏。

### 存储与工作区 {#storage}

PostgreSQL 保存会话、轮次、消息、调度状态和两类审计记录；pgvector 保存知识库、记忆和工具描述向量。`.AgentOS/` 保存用户可维护的 `AGENT.md`、Bootstrap、Skill、脚本和 Markdown 记忆。

## 模块边界 {#modules}

核心阶段包含 9 个 Maven 模块：

| 模块 | 职责 |
|---|---|
| `AgentOS-core` | 核心抽象、ReAct、Prompt、Turn、Scheduler 契约 |
| `AgentOS-provider` | ChatModel / EmbeddingModel 的编程式 Provider 映射 |
| `AgentOS-memory` | MemoryService 与长期记忆后端 |
| `AgentOS-tool` | ToolCallback、MCP、内置工具、Sandbox、通知 |
| `AgentOS-storage` | PostgreSQL、JPA、Flyway、审计和 ShedLock |
| `AgentOS-web` | Spring MVC REST API 与 OpenAPI |
| `AgentOS-channel-cli` | CLI Channel |
| `AgentOS-cli` | Picocli 根命令和独立 CLI JAR |
| `AgentOS-boot` | Spring Boot 启动和依赖聚合 |

契约放在 core，实现放在具体模块；新增 Channel 或 Tool 不修改核心引擎。

## 关键边界 {#boundaries}

1. 主链路不走 `ChatClient` 的自动工具循环；循环由 AgentOS 驱动，工具由注入的 `ToolCallingManager` 执行。
2. 不使用 ThreadLocal 传递会话和 Agent，上下文通过 `ToolContext` 传给工具。
3. `AGENT.md` 和 Skill 是上下文来源，不是 Tool；Skill 正文按需读取。
4. 事务不跨越 LLM 调用、工具调用或 HTTP 同步等待；`spring.jpa.open-in-view=false`。
5. PostgreSQL 是核心阶段唯一关系数据库，不支持 SQLite/H2。

## 设计依据 {#sources}

- [技术方案（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [需求文档（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
