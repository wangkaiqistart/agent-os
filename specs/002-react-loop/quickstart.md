# Quickstart: 验证 US-2 ReAct 循环

**Plan**: [plan.md](plan.md) | 行为契约见 [contracts/](contracts/)，表结构见 [data-model.md](data-model.md)

**2026-11-16 增量状态**：第 9 节是默认主 Agent 的待实现验收指南，命令及新增测试在实施完成后执行；本次规划未运行 Maven、数据库或真实模型。第 2.1 节保留原 Phase 2/3 的实测记录，不能作为新需求通过的证据。

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
| 结果截断并注明原长；返回给模型的与审计里的版本；参数仅脱敏并保留 JSON 结构，不套用结果长度上限 | `GovernedToolCallbackTest`、`JpaToolInvocationRecorderTest` | US2-9、FR-035、FR-037 |
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

### 2.1 Phase 2/3 实测记录（2026-11-15）

本轮在 Docker Desktop 29.9.2 可用的环境执行了：

```bash
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py
```

结果：全仓 `verify` 通过；Provider 220 个测试通过；storage 的 `pgvector/pgvector:pg17` 连通、Flyway V1 五表迁移和 Hibernate schema 校验通过；boot 的 `ReActApplicationTest` 通过本地 HTTP 模型替身完成一次纯文本 chat，并核对 1 个 session、1 个 turn、2 条消息、1 条 `llm_call` 和唯一落库 `LlmCallRecorder`。本次没有启动真实模型，也没有执行工具闭环、并发故障注入或真实天气 Demo；这些属于后续 Phase 4 及之后任务。

随后补充的 Core/CLI/Channel 回归测试共 21 个通过，覆盖 Agent frontmatter 错误、Bootstrap 顺序与热加载、空助手失败、Provider 校验先于建会话、CLI 单消息假 launcher、空行/EOF/`/exit`；同时修复了 Bootstrap 映射顺序在不可变包装后不稳定的问题，并消除了内存契约测试的 JUnit 静态 `@Nested` 发现警告。

## 3. 阶段 Demo 1：查天气穿衣（手动）

`.env` 里要有 `AGENTOS_DB_URL` 等数据库连接，以及 `AGENTOS_LLM_*`（`primary` 模型）。HTTP白名单通过下面合法的环境变量传入，不向.env追加带点的Spring属性（.env还需被shell source）。

```bash
mvn -B -DskipTests package
java -jar AgentOS-boot/target/AgentOS.jar init
```

默认主 Agent 增量实现后，这一步会从已有模型输入生成无工具权限的 main；该 main 不承担天气演示。下面仍显式创建并选择 weather，避免默认无工具权限与天气任务混淆。模型来源不足时 init 应提示补齐，而不是生成不可用 Agent。

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

## 9. 默认主 Agent 增量验收（2026-11-16 已执行）

本节对应 US1 场景 7–14、FR-039/FR-040 和 SC-018–SC-022。增量任务已由 `/speckit-tasks` 追加为 T094–T109（Phase 10，安排在 Phase 3 后、Phase 4 前执行），已按用户指定范围实施并通过下述本地验收，不修改原完成记录。完整参数/退出码规则见 [workspace-and-cli.md](contracts/workspace-and-cli.md)，模型来源格式见 [configuration.md](contracts/configuration.md)。

### 9.1 自动化与证据分层

实施完成后，在仓库根目录运行原质量门：

```bash
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py
```

- 文件与命令测试使用 `@TempDir`、合成 `.env` 和注入的环境查询函数，不读取开发 `.env`，不依赖本机模型变量，不启动 Spring、不联网。注入“如果被调用就失败”的 launcher/低优先级读取器，验证 init 不启动上下文、来源解析确实惰性。
- `DefaultAgentCliIntegrationTest` 为本阶段新增的 boot 数据库测试，使用 `pgvector/pgvector:pg17` 和现有 LocalModelServer。init 经真实 CLI 创建 main，chat 经真实 SpringContextLauncher；隔离环境关闭 `.env` 导入、Embedding 与其他非必要连接。不得将此测试改成直接调用 AgentService 或只捕获 Path 的假 launcher。
- 本机 Docker 不可用时，文件测试仍可通过，数据库装配测试必须标记跳过/未验证；`CI=true` 必须实际执行数据库测试。报告脚本通过不等于每项新增行为已覆盖，还须确认新增测试出现在 Surefire 报告中且未跳过。

