# Contract: core 端口变化

本期对入口（CLI、调试 HTTP、后续 REST / 定时）暴露的 core 接口变化。实现模块只依赖这些契约（依赖倒置）。

## 1. `TurnSink`（`com.agentos.core.turn`）

| 方法 | 变化 | 语义 |
| --- | --- | --- |
| `boolean streaming()` | 不变 | 为 `true` 时循环使用流式模型调用 |
| `void onEvent(TurnEvent event)` | **新增** | 按 `seq` 顺序收到本轮全部事件；同一时刻只有一个线程调用；抛异常即视为输出端失效 |
| `onDelta` / `onToolCall` / `onToolResult` / `onComplete` / `onError` | 保留 | 兼容层：`onEvent` 的默认实现把 `message.delta`、`tool.started`、`tool.finished`、控制面事件翻译成这 5 个回调。核心代码不再直接调用它们 |

兼容层的映射：

| 事件 | 旧回调 | 与旧行为的差异 |
| --- | --- | --- |
| `message.delta` | `onDelta(text)` | 同步调用也会收到一次全文 |
| `tool.started` | `onToolCall(id, name, argumentsSummary)` | 改为单个工具真正执行前触发，而不是整批执行前一次性触发 |
| `tool.finished` | `onToolResult(id, name, resultSummary)` | 改为单个工具结束时触发；失败、拒绝、跳过也会触发 |
| `run.completed` | `onComplete(TurnResult)` | 不变 |
| `run.failed` / `run.cancelled` | `onError(TurnResult)` | 不变 |

## 2. `AgentService`（`com.agentos.core.turn`）

| 方法 | 变化 | 语义 |
| --- | --- | --- |
| `boolean cancel(UUID turnId)` | **新增**，默认返回 `false` | 请求取消本进程内正在执行的轮次。已受理返回 `true`；轮次不存在、不在本进程或已结束返回 `false`。幂等：重复调用不改变结果。只发出请求，不等待轮次结束；结束由该轮的 `process(...)` 返回和终态事件体现 |

## 3. `TurnResult`

新增可空字段 `failureCode`（`TurnFailureCode`）。成功、取消时为空。保留原 5 参数构造器。

## 4. `TurnStatus`

新增 `CANCELLED`。入口按 `status` 判断时要处理这个新值：CLI 映射为退出码 3（与其他非成功状态一致）。

## 5. `TurnExecutionBudget`

| 方法 | 变化 |
| --- | --- |
| `cancel()` | 保留，等价于 `cancel(CancelReason.INTERNAL)` |
| `cancel(CancelReason reason)` | **新增**；第一次的原因生效；`USER` 把 hard stop 收紧到现在 + 2 秒，其他原因收紧到现在 + 1 秒 |
| `CancelReason cancelReason()` | **新增**；未取消时为空 |

## 6. `ToolContextKeys`

新增 `TURN_EVENTS = "agentos.turnEvents"`，值为 core 的事件发布接口（工具模块只调用 `publish(...)`）。和其他内部键一样，不会经 MCP 发给外部服务。
