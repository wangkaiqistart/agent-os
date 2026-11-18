package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpToolPropertiesTest {
    @Test
    void defaultsAndBoundsAreApplied() {
        assertThat(new HttpToolProperties(List.of(), null, 0, 0, null, 10_000).maxRedirects())
                .isZero();
        assertThat(new HttpToolProperties(List.of(), null, 0, 0, null, 10_000).maxResultChars())
                .isEqualTo(10_000);
        assertThat(
                        new HttpToolProperties(List.of(), "ua", 3, 1, Duration.ofSeconds(1), 128)
                                .maxResultChars())
                .isEqualTo(128);
        assertThatThrownBy(() ->
                        new HttpToolProperties(
                                List.of(), "ua", 4, 1, Duration.ofSeconds(1), 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new HttpToolProperties(List.of(), "ua", 3, 1, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindingMissingRedirectLimitDefaultsToThree() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .run(ctx ->
                        assertThat(ctx.getBean(HttpToolProperties.class).maxRedirects())
                                .isEqualTo(3));
    }

    @Test
    void normalizesDomainsAndRejectsAllInvalidBoundaries() {
        var defaults =
                new HttpToolProperties(List.of("  example.com  ", " "), null, 3, 0, null, 10000);
        assertThat(defaults.allowedDomains()).containsExactly("example.com");
        assertThat(defaults.maxResponseBytes()).isEqualTo(1048576);
        assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(30));
        for (int bytes : List.of(-1, 1048577)) {
            assertThatThrownBy(() -> new HttpToolProperties(List.of(), null, 3, bytes, null, 10000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            assertThatThrownBy(() -> new HttpToolProperties(List.of(), null, 3, 0, timeout, 10000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            HttpToolProperties.class)
    static class Config {}
}
