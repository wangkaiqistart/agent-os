# Research: US-2 ReAct 循环

**Date**: 2026-11-15 | **Plan**: [plan.md](plan.md)

**Updated**: 2026-11-16，追加默认主 Agent 的 R20–R24；R1–R19 保留为原研究记录。本次新增结论来自当前源码只读核对与设计分析，不是新的运行实测。

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

- **Decision**: 沿用 `spring.ai.tools.limits.*` 与 `RETURN_ERROR_RESPONSE`。`ToolExecutor` 对比"响应里请求的调用"与"实际执行的调用"，仅在管理器正常返回时，为因次数上限未执行的补写 `limit_skipped`；中途致命异常后未开始的调用不算超限，不计入工具执行审计分母。
- **Rationale**: 探针 B（单工具上限 2，请求 4 次 `get_a`）：前两次执行，后两次得到 `Tool call limit (2) exceeded for tool 'get_a'...` 的纯文本结果，回调没有被调用，所以回调里没法记录它们。管理器从 prompt 里最后一条 `UserMessage` 之后开始计数，所以交给它的 prompt 必须带着本轮累计的全部历史，不能只带本次迭代，否则跨迭代的上限会被重置，要有一个测试专门覆盖。
- 全部调用都声明直接返回时 `returnDirect()` 才是 `true`，混合时是 `false`（探针 C、D）。

## R5 模型给的工具调用 ID 怎么交给审计

- **Decision**: `ToolExecutor` 调用管理器之前，按工具名建一个先进先出的账本：同名工具的调用 ID 按响应里的顺序排队，账本放进 `ToolContext`。`GovernedToolCallback` 执行时取同名队首的 ID。仅管理器正常返回时，账本剩余ID才代表次数超限；致命中止不这样补记。整批执行前验证所有ID非空且本响应内唯一，异常时整批零执行、轮次FAILED，不猜ID。Provider请求没有本次响应ID账本；账本由ToolExecutor在响应后放进重建的Manager Prompt。
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

## R8 租约、接管与回收

- **Decision**: 取租约/续租使用数据库时间，租约至少覆盖本轮deadline加30秒（max(最短TTL150s,剩余本轮预算+30s)）。Provider内部重试和Retry-After都受本轮deadline约束，所以无需修改Provider或另加后台心跳。代价是强杀后回收可能等待剩余本轮时间加30秒。
- core端口TurnStore.startTurn在storage一个短事务中完成会话查找创建、失效旧轮次回收、取租约、建轮次、用户消息写入；不把这些拆成core多次端口调用。turnId在调用前生成，提交结果未知仅按turnId查证，不重放。finishAndRelease同样是单一原子端口。已存在会话的busy请求不更新last_active_at。归档和取租约在数据库条件更新/行锁上串行，不用进程内锁。
- 续租要求owner匹配且lease未过期；消息追加在同一短事务中验证owner、未过期及RUNNING状态，不能用“先检查后在另一事务写入”留下竞态。
- 启动与会话接管均收尾没有有效owner租约的RUNNING轮次，不要求deadline已过；独立扫描终态轮次遗留的in_progress工具行。清理不可影响有效/新owner，幂等不重写终态。
- **Alternatives**: 150秒只按一次Provider timeout计算不安全，因为逻辑调用可能有两次120秒请求；周期心跳有额外生命周期，本story采用覆盖整轮的租约。

## R9 没有 Docker 时的测试分层

