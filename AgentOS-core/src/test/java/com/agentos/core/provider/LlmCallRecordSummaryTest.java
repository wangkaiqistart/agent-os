package com.agentos.core.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmCallRecordSummaryTest {
    private static LlmCallRecord record(boolean success, FailureKind kind, Integer http) {
        return new LlmCallRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                "primary",
                "gpt-x",
                success ? "gpt-x-0101" : null,
                null,
                CallMode.CALL,
                1,
                success ? "tool_calls" : null,
                success ? 1 : null,
                0,
                success ? 1200 : null,
                success ? 80 : null,
                success ? 1280 : null,
                null,
                null,
                null,
                null,
                null,
                success,
                kind,
                http,
                success ? null : "SECRET-LIKE-DETAIL",
                Instant.now(),
                4200);
    }

    @Test
    void successSummaryShowsModelDurationAndTokens() {
        assertThat(record(true, null, null).summary())
                .contains("provider=primary", "model=gpt-x-0101", "iteration=2")
                .contains("durationMs=4200", "finish=tool_calls", "toolCalls=1", "tokens=1200/80");
    }

    @Test
    void failureSummaryShowsKindAndStatusWithoutErrorSummary() {
        var summary = record(false, FailureKind.SERVER_ERROR, 504).summary();
        assertThat(summary).contains("failure=SERVER_ERROR", "http=504", "model=gpt-x");
        assertThat(summary).doesNotContain("SECRET-LIKE-DETAIL");
    }
}
