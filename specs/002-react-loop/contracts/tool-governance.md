# Contract: 工具与治理

**Owner**: 契约在 `AgentOS-core`（`Sandbox`、`ToolPolicy`、`Redactor`、`ToolInvocationRecorder`），实现在 `AgentOS-tool` | **Consumers**: `ToolExecutor`、`ReActLoop`

## http_get

| 项 | 内容 |
|----|------|
| 名称 | `http_get` |
| 输入 | `url`（必填，`http` 或 `https` 的绝对地址） |
| 输出 | 响应体文本；非 2xx 视为失败，只报状态码，不回显响应体 |
| 策略 | 幂等；超时 30 秒，连接与读取超时设在 `HttpClient` 上；结果上限 10000 字符 |
| 重定向 | 不自动跟随。收到 3xx 且有 `Location` 时手动跟随，最多 3 跳；**每一跳**的目标都先过沙箱（FR-028） |
| 响应体 | 读到 1 MiB 就停止，超出部分丢弃，不撑爆内存 |
| User-Agent | 取配置，默认 `AgentOS/<版本>`；有的公共接口（如 met.no）要求带可识别的 User-Agent |

## Sandbox

`Sandbox.enforce(SandboxAction)`，`SandboxAction(ActionType type, String target)`，`ActionType` 取 `FILE_READ`、`FILE_WRITE`、`SHELL`、`HTTP`。拒绝时抛 `SandboxViolationException`，消息写明被拒绝的类型和目标域名（目标网址里的密钥参数先脱敏）。

`WhitelistSandbox` 是本 story 唯一的实现，只认 `HTTP`，其余三种一律拒绝：

- 白名单来自 `agentos.tools.http.allowed-domains`，默认空，即全部拒绝。
- 条目是精确域名（不分大小写），或 `*.example.com`；后者只匹配子域名，不匹配 `example.com` 本身。
- 目标是 IP 地址时，必须原样出现在白名单里；不因为"是内网地址"而特殊放行，也不做 DNS 解析后的二次校验（残留风险见 plan）。

## 工具名单与解析

- 每一轮的可用工具 = `AGENT.md` 的 `tools` 名单（FR-025）。名单为空时，请求里不带任何工具定义。
- `ToolRegistry` 在装配时调用 `ToolCallingChatOptions.validateToolCallbacks(...)` 校验重名，名单里有未注册的工具名时，在轮次开始前失败（FR-026）。
- 全局注册一个 `ToolCallbackResolver`，对任何名字都返回"本 Agent 没有名为 X 的工具"的回调，并打开 `spring.ai.tools.resolution.fallback.enabled`（research R2）。这个回调同样套 `GovernedToolCallback`，所以名单外的调用也有一条状态为失败的审计记录（FR-030）。

## GovernedToolCallback：每次调用的顺序

1. `TurnGuard.checkpoint`：整轮截止时间与续租（FR-005、FR-029）。失败时工具还没开始，没有记录。
2. 从账本取同名队首的调用 ID（research R5）。
3. 参数先脱敏，写一条状态为 `in_progress` 的工具调用记录。写入失败按 FR-031 重试，仍失败抛 `AuditWriteException`，**这个工具不会被执行**。
4. 在虚拟线程上执行，最多等"工具策略超时"与"剩余整轮时间"中较小的值。
5. 结果超过 `max_result_chars` 就截断，末尾注明"结果已截断，原长 N 字符"。
6. 结果脱敏，把记录更新为终态。更新失败重试，仍失败抛 `AuditWriteException`，给使用者的说明注明该操作可能已经执行。
7. 失败时抛 `ToolExecutionException`，由处理器决定（research R3）。

| 情形 | 记录状态 | 给模型的结果 |
|------|---------|--------------|
| 正常返回 | `success` | 结果文本（可能已截断） |
| 抛业务异常 | `failed` | "工具 X 执行失败：<脱敏原因>，<是否值得重试>" |
| 超时，原因是工具自己的超时 | `timeout` | 同上，原因写"超时" |
| 沙箱拒绝 | `sandbox_denied` | 同上，原因写被拒绝的类型和域名 |
| 次数超限 | `limit_skipped` | Spring AI 生成的超限文本；回调没被调用，记录由 `ToolExecutor` 补写 |
| 名单外或不存在的工具 | `failed` | "本 Agent 没有名为 X 的工具" |
| 参数不是合法 JSON | `failed` | 同上，原因写"参数不是合法 JSON" |
| 超时，原因是整轮剩余时间更短 | `timeout` | 不反馈给模型：重抛 `TurnDeadlineExceededException`，本轮 `TIMED_OUT`（FR-019） |
| 租约丢失、审计写入失败 | 保持 `in_progress`，由启动收尾改为 `interrupted` | 不反馈给模型：重抛，结束本轮 |

## 脱敏（Redactor）

- 网址里的 `key`、`token`、`access_token`、`sign` 查询参数，值替换成 `***`。
- 名为 `authorization`、`api_key`、`apikey`、`token`、`secret`、`password` 的字段和请求头（JSON 键，以及 `name=value`、`name: value` 形式）。
- 已知的密钥原值：由装配时提供的集合给出，本 story 取各 Provider 连接的 API key 和数据库密码；US-5 的 `ConfigLoader` 接手后改由它提供。原样出现就替换成 `***`。
- 脱敏发生在：写审计之前、把错误信息返回模型之前、写日志之前（FR-036）。日志不输出工具参数和结果（FR-037）。

## 次数上限与跳过的记录

- 配置沿用 `spring.ai.tools.limits.*`：`max-calls-per-tool-default: 10`、`max-total-tool-calls: 30`、`on-limit-exceeded: RETURN_ERROR_RESPONSE`。
- 超限的调用不会进入回调。`ToolExecutor` 在管理器返回后，把账本里剩下的调用 ID 逐个写成 `limit_skipped` 记录（FR-016）。
- 交给管理器的 prompt 要带本轮累计的历史，次数才是跨迭代累计的（research R4）。
