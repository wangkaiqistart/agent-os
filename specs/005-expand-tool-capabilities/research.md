# Research：Tool 能力补齐与插件接入

日期：2026-11-18。依据 `spec.md`（包含本次确认的协作写入边界）、constitution、
`CLAUDE.md`、`docs/TechnicalSolution.md` 第 6、8、9、10、12 章及当前代码取证。
这是设计研究，不是功能已实现或验收已通过的报告。

## R1：继承 004，扩展目录而不增加执行入口

- **Decision**：保留 `ToolExecutor → 注入的 ToolCallingManager → GovernedToolCallback`。
  新增 `ToolDescriptor`、不可变 `ToolCatalogSnapshot` 与 Agent 授权快照；在创建轮次前一次性解析，
  同一个快照传入循环。快照分别持有模型可见回调和执行回调，后者可包含仅拒绝执行的不可用工具。
- **Rationale**：当前 `ToolRegistry` 是固定 Map，`DefaultAgentService` 先 validate、
  `ReActLoop.run` 后 callbacks，两次读取动态目录可能得到不同版本；当前治理与批量拒绝审计还硬编码
  `builtin`。005 必须统一带入真实来源，也覆盖非法参数、限次跳过与不可用来源的拒绝记录。
- **Alternatives considered**：循环内每次重新查询目录会改变当前轮次授权；全局 Bean resolver
  会绕过名单；二者均拒绝。core 只增加端口和值对象，具体发现、MCP、文件实现仍在 tool。

## R2：Java 插件发现、元数据与输入校验

- **Decision**：启动时发现 Spring Bean 上的官方 `@Tool` 方法，通过 `ToolCallbacks.from(...)`
  生成回调；同时接收显式 `ToolCallback` Bean。用 Bean 身份与方法身份去重，不按名称吞掉冲突。
  代理通过 Spring AOP 目标类型检查，保留代理调用；不实例化任意类，不扫描 Agent/Skill 文件。
  增加 `com.agentos.tool.annotation.ToolPolicy` 注解与显式回调注册元数据；现有
  `com.agentos.core.tool.ToolPolicy` record 保留，不改成注解。缺省非幂等、30s、10000 字符。
  所有回调保存 `ToolMetadata`，按需保留 `returnDirect`。
- **Rationale**：官方转换支持标准参数与 `ToolContext`；显式回调也必须有安全默认策略。
  建立候选目录后校验非空名称/描述、合法 object schema、唯一名称，再调用官方
  `ToolCallingChatOptions.validateToolCallbacks(...)`，最后原子发布。
- **Alternatives considered**：把容器所有 Bean 都交给运行期 resolver 不满足零越权执行；
  将同名项当作“重复发现”会掩盖不同插件冲突；不采用。
- **输入约束**：复用 MCP SDK 已传递依赖中的 Jackson 3
  `io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator`，显式选择具体实现，
  不依赖可能默认放行的接口方法。缺省或声明 JSON Schema 2020-12；注册时校验 schema，
  执行前校验 JSON 对象参数。schema 最大 64 KiB、深度 64，仅允许本地 `#` 引用，
  递归检查 `$ref`/`$dynamicRef` 等引用关键字，禁止外部引用及外部资源解析；
  不兼容定义标记配置错误，不放宽校验静默接入。

## R3：文件授权与协作式原子提交

- **Decision**：工作根、读允许根、写允许根分别配置，缺省授权为空。
  `Path.startsWith` 比较真实路径组件；相对路径相对工作根；不存在目标检查真实父目录。
  读可通过软链接，但最终真实目标必须获准；写入解析到最终目标后锁定该目标，提交前复核原始路径
  的解析结果、父目录身份及目标版本。路径检查不代替实际 I/O 前的授权检查。
- **Rationale**：本机 macOS / JDK 21 的目录流实测不支持 `SecureDirectoryStream`。
  用户明确确认可信单机协作写入：所有写入方使用同一稳定 sidecar 锁协议，管理员保持目录结构稳定。
  JDK `FileLock` 是协作锁；`ATOMIC_MOVE` 没有目标版本 CAS。不能承诺抵御任意非协作改名竞态。
- **提交方案**：同父目录 0600 临时文件写全、`force(true)`、关闭后发布。默认创建采用
  `Files.createLink(target, temp)`，目标存在即失败且不会暴露半成品；不支持硬链接则安全失败。
  覆盖与编辑采用同目录 `ATOMIC_MOVE`，不支持时安全失败，不降级 copy 或直接覆盖。
  进程内规范路径锁配合同一部署锁根下按真实目标路径哈希命名的 `FileLock` sidecar；
  锁根由管理员维护，不允许模型配置、删除或通过搜索读出；外部写入方必须使用同一锁根和算法。
  锁等待使用剩余预算。写覆盖只检查身份/大小/mtime，不为了写权限读取旧正文；编辑需读写权限，
  额外比较原内容摘要。临时文件及锁文件不作为用户搜索结果暴露。
