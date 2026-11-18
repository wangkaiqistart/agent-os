# Quickstart: AgentOS 工具注册与安全执行

## Prerequisites

- JDK 21 and Maven available.
- Docker available for PostgreSQL Testcontainers; default image is `pgvector/pgvector:pg17`.
- Repository root: `/Volumes/Data/my_project/agent-os`.
- Do not load production credentials or real business data into tests.
- Default tests use local HTTP substitutes and do not call real external services.

## 本次 Phase 1–3 验证记录（2026-11-18）

已执行并通过：

```text
mvn -B -ntp -pl AgentOS-core,AgentOS-tool -am test   PASS
mvn -B -ntp -pl AgentOS-storage -am test             PASS (63 tests; PostgreSQL Testcontainers)
mvn -B -ntp -pl AgentOS-boot -am test                PASS (64 tests; PostgreSQL Testcontainers)
```

新增证据包括：工具定义缺少 name/description/inputSchema 时拒绝、Agent 重复授权拒绝、未知工具只返回受治理的安全拒绝、请求级 callback 优先、单一官方 `ToolCallingManager`/resolver/processor 装配，以及未知 Agent 工具在创建轮次前失败。无工具 Agent 的模型请求不包含 `tools` 定义。

Storage 与 Boot 测试已使用 Docker Testcontainers 启动 `pgvector/pgvector:pg17`，PostgreSQL 集成场景实际执行并通过；没有使用 H2、SQLite 或共享数据库替代。测试期间出现的数据库超时、重复键和审计未确认日志均来自主动覆盖的预期故障场景，未造成测试失败。

Phase 1–3 的完整质量门 `mvn -B -ntp verify` 已通过；`python3 scripts/check_db_test_reports.py` 已确认 PostgreSQL 连通性测试实际执行并通过。该脚本只检查连通性用例，不能代替其余数据库测试报告。

### V1 旧库兼容回归

直接修改已执行的 V1 会触发 Flyway checksum mismatch，导致 `chat` 在启动阶段失败。本次修复恢复原始 `V1__core_tables.sql`（校验和 `129473985`），并通过 `V2__tool_invocation_unconfirmed_status.sql` 扩展工具审计状态，不修改 Flyway 历史记录。

`MigrationTest.upgradesOriginalV1WithoutRepairOrChangingHistory` 使用固定历史 V1 快照和独立 PostgreSQL Testcontainers：先确认修复前复现 `129473985` 与 `-1073444995` 不匹配，再验证恢复 V1 后仅执行 V2、旧会话/轮次/工具记录逐字段保持不变、可写入 `unconfirmed`，并且重复迁移没有新增执行。新库同时验证完整 V1 + V2 初始化。全部验证不连接共享数据库，也不执行 repair 或 clean。

迁移修复后的 `mvn -B -ntp verify` 于 2026-11-18 14:20（Asia/Shanghai）通过：Storage 64 个测试、Boot 64 个测试均无失败或跳过；数据库连通性报告门禁通过。已重新生成 `AgentOS-boot/target/AgentOS.jar`，检查其嵌套 Storage JAR 确认包含与源码一致的原始 V1、新增 V2，且不包含历史测试快照。共享数据库启动升级尚未执行。

对于日志中已执行原始 V1 的数据库，重新构建后可由用户再次运行 `java -jar AgentOS-boot/target/AgentOS.jar chat`；正常启动应显示 V1 校验通过并迁移到 V2。若数据库已应用修改过的 V1 或已被 repair，需要另行核对历史，不能把上述旧库回归当作该情形的验证。

### US1 CLI 验收收尾

未知工具配置的错误定位已修复：`UnknownAgentToolException` 由 core 定义，ToolRegistry 生成包含 Agent 相对文件、tools 字段和具体工具名的提示，CLI 只定向展示该类型；其他异常原文和 cause 继续隐藏。

新增回归先实际复现失败，再完成修复：目录提示漏工具名、真实 CLI 隐藏配置位置。`CliAcceptanceIntegrationTest.unknownToolReportsAgentFileAndNameWithoutExternalCallsOrRecords` 使用真实装配和 PostgreSQL 验证退出码 2、零模型请求及零新增会话/轮次/消息/审计记录；CLI 单测额外验证 cause 和其他参数错误不泄露。

修复后 `mvn -B -ntp verify` 通过：521 项测试，失败 0、错误 0、跳过 0；数据库连通性报告检查通过。使用新 JAR 和全新 Testcontainers 数据库重跑六项 CLI 用例，全部 PASS，包括未知工具错误中的配置文件与具体名称。临时模型、HTTP 服务和数据库容器均已清理；没有连接真实模型或业务数据库。

复现步骤见 [manual-us1.md](./manual-us1.md)，实测报告见 [manual-us1-results.md](./manual-us1-results.md)，安全统计与数据库读回见 [us1-cli-evidence-2026-11-18.json](./fixtures/us1-cli-evidence-2026-11-18.json)。Phase 4 及后续阶段继续保留未完成状态。

