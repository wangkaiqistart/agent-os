# Phase 9 验证记录（2026-11-17）

范围：002-react-loop 的 T087–T093。所有结论是本地验证/指定真实模型样本，不表示部署、共享数据库或生产环境已验证。既有 Phase 8 提交保留。

## 质量门

| 检查 | 结果 | 证据 |
|---|---|---|
| `mvn -B -ntp spotless:apply` | PASS | 默认格式规则，未放宽 |
| `mvn -B -ntp -Dagentos.boot.final-name=AgentOS-polish verify` | PASS | 2026-11-17T12:28:05+08:00；463 tests / 0 failures / 0 errors / 0 skipped |
| `python3 scripts/check_db_test_reports.py` | PASS | Docker可用，实际PostgreSQL测试执行；无H2/共享库 |
| 官网 `npm run verify` | PASS | 10对中英文检查及VitePress构建；既有大chunk提示保留 |
| 真实模型专项 | PASS | `-Pllm -Dtest=ReActWeatherSmokeTest`：2 tests / 0 failures / 0 errors / 0 skipped |
| 运行时密钥扫描与差异检查 | PASS | 见下文扫描范围；`git diff --check` |

仅覆盖构建命名为独立JAR，避免覆盖正在运行的标准JAR；仍执行同一verify全部规则。没有关闭测试、增加排除项或跳过数据库测试。普通构建不启用llm；真实请求另行显式运行。

首次全量测试均通过但测试文件编辑后未格式化而触发Spotless，已格式化后重新执行完整门禁。真实Demo首次验收将本地替身会话误算进CLI活跃会话计数，已限定CLI渠道，最终两个用例全部通过。此问题属于测试隔离断言，未修改业务实现。

## 真实模型与真实天气

用户明确授权测试启动进程仅在运行时加载本地`.env`的三个主模型变量；不输出、不写入测试配置或仓库文件。运行前已说明费用和发送天气上下文。只有primary参与，不调用Embedding/DeepSeek。

- 模型：`gpt-5.6-luna`，Profile指定low推理强度/2048输出上限，不发送temperature。
- 数据库：`pgvector/pgvector:pg17`独立Testcontainers，空库Flyway迁移授权来自该测试任务；关闭`.env`导入，不加载用户DB配置，不执行clean/共享库故障注入。
- 本地天气替身：固定北京8℃/风3m/s/降水0，含合成token测试脱敏；实测2次模型/1次HTTP/4条消息，`SUCCEEDED + CONFIRMED`，工具执行计数与审计完全一致。
- 真实数据源：`https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=39.9&lon=116.4`，唯一白名单`api.met.no`；默认可识别User-Agent。临时目录手写weather Agent与Bootstrap，不读取用户原Agent/历史。
- 真实CLI：`java -jar .../AgentOS-polish.jar chat --workspace <临时根> --profile weather --message "查一下北京天气并告诉我穿什么"`；第二次独立进程追加`--new`。两次均退出0，各2次模型/1次工具/4条消息，合计4/2/8；旧会话归档且旧轮次/消息/两类审计逐行完全不变，新会话UUID不同，只有一个CLI active会话。
- 核对：工具状态均success，关联session/turn/iteration与消息里的请求/结果ID对应，模型tool_call_count之和与工具审计数一致，结束后自身lease为空；日志、工具审计及模型审计无主模型key原值/合成秘密。

本次返回的穿衣建议摘要：长袖或保暖内衣加毛衣/抓绒与厚外套，早晚防风保暖并备折叠伞。这是模型根据本次met.no数据生成的回答样本，未作气象预报的独立准确性评估。模型可能多次取数；2次模型只是本次测量，不能当成固定契约。

重跑方式（先安全注入三个模型环境变量，不把properties文件source为shell）：

```bash
mvn -B -ntp -DskipTests -Dagentos.boot.final-name=AgentOS-polish package
AGENTOS_WEATHER_DEMO=true mvn -B -ntp -Pllm -pl AgentOS-boot -am \
  -Dtest=ReActWeatherSmokeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentos.weather-demo.jar=/absolute/path/AgentOS-boot/target/AgentOS-polish.jar test
```

没有`AGENTOS_WEATHER_DEMO=true`时只运行本地天气专项，真实天气方法为NOT_RUN；key缺失时整类跳过也只能算NOT_RUN。上方结果来自实际显式执行，非条件跳过。

## 架构与事实源

