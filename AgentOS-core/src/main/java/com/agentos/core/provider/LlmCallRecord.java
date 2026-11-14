package com.agentos.core.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次实际 Provider 请求的记录。
 *
 * <p>空值与 0 不混用。记录不得包含 key、Prompt、工具参数、图片数据、网址或原始响应体。
 *
 * <p>一个逻辑调用最多有两次实际尝试：第一次失败后，只有符合重试策略的失败才会产生第二次记录。
 * 因此 {@code attempt} 只能是 1 或 2。
 *
 * @param sessionId 所属会话 ID
 * @param turnId 所属轮次 ID
 * @param iteration 所属 ReAct 迭代序号
 * @param provider 使用的具名 Provider
 * @param requestedModel 请求时指定的模型名
 * @param responseModel Provider 实际返回的模型名
 * @param responseId Provider 返回的响应 ID
 * @param mode 同步调用或流式调用
 * @param attempt 本次逻辑调用中的第几次尝试，只允许 1 或 2
 * @param finishReason 模型结束原因，例如 stop 或 tool_calls
 * @param toolCallCount 本次响应包含的工具调用数量
 * @param imageCount 请求中包含的图片数量
 * @param promptTokens 输入 Token 数量
 * @param completionTokens 输出 Token 数量
 * @param totalTokens 输入加输出的 Token 总数
 * @param cacheReadTokens 从缓存读取的 Token 数量，无法获知时为 null
 * @param cacheWriteTokens 写入缓存的 Token 数量，无法获知时为 null
 * @param requestsRemaining Provider 返回的剩余请求额度
 * @param tokensRemaining Provider 返回的剩余 Token 额度
 * @param outputCapHonored 是否遵守了输出上限
 * @param success 本次实际请求是否成功
 * @param failureKind 失败分类；成功时通常为 null
 * @param httpStatus Provider 返回的 HTTP 状态码
 * @param errorSummary 脱敏后的错误摘要
 * @param startedAt 实际请求开始时间
 * @param durationMs 实际请求耗时，单位为毫秒
 */
public record LlmCallRecord(
        UUID sessionId,
        UUID turnId,
        Integer iteration,
        String provider,
        String requestedModel,
        String responseModel,
        String responseId,
        CallMode mode,
        int attempt,
        String finishReason,
        Integer toolCallCount,
        int imageCount,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long cacheReadTokens,
        Long cacheWriteTokens,
        Long requestsRemaining,
        Long tokensRemaining,
        Boolean outputCapHonored,
        boolean success,
        FailureKind failureKind,
        Integer httpStatus,
        String errorSummary,
        Instant startedAt,
        long durationMs) {

    /**
     * record 的紧凑构造器。
     *
     * <p>Java record 会自动生成普通构造器、访问器、equals 和 hashCode；这个构造器允许我们在创建对象时
     * 额外做不变量校验。
     */
    public LlmCallRecord {
        if (attempt != 1 && attempt != 2) {
            throw new IllegalArgumentException("attempt 必须为 1 或 2");
        }
        if (imageCount < 0 || durationMs < 0) {
            throw new IllegalArgumentException("图片数量和耗时不能为负数");
        }
    }
}
