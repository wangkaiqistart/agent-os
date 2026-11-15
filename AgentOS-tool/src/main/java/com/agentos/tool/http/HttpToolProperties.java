package com.agentos.tool.http;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agentos.tools.http")
public record HttpToolProperties(
        List<String> allowedDomains,
        String userAgent,
        int maxRedirects,
        int maxResponseBytes,
        Duration timeout) {
    public HttpToolProperties {
        allowedDomains =
                allowedDomains == null
                        ? List.of()
                        : allowedDomains.stream()
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
        userAgent = userAgent == null || userAgent.isBlank() ? "AgentOS/0.1" : userAgent;
        if (maxRedirects == 0) {
            maxRedirects = 3;
        }
        if (maxResponseBytes == 0) {
            maxResponseBytes = 1_048_576;
        }
        if (maxRedirects < 0 || maxRedirects > 3) {
            throw new IllegalArgumentException("max-redirects 必须在 0 到 3 之间");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("max-response-bytes 超出范围");
        }
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("HTTP timeout 必须为正数");
        }
    }
}
