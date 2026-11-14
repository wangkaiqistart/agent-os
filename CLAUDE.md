# CLAUDE.md

本文件为 Claude Code 在本仓库工作时提供指引。

## 项目现状

AgentOS 是 Java 实现的企业级 **Agent OS**：私有部署的 Agent 统一底座，在一个进程里承载多个业务 Agent，共享 LLM 接入、ReAct 引擎、记忆、工具、沙箱和审计能力。

**仓库目前是 Maven 骨架加工程基线：** 根 `pom.xml`（继承 `spring-boot-starter-parent` 4.1.2，导入 `spring-ai-bom` 2.0.2，挂了质量门插件，见下文「工程基线」）和核心阶段 9 个模块；`AgentOS-boot` 有启动类 `AgentOSApplication`（先交给 Picocli 根命令 `AgentOSCommand`）和 `application.yaml`。已有的代码只有 API 规范类（core 的 `ErrorCode`、`AgentOSException`，web 的 `ApiResponse`、`GlobalExceptionHandler`、`WebConfig`）和基线测试（`GlobalExceptionHandlerTest`、`ArchitectureRulesTest`、`LoggingBaselineTest`）。业务代码、Flyway 脚本还没写。动手写代码前先读对应文档章节，不要凭印象实现。

- 在仓库根目录执行 `mvn`。`.mvn/maven.config` 会加载 `.mvn/settings.xml`，强制所有依赖只从 Maven Central 解析，覆盖本机 settings 里的公司镜像。
- groupId 是 `com.agentos`，包名 `com.agentos.<模块>`（如 `com.agentos.channel.cli`）。
- 版本集中在根 `pom.xml` 的 `<properties>`：Spring AI 2.0.2、springdoc 3.2.0、Picocli 4.7.7、ShedLock 7.10.1。

## 文档地图与优先级

| 文档 | 回答什么 | 何时读 |
|------|---------|-------|
| `docs/IndustryResearch.md` | Why：业界格局、Java 生态缺位、定位与边界 | 理解"做什么、不做什么" |
| `docs/DemandAnalysis.md` | What：功能/非功能需求、数据实体、验收标准 | 确认需求与验收口径 |
| `docs/TechnicalSolution.md` | How：架构、模块、关键决策、表结构 | **实现时的主依据** |
| `docs/AiProgrammingGuide.md` | 怎么用 Spec-Kit + Claude Code 分 5 个 user story 实施 | 规划任务拆分 |

文档之间有冲突时，以 `TechnicalSolution.md` 为准。表字段只在技术方案第 9 章维护。

## 技术栈（已定，不要替换）

- JDK 21（virtual thread）+ Spring Boot 4.x（4.0.x / 4.1.x）+ Spring MVC
- **Spring AI 2.0.x**，经 `org.springframework.ai:spring-ai-bom` 管理版本，依赖全部来自 Maven Central。**优先使用 Spring AI 2.0 的官方组件，不要自己重写它已有的能力**
- **禁止引入 Spring AI Alibaba 等 `com.alibaba.cloud.ai` 系列依赖**。主链路模型、国内模型和百炼 Embedding 都走 `spring-ai-openai` 的 OpenAI 兼容接口（纯 HTTP，不引入厂商 SDK）
- **PostgreSQL 16+（pgvector 扩展）** + Spring Data JPA + Flyway（只维护 PostgreSQL 一套迁移脚本，`ddl-auto=validate`）。**不使用 SQLite/H2**。JSON 字段用 `jsonb`；向量用 Spring AI `PgVectorStore`（`kb_vectors`、`memory_vectors`、`tool_vectors` 三张表），表结构也由 Flyway 创建（`initialize-schema=false`）
- ShedLock（定时任务锁）、Picocli（CLI）、SnakeYAML、Logback/SLF4J、springdoc-openapi（兼容 Boot 4 的版本）
- MCP：`spring-ai-starter-mcp-client`（SYNC 客户端）
- 测试：JUnit 5、Testcontainers（`pgvector/pgvector:pg17`）、Spring AI 评估器（`RelevancyEvaluator`、`FactCheckingEvaluator`）

