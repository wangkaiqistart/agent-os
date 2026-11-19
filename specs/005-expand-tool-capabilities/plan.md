# Implementation Plan：Tool 能力补齐与插件接入

**Branch**: `specs/005-expand-tool-capabilities` | **Date**: 2026-11-18 | **Spec**: [spec.md](./spec.md)

**Input**: `specs/005-expand-tool-capabilities/spec.md`

**状态**：005 的 US1–US9 已实现，跨故事回归与最终质量门见 acceptance-results.md；真实外部依赖与模型评估分别记录 NOT_RUN。
setup-plan 返回规范化 BRANCH `005-expand-tool-capabilities`，实际 Git 分支如上。

## Summary

在 004 的单一执行、授权、审计、脱敏、限次和截止时间基础上，新增 `read_file`、`write_file`、
`list_dir`、`shell`、`http_post`、`edit_file`、`grep`、`glob`、`ask_user`、`web_search`，保留 `http_get`。
同期支持官方 `@Tool`/显式 callback 插件、三种 MCP transport、来源生命周期、CLI 工具查询和令牌认证的
只读 HTTP 查询。所有来源最终均是受治理的 Spring AI `ToolCallback`。

技术重点是不可变目录/授权快照、原调用标识审计、显式 I/O scope、默认拒绝的文件/程序/网络授权、
协作锁下的原子文件发布、受控进程环境与清理，以及 MCP 隐藏/恢复而不重放调用。
搜索首个真实提供者采用 Brave；交互通过入口端口传递，禁止工具自行读取控制台。

不交付 Memory/Notify、定时调度、完整会话 REST、Tool Policy 引擎、工具检索/渐进暴露、
并行工具执行、容器沙箱、MCP Server、多租户。`@ToolPolicy` 仅为本期治理参数元数据，
不代表扩展阶段策略引擎。天气/科技/GitHub Demo 只向当前入口返回结果。

## Technical Context

**Language/Version**: JDK 21；中文说明/注释，SLF4J 占位符日志。

**Primary Dependencies**: Spring Boot 4.1.2、Spring AI 2.0.2、官方 ToolCallbacks/ToolCallingManager、
MCP Java SDK 2.1.0（当前 BOM 解析结果）、MCP Jackson3 JSON Schema validator、JDK HttpClient/NIO/ProcessHandle、
Picocli 4.7.7、SnakeYAML、Spring MVC。依赖仅 Maven Central，无厂商 SDK。

**Storage**: 复用 PostgreSQL 16+/pgvector 的 tool_invocations、agent_turns、session_messages 及现有审计事务；
V1/V2 已有 source、状态、attempts、error/result 字段，本期无新业务表，不改历史迁移。
配置与 MCP 定义为文件；文件产物落已授权目录。目录、来源、交互是进程内状态。

**Testing**: JUnit 5、Testcontainers `pgvector/pgvector:pg17`、临时文件系统、本地 HTTP/MCP/进程替身、
脚本化 ChatModel、CLI 输入替身；三个 Demo 的 Spring AI 评估器测试仅在 llm profile 和测试模型环境变量
显式启用时运行，真实外部服务另行启用。最终质量门 `mvn -B -ntp verify`。

**Target Platform**: 可信单机 Linux/macOS，JDK 21；文件系统须支持同目录原子移动和硬链接以通过全部写入验收。
目录结构由部署者稳定维护，所有写入方遵守同一 sidecar 锁协议；不提供多租户隔离。

**Project Type**: 九模块 Maven 单体，CLI + 最小只读 MVC 入口；不拆 Tool 模块。

**Performance Goals**: 默认模型 10 次、单工具 10 次、工具总计 30 次、整轮 30s；
五类慢场景各 20 次，p95 ≤ 配置预算 + 1s；受控进程清理在同一窗口确认；
永不响应 MCP 来源使本地就绪增加 ≤ 1s；无人值守提问 20 次均 < 1s。

**Constraints**: 普通结果 10000 字符；单文件/POST 请求/POST 响应各 1 MiB；列表/搜索 200 项；
扫描 1000 项；命令保留输出合计 64 KiB；问题/回答各 4000 字符；搜索查询 1000 字符、10 项、响应 1 MiB；
MCP 初始化 10s/来源、调用默认 30s、退避 1s 倍增至 300s；共享清理窗口 1s。
已暴露的工具限值可收紧，不以 0 表示无限；MCP 初始化/发现/重连采用固定安全上限，调用策略可按服务或工具收紧。
I/O 同时受工具与整轮剩余预算约束。