- **Alternatives considered**：`ATOMIC_MOVE` 不带 `REPLACE_EXISTING` 也不保证不覆盖，不能用于默认创建；
  `CREATE_NEW` 后直接写目标会暴露部分内容；仅两次 realpath 检查无法消除最后检查后的竞态。

## R4：有限文本读取与确定性搜索

- **Decision**：严格 UTF-8 解码，仅普通文件；按上限加 1 字节检测增长和超限。
  默认单文件/写入结果 1 MiB，列表/搜索 200 项，扫描 1000 项。目录项稳定排序后受预算遍历；
  目录枚举本身也计入扫描上限，不能先无限收集再排序。超出枚举上限返回排序后的已扫描子集，
  不宣称全局最前 200 项。`list_dir` 仅直接子项；搜索不跟随目录软链接，文件软链接仍校验最终授权。
  `grep` 是字面文本，`glob` 是受限可移植文件名模式，不引入任意正则或完整索引。
- **Rationale**：字节、扫描、返回、时间是独立预算。跳过非法编码、超大、无权限条目时只返回
  安全原因计数；完整空结果和部分扫描零命中必须区分。
- **Alternatives considered**：无界 `readString`、递归跟随链接、先收集所有目录项再截断均不满足规格。

## R5：工具 I/O 截止时间与资源清理

- **Decision**：增加显式 `ToolExecutionScope`，携带本次有效单调截止时间、checkpoint、取消资源登记
  及执行/清理结果；经内部 `ToolContext` 注入。有效 deadline 取工具策略与整轮剩余预算较小者，
  重试沿用同一 deadline。治理层先确认 begin，再允许工具注册和启动资源；工具自身设置 I/O 超时。
  取消、租约丢失、工具超时触发同一幂等清理路径；所有清理和审计共同消耗既有 1s 窗口。
- **Rationale**：现有装饰器的 `Future.cancel(true)` 只中断线程，不能证明子进程或远程动作停止；
  内部 `toolDeadline` 尚未传给工具。不得为每条连接/每个子进程再分配一个独立 1s。
- **Alternatives considered**：超时只围住 Future 会留下仍执行的动作；强杀 Java 插件线程不安全。
  不合作插件只能保证停止等待、报告动作状态未知，要求插件自行支持超时/取消，不声称进程隔离。

## R6：Shell 与 STDIO 环境隔离

- **Decision**：真实绝对 executable + argv 数组 + 获准真实 cwd，禁止隐含解释器与 PATH 查找。
  `ProcessBuilder.environment().clear()` 后加入部署者显式允许的基础变量和按命令/服务配置的变量。
  默认继承名单为空，模型无 env 参数。stdout/stderr 并发排空，共享 64 KiB 保留上限；
  超额丢弃并继续排空。进程树保存根句柄并持续采集后代，清理时强制终止可识别集合并有限确认。
- **Rationale**：MCP SDK 2.1.0 `StdioClientTransport` 的默认 ProcessBuilder 会保留宿主环境，
  仅设置 server env 并不足够。其 `getProcessBuilder()` 扩展点可清空环境和设 cwd，但 SDK
  持有的 Process 为 private 且无句柄访问 API，ProcessBuilder 又为 final，无法靠覆写 start 捕获句柄。
  因此选用窄范围 `ManagedStdioTransport` 适配器补宿主进程启动、句柄、管道与有界清理；
  实现官方 transport 端口，继续复用官方 JSON mapper、MCP 会话、初始化、发现和工具回调，
  不重写 JSON-RPC/MCP 协议客户端。默认 stderr 日志不能保留原文。
- **Alternatives considered**：`sh -c`、直接套用默认 STDIO、只终止父进程均不采用。
  脱离/重新托管的后代不能保证可识别，必须呈现清理未确认。白名单不隔离获准程序内部行为。

## R7：复用官方 MCP SYNC 客户端，异步维护生命周期

- **Decision**：Spring AI 2.0.2 + BOM 解析的 MCP SDK 2.1.0；继续关闭客户端自动配置。
  STDIO、JDK HttpClient SSE、JDK HttpClient Streamable HTTP 三种 transport，
  `McpClient.sync(transport)` 编程式构造，使用官方 `SyncMcpToolCallbackProvider` 产出回调。
  每来源后台虚拟线程独立 initialize + tools/list，共用该来源 10s 初始化预算，本地就绪不等待。
  失败按 1s、2s、4s…最多 300s 重连。每次连接/发现重新授权 URL 或程序/cwd。
