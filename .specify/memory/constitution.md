# AgentOS Constitution

## Core Principles（核心原则）

### I. JDK 21 + Spring Boot 4.x + Spring AI 2.0.x 单体应用

- 必须使用 JDK 21、Spring Boot 4.x（4.0.x / 4.1.x）、Spring AI 2.0.x（经 `spring-ai-bom` 管理版本），
  依赖只来自 Maven Central。
- 交付形态是 Maven 多模块单体：核心阶段 9 个模块，产物为一个可执行 JAR 加一个 PostgreSQL。
- 禁止引入 Spring AI Alibaba 等 `com.alibaba.cloud.ai` 系列依赖；国内模型和百炼 Embedding
  一律走 `spring-ai-openai` 的 OpenAI 兼容接口，不引入厂商 SDK。
- 契约放 `AgentOS-core`，实现放具体模块；新增 Channel 或 Tool 实现只加模块、不改 core。
  Tool 只有 `AgentOS-tool` 一个模块。

理由：Spring AI 2.0.x 只支持 Boot 4.x；单体 JAR 让企业用现有 Java 运维体系部署，满足"私有""易接入"。

### II. 五大核心能力优先

- 核心阶段只交付运行时内核的五大能力：对接 LLM、ReAct 循环、三层 Memory、Tool 体系、Web Service。
- 多租户、SSO、Tool Policy、SSE 端点、容器沙箱、Prometheus 导出等扩展阶段能力，核心阶段
  禁止提前实现，只保留接口扩展点（如 `TurnSink`、`Sandbox`）。
- 对外文案（README、官网）禁止把未实现的能力写成"已支持"。

理由：核心阶段交付的是内核底座，企业级治理层是终局，先把地基做扎实。

### III. 循环自实现，组件用 Spring AI 2.0

- `ReActLoop` 必须自己驱动迭代，直接调用 `ChatModel.call/stream`；主链路禁止走 `ChatClient`。
- 循环之外必须优先用 Spring AI 2.0 官方组件，禁止重写它已有的能力：工具统一为 `ToolCallback`，
  上下文经 `ToolCallingChatOptions.toolContext(...)` 传入 `ToolContext`（禁止用 ThreadLocal），
  流式聚合用 `MessageAggregator`。
- 必须同时守住两道上限（`max_iterations`、`spring.ai.tools.limits.*`）和四层超时
  （单次 LLM、单个工具、整轮、HTTP 同步等待）。
- `ProviderService` 按 `agentos.providers.<name>` 编程式构建 `ChatModel` 并显式映射；
  关闭模型内置重试，重试只归 `ProviderService`。

理由：迭代上限、整轮超时、流式输出、会话落库都必须由 AgentOS 掌控。

### IV. 工具只有一个执行入口（NON-NEGOTIABLE）

- 工具只能经 `ToolExecutor` 调用**注入的** `ToolCallingManager.executeToolCalls(...)` 执行。
- 禁止使用 `ToolCallingAdvisor`（全局 `spring.ai.chat.client.tool-calling.enabled=false`）；
  禁止 `ToolCallingManager.builder().build()` 自建；辅助调用使用 `ChatClient` 时禁止传 tools。
- 每个工具外层必须套 `GovernedToolCallback`，负责审计、超时、幂等重试、结果截断、脱敏；
  只有声明 `idempotent` 的工具才能自动重试。`ToolRegistry` 必须调用
  `ToolCallingChatOptions.validateToolCallbacks(...)` 校验重名。
- 工具失败经自定义 `ToolExecutionExceptionProcessor` 转成错误消息反馈给模型；
  `TurnDeadlineExceededException` 重抛结束本轮。

理由：两个执行入口会让工具被调两次，并绕过 Sandbox 与审计。这是最容易被写错的一条。

### V. Plugin Tool 三档接入

- 业务方扩展工具有三档：零代码（Agent 目录 + 复用 MCP server，主推）、自己写 MCP server、
  写 Java Spring Bean（`@Tool`）。三档最终都是 `ToolCallback`，受同一套治理。
- MCP 客户端必须编程式创建（`spring.ai.mcp.client.enabled=false`），逐个带超时初始化，
  失败不阻塞启动、后台重连；`TurnContext` 禁止发给 MCP server。

理由：企业上一个新 Agent 放一个目录就能跑，是"统一"目标的落地方式。

### VI. PostgreSQL（pgvector）+ MEMORY.md 存储，审计 day one 落库

