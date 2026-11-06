---
title: 工具与安全
lang: zh-CN
description: AgentOS 的 ToolCallback、MCP、Sandbox 与工具治理边界。
---

# 工具与安全 {#tools-security}

Tool 是 Agent 能调用的外部能力。AgentOS 统一使用 Spring AI 2.0 的 `ToolCallback`，不为内置工具、Java Bean 和 MCP 工具分别造执行模型。

## 一个工具执行入口 {#one-entry-point}

模型提出工具调用请求后，流程固定为：

```text
ChatModel → ToolExecutor → 注入的 ToolCallingManager → GovernedToolCallback → ToolCallback
```

主链路不使用 `ChatClient` 的 `ToolCallingAdvisor` 自动执行，也不使用 `ToolCallingManager.builder().build()` 自建 Manager。这样 `spring.ai.tools.limits.*`、异常处理器和观测配置才能统一生效。

## GovernedToolCallback {#governed}

每个 ToolCallback 外层套治理装饰器，负责：

- 从 `ToolContext` 取得 TurnContext 并检查截止时间；
- 执行前续租 Session；
- 应用工具级 I/O 超时；
- 只对声明为幂等的工具进行重试；
- 按 `max_result_chars` 截断返回结果；
- 使用 `Redactor` 脱敏 URL、Authorization、token、password 等字段；
- 写入 `tool_invocations`。

`notify`、`http_post`、`write_file` 和 `shell` 默认不自动重试，因为失败可能发生在副作用之后。

## 九个内置 Tool {#built-ins}

| 组 | Tool | 作用 |
|---|---|---|
| 文件 | `read_file` / `write_file` / `list_dir` | 受工作区路径白名单限制 |
| Shell | `shell` | 受可执行文件白名单和进程超时限制，不经 shell 解释 |
| HTTP | `http_get` / `http_post` | 受域名白名单和 HTTP 超时限制 |
| Memory | `save_memory` / `recall_memory` | 写入或查询长期记忆 |
| 通知 | `notify` | 从数据库按名称找到通知渠道并推送 |

## 三种扩展方式 {#extension-levels}

1. **零代码**：写 `AGENT.md`，复用现成 MCP server；
2. **轻代码**：用任意语言编写 MCP server；
3. **重代码**：在 Spring Bean 方法上使用 `@Tool` 和 `@ToolParam`。

MCP 工具、`@Tool` Bean 和内置工具最终都是 `ToolCallback`。注册到 Profile 前必须做名称冲突校验，因为直接调用 `ChatModel` 时 Spring AI 不会替你检查重复名称。

## MCP {#mcp}

AgentOS 使用 Spring AI 的 `spring-ai-starter-mcp-client` 对接 STDIO、SSE 和 Streamable-HTTP。客户端按配置编程式创建，初始化和 `tools/list` 有独立超时；某个 MCP server 失败不会阻塞应用启动，后台可以重连。

`TurnContext` 不能发送到 MCP server。内部上下文默认由 `ToolContextToMcpMetaConverter.noOp()` 截断，MCP 来源的治理装饰器还会移除 AgentOS 内部键。

## Sandbox {#sandbox}

核心阶段提供 `Sandbox` 接口和 `WhitelistSandbox`：

- 文件读写：标准化路径并检查允许目录，防止 `../` 穿越；
- Shell：精确匹配可执行文件，进程超时后强制销毁；
- HTTP：解析 host 并检查域名白名单；
- 通知：复用 HTTP 白名单，密钥只在配置中解析。

应用层白名单是误操作防线，不是运行不可信代码的完整隔离。容器和 microVM 是后续演进方向。

## 设计依据 {#sources}

- [技术方案第 6 章（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [需求文档 5.6（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
