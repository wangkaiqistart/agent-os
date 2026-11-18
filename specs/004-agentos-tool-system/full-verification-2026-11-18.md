# 004 完整功能验证与收敛复核

> 最新状态（2026-11-18 19:15:14 +08:00）：Phase 9 / T053 已完成，53/53 项任务完成；修复后完整质量门 550 项全部通过，慢正文 20/20 次实际进入正文并取消关闭，500ms 预算下 p95 为 517ms。详见 [Phase 9 验收报告](./phase9-results.md)。以下保留修复前的历史验证和发现，不代表当前仍有待办；真实模型本轮未启用。

验证日期：2026-11-18（Asia/Shanghai）。范围以本 Feature 的 spec、plan 和 tasks 为准。

## 结论

**功能验证通过，收敛仍有 1 项测试稳定性待办。** 第二次完整质量门为 550 项测试、0 失败、0 错误、0 跳过；使用本次构建的真实可执行 JAR，额外 15 项 CLI 黑盒验收全部通过。真实模型联调为 **NOT_RUN**，不能据此宣布真实外部服务兼容性已通过。

初次静态收敛复核未发现新功能缺口；动态验收随后暴露慢正文抽样的偶发失败，已更新结论并仅在 tasks.md 末尾追加 **Phase 9 / T053**。没有实施该任务，没有修改应用或测试源码。

## 收敛发现

已核查 27 条 FR、9 条 SC、18 个验收场景、11 个边界场景、9 项计划架构门、8 项宪章原则及原有全部 52 项任务。原有 tasks.md 字节保持不变，只追加新阶段；没有修改 spec、plan、constitution 或 checklist。扩展 hooks 文件不存在。

| ID | 类型 | 级别 | 来源 | 实际证据 | 后续 |
|---|---|---|---|---|---|
| F1 | partial | MEDIUM | SC-005、T029、T037 | 首次全量运行中慢正文用例仅收到 19/20 个请求；定向复跑与第二次全量运行通过。100ms 预算可能在正文开始前耗尽，测试未确认每次都进入正文读取 | T053：稳定准备阶段并确认 20 个实际慢正文样本；保留共享 deadline、零重试和预算加 1 秒的 p95 标准 |

发现计数：missing=0、partial=1、contradicts=0、unrequested=0；CRITICAL=0、HIGH=0、MEDIUM=1、LOW=0。当前 53 项任务中原 52 项已完成，T053 待实施。一次复跑通过不消除首次失败事实。

## 实际执行

