package com.agentos.provider.chat;

import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * US-1 的结构化日志记录器。
 *
 * <p>TODO(US-2)：由写入 llm_calls 表的实现替换；outputCapHonored 的告警由 ProviderService 发出。
 * 当前实现输出 {@link LlmCallRecord} 的全部调用元数据，不记录 Prompt、密钥、图片和工具参数。
 */
public class LoggingLlmCallRecorder implements LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmCallRecorder.class);

    @Override
    public void record(LlmCallRecord record) {
        // 使用 SLF4J 的结构化字段，而不是把字段拼进一长段字符串。
        // 这样日志平台可以按 provider、success、durationMs 等字段过滤。
        // LlmCallRecord 的每个字段一个键，空值照样输出 null，便于区分“没有值”和“值为 0”。
        // UUID、枚举和 Instant 先转成字符串，JSON 里的取值不依赖格式化器对对象的处理方式。
        log.atInfo()
                .addKeyValue("event", "llm_call")
                .addKeyValue("sessionId", text(record.sessionId()))
                .addKeyValue("turnId", text(record.turnId()))
                .addKeyValue("iteration", record.iteration())
                .addKeyValue("provider", record.provider())
                .addKeyValue("requestedModel", record.requestedModel())
                .addKeyValue("responseModel", record.responseModel())
                .addKeyValue("responseId", record.responseId())
                .addKeyValue("mode", text(record.mode()))
                .addKeyValue("attempt", record.attempt())
                .addKeyValue("finishReason", record.finishReason())
                .addKeyValue("toolCallCount", record.toolCallCount())
                .addKeyValue("imageCount", record.imageCount())
                .addKeyValue("promptTokens", record.promptTokens())
                .addKeyValue("completionTokens", record.completionTokens())
                .addKeyValue("totalTokens", record.totalTokens())
                .addKeyValue("cacheReadTokens", record.cacheReadTokens())
                .addKeyValue("cacheWriteTokens", record.cacheWriteTokens())
                .addKeyValue("requestsRemaining", record.requestsRemaining())
                .addKeyValue("tokensRemaining", record.tokensRemaining())
                .addKeyValue("outputCapHonored", record.outputCapHonored())
                .addKeyValue("success", record.success())
                .addKeyValue("failureKind", text(record.failureKind()))
                .addKeyValue("httpStatus", record.httpStatus())
                .addKeyValue("errorSummary", record.errorSummary())
                .addKeyValue("startedAt", text(record.startedAt()))
                .addKeyValue("durationMs", record.durationMs())
                .log("Provider 调用完成");
    }

    /** 非空对象转字符串，null 保持 null。 */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