- **Decision**: ① `ReActLoop`、`PromptBuilder`、`AgentService`、`ToolExecutor` 面向端口（`SessionManager`、`TurnStore`、`MessageStore` 和两个记录器）测试，用内存实现，不需要数据库；② 端口的契约写成抽象测试基类，内存实现和 JPA 实现各跑一遍，防止两边语义分叉；③ JPA 实现的测试用 Testcontainers（`pgvector/pgvector:pg17`），打 `@Tag("db")`。
- **本机行为**：`docker info` 连不上时，`db` 测试被跳过并打印原因；环境变量 `CI=true` 时不允许跳过，Docker 不可用就让构建失败，避免 CI 悄悄跳过。
- **依赖**：`testcontainers-postgresql` 与 `testcontainers-junit-jupiter` 2.0.5（Maven Central 上有，本地仓库里只有 `testcontainers-bom`，首次构建要下载）。不引入 `spring-boot-testcontainers`，用 `@DynamicPropertySource` 手动给连接属性，依赖更少。
- **Rationale**: 本机 `Docker.app` 已安装，`docker version` 客户端是 29.9.2，但守护进程没有运行（`docker info` 连不上套接字）。所以并非"没有 Docker"，启动 Docker Desktop 后这些测试就能在本机跑。宪章禁止 H2 和 SQLite，也禁止对共享库做破坏性操作，不用它们替代。
- **风险**：SQL 层的问题只有在有 Docker 时才会发现。CI 不允许跳过，就是为了守住这一条。
- **Phase 1落实（2026-11-15）**：storage测试通过自定义JUnit ExecutionCondition先检查Docker，本地无Docker禁用db测试、CI无Docker报配置错误；随后才由官方Testcontainers扩展启动静态容器。PostgresIntegrationTest通过DynamicPropertySource提供容器连接，不加载.env。PostgresConnectivityTest仅执行SELECT 1，不提前实现迁移/JSON映射spike。
- core的test-jar绑定process-test-classes，storage以test scope复用；官方工具自动配置仅加core/tool的test依赖。boot后续数据库测试使用boot自身小型夹具，暂不引入storage测试JAR，避免正式架构扫描混入测试JAR。测试依赖不传递，需要时boot单独声明Testcontainers；自动配置imports等到实际有配置类时登记，不提前创建不存在类的条目。
- 本地Docker启动状态会变化；本次实施最初不可达，后续已经可达（Server 29.9.2），真实连通性测试首次需要拉取pgvector镜像。最终是否成功以quickstart的实际运行记录为准，不沿用研究时“未运行”的结论。

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
- **Rationale**: US-1 起连接不再配聊天模型，模型名只能来自 Profile，所以缺了就在发请求前失败。该说明已经在提交9d3cf6e更正，本story只保持一致，不再列为未解决项。

## R14 审计重试、幂等性与硬截止预算

- **Decision**: 只重试模型/工具审计的瞬时数据库写入错误，最多3次，等待200/500ms。每次事务在等待前结束；约束、参数、协议错误不重试。消息追加、租约、建轮次等不统一包进重试器。
- 模型审计每次LlmCallRecorder.record入口生成一次UUID write_id，单次重试复用；数据库UNIQUE write_id，冲突读取并比对相同payload后视为成功，不同payload报错。工具begin复用(turn,iteration,tool_call_id)唯一键；complete对同一行幂等更新；不重发模型、不重复执行工具。不改US-1的调用逻辑。
- `TurnExecutionBudget`用单调时间，TurnBudgetRegistry按turnId显式提供给只有LlmCallRecord参数的记录器。TurnRunner有界等待到deadline+1s，到hardStop必要持久化仍未确认时取消并返回UNCONFIRMED，仅终结通知未返回则保留已确认结果；移除登记后迟到record失败关闭，不能拿到新预算。
- 0.7秒只是退避总和，不包含3次SQL；每次audit默认2秒、可配置1ms～5秒且受剩余预算限制。deadline后停止新外呼、工具begin和普通审计退避；deadline前已开始调用返回后的最终模型审计/工具终态也从共享最后1秒出预算，每项最多一次写尝试，提交未知仅有界查证、不重放；它与工具收尾、轮次终态和释放租约共用hardStop。正常超时且必要审计/收尾确认为TIMED_OUT+CONFIRMED。连接/锁/SQL/socket等待必须有界，外层取消不能代替JDBC超时，必须做故障注入验证。
- 远程数据库往返回归补充（2026-11-17）：statement_timeout/lock_timeout在一条SQL内设置；新随机write_id的正常模型审计直接INSERT，依赖唯一约束去重，异常后再查证完整载荷。查到不同载荷立即失败，不把冲突当作未提交继续重试。减少往返而不扩大250ms上限；独立PostgreSQL测试按每次SQL/提交注入等待，同时验证可完成及超过预算两条路径。
- 扩大验证发现工具路径也需要减少往返：begin/skipped直接INSERT并在异常后核对唯一键与完整载荷；requireRunning通过MATERIALIZED CTE顺序取会话锁、读取父轮次，再检查当前数据库时间。真实FOR UPDATE竞争测试确认等锁期间deadline到达后会拒绝；complete继续锁工具行，不能覆盖回收终态。新回归同时验证十轮历史和一次审计失败后同一会话的下一轮恢复。
- 正常收尾在一个短事务写轮次终态、终结in_progress工具并释放本owner租约。持续数据库故障不可能保证立即持久化，返回安全失败及persistenceStatus=UNCONFIRMED，停止外呼，不伪装已入库，也不承诺恢复缺失模型记录；数据库恢复后由租约失效回收收敛。
- **Alternatives**: 对所有写入自动重试会重复消息和审计、误判lease；使用线程局部变量传deadline违反宪章；增补持久化补偿队列超出本story，均不采用。