| 矩阵 | 预期结果 | 验证位置 / 对应标准 |
|------|----------|-----------------------|
| 空工作区，显式模型；所有外部服务均不可用 | main 可被 AgentLoader 加载，中文通用指令、空 tools，退出 0；零对外连接 | WorkspaceInitializerTest、AgentOSCliTest / SC-018 |
| 参数、进程、合成 `.env` 各自存在及互相冲突 | 严格按优先级选择；高优先级已提供时不读较低来源 | InitModelResolverTest / SC-019 |
| 模型来源缺失、空白、占位符或所选来源格式错误 | 安全失败，退出 2；工作区写入 0；不使用低优先级掩盖 | InitModelResolverTest、InitCommandBehaviorTest / SC-019 |
| 已有合法 main、修改过的 Bootstrap，再传不同模型/不传模型 | 所有旧文件字节不变，只补缺项，main 跳过且不读取模型来源 | WorkspaceInitializerTest / SC-013、SC-019 |
| main 缺少 model、name 不匹配、frontmatter 损坏或含未解析模型占位符 | 指明文件/字段，退出 2，不覆盖、不报告就绪 | AgentLoaderTest、WorkspaceInitializerTest / SC-019 |
| 模型/Provider 名含 YAML 特殊字符，`.properties` 有转义、续行、重复键 | 加载后与输入值一致，tools 仍为空，无 YAML 结构注入；来源格式符合契约 | InitModelResolverTest、WorkspaceInitializerTest / SC-019、SC-022 |
| 路径类型/链接冲突、不可读写、注入中途 I/O 故障、竞争创建 | 返回 1 或赢家合法后跳过，已有文件不覆盖；部分失败不冒报成功，重试只补缺项 | WorkspaceInitializerTest、InitCommandBehaviorTest / SC-022 |
| main + 自定义 Agent，显式覆盖，旧工作区唯一/多个/零 Agent | 依次符合优先级与兼容规则；选中配置损坏时无回退、无 launcher 调用 | ChatCommandTest / SC-020 |
| 高优先级所选 `.env` 无法读取、非法 properties | I/O 返回 1，语法返回 2；不输出原值/异常原文 | InitModelResolverTest、InitCommandBehaviorTest / SC-019、SC-022 |
| 合成配置含测试用秘密标记 | 新建文件和 stdout/stderr 中找不到该标记，不输出 `.env` 原文 | InitCommandBehaviorTest、WorkspaceInitializerTest / SC-022 |
| 同时存在冲突的默认目录、环境 workspace 和指定目录 | 真实模型请求只含指定目录 Agent/Bootstrap 标记，默认目录未读取/修改 | DefaultAgentCliIntegrationTest / SC-021 |

对权限/I/O 故障优先使用可注入写入操作或可控文件系统状态；不能仅依赖 `chmod` 在当前用户下恰好失败。竞争测试只保证不覆盖赢家，不要求所有并发命令都成功。若中途写坏了 main，重试按不合法已有文件返回 2并提示人工处理，不自动修复。

### 9.2 本地文件初始化烟测

以下命令只操作新建临时工作区。示例模型 `local-test-model` 是合成值，不是程序内置默认模型；本段不启动聊天或检验服务端是否支持它。前提是新增参数已实现且执行过构建：