- **Rationale**：单一坏来源不能阻塞启动，也不能改变其他来源授权。当前轮次钉住客户端 generation；
  来源失联立即关闭该 generation 的可用门，恢复只发布新 generation，旧轮次不能借此重放。
  工具调用由 scope 剩余时间约束，SDK 默认 requestTimeout 只是上界。
- **API 核实**：`McpSyncClient.callTool(CallToolRequest)` 没有每次调用 timeout 重载，
  不能对共享客户端临时改 timeout。缩短的等待到期后有界中止该来源 generation，
  反馈结果未知并禁止重放。SSE 的 message endpoint 也须授权，不能只校验初始地址；
  分析修订进一步限定实际端点与配置 URL 同源（规范化 scheme/host/有效端口），通用白名单不授予
  跨源接收凭证权限；发请求及附加来源 headers 前拒绝跨源，本期不配置跨源例外。
  Streamable HTTP 显式 `resumableStreams(false)`，禁透明业务重发。
  分页发现共用初始化预算，最多 1000 个工具、每响应 1 MiB，不能每页重新计时。
- **Alternatives considered**：Starter 自动连接、同步阻塞启动、失败后临时建新客户端重发均拒绝。
  本地服务进程为应用级共享资源；取消某轮不直接杀掉其他轮仍在使用的健康服务，
  取消不能确认隔离完成时使该来源 generation 失效，再有界关闭并由后台恢复。

## R8：MCP 名称、授权、数据与日志边界

- **Decision**：公开名固定为 `mcp_<server-id>__<remote-name>`。server-id 限 `[a-z0-9-]{1,20}`，
  remote-name 限 `[A-Za-z0-9_-]+`，最终名称最多 64 字符；不兼容名称拒绝注册并报告来源。
  本地插件禁止占用 `mcp_` 保留前缀。用自定义官方名称生成扩展点映射，不依赖会随服务信息变化的前缀。
  `tools` 与 `mcp_servers` 必须同时声明。失联仅延迟明确属于已声明来源的工具存在性检查；
  恢复后发现缺失则后续轮次创建前失败。未知本地名、未知服务即失败。
- **Rationale**：公开名称必须在首次发现前也可归属来源。目录保留已知不可用条目与尚未发现的来源状态。
  模型可见集合隐藏不可用项，执行集合为原已授权失效项保留安全拒绝回调，真实远程动作数为零。
- **数据边界**：显式设置 `ToolContextToMcpMetaConverter.noOp()`，远程调用前另构造空业务上下文，
  不采用仅列举几个内部键再删除的黑名单。批准元数据未来另行白名单添加，本期默认为空。
  MCP 远端 `isError` 内容按不可信错误处理。关闭官方 MCP callback/transport 原始内容日志，
  安全包装层只记来源、状态、类型，不记原始 Throwable、地址、stderr、参数或结果。
- **Alternatives considered**：服务名即授权全部工具、不可用时回退同名本地工具、把完整
  `ToolContext` 给官方默认转换器均拒绝。

## R9：POST 的一次提交与不确定性

- **Decision**：在现有 HTTP 域名白名单上实现 `http_post(url, body, content_type)`，
  内容类型限定 `text/plain`、`application/json`（UTF-8），JSON 文本先校验。
  请求和响应各 1 MiB，禁止重定向、认证参数及自动重试。发出前校验授权/正文/预算；
  调用 send 后故障保守标记“可能生效”，非 2xx/超大响应不返回正文。
- **Rationale**：JDK HttpClient 不跟随跳转；客户端/底层 transport 的重发行为也必须用接收计数
  验证，不能只看工具策略。连接、响应头、正文共用同一 scope deadline。
- **Alternatives considered**：跟随 307/308、超时即声明未提交、因审计失败重新提交均拒绝。

## R10：真实搜索实现采用 Brave Web Search

- **Decision**：交付可配置 `BraveSearchProvider`，默认端点
  `https://api.search.brave.com/res/v1/web/search`，服务凭证仅由环境引用注入
  `X-Subscription-Token` 请求头。GET 参数 `q`、`count`，映射 `web.results[].title/url/description`。
  endpoint 可配置用于兼容服务/本地替身，始终经过 HTTP 授权且禁止跳转。
  查询最多 1000 字符、最多 10 项、响应最多 1 MiB；不抓取命中页面。
- **Rationale**：公开协议简单、无需新增供应商 SDK，能用本地 HTTP 替身核对真实适配逻辑。
  配置缺失保留工具条目但标记不可用；声明它的 Agent 可加载，模型隐藏，旧请求得到明确未配置错误。
  超大响应是失败，不能把部分 JSON 当搜索成功；结果超条数/字符预算时标记截断。
