# Implementation Plan: AgentOS 工具注册与安全执行

**Branch**: `004-agentos-tool-system` | **Date**: 2026-11-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-agentos-tool-system/spec.md`

## Summary

本功能把 Agent 声明的工具名称收敛为最小可见集合，并通过唯一工具执行入口完成调用标识关联、租约与预算检查、执行前审计、沙箱授权、受限执行、结果脱敏和执行后审计。首期保留内置 `http_get`，使用现有 Spring AI `ToolCallback` 契约，不在循环外建立第二套工具协议。

未知工具配置由 core 的 `UnknownAgentToolException` 表达，提示只包含 Agent 相对配置路径、字段与工具名；tool 目录负责产生该错误，CLI 按专用类型展示提示，不转发其他异常原文或 cause。真实 CLI 与 PostgreSQL 集成测试同时验证错误定位、零模型请求及零新增会话/轮次/消息/审计记录。

技术方案沿用当前仓库已落地的模块边界：`AgentOS-core` 只持有 `ToolCatalog`、`Sandbox`、`ToolInvocationRecorder` 等端口；`AgentOS-tool` 提供目录、治理回调、HTTP 工具和白名单沙箱；`AgentOS-storage` 以 PostgreSQL 的 `tool_invocations` 表记录意图与终态；`AgentOS-boot` 负责官方工具管理器的装配验证和集成验收。工具循环继续由 `ReActLoop` 驱动，实际执行只委托注入的官方 `ToolCallingManager`。

## Technical Context

**Language/Version**: JDK 21；Spring Boot 4.x；Spring AI 2.0.x；Maven 多模块单体

**Primary Dependencies**: Spring AI `ToolCallback`、`ToolCallingManager`、`ToolCallingChatOptions`；JDK `HttpClient`；Spring Boot 配置绑定；JPA/Flyway；Jackson JSON

**Storage**: PostgreSQL 16+，使用 Flyway 管理 `tool_invocations`；不使用 H2、SQLite 或共享数据库

**Testing**: Maven Surefire/Failsafe；JUnit 5；Mockito 仅用于隔离端口；Testcontainers `pgvector/pgvector:pg17` 用于数据库集成；本地 `HttpServer` 用于 HTTP 替身；真实模型测试只在显式 `llm` profile 下运行

**Target Platform**: 支持 JDK 21 的 Linux/macOS 服务进程；可由 CLI、Web 或定时入口触发同一 AgentOS 运行时

**Project Type**: Maven 多模块后端单体应用，包含 core、provider、storage、tool、channel 和 boot 模块

**Performance Goals**: 默认整轮预算 30 秒；模型最多 10 次；单工具最多 10 次；本轮工具总数最多 30 次；HTTP 最多 3 次重定向、响应正文最多 1 MiB；普通工具结果默认最多 10000 个字符；幂等工具最多自动重试 1 次；截止时间验收使用 5 类场景各 20 次本地替身运行，p95 在预算加 1 秒内结束

**Constraints**: 工具必须经唯一的注入式 `ToolCallingManager` 执行；禁止 `ToolCallingAdvisor`、`ThreadLocal` 业务上下文和自行构造 `ToolCallingManager`；副作用前必须确认审计；审计和日志不得输出 prompt、凭证、完整工具参数或未受限响应正文；默认 HTTP 白名单为空

**Scale/Scope**: 每个 Agent 只暴露其显式声明的工具子集；首期包含一个内置 HTTP GET 工具和可复用的内部 `ToolCallback` 注册契约；不包含复杂 Tool Policy、并行工具执行、容器级沙箱或远程工具协议客户端

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Result | Evidence |
|------|--------|----------|
| 核心模块边界 | PASS | core 只依赖端口；具体工具在 `AgentOS-tool`；存储在 `AgentOS-storage` |
| 唯一工具执行入口 | PASS | `ToolExecutor` 只调用注入的官方 `ToolCallingManager.executeToolCalls`；不使用 `ToolCallingAdvisor` |
| ToolCallback 统一抽象 | PASS | 内置工具和内部扩展都以 `ToolCallback` 进入 `ToolCatalog`，治理由 `GovernedToolCallback` 统一收口 |
| Sandbox 先行 | PASS | `WhitelistSandbox.enforce` 在 HTTP 请求前逐跳执行；默认拒绝未授权域名 |
| 审计 day one | PASS | `ToolInvocationRecorder` 在执行前写 `in_progress`，执行后更新同一行；表由 PostgreSQL/Flyway 管理 |
| 时间与调用上限 | PASS | `ReActLoop` 的迭代上限、官方工具调用限次、共享 `TurnExecutionBudget` 和 HTTP 正文截止时间共同生效 |
| 密钥和敏感数据 | PASS | 配置只使用环境变量占位；`Redactor`、结构化日志和审计状态禁止回显敏感载荷 |
| 测试与质量门 | PASS | 单元、模块集成和 PostgreSQL Testcontainers 路径已定义；最终质量门为 `mvn -B -ntp verify` |
| 范围控制 | PASS | 本计划不引入复杂策略、并行执行、容器隔离或远程工具协议客户端 |

所有门禁通过，无需复杂度豁免。

## Project Structure

### Documentation (this feature)

```text
specs/004-agentos-tool-system/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── tool-governance.md
│   ├── tool-registry.md
│   ├── http-get.md
│   └── audit-lifecycle.md
└── tasks.md                 # 由 $speckit-tasks 生成
```

### Source Code (repository root)

```text
AgentOS-core/
├── src/main/java/com/agentos/core/tool/
│   ├── ToolCatalog.java
│   ├── UnknownAgentToolException.java
│   ├── Sandbox.java
│   ├── SandboxAction.java
│   └── Redactor.java
├── src/main/java/com/agentos/core/audit/
│   ├── ToolInvocationRecord.java
│   └── ToolInvocationRecorder.java
└── src/main/java/com/agentos/core/turn/
    ├── ReActLoop.java
    ├── ToolExecutor.java
    ├── ToolCallLedger.java
    ├── TurnExecutionBudget.java
    └── ToolContextKeys.java