```bash
agentos_demo_workspace="$(mktemp -d "${TMPDIR:-/tmp}/agentos-main-demo.XXXXXX")"
java -jar AgentOS-boot/target/AgentOS.jar init --workspace "$agentos_demo_workspace" --model local-test-model
shasum -a 256 "$agentos_demo_workspace/agents/main/AGENT.md"
java -jar AgentOS-boot/target/AgentOS.jar init --workspace "$agentos_demo_workspace" --model different-model
shasum -a 256 "$agentos_demo_workspace/agents/main/AGENT.md"
```

预期两次退出 0，第二次报告 main 跳过；两次摘要一致。检查共享模板和目录存在，生成文件仅含具体模型名、可选 Provider 名、空工具名单和中文指令，不复制本地连接/凭据。

验证缺少模型时没有任何工作区写入，需排除当前执行目录 `.env` 和进程模型变量干扰：

```bash
agentos_project_root="$PWD"
agentos_no_model_dir="$(mktemp -d "${TMPDIR:-/tmp}/agentos-no-model.XXXXXX")"
(
  cd "$agentos_no_model_dir" || exit 1
  env -u AGENTOS_LLM_MODEL java -jar "$agentos_project_root/AgentOS-boot/target/AgentOS.jar" init --workspace workspace
)
# 上一条命令预期退出码 2，并提示 --model 或 AGENTOS_LLM_MODEL
test ! -e "$agentos_no_model_dir/workspace"
```

末行应成功。无需删除或清空用户原有 `.AgentOS`，也不要为烟测 source 真实 `.env`。

### 9.3 无需手写 Agent 的真实 CLI 装配验收

自动化测试准备临时工作区与冲突的默认目录，经 CLI init 生成 main，不手写 main 内容；然后在选定工作区的 Bootstrap 中放测试标记。注入仅含容器数据库、本地模型替身和禁用 `.env` 导入的隔离 Environment，同时提供与 CLI 路径冲突的低优先级 workspace 值。

调用 `AgentOSCli.execute(realLauncher, "chat", "--workspace", chosenRoot, "--message", "你好")`，不传 `--profile`。断言：

1. 退出码 0，输出替身回答；请求里的模型名等于 init 输入、没有工具定义、system 指令来自所选目录的 main 与 Bootstrap，不含冲突目录标记。
2. root 绑定值与所选目录相同，真正启动的是 NONE 上下文，结束后资源关闭；不能仅以假 launcher 收到 Path 作为通过。
3. 空测试库中有 `profile_name=main` 的 1 session、1 `SUCCEEDED` turn、按序 2 messages、1 llm_call、0 tool_invocations，审计持久化确认；不创建另一个 Agent 的会话。
4. 默认目录内容不变。临时工作区未选择/无法加载时，不回退默认目录；错误分支不调用本地模型。

人工可选地验证真实服务：在已有 Provider/数据库配置可用且完成新增实现后，从仓库根目录对另一个临时目录执行下面两条命令。第一条从进程或执行目录 `.env` 取得模型名，第二条自然选择 main；无需手写 Agent 文件。真实请求不属于普通 `verify`：

```bash
agentos_live_workspace="$(mktemp -d "${TMPDIR:-/tmp}/agentos-main-live.XXXXXX")"
java -jar AgentOS-boot/target/AgentOS.jar init --workspace "$agentos_live_workspace"
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace "$agentos_live_workspace" --message "你好"
```

如果外部配置不可用，chat 应安全失败；不得据此否定已经独立通过的本地文件初始化，也不能把 init 成功当成真实服务连通证明。

### 9.4 实施后记录要求

分别记录文件测试、真实 CLI + 替身 + PostgreSQL、可选真实服务的 PASS/FAIL/NOT_RUN，附实际测试名称和退出码；数据库跳过需单列原因。保留第 2.1 节历史记录，追加本节实测日期和结果，不用旧的 T035 完成状态替代 SC-018–SC-022 的新证据。本地文件、真实 CLI + 替身 + PostgreSQL 已实测 PASS，具体证据见第 9.5 节；可选真实模型/外部数据库服务验收仍为 NOT_RUN。

### 9.5 Phase 10 实施记录（2026-11-16）

