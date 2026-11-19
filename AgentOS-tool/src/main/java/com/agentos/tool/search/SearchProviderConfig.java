package com.agentos.tool.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Brave部署凭证仅通过配置注入，不开放模型参数。 */
@ConfigurationProperties("agentos.tools.search")
public record SearchProviderConfig(
        @DefaultValue("brave") String provider,
        @DefaultValue("https://api.search.brave.com/res/v1/web/search") String endpoint,
        @DefaultValue("") String apiKey,
        @DefaultValue("1000") int maxQueryChars,
        @DefaultValue("10") int maxResults,
        @DefaultValue("1048576") int maxResponseBytes,
        @DefaultValue("30s") Duration timeout) {
    public SearchProviderConfig {
        if (!"brave".equals(provider)
                || maxQueryChars < 1
                || maxQueryChars > 1000
                || maxResults < 1
                || maxResults > 10
                || maxResponseBytes < 1
                || maxResponseBytes > 1048576
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("搜索配置限值非法");
        }
        if (apiKey != null && (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("搜索凭证配置非法");
        }
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && endpoint != null && !endpoint.isBlank();
    }

    @Override
    public String toString() {
        return "SearchProviderConfig[redacted]";
    }
}