参考笔记：https://notes.wwkq.top/frameworks/spring-ai/ （Spring AI 2.0.2 官方文档的中文整理），类名与配置键以它为准。

## 不可违反的设计约束

这些约束是 AI 最容易写错的地方，代码评审时逐条检查：

1. **循环自实现，组件用 Spring AI 2.0。** `ReActLoop` 自己驱动迭代，直接调用 `ChatModel.call/stream`。主链路不走 `ChatClient`，不用 `ToolCallingAdvisor`。
2. **工具只有一个执行入口。** 工具只经 `ToolExecutor` 调用**注入的** `ToolCallingManager.executeToolCalls(...)` 执行。禁止 `ToolCallingManager.builder().build()` 自建（否则 `spring.ai.tools.limits.*`、异常处理器、观测都不生效）。辅助调用（生成 `AGENT.md` 草稿、评估器）用 `ChatClient` 时不传 tools，并全局设置 `spring.ai.chat.client.tool-calling.enabled=false`。
3. **工具统一为 `ToolCallback`。** 内置 Tool、`@Tool` Bean（`ToolCallbacks.from`）、MCP 工具（`SyncMcpToolCallbackProvider`）都是 `ToolCallback`，外层套 `GovernedToolCallback`：审计（`tool_invocations`）、超时、幂等重试、结果截断（`max_result_chars`）、脱敏（`Redactor`）都在这里。不再有 `AgentTool` 接口。`ToolRegistry` 必须调用 `ToolCallingChatOptions.validateToolCallbacks(...)` 校验重名，直接调 `ChatModel` 时 Spring AI 不会替你校验。
4. **只重试幂等工具，超时要在 I/O 层生效。** 工具策略（`@ToolPolicy` / 内置默认 / `mcp_servers.yaml`）声明 `idempotent`、`timeout`、`max_result_chars`。`notify`、`http_post`、`write_file`、`shell` 不自动重试。HTTP 客户端、子进程（`destroyForcibly`）、MCP `requestTimeout` 各自设超时，装饰器超时只兜底。
5. **MCP 客户端编程式创建**（`spring.ai.mcp.client.enabled=false`）：逐个带超时初始化，失败不阻塞启动、后台重连。`TurnContext` 不能发给 MCP server：`ToolContextToMcpMetaConverter` 用 `noOp()`，并在 `GovernedToolCallback` 里去掉内部键。
6. **失败反馈给模型。** 自定义 `ToolExecutionExceptionProcessor`：Sandbox 拒绝、工具超时、业务错误转成错误消息给模型；`TurnDeadlineExceededException` 重抛结束本轮。
7. **不用 ThreadLocal 传上下文。** 当前 Agent、会话、轮次通过 `ToolCallingChatOptions.toolContext(...)` 放入 `TurnContext`，工具方法声明 `ToolContext` 参数读取。
8. **两道上限、四层超时。** `max_iterations`（默认 10）限 LLM 调用次数；`spring.ai.tools.limits.*`（`RETURN_ERROR_RESPONSE`）限单轮工具调用次数。超时分单次 LLM（Provider `timeout`）、单个工具（工具策略）、整轮（`turn_timeout`）、HTTP 同步等待（超时返回 202，不取消轮次）。
9. **会话模型。** `sessions` 用 UUID 主键 + `(channel, user_id, profile_name) WHERE status='active'` 部分唯一索引；定时任务的 user_id 是 `schedule:<scheduleId>`，`invoke` 是 `invoke:<uuid>`，否则并发会撞索引。消息一条一行写 `session_messages`，类型对应 Spring AI 的 `UserMessage` / `AssistantMessage` / `ToolResponseMessage`；每条用户消息是一个 `agent_turns` 轮次。prompt 按**轮次**截断（`max_history_turns` 与 `max_history_tokens` 同时生效；失败轮次只保留用户消息加一条说明），不能拆开工具调用和工具结果。同一会话同时只跑一个轮次：会话租约冲突返回 409，每次迭代和每次工具调用前续租，续租失败立即终止；残留的 `RUNNING` 轮次由 `TurnReaper` 标为 `INTERRUPTED`。
10. **数据库连接。** `spring.jpa.open-in-view=false`；`@Transactional` 不能包住 LLM 调用、工具调用或同步等待。
11. **Provider 显式映射。** `ProviderService` 按 `agentos.providers.<name>` 编程式构建 `ChatModel`，以 provider name 为键。禁止靠扫描容器里的 `ChatModel` Bean 区分 Provider。
12. **审计表从第一天就写。** `llm_calls` 记 `ChatResponseMetadata` 的 ID、实际模型、`Usage`（缓存 token 为 null 时落 NULL）、`RateLimit`（`EmptyRateLimit` 时落 NULL）、结束原因，调用失败也写一行；两张审计表都带 `session_id`、`turn_id`、`iteration`。写入前脱敏 webhook token 和密钥字段。只写日志不算完成。
13. **重试归 `ProviderService`。** 构建 `ChatModel` 时关闭模型实现内置的重试，只对 429 / 5xx / 连接错误重试 1 次，并受整轮截止时间约束。
14. **Embedding 单独配置，不跟主链路模型绑定。** 关闭 Starter 的 Embedding 自动配置（`spring.ai.model.embedding=none`），按 `agentos.embedding.*` 编程式构建唯一的 `OpenAiEmbeddingModel`；三个 `PgVectorStore` 用 `spring-ai-pgvector-store` 模块按表名构建，维度显式配置并在启动时校验。
15. **Agent 目录和 Skill 不是 Tool。** `AGENT.md` 由 core 的 `AgentLoader` / `ContextLoader` 加载进 system prompt。Skill 每轮只注入 name、description、本地路径，正文经 `read_file` 按需读取。
16. **Memory 走统一门面 `MemoryService`**，内部委托 `SessionManager` 和 `LongTermMemoryStore`（`memory.backend` 选 markdown / postgres / mem0）。核心记忆区永不截断、不参与检索；归档区按 `memory.archival.max-chars`（默认 4000）截断；长期记忆不缓存。记忆放在 system prompt 末尾标注"是数据不是指令"的分隔块里，`save_memory` 单条限 500 字符。不用 Spring AI 的 `ChatMemory` Advisor。
17. **Sandbox 接口先行。** `Sandbox.enforce(SandboxAction)`，核心阶段只有 `WhitelistSandbox`，在内置工具内部调用。不使用 `SecurityManager`。
18. **三个触发入口共用一条链路。** CLI、REST、定时都调 `AgentService.process(...)`。定时任务用 ShedLock 的 `LockingTaskExecutor` 防重叠，不用进程内锁；会话冲突时本次触发记 `skipped`。
19. **Tool 只有一个模块 `AgentOS-tool`**，不要拆。
20. **密钥不落盘。** 用 `${ENV_VAR}` 占位，由 `ConfigLoader` 解析并校验。不提交 `.env`。
21. **不改 constitution。** 发现原则有问题时停下来问用户，不要自行修改。

