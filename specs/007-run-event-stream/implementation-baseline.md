# 007 实施基线

- 日期：2026-11-21
- 提交：`3c72384`（分支 `specs/007-run-event-stream`，007 只有文档改动，代码与 `main` 的 `6f24d84` 相同）
- 环境：JDK 21.0.9、Maven 3.9.14、Docker 29.9.2（Testcontainers `pgvector/pgvector:pg17`）
- 命令：`mvn -B -ntp verify`

## 结果

`BUILD SUCCESS`，所有模块的格式、Checkstyle、SpotBugs、依赖禁令、ArchUnit 与测试均通过。

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| AgentOS-core | 98 | 0 | 0 | 0 |
| AgentOS-provider | 220 | 0 | 0 | 0 |
| AgentOS-tool | 147 | 0 | 0 | 0 |
| AgentOS-storage | 65 | 0 | 0 | 0 |
| AgentOS-web | 14 | 0 | 0 | 0 |
| AgentOS-channel-cli | 6 | 0 | 0 | 0 |
| AgentOS-cli | 67 | 0 | 0 | 0 |
| AgentOS-boot | 108 | 0 | 0 | 0 |

基线无失败项，后续任何失败都视为本功能引入。
