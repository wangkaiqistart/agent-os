# 004 工具注册与安全执行：Phase 4–7 验收

日期：2026-11-18。范围：`specs/004-agentos-tool-system`，当前检出分支 `specs/004-agentos-tool-system`。

## 交付边界

本 Feature 交付 `http_get`、Agent 最小授权目录、唯一官方 Manager、先记后做审计、受控幂等重试、结果安全和共享预算。文件、Shell、HTTP POST、Memory/Notify 工具、交互/搜索、MCP 客户端、任意 Java Bean 自动注册和 `tool list` 不属于本次交付。

重试只接受工具明确抛出的 `TransientToolException(executionStarted=false)`，且工具必须幂等，最多一次。HTTP 请求超时、响应丢失、普通网络异常和 5xx 不足以证明动作未开始，因此当前 HTTP 实现不自动重发。只有回调内部重试消耗补充尝试计数；官方 Manager 继续按本轮模型调用历史实施原有限次。

审计 complete 未确认时，仅在原 hard stop 内尽力更新原 `in_progress` 为 `unconfirmed`，不覆盖已确认/回收终态。数据库持续不可用时无法保证这次标记落库，必须报告 UNCONFIRMED，由恢复流程处理遗留意图；不得重新执行外部动作。

## 红灯与修复

| 实际复现的问题 | 修复与回归 |
|---|---|
| 截断到字符上限后再追加标记，最终越界；先切断密钥再脱敏可能残留片段 | 先脱敏，标记计入上限；审计保留原始字符数。极小上限只保留标记前缀 |
| 非对象或尾随 JSON 仍可进入 delegate | 只接受单个 JSON 对象，非法输入 attempts=0 |
| 幂等且明确未开始动作的瞬时失败没有重试 | 最多两次底层尝试，共享调用 ID、审计行和一个工具 deadline |
| complete 失败后原行仍为 in_progress | 同身份原行尽力标为 unconfirmed，后续 complete 不覆盖它 |
| 预算已取消仍可直接发出 HTTP 请求 | 每跳在请求前检查取消和截止时间 |
| 正文超限只返回模糊网络错误 | 明确固定超限提示，无正文、原始 cause 或自动重试 |
| 内部重试未计入工具上限 | 共享轮次尝试计数复用官方配置，跨迭代累计 |
| 已执行一次但重试被拒绝时被错误记为整个调用 limit_skipped | 保留 failed/attempts=1；只有初次尝试被拒绝才是 limit_skipped/attempts=0 |
| 超长参数和错误摘要未受限制 | 先脱敏再限制为输入摘要 10000、错误摘要 1000 字符 |

所有上述缺口都先运行新增失败测试，再修改实现。全量质量门首次发现公开对象锁的 FindSecBugs 告警，已改为私有 final 锁，没有新增排除项或放宽门禁。

## 三类本地 Demo

| Demo | 真实装配与观测结果 | 主要证据 |
|---|---|---|
| 合法 HTTP GET | 先确认 in_progress，再发送请求；原行 success；相同调用 ID 的脱敏结果进入下一次模型请求 | ReActApplicationTest、CliAcceptanceIntegrationTest、US1 CLI 证据 |
| 白名单拒绝 | 非白名单初始 URL 零请求，sandbox_denied；安全错误交模型继续；非法重定向的下一跳零请求 | ReActApplicationTest.sandboxDenialIsAuditedAndExplainedToSecondModelWithoutHttp、HttpToolsTest |
| 超时/超大正文/审计未确认 | 慢正文终止本轮且不发下一次模型请求；超过 1 MiB 不回传正文；complete 失败时动作只执行一次，原行 unconfirmed，用户收到“可能已经执行” | ReActDeadlineIntegrationTest 的后三项测试 |

联合限次 Demo：内部工具重试两次 + HTTP 一次消耗总预算 3；下一次 HTTP 零执行，原审计 limit_skipped/attempts=0；模型共调用 4 次后正常回答。同批三个同名请求与后续迭代累计只执行 2 次，对应 two success + two limit_skipped，跳过 ID 为 three、four。