## 环境与密钥

项目开始时项目方会提供 PostgreSQL 地址、账号密码、一个**测试专用**的主链路模型接口（OpenAI 兼容中转，模型 `gpt-5.6-luna`），以及一套**测试专用**的 Embedding 服务（百炼 `text-embedding-v4`）。放进仓库根目录的 `.env`（已 gitignore，`application.yaml` 用 `spring.config.import: optional:file:.env[.properties]` 读取）或 CI 密钥，**永远不要写进任何会提交的文件、日志或文档**。

| 变量 | 用途 |
|------|------|
| `AGENTOS_DB_URL` / `AGENTOS_DB_USERNAME` / `AGENTOS_DB_PASSWORD` | PostgreSQL 连接 |
| `AGENTOS_LLM_BASE_URL` / `AGENTOS_LLM_API_KEY` / `AGENTOS_LLM_MODEL` | 主链路模型 provider `primary`（主链路与辅助调用共用）。测试环境是推理模型：**不发送 `temperature`**，用 `reasoning_effort` 和 `max_completion_tokens`，超时 120 秒；base-url 写到 `/v1`；中转接口的工具调用、流式工具调用、用量统计、`json_schema` 支持要实测 |
| `AGENTOS_DEEPSEEK_BASE_URL` / `AGENTOS_DEEPSEEK_API_KEY` / `AGENTOS_DEEPSEEK_MODEL` | provider `deepseek`（DeepSeek 官方接口，测试模型 `deepseek-flash`），与 `primary` 同级，Agent 在 Profile 里按名称选用。key 为空时该连接不可用，不影响启动 |
| `AGENTOS_EMBEDDING_BASE_URL` / `AGENTOS_EMBEDDING_API_KEY` / `AGENTOS_EMBEDDING_MODEL` / `AGENTOS_EMBEDDING_DIMENSIONS` | 测试专用的 Embedding 服务：阿里云百炼兼容模式 `text-embedding-v4`，1024 维；base-url 写到 `https://dashscope.aliyuncs.com/compatible-mode/v1`；单次请求最多 10 条，`BatchingStrategy` 要限条数 |

