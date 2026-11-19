# 005 分析与独立修复记录

2026-11-19，Asia/Shanghai。分析阶段只读；以下文件由独立实施修复步骤归档。

## Phase 13 收敛复核

T130 修复秘密登记早于首次事件、审计及目录投影的边界，T131 统一含NUL文件的读取、编辑、搜索判定。
原始发现与任务保留在 tasks.md 的 Phase 13；实施后定向回归先红后绿，完整676项测试及数据库/JAR验收通过。
附带修复的续租截止时间分类符合FR-006/SC-014，HTTP夹具仍使用原预算与断言；没有改变验收阈值。
131项任务均完成且编号唯一，62/62要求映射仍有效；未发现剩余CRITICAL/HIGH或宪章冲突。
constitution/checklist保持原哈希，外部模型/评估器/Brave/MCP保持NOT_RUN。
本节为独立实施阶段归档；详细实测与日志索引见 acceptance-results.md 的 Phase 13 最新验证。

## Phase 12 历史记录

| ID | 原级别 | 问题 | 修复与验证 |
|---|---|---|---|
| H1 | MEDIUM | 打包脚本假定首轮 MCP 必定已就绪，异步发现导致目录断言间歇失败 | 脚本先在同一交互 CLI 进程通过无工具探测轮次取得含远程工具的真实快照，再执行正式调用；最终2次探测后12项成功、12项拒绝，全部24条审计收尾；ask_user验证实际回答 |
| R1 | HIGH | 全量回归中查询 Bean 错将自定义主执行 ToolCatalog 转为 ToolRegistry，导致快照测试启动失败 | 查询 Bean 显式绑定已注册目录，新增装配回归先红后绿；ToolSnapshotBeforeTurn 原断言保留，20项定向 verify 与全部静态规则通过 |
| S1 | HIGH | Shell 显式环境变量回显未加入共享秘密集合，原样进入审计 | ShellTools 在启动前登记解析后显式值；执行与目录装配注入共享 Redactor。Privacy 集成测试先红后绿，检查审计、历史、回填和事件；本轮定向 verify 成功 |
| D1 | MEDIUM | plan/quickstart 仍称尚未实施，任务统计文字过期 | 文档更新为实际实现及重放入口，勾选状态由证据维护；外部模型与服务保留 NOT_RUN |
| D3 | MEDIUM | 配置契约含未绑定 MCP 全局属性和不支持的服务级环境继承字段 | 移除不可用示例字段，说明固定上限、共用 Shell 基础继承名单及可收紧的服务/工具策略；与加载器允许字段复核一致 |
| D2 | MEDIUM | 技术方案混淆 MCP 本机连接授权与服务内部隔离 | 第6章明确连接程序/cwd/实际 URL 受本机授权；远端内部行为不承诺隔离，精确工具与服务双重授权 |

回归测试的附加断言允许工具在取消竞态中先报告 confirmed failed；整轮仍必须 TIMED_OUT，
每个样本审计终态、持久化 CONFIRMED、动作次数和真实清理检查保留，p95/每次清理窗口不放宽。
定向命令：`mvn -B -ntp -pl AgentOS-boot -am -Dtest=ToolCapabilitiesPrivacyIntegrationTest,ToolCapabilitiesDeadlineIntegrationTest,ToolAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false verify`。
日志 `/tmp/agentos-005-shell-red.log`（红灯）、`/tmp/agentos-005-cross-fixed.log`（绿灯）；后者 5 项测试，0失败/错误/跳过，耗时2分17秒。

打包 harness 补充实际宿主合成凭证注入与 Shell/STDIO 显式业务变量检查；检查反馈、审计、日志及目录无泄漏。
仅保存检查布尔值与计数，不归档临时凭证或原始运行日志。

62条要求（48 FR + 14 SC）全部有任务映射，129个任务编号唯一，无宪章冲突或未映射任务。
constitution 与 requirements checklist 未修改；requirements 16/16。未注册扩展 hooks。
最终只读复核及全量质量门结果见 acceptance-results.md。

R1日志：`/tmp/agentos-005-final-verify-first.log`（全量首轮，仅快照测试启动错误）、`/tmp/agentos-005-query-binding-red.log`（新增回归红灯）、`/tmp/agentos-005-query-binding-fixed.log`（32.214秒，20项测试通过）。

全量 verify 最终666项、0失败/错误/跳过；两个类在全量XML内缺镜像启动日志，单独复跑Plugin/MCP后，原数据库检查脚本全部通过，未修改检查规则。最终只读复核：62/62要求有任务，未发现新的规格/契约漂移，无未解决CRITICAL/HIGH；分析过程不写文件，记录由独立归档步骤生成。
