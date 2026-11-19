# Data Model：工具、来源、授权与交互

本文件定义拟新增/扩展的运行时模型；现有数据库字段仍以 `docs/TechnicalSolution.md` 第 9 章和
已提交 Flyway 迁移为准。本期不新增业务表，不修改 V1/V2。下列 Java 名称为设计契约。

## 1. ToolDescriptor / ToolRegistration

| 字段 | 语义/校验 |
|---|---|
| name | 唯一公开名，`[A-Za-z0-9_-]{1,64}`；本地禁止 `mcp_` 前缀 |
| description | 非空，查询/模型共享，最大 4000 字符，先脱敏 |
| inputSchema | object schema，缺省/2020-12，≤64 KiB、深度≤64，只允许本地引用 |
| source | `builtin`、`java:<safe-bean-id>`、`mcp:<server-id>`；未知请求为 `unknown` |
| originalName | MCP 原始工具名，仅内部映射，不能凭名称回退其他来源 |
| policy | 现有 ToolPolicy：idempotent、timeout、maxResultChars |
| metadata | 官方 ToolMetadata，保留 returnDirect |
| callback | 内部原始 callback，发布执行集合时只给治理后的回调，不序列化到查询 |
| registrationIdentity | Bean/方法/回调对象身份，用于同对象重复发现去重 |
| availability | AVAILABLE / UNAVAILABLE / UNCONFIGURED；非法注册不作为可用项发布 |
| generation | MCP 客户端代次；本地为固定注册版本 |

无注解/显式策略的 Java/MCP 默认非幂等、30s、10000 字符。名称冲突拒绝候选注册，保留旧目录；
初始本地目录冲突使初始化失败，后台单个 MCP 候选冲突只令该来源不可用。
策略注解放 tool.annotation；core 的 ToolPolicy record 不变。显式回调可配 ToolRegistration 元数据。

## 2. ToolSourceState / McpClientGeneration

| 字段 | 语义 |
|---|---|
| sourceId / transport | 配置稳定 server-id；STDIO / SSE / STREAMABLE_HTTP |
| state | CONFIGURED、CONNECTING、AVAILABLE、UNAVAILABLE、CLOSING、CLOSED |
| generationId | 成功连接实例的单调版本，不复用旧实例身份 |
| knownDescriptors | 上次验证通过的定义；失联保留；未发现时允许为空 |
| safeReason | 固定分类：CONNECT_FAILED、TIMEOUT、INVALID_DEFINITION、DENIED、CLOSED 等 |
| nextReconnectAt / failures | 1s 指数退避，上限 300s，成功清零 |
| initDeadline | 整个初始化及分页发现的 10s 预算 |
| client / ownedProcess / streams | 仅内部；不得出现在日志、模型或查询 |
| availabilityGate | 一旦失效不可复活；恢复必须新建 generation |

状态转换：`CONFIGURED → CONNECTING → AVAILABLE`；连接/发现/调用失联转 UNAVAILABLE；
`UNAVAILABLE → CONNECTING → AVAILABLE(new generation)`；关闭转 CLOSING → CLOSED。
应用关闭后不再调度重连。单来源状态失败不改变其他来源。
某轮取消若不能确认隔离取消，则关闭该来源 generation，其他同来源调用也可能失败；均不得重放。

## 3. AgentToolAuthorization / ToolCatalogSnapshot

AgentProfile 增加有序、去空白、拒绝重复的 `mcpServers`，对应 frontmatter `mcp_servers`。
既有 tools 仍为精确公开名列表；空列表合法。旧构造器兼容为 mcpServers 空集合。

| 快照字段 | 语义 |
|---|---|
| version / capturedAt | 一次原子目录读取的版本和时间 |
| descriptors / sources | 所有有效注册与来源安全状态的不可变视图 |
| agentName / declaredTools / declaredServers | 本轮已加载 Profile 的授权副本 |
| modelCallbacks | 按 tools 声明顺序的已授权且可用治理回调 |
| executionCallbacks | 同一授权集合的治理回调，包含不可用/未配置的拒绝回调 |
| bindingByName | 原公开名到 descriptor/source/generation 的固定绑定 |

建轮次前一次 resolve；后续模型迭代不重新绑定。可用门可由 AVAILABLE 单向失效，
但新 generation 不能替代旧绑定。下一轮重新取得快照，恢复可见性。
远程须同时声明 server 与公开名。仅已知 server 前缀可延迟缺失工具校验，且只限该来源不可用时；
来源恢复后确实不存在的名字在建下一轮前报错。无关本地 typo 不延迟。

查询投影仅含 version、agent、tools、sources。每项含 name、description、source、status、
authorized/visible（全局查询为 null），不输出 callback、schema、配置地址、命令、env、凭证或内部上下文。
未配置搜索为 UNCONFIGURED；MCP 首次发现前通过 sources 列表表达来源，不伪造工具定义。

## 4. FileAccessPolicy / FileTargetVersion

- FileAccessPolicy：工作根、读允许根、写允许根、管理员维护的锁根；默认读写根均空。
  canonical roots 按组件匹配，绝不字符串前缀。工作根不是自动授权。
- FileTargetVersion：输入解析后的真实目标、真实父目录/fileKey、目标是否存在、fileKey、size、mtime；
  编辑另有原内容摘要；这些只内部使用。不能为仅写授权读取旧正文。
