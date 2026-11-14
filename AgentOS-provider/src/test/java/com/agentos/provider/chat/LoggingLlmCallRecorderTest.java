package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * LoggingLlmCallRecorder 的结构化字段。
 *
 * <p>用 Logback 的 ListAppender 抓真实日志事件，读取 SLF4J key-value 的实际值。
 * 期望字段清单手写，不从 LlmCallRecord 反射生成，这样漏掉任何一个字段都会失败。
 */
class LoggingLlmCallRecorderTest {

    private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TURN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant STARTED = Instant.parse("2026-11-14T08:30:00.123Z");

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingLlmCallRecorder.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("成功记录：26 个字段全部输出，服务端返回的 0 保持为 0")
    void successRecordLogsEveryFieldAndKeepsZero() {
        // 同类型字段取值互不相同，字段之间取错值也会失败
        LlmCallRecord record =
                new LlmCallRecord(
                        SESSION,
                        TURN,
                        3,
                        "primary",
                        "gpt-5.6-luna",
                        "gpt-5.6-luna-2026-10-01",
                        "chatcmpl-abc",
                        CallMode.STREAM,
                        2,
                        "stop",
                        0,
                        1,
                        120,
                        30,
                        150,
                        0L,
                        null,
                        99L,
                        5000L,
                        Boolean.TRUE,
                        true,
                        null,
                        200,
                        null,
                        STARTED,
                        1532L);

        new LoggingLlmCallRecorder().record(record);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("event", "llm_call");
        expected.put("sessionId", "11111111-1111-1111-1111-111111111111");
        expected.put("turnId", "22222222-2222-2222-2222-222222222222");
        expected.put("iteration", 3);
        expected.put("provider", "primary");
        expected.put("requestedModel", "gpt-5.6-luna");
        expected.put("responseModel", "gpt-5.6-luna-2026-10-01");
        expected.put("responseId", "chatcmpl-abc");
        expected.put("mode", "STREAM");
        expected.put("attempt", 2);
        expected.put("finishReason", "stop");
        expected.put("toolCallCount", 0);
        expected.put("imageCount", 1);
        expected.put("promptTokens", 120);
        expected.put("completionTokens", 30);
        expected.put("totalTokens", 150);
        expected.put("cacheReadTokens", 0L);
        expected.put("cacheWriteTokens", null);
        expected.put("requestsRemaining", 99L);
        expected.put("tokensRemaining", 5000L);
        expected.put("outputCapHonored", true);
        expected.put("success", true);
        expected.put("failureKind", null);
        expected.put("httpStatus", 200);
        expected.put("errorSummary", null);
        expected.put("startedAt", "2026-11-14T08:30:00.123Z");
        expected.put("durationMs", 1532L);

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValues(event)).containsExactlyEntriesOf(expected);
    }

    @Test
    @DisplayName("失败记录：无关联、无用量、无限流时输出 null，失败分类和摘要有值")
    void failureRecordLogsNullsAndFailureSummary() {
        LlmCallRecord record =
                new LlmCallRecord(
                        null,
                        null,
                        null,
                        "deepseek",
                        "deepseek-flash",
                        null,
                        null,
                        CallMode.CALL,
                        1,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        FailureKind.RATE_LIMITED,
                        429,
                        "RATE_LIMITED status=429 type=rate_limit_error",
                        STARTED,
                        0L);

        new LoggingLlmCallRecorder().record(record);

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValues(event))
                .hasSize(27)
                .contains(
                        entry("event", "llm_call"),
                        entry("sessionId", null),
                        entry("turnId", null),
                        entry("iteration", null),
                        entry("provider", "deepseek"),
                        entry("requestedModel", "deepseek-flash"),
                        entry("responseModel", null),
                        entry("responseId", null),
                        entry("mode", "CALL"),
                        entry("attempt", 1),
                        entry("finishReason", null),
                        entry("toolCallCount", null),
                        entry("imageCount", 0),
                        entry("promptTokens", null),
                        entry("completionTokens", null),
                        entry("totalTokens", null),
                        entry("cacheReadTokens", null),
                        entry("cacheWriteTokens", null),
                        entry("requestsRemaining", null),
                        entry("tokensRemaining", null),
                        entry("outputCapHonored", null),
                        entry("success", false),
                        entry("failureKind", "RATE_LIMITED"),
                        entry("httpStatus", 429),
                        entry("errorSummary", "RATE_LIMITED status=429 type=rate_limit_error"),
                        entry("startedAt", "2026-11-14T08:30:00.123Z"),
                        entry("durationMs", 0L));
    }

    private ILoggingEvent singleEvent() {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    /** 按出现顺序收集 key-value；重复的 key 直接判失败。 */
    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        assertThat(pairs).isNotNull();
        Map<String, Object> values = new LinkedHashMap<>();
        for (KeyValuePair pair : pairs) {
            assertThat(values).as("重复的 key：" + pair.key).doesNotContainKey(pair.key);
            values.put(pair.key, pair.value);
        }
        return values;
    }
}
