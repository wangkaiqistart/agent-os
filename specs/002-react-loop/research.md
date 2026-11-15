# Research: US-2 ReAct 循环

**Date**: 2026-11-15 | **Plan**: [plan.md](plan.md)

工具执行的结论来自对 Spring AI 2.0.2 `ToolCallingManager` 的实测（一次性探针，没有进仓库）：用 `ApplicationContextRunner` 加官方自动配置，构造带工具调用的 `ChatResponse`，直接调用 `executeToolCalls`。其余结论来自反汇编、本机环境检查和对公开天气接口的只读请求。下面提到的探针工具：`get_a` 是名单内的工具，`get_b` 声明成 Bean 但不在名单里，`ghost` 不存在，`boom` 会抛异常。

## R1 `ToolCallingManager` 的执行方式

- **Decision**: 把它当唯一的工具执行入口，并接受它的行为：同步、按请求顺序、在调用线程上执行，没有超时，只认请求里带的工具。
- **Rationale**: 探针 A 请求 `[get_a, get_b, ghost, boom]`：`get_a` 先执行，随后整批抛出 `IllegalStateException: No ToolCallback found for tool name: get_b`，`get_a` 已经执行的结果丢失。所有回调都在 `main` 线程执行（`virtual=false`），`ToolContext` 里只有我们放进去的键。
- **Alternatives**: 自己遍历工具调用逐个执行。没选：这就是宪章原则 IV 禁止的第二个执行入口。

## R2 名单外的工具：打开解析回退，换成"只返回错误"的解析器

- **Decision**: 配置 `spring.ai.tools.resolution.fallback.enabled=true`，并注册一个 `ToolCallbackResolver`：任何名字都返回一个回调，回调的结果是"本 Agent 没有名为 X 的工具"。
- **Rationale**: 回退默认关闭，名单外的名字会让整批抛异常。对照探针 K（默认解析器加回退）：声明成 Bean 但不在名单里的 `get_b` 被真的执行了，等于绕过了 Agent 的工具名单（FR-030）；`ghost` 仍然整批抛异常。探针 J（自定义解析器加回退）：四个调用都按请求顺序、带各自的调用 ID 返回了结果，没有异常。管理器先查请求里带的工具，找不到才问解析器，所以这个解析器永远不会返回真工具。
- **Alternatives**: 调用管理器之前先把名单外的调用从助手消息里删掉。没选：助手消息里的调用和工具响应对不上，模型看不到错误，可能反复请求。

## R3 异常：全部包成 `ToolExecutionException`，由处理器决定脱敏还是重抛

- **Decision**: `GovernedToolCallback` 捕获所有异常，包成 `ToolExecutionException`；注册自定义的 `ToolExecutionExceptionProcessor`：普通失败返回脱敏后的错误文本，整轮超时、租约丢失、审计写入失败这三类重抛，结束本轮。
- **Rationale**: 探针 E：自定义回调直接抛 `IllegalStateException("boom-secret-token http://x/?key=SECRET123")`，异常原样冒出，密钥在消息里，处理器根本没被调用。探针 J：包成 `ToolExecutionException` 之后，处理器返回了脱敏文本，同一批的其他调用不受影响。默认的 `DefaultToolExecutionExceptionProcessor` 会把异常消息原样交给模型，不能用。
- **没有实测的部分**：处理器里重抛（抛出原因）的路径，作为实现任务里的测试覆盖。

## R4 工具次数上限

- **Decision**: 沿用 `spring.ai.tools.limits.*` 与 `RETURN_ERROR_RESPONSE`。`ToolExecutor` 对比"响应里请求的调用"与"实际执行的调用"，为没被执行的补写 `limit_skipped` 记录。
- **Rationale**: 探针 B（单工具上限 2，请求 4 次 `get_a`）：前两次执行，后两次得到 `Tool call limit (2) exceeded for tool 'get_a'...` 的纯文本结果，回调没有被调用，所以回调里没法记录它们。管理器从 prompt 里最后一条 `UserMessage` 之后开始计数，所以交给它的 prompt 必须带着本轮累计的全部历史，不能只带本次迭代，否则跨迭代的上限会被重置，要有一个测试专门覆盖。
- 全部调用都声明直接返回时 `returnDirect()` 才是 `true`，混合时是 `false`（探针 C、D）。

## R5 模型给的工具调用 ID 怎么交给审计

- **Decision**: `ToolExecutor` 调用管理器之前，按工具名建一个先进先出的账本：同名工具的调用 ID 按响应里的顺序排队，账本放进 `ToolContext`。`GovernedToolCallback` 执行时取同名队首的 ID。执行结束后账本里还剩的 ID，就是被次数上限跳过的。
- **Rationale**: `ToolContext` 只有一个 `Map`（`getContext()`），回调拿不到模型给的调用 ID，而审计要关联它（FR-035）。管理器顺序执行，且次数上限跳过的总是同名工具里靠后的调用，所以"同名工具的第 k 次执行对应第 k 个 ID"成立。这个前提依赖管理器的顺序语义，要用测试固定下来；升级 Spring AI 后测试失败，说明前提变了。
- **Alternatives**: 按参数匹配调用。没选：参数相同的调用会混在一起。