T094：用户在修复分析问题后明确执行 `/speckit-implement Phase 10`，作为本次增量 plan 的人工 review 确认；范围仍为 T094–T109，不进入工具故事。Checklist 16/16 通过。实现采用行为测试先行，随后完成 T095–T106；T107 运行证据与 T108 文档同步如下。


**TDD 与修复证据。** 模型占位符、模型来源、init、chat 选择、实际 launcher root 和真实 CLI 装配各先观察行为断言失败，再实现转绿；未用编译错误或数据库跳过替代红灯。会话/循环/Provider 调用逻辑没有另开执行路径。首次全量检查发现候选路径 `getFileName()` 的可空静态告警，补充空值过滤后重跑统一质量门通过，未加规则排除。过程日志保留于本次本机 `/tmp/agentos-phase10-red-{model,resolver,init,chat,launcher,cli-boot}.log` 及对应 green 日志；它们是临时诊断文件，长期结论以下列测试和断言为准。

**T107 统一质量门：PASS。** `mvn -B -ntp spotless:apply` 后，`mvn -B -ntp verify` 返回 0、BUILD SUCCESS（2026-11-16 16:46:46 +08:00，耗时 1:22），320 tests / 0 failures / 0 errors / 0 skipped；格式、Checkstyle、SpotBugs/FindSecBugs、Enforcer 与 ArchUnit 均通过。完整本机日志为 `/tmp/agentos-phase10-verify.log`。本次未启用 `llm` profile，模型 HTTP 请求只发送给测试内 LocalModelServer。

| 验收标准 | 结果 | 当前证据与实际断言 |
|----------|------|--------------------|
| SC-018 首次 main 和无需手写 Agent 的聊天 | PASS | WorkspaceInitializerTest（7 项）/InitCommandBehaviorTest（3 项）验证离线创建、零 launcher；DefaultAgentCliIntegrationTest（1 项）经真实 init/chat、真实启动器、本地模型替身与容器 PostgreSQL 得到回答，main、空 tools、1 session、1 SUCCEEDED turn、2 messages、1 llm_call、0 tool_invocations、租约释放 |
| SC-019 模型来源/无效输入/幂等 | PASS | InitModelResolverTest（11 项）覆盖优先级、惰性解析、无效不回退、properties 格式；AgentLoaderTest（4 项）含模型占位符拒绝；WorkspaceInitializerTest/InitCommandBehaviorTest 及 JAR 烟测验证无模型零写入、已有 main 不读新来源、不覆盖损坏文件 |
| SC-020 Agent 选择 | PASS | ChatCommandTest（6 项）覆盖显式/main/旧工作区候选矩阵、选中坏文件不回退、错误分支零 launcher、空输入和资源关闭；实际默认 main 路径由 DefaultAgentCliIntegrationTest 复验 |
| SC-021 实际 workspace | PASS | SpringContextLauncherTest（2 项）固定规范化 Path、NONE、属性优先级与 close；DefaultAgentCliIntegrationTest 放置冲突默认目录和配置，检查真实请求只含所选 Bootstrap 标记，冲突目录字节不变 |
| SC-022 敏感数据与文件失败 | PASS | InitModelResolverTest/InitCommandBehaviorTest 验证合成秘密不进入输出；WorkspaceInitializerTest 验证 YAML round-trip、文件/目录/链接冲突、确定性中途 I/O 失败及重试、竞争赢家保留、坏 main 不自动修复 |

上述测试数量为各测试类的实际总数，类在多条验收标准中复用，不能累加为新增测试总数。全仓 320 项中 provider 220、core 19、CLI 28、channel-cli 3、storage 16、boot 22、web 11、tool 1。

**数据库：PASS，无跳过。** 本机启动 Docker 后实际使用 Testcontainers 的 `pgvector/pgvector:pg17`，未连共享库。`python3 scripts/check_db_test_reports.py` 返回 0，输出“数据库连通性测试已执行并通过。”；另逐一核对 boot 的 Surefire XML：`DefaultAgentCliIntegrationTest` 为 tests=1/failures=0/errors=0/skipped=0，`SpringContextLauncherTest` 为 tests=2/failures=0/errors=0/skipped=0。真实装配使用隔离 Spring Environment、关闭开发 `.env` 导入和 Embedding；未读取真实开发密钥。