T087先用空规则让违规夹具出现预期断言红灯，再实现方法/类/组合Transactional注解与同类委托扫描；检测Provider、ChatModel、ToolCallback、Manager、AgentService、ToolExecutor、HTTP与Future等待。非storage不得依赖TransactionTemplate。唯一ToolExecutor改为完整类名，扫描范围固定core/provider/tool/storage/channel/CLI/boot；MDC单键/队列/adapter读取禁止，已有仅用于同线程日志隔离的context-map保存恢复不作为业务上下文。

静态规则不能完整推导跨Bean/异步调用；T077的动态验证仍为主证据：模型等待、工具HTTP和审计退避均确认不占数据库连接。没有修改已有规则的强度。

技术方案9.2、V1、实体与记录器已核对：tool状态in_progress/interrupted及updated_at、llm failure_kind/http_status/write_id；先记后做、动态lease、取锁后实际时间、接管收尾与新owner保护。审计预算当前默认2秒/上限5秒，老任务250ms保留为历史并显式注明被后续修复替代。CLI、官网六份中英文页面与后续能力边界已同步。

## 42条FR逐项核对

PASS表示对应范围的实现/测试和本次回归通过，不表示对任意生产故障或任意模型行为作成功保证。

| 需求 | 结果 | 行为 | 证据与任务 |
|---|---|---|---|
| FR-001 | PASS | 统一入口及渠道解耦 | AgentServiceTest、DefaultAgentCliIntegrationTest；T020/T028/T107 |
| FR-002 | PASS | 活跃会话唯一、归档后新UUID | JpaStorageContractTest、JpaTurnStoreContractTest；T080/T082 |
| FR-003 | PASS | 每消息一轮及终态字段 | ReActApplicationTest、MigrationTest；T022/T025/T050 |
| FR-004 | PASS | 同会话忙时零写入 | SessionConcurrencyIntegrationTest；T081/T084 |
| FR-005 | PASS | 迭代/工具前续租、丢失后停止 | LeaseLossTest、SessionConcurrencyIntegrationTest；T081/T083 |
| FR-006 | PASS | 有界收尾与失效/孤儿恢复 | TurnReaperTest、TurnReaperStartupTest、AgentServiceFailureTest；T072/T075 |
| FR-007 | PASS | 逐次模型→工具→历史→终态 | ReActLoopTest、ReActApplicationTest；T027/T048/T050 |
| FR-008 | PASS | 唯一注入管理器执行一次 | ArchitectureRulesTest、ToolExecutorTest；T037/T042/T087 |
| FR-009 | PASS | 全部returnDirect不再请求模型 | ReActLoopStreamingTest.returnDirectStopsAfterOneModelCall；T037/T048 |
| FR-010 | PASS | 工具顺序和调用ID回填 | ToolCallLedgerTest、ToolExecutorTest；T037/T042 |
| FR-011 | PASS | ToolContext显式上下文、不入prompt | ToolExecutorTest、ToolAutoConfigurationTest、ArchitectureRulesTest；T037/T049/T087 |
| FR-012 | PASS | 四项日志关联及清理 | AgentServiceTest、GovernedToolCallbackTest、LlmAuditIntegrationTest；T039/T078 |
| FR-013 | PASS | 同步/流式同链路、五类事件和终结边界 | ReActLoopStreamingTest、TurnDeadlineTest；T037/T048/T054 |
| FR-014 | PASS | 两道独立次数上限 | ReActLoopLimitsTest、ToolCallLimitsTest；T052/T053 |
| FR-015 | PASS | 迭代上限强制结束、有安全说明 | ReActLoopLimitsTest、ReActApplicationTest；T052/T056 |
| FR-016 | PASS | 超限不执行并逐条审计 | ToolCallLimitsTest、ToolExecutorTest；T053/T057 |
| FR-017 | PASS | 三层超时和共享整轮时限 | TurnDeadlineTest、ReActApplicationTest；T054/T060 |
| FR-018 | PASS | HTTP连接/跳转/body共享工具预算 | HttpToolsDeadlineTest、HttpToolsTest；T055/T059 |
| FR-019 | PASS | deadline+1秒包含审计/收尾/通知 | TurnDeadlineTest、TurnStorageBudgetTest、AuditLatencyTest；T054/T060/T070 |
| FR-020 | PASS | 首次prompt顺序和预留块 | PromptBuilderTest、PromptHistoryTest；T019/T024/T064 |
| FR-021 | PASS | 后续迭代保留完整历史 | ToolExecutorTest、ReActLoopStreamingTest；T037/T048 |
| FR-022 | PASS | 完整轮次双限、0与90%预算 | PromptHistoryTest、AgentSettingsTest；T062/T064 |
| FR-023 | PASS | 失败历史安全重建、原记录保留 | PromptHistoryTest、JpaMessageStoreTest；T062/T065 |
| FR-024 | PASS | Agent/Bootstrap逐轮热加载、缺失WARN | ContextLoaderTest、AgentLoaderTest；T018/T063/T067 |
| FR-025 | PASS | Profile必填/默认值/空权限 | AgentLoaderTest、AgentSettingsTest、ReActApplicationTest；T007/T018/T023 |
| FR-026 | PASS | 非法Agent/工具/provider在起步前拒绝 | AgentLoaderTest、AgentServiceTest、ToolRegistryTest；T018/T020/T049 |
| FR-027 | PASS | Agent目录不成为工具 | ToolRegistryTest、PromptBuilderTest、ToolCatalog实现核对；T019/T047 |
| FR-028 | PASS | HTTP GET白名单与逐跳校验 | HttpToolsTest、WhitelistSandboxTest；T038/T043/T044 |
| FR-029 | PASS | 先记后做、独立线程、超时/截断/脱敏 | GovernedToolCallbackTest、ReActApplicationTest；T039/T046/T050 |
| FR-030 | PASS | 普通工具错误反馈、致命错误中止 | AgentOsToolExecutionExceptionProcessorTest、AuditFailureTest；T039/T046/T074 |
| FR-031 | PASS | 审计有限重试/稳定键/未知提交/UNCONFIRMED | RetryingAuditWriterTest、AuditTransactionTest、AuditLatencyTest、LlmAuditIntegrationTest；T070/T071/T073 |
| FR-032 | PASS | 只追加消息、结构化JSON、重启恢复 | JsonbMappingTest、JpaMessageStoreTest、DefaultAgentCliIntegrationTest；T014/T065/T068 |
| FR-033 | PASS | Flyway五表与JPA validate，无vector依赖 | MigrationTest、ReActApplicationTest；T014/T015/T016 |
| FR-034 | PASS | 模型各次尝试与完整元数据落库 | LlmAuditIntegrationTest、AuditTransactionTest；T071/T078 |
| FR-035 | PASS | 工具begin/终态/跳过/异常ID/唯一键 | JpaToolInvocationRecorderTest、ToolExecutorTest；T040/T042/T045/T057 |
| FR-036 | PASS | 参数/结果/已知值脱敏 | RedactorTest、ToolAuditRedactionTest、ReActWeatherSmokeTest；T036/T041/T089 |
| FR-037 | PASS | 日志不含prompt/参数/结果/密钥，审计安全截断 | LlmAuditIntegrationTest、ToolAuditRedactionTest、运行时产物扫描；T071/T078/T093 |
| FR-038 | PASS | 模型/工具/退避不持有数据库连接 | LlmAuditIntegrationTest、ReActApplicationTest、AuditTransactionTest；T077；T087静态辅助 |
| FR-039 | PASS | init生成main、幂等、缺模型零写入 | WorkspaceInitializerTest、InitModelResolverTest、InitCommandBehaviorTest；T095/T099/T100 |
| FR-040 | PASS | chat选择/工作区/交互/单消息/--new | ChatCommandTest、DefaultAgentCliIntegrationTest、真实天气CLI；T102/T106/T066/T090 |
| FR-041 | PASS | CLI登录身份及跨进程恢复 | CliIdentityTest、DefaultAgentCliIntegrationTest；T063/T068 |
| FR-042 | PASS | 安全错误、空消息零轮次、忙说明/退出码 | ChatCommandTest、CliChannelTest、AgentServiceTest；T021/T085 |