## R15 命名与要同步的文档

- 新增 `AgentProfile` 包装 US-1 的 `Profile`，不改 `Profile` 的签名，避免牵动 `ProviderRequest` 和已有测试。
- 实现完成后要同步到技术方案：`tool_invocations` 增加 `in_progress`、`interrupted` 状态和 `updated_at`；`llm_calls` 增加 `failure_kind`、`http_status` 两列及模型审计去重的write_id；AGENT.md模型必填说明已更正。

## R16 落库的记录器怎么压过 provider 的日志记录器

- **Decision**: `AgentOS-storage` 的自动配置声明落库的 `LlmCallRecorder`，用 `@AutoConfiguration(beforeName = "com.agentos.provider.config.ProviderAutoConfiguration")` 排在 provider 之前；provider 里的日志记录器带 `@ConditionalOnMissingBean`，于是退让。用字符串引用类名，因为 `storage` 不依赖 `provider`。
- **Rationale**: US-1 就是为此设计的：记录输出可替换，替换时不改 Provider 的调用逻辑（US-1 FR-025）。
- **风险**：自动配置的先后顺序出错时，会悄悄回到日志记录器，审计表就没有模型调用记录。由一个 boot 装配测试固定：容器里只有一个 `LlmCallRecorder`，且是落库的那个。
- **日志保留**：落库记录器在数据库确认成功后写同一份脱敏结构化llm_call元数据，每次record最多一条，不在每次SQL重试重复输出；数据库写入失败时ERROR保留脱敏后的相同调用元数据、write_id和persistenceStatus=UNCONFIRMED，不含响应体/Prompt/key；这是故障诊断证据，不当作审计落库成功。原LlmCallJsonLogTest继续验证日志实现，另用装配测试验证落库+日志。

## R17 端口、流式与配置边界

- core新增ToolCatalog端口由tool实现；ProviderCatalog.validate(Profile)只读外呼前校验由provider的现有RequestValidator实现，不更改Provider的call/stream/retry逻辑。所有新接口和配置变化先测后实现。
- 流式必须验证完整正常链：delta→toolCall→toolResult→后续delta→唯一complete；sink可用且有剩余通知预算时错误终态最多一次error，不能complete和error都发；过程sink异常终止后不再回调该sink，终结通知失败不改已确认结果。
- settings合法范围固定：maxIterations≥1、turnTimeout>0、两个history上限≥0；任一history上限0时不带历史，正token预算用90% floor，避免“留余量”不可测。
- Demo的真实模型调用次数不固定，按响应核对有界闭环和审计一一对应。精确一次工具/两次模型仅用于脚本替身，不把真实模型行为写成确定性测试。

## R18 复查闭合：提交查证与时钟（2026-11-15）

- startTurn预生成turnId，起步前建立单调预算。确认已提交且RUNNING/owner/预算有效则继续；确认已提交但不可继续则收尾；确认原事务已结束且未提交才报启动失败；仅查无行不等于未提交，仍未知返回本地FAILED+turnId+UNCONFIRMED。不重放起步事务。
- finishAndRelease更新0行先查旧轮次：若被新owner回收且必要审计/旧工具/旧owner租约均已收敛，返回数据库终态+CONFIRMED；不覆盖不同终态、不清新owner；无法查证才UNCONFIRMED。
- 数据库deadline使用取得连接/行锁后的数据库clock_timestamp()+本地剩余时长，本地单调预算不重新起算，连接/SQL/往返都扣预算。消息写入既要本地未截止也要数据库owner/lease/deadline条件有效；Provider的Instant只作为当前剩余时长的适配。
- 在途调用最终审计可在deadline之后、hardStop之前写入，避免把正常数据库下的超时模型记录丢掉；不增加第二份清理预算。对应故障/时钟/已收敛用例前置于实现，后续HTTP与装配测试只做集成复验。

