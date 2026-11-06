---
title: CLI 与 REST 参考
lang: zh-CN
description: AgentOS 当前可运行入口与 1.0 目标 CLI、REST 参考。
---

# CLI 与 REST 参考 {#reference}

本页把“当前可用”和“设计目标”分开。业务能力尚未实现时，不把目标接口写成已上线接口。

## 当前可用 CLI {#available-cli}

| 命令 | 状态 | 作用 |
|---|---|---|
| `java -jar AgentOS-cli-exec.jar --help` | 可用 | 显示帮助 |
| `java -jar AgentOS-cli-exec.jar --version` | 可用 | 显示 AgentOS、Spring Boot、Spring AI、Java 版本 |
| `java -jar AgentOS.jar --help` | 可用 | 完整应用的 Picocli 帮助 |
| `java -jar AgentOS.jar --version` | 可用 | 完整应用的版本信息 |

## 1.0 目标 CLI {#target-cli}

以下 12 个子命令来自技术方案，当前属于规划：

```text
init
status
chat [--profile <name>]
serve
gateway
profile list
profile create <name>
profile show <name>
profile delete <name>
provider list
tool list
session list
```

## 1.0 目标 REST API {#target-api}

| 方法 | 路径 | 状态 |
|---|---|---|
| `POST` | `/api/v1/sessions` | 规划 |
| `POST` | `/api/v1/sessions/{id}/messages` | 规划 |
| `GET` | `/api/v1/sessions/{id}` | 规划 |
| `DELETE` | `/api/v1/sessions/{id}` | 规划 |
| `POST` | `/api/v1/agents/{name}/invoke` | 规划 |
| `GET` | `/api/v1/profiles` | 规划 |
| `GET` | `/api/v1/memory` | 规划 |
| `GET` | `/api/v1/tools` | 规划 |
| `GET` | `/api/v1/health` | 规划 |
| `GET` | `/api/v1/info` | 规划 |

目标响应还包含 Turn 状态：`RUNNING`、`SUCCEEDED`、`FAILED`、`TIMED_OUT`、`LIMIT_EXCEEDED`、`MAX_ITERATIONS` 和 `INTERRUPTED`。HTTP 同步等待超过预算时返回 202；同一 Session 已有 Turn 时返回 409。

## 配置入口 {#configuration}

- `application.yaml`：应用运行配置；
- `.env`：本地秘密和外部服务地址，不提交；
- `.AgentOS/agents/<name>/AGENT.md`：Agent 的 Profile 与指令；
- `.AgentOS/mcp_servers.yaml`：MCP server 配置；
- PostgreSQL + Flyway：运行状态与审计数据。

## 相关页面 {#related}

- [快速开始](/docs/quick-start)
- [模型与配置](/docs/models)
- [ReAct 与轮次](/docs/react-loop)