## 22条SC逐项核对

| 标准 | 结果 | 行为 | 证据与任务 |
|---|---|---|---|
| SC-001 | PASS | 真实primary+met.no CLI天气与穿衣 | ReActWeatherSmokeTest.realWeatherCliAndNewPreserveArchivedHistory；T090；两个独立进程均成功 |
| SC-002 | PASS | 纯文本精确1次模型/2条消息 | ReActApplicationTest.plainTextChatPersistsSessionTurnMessagesAndOneModelCall；T033 |
| SC-003 | PASS | 工具请求一次仅执行一次 | ToolExecutorTest、ReActApplicationTest脚本模型；T037/T050；真实本地天气另实测1次 |
| SC-004 | PASS | 默认10/覆盖3次模型后结束 | ReActLoopLimitsTest；T052/T056 |
| SC-005 | PASS | 超限工具零执行且有跳过审计 | ToolCallLimitsTest；T053/T057 |
| SC-006 | PASS | 预算+1秒、正常超时CONFIRMED/故障UNCONFIRMED | TurnDeadlineTest、TurnStorageBudgetTest、AuditLatencyTest；T054/T060/T077 |
| SC-007 | PASS | 正常/瞬时故障审计逐一对应 | AuditTransactionTest、LlmAuditIntegrationTest、真实天气审计/消息ID核对；T070/T071/T089 |
| SC-008 | PASS | 日志/审计无测试秘密及实际primary key | RedactorTest、ToolAuditRedactionTest、ReActWeatherSmokeTest、产物扫描；T036/T078/T093 |
| SC-009 | PASS | 第二条100%拒绝且零写入 | SessionConcurrencyIntegrationTest；T084 |
| SC-010 | PASS | 终态/租约/恢复与旧owner保护 | TurnReaperTest、JpaTurnStoreContractTest、AgentServiceFailureTest；T072/T075/T080 |
| SC-011 | PASS | 历史工具请求/结果零拆分 | PromptHistoryTest、JpaMessageStoreTest；T062/T065 |
| SC-012 | PASS | 文件改动下一轮生效 | ContextLoaderTest、AgentLoaderTest；T063/T067 |
| SC-013 | PASS | init重跑零覆盖/零连接 | WorkspaceInitializerTest、InitCommandBehaviorTest；T099/T100 |
| SC-014 | PASS | 统一Maven质量门全部通过 | Spotless/Checkstyle/SpotBugs/FindSecBugs/Enforcer/ArchUnit/测试；T087/T092 |
| SC-015 | PASS | 瞬时审计不缺/不重、执行前后失败区分 | RetryingAuditWriterTest、AuditTransactionTest、AuditFailureTest；T070/T073/T074 |
| SC-016 | PASS | 重定向越界/内网零请求 | HttpToolsTest、WhitelistSandboxTest；T038/T044 |
| SC-017 | PASS | --new旧记录不变、旧历史不入新prompt、忙拒绝 | ChatCommandTest、JpaStorageContractTest、SessionConcurrencyIntegrationTest；真实CLI额外逐行比对旧记录；T063/T068/T084/T090 |
| SC-018 | PASS | 默认main纯文本全链路与离线init | DefaultAgentCliIntegrationTest、WorkspaceInitializerTest；T105/T107 |
| SC-019 | PASS | 模型来源/无效值/幂等/损坏矩阵 | InitModelResolverTest、AgentLoaderTest、WorkspaceInitializerTest；T095/T096/T099 |
| SC-020 | PASS | 显式/main/旧目录选择及错误零外呼 | ChatCommandTest、AgentOSCliTest；T102/T103 |
| SC-021 | PASS | 自定义工作区初始化/选择/加载一致 | SpringContextLauncherTest、DefaultAgentCliIntegrationTest；T104/T107 |
| SC-022 | PASS | 初始化无秘密/路径冲突/部分失败不覆盖 | WorkspaceInitializerTest、InitCommandBehaviorTest、InitModelResolverTest；T095/T099/T108 |