## R19 数据库实际时间与输出回调（2026-11-15）

- PostgreSQL的now()/transaction_timestamp()固定为事务开始时刻，等待行锁后不会更新；clock_timestamp()才返回实际当前时间。租约和deadline实时判定必须取得所需行锁后捕获db_now=clock_timestamp()，本操作统一使用该值；普通created_at默认值可用now()。依据：[PostgreSQL当前时间函数](https://www.postgresql.org/docs/current/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT)。补两个连接锁等待跨过有效期的测试，不只在无锁路径测时间。
- 输入ProcessRequest不含回调；process(request)委托process(request,TurnSink.NOOP)，双参数重载同一执行链路，sink非空。CLI使用单参数，测试/后续适配器用双参数选择流式。
- 过程回调失败在终态确认前按取消处理；终结通知是结果通知，不是新的业务执行。必要持久化处理结束后才在受管虚拟线程尝试一次complete或error，只等待剩余hardStop；已失效、过程回调未退出或无剩余预算则跳过并安全记录未送达。异常/阻塞超时不改确认终态或persistenceStatus，不再补另一种终结事件。取消不响应中断的回调不能声称已强制杀死，但系统不再向它发送事件或推进外呼/消息写入。

## R20 默认主 Agent 的现有支点与缺口（2026-11-16）

> R20–R24 保留实施前的研究快照，其中“当前”“尚未执行”均描述研究时的基线。Phase 10 实施后跟进：InitModelResolver/DefaultAgentOptions、CREATE_NEW 初始化、main 选择与 `launch(Path)` 已落地；LocalModelServer 已捕获请求，新增真实 CLI + PostgreSQL 测试通过。运行证据见 [quickstart.md](quickstart.md) 第 9.5 节；真实模型未运行。

- **Decision**: 在既有 `002` 中增量实现，不新建 feature、不重开 T001–T035。默认 `main` 使用现有 Agent 目录、frontmatter、Profile 和处理入口，不引入“主 Agent 引擎”或额外数据库实体。
- **Rationale / 当前证据**：`WorkspaceInitializer.initialize` 只创建共享目录、Bootstrap 和记忆文件；`InitCommand` 只有 `--workspace`，没有模型输入。`AgentLoader` 可脱离 Spring 直接使用 SafeConstructor 解析并验证 name/model/settings；它不做环境变量插值，当前仅拒绝空模型、不拒绝 `${...}`。`CoreAutoConfiguration` 从相同 `WorkspaceProperties.root` 构造 AgentLoader 与 ContextLoader，所以实际工作区传递无须改动循环。
- **Alternatives considered**：把配置写进根部 `AGENTS.md` 会混淆共享自然语言指令和 Profile；给连接重新增加默认模型会推翻 US-1 模型归属；另建 main 运行引擎没有必要，均不采用。
- **证据**：[初始化器](../../AgentOS-cli/src/main/java/com/agentos/cli/WorkspaceInitializer.java)、[init 命令](../../AgentOS-cli/src/main/java/com/agentos/cli/InitCommand.java)、[加载器](../../AgentOS-core/src/main/java/com/agentos/core/agent/AgentLoader.java)、[core 装配](../../AgentOS-core/src/main/java/com/agentos/core/config/CoreAutoConfiguration.java)、技术方案 8.1“生成默认 Profile”。

## R21 不启动 Spring 的模型名解析

- **Decision**: 新增 CLI 内小型 `InitModelResolver`，注入环境查询与执行目录便于隔离测试；结果为非敏感 `DefaultAgentOptions`。只在 `main` 缺失时按已约定优先级读取输入，已有合法 main 不读取较新模型配置。显式提供但无效与完全缺失分开处理，不回退掩盖错误。
- **Rationale / 当前证据**：当前没有可直接复用的无 Spring `ConfigLoader`；boot 的 `application.yaml` 用 `spring.config.import` 导入 `.env[.properties]`，只有启动上下文时生效。为 init 启动这个上下文会触发数据源等重型配置，违反 FR-039。模型名属于 Agent，必须在生成时成为具体字符串，不能让已有 AgentLoader 猜测展开规则。
- **格式选择**：使用 JDK `Properties.load(Reader)` 的语法，以 UTF-8 Reader 读取；通过只保留目标键的 Properties 投影适配，使结果不持有其他配置项。解析重复键采用最后定义生效，支持标准转义/续行；引号不是 shell 引号，`export KEY=...` 也不是模型项，均不新增 shell 语义。含占位符、空白值和读取/解析失败输出安全文案，不拼接原始异常、内容或环境变量值。测试覆盖中文、`=`/`:`、转义、续行、重复键、缺失、无效高优先级以及低优先级未读取。
- **Alternatives considered**：不使用 `source`、dotenv 新依赖或全局 Spring Environment；不手写 `split("=")` 冒充 `.properties` 解析；不将 `.env` 的全部键保存到初始化结果。解析器只服务这一输入，不扩展为通用配置加载系统。
- **证据**：[入口配置](../../AgentOS-boot/src/main/resources/application.yaml)、[CLI 依赖](../../AgentOS-cli/pom.xml)、[加载器](../../AgentOS-core/src/main/java/com/agentos/core/agent/AgentLoader.java)。未读取用户实际 `.env`。

## R22 幂等、字段校验和生成文件

- **Decision**: 所有预检查先于目录/文件写入；已有 main 复用 AgentLoader 做结构校验，补齐模型未解析占位符的拒绝规则；不验证远端 Provider 是否存在。缺失 main 的 YAML 由结构化序列化生成，只含非敏感参数与固定指令。逐文件 `CREATE_NEW`，已存在内容保留，路径类型/受管理链接冲突返回安全错误。
- **Rationale / 当前证据**：原初始化器是 `exists → writeString`，不是原子创建，存在竞争覆盖窗口；对已有目录也没有类型检查。对任意模型/Provider 名直接字符串拼接还可能改变 YAML 结构。现有加载器拒绝 Agent 末级软链接，初始化与候选扫描应保持一致。
- **恢复边界**：工作区不提供跨文件事务；中途失败已创建文件保留，完整旧文件不覆盖。竞争创建时校验现有赢家；若留下不完整 main，重试明确返回字段/文件错误，不能自动重写。生成前缺少模型名时工作区写入次数必须为 0。
- **Alternatives considered**：不删除旧工作区重建、不使用覆盖写后备份、不将默认密钥写入模板、不为这次初始化引入数据库事务或持久化任务队列。选择 create-new 保证不覆盖，不宣称所有并发 init 都一定成功。
- **证据**：[WorkspaceInitializer](../../AgentOS-cli/src/main/java/com/agentos/cli/WorkspaceInitializer.java)、[AgentLoader](../../AgentOS-core/src/main/java/com/agentos/core/agent/AgentLoader.java)。这些修复尚未实施。

## R23 工作区必须到达真实加载器

- **Decision**: `ContextLauncher.launch(Path workspaceRoot)` 替代无参方法；ChatCommand 传规范化绝对路径，SpringContextLauncher 通过 `.run("--agentos.workspace.root=" + workspaceRoot)` 传入。所有内部实现和测试同步迁移，不留忽略参数的兼容回退。
- **Rationale / 当前证据**：当前 ChatCommand 的 `--workspace` 只用于扫描，实际 `launch()` 没有参数；boot 的 `.web(NONE).run()` 又使用默认配置，造成“选得到、加载不到”。现有 core 装配已把两个加载器绑定同一 root，问题在入口到 Spring 的传参。CLI 的参数配置源优先于普通环境/配置文件，能让选择与加载一致。
- **选择行为**：显式 profile 优先；默认 main 路径存在就选择并校验，损坏/冲突不回退；没有 main 才沿用唯一目录/多目录/零目录规则。候选流及时关闭，不扫描非目录或末级链接。选中项的本地错误在 launcher 前失败；运行时 Provider 校验仍保留。
- **测试 seam**：boot 增加包内启动器构建工厂注入，只让测试提供隔离 Environment：测试属性优先于 application.yaml 与进程环境，但仍低于 launcher 注入的 `commandLineArgs` 工作区；设置 `spring.config.import` 为空、使用容器数据库和本地模型。生产不新增用户参数，不设置全局系统属性。
- **Alternatives considered**：单纯改目录扫描不能修复实际加载；builder 的 `.properties(...)` 是低优先级默认属性，可能被环境覆盖；`System.setProperty` 污染同 JVM 其他命令/测试，均不采用。
- **证据**：[ChatCommand](../../AgentOS-cli/src/main/java/com/agentos/cli/ChatCommand.java)、[ContextLauncher](../../AgentOS-cli/src/main/java/com/agentos/cli/ContextLauncher.java)、[SpringContextLauncher](../../AgentOS-boot/src/main/java/com/agentos/boot/SpringContextLauncher.java)、[WorkspaceProperties](../../AgentOS-core/src/main/java/com/agentos/core/config/WorkspaceProperties.java)。

## R24 退出码与端到端证据

- **Decision**: 命令入口明确返回退出码或统一映射安全错误：init 的缺少/无效输入和损坏 Agent 为 2，路径/I/O 失败为 1，成功为 0；chat 保持现有契约的 0/1/2/3。选用 Picocli 的 `Callable<Integer>` 或等价集中处理，所有对外文案不得拼接底层异常原值；不增加新的日志/凭据配置层。
- **Rationale / 当前证据**：当前 InitCommand 和 ChatCommand 均为 Runnable，包装 PicocliException 后未实现契约中的完整分类。`AgentOSCliTest` 还包含“没有模型输入的 init 成功”断言，须改为显式测试模型或已有合法 main；不能为保旧断言降低新要求。现有 `ReActApplicationTest` 手写 answer、直接调用 AgentService，只证明处理主链路落库，没有证明 CLI 初始化和默认选择。
- **验收设计**：扩充 CLI 文件/命令矩阵；新增 `DefaultAgentCliIntegrationTest`，经 CLI init 生成 main，再由 CLI chat 调真实 SpringContextLauncher，通过 Testcontainers PostgreSQL 和已有 LocalModelServer 返回一条纯文本。现有替身只有调用计数和固定响应，须在测试源码内补充请求捕获，检查实际模型、无 tools、指定目录 Bootstrap；不得新增生产 prompt 日志。数据库只出现 main 的 1 个会话、1 个成功轮次、2 条消息、1 条模型调用，工具记录为 0。另放冲突默认目录和配置源，证明实际加载没有落回默认 root。
- **Alternatives considered**：仅断言 launcher 收到路径不等于实际加载正确；复制手写 main 的原集成测试绕过初始化；使用共享库或真实模型会破坏隔离与普通验证边界，均不采用。`--new` 原任务保留，不借本次增量标完成。
- **证据**：[CLI 测试](../../AgentOS-cli/src/test/java/com/agentos/cli/AgentOSCliTest.java)、[选择测试](../../AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java)、[原纵向测试](../../AgentOS-boot/src/test/java/com/agentos/boot/ReActApplicationTest.java)、[模型替身](../../AgentOS-boot/src/test/java/com/agentos/boot/support/LocalModelServer.java)。本轮未执行这些新增验收，待任务实现。

## R18 远程数据库提交超时的预算与连接安全修复（2026-11-17）

- 新日志表明优化SQL往返后仍在COMMIT响应处超时，随后查证也可能因250ms上限失败；不能仅凭08006认定数据库宕机。原先60～65ms模拟与五轮真实验收不代表任意抖动下稳定。
- 默认审计尝试改为2秒、配置上限5秒，并继续按原轮次预算压短；不扩大deadline后的共享1秒，不改constitution或质量门。SQL重试次数仍最多三次。
- 审计等待与工作线程共用一个绝对操作时限，调度等待不能重置预算；超时/取消不interrupt JDBC/TLS工作线程。PostgreSQL statement/lock timeout、JDBC networkTimeout和提交前检查独立约束事务生命周期。未知提交只查证、不重放模型、工具或数据库写入。
- 事务配置SQL完成后再次检查预算，避免迟到配置之后再开始业务写入；事务结束恢复networkTimeout，恢复失败时abort连接。测试使用真实独立PostgreSQL确认回滚原子性、连接autocommit/networkTimeout/SET LOCAL恢复，无idle in transaction。
- 修复版可用独立final-name构建，避免覆盖正在运行的fat JAR；标准构建名称保持AgentOS。
