# Data Model: AgentOS 工具注册与安全执行

## 1. ToolDefinition（工具定义）

**来源**：注册目录中的统一工具回调定义。

| 字段 | 类型/约束 | 说明 |
|------|-----------|------|
| name | 字符串，必填、全局唯一 | 模型调用名称；重名在目录建立时失败 |
| description | 字符串，必填 | 面向模型的用途说明，不包含凭证或内部堆栈 |
| inputSchema | JSON Schema 字符串，必填且可解析 | 模型可填写的参数约束；内部上下文不进入 Schema |
| returnDirect | 布尔元数据 | 工具结果是否可以直接作为本轮最终结果 |
| source | 字符串 | 首期为 `builtin`；扩展工具必须声明来源 |

**生命周期**：启动或 Agent 加载时注册；请求期间只读。目录不自动扫描未授权 Bean。

## 2. AgentToolAuthorization（Agent 工具授权）

| 字段 | 类型/约束 | 说明 |
|------|-----------|------|
| agentName | 非空字符串 | 对应 Agent 配置 |
| toolNames | 有序字符串集合 | 只允许目录中已注册名称；空集合合法且表示不提供工具 |
| sourceFile | 路径/描述 | 错误信息中指出授权来源，不包含敏感值 |

**规则**：

- `toolNames` 按声明顺序生成模型可见回调集合。
- 未知名称在模型请求前失败，不创建轮次或工具执行记录。
- 运行中收到未授权/不存在名称时由安全 resolver 返回拒绝工具结果，不执行同名组件，允许本轮继续。
- Agent/Skill 目录本身不是工具定义。

## 3. ToolCall（模型工具调用）

| 字段 | 类型/约束 | 说明 |
|------|-----------|------|
| id | 非空、同一批唯一 | 模型生成的调用标识 |
| name | 非空 | 必须匹配授权回调或进入安全拒绝分支 |
| arguments | JSON 文本 | 传给工具的模型参数；治理层先校验 |
| iteration | 正整数 | 所属模型迭代 |
| order | 正整数 | 同名调用按模型响应顺序关联 |

**状态**：`proposed → claimed → in_progress → success/failed/timeout/sandbox_denied/interrupted/limit_skipped/unconfirmed`。不允许生成假 ID；整批 ID 缺失、重复或与账本不匹配时安全失败。

## 4. ToolInvocationAudit（工具调用审计）

与 `AgentOS-storage` 的 `tool_invocations` 对应。

| 字段 | 类型/约束 | 说明 |
|------|-----------|------|
| id | 数据库生成标识 | 审计行主键 |
| sessionId | UUID，必填 | 会话关联 |
| turnId | UUID，必填 | 轮次关联 |
| iteration | 正整数 | 模型迭代 |
| toolCallId | 非空 | 调用标识 |
| toolName/toolSource | 非空 | 工具身份；首期 source 为 builtin |
| inputJson | 脱敏 JSON 或安全文本 | 不存凭证 |
| status | 小写状态枚举 | `in_progress`、`success`、`failed`、`timeout`、`sandbox_denied`、`interrupted`、`limit_skipped` 或 `unconfirmed` |
| resultText | 可空、脱敏 | 普通工具默认最多 10000 个字符且可配置；超限只保留受限前缀和固定截断标记；HTTP 响应正文超 1 MiB 时不回传正文 |
| resultChars | 可空、非负 | 原始结果字符数 |
| resultTruncated | 布尔 | 是否发生普通工具结果截断 |
| errorMessage | 可空、固定安全文案 | 不存原始异常链、URL 凭证 |
| idempotent | 布尔 | 只有 `true` 才允许对明确瞬时失败自动重试；默认最多 1 次重试 |
| attempts | 非负整数 | 实际底层调用尝试次数；每次尝试均消耗单工具和本轮工具预算 |
| durationMs | 可空、非负 | 工具执行耗时 |
| createdAt/updatedAt | 时间戳 | 审计生命周期 |

**唯一性**：`(turnId, iteration, toolCallId)` 唯一。完成写入只更新原 `in_progress` 行；身份或载荷冲突必须失败。完成状态未知或收尾写入未确认时，原行进入 `unconfirmed`，禁止重跑；执行前 begin 无法确认时不启动底层工具。

## 5. TurnExecutionBudget（轮次执行预算）

| 字段 | 类型/约束 | 说明 |
|------|-----------|------|
| deadline | Instant + 单调时钟 | 业务截止时间 |
| hardStopDeadline | 单调时钟 | 仅保留用于最终审计/清理的窗口 |
| modelCalls | 非负计数 | 对应 ReAct 最大迭代 |
| totalToolCalls | 非负计数 | 对应 Spring AI 总工具限次；每次底层尝试（包括允许的重试）均计数 |
| perToolCalls | 工具名到计数 | 对应单工具限次 |
| cancelled | 布尔 | 取消后禁止新增外呼 |
| auditsConfirmed | 布尔 | 任一审计无法确认后为 false |

**默认值**：模型 10、单工具 10、总工具 30、整轮 30 秒；HTTP 每次读取还受 30 秒工具配置和同一整轮剩余时间的较小值约束。

## 6. SandboxPolicy（沙箱策略）

首期实现为 `WhitelistSandbox`：

- ActionType 为 HTTP；其他动作默认拒绝。
- allowedDomains 默认空。
- URL 必须为绝对 HTTP/HTTPS、无 user-info。
- 精确域名精确匹配；`*.example.com` 只匹配子域，不匹配根域；IP 仅精确匹配。
- 每次 HTTP 重定向重新校验；最多 3 次。

## 7. ToolResult（工具结果）

| 字段 | 类型/约束 | 说明 |
|------|-----------|------|
| callId/name | 关联 ToolCall | 回填模型上下文和审计 |
| value | 脱敏文本或安全错误 | 普通工具默认最多 10000 个字符且可配置，超限附固定截断标记；HTTP 超限只返回安全错误，不返回正文 |
| status | success 或安全错误分类 | 由治理层映射为模型可继续或终止信号 |
| direct | 布尔 | 继承工具元数据决定是否提前结束 |
