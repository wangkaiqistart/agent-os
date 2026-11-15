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
| 租约在任何终态都释放；启动收尾把遗留轮次和 `in_progress` 工具记录标为已中断 | `AgentServiceTest`、`TurnReaperTest` | US5-4、US5-5、SC-010 |
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

`.env` 里要有 `AGENTOS_DB_URL` 等数据库连接，以及 `AGENTOS_LLM_*`（`primary` 模型）。再给 HTTP 工具开白名单（不是密钥，写在 `.env` 里即可）：

```bash
echo 'agentos.tools.http.allowed-domains=api.met.no' >> .env
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
java -jar AgentOS-boot/target/AgentOS.jar chat --profile weather --message "查一下北京天气并告诉我穿什么"
```

预期：打印带穿衣建议的回答，退出码 0。在数据库里核对（SQL 的取值是预期，不是我跑出来的）：

```sql
select status, iterations from agent_turns order by started_at desc limit 1;        -- SUCCEEDED, 2
select iteration, type, left(content, 40) from session_messages order by id;         -- 用户、助手(工具调用)、工具、助手
select tool_name, status, result_truncated from tool_invocations;                      -- http_get, success, true
select attempt, success, finish_reason from llm_calls order by id;                     -- 两条
```

met.no 的响应约 38000 字符，超过 10000 的上限，所以 `result_truncated` 应为 true。本机访问不了 `api.open-meteo.com` 和 `wttr.in`，met.no 和 `www.7timer.info` 可以。

## 4. 真实模型自动化测试

`@Tag("llm")`，单独开启，默认构建不运行：

```bash
set -a; source .env; set +a
mvn -B verify -Pllm
```

用真实的 `primary` 模型加本地天气替身加 Testcontainers 数据库（需要 Docker），断言：轮次 `SUCCEEDED`、1 次工具调用、2 条模型调用记录。用本地替身而不是真实天气接口，避免公网波动让测试不稳定；真实天气源只在第 3 节手动验证。

## 5. 人工确认

- 迁移脚本只建五张表，不含 `CREATE EXTENSION vector`。
- 启动 `chat` 时，容器里只有一个 `LlmCallRecorder`，且是落库的那个。
- 共享库首次启动 `chat` 会自动执行迁移建表。共享库禁止 Flyway `clean`（宪章）。
