# Implementation Plan: AgentOS CLI 入口与会话

**Branch**: `003-agentos-cli` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-agentos-cli/spec.md`

## Summary

为 AgentOS CLI 固化离线初始化、Agent 选择、延迟启动聊天、OS 账号会话身份、交互输入、`--new` 和稳定退出码。现有 `AgentOS-cli`、`AgentOS-boot`、`AgentOS-core` 和 `AgentOS-storage` 已提供大部分实现骨架，本计划以当前规格的 5 项澄清为权威，重点修正与旧 `002-react-loop` 契约的模型来源/Provider 默认冲突，并补齐行为测试和文档契约。

实现继续保持 CLI 只做适配：消息统一提交 `AgentService.process(...)`，会话与审计继续由 PostgreSQL 运行时保存，Spring 上下文只通过 `ContextLauncher` 在 `chat` 需要时启动。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.x、Spring AI 2.0.x、Picocli 4.7.7、SnakeYAML、JUnit 5、AssertJ、Testcontainers

**Storage**: 本地工作区文件系统 + PostgreSQL 16+/pgvector 现有 session/turn/audit 存储；不新增 CLI 专用表

**Testing**: Maven Surefire/JUnit 5；文件行为测试使用临时目录和注入依赖；装配测试使用 `pgvector/pgvector:pg17` 与本地 HTTP 模型替身；真实模型仅显式 `llm` profile；跨模块验收统计由 Python 标准库脚本输出 JSON/Markdown，不读取真实密钥

**Target Platform**: JDK 21 JVM 命令行；`AgentOS-boot` 可执行 JAR 和独立 CLI exec JAR

**Project Type**: 多模块单体中的 CLI 入口与运行时适配

**Performance Goals**: 帮助、版本和初始化不启动 Spring/数据库/Provider；固定验收环境预热后 20 次初始化至少 19 次在 2 秒内完成；交互使用单个运行时上下文，不为每行输入重复启动

**Constraints**: 初始化不读环境变量或 `.env` 中的模型回退，必须显式 `--model`；全局 `--config` 为 CLI 与 Boot 共用配置入口，显式路径失败不回退，未指定时按固定本地路径顺序查找；默认 Provider 写入 `primary`；显式 Provider 只按本地非敏感配置投影校验，不启动 Spring、不联网；不得落盘凭据；使用 OS 账号作为 userId；退出码固定为 0/1/2/3；不得绕过统一 AgentService、会话租约和审计；不新增数据库表

**Scale/Scope**: 单工作区、多 Agent、本地 CLI 用户；同一 `(cli, OS account, Agent)` 同时只允许一个运行轮次；本功能不实现 Profile 管理、Provider 管理、Tool 管理、REST、gateway 或定时入口

## Constitution Check

*GATE: Must pass before Phase 0 research and again after Phase 1 design.*

| Principle / Gate | Status | Evidence / Plan |
|---|---|---|
| JDK 21 + Boot 4.x + Spring AI 2.0.x，Maven Central only | PASS | 使用现有 Maven 多模块和已锁定版本，不新增厂商 SDK |
| 五大核心能力优先，范围不提前扩展 | PASS | 只规划 CLI 入口适配；不实现 REST、MCP、Tool Policy、SSO 或多租户 |
| 循环由运行时驱动，CLI 不实现模型/工具循环 | PASS | `ChatCommand` 只构造 `ProcessRequest`，调用 `AgentService` |
| 工具单一执行入口与上下文约束 | PASS | CLI 不执行工具、不创建 ToolCallingManager、不使用 ThreadLocal |
| PostgreSQL/pgvector 与 day-one 审计 | PASS | 复用现有 storage；端到端测试使用 PostgreSQL，CLI 不新增存储旁路 |
| 三入口共享 AgentService | PASS | CLI 通过统一 `AgentService.process(...)`，不创建 CLI 专用历史 |
| 密钥不落盘、不打印敏感数据 | PASS | 初始化只写模型/Provider 名称；输出和错误信息不含凭据、prompt 或异常堆栈 |
| 测试与质量门 | PASS | 执行 `mvn -B -ntp verify` 与 `scripts/check_db_test_reports.py`，真实模型单独 opt-in |
| 每个 user story 有阶段 Demo | PASS | US1–US4 的 T018、T026、T033、T038 分别执行 quickstart 阶段 Demo；20 次统计另由 T041/T042 完成 |
| 与旧 002 契约的一致性 | PASS WITH DOCUMENT UPDATE | 本规格明确覆盖旧契约的 env/.env 模型回退和 Provider 省略行为；实施需同步旧契约，不能静默复用冲突规则 |

**Gate conclusion**: PASS。最后一项是文档/测试行为同步，不是 constitution 违规；不得修改 constitution。

## Phase 0: Research summary

研究产物见 [research.md](research.md)。关键结论：

1. 复用当前 CLI/boot 端口和延迟启动边界。
2. 以显式 `--model` 和生成 `primary` 为本规格权威，更新旧契约和解析器测试。
3. 以 `(cli, OS account, Agent)` 复用现有会话，不新增表。
4. 通过文件单测、真实 CLI + 本地模型替身 + PostgreSQL 集成测试验证跨层行为。

## Phase 1: Design artifacts

- [data-model.md](data-model.md)：工作区、Agent Profile、Provider 引用、SessionKey、Turn 和 CLI 执行状态。
- [contracts/cli.md](contracts/cli.md)：命令语法、Agent 选择、输出流、退出码和会话规则。
- [contracts/workspace-and-profile.md](contracts/workspace-and-profile.md)：初始化目录、frontmatter、显式模型和 `primary` Provider 规则。
- [quickstart.md](quickstart.md)：离线初始化、错误输入、聊天、会话恢复、`--new` 和质量门验证。

## Source structure

```text
AgentOS-cli/src/main/java/com/agentos/cli/
├── AgentOSCli.java              # Picocli 根入口与参数错误映射
├── AgentOSCommand.java          # 全局 --config 与 help/version
├── InitCommand.java             # 离线 init 与退出码
├── InitModelResolver.java       # 本规格改为仅显式 --model
├── ProviderNameProjectionLoader.java # 从同一 ConfigData 只提取 agentos.providers 的 key
├── ProviderNameCatalog.java     # 非敏感 Provider 名称端口
├── LocalProviderNameCatalog.java # 本地配置名称投影实现
├── WorkspaceInitializer.java    # 工作区文件与默认 main
├── ChatCommand.java             # Agent 选择、交互输入、--new、输出与退出码
└── ContextLauncher.java         # CLI → boot 的最小端口，传递 config path