## 1. Static and unit validation

From repository root:

```bash
mvn -B -ntp -pl AgentOS-core,AgentOS-tool -am test
```

Expected:

- Tool registry rejects unknown and duplicate definitions.
- Empty tool lists expose no callbacks.
- Governed callbacks write begin before delegate execution.
- Begin failure yields zero delegate calls.
- Completion failure reports possible execution and does not retry the delegate.
- Idempotent tools retry at most once for a clearly transient, definitely failed attempt; non-idempotent tools and unknown completion never retry.
- Ordinary tool results default to 10000 characters; `resultChars` and `resultTruncated` are persisted.
- Sandbox rejects non-whitelisted domains, illegal schemes and user-info.
- HTTP tests cover redirect, response size, non-2xx and cancellation boundaries.

## 2. Storage and audit integration

```bash
mvn -B -ntp -pl AgentOS-storage -am test
```

Expected:

- Flyway creates `tool_invocations` in PostgreSQL.
- The unique key is enforced.
- `unconfirmed` is available when completion persistence cannot be confirmed.
- Begin and complete use independent short transactions.
- Transient persistence failures are bounded by audit retry policy.
- Recovery changes orphaned rows to `interrupted` without modifying valid-owner rows.

If Docker is unavailable, report the storage integration as NOT_RUN; do not replace it with H2 or SQLite.

## 3. Boot end-to-end validation

```bash
mvn -B -ntp -pl AgentOS-boot -am test
```

Expected:

1. Agent declaring `http_get` sends only that tool definition to the model substitute.
2. The model requests `http_get`; the local HTTP substitute receives the request only after an `in_progress` row is confirmed.
3. The next model request receives the tool response with the same call ID.
4. The final `tool_invocations` row is `success`, `failed`, `timeout`, `sandbox_denied`, `interrupted` or `unconfirmed` as applicable.
5. Logs and errors contain no prompt, API key, full tool arguments or response body when the body is over the cap.
6. Sustained model/tool/body delays finish within the configured deadline plus the one-second cleanup allowance.

Useful existing tests include `ReActApplicationTest`, `ToolAuditRedactionTest`, `SessionConcurrencyIntegrationTest` and `ReActWeatherSmokeTest`.

## 4. Deadline acceptance matrix

Run five local substitute scenarios—slow model, slow tool, slow body, audit wait and sustained streaming—20 times each. Record p95 for each group and require every group to finish within the configured budget plus one second. Streaming deltas must not move the deadline.

## 5. Feature demos

Run and record evidence for these three demos:

1. Authorized `http_get` returns a redacted, length-limited result.
2. Non-whitelisted `http_get` is rejected with zero substitute-server requests.
3. Slow, oversized or audit-unconfirmed execution returns the safe error, records the correct audit status and does not retry an uncertain action.

The broader AgentOS daily weather, technology-news and GitHub-news demos belong to their respective feature acceptance paths and are outside this feature's demo task.

## 6. Full quality gate

```bash
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py
```

Expected: all modules pass Spotless, Checkstyle, SpotBugs/FindSecBugs, ArchUnit, unit tests and PostgreSQL integration tests. The database report checker must not show missing or unclassified database tests.

## 7. Optional real-model smoke

Only when explicitly enabled:

```bash
mvn -B -ntp -Pllm -pl AgentOS-boot -am -Dtest=ReActWeatherSmokeTest verify
```

Use a dedicated approved HTTP endpoint in `agentos.tools.http.allowed-domains`. Never use production credentials or sensitive business data. If required environment variables are absent, mark the smoke test SKIPPED rather than weakening default verification.

## Acceptance evidence

Record:

- visible tool definitions sent to the model;
- model/tool call counts and skipped call counts;
- local substitute request count;
- audit row identity, status, attempts and persistence confirmation;
- retry eligibility, attempt count and shared-budget consumption;
- elapsed time versus the shared deadline;
- redaction checks over user output, logs and database fields.

## Phase 4 / US2 实施记录（2026-11-18）

