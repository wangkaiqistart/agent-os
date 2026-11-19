# Contract：注册、授权、执行与查询

## Java 插件入口

1. 标准 Spring Bean 中的 `@Tool` 方法，经官方 `ToolCallbacks.from(bean)`。
2. 显式 `ToolCallback` Bean，可选 ToolRegistration 治理元数据。
3. 方法级 `com.agentos.tool.annotation.ToolPolicy` 提供 idempotent/timeout/maxResultChars；
   未声明为 false/30s/10000。保留官方 ToolMetadata，包括 returnDirect。

同一 Bean 方法/同一回调实例只注册一次；不同定义同名报来源及名称，不能静默覆盖。
名称 `[A-Za-z0-9_-]{1,64}`，本地不可用 `mcp_` 前缀。描述非空≤4000 字符；输入为有效 object schema，
缺省/2020-12、≤64 KiB/64 层、所有引用关键字仅允许本地引用，禁止校验器解析远程资源。
候选 schema 与最终重复名都校验后才发布。
无效本地启动配置失败；无效 MCP 更新拒绝该候选，来源不可用、其他来源继续工作。

## Agent 声明

```yaml
tools: [read_file, http_get, mcp_news__search]
mcp_servers: [news]
```

- tools 是精确公开名、有序、不重复；mcp_servers 同样不重复。服务引用不授权其全部工具。
- 未知本地名、未知服务在 startTurn/模型外呼前失败。
- 已配置、已声明但失联来源的公开名可延期存在性校验；恢复后未发现则新轮次创建前报配置错误。
- 已注册但 UNCONFIGURED 的 web_search 可声明，后续模型隐藏，执行时安全拒绝。
- 不可用条目在 Agent 查询中仍显示 authorized=true、visible=false。

## core 端口语义

`ToolCatalog.resolve(AgentProfile)` 返回不可变 Agent 授权快照；原 validate/callbacks 可作为兼容适配，
运行主链必须在建轮次前 resolve 一次并传递，不能分两次读动态状态。
`ToolCatalogQuery.snapshot(agentName?)` 返回安全查询投影，不产生轮次、模型调用、工具动作或同步发现。

快照至少包含 version、来源、modelCallbacks、executionCallbacks、name→source/generation 绑定。
Provider 只收可见集合，ToolExecutor 只使用本轮执行集合。来源失效只能拒绝原绑定，不能换成新来源。
不可用来源安全拒绝不进入真实远程调用；未授权 callback resolver 也绝不解析同名 Bean/Function。

## 统一执行与失败

唯一动作链仍为 ToolExecutor → 注入的官方 Manager → GovernedToolCallback → 委托。
授权/checkpoint → 原调用 ID 审计 begin 确认 → 参数 schema 校验 → 领取尝试预算 → 实际动作 →
脱敏/截断/checkpoint → 原行 complete 确认。审计失败、租约丢失、整轮超时终止轮次。
普通参数、来源不可用、Sandbox、工具自身超时/业务错误反馈安全结果，模型可继续。

失败信息区分未开始与可能已执行；超时、取消、连接恢复、审计未确认均不得重放副作用。
幂等性也不自动允许重试：仅沿用 004 的“明确尚未开始的瞬时故障最多一次”，同调用 ID/同 deadline，
每次尝试消耗预算。write_file/edit_file/shell/http_post/ask_user 绝不自动重跑。

治理构造 ToolExecutionScope，把有效工具 deadline、资源清理与执行状态显式传入；
MCP 委托只收到业务参数及空 metadata。不存在绕过治理的插件/MCP 专用执行入口。
未进入 callback 的整批非法参数、限次跳过以及 resolver 拒绝也写真实来源，未知来源标 unknown。

## 查询 CLI

```text
AgentOS [--config PATH] tool list [--workspace PATH] [--agent NAME] [--json]
AgentOS [--config PATH] tool serve [--workspace PATH]
```

list 默认人类可读表，--json 输出 HTTP data 同形的快照。退出 0 表示查询成功，
2 表示参数/Agent 声明非法，1 表示运行时/启动失败；不可用来源作为正常目录内容展示。
list 不需要 HTTP token；standalone CLI 无 boot launcher 返回固定错误/非零，不伪造结果。
serve 只开放 GET /api/v1/tools，缺 token 安全失败；配置远程监听及 TLS 边界见 configuration。
help/version/init 保持无上下文行为；不为 tool 命令顺手实现完整 serve 或会话接口。

HTTP 的准确字段、认证及状态码见 [tool-query.openapi.yaml](./tool-query.openapi.yaml)。
两个入口使用同一 query 服务；集成测试向两者注入同一快照比较，不跨两次后台更新强求版本相同。