AgentOS-boot/src/main/java/com/agentos/boot/
├── AgentOSApplication.java      # 委托 CLI，不提前启动 Spring
└── SpringContextLauncher.java   # NONE 上下文、workspace root 与 config path 传递

AgentOS-core/src/main/java/com/agentos/core/
├── agent/AgentLoader.java       # Agent Profile 校验
├── turn/AgentService.java       # 统一处理入口
└── session/SessionManager.java  # --new 空闲归档

AgentOS-cli/src/test/java/com/agentos/cli/
├── AgentOSCliTest.java
├── InitCommandBehaviorTest.java
├── InitModelResolverTest.java
├── WorkspaceInitializerTest.java
└── ChatCommandTest.java

AgentOS-boot/src/test/java/com/agentos/boot/
├── DefaultAgentCliIntegrationTest.java
└── SessionConcurrencyIntegrationTest.java

scripts/
├── cli_init_acceptance.py       # C2 初始化性能、幂等和未知 Provider 统计
└── cli_session_acceptance.py    # C3 恢复、隔离和 --new 统计
```

**Structure Decision**: 保持现有多模块目录，不创建新模块、不创建第二套 CLI 入口、不新增数据库迁移。实现集中在 `AgentOS-cli`，跨模块装配只通过现有 `ContextLauncher`/`SpringContextLauncher` 和统一 core/storage 服务完成。

## Implementation focus for `$speckit-tasks`

1. 先更新行为测试，使显式 `--model`、本地 Provider 名称投影、未知 Provider、`primary` 写入、OS 账号身份、help/version、固定退出码和交互失败后最终 0 形成可观察红灯。
2. 调整 `InitModelResolver`、`ProviderNameProjectionLoader`、`LocalProviderNameCatalog` 与 `WorkspaceInitializer`，实现全局 `--config` 和固定默认搜索顺序；确保缺少 `--model` 或 Provider 名称不在配置投影中时在任何写入前返回 2；投影只读取 key，不读取凭据或连接值；Boot chat 通过 `spring.config.additional-location` 使用同一路径；名称目录不可用时不得静默退回硬编码集合；新 `main` 明确写入 `primary`。
3. 复核 `ChatCommand` 的身份来源、交互返回值和 `--new` 错误映射；保持单条消息失败返回 3。
4. 更新 `specs/002-react-loop` 中冲突的 CLI/configuration 契约或添加明确迁移说明，避免旧文档继续指导错误实现；`AgentOS-channel-cli` 只保留回归测试，不改生产输入适配器。
5. 按 US1–US4 阶段 Demo 逐故事验收，再运行 C1/C2/C3 统计、受影响测试、完整 Maven 质量门和数据库测试报告检查。

验收脚本只依赖 Python 标准库，使用临时目录和合成配置；C3 需要 Testcontainers PostgreSQL。Docker 不可用时必须输出 `NOT_RUN` 和原因，`CI=true` 不得静默跳过。

## Post-design Constitution Check

- CLI 仍是入口适配层，未承担模型循环、工具执行、数据库事务或业务上下文传递。
- 所有持久化仍由现有 PostgreSQL/Flyway storage 链路完成，未增加 H2/SQLite、缓存或本地 JSON 历史。
- 轻命令保持离线，聊天通过无 Web Spring 上下文进入统一 AgentService。
- 未引入密钥、通用配置系统、额外 Provider SDK 或 constitution 修改。

**Post-design gate: PASS。**

## Complexity Tracking

无 constitution 例外，无新增项目或旁路架构。
