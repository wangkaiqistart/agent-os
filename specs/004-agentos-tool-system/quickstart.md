# Quickstart: AgentOS 工具注册与安全执行

## Prerequisites

- JDK 21 and Maven available.
- Docker available for PostgreSQL Testcontainers; default image is `pgvector/pgvector:pg17`.
- Repository root: `/Volumes/Data/my_project/agent-os`.
- Do not load production credentials or real business data into tests.
- Default tests use local HTTP substitutes and do not call real external services.

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