- FileWriteLease：规范目标的进程内锁 + 稳定 sidecar FileLock；所有协作写方必须使用同一协议。
  sidecar 名为真实绝对目标路径 UTF-8 SHA-256 的十六进制加 `.lock`，位于同一部署配置锁根。
  锁根不可由模型修改，锁文件不随每次释放删除（避免 inode 竞争）；锁顺序固定按路径排序。
- PreparedFileWrite：同父目录不可预测临时文件、待提交字节数、预期版本、提交状态。
  UTF-8 正文和编辑后的结果各≤1 MiB，父目录必须存在。

生命周期：`RESOLVED → LOCKED → PREPARED → REVALIDATED → COMMITTED → CLEANED`。
提交前异常删除临时文件且保持原目标；创建发布为硬链接，覆盖/编辑为原子 move。
COMMITTED 后的审计/清理异常只能报告已提交或不确定，不能说未执行，更不能自动补写一次。
目录变化/授权变化/版本变化/原子能力缺失均拒绝。所有保证以前述可信单机协作协议为前提。

## 5. ToolExecutionScope / ProcessExecution

scope 字段：turn/call 身份、有效 deadlineNanos、共享 TurnExecutionBudget 引用、checkpoint、
资源取消登记、执行状态 NOT_STARTED / POSSIBLY_STARTED / COMPLETED、cleanupConfirmed 与安全原因。
该对象不作为模型参数、不持久化、不传 MCP；用独立内部 ToolContext 键传递。
POST 在发送边界由 scope 向共享 budget 登记独立 Submission；仅成功响应、治理、清理和完成审计均确认后
确认本次登记。未确认提交保留到本轮终结，后续另一提交成功不能清除它。该状态与 auditsConfirmed 独立，
只追加固定“提交可能已生效”警告，不改变原终止状态或时间预算。租约失效时仍 RUNNING 的本轮使用
同一固定警告收口；已存在的数据库终态保持优先，不覆盖接管者答案或释放其租约。

进程模型包含真实 executable/cwd、argv、部署者构建的最小 env、根句柄和可识别后代、
stdout/stderr 的合计保留字节、truncated、exitCode、cleanupConfirmed。
配置/argv/env 不进入目录投影。结果输出按 UTF-8 替换解码处理进程字节，始终受治理；
文件正文则使用严格解码，两者不能混用。
启动、持续 I/O、等待和提交前 checkpoint；取消/租约失效后禁止后续业务动作。
同一 scope 的并发 I/O 检查点串行续租，锁等待也受原工具/轮次较小预算约束；获取锁和续租返回后复核预算，
排队者到期不再发起数据库动作，避免本轮输出/等待线程的事务争抢同一会话行锁。
清理只有一份共享窗口，未确认进程退出明确标记，不承诺控制不可识别的脱离后代。
治理工作线程先登记所有权再启动。自身关闭 scope 时不取消或等待自身，但委托退出前整体清理仍未确认；
外部关闭照常取消并等待实际退出，不以 Future 已取消作为停止证据，也不重开清理窗口。
工作线程清理已在途时，治理方在同一剩余窗口内等待其退出后再判定清理与记录完成审计，不能提前发布终结结果。

## 6. InteractionRequest / InteractionResult

InteractionRequest：唯一 requestId、turnId、toolCallId、≤4000 字符的安全问题、有效 deadline。
InteractionResult：ANSWERED、UNSUPPORTED、EOF、BUSY、TOO_LONG、TIMED_OUT、CANCELLED，及受限回答。
入口能力默认 UNSUPPORTED，经 AgentService 新重载显式传递；旧重载保持默认不支持。

状态：`CREATED → WAITING → ANSWERED`，或进入其他终态。
每次阻塞输入开始前绑定 requestId/generation；终态不可再接受回答。
取消后仍未完成的底层输入继续归旧 generation，迟到行丢弃，broker 未释放前新请求 BUSY。
回答增量读取，固定大小缓冲、最多保留 max-answer-chars+1 个字符；超限立即 TOO_LONG，
同一 reader 丢弃余行至换行/EOF，不保存超额内容。超时/取消停止工具等待，底层读/丢弃未结束时
仍占用旧 generation，不另起 reader，不把余行交给聊天或下一次提问。
问题与回答是同一次工具调用的内容，不建立新用户轮次；敏感信息沿用现有治理。

## 7. SearchProviderConfig / SearchResult

provider=brave、endpoint、环境引用凭证、query/result/response 限值、timeout。
缺失必要配置为 UNCONFIGURED，不阻塞本地其他能力。
SearchResult：items[{title,url,snippet}]、truncated、complete；完整空 items 是成功无结果。
DENIED/UNCONFIGURED/FAILED/超大响应不是成功空结果。最多 10 项，不抓取页面。
返回链接仅允许无 user-info 的 HTTP(S) 公共引用，带敏感值须脱敏或舍弃；不自动加入访问白名单。

## 8. 沿用审计及无迁移决策

继续 `(session_id, turn_id, iteration, tool_call_id)` 身份，同一 begin/complete 更新；
tool_source 使用真实来源；attempts 记录真实尝试；状态保留 V2 的 in_progress/success/failed/timeout/
sandbox_denied/limit_skipped/interrupted/unconfirmed。
不可用、错误输入和未授权也审计；外部动作零执行不等于没有审计行。
动作可能生效、清理未确认等使用受限安全 error_message/result_summary 表达，不引入并行审计表。
如后续确需新字段，先更新技术方案并新增 Flyway 迁移；禁止改已执行迁移或自动建表。