```bash
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py --tools
python3 -m unittest discover -s scripts/tests
python3 /tmp/agentos_004_blackbox.py
mvn -B -ntp -Pllm -pl AgentOS-boot -am -Dtest=ReActWeatherSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

首次 `verify` 返回 1：`HttpToolsDeadlineTest.sustainedBodyP95RemainsBoundedAcrossTwentyRequestsWithoutRetry` 期望 20 请求、实测 19，失败发生在 tool 模块，后续模块未执行。定向复跑该测试类返回 0。随后在没有修改代码、测试或质量门的情况下重新运行完整 `verify`，于 **2026-11-18T18:02:47+08:00** 完成，耗时 02:30，返回 0。没有删除断言、放宽规则或跳过数据库测试。

| 模块 | 第二次完整运行测试数 |
|---|---:|
| AgentOS-boot | 75 |
| AgentOS-channel-cli | 3 |
| AgentOS-cli | 61 |
| AgentOS-core | 72 |
| AgentOS-provider | 220 |
| AgentOS-storage | 65 |
| AgentOS-tool | 43 |
| AgentOS-web | 11 |
| 合计 | 550 |

Spotless、Checkstyle、SpotBugs/FindSecBugs、Enforcer、ArchUnit 全部通过。PostgreSQL 连通性与 2 个测试类中 7 项关键 Tool 数据库证据检查通过；Phase 8 新增/加强的用例也在本次完整运行中成功执行。数据库报告检查器自身 8 项回归通过。

完整测试计数使用第二次运行后立即保存的 Surefire 快照，排除旧报告；之后独立 llm 路径的 2 项跳过不计入默认 550 项结果。

## 四个用户故事

| 范围 | 本轮验证 | 证据 |
|---|---|---|
| US1 最小工具集合 | PASS | 目录校验、精确子集/顺序、空集合、缺少定义/重复注册；真实 CLI 未知/重复配置零模型、零工具、零会话/轮次/消息/审计；运行期拒绝可继续 |
| US2 治理和审计 | PASS | 执行前可查 in_progress；原行完成；幂等且明确未开始的瞬时失败最多重试一次并消耗预算；begin 失败零执行、complete 未确认不重放；历史/审计/日志脱敏，租约接管和迟到结果隔离 |
| US3 安全 HTTP | PASS | 默认拒绝、精确域名/IP和子域边界；协议/user-info 拒绝；逐跳授权；三次重定向；非 2xx 与超大正文安全错误；慢正文取消、无自动重试 |
| US4 有界循环 | PASS，含 T053 稳定性待办 | 模型/单工具/总工具限次、内部重试跨迭代计账、跳过 ID 与零尝试；同步/流式闭环、returnDirect、正常结束无总结调用；sink 故障与终结至多一次；五类截止时间抽样复跑通过 |

内部扩展工具、returnDirect、过程 sink 故障、审计故障注入、并发接管、总工具预算和流式协议由相应单元/真实 PostgreSQL 集成测试覆盖。下面的 CLI 黑盒只测试已注册的生产 `http_get`，不宣称这些内部契约也能仅靠 CLI 配置构造。

## 新 JAR 的 CLI 黑盒验收

真实 JAR + 独立 `pgvector/pgvector:pg17` PostgreSQL + 本机 HTTP/模型替身。没有读取 `.env`、连接共享库或使用真实模型凭证。每个用例使用独立 Agent；退出码、模型实际可见定义、请求数、工具结果 ID、数据库计数/状态与合成敏感标记缺失均由驱动断言。非法配置的五类数据库计数为零。

| 场景 | CLI 退出码 | 模型请求 | HTTP 请求 | 轮次状态 | 工具 ID:状态/attempts | 结果 |
|---|---:|---:|---:|---|---|---|
| 合法工具与先记后做 | 0 | 2 | 1 | SUCCEEDED | call-1:success/1 | PASS |
| 空工具集合 | 0 | 1 | 0 | SUCCEEDED | 无 | PASS |
| 未知工具配置 | 2 | 0 | 0 | 无轮次 | 无 | PASS |
| 重复工具配置 | 2 | 0 | 0 | 无轮次 | 无 | PASS |
| 运行期未授权工具 | 0 | 2 | 0 | SUCCEEDED | call-1:failed/1 | PASS |
| 运行期不存在工具 | 0 | 2 | 0 | SUCCEEDED | call-1:failed/1 | PASS |
| 非白名单初始地址 | 0 | 2 | 0 | SUCCEEDED | call-1:sandbox_denied/1 | PASS |
| 跳转至非白名单 | 0 | 2 | 1 | SUCCEEDED | call-1:sandbox_denied/1 | PASS |
| 恰好三次合法跳转 | 0 | 2 | 4 | SUCCEEDED | call-1:success/1 | PASS |
| 超过 1 MiB 正文 | 0 | 2 | 1 | SUCCEEDED | call-1:failed/1 | PASS |
| HTTP 503 无正文回显 | 0 | 2 | 1 | SUCCEEDED | call-1:failed/1 | PASS |
| 后一项 JSON 损坏，整批不执行 | 0 | 2 | 0 | SUCCEEDED | good:failed/0, bad:failed/0 | PASS |
| 单工具上限 2，同批请求 3 次 | 0 | 2 | 2 | SUCCEEDED | one:success/1, two:success/1, three:limit_skipped/0 | PASS |
| 模型上限 3，无额外总结 | 3 | 3 | 3 | MAX_ITERATIONS | call-1:success/1, call-2:success/1, call-3:success/1 | PASS |
| 慢正文耗尽整轮预算 | 3 | 1 | 1 | TIMED_OUT | call-1:timeout/1 | PASS |

慢正文 CLI 的 `process_ms` 为整段 Java 进程耗时（含 JVM、Spring 和迁移启动），不能作为整轮 deadline 的 p95；整轮性能标准由下表直接包住运行时调用的测试验证。模型/工具达到上限或超时时 CLI 退出 3 是预期业务结果，不代表该负向用例失败。

`normal` 用例在 HTTP 服务处理请求前查询到 `in_progress`，随后同一调用 ID 为 success 并进入下一次模型请求。损坏 JSON 用例的两条原 ID 均 failed/attempts=0、HTTP=0；限次用例 one/two 各成功一次，three 为 limit_skipped/attempts=0。所有已运行的模型后续历史、CLI 输出、结构化日志和数据库摘要均未检出本次合成 URL/结果凭证；CLI 输出和日志也未检出 prompt/用户消息哨兵。

仓库内可重放命令：`python3 specs/004-agentos-tool-system/fixtures/full_tool_acceptance.py`；其相对路径定位和容器创建保护已做语法校验，15 项实际运行来自保存前的临时驱动。

JAR SHA256：`c8f19779fe204cce3936ce59b71f8b6b75e2fc1fccc8fa7a9e856944de8d133a`。临时数据库容器已确认删除，模型/HTTP 服务已停止；没有清理或改写业务库。

## 截止时间验收（第二次完整运行）

| 场景 | 样本数 | 预算 ms | p95 ms | 允许上限 ms | 结果 |
|---|---:|---:|---:|---:|---|
| 慢模型 | 20 | 50 | 131 | 1050 | PASS |
| 持续流式分片 | 20 | 80 | 88 | 1080 | PASS |
| 慢正文 | 20 | 100 | 105 | 1100 | PASS |
| 慢工具 | 20 | 80 | 86 | 1080 | PASS |
| 审计等待 | 20 | 80 | 85 | 1080 | PASS |

本次 100 个样本通过。SC-005 的抽样可靠性仍由 T053 跟进，不将先前的 19 次实际请求包装为完整 20 次慢正文覆盖。

## 真实模型边界

显式 `-Pllm` 命令已执行，2 项测试由环境门禁跳过，状态 **NOT_RUN**。进程未提供 `AGENTOS_LLM_API_KEY`，真实天气额外 opt-in 也未开启。默认测试、CLI 黑盒和这次跳过均不能证明真实模型供应商或公网天气服务已通过联调。

本 Feature 只交付 `http_get` 与内部统一扩展契约；文件、Shell、HTTP POST、完整九工具、MCP 客户端、工具搜索、复杂策略、并行调用和容器沙箱不在本次规格范围，未按缺失功能计算。

## 证据与下一步

- 安全机读结果：[full-verification-2026-11-18.json](./fixtures/full-verification-2026-11-18.json)。包含本次完整 Surefire 快照、首次失败/复跑记录、15 项 CLI 数据库读回和逐项断言。
- CLI 验收驱动：[full_tool_acceptance.py](./fixtures/full_tool_acceptance.py)。只创建专用本地 PostgreSQL 容器、临时配置与工作区；finally 删除自己创建的容器，不访问已有数据库。驱动从本次实际执行的临时脚本保存，将仓库绝对路径改为脚本相对定位，并加上“仅创建成功后才清理”的容器保护；应用与验收断言未改。
- 首次失败日志：`/tmp/agentos-004-full-verify-20261118.log`；定向复跑：`/tmp/agentos-004-deadline-recheck-20261118.log`；完整复跑：`/tmp/agentos-004-full-verify-recheck-20261118.log`；llm 门禁：`/tmp/agentos-004-llm-gate-20261118.log`。

下一步用 `$speckit-implement Phase 9` 完成 T053，再复核收敛。真实模型需在提供进程环境凭证后走显式验收路径。本次没有修改应用代码，没有提交、推送、合并或发布。
