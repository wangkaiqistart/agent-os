# Quickstart: 验证 US-2 ReAct 循环

**Plan**: [plan.md](plan.md) | 行为契约见 [contracts/](contracts/)，表结构见 [data-model.md](data-model.md)

## 前置条件

- JDK 21、Maven 3.9+，在仓库根目录执行。
- 离线验证（第 1 节）不需要任何 key，也不需要 Docker 和数据库。
- 数据库验证（第 2 节）需要 Docker 在运行。本机 Docker Desktop 已安装，但要先启动，`docker info` 能连上才行。
- 手动演示（第 3 节）需要 `.env` 里有可用的数据库连接和 `primary` 模型。共享库需要 SSL，`AGENTOS_DB_URL` 要带 `sslmode=require`；本 story 的迁移不用 pgvector，所以没装它也可以。

## 1. 离线验证（默认构建）

```bash
mvn -B verify
```

预期：全部模块通过；依赖数据库的测试（`@Tag("db")`）在本机没有 Docker 时被跳过，并打印原因。

| 场景 | 测试 | 对应 |
|------|------|------|
| 无工具调用：1 次模型调用、1 个成功轮次、2 条消息 | `ReActLoopTest` | US1-1、SC-002 |
| 工具调用一次、工具恰好执行一次；结果出现在第二次请求里 | `ReActLoopTest` | US2-1、SC-003 |
| 模型每次都要求工具：恰好 10 次后 `MAX_ITERATIONS`；`max_iterations=3` 时 3 次 | `ReActLoopTest` | US3-1、US3-2、SC-004 |
| 一次响应请求超过单工具上限：超出的不执行，每个有 `limit_skipped` 记录；上限跨迭代累计 | `ToolExecutorTest` | US3-3、SC-005 |
| 整轮超时：`TIMED_OUT`，耗时不超过截止时间加 1 秒，慢工具被停止 | `ReActLoopTest`、`GovernedToolCallbackTest` | US3-4、US3-5、SC-006 |
| 名单外、不存在的工具、非法 JSON 参数：作为错误结果反馈，循环继续；没写 `tools` 时请求里没有工具定义 | `ToolExecutorTest` | US1-6、US2-7、FR-030 |
| 沙箱：域名不在白名单、重定向到白名单外或内网、超过 3 跳，都不向外发请求 | `HttpToolsTest`、`WhitelistSandboxTest` | US2-2 ~ US2-5、SC-016 |
| 结果截断并注明原长；返回给模型的与审计里的版本 | `GovernedToolCallbackTest` | US2-9 |
| 审计：先记后做；失败也有记录；写入失败重试，仍失败则工具不执行 | `GovernedToolCallbackTest`、`RetryingAuditWriterTest` | US5-6 ~ US5-8、SC-007、SC-015 |
| 脱敏：网址密钥、授权字段、已知密钥值，在审计、日志、返回给模型的错误里都找不到 | `RedactorTest`、`RedactionTest` | US5-3、SC-008 |
| 提示词顺序；历史按完整轮次截断，工具请求与结果不拆开；失败轮次只剩用户消息加说明；`AGENT.md` 改动下一轮生效 | `PromptBuilderTest`、`ContextLoaderTest` | US4、SC-011、SC-012 |
| 会话串行：第二条被拒绝且不写入；续租失败立即终止；并发创建只得到一个会话 | `AgentServiceTest`、`SessionManagerContractTest` | US6、SC-009 |
| 数据库可用时确认释放；持续故障返回UNCONFIRMED；无有效租约的RUNNING及终态孤儿in_progress记录由启动/接管收尾，有效租约不清理 | `AgentServiceTest`、`TurnReaperTest` | US5-4、US5-5、SC-010 |
| `init` 幂等、不连数据库；`chat` 的参数、`--new`、退出码 | `InitCommandTest`、`ChatCommandTest` | US1-4、US1-5、US4-7、SC-013 |
| 不存在第二条工具执行路径；运行上下文不走线程局部变量；core 不依赖实现模块 | `ArchitectureRulesTest` | SC-014 |
| 落库的记录器压过 provider 的日志记录器，容器里只有一个 | boot 装配测试 | FR-034 |

## 2. 数据库测试（需要 Docker）

先启动 Docker Desktop，用 `docker info` 确认能连上，然后：

```bash
mvn -B verify
```

Docker 在运行时，`@Tag("db")` 的测试会自动执行，首次要拉取 `pgvector/pgvector:pg17` 镜像。预期：迁移脚本能在空库上建出五张表，Hibernate 校验通过；`jsonb` 写入读回一致（research R7 的 spike）；`SessionManagerContractTest` 等契约测试对 JPA 实现通过；并发取租约只有一个成功。

## 3. 阶段 Demo 1：查天气穿衣（手动）

`.env` 里要有 `AGENTOS_DB_URL` 等数据库连接，以及 `AGENTOS_LLM_*`（`primary` 模型）。HTTP白名单通过下面合法的环境变量传入，不向.env追加带点的Spring属性（.env还需被shell source）。

```bash
mvn -B -DskipTests package
java -jar AgentOS-boot/target/AgentOS.jar init
```

创建 `.AgentOS/agents/weather/AGENT.md`（模型名不能用环境变量，请按 `.env` 里的 `AGENTOS_LLM_MODEL` 手写）：

