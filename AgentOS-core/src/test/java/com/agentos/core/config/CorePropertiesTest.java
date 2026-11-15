package com.agentos.core.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CorePropertiesTest {
    @Test
    void rejectsInvalidTurnAndAuditValues() {
        assertThatThrownBy(() ->
                        new TurnProperties(
                                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new AuditProperties(
                                3,
                                Duration.ofMillis(1),
                                Duration.ofMillis(1),
                                Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new AuditProperties(
                                1,
                                Duration.ofMillis(1),
                                Duration.ofMillis(1),
                                Duration.ofMillis(251)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
