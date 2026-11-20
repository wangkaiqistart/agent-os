# US2 演示：截断或被过滤的回答不算成功

- 日期：2026-11-21
- 方式：脚本化模型，不依赖真实模型
- 命令：`mvn -B -ntp -pl AgentOS-core,AgentOS-provider -am -Dtest='ReActLoopFinishReasonTest,PromptHistoryTest,ReActLoopTest,ReActLoopStreamingTest,RetryPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test`

## 样本结果（`ReActLoopFinishReasonTest`，9 个用例全部通过）

| 结束原因 | 调用方式 | 响应里带工具调用 | 轮次状态 | 错误码 | 工具执行次数 | 模型调用次数 | 写入的助手消息 | `error_message` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `length` | 同步 | 否 | FAILED | OUTPUT_TRUNCATED | 0 | 1 | 无 | `OUTPUT_TRUNCATED：模型输出达到上限被截断，可调大 max_output_tokens 或拆分任务` |
| `LENGTH` | 流式 | 否 | FAILED | OUTPUT_TRUNCATED | 0 | 1 | 无 | 同上 |
| `length` | 同步 | 是 | FAILED | OUTPUT_TRUNCATED | 0 | 1 | 无 | 同上 |
| `length` | 流式 | 是 | FAILED | OUTPUT_TRUNCATED | 0 | 1 | 无 | 同上 |
| `content_filter` | 同步 | 否 | FAILED | CONTENT_FILTERED | 0 | 1 | 无 | `CONTENT_FILTERED：回答被内容安全策略过滤` |
| `CONTENT_FILTER` | 流式 | 否 | FAILED | CONTENT_FILTERED | 0 | 1 | 无 | 同上 |
| `content_filter` | 流式 | 是 | FAILED | CONTENT_FILTERED | 0 | 1 | 无 | 同上 |
| `stop` / `STOP` | 同步 | 否 | SUCCEEDED | — | — | 1 | 有 | — |

"工具执行次数 0"由 `verifyNoInteractions(ToolCallingManager)` 断言；工具回调本身一旦被调用就会抛 `AssertionError`。

## Provider 层不重试（`RetryPolicyTest`）

`StubModelServer` 分别返回 `finish_reason=length`、`content_filter`：`DefaultProviderService` 都只发出 1 次请求，只写 1 条 `success=true` 的调用记录，响应里保留原结束原因。

## 下一轮上下文（`PromptHistoryTest`）

上一轮各种失败进入下一轮 prompt 时，只保留用户消息和一条固定说明，半截回答（`PARTIAL_ANSWER`）和 `error_message` 原文都不会出现：

| 上一轮 | 进入 prompt 的说明 |
| --- | --- |
| FAILED + `OUTPUT_TRUNCATED：…` | 上一轮处理未完成：上一轮回答因长度上限被截断 |
| FAILED + `CONTENT_FILTERED：…` | 上一轮处理未完成：上一轮回答被内容策略过滤 |
| CANCELLED | 上一轮处理未完成：本轮已被用户取消 |
| FAILED + 其他 | 上一轮处理未完成：处理失败 |
