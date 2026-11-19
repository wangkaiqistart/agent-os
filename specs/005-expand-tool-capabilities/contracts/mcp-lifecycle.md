# Contract：MCP 配置、发现、调用和关闭

## 接入与 SDK

保持 `spring.ai.mcp.client.enabled=false`，使用官方 SYNC 客户端和官方 callback provider。
当前实际版本为 Spring AI 2.0.2 / MCP Java SDK 2.1.0。

```text
McpClient.sync(transport).requestTimeout(...).initializationTimeout(...).build()
SyncMcpToolCallbackProvider.builder().mcpClients(...)
  .toolNamePrefixGenerator(...).toolContextToMcpMetaConverter(noOp()).build()
```

客户端 `requestTimeout` 是固定构建值，`callTool` 没有单次 timeout 重载。
单次等待使用原工具 scope；不得改共享客户端 timeout 或为缩短超时新建客户端重放调用。

## 传输边界

| transport | 必需配置 | 额外授权/控制 |
|---|---|---|
| stdio | command、args、cwd、可选 env | 真实程序/cwd；清空环境；持有进程树；stdout 专用于 MCP，stderr 受控 |
| sse | url、sse-endpoint、可选环境引用 headers | 初始地址、服务给出的 message endpoint、每次实际请求都授权且限配置 URL 同源；禁跳转 |
| streamable-http | url、endpoint、可选环境引用 headers | 实际请求授权；禁跳转，resumableStreams(false)，禁自动重放 |

HTTP transport 使用 JDK HttpClient，connect/response 均有界，响应≤1 MiB。
所有实际端点须与该来源配置 URL 同源：规范化 scheme/host 并使用有效端口（HTTP 80、HTTPS 443）。
同源检查与通用域名授权均在发请求及附加配置 headers 前执行；凭证只附加到该来源同源请求。
跨 scheme、host 或有效端口的 SSE message endpoint 均拒绝，即使目标已在通用白名单中，
目标请求数和接收到的凭证数也必须为 0。本期不提供跨源端点/凭证接收范围配置。
STDIO 入站消息≤1 MiB；超大/无效消息令该来源安全失败，不记录原文。
`ManagedStdioTransport` 只实现官方 transport 端口的进程/管道所有权，复用官方 mapper 与协议会话。
不能声称官方 StdioClientTransport 已提供 process handle；它仅有 getProcessBuilder 扩展点且 Process 为 private。
不采用反射读取 SDK 私有字段或 ProcessBuilder.start 覆写。

## 名称和目录发布

server-id 限 `[a-z0-9-]{1,20}`。公开名 `mcp_<server-id>__<remote-name>`，最终≤64 字符；
remote-name 限 `[A-Za-z0-9_-]+`，不兼容名称报告来源并拒绝候选注册，不做不透明重命名。
prefix generator 捕获配置 server-id，不依赖服务自报名称/发现顺序/会话 ID。
显式维护 publicName→serverId/originalName 映射；本地禁止占用 mcp_ 前缀。
每来源完整分页发现总计最多 1000 工具，与 initialize 共享 10s，不每页重置。
候选定义/schema/最终重名校验通过后原子发布，不覆盖任何现有其他来源工具。

## 可用性与快照

启动只注册配置来源状态并调度后台初始化；不在构造器或 readiness 路径等待远端。
配置错误安全定位 server-id 和字段名，绝不显示凭证/完整地址/env 值。
失败退避 1s 倍增至 300s；重连前重新授权程序/地址。

Agent 同时声明 server-id 与公开名。来源不可用但配置存在时 Agent 可加载；
后续轮次模型定义中该来源数量为零，查询保留 known tools 和来源状态；首次未发现也有来源行。
当前轮次固定原授权与 generation。失效后的原绑定只返回安全不可用错误，底层调用零次；
恢复发布新 generation，只供后续轮次，不能让旧 callback 自动代理新连接。
恢复发现原声明工具不存在，新轮次创建前配置失败；新发现工具未声明仍不可见/不可执行。

## 调用、取消与数据隔离

所有 MCP 调用仍由原 Manager 进入 GovernedToolCallback，先审计后委托官方 callback。
默认非幂等；按工具显式策略也只能沿用 004 的未开始瞬时失败重试条件。
超时、断线、取消后结果不明不重放，重连也不重放。

设置 `ToolContextToMcpMetaConverter.noOp()`，在委托前再构造只含允许元数据的上下文（默认空）。
会话/轮次对象、账本、预算、交互能力、workspace、租约全部不发送，不出现在模型输入 schema。
只删除三个旧内部键不是充分隔离，新加内部键也不得外泄。
远端 isError、JSON-RPC 错误、stderr 都是不可信原文，外层转换为安全错误。
官方 MCP callback/session/transport 内容 logger 关闭，安全层只记录关联 ID/来源/状态。

scope 到期或取消后，先取消等待；SDK 不能确认单请求取消时使该 generation 不可用并有界关闭。
只影响该来源；同来源在途调用可能安全失败，其他来源必须仍可调用。
本地 MCP 服务属于应用级资源；只在 generation 失效或应用关闭时回收其负责启动的进程，
不因某轮结束无条件杀掉共享健康服务。进程树按可识别范围清理，未确认不能伪称全部退出。
关闭/清理与审计共享既有窗口，不逐资源延长；应用关闭停止重连并等待有界清理，记录未确认状态。

## 必须验证

三 transport 分别发现/调用/关闭；两个同名服务；配置错与未知服务；全量分页上限；
坏来源不拖本地就绪；失联隐藏/拒绝、恢复/新工具未授权、旧 generation 不换绑；
SSE 恶意 message endpoint 与跳转零越权，包括通用白名单内的另一来源/端口请求及凭证均为 0；
同源相对端点及默认端口等价比较通过；上下文哨兵零外泄；宿主未授权 env 不继承；
取消/关闭进程树及未确认清理；SDK 原始错误日志不泄漏；远程动作不确定零重放。