## 待办、临时入口与产物检查

本Spec的42条FR、22条SC均有任务和证据，不以任务勾选代替运行。后续长期记忆、MCP、完整Tool策略、REST/调度/SSE不在本Spec，不标记为已交付。

仓库已有`web/dev/AgentLoopDebugController`（显式`agentos.debug.react-loop.enabled=true`才注册）及测试源码中的DevTestApplication/ProviderTestController调试主类；本轮未删除前序调试资产。ReActApplicationTest新增断言确认正式默认上下文不注册此Controller；不存在本轮新增的正式SSE API。Provider里US-5 ConfigLoader TODO为后续范围；日志Recorder的US-2 TODO是旧说明，实际storage自动配置已经替换并由唯一Bean测试验证。只读分析阶段不顺手改写这些既有文件。

源码/文档中的已跟踪及非忽略新文件、独立可执行JAR的全部顶层ZIP条目逐项检查当前主模型key原值，零命中；默认.env与运行配置未打包。该检查加上合成秘密脱敏测试构成本轮证据，不是对历史Git对象或全部第三方依赖的完整安全审计。

可复验日志位于`/tmp/agentos-phase9-architecture-red.log`、`/tmp/agentos-phase9-real-weather-final.log`、`/tmp/agentos-phase9-verify-final.log`、`/tmp/agentos-phase9-website-final.log`；临时日志可被系统清理，长期证据是测试源码、本记录和quickstart第14节。
