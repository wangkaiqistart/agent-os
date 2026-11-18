# Phase 9：慢正文截止时间验收稳定性

验证日期：2026-11-18（Asia/Shanghai）。T053 已完成，tasks.md 共 53/53 项完成。本阶段只修改两个测试文件及验收文档，没有改变应用代码、生产默认预算或质量门。

## 问题与修复

修复前完整验收曾仅收到 19/20 个请求。100ms 测试预算可能在初始化、连接或调度阶段耗尽，因此收到超时并不能证明实际覆盖了慢正文。

先为首次沙箱准备确定性注入 750ms 延迟，原测试实际失败：预期 `TurnDeadlineExceededException`，实际为 `IllegalStateException: HTTP 请求超时`。这说明准备阶段会干扰原有采样。该定向红灯与后续绿灯输出已在会话中核对，早期临时日志未保留；以下完整质量门日志单独保存在项目 target 中。

修复后的同一个 HttpTools 实例先以独立的 5 秒准备预算请求 `/warmup`，必须成功返回 `ready` 且计数为 1。随后使用 20 个独立地址采样，每个请求从调用前开始共享 500ms 绝对预算，采样中不重置 deadline。750ms 首次准备延迟由预热承担。

每个样本必须同时满足以下条件，否则整项测试失败：

- 服务端已发送响应头，并持续发送超过一个正文分片；每个地址实际请求恰好 1 次。
- 客户端因整轮截止时间抛出 `TurnDeadlineExceededException`；服务端在 1 秒内观测到连接中断并关闭 exchange。
- 取消确认在外层 server.close 之前完成；自然传输完成或服务端线程被中断均不能冒充客户端取消。
- 全局请求计数逐次精确增加；预热不计入 20 个慢正文样本。

服务端每 5ms 发送 1024 字节，最多持续约 4 秒、总计 819200 字节，低于 1 MiB 正文边界。p95 使用排序后的第 19 个样本；耗时包含取消关闭收尾，仍严格要求不超过所配预算加 1000ms。500ms 是本地测试预算，生产默认整轮预算仍为 30 秒。

## 验证结果

定向 `HttpToolsDeadlineTest` 的 3 项测试通过；慢正文 20 次全部到达、进入正文并取消关闭，p95 为 517ms。

最终完整质量门：`mvn -B -ntp verify`，返回 0，**2026-11-18T19:15:14+08:00** 完成，耗时 02:41。仅统计本次日志创建时间之后更新的 Surefire XML，排除旧的真实模型 smoke 报告：**550 项测试，0 失败、0 错误、0 跳过**。

| 模块 | 测试数 |
|---|---:|
| AgentOS-core | 72 |
| AgentOS-provider | 220 |
| AgentOS-tool | 43 |
| AgentOS-storage | 65 |
| AgentOS-web | 11 |
| AgentOS-channel-cli | 3 |
| AgentOS-cli | 61 |
| AgentOS-boot | 75 |
| 合计 | 550 |

| 截止时间场景 | 次数 | 预算 ms | p95 ms | 允许上限 ms | 结果 |
|---|---:|---:|---:|---:|---|
| 慢模型 | 20 | 50 | 130 | 1050 | PASS |
| 持续流式分片 | 20 | 80 | 87 | 1080 | PASS |
| 慢正文 | 20 | 500 | 517 | 1500 | PASS |
| 慢工具 | 20 | 80 | 86 | 1080 | PASS |
| 审计等待 | 20 | 80 | 85 | 1080 | PASS |

本次慢正文附加计数：requests=20、bodies_started=20、cancelled_and_closed=20、warmup_requests=1。这些汇总只有在所有逐样本断言通过后才输出。

Spotless、Checkstyle、SpotBugs/FindSecBugs、Enforcer、ArchUnit 通过。`python3 scripts/check_db_test_reports.py --tools` 通过，确认 PostgreSQL 连通性及 2 个测试类的 7 项关键 Tool 数据库用例实际执行。`git diff --check` 通过。

## 证据与复验

- 安全统计：[phase9-evidence-2026-11-18.json](./fixtures/phase9-evidence-2026-11-18.json)。
- 本地完整构建日志：`target/phase9-verify.log`（忽略的构建产物，未纳入版本管理）。
- 单独复验：`mvn -B -ntp -pl AgentOS-tool -am -Dtest=HttpToolsDeadlineTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 最终门禁：`mvn -B -ntp verify`，随后运行 `python3 scripts/check_db_test_reports.py --tools`。

本轮使用 PostgreSQL Testcontainers 和本地替身，未启用真实模型 profile，真实模型联调为 **NOT_RUN**；前轮 15 项 CLI 黑盒证据保留在[历史完整功能报告](./full-verification-2026-11-18.md)，不冒充本轮重新执行。未提交或推送改动，checklist 保持原始 16/16 状态。