**Scale/Scope**: 9 个故事、48 条功能要求、14 条成功标准；11 个内置工具加插件。
每来源最多发现 1000 工具、单响应 1 MiB，schema 最大 64 KiB/深度 64。不增加吞吐量承诺。

## Constitution Check

门禁在 Phase 0 前及 Phase 1 后复核。以下是设计合规，不代表功能已验收。

| 原则/门禁 | 设计落实 | 研究前 | 设计后 |
|---|---|---|---|
| I：版本、模块、Central | 固定版本，core 放端口，实现放 tool，无新增模块 | PASS | PASS |
| II：核心能力边界 | 无记忆/通知/调度/SSO/多租户，仅规格要求的只读令牌入口 | PASS | PASS |
| III：官方组件、循环自驱 | 保持 ChatModel 主链；官方 callback/manager/MCP/schema；共享双限次和 scope | PASS | PASS |
| IV：唯一执行与先审计 | 一个注入 Manager；所有来源及拒绝 callback 受治理；先 begin 后副作用 | PASS | PASS |
| V：三档插件、失败隔离 | 官方 Java/MCP；后台连接；noOp + 远程空上下文 | PASS | PASS |
| VI：PostgreSQL/持久审计 | 复用 V1/V2 和调用身份；不以日志替代、不用 H2/SQLite | PASS | PASS |
| VII：每故事 Demo | quickstart 按 US1–9；三个汇总 Demo 加官方评估器测试；显式 llm，未跑为 NOT_RUN | PASS | PASS |
| VIII：Agent/Skill 非 Tool | 只扩展 AgentLoader 的 mcp_servers；目录不成为回调 | PASS | PASS |
| Sandbox/密钥/事务 | 工具内部 enforce；显式端口；环境引用；I/O 不包 DB 事务 | PASS | PASS |
| 流程/质量门 | 人工 review → tasks → 测试先行 → 实施复核；最终 verify 不降门禁 | PASS | PASS |

`ManagedStdioTransport` 仅补官方缺失的宿主进程所有权/环境/有界清理接口，复用官方 transport 端口、
JSON mapper、MCP 会话及 callback；不另造 MCP 客户端或执行入口。依据见 [research.md](./research.md) R6–R8。
无 constitution 豁免，不修改 constitution。AGENTS 的历史 002 Phase 1 提示不作为本次明确指定的 005 实施范围。

## Project Structure

### Documentation (this feature)

```text
specs/005-expand-tool-capabilities/
├── spec.md                      # 保留原改动，追加已确认并发边界
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── tasks.md
└── contracts/
    ├── tool-catalog.md
    ├── builtin-tools.md
    ├── mcp-lifecycle.md
    ├── configuration.md
    └── tool-query.openapi.yaml
```

`tasks.md` 已生成并按实施证据维护依赖、验收要求和完成状态。

### Source Code (repository root；005 实施位置)

```text
AgentOS-core/src/main/java/com/agentos/core/
├── agent/               # AgentProfile / AgentLoader：mcp_servers
├── tool/                # 目录、描述、快照、查询、scope 契约
├── interaction/         # InteractionPort、request/result、入口能力
└── turn/                # 原服务/循环/执行器传递快照/能力，真实来源审计
AgentOS-tool/src/main/java/com/agentos/tool/
├── registry/            # 插件发现、校验、候选目录原子发布、Agent 授权
├── annotation/          # ToolPolicy 注解
├── govern/              # 原治理层增加 scope、source、有界清理
├── sandbox/             # 独立读写、命令/cwd、网络授权
├── file/                # 路径、协作锁、原子提交、文件工具
├── process/             # shell/MCP 共用进程所有权、环境、排空和清理
├── mcp/                 # 客户端 generation、受控 STDIO、生命周期
├── http/                # 保留 GET，新增 POST，复用受限 I/O
├── interaction/         # ask_user 到入口端口
└── search/              # SearchProvider、Brave 适配、web_search
AgentOS-cli/src/main/java/com/agentos/cli/
                         # tool list / tool serve、目录 launcher、唯一输入 broker
AgentOS-channel-cli/src/main/java/com/agentos/channel/cli/
                         # 同一 InteractionPort，不另读 stdin
AgentOS-web/src/main/java/com/agentos/web/tool/
                         # 只读 Controller、bearer Filter、安全 DTO
AgentOS-boot/src/main/java/com/agentos/boot/
                         # 独立目录启动/装配模式，不复用 debug API
AgentOS-boot/src/main/resources/
                         # 配置占位符与 MCP 原始日志关闭
AgentOS-*/src/test/       # 单元/契约测试，boot/storage PostgreSQL 闭环
pom.xml                  # 增加显式 external-tools profile/tag，普通 verify 继续禁止外部调用
docs/TechnicalSolution.md # 实施时同步005默认值、已实现能力和部署边界
specs/005-expand-tool-capabilities/fixtures/
                         # 已交付 Demo harness 与脱敏安全证据
```

