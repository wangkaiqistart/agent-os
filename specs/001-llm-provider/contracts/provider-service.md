# Contract: ProviderService 与 EmbeddingService

**Owner**: `AgentOS-core`（`com.agentos.core.provider`） | **Implementation**: `AgentOS-provider` | **Consumers**: US-2 的 `ReActLoop`、评估器；US-3 的记忆检索

类型字段见 [../data-model.md](../data-model.md)。本文件只定义行为。

## ProviderService.call(ProviderRequest) → ProviderResult

处理顺序：

1. **外呼前校验**，任一失败抛 `AgentOSException(INVALID_ARGUMENT)`，不发请求、不产生调用记录：
   - 解析连接：`profile.provider` 为空时取默认连接；名称不存在 → "未知的 provider：<名称>"。
   - 连接不可用（key 为空）→ "provider 不可用：<名称>，缺少环境变量 <变量名>"。
   - `profile.model` 为空或空白 → "Profile 缺少聊天模型"。
   - 消息里有非图片媒体 → "不支持的媒体类型：<MIME>"。
   - 已过截止时间 → 按超时处理，抛 `ProviderCallException`，`calls` 为空。
2. **组装请求**：只写 Profile 设置过的生成参数；输出上限按连接类型映射；工具与 `toolContext` 原样放入请求选项；单次超时 = min(连接超时, 截止时间 − 当前时间)。
3. **发出第 1 次请求**，结束后立即调用 `LlmCallRecorder.record(...)`。
4. **失败时**按 research R6 分类：可重试且满足 FR-018（等待 `Retry-After` 后剩余时间 ≥ 连接的 `retry-min-remaining`，没有截止时间时直接满足）则发第 2 次请求，超时取 min(连接超时, 剩余时间)，并记录；否则抛 `ProviderCallException`，附带全部记录。
5. **成功时**：没有候选结果按 PROTOCOL 失败处理（不重试）；Profile 设了输出上限且 completionTokens 超过上限时，记录 `outputCapHonored=false` 并告警；返回 `ProviderResult`。

保证：

- 不执行工具，不发起下一次模型调用（FR-015）。
- 只有工具调用、没有文本的响应是成功结果（FR-016）。
- 最多 2 次请求（SC-003）；总耗时不超过截止时间加 1 秒（SC-004）。
- 线程被中断时立即停止等待，记为 CANCELLED，不重试，并恢复中断标志。

## ProviderService.stream(ProviderRequest, Consumer<String> textSink) → ProviderResult

与 `call` 相同，另有：

- 每个非空文本增量按到达顺序交给 `textSink`；工具调用只在聚合结果中出现，并且是拼接完整的（FR-014）。
- 请求总是带 `stream_options.include_usage`。
- 已经向 `textSink` 交出过增量后失败：不重试，记录失败，抛 `ProviderCallException`（FR-019）。
- 返回值与 `call` 等价：`response` 是聚合后的 `ChatResponse`；一次正常调用只有 1 条记录，用量不重复累加。
- `textSink` 抛出的异常视为调用方取消：停止接收，记录为 CANCELLED。

## EmbeddingService

- `embed(List<String>)`：按 `max-batch-size` 分批顺序发送，返回数量和顺序与输入一致（FR-033）；每批按 research R6 最多重试 1 次（FR-034），向量化没有本轮截止时间，重试门槛不适用；空列表返回空列表、不发请求。
- 每批响应都检查每个向量的长度等于 `dimensions`；不一致时整次调用失败、不返回任何向量，状态转为 `MISCONFIGURED`（FR-032）。
- 按当前状态处理（research R12）：

  | 状态 | `embed` 的行为 |
  |------|---------------|
  | `READY` | 正常发送 |
  | `CHECKING` | 等待后台校验结果，最多一个校验超时；之后按新状态处理，仍未完成则报"向量化校验中" |
  | `DISABLED` | 抛 `AgentOSException(PROVIDER_UNAVAILABLE, "向量化未启用")` |
  | `UNAVAILABLE` | 抛 `AgentOSException(PROVIDER_UNAVAILABLE, "向量化不可用：<原因>")`，不触发额外校验 |
  | `MISCONFIGURED` | 抛 `AgentOSException(PROVIDER_UNAVAILABLE, "向量化配置错误：配置维度 <n>，实际 <m>")` |

- 每次 `embed` 写一条 `embedding_call` 结构化日志（FR-035）。
- 只接受文本：`List<String>` 本身不能携带媒体；US-3 若用 `Document` 调用，带媒体的 `Document` 由调用方拒绝（FR-037）。

## 错误码对照

| 情况 | 异常 | 错误码 | 有调用记录 |
|------|------|--------|-----------|
| 未知 provider、连接不可用、缺模型、不支持的媒体 | `AgentOSException` | `INVALID_ARGUMENT` | 否 |
| 所有尝试都失败 | `ProviderCallException` | `PROVIDER_UNAVAILABLE` | 是（1 ~ 2 条） |
| 向量化未启用 / 校验中 / 不可用 / 配置错误 | `AgentOSException` | `PROVIDER_UNAVAILABLE` | 否 |

`GlobalExceptionHandler.toHttpStatus` 已有这两个错误码的映射（400 / 503），不需要新增。