- 红灯：`GovernedToolCallbackTest` 实际复现超长标记、非对象/尾随 JSON 被接受、幂等且明确未开始动作的瞬时故障没有重试；PostgreSQL `JpaToolInvocationRecorderTest` 实际复现 complete 失败后原行仍为 `in_progress`。
- 修复：新增 `TransientToolException(executionStarted)` 契约，只有幂等且明确未开始动作的瞬时故障最多重试一次；普通异常、超时、未知完成、审计失败和租约丢失不重试。两次尝试共享一个工具 deadline、模型调用 ID 和审计行；实际尝试数写入 `attempts`。
- 修复：只接受单个 JSON 对象；先脱敏再截断，原始字符数保留在审计元数据中，截断标记计入结果上限。极小字符上限无法容纳完整标记时保留标记前缀，完整原长仍可从审计查询。
- 修复：complete 未确认时，在原 hard stop 内尽力将同身份的原 `in_progress` 行改为 `unconfirmed`；不覆盖已确认或回收终态。数据库持续不可用时仍报告 UNCONFIRMED，原意图交由恢复路径处理，不伪称标记已持久化。
- 定向测试通过：GovernedToolCallbackTest、AuditFailureTest、JpaToolInvocationRecorderTest。整阶段 `mvn -B -ntp -pl AgentOS-boot -am test` 通过，包括 PostgreSQL 审计、恢复、唯一 Manager、完整历史、returnDirect、sink 故障和 Boot 工具闭环。后续 Phase 6 补齐重试次数与官方工具限次的联合验收。

## Phase 5 / US3 实施记录（2026-11-18）

新增 HTTP 红灯测试确认：已取消预算仍会发送请求，1 MiB 超限仅返回模糊的网络失败。修复后 `mvn -B -ntp -pl AgentOS-tool -am test` 通过（Tool 36 项测试，无失败、错误、跳过）。

- GET 允许路径正常返回，三次相对跳转共四个请求；第四次跳转及跨域拒绝不发送下一跳。
- 非白名单初始地址、非法协议、user-info URL、已取消或耗尽预算均零网络请求。
- 模型 Schema 只有 `url`，不暴露 ToolContext。
- 正文超过 1 MiB 只返回固定“HTTP 响应正文超过大小上限”，无正文、原始 cause 或自动重试；本地服务计数为 1。
- 非 2xx、缺失/非法 Location、慢正文均有安全错误；配置归一化与非法范围检查通过。五类 p95 重复验证见 Phase 6。

## Phase 6 / US4 实施记录（2026-11-18）

新增红灯测试确认回调内部重试未计入单工具上限；另确认超长审计参数和错误未受长度限制。修复后治理输入摘要最多 10000 字符、错误摘要最多 1000 字符，均先脱敏后截断。

- 官方 Manager 仍为唯一入口，按本轮累计工具消息限制模型调用；`TurnExecutionBudget` 只补记回调内部的实际尝试，自动配置复用同一 `ToolCallingProperties` 的默认/按名称/总数配置，不建立第二个 Manager。
- 默认单工具 10 次；五次各重试一次消耗 10 次后，下次调用执行为 0，审计为 `limit_skipped`。只有重试被拒绝而之前已有失败尝试时，原调用保留 `failed` 和实际 attempts，避免伪称整次调用未执行。
- Boot 联合验证：重试工具执行 2 次 + HTTP 执行 1 次消耗总预算 3；下一次 HTTP 为 0，审计 `limit_skipped/attempts=0`，四次模型请求后正常回答；同批三个同名调用与下一迭代调用累计只执行 2 次，对应两条跳过记录。
- 模型默认 10 次、覆盖 3 次、最后一次正常回答与 returnDirect、同步/流式 ID 回填、致命中止与 sink 故障均由 core 独立验收覆盖。
- `mvn -B -ntp -pl AgentOS-boot -am -Dtest=GovernedToolCallbackTest,GovernanceDeadlineAcceptanceTest,HttpToolsDeadlineTest,TurnDeadlineTest,ReActLoopLimitsTest,ToolCallLimitsTest,ToolExecutorTest,ReActLoopStreamingTest,ReActDeadlineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过；包含真实 PostgreSQL 的 5 项 Boot 新增联合用例。
- 五类截止时间场景各运行 20 次；测试内排序计算第 19 个样本为 p95，每类须在原预算 + 1000ms 内返回；最终质量门重新运行并记录最终数据，见 acceptance-results.md。

## Phase 7 / 最终质量门（2026-11-18）

`mvn -B -ntp verify` 通过：540 项测试，失败/错误/跳过均为 0；`python3 scripts/check_db_test_reports.py --tools` 及检查器 8 项 Python 回归通过。质量门发现的公开对象锁与空配置拆箱风险均已修复，未放宽规则或新增排除。FR-001–FR-025、FR-003a、FR-011a 和 SC-001–SC-009 的覆盖索引、五类共 100 次 p95 与三类 Demo 见 [acceptance-results.md](./acceptance-results.md)。

显式 `-Pllm` 的 ReActWeatherSmokeTest 路径已执行，2 项测试因缺少进程环境变量 AGENTOS_LLM_API_KEY 跳过，真实模型验收为 **NOT_RUN**；普通全量质量门的 540 项零跳过与此独立记录，不混算。后续有测试凭证时可按第 7 节执行，仍使用隔离数据库和本地天气服务。

实施前后均检查 `.specify/extensions.yml`，文件不存在，无扩展 hooks。requirements checklist 保持原始 16/16，不修改其勾选状态。