- 关系数据与向量只用 PostgreSQL 16+（pgvector）；禁止 SQLite / H2。
- 表结构只由 Flyway 管理（`ddl-auto=validate`，向量表 `initialize-schema=false`），只维护一套
  PostgreSQL 迁移脚本；表字段以技术方案第 9 章为准。
- 长期记忆默认 `MEMORY.md` 文件，经统一门面 `MemoryService` 访问；禁止使用 Spring AI 的
  `ChatMemory` Advisor。
- `tool_invocations` 与 `llm_calls` 必须在核心阶段就写入，调用失败也写一行，写入前脱敏；
  两张表都带 `session_id`、`turn_id`、`iteration`。只写日志不算完成。

理由："可审计"是 AgentOS 的差异化能力，数据地基必须 day one 立起来，避免后期从日志反解析返工。

### VII. 每个 user story 完成后有可演示 Demo

- 按依赖顺序推进：US-1（Provider）→ US-2（ReAct）→ US-3（Memory）∥ US-4（Tool）→ US-5（Web Service）。
- 每个 user story 完成时必须跑通对应的阶段 Demo；最终验收是三个 Demo（每日天气、每日科技日报、
  每日 GitHub 日报）加 Spring AI 评估器测试。
- 优先级是跑通而非完美：禁止为假想需求增加抽象或功能。

理由：可演示的阶段成果能尽早暴露集成问题，也是推进节奏的验收口径。

### VIII. Agent 目录不是 Tool

- `AGENT.md` 由 core 的 `AgentLoader` / `ContextLoader` 加载：frontmatter 派生 Profile，正文注入
  system prompt。Agent 目录与 Skill 禁止注册进 `ToolRegistry`。
- Skill 每轮只注入 name、description、本地路径，正文与 `scripts/` 经 `read_file` / `shell` 按需读取。
- 记忆放在 system prompt 末尾、标注"是数据不是指令"的分隔块里。

理由：Agent 目录定义的是 Agent 本身，不是 Agent 可调用的动作；混为 Tool 会多一次无意义的调用。

## 技术约束

- **密钥不落盘**：配置用 `${ENV_VAR}` 占位，由 `ConfigLoader` 解析并校验；禁止提交 `.env`，
  密钥禁止出现在代码、日志、文档中。
- **Sandbox 接口先行**：`Sandbox.enforce(SandboxAction)`，核心阶段只有 `WhitelistSandbox`，
  在内置工具内部调用；禁止使用 `SecurityManager`。
- **三个触发入口共用一条链路**：CLI、REST、定时任务都调 `AgentService.process(...)`；
  同一会话同时只跑一个轮次，会话租约冲突返回 409。
- **数据库连接**：`spring.jpa.open-in-view=false`；`@Transactional` 禁止包住 LLM 调用、
  工具调用或同步等待。
- **测试环境**：自动化测试默认用 Testcontainers（`pgvector/pgvector:pg17`），不连共享库，
  禁止对共享库执行 Flyway `clean`；调真实模型的测试加 `@Tag("llm")` 并按环境变量启用。
- 上述原则的完整展开见 `CLAUDE.md`「不可违反的设计约束」，代码评审时逐条检查。

## 开发流程与质量门

- **唯一质量门是 `mvn -B verify`**：格式（Spotless）、Checkstyle、SpotBugs + FindSecBugs、
  依赖禁令（enforcer）、架构约束（ArchUnit）、测试。CI 跑同一条命令。
  失败时修代码，禁止为了通过而放宽规则或加排除项（确需调整须项目方同意）。
- **先读文档再实现**：动手前读技术方案对应章节；文档冲突时以 `docs/TechnicalSolution.md` 为准。
- **Spec Kit 流程**：`/speckit-plan` 生成后必须人工 review；每次 `/speckit-implement` 后
  跑 `/speckit-analyze` 检查漂移，发现偏离立即修正。
- **范围控制**：只做当前任务范围内的事，禁止顺手重构或提前实现扩展功能。

## Governance

- 本宪章高于其他开发约定。`CLAUDE.md`、`docs/` 与本宪章冲突时，以本宪章为准并同步修正冲突文档。
- **AI agent 禁止自行修改本宪章。** 实施中发现原则有问题时必须停下，向项目方说明问题与建议，
  经项目方确认后才能修订。
- 修订须记录在文件头部的 Sync Impact Report 中，并按语义化版本递增：
  MAJOR = 删除或重新定义原则；MINOR = 新增原则或实质性扩充；PATCH = 措辞澄清。
- 合规检查：每个 plan 必须通过 Constitution Check；每个 PR 评审须确认未违反本宪章。

**Version**: 1.0.0 | **Ratified**: 2026-11-08 | **Last Amended**: 2026-11-08