## R6 工具回调的线程与超时

- **Decision**: `GovernedToolCallback` 把真正的回调放到虚拟线程上执行，用 `Future.get(超时)` 等结果，超时取"工具策略超时"与"剩余整轮时间"中较小的值；超时就取消并记 `timeout`。线程里的日志关联字段（MDC）由 `TurnContext` 的字段重新写入，不从当前线程拷贝。
- **Rationale**: 探针显示回调就在 `ToolCallingManager` 的调用线程上同步执行、没有超时，所以超时只能由回调自己承担。`ArchitectureRulesTest` 禁止 `MDC.get`，不能复制 MDC，只能在新线程上用 `TurnContext` 里的会话、轮次、Agent、迭代四个字段 `MDC.put`，结束时清除。取消只是中断线程，不保证底层 I/O 停下，所以每个工具仍要在自己的 I/O 层设超时（FR-018）。
- **Alternatives**: 在调用线程上直接执行。没选：阻塞的网络读不响应超时，整轮时限就失效了。

## R7 持久化映射

- **Decision**: 普通读写用 Spring Data JPA 实体与仓库；`jsonb` 列用 Hibernate 的 JSON 类型映射；需要"条件更新"的几处（租约、查找或创建会话、归档）用原生 SQL。第一个存储任务是一个 spike：用 Testcontainers 验证 `jsonb` 的写入与读回。
- **Rationale**: `hibernate-core` 7.4.13 带 `Jackson3JsonFormatMapper`，和 Boot 4 的 Jackson 3 对得上，经 JPA 映射 `jsonb` 应该可行。但本机没有数据库，没法实测，所以先做 spike，不通过就把这几列改成 `JdbcClient` 读写。宪章要求 Spring Data JPA，所以不整体换掉。
- **Alternatives**: 全部用 `JdbcClient`。没选：偏离宪章指定的技术栈，只在 spike 失败时作为兜底。

## R8 租约与会话的 SQL

- 取租约：`UPDATE sessions SET lease_owner=?, lease_until=now()+? WHERE session_id=? AND (lease_until IS NULL OR lease_until < now())`，影响行数为 0 就是被占用。
- 续租：同样的更新，条件是 `lease_owner=?`，影响行数为 0 就是租约丢失。释放：置空，条件同样是 `lease_owner=?`。
- 查找或创建会话：`INSERT ... ON CONFLICT DO NOTHING` 再查询，唯一索引保证并发下只有一个活跃会话。
- 时间一律用数据库的 `now()`，不用 Java 时钟，避免多进程时钟不一致影响租约。
- 租约时长不能按"每个 Provider 的 timeout"算：`AgentService` 不知道本轮会用哪个连接。改成配置 `agentos.turn.lease-ttl`，默认 150 秒（`primary` 的 120 秒加 30 秒），启动时对每个连接检查 `timeout + 30s ≤ lease-ttl`，不满足打 WARN。

## R9 没有 Docker 时的测试分层

- **Decision**: ① `ReActLoop`、`PromptBuilder`、`AgentService`、`ToolExecutor` 面向端口（`SessionManager`、`TurnStore`、`MessageStore` 和两个记录器）测试，用内存实现，不需要数据库；② 端口的契约写成抽象测试基类，内存实现和 JPA 实现各跑一遍，防止两边语义分叉；③ JPA 实现的测试用 Testcontainers（`pgvector/pgvector:pg17`），打 `@Tag("db")`。
- **本机行为**：`docker info` 连不上时，`db` 测试被跳过并打印原因；环境变量 `CI=true` 时不允许跳过，Docker 不可用就让构建失败，避免 CI 悄悄跳过。
- **依赖**：`testcontainers-postgresql` 与 `testcontainers-junit-jupiter` 2.0.5（Maven Central 上有，本地仓库里只有 `testcontainers-bom`，首次构建要下载）。不引入 `spring-boot-testcontainers`，用 `@DynamicPropertySource` 手动给连接属性，依赖更少。
- **Rationale**: 本机 `Docker.app` 已安装，`docker version` 客户端是 29.9.2，但守护进程没有运行（`docker info` 连不上套接字）。所以并非"没有 Docker"，启动 Docker Desktop 后这些测试就能在本机跑。宪章禁止 H2 和 SQLite，也禁止对共享库做破坏性操作，不用它们替代。
- **风险**：SQL 层的问题只有在有 Docker 时才会发现。CI 不允许跳过，就是为了守住这一条。

## R10 HTTP 工具与阶段 Demo 的天气数据源

