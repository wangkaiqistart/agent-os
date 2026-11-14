package com.agentos.provider.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider 连接配置；聊天模型和生成参数由 Profile 提供。 */
@ConfigurationProperties("agentos")
public class ProviderProperties {

    private String defaultProvider = "primary";
    private Map<String, Connection> providers = new LinkedHashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, Connection> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Connection> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    }

    /** 单个具名连接配置。 */
    public static class Connection {
        private String type;
        private String baseUrl;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(60);
        private Duration retryMinRemaining = Duration.ofSeconds(15);
        private Duration retryMaxWait;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getRetryMinRemaining() {
            return retryMinRemaining;
        }

        public void setRetryMinRemaining(Duration retryMinRemaining) {
            this.retryMinRemaining = retryMinRemaining;
        }

        public Duration getRetryMaxWait() {
            return retryMaxWait;
        }

        public void setRetryMaxWait(Duration retryMaxWait) {
            this.retryMaxWait = retryMaxWait;
        }
    }
}
