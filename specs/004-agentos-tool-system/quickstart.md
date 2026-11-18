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