- **Decision**: 用 JDK 自带的 `HttpClient`，关闭自动重定向，手动跟随（最多 3 跳，每跳过沙箱），在 `HttpClient` 上设连接和请求超时，响应体按字节数封顶读取。Demo 用 met.no 的 `locationforecast`，需要带可识别的 User-Agent，不需要密钥；AGENT.md 里直接写北京的经纬度，省去地名转坐标的一步。
- **Rationale**: 在本机只读地请求了几个公开天气接口（不带任何密钥）：`api.open-meteo.com` 超时，`wttr.in` 连接被拒，`api.met.no` 返回 200（38183 字节，2.5 秒，没有重定向），`www.7timer.info` 返回 200（15113 字节）。后两者的响应都超过 10000 字符，正好演示截断；met.no 的时间序列从当前时刻开始，截掉后面的预报，前面的实时数据还在。浏览器可用的接口不一定能在本机访问，所以 Demo 的数据源选了实测通过的。
- **Alternatives**: 7timer 作为 met.no 不通时的备选，换数据源只需改白名单和 AGENT.md。`java.net.http.HttpClient` 对虚拟线程友好，不引入 OkHttp 或 WebClient。

## R11 token 估算

- **Decision**: 用 `spring-ai-commons` 里已有的 `JTokkitTokenCountEstimator`，没有新依赖。
- **Rationale**: 本地仓库里 `jtokkit` 已经在。它按 OpenAI 的分词计数，对中文和 DeepSeek 只是近似值，所以 `max_history_tokens` 只是上限的估计，要留余量（spec Assumptions）。

## R12 `AgentOS chat` 怎么启动 Spring 上下文

- **Decision**: `AgentOS-cli` 定义 `ContextLauncher` 接口；`AgentOS-boot` 实现它（`SpringApplication` 加 `WebApplicationType.NONE`、关闭横幅），并在 `AgentOSApplication.main` 里交给 `AgentOSCli.execute`。`init` 不需要上下文。
- **Rationale**: `boot` 依赖 `cli`，反过来会形成循环依赖。数据库连接缺失或不可用时，`ChatCommand` 捕获启动失败，只打印一句话原因，不让 Spring 的整段堆栈冒到使用者面前。

## R13 AGENT.md 的解析

- **Decision**: 在 `core` 里用 SnakeYAML（`core` 已依赖它）解析 frontmatter；认识但本 story 不支持的键只打 WARN；`provider.model` 必填。
- **Rationale**: US-1 起连接不再配聊天模型，模型名只能来自 Profile，所以缺了就在发请求前失败。`docs/DemandAnalysis.md` 5.2 还写着"model 可选，默认用 provider 配置的模型"，已经过时，列入文档同步。

## R14 审计写入的重试

- **Decision**: `RetryingAuditWriter` 包装所有落库写入：共 3 次尝试，间隔 200 毫秒、500 毫秒，总时长约 0.7 秒；仍失败抛 `AuditWriteException`。它走 `finally` 之外的路径，被当作"本轮失败"，不当成模型故障去重试模型调用。
- **Rationale**: 0.7 秒落在整轮截止时间的 1 秒容差之内。`LlmCallRecorder.record` 抛出的异常，US-1 已保证"不吞掉、不重发请求"。

## R15 命名与要同步的文档

- 新增 `AgentProfile` 包装 US-1 的 `Profile`，不改 `Profile` 的签名，避免牵动 `ProviderRequest` 和已有测试。
- 实现完成后要同步到技术方案：`tool_invocations` 增加 `in_progress`、`interrupted` 状态和 `updated_at`；`llm_calls` 增加 `failure_kind`、`http_status` 两列；`AGENT.md` 的 `provider.model` 改为必填（`DemandAnalysis.md` 5.2）。

## R16 落库的记录器怎么压过 provider 的日志记录器

- **Decision**: `AgentOS-storage` 的自动配置声明落库的 `LlmCallRecorder`，用 `@AutoConfiguration(beforeName = "com.agentos.provider.config.ProviderAutoConfiguration")` 排在 provider 之前；provider 里的日志记录器带 `@ConditionalOnMissingBean`，于是退让。用字符串引用类名，因为 `storage` 不依赖 `provider`。
- **Rationale**: US-1 就是为此设计的：记录输出可替换，替换时不改 Provider 的调用逻辑（US-1 FR-025）。
- **风险**：自动配置的先后顺序出错时，会悄悄回到日志记录器，审计表就没有模型调用记录。由一个 boot 装配测试固定：容器里只有一个 `LlmCallRecorder`，且是落库的那个。
- **代价**：`LlmCallJsonLogTest` 验证的是日志记录器本身，不受影响；但落库之后，应用日志里不再有 `llm_call` 事件。US-1 把它当作临时输出，spec 也要求落库，这里不再保留日志输出。