**Structure Decision**：当前 checkout，不建 worktree。查询和入口能力只依赖 core；实现模块不反向注入到 core。
只读模式避免强制数据库/模型启动，执行模式仍要求真实审计依赖。

## Phase 0：研究结论

[research.md](./research.md) 已解决 API、存储、I/O、搜索提供者和交互所有权问题。
按技能要求分派三个只读研究代理：MCP/Java、文件/进程、HTTP/搜索/交互/查询。
关键现场发现：官方 STDIO 环境继承与句柄缺口、MCP 无单次 timeout 重载、本机无 SecureDirectoryStream、
`ATOMIC_MOVE` 不保证默认不覆盖。协作写入范围已由用户确认并追加规格，无未解决澄清。

## Phase 1：设计与集成

### 1. 目录及授权

本地启动发现，远程来源独立维护；候选集合完整校验后以不可变 version 发布。
AgentLoader 解析 mcp_servers，未知服务/本地名在建轮次前失败，失联仅延迟该来源存在性校验。
DefaultAgentService 在 startTurn 前一次取得授权快照并传入循环；Provider 只收 modelCallbacks，
ToolExecutor 收 executionCallbacks，均来自同一个快照。失联来源/未配置搜索保留显式拒绝回调。
禁止全局 resolver 查容器补出真实工具。generation 可用门在执行前复核，恢复只供后续轮次。

### 2. 治理与审计

回调携带真实 source，沿用原 tool_call_id 与 begin/complete 同行更新。
非法整批参数、未授权 fallback、限次跳过的审计也从快照取来源；未知名为 unknown。
治理层创建 scope 传给本地工具/插件；MCP 只收到业务参数和默认空元数据。
取消登记资源清理，状态未知保守提示；超时、断线、重连、审计失败均不能触发重放。
参数、结果、问题/回答、历史、日志、错误和目录投影都受脱敏与长度约束。
结构化结果先缩小条目/内容并保持合法 JSON 和状态；统一治理保留最后字符上限。

### 3. 文件与命令

文件读写独立授权，编辑需要两者；严格 UTF-8 和有界读，搜索逐项授权并报告不完整原因。
edit_file 的旧文本只要求长度大于 0，允许纯空格/换行/缩进且不 trim；唯一匹配规则保持不变。
写入统一协作锁，同目录完整临时文件准备后复核发布；创建用硬链接，覆盖/编辑原子替换；
不支持则拒绝，不降级破坏原文件。副作用后的审计或清理失败不能声称未执行。
Shell 真实 executable+argv、环境清空重建、输出并发排空、可识别进程树有界清理。
白名单只限制直接目标，不隔离获准程序内部行为。

### 4. MCP

三 transport 各有受控替身；初始化后台执行，不占本地就绪路径。官方 SYNC 客户端负责协议，
宿主进程 adapter 仅补生命周期。初始 URL、SSE message endpoint、实际请求和重连均授权；
实际端点还须与该来源配置 URL 同源（规范化 scheme、host、有效端口），在发请求及附加凭证前检查。
跨源即使命中通用域名白名单也拒绝，本期不配置跨源例外；来源凭证只附加到同源请求。
禁 HTTP 跳转及透明恢复重发。取消/短 deadline 中止相关 generation，其他来源不受影响；
同来源在途调用可安全失败但不重放。重连清单仅供新轮次，新增工具不自动授权。