AgentOS-tool/
├── src/main/java/com/agentos/tool/
│   ├── ToolAutoConfiguration.java
│   ├── registry/ToolRegistry.java
│   ├── govern/GovernedToolCallback.java
│   ├── govern/UnknownToolResolver.java
│   ├── govern/AgentOsToolExecutionExceptionProcessor.java
│   ├── sandbox/WhitelistSandbox.java
│   └── http/
│       ├── HttpTools.java
│       └── HttpToolProperties.java
└── src/test/java/com/agentos/tool/
    ├── registry/ToolRegistryTest.java
    ├── govern/GovernedToolCallbackTest.java
    ├── govern/AuditFailureTest.java
    ├── sandbox/WhitelistSandboxTest.java
    ├── http/HttpToolsTest.java
    └── http/HttpToolsDeadlineTest.java

AgentOS-storage/
├── src/main/java/com/agentos/storage/session/
│   ├── ToolInvocationEntity.java
│   └── ToolInvocationRepository.java
├── src/main/java/com/agentos/storage/audit/
│   ├── JpaToolInvocationRecorder.java
│   └── AuditTransactions.java
└── src/main/resources/db/migration/
    ├── V1__core_tables.sql
    └── V2__tool_invocation_unconfirmed_status.sql

AgentOS-boot/
├── src/main/resources/application.yaml
└── src/test/java/com/agentos/boot/
    ├── ReActApplicationTest.java
    ├── ToolAuditRedactionTest.java
    ├── ArchitectureRulesTest.java
    └── smoke/ReActWeatherSmokeTest.java
```

**Structure Decision**: 采用现有 Maven 多模块单体结构。core 定义稳定端口和运行上下文，tool 只实现工具与治理，storage 负责可查询审计，boot 验证自动配置、模块依赖和真实 PostgreSQL 闭环。新增内部工具只需提供统一回调、注册、Agent 授权和独立测试，不修改 `ReActLoop` 的控制逻辑。

## Phase 0: Research

研究结论见 [research.md](./research.md)。已解决 Spring AI 工具执行边界、默认限次、HTTP 截止时间与正文上限、PostgreSQL 审计事务和扩展范围等设计问题。

## Phase 1: Design & Contracts

设计产物如下：

- [data-model.md](./data-model.md)：工具定义、授权集合、调用账本、预算、审计记录、沙箱策略与结果状态。
- [contracts/tool-governance.md](./contracts/tool-governance.md)：统一执行入口、ToolContext、治理状态和错误传播。
- [contracts/tool-registry.md](./contracts/tool-registry.md)：注册、校验、Agent 过滤和未知工具拒绝。
- [contracts/http-get.md](./contracts/http-get.md)：URL、白名单、重定向、响应大小和超时行为。
- [contracts/audit-lifecycle.md](./contracts/audit-lifecycle.md)：执行前/执行后审计、幂等键、恢复和持久化未确认语义。
- [quickstart.md](./quickstart.md)：本地替身、PostgreSQL Testcontainers、模块测试、全链路验收和可选真实模型冒烟。

## Post-Design Constitution Check

| Gate | Result | Design evidence |
|------|--------|-----------------|
| 依赖倒置和模块职责 | PASS | contract 只引用 core 端口；实现路径与模块目录一一对应 |
| 唯一 Manager 和治理回调 | PASS | contracts/tool-governance.md 明确禁止直接 delegate、Advisor 和自建 Manager |
| 审计先于副作用 | PASS | audit-lifecycle.md 定义 begin 确认失败时零执行；complete 未确认进入 `unconfirmed` 并禁止重试；仅明确瞬时失败且幂等的工具允许最多 1 次重试 |
| 共享截止时间 | PASS | http-get.md 和 tool-governance.md 都从同一轮预算计算剩余时间，持续分片不能续命 |
| 默认拒绝与最小权限 | PASS | tool-registry.md、http-get.md 规定空集合/空白名单默认拒绝 |
| 脱敏与安全输出 | PASS | 所有结果、错误、日志和审计摘要均经脱敏；普通工具结果默认限制为 10000 个字符并保留截断元数据 |
| PostgreSQL 与测试质量门 | PASS | quickstart.md 使用 pgvector Testcontainers；默认验证不访问真实外部服务 |
| 范围控制 | PASS | 远程工具协议、复杂策略、并行执行和容器隔离均明确延期 |

设计没有引入宪章例外，可进入 `$speckit-tasks`。

## Complexity Tracking

无宪章违规，无需复杂度记录。