```markdown
---
name: weather
provider:
  name: primary
  model: <你的 primary 模型名>
  reasoning_effort: low
tools:
  - http_get
---
你是穿衣助手。用户问天气时，用 http_get 请求
https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=39.9&lon=116.4
（这是北京），根据返回的当前气温、风速和降水，给出穿衣建议。
```

```bash
AGENTOS_TOOLS_HTTP_ALLOWEDDOMAINS=api.met.no java -jar AgentOS-boot/target/AgentOS.jar chat --profile weather --message "查一下北京天气并告诉我穿什么"
```

预期：打印带穿衣建议的回答，退出码 0。在数据库里核对（SQL 的取值是预期，不是我跑出来的）：

```sql
select status, iterations from agent_turns order by started_at desc limit 1;        -- 应为SUCCEEDED；iterations按真实响应核对，不固定为2
select iteration, type, left(content, 40) from session_messages order by id;         -- 用户、助手(工具调用)、工具、助手
select tool_name, status, result_truncated from tool_invocations;                      -- http_get, success, true
select attempt, success, finish_reason from llm_calls order by id;                     -- 与实际请求数一一对应，不固定为两条
```

met.no 的响应约 38000 字符，超过 10000 的上限，所以 `result_truncated` 应为 true。本机访问不了 `api.open-meteo.com` 和 `wttr.in`，met.no 和 `www.7timer.info` 可以。

## 4. 真实模型自动化测试

`@Tag("llm")`，单独开启，默认构建不运行：

```bash
set -a; source .env; set +a
mvn -B verify -Pllm
```

用真实的 `primary` 模型加本地天气替身加 Testcontainers 数据库（需要 Docker），断言：轮次SUCCEEDED、至少一次预期http_get、模型和工具次数在配置上限内，记录与实际调用逐一对应。精确1次工具/2次模型的断言只适用于脚本模型替身，不适用于真实模型。用本地替身而不是真实天气接口，避免公网波动让测试不稳定；真实天气源只在第 3 节手动验证。

## 5. 人工确认

- 迁移脚本只建五张表，不含 `CREATE EXTENSION vector`。
- 启动 `chat` 时，容器里只有一个 `LlmCallRecorder`，且是落库的那个。
- 共享库首次启动 `chat` 会自动执行迁移建表。共享库禁止 Flyway `clean`（宪章）。

## 6. 新增的故障与流式验收

- 流式正常工具闭环：delta按序、toolCall/toolResult按ID对应、sink可用且预算充足时正常complete一次、失败error一次；过程sink异常后不再回调，通知预算耗尽可跳过并记录未送达。同步与流式最终文本、工具结果及审计归属一致。
- 250ms单次审计故障、连接/锁等待、200/500ms退避与finally组合注入，检查从创建轮次到返回结果≤deadline+1s；持续故障必须返回turnId和UNCONFIRMED，不将它记为持久化成功。
- 模型审计提交成功但响应丢失后重试，write_id唯一且payload相同，只留一行；工具begin/complete同理。不重放模型或工具。
- 默认300秒轮次取得的租约至少到deadline+30秒；模拟Provider内部两次长请求时不被接管；无有效租约时接管事务原子回收旧轮次。父轮次已FAILED/INTERRUPTED但工具还in_progress，同样能收尾。
- max_iterations=0/负数、turn_timeout非正失败；history任一上限0不带历史，token预算使用90% floor。空/重复工具ID整批零执行、FAILED。

## 7. 七项复查的回归验收

- deadline处模型/工具结束，最终审计进入共享清理预算：数据库正常时失败模型记录和工具timeout终态均在，结果TIMED_OUT+CONFIRMED；数据库挂起时不超过hardStop，返回UNCONFIRMED。
- startTurn提交查证四分支分别验证，不将暂时查无行当成事务回滚，不重写用户消息、不重复外呼。
- 新owner接管后旧owner执行finishAndRelease，已有终态及必要审计/工具/旧lease均确认时返回数据库终态+CONFIRMED，不能清新lease；查询失败才未确认。
- 应用墙钟与数据库时间有偏差、起步连接/锁等待及往返有延迟时，数据库deadline从剩余时长生成，本地预算不增加。
- 注册安全、正常流式与审计事务行为测试分别由T039/T037/T070前置红灯，T049/T060/T077只复验装配与真实I/O。

## 8. 数据库实际时间与输出通知验收

- 两连接：A事务持有会话行锁；B在过期前开启事务并等待锁；让租约或deadline在等待期间过期再释放A。B取锁后必须按clock_timestamp实际时间拒绝过期续租/追加，不能按事务起点now()放行。
- 单参数process与显式NOOP的双参数process行为一致；非空streaming sink走流式，null sink在开始轮次前失败。
- 正常成功收尾后onComplete抛异常或阻塞：TurnResult与数据库仍是已确认成功，不再调用onError，调用方返回不晚于原hardStop；onError抛异常/阻塞同样不改已确定失败结果，不重试通知。
- 如果收尾耗尽剩余预算或过程回调仍阻塞，不并发重入输出端、不为发送终结事件续期；仅记录安全的通知未送达。通知失败不能伪装为持久化失败。
