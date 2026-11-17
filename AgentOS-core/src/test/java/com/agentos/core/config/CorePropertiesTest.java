package com.agentos.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CorePropertiesTest {
    @Test
    void auditTimeoutSupportsRemoteDatabaseWithoutChangingCleanup() {
        assertThat(new AuditProperties(2, null, null, null).attemptTimeout())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(new AuditProperties(2, null, null, Duration.ofSeconds(5)).attemptTimeout())
                .isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> new AuditProperties(2, null, null, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TurnProperties(null, null, null).cleanup()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void ordinaryStorageTimeoutBindsIndependentlyFromCleanupAndAudit() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("agentos.turn.storage-timeout=3s")
                .run(context -> {
                    var turn = context.getBean(TurnProperties.class);
                    assertThat(turn.storageTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(turn.cleanup()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(new TurnProperties(null, null, null).storageTimeout())
                            .isEqualTo(Duration.ofSeconds(5));
                });
        assertThatThrownBy(() -> new TurnProperties(null, null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnProperties(null, null, null, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(TurnProperties.class)
    static class PropertiesConfig {}

    @Test
    void rejectsInvalidTurnAndAuditValues() {
        assertThatThrownBy(() ->
                        new TurnProperties(
                                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnProperties(null, null, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnProperties(null, null, Duration.ofMillis(500)))
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
                                Duration.ofMillis(5001)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