这里使用真实 Spring 装配、官方 ToolCallingManager、真实 PostgreSQL Testcontainers；模型与 HTTP 服务为回环地址替身。它们证明运行链路，不替代真实外部模型兼容性验证。

## FR 覆盖

测试名称均可在对应模块 `src/test/java` 下查找。

| 要求 | 执行证据 |
|---|---|
| FR-001 | ToolRegistryTest：名称、描述、Schema、重名拒绝 |
| FR-002 | ToolRegistryTest、ToolAuthorizationIntegrationTest：精确子集、顺序、空工具 |
| FR-003 | CliAcceptanceIntegrationTest：未知工具的来源/名称、安全提示、零外呼及零新增记录 |
| FR-003a | ToolAuthorizationIntegrationTest、UnknownToolResolverTest：运行期越权/不存在工具零 delegate，安全继续 |
| FR-004 | ReActDeadlineIntegrationTest.acceptanceCatalog：HTTP 与内部 retry_tool 共用契约和装配链 |
| FR-005 | ArchitectureRulesTest、ToolExecutorTest、ToolAutoConfigurationTest：唯一注入式 Manager |
| FR-006 | GovernedToolCallbackTest、SessionConcurrencyIntegrationTest：租约/预算与副作用前校验 |
| FR-007 | AuditFailureTest：begin 未确认零执行；ReActApplicationTest：HTTP 到达前可查 in_progress |
| FR-008 | JpaToolInvocationRecorderTest、ReActDeadlineIntegrationTest：原行终态、unconfirmed、attempts |
| FR-009 | ToolExecutorTest、ReActApplicationTest：完整历史及调用 ID 回填 |
| FR-010 | AgentOsToolExecutionExceptionProcessorTest、GovernedToolCallbackTest：普通错误与致命信号分类 |
| FR-011 | GovernedToolCallbackTest.onlyDefinitelyNotStartedIdempotentTransientFailureRetriesAtMostOnce；ReActDeadlineIntegrationTest.retryAndNextIterationShareActualAttemptBudget |
| FR-011a | AuditFailureTest、GovernedToolCallbackTest：未知完成、非幂等、致命信号、审计失败不重试 |
| FR-012 | GovernedToolCallbackTest：先脱敏、最终字符上限、原长、摘要上限；ToolAuditRedactionTest、ReActApplicationTest：日志/数据库/历史敏感信息缺失 |
| FR-013 | ReActLoopLimitsTest、ToolCallLimitsTest、ReActDeadlineIntegrationTest：模型、单工具、总工具与重试计数 |
| FR-014 | TurnDeadlineTest、GovernanceDeadlineAcceptanceTest、HttpToolsDeadlineTest：五类共享截止时间 |
| FR-015 | HttpToolsTest.cancelledBudgetNeverSendsRequestAndUnsafeRedirectNeverSendsNextHop、GovernedToolCallbackTest、SessionConcurrencyIntegrationTest |
| FR-016 | HttpToolsTest.schemaExposesOnlyUrlAndRejectedInitialUrlsSendZeroRequests |
| FR-017 | WhitelistSandboxTest：默认拒绝、大小写、精确域名/IP、只授权子域 |
| FR-018 | WhitelistSandboxTest、HttpToolsTest：user-info、协议、三次跳转、非法下一跳 |
| FR-019 | HttpToolsTest.bodyOverOneMiBReturnsExplicitSafeSizeErrorWithNoRetryOrBody、ReActDeadlineIntegrationTest.oversizedBodyReturnsSafeToolErrorAndContinuesWithOneHttpRequest |
| FR-020 | HttpToolsDeadlineTest、ReActDeadlineIntegrationTest.slowBodyStopsTurnWithoutNextModelOrHttpRetry |
| FR-021 | HttpToolsTest：非 2xx、无效 Location、超大正文、错误无敏感 cause |
| FR-022 | ReActLoopStreamingTest、TurnDeadlineTest：流式工具闭环、持续分片、sink 故障、终结至多一次 |
| FR-023 | ReActLoopStreamingTest.returnDirectStopsAfterOneModelCall；GovernedToolCallback 委托原元数据 |
| FR-024 | ToolRegistryTest、GovernedToolCallbackTest、HttpToolsTest、AuditTransactionTest、SessionConcurrencyIntegrationTest |
| FR-025 | 默认构建排除 llm；ReActWeatherSmokeTest 有 llm 标签、环境门禁与隔离工作区/数据库 |

