package com.agentos.provider.chat;

import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * US-1 的结构化日志记录器。
 *
 * <p>TODO(US-2)：由写入 llm_calls 表的实现替换；outputCapHonored 的告警由 ProviderService 发出。
 */
public class LoggingLlmCallRecorder implements LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingLlmCallRecorder.class);

    @Override
    public void record(LlmCallRecord record) {
        log.atInfo()
                .addKeyValue("event", "llm_call")
                .addKeyValue("provider", record.provider())
                .addKeyValue("requestedModel", record.requestedModel())
                .addKeyValue("mode", record.mode())
                .addKeyValue("attempt", record.attempt())
                .addKeyValue("success", record.success())
                .addKeyValue("durationMs", record.durationMs())
                .log("Provider 调用完成");
    }
}
