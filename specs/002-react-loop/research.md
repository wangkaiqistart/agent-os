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
- 0.7秒只是退避总和，不包含3次SQL；每次audit最多250ms且受剩余预算限制。deadline后停止新外呼、工具begin和普通审计退避；deadline前已开始调用返回后的最终模型审计/工具终态也从共享最后1秒出预算，每项最多一次写尝试，提交未知仅有界查证、不重放；它与工具收尾、轮次终态和释放租约共用hardStop。正常超时且必要审计/收尾确认为TIMED_OUT+CONFIRMED。连接/锁/SQL/socket等待必须有界，外层取消不能代替JDBC超时，必须做故障注入验证。
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
