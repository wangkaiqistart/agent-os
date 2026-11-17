# 远程数据库审计超时修复与验证（2026-11-17）

## 问题与边界

用户提供的混合日志中，旧版在审计预查时被线程中断，新版正常路径去掉预查后仍在远程 PostgreSQL 的 COMMIT 响应处读超时。2026-11-17 10:24 的提交与查证均未确认；10:25 的另一次提交超时后出现成功审计日志，表明查证可以恢复。不能把 SQLSTATE 08006 直接解释为数据库宕机，也不能从查不到行推断原事务必然回滚。

之前427项测试及五轮真实模型验收记录保留，它们只覆盖当时样本。此次修复不承诺网络、服务器和磁盘永不故障，目标是减少不必要的超时断连、保持数据原子性和去重、故障时明确报告未知状态。没有修改共享库表结构、关闭同步提交、删除用户会话或对共享库注入故障。

## 修复

1. `agentos.audit.attempt-timeout` 默认从250ms改为2秒，可配置1ms～5秒；含线程调度、连接获取、SQL和提交，继续取本轮剩余预算较小值。重试最多三次，200/500ms退避不变。configuration契约同步采用实际绑定的max-retries/backoff-first/backoff-second。
2. 在途调用的最终审计可跨过业务deadline，所有最终审计/查证/收尾仍共享原hardStop；deadline后一秒不扩大，不能重新退避重放。
3. 审计调用方等待与工作线程共享单次绝对时限；等待超时或调用方取消不再interrupt JDBC/TLS工作线程。数据库事务依靠SQL/锁/socket超时结束，在入口、超时配置往返完成后及提交前检查时限。不能用线程取消证明事务已回滚。
4. 提交结果未知时，只按原write_id或工具唯一键查询并比较完整载荷；查证成功才CONFIRMED，查不到或查证失败仍UNCONFIRMED，不重写未知审计、不重发模型或工具。
5. 事务结束时恢复连接networkTimeout；恢复失败则abort/关闭，避免将错误设置交回池。SET LOCAL只在当前事务生效，回滚不会泄漏部分业务更新。
6. Boot允许通过agentos.boot.final-name构建独立JAR，默认名称保持AgentOS。用户已关闭旧进程后，再生成标准JAR，避免运行期间覆盖fat JAR引发延迟类加载错误。

## 测试先行与独立数据库验证

- `/tmp/agentos-db-safety-red.log`：旧默认250ms和超时interrupt行为使新增core回归失败。
- `/tmp/agentos-db-latency-red.log`：旧代码在150ms逐次往返时抛出模型审计未确认；不是Docker跳过。
- `/tmp/agentos-db-safety-targeted.log`：修复后core配置/等待边界和PostgreSQL审计回归通过。

所有破坏性故障、锁、连接耗尽与延迟注入都在独立`pgvector/pgvector:pg17` Testcontainers执行，不连接用户数据库。逐次往返和提交响应等待由JDBC包装器模拟；SQL、提交、回滚、锁和回读使用真实PostgreSQL。这不是对用户网络RTT的测量。

| 场景 | 验证结果 |
|---|---|
| 连续远程往返 | 150/250/180ms逐次SQL及提交等待，三次审计成功，三条独立记录，插入次数恰好三次 |
| 提交已完成、响应迟到 | 真实提交后客户端响应延迟2.3秒，另一连接按原键确认；第一次只插入一行；后台事务完成后同池下一次记录仍成功 |
| 配置SQL耗尽预算 | 100ms操作时限、配置往返200ms，业务回调零执行，池仍能执行下一条查询 |
| 超过配置预算 | 800ms逐次等待时有界UNCONFIRMED，插入不重放；最终池无活跃借用，记录至多一行 |
| 超时不interrupt | 工作线程在独立闩锁上等待，调用方超时返回，工作线程未收到中断且未知写入不重试 |
| 成功/回滚后连接恢复 | 核对池中全部连接autocommit=true、networkTimeout恢复，statement_timeout与lock_timeout为0；回滚前更新不落库，无idle in transaction |
| 原故障矩阵 | 提交丢响应、完整载荷冲突、真实行锁/SQL超时、小池耗尽、退避无事务、工具先记后做、启动回收、迟到写保护全部回归通过 |

## 全仓质量门

`mvn -B -ntp -Dagentos.boot.final-name=AgentOS-dbfix verify`于 **2026-11-17 10:43:47 +08:00** 完成，耗时 **2:13**：

**461 tests / 0 failures / 0 errors / 0 skipped**。

core68、provider220、tool26、storage63、web11、channel-cli3、CLI32、boot38。Spotless、Checkstyle、SpotBugs/FindSecBugs、Enforcer与ArchUnit通过。`python3 scripts/check_db_test_reports.py`返回0，确认数据库测试实际执行。日志`/tmp/agentos-db-safety-verify.log`。

工作区包含此前尚未提交的并发/租约改动，上述全仓结果包含它们。本次没有以修复名义重置或清理这些改动，也没有自动提交。

全量通过后，`mvn -B -ntp -DskipTests package`只负责重新生成标准名称JAR，于10:45:55完成；未把跳过测试的打包当作质量门证据。标准JAR的`--version`启动检查通过。

## 真实配置验收

使用显式llm Spring profile、独立身份`db-safety-20261117-1046`和现有main Agent，由应用正常加载配置；工具未读取或打印.env密钥。只做普通文本请求，故障注入不进入用户数据库。首次进程完成三轮（43.428秒）：记住暗号、正确复述、计算43；退出后同身份重启再一轮（19.567秒），新输入不含暗号仍恢复“梧桐682”。应用确认日志累计4次成功模型调用、1个session、4个不同write_id，全部CONFIRMED；两进程退出码均为0，ERROR=0、Read timed out/Closed by interrupt=0、持久化未确认=0，均记录连接池Shutdown completed。

汇总`/tmp/agentos-db-safety-live-first.json`、`/tmp/agentos-db-safety-live-restart.json`；控制台及结构化日志`/tmp/agentos-db-safety-live-first.txt`、`/tmp/agentos-db-safety-live-restart.txt`、`/tmp/agentos-db-safety-live.jsonl`。这证明本次样本在实际配置下通过，不代表已测量真实服务器负载或保证任意网络抖动下成功。

用户可以退出旧chat后在仓库根目录执行`java -jar AgentOS-boot/target/AgentOS.jar chat`。若后续仍出现UNCONFIRMED，保留turnId与安全阶段日志；数据库持续不可达时不会凭日志补造“审计成功”，也不会自动重发外部调用。

## 独立提交快照验证

仅导出暂存区中的本次修复，排除工作区其他并发/租约改动后，在独立目录执行`mvn -B -ntp verify`，于 **2026-11-17 11:06:15 +08:00** 完成，耗时 **2:16**：**434 tests / 0 failures / 0 errors / 0 skipped**。全模块与质量门通过；`python3 scripts/check_db_test_reports.py`返回0，确认数据库连通性测试实际执行并通过。日志为`/tmp/agentos-dbfix-staged-verify.log`。上述461项属于包含其他改动的工作区验证，本节434项才是此次独立提交的验证结果。