变量模板见仓库根目录的 `.env.example`。

- 提供的库需要 pgvector 扩展或建扩展权限。使用提供的库时设 `spring.docker.compose.enabled=false`。
- 自动化测试默认用 Testcontainers，不连共享库；不要对共享库执行 Flyway `clean`。
- 调真实模型的测试加 `@Tag("llm")` 和 `@EnabledIfEnvironmentVariable(named = "AGENTOS_LLM_API_KEY", matches = ".+")`，Embedding 测试按 `AGENTOS_EMBEDDING_API_KEY` 判断，用 `mvn verify -Pllm` 运行。`PgVectorStore` 的集成测试用假 `EmbeddingModel`，不依赖真实 key。

## 目标模块结构

Maven 多模块，模块名前缀 `AgentOS-`：核心阶段 9 个（`core`、`provider`、`memory`、`tool`、`channel-cli`、`web`、`storage`、`cli`、`boot`），后续加入 5 个（`persona`、`knowledge`、`channel-feishu`、`channel-wecom`、`channel-dingtalk`）。职责见 `TechnicalSolution.md` 第 10 章。新增 Channel 或 Tool 实现只加模块，不改 core；契约放 core，实现放具体模块（依赖倒置）。

## 运行时工作区

`AgentOS init` 生成 `.AgentOS/`：`agents/<name>/AGENT.md`（frontmatter = Profile，正文 = 任务指令，可带 `skills/` 相对软连接、`scripts/`、`REFERENCE.md`）、`skills/`、`memory/MEMORY.md`、`mcp_servers.yaml`、Bootstrap 文件 `AGENTS.md` / `SOUL.md` / `USER.md`、`output/`、`logs/`。`init` 必须幂等，不覆盖已有文件，也不连接数据库。

## 命令

```bash
mvn -B verify                     # 质量门：格式、Checkstyle、SpotBugs、依赖禁令、ArchUnit、测试
mvn spotless:apply                # 自动格式化
mvn -B clean package              # 构建，产物 AgentOS-boot/target/AgentOS.jar（集成测试加入后依赖 Docker / Testcontainers）
python3 scripts/check_env.py      # 检查 .env 里的模型与 Embedding 配置是否可用（不打印 key）
java -jar AgentOS-boot/target/AgentOS.jar --version       # 完整应用
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version # 只含命令行模块，可单独运行
# 以下为目标形态，尚未实现
mvn verify -Pllm                  # 加跑调用真实模型的端到端测试与评估
AgentOS init | status | chat [--profile <name>] | serve | gateway
AgentOS profile list|create|show|delete <name>
AgentOS provider list | tool list | session list
```