### 5. POST、搜索和交互

POST 禁重试和跳转，以真实计数验证最多一次；send 后失败说明可能生效。
Brave GET 使用部署凭证头和受限响应，区分空/截断/失败/未配置，不抓取命中链接。
ask_user 能力由入口显式传递，CLI 一个输入 owner；迟到输入丢弃，无人值守立即失败。
回答使用固定大小缓冲增量读取，最多保留回答上限加 1 个字符，超限立即 TOO_LONG；同一 reader
丢弃余行，禁止完整 readLine 后才限长。工具等待可取消；底层读取未结束时旧 generation 继续占用 broker。
不以 trigger 字符串推测控制台能力。

### 6. 查询与认证

tool list 与 GET /api/v1/tools 共享 ToolCatalogQuery，输出 version、tools、sources；
Agent 视图展示授权/可见性，保留不可用项，查询不执行工具或触发新发现。
tool serve 启动最小目录模式，默认回环；远程显式开放，TLS 由部署者/反向代理提供。
Filter 在读目录/AgentLoader 前校验 bearer，缺失/错误均 401；无令牌不装配入口，服务命令安全失败。
不开目录 CORS，不记录认证头/完整 URL，不把 debug controller 作为生产入口。
目录模式只构造描述/来源与查询投影；执行模式才装配审计依赖和可执行治理回调，
不能为了复用完整 ToolAutoConfiguration 给查询模式注入无效/空审计仓库。

### 7. 交付顺序与验收门

| 阶段 | 故事/要求 | 完成证据 |
|---|---|---|
| 共享契约与插件 | US1；FR-001–008 | 冲突/schema/授权/审计失败零执行，004 回归 |
| 文件与命令 | US2–3；FR-009–019 | 原子提交、软链接、并发、参数、输出、20 次超时/取消 |
| MCP 全链 | US4；FR-020–028 | 三 transport、环境、失联/恢复/关闭、无上下文泄漏 |
| 提交与精确文件 | US5–6；FR-029–035 | POST 计数/可能生效、唯一匹配、扫描不完整 |
| 交互与搜索 | US7–8；FR-036–041 | 迟到零串用、真实 provider 本地协议测试、状态区分 |
| 只读查询 | US9；FR-042–044 | 同快照一致、认证前零读取、零业务调用 |
| 完整验收 | FR-045–048、SC-001–014 | 每故事 Demo、三个汇总的官方评估器测试、慢场景各 20 次、DB 闭环、最终 verify |

实施按上述顺序及 tasks 依赖推进，测试覆盖行为/故障并记录实际动作次数。
先定向验证，再执行最终质量门和数据库报告检查；005 结果以本轮验收记录为准。

三个汇总 Demo 的 `DemoEvaluationIntegrationTest` 使用实际工具资料及最终输出：天气/GitHub 用
FactCheckingEvaluator，科技用 RelevancyEvaluator；各评估 3 次、至少 2 次 isPass()，不用 getScore() 代替。
测试标记 llm，且要求 AGENTOS_LLM_API_KEY；可用的 deepseek Provider 优先评估，否则用 primary 并记录
同模型自评限制，评估 ChatClient 不传 tools。数据端仍用隔离 HTTP/MCP/文件与 PostgreSQL，不需要外部
搜索、Memory 或通知。默认 verify 不调用评估模型；缺凭证或未运行记 NOT_RUN，不能宣称评估已通过。

实施收尾依次执行只读 analyze、独立修复、只读复核、质量门及结果归档；分析技能自身不修改文件。

## Complexity Tracking

无 constitution 豁免。快照、scope、输入 broker、STDIO adapter 分别解决来源身份、真实 I/O 清理、
迟到输入归属和官方进程所有权缺口，不增加通用工作流或策略引擎。

## Review 重点

人工 review 确认协作锁协议、generation 失效对同来源调用的影响、STDIO adapter 最小边界、
只读模式装配隔离、POST 最多一次测试、外部联调显式开关。实际 API/平台不达契约时报告门禁失败，
不降低标准或把 NOT_RUN 算 PASS。