**打包产物烟测：PASS。** 在独立临时执行目录使用合成模型 `local-test-model`：Boot JAR 首次 init 返回 0，第二次携带不同模型仍返回 0，main 的 SHA-256 前后一致（`b78a9c6e3c0481b3c3cdbdf276d0010ac3955997fae1261a09e8d1c8f6da6221`）；移除进程模型变量且执行目录无 `.env` 时返回 2，目标 workspace 不存在；独立 CLI exec JAR init 返回 0；两种 JAR 的 help/version 均返回 0。临时目录自动清理，未清理或改写用户现有 `.AgentOS`。

**T108 文档：已同步。** CLI/boot 代码导读、ClassStructure 和技术方案 8.1/8.2 已按实际实现说明共享 AGENTS 与普通 main Profile 的区别、模型只用于创建、实际 Path 传递及外部连接前提；新增生产代码保留中文意图/边界注释。默认 main 无工具，天气 Agent 需显式指定 `--profile weather`，工具闭环未在本阶段交付。

**未运行与原阶段边界。** 真实模型、外部 Provider/数据库服务与真实天气 Demo 为 NOT_RUN，当前 PASS 指本地文件和模型替身 + 真实容器库；本次未发送真实模型请求。T036–T093（原 Phase 4–9）保持待办，`--new` 当前明确拒绝而非静默忽略，归档仍由原 US4 实施。本节不取代第 2.1 节原有历史记录，也不代表整个 ReAct 达标。


**T109 实施后分析：PASS。** 已按 `/speckit-analyze` 的只读流程重新运行前置检查，核对 spec、plan、data-model、configuration/workspace 契约、tasks、实际源码与 Surefire 证据。范围为本增量的 2 条 FR、5 条 SC、8 个 US1 场景和 16 项任务，覆盖率 100%，未映射任务 0，新增歧义/重复要求 0，CRITICAL/HIGH 问题 0，未发现宪章冲突。只读报告发现 2 项 LOW 文档漂移：任务页尾仍为实施前计数、MEMORY.md 被误述为空文件。结束只读分析后回到实施流程修正描述和任务计数；不涉及 Java 修改，无需重复 Maven。

| 增量需求 | 任务覆盖 | 实现/证据 |
|----------|----------|-----------|
| FR-039 | T095–T101、T107 | InitModelResolver/WorkspaceInitializer/InitCommand、文件及命令测试 |
| FR-040 本次选择与 workspace 部分 | T102–T107 | ChatCommand、ContextLauncher、SpringContextLauncher、真实 CLI 装配测试；归档不在本次范围 |
| SC-018 | T099–T101、T105–T107 | 本地初始化 + DefaultAgentCliIntegrationTest |
| SC-019 | T095–T101、T107 | 来源/AgentLoader/幂等测试和打包产物烟测 |
| SC-020 | T102–T103、T107 | ChatCommandTest 的选择及零启动矩阵 |
| SC-021 | T104–T107 | SpringContextLauncherTest、真实请求的工作区标记和数据库回读 |
| SC-022 | T095、T099–T101、T107 | 合成敏感输入、确定性 I/O、竞争/重试和安全输出测试 |

US1 场景 7–14 分别由上述创建、真实聊天、来源/幂等、选择/兼容、同根加载、敏感数据矩阵覆盖。未新增表/迁移、通用配置系统、真实模型默认常量、工具执行入口或生产 prompt 日志；未冒报 `--new`。T001–T093 的任务正文/勾选与开始实施时一致，用户已暂存的 `.AgentOS` 删除及替代文件未改写，checklist 和 constitution 未修改。扩展配置 `.specify/extensions.yml` 不存在，无 before/after implement/analyze hooks 需执行。变更留在工作区，未提交、推送或发布。
