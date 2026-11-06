---
title: 什么是 AgentOS
lang: zh-CN
description: AgentOS 是 Java 原生、私有部署、可审计的企业级 Agent OS。
---

# 什么是 AgentOS {#what-is-agentos}

> **当前状态：设计阶段。** 仓库已经有 Maven 多模块骨架、可执行 JAR 和 CLI 版本入口；本文介绍的是 AgentOS 1.0 的目标运行时，尚未实现的能力会明确标注。

## 一句话定义 {#definition}

**AgentOS 是 Java 原生、私有部署、可审计的企业级 Agent OS。**

它运行在企业自己的服务器或 K8s 上，在同一个进程中承载多个业务 Agent。每个 Agent 由一个目录定义，多个 Agent 共享模型接入、ReAct 引擎、记忆、工具、沙箱、调度和审计。

<img src="/diagrams/architecture.zh.svg" alt="AgentOS 目标架构：三个触发入口、统一 AgentService、自研 ReAct 引擎、共享能力和 PostgreSQL 工作区" />

## 为什么需要一个底座 {#why}

做出一个能聊天的 Agent 并不难。真正难的是让它长期、稳定、可追溯地运行在企业环境里：模型要能切换，工具要有边界，会话要能恢复，失败要能解释，所有调用要能审计。

没有统一底座时，每个业务团队都会重复建设：

- 一套模型 API 适配和重试逻辑；
- 一套工具注册、权限、超时和错误处理；
- 一套会话与长期记忆存储；
- 一套定时触发和后台任务管理；
- 一套日志、调用记录和敏感信息脱敏。

AgentOS 把这些公共能力下沉，业务方只需要定义 Agent 和接入工具。

## AgentOS 解决什么问题 {#problems}

### Agent 不是一次性脚本 {#problem-runtime}

业务 Agent 会持续运行，既可能被 CLI 或 REST 触发，也可能由 cron 到点触发。它需要统一的 Session、Turn、Lease 和超时模型，不能让每个入口各自实现一套执行逻辑。

### 工具调用不能默认失控 {#problem-governance}

模型只能提出工具调用请求，真正执行必须经过应用治理：白名单、I/O 超时、幂等重试、结果截断、脱敏和审计。AgentOS 的目标是让工具始终经过同一个受控入口。

### 企业需要私有、可追溯的运行环境 {#problem-enterprise}

模型和业务数据不应该被锁在某个 SaaS 平台里。AgentOS 使用 Spring Boot、Spring AI、PostgreSQL 和企业熟悉的 Java 运维体系，设计目标是让数据留在企业基础设施中。

## AgentOS 怎么解决 {#how}

### 一个目录定义一个 Agent {#directory-agent}

`.AgentOS/agents/<name>/AGENT.md` 的 frontmatter 描述 Provider、工具、渠道、定时规则和运行上限，正文描述任务指令。Skill、参考资料和脚本按需读取，不把整套资源一次性塞进上下文。

### 一个循环串起三种入口 {#one-loop}

CLI、REST 和 AgentScheduler 都进入 `AgentService.process(...)`，再由 `ReActLoop` 驱动：调用模型、执行工具、回填结果、继续迭代，直到模型给出最终回答或触达上限。

### Spring AI 负责协议，AgentOS 负责运行时 {#spring-ai-boundary}

主链路直接使用 Spring AI 2.0 的 `ChatModel`、`ToolCallback`、`ToolCallingManager`、`ToolContext` 和 `MessageAggregator`。循环由 AgentOS 自己驱动，工具由注入的 `ToolCallingManager` 执行，审计和治理由 `GovernedToolCallback` 完成。

## 它是什么，不是什么 {#positioning}

| AgentOS | 它解决什么 | 它不替代什么 |
|---|---|---|
| Agent OS 运行时 | 让多个业务 Agent 私有、持续、可审计地运行 | 不替代底层 LLM 服务 |
| Agent 配置底座 | 用目录和 Markdown 定义 Agent | 不要求业务方为每个 Agent 写后端服务 |
| 工具与记忆治理层 | 统一工具、会话、记忆、超时、审计 | 不把企业业务系统自动变成 Agent |
| REST/CLI 运行入口 | 让业务系统和人触发 Agent | 不做可视化 Workflow 编排 |

AgentOS 可以作为 Dify、Coze 等上层编排平台的运行时后端；也可以直接被业务系统调用。它和 Spring AI、LangChain4j 的关系是复用：框架提供底层能力，AgentOS 提供可运行的企业底座。

## 核心概念 {#concepts}

| 概念 | 含义 |
|---|---|
| Agent | 一个 `.AgentOS/agents/<name>/` 目录定义的业务智能体 |
| Profile | 从 `AGENT.md` frontmatter 派生出的运行配置 |
| Provider | 对聊天模型的统一命名和配置 |
| Turn | 一条用户消息到最终响应的一次处理 |
| Session | 保存会话消息、轮次和租约的上下文容器 |
| Tool | Agent 可以调用的能力，统一表达为 Spring AI `ToolCallback` |
| Skill | 可复用的上下文资源，不是 Tool，不进入 ToolRegistry |
| Memory | 会话记忆和长期记忆的统一门面 |
| Sandbox | 工具访问文件、命令和网络前的安全边界 |

## 目标能力 {#capabilities}

下表描述 1.0 设计目标，不表示所有能力当前已经实现：

| 能力 | 设计方案 | 当前状态 |
|---|---|---|
| LLM 接入 | Spring AI 2.0 `ChatModel` + Provider 显式映射 | 设计完成，主链路待实现 |
| ReAct 循环 | AgentOS 自研循环 + Spring AI 官方工具组件 | 设计完成，待实现 |
| 三层记忆 | PostgreSQL 会话、Markdown/PostgreSQL 长期记忆、pgvector 语义路 | 设计完成，待实现 |
| Tool 体系 | 9 个内置 Tool、MCP、`@Tool` Bean、治理装饰器 | 设计完成，待实现 |
| Web Service | 10 个核心 REST 端点 | 设计完成，待实现 |
| 定时触发 | CronTrigger + ShedLock + 轮次审计 | 设计完成，待实现 |
| 工程骨架 | Maven 9 模块、CLI `--help` / `--version` | 已建立 |

## 按角色继续阅读 {#continue}

- **Agent 作者**：先看 [定义业务 Agent](/docs/agent)，再看 [工具与安全](/docs/tools)。
- **平台与运维**：先看 [快速开始](/docs/quick-start)，再看 [模型与配置](/docs/models) 和 [CLI 与 REST 参考](/docs/reference)。
- **贡献者**：先看 [总体架构](/docs/architecture)，再看 [ReAct 与轮次](/docs/react-loop) 和 [路线图与贡献](/docs/roadmap)。

## 设计依据 {#sources}

本页依据：

- [行业调研（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/IndustryResearch.md)
- [需求文档（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
- [技术方案（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