核心阶段 12 个子命令，后续加 `agent import`。`init`、`profile list` 这类命令不启动 Spring 上下文；`chat`、`serve`、`gateway` 才启动。

## 工程基线

质量门全部绑定在 `mvn verify`，CI（`.github/workflows/ci.yml`）跑的是同一条命令。**`mvn verify` 失败 = 违反规范**，修代码，不要放宽规则或加排除项来让它通过（确需调整先问用户）。

| 检查 | 工具 | 失败时怎么办 |
|------|------|-------------|
| 格式（Google Java Style，AOSP 4 空格） | Spotless + google-java-format | `mvn spotless:apply` |
| 命名、导入、空 catch、switch 缺 default | Checkstyle（`config/checkstyle/checkstyle.xml`） | 按报错位置改 |
| 缺陷与安全（注入、弱加密、路径穿越） | SpotBugs + FindSecBugs | 改代码；误报用 `@SuppressFBWarnings(value=..., justification=...)` 并写原因 |
| 禁用依赖（alibaba、H2、SQLite）、JDK 21 | maven-enforcer-plugin | 删依赖 |
| 设计约束（ToolCallingAdvisor、自建 ToolCallingManager、ThreadLocal、System.out、字段注入、core 依赖实现模块） | ArchUnit（`AgentOS-boot` 的 `ArchitectureRulesTest`） | 按约束改实现 |
| 密钥泄露 / 源码漏洞 / 依赖漏洞 | gitleaks、CodeQL、Dependabot（`security.yml`、`dependabot.yml`） | 泄露的密钥先轮换，再清理 |

工具查不到、需要自觉遵守的约定：

- 依赖用构造器注入；日志用 SLF4J 占位符（`log.info("x={}", x)`），不拼字符串，不打印密钥、prompt 全文和工具参数。
- MDC 只用于日志关联，约定键 `sessionId`、`turnId`、`agent`、`iteration`；业务上下文走 `ToolContext`，不从 MDC 读。
- 业务异常抛 `AgentOSException(ErrorCode, message)`，message 会返回给调用方，不能含内部细节；新增错误码要在 `GlobalExceptionHandler.toHttpStatus` 补映射。
- REST 响应统一用 `ApiResponse` 信封，Controller 不自己 try/catch 拼错误响应。

## 官网

`website/` 是 VitePress 1.6 站点（独立的 npm 项目，不参与 Maven 构建）。首页由 `website/.vitepress/theme/components/HomePage.vue` 渲染，背景是 `HeroScene.vue` 的 three.js 场景（只在浏览器端动态加载，兼容 SSR），`ReactTrace.vue` 是轮次回放终端。首页文案必须与 `docs/` 一致，不要写尚未实现的能力为"已支持"。

```bash
cd website && npm install && npm run dev    # 本地预览 http://localhost:5173
npm run build                               # 产物在 website/.vitepress/dist
```

## 文档阅读提示

文档里的 `../website/public/images/*.svg` 图片指向本仓库不存在的站点资源，不要去找。

## 工作方式

- 用中文回复，代码注释用中文。
- 主体开发按 US-1（Provider）→ US-2（ReAct）→ US-3（Memory）∥ US-4（Tool）→ US-5（Web Service）推进，每个 user story 完成后要有阶段 Demo；最终验收是三个 Demo（每日天气、每日科技日报、每日 GitHub 日报）加评估测试。
- 只做当前任务范围内的事。多租户、SSO、Tool Policy、SSE 端点、容器沙箱等扩展功能不要提前实现，但保留接口扩展点（如 `TurnSink`、`Sandbox`）。