## SC 覆盖

| 要求 | 执行证据 |
|---|---|
| SC-001 | US1 CLI 和 ToolAuthorizationIntegrationTest：加载期配置失败，零模型/工具/轮次记录 |
| SC-002 | JpaToolInvocationRecorderTest、AuditFailureTest、Boot 闭环：同一调用 ID、原行终态及 attempts，begin 失败零执行 |
| SC-003 | WhitelistSandboxTest、HttpToolsTest：非法初始请求为 0；重定向拒绝下一跳为 0，前序授权请求按实计数 |
| SC-004 | ReActDeadlineIntegrationTest：同批/跨迭代/内部重试不越界，跳过 ID 一一对应 |
| SC-005 | 五类各 20 次，p95 与预算见下方最终验证数据 |
| SC-006 | ToolAuditRedactionTest、ReActApplicationTest、GovernedToolCallbackTest：用户/历史/审计/日志安全抽样 |
| SC-007 | ToolExecutorTest、ReActApplicationTest、ReActLoopLimitsTest：ID 回填、正常结束无额外总结调用 |
| SC-008 | ReActDeadlineIntegrationTest.acceptanceCatalog 加入内部 retry_tool，没有修改循环控制逻辑 |
| SC-009 | US1 六项 CLI 验收、ToolAuthorizationIntegrationTest：运行期拒绝零底层执行且允许继续 |

SC-003 的文字已对齐逐跳授权契约：零请求约束适用于非法初始 URL 或非法/超限的下一跳；不能把已经发生的授权前序跳转写成零请求。未改变三次跳转上限。

## 最终验证

- **PASS**：`mvn -B -ntp verify`，2026-11-18 17:16:50（Asia/Shanghai）完成；540 项测试，0 失败、0 错误、0 跳过；格式、Checkstyle、SpotBugs/FindSecBugs、依赖禁令、ArchUnit 和 PostgreSQL 测试全部通过。新 JAR 已构建。
- **PASS**：`python3 scripts/check_db_test_reports.py --tools`，数据库连通性和 7 项关键 Tool 数据库证据实际执行。
- **PASS**：`python3 -m unittest discover -s scripts/tests`，8 项检查器回归；`git diff --check` 通过。
- **NOT_RUN**：显式 `-Pllm` 路径已执行，但 ReActWeatherSmokeTest 的 2 项用例因进程未提供 AGENTOS_LLM_API_KEY 被环境门禁跳过，没有真实模型请求。没有读取 `.env`、连接共享数据库或对外发送业务消息。

| 截止时间场景 | 次数 | 预算 ms | p95 ms | 允许上限 ms | 结果 |
|---|---:|---:|---:|---:|---|
| 慢模型 | 20 | 50 | 130 | 1050 | PASS |
| 持续流式分片 | 20 | 80 | 87 | 1080 | PASS |
| 慢正文 | 20 | 100 | 105 | 1100 | PASS |
| 慢工具 | 20 | 80 | 86 | 1080 | PASS |
| 审计等待 | 20 | 80 | 85 | 1080 | PASS |

可机读的安全统计见 [phase4-7-evidence-2026-11-18.json](./fixtures/phase4-7-evidence-2026-11-18.json)。其中的调用数和状态来自已通过测试的数据库读回与请求计数断言，不包含参数、正文、凭证或原始日志。
