# 005 实施基线

记录日期：2026-11-18（Asia/Shanghai）。这是环境观察，不是005功能验收。

- 当前分支：`specs/005-expand-tool-capabilities`，HEAD `cf290ae33fd13b4703cc0349d892c22294c16993`。
- JDK：Zulu 21.0.9；Maven：3.9.14；macOS：27.2 aarch64。
- Docker server：29.9.2，可用；数据库测试仍用独立 `pgvector/pgvector:pg17` Testcontainers。
- 开始时修改：`spec.md`；未跟踪：`contracts/`、`data-model.md`、`plan.md`、`quickstart.md`、`research.md`、`tasks.md`（均在本功能目录）。完整保留，不重置、不建worktree。
- 已读 constitution、CLAUDE.md、005规格/计划/研究/模型/契约/quickstart/tasks；requirements checklist 16/16，未修改标记。
- 当前明确005范围覆盖入口中的历史002目标；不实现Memory/通知/调度/完整会话REST。
- `.specify/extensions.yml` 不存在，无实施前hook；实施后再次检查。
- `.gitignore`/`.dockerignore` 已存在，覆盖编译、编辑器、密钥和容器上下文；不改无关网站配置。
- 未读取.env，未启动真实模型或外部工具请求。

004回归入口：core的 `ToolExecutorTest`、`ToolCallLimitsTest`、`ReActLoopLimitsTest`；tool的 `GovernedToolCallbackTest`、`AuditFailureTest`、`GovernanceDeadlineAcceptanceTest`、`UnknownToolResolverTest`、`HttpToolsDeadlineTest`、`ToolAutoConfigurationTest`；boot的 `ToolAuthorizationIntegrationTest`、`ToolAuditRedactionTest`、`ReActDeadlineIntegrationTest`、`ArchitectureRulesTest`。测试结果在对应阶段单独记录。

验证要求：每阶段定向验证；最终 `mvn -B -ntp verify`、数据库报告、时限样本和只读漂移复核。缺真实外部依赖的测试记录NOT_RUN。

## Setup 验证（2026-11-18 22:44:41 +08:00）

- `mvn -B -ntp -pl AgentOS-boot -am test-compile -DskipTests`：PASS（仅编译）。
- `mvn -B -ntp spotless:apply`：完成。
- `mvn -B -ntp verify`：PASS，10个reactor项目成功，耗时3分9秒。本次Maven日志汇总：tests/failures/errors/skipped=(550, 0, 0, 0)（排除旧XML报告）。数据库使用独立PostgreSQL17.12，无真实模型/外部工具请求。
- evidence schema：Draft202012Validator.check_schema通过；PASS+未执行拒绝，NOT_RUN+未执行接受。
- `git diff --check`：PASS。
- 原Spring AI BOM未导出SDK子构件版本，显式导入官方`mcp-bom:2.1.0`，与原传递解析版本一致。
- 本阶段不代表005工具实现或验收完成。
