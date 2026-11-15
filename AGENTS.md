# AgentOS Codex 开发指南

本文件是 Codex 在本仓库工作的入口。项目原先由 Claude Code 使用，现由 Codex 接续；已有的
`CLAUDE.md`、`.specify/` 和 `specs/` 继续有效，不重复复制其中的完整约束。

## 事实源与阅读顺序

- 总体原则：`.specify/memory/constitution.md`。
- 仓库约定、Spring AI 2.0.2 设计约束、模块职责和质量门：`CLAUDE.md`。
- 实现依据：`docs/TechnicalSolution.md`；需求口径：`docs/DemandAnalysis.md`。
- 当前功能：`specs/002-react-loop/{spec,plan,research,data-model,quickstart,tasks}.md` 及其 `contracts/`。
- 文档冲突时，以 constitution 和 `CLAUDE.md` 中声明的优先级为准；不得自行修改 constitution。

开始编码前，先检查 `git status --short --branch`，保留用户和前序 agent 的未提交改动，不要重置、清理或覆盖它们。

## Codex 与 Spec-Kit 接续

仓库已经包含 Spec-Kit 运行资产：`.specify/scripts/bash/`、模板、工作流和 `specs/`。Codex 集成已安装在
`.agents/skills/speckit-*/`（Claude 兼容技能仍保留在 `.claude/skills/`）；执行 `/speckit-*` 时按对应
`.agents/skills/speckit-*/SKILL.md` 的流程在当前会话中执行，
并使用 `.specify/scripts/bash/check-prerequisites.sh` 做路径和前置检查。`/speckit-implement` 必须：

1. 读取 `tasks.md`、`plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/` 和 `quickstart.md`；
2. 检查 checklist，不修改 checklist 勾选状态；
3. 按阶段和任务依赖执行，遵循测试先行，完成任务后才将任务标为 `[X]`；
4. 每个阶段完成后运行适用的验证，最终以 `mvn -B verify` 为质量门；
5. 实施前后检查扩展 hooks（若存在），不自动提交、推送、合并或发布。

当前接续目标是 `specs/002-react-loop/tasks.md` 的 **Phase 1: Setup（T001–T006）**；完成前不进入
Foundational 或任何用户故事实现。

## 工程与安全约束

- JDK 21、Spring Boot 4.x、Spring AI 2.0.x；Maven 依赖只来自 Maven Central。
- PostgreSQL 16+ / pgvector 和 Testcontainers 是数据库验证路径；禁止用 H2、SQLite 或共享库替代。
- 不读取或打印 `.env` 中的密钥；提交文件只能使用环境变量占位符。
- 保持 core 的依赖倒置、注入式 `ToolCallingManager`、无 `ThreadLocal` 业务上下文、无 `ToolCallingAdvisor`。
- 中文回复和代码注释；日志使用 SLF4J 占位符，不记录 prompt、工具参数或密钥。
- 只做当前 Spec 的范围；遇到设计与 constitution 冲突时先报告，不静默降级验收标准。

## 常用验证

```bash
mvn -B -ntp verify
mvn spotless:apply
python3 scripts/check_db_test_reports.py
```

数据库测试默认使用 `pgvector/pgvector:pg17`。本地 Docker 不可用时可以明确跳过并报告未验证；`CI=true`
时不得跳过。真实模型测试需要显式的 `llm` profile 和环境变量，不能在普通验证中发送真实请求。
