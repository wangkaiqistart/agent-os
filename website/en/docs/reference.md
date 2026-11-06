---
title: CLI and REST reference
lang: en-US
description: Current CLI entry points and the AgentOS 1.0 target CLI and REST surface.
---

# CLI and REST reference {#reference}

This page separates what runs today from the 1.0 design. Planned business capabilities are not presented as live APIs.

## Available CLI today {#available-cli}

| Command | Status | Purpose |
|---|---|---|
| `java -jar AgentOS-cli-exec.jar --help` | Available | Print help |
| `java -jar AgentOS-cli-exec.jar --version` | Available | Print AgentOS, Spring Boot, Spring AI, and Java versions |
| `java -jar AgentOS.jar --help` | Available | Full application Picocli help |
| `java -jar AgentOS.jar --version` | Available | Full application version information |

## 1.0 target CLI {#target-cli}

These 12 subcommands come from the technical solution and are currently planned:

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

## 1.0 target REST API {#target-api}

| Method | Path | Status |
|---|---|---|
| `POST` | `/api/v1/sessions` | Planned |
| `POST` | `/api/v1/sessions/{id}/messages` | Planned |
| `GET` | `/api/v1/sessions/{id}` | Planned |
| `DELETE` | `/api/v1/sessions/{id}` | Planned |
| `POST` | `/api/v1/agents/{name}/invoke` | Planned |
| `GET` | `/api/v1/profiles` | Planned |
| `GET` | `/api/v1/memory` | Planned |
| `GET` | `/api/v1/tools` | Planned |
| `GET` | `/api/v1/health` | Planned |
| `GET` | `/api/v1/info` | Planned |

Target responses include Turn states: `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `LIMIT_EXCEEDED`, `MAX_ITERATIONS`, and `INTERRUPTED`. When synchronous HTTP waiting exceeds its budget, the API returns 202; when a Session already has a Turn, it returns 409.

## Configuration entry points {#configuration}

- `application.yaml`: application runtime configuration;
- `.env`: local secrets and external service addresses, never committed;
- `.AgentOS/agents/<name>/AGENT.md`: Agent Profile and instructions;
- `.AgentOS/mcp_servers.yaml`: MCP server configuration;
- PostgreSQL + Flyway: runtime state and audit records.

## Related pages {#related}

- [Quick start](/en/docs/quick-start)
- [Models and configuration](/en/docs/models)
- [ReAct and turns](/en/docs/react-loop)
