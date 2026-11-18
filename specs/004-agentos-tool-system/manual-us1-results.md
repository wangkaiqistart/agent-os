# Phase 3 / US1 实际 CLI 验证报告

2026-11-18，当前分支 `specs/004-agentos-tool-system`，代码提交 `4d8ad2192a8631a485f2290aa270e9b47d518a84`。

结论：本次六项 CLI 验收全部 PASS。未知工具配置现在明确指出配置文件、tools 字段和具体工具名，同时保持零模型请求、零会话/轮次/消息/审计记录。内部注册和装配契约由自动化测试覆盖，本报告不将 CLI 验收扩展为整个 Tool Feature 或真实外部服务验收。

## 执行方式与环境

- 重新执行 `mvn -B -ntp verify`，使用本次构建的真实 `AgentOS.jar`，逐项运行 CLI 的 `init`、`chat --message`、`chat --new`。
- JDK 21.0.9，Docker 29.9.2；数据库由 Testcontainers 创建，镜像 `pgvector/pgvector:pg17`，实际 PostgreSQL 17.12。V1、V2 迁移均成功。
- 模型和 HTTP 目标使用 `fixtures/us1_manual_server.py`，记录模型实际收到的工具定义、模型请求数、HTTP 请求数和工具结果类别；本轮没有访问真实模型或外部网站。
- 运行目录、Agent 配置、日志和数据库均独立于现有工作区及业务库；未读取项目 `.env`。临时服务已停止，数据库容器已确认移除。
- JAR SHA-256：`caf4d5fdbffaef452bca932745086bf12da5fef6ddc4d6d4ebecbe98df195f78`。

## CLI 结果

表内计数为每个用例执行前清零后的实际请求数。

| 用例 | 退出码 | 模型请求 | HTTP 请求 | 数据库及模型请求查证 | 结果 |
|---|---:|---:|---:|---|---|
| Agent 配置 `[http_get]` | 0 | 2 | 1 | 模型只见 http_get；描述非空、Schema 为 object 且仅含 url；工具审计 success | PASS |
| Agent 配置 `[]` | 0 | 1 | 0 | 模型请求没有 tools 字段；无工具审计 | PASS |
| Agent 配置 `[does_not_exist]` | 2 | 0 | 0 | 会话、轮次、消息、模型审计、工具审计均为零；提示包含 Agent 文件和 does_not_exist | PASS |
| Agent 配置 `[http_get, http_get]` | 2 | 0 | 0 | CLI 指出无法解析 Agent 文件；五类数据库记录均为零 | PASS |
| 空工具 Agent，模型强行返回 http_get | 0 | 2 | 0 | 模型请求仍无 tools；拒绝作为工具结果回传；http_get/failed；轮次 SUCCEEDED | PASS |
| 授权 http_get，模型强行返回 outside | 0 | 2 | 0 | 模型仅见 http_get；outside 被拒绝并回传；outside/failed；轮次 SUCCEEDED | PASS |

两项运行时拒绝均不是 `sandbox_denied`，而是工具授权拒绝：`本 Agent 没有名为 … 的工具`。这确认了域名授权与 Agent 工具名单是不同检查。

## 数据库实际读回

测试 Agent 前缀为 `us1-20261118163019`。六项各执行一次后的数据库计数与手动文档预期一致：

| Agent 后缀 | sessions | turns | messages | model_calls | tool_records |
|---|---:|---:|---:|---:|---:|
| http | 2 | 2 | 8 | 4 | 2 |
| none | 2 | 2 | 6 | 3 | 1 |
| unknown | 0 | 0 | 0 | 0 | 0 |
| duplicate | 0 | 0 | 0 | 0 | 0 |

| 用例 | turn_id | 轮次状态 | 工具审计 |
|---|---|---|---|
| 合法授权 | 8c5dbec8-ac2a-4a57-a839-5c0d63bc98da | SUCCEEDED | http_get / success |
| 空工具 | 236edf00-27cf-4eec-bace-9fe55277d7f3 | SUCCEEDED | 无 |
| 未授权调用 | 834682ad-1d10-4e34-bbcc-134f66cf3ad9 | SUCCEEDED | http_get / failed |
| 不存在工具调用 | 0239faf2-081f-4200-a086-8fdd43047dd8 | SUCCEEDED | outside / failed |

## 质量门和未覆盖范围

`mvn -B -ntp verify` 返回 0，521 项测试，失败 0、错误 0、跳过 0；`python3 scripts/check_db_test_reports.py` 返回 0，确认 PostgreSQL 连通性测试实际执行并通过。

本次 CLI 验证没有直接构造全局注册重名、缺少定义、多工具声明顺序、returnDirect、同名 Bean 及单例装配场景；这些仍由现有契约测试覆盖。真实模型兼容性、公网访问质量和后续 Phase 的完整治理不属于本轮验证结果。

Spec-Kit 一致性复核确认本次补齐 T011/T012 的未知配置错误定位，未改变工具执行入口、模块依赖或后续阶段完成状态。另保留一项后续 HTTP 规格表述问题：SC-003 的“超限重定向零网络请求”需在 Phase 5 前区分此前已发生的合法请求与超限后的后续请求；本次不修改该验收标准。

已先新增断言并实际复现两处红灯：ToolRegistry 提示不含 unknown，真实 CLI 只返回通用提示。修复后，core 的专用配置异常携带相对文件、字段与工具名；CLI 只展示该类型，不展示 cause 或其他参数错误原文。对应目录、CLI 单元测试和真实 CLI/PostgreSQL 集成测试均已通过。

本次实际提示为 `agents/us1-20261118163019-unknown/AGENT.md 的 tools 包含未注册工具：does_not_exist`，退出码 2。修复前的部分通过证据保留在 [before-fix.json](fixtures/us1-cli-evidence-2026-11-18-before-fix.json)，本次证据使用新 Agent 前缀和全新数据库。

首次执行曾因验收配置 `timeout: 5s` 继承默认 `retry-min-remaining: 15s` 而启动失败。已在手动文档补充 `retry-min-remaining: 1s`，随后用全新临时数据库重新执行全部六项；上表均为修正后的结果，首次启动失败未计为授权验证通过。

完整安全统计、断言结果和数据库读回保存在 [us1-cli-evidence-2026-11-18.json](fixtures/us1-cli-evidence-2026-11-18.json)。本次临时原始日志与只读 SQL 保存在 `/var/folders/rl/xwfdz8nx743_tf4h3fbhbs840000gn/T/agentos-us1-fix-z17dtmot/`，首次验证保存在 `/tmp/agentos-us1-run.Fwwtl5/`；原始日志不纳入提交。业务修复和回归测试已单独提交为 `4d8ad21`，验证资料另作一批提交；未推送。