- **Alternatives considered**：抓取搜索网页容易把 HTML 变化当结果；固定桩和仅空接口不算交付。
  公共资料地址 `https://api-dashboard.search.brave.com/app/documentation/web-search/get-started`；
  外部连通性与付费凭证测试单独显式启用，本次未调用真实搜索，不宣称兼容性实测通过。
  本次官方文档站访问超时；官方仓库 `https://github.com/brave/brave-search-mcp-server`
  可确认提供者存在，具体协议适配仍需实施时以公开服务文档和本地协议替身核对。

## R11：ask_user 显式能力与输入所有权

- **Decision**：core 定义 `InteractionPort` 与轮次运行能力；`AgentService` 增加接受能力的重载，
  原重载默认不支持交互。端口显式传到循环/ToolContext，不进 Profile、模型 schema 或 MCP metadata。
  CLI chat 使用一个输入 broker，替代 ChatCommand/工具各自读 stdin。交互按 turnId/requestId/generation
  归属；并发占用、EOF、4000 字符超限明确失败；取消后已开始的读取仍属旧 generation，迟到输入丢弃。
  分析修订要求回答增量读取、固定大小缓冲、最多保留上限加 1 个字符；超限即 TOO_LONG，原 reader
  丢弃余行至换行/EOF，不缓存超额内容。工具等待可取消，底层读/丢弃未结束仍 BUSY，不另建 reader。
- **Rationale**：现有 ChatCommand 和 CliChannel 都有各自 reader；工具再 new reader 会串读。
  每次行读取开始前捕获归属，不能在读完后把行交给当前恰好等待的下一轮。
- **Alternatives considered**：在工具里直接 `System.in`、每个提问启动不可取消 readLine、
  用 ThreadLocal 传终端、先完整 readLine 再判断长度均拒绝。取消且底层读取尚未结束时 broker 保持占用，不能马上给下一轮输入。

## R12：目录查询与最小认证入口

- **Decision**：core 提供 `ToolCatalogQuery` 端口；CLI `tool list [--agent NAME] [--json]`
  和 HTTP `GET /api/v1/tools?agent=NAME` 使用同一安全投影。
  新增 `tool serve` 仅启动工具目录只读 MVC 模式，不提前交付完整 serve/会话 API。
  缺省监听 `127.0.0.1`，非回环必须显式配置；通过环境引用设置 bearer token。
  专用过滤器先认证，再进入控制器/解析 Agent/读取目录，缺失或错误令牌一致 401；
  未配置有效令牌时不装配 HTTP 查询入口，CLI 不受影响。token 只从 Authorization 头读取，
  URL 中的 token 不认证；带此类查询参数的请求安全拒绝，不回显 URL。
- **Rationale**：当前生产没有 serve 子命令，只有调试 HTTP 入口；必须给出真实可运行启动路径。
  查询只读已维护目录，不触发新发现、不等待连接、不启动模型/工具、不创建会话/轮次。
  目录模式只装配必需配置、插件、目录与 MCP 后台维护，不强制初始化 Provider、Embedding 或数据库；
  不可执行的查询路径无需伪造审计仓库。
- **Alternatives considered**：直接暴露现有 debug controller、匿名回环例外、命令输出静态配置投影
  均不满足认证或运行时状态一致性。独立 CLI 缺少 boot launcher 时返回明确不可用，不显示假目录。

## R13：三个 Demo 的官方评估器验收

- **Decision**：落实 constitution VII 和技术方案 12.4：天气/GitHub 用 FactCheckingEvaluator，科技用
  RelevancyEvaluator，输入实际工具资料和最终输出，各运行 3 次且至少 2 次 isPass()；不使用 getScore()
  判断事实核查通过。可用的 deepseek Provider 优先，缺失时 primary 自评并记录限制，评估 ChatClient 不传 tools。
- **运行边界**：新增 DemoEvaluationIntegrationTest，@Tag("llm") 并按 AGENTOS_LLM_API_KEY 启用，
  普通 verify 不外呼；数据源仍为本地受控服务与独立 PostgreSQL，不依赖真实搜索/外部 MCP、Memory 或通知。
  缺模型凭证/未运行记 NOT_RUN，离线 Demo 通过不替代评估测试。测试 API 仍须实施时核对当前依赖并编译验证。

## 研究结论与限制

所有规划疑点已形成决策；文件并发边界由用户在本会话明确确认。其余为实现与验收风险：
原子文件发布能力、MCP 三种 transport 关闭、底层 POST 重发、CLI 迟到输入和非合作插件取消。
必须以受控故障注入验证，不得把本研究当通过证据。未读取 `.env`，未发送真实模型、外部工具或通知请求。

技术方案第 6.1 节仍有早期 shell=60s/read_file=20000 字符的目标默认值；005 采用本次规格
明确的30s/10000字符。实施时同步该章节及已实现/规划中说明，不将历史默认值静默混入005配置。
