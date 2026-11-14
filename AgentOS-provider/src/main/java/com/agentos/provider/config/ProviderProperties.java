package com.agentos.provider.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider 连接配置；聊天模型和生成参数由 Profile 提供。
 *
 * <p>配置前缀是 {@code agentos}，所以它既能读取默认 Provider，也能读取多个具名连接：
 * {@code agentos.providers.primary}、{@code agentos.providers.deepseek}。
 */
@ConfigurationProperties("agentos")
public class ProviderProperties {

    /** Profile 没指定 Provider 时使用的连接名。 */
    private String defaultProvider = "primary";

    /** 连接名到连接配置的映射；Map 的 key 就是 Provider 名称。 */
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
        // 做一次复制，避免外部继续持有并修改 Spring 已绑定的 Map。
        this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    }

    /**
     * 单个具名连接配置。
     *
     * <p>它描述远程服务的连接参数，不描述某一次对话的消息和生成选项。
     */
    public static class Connection {
        /** 连接类型，例如 openai 或 deepseek。 */
        private String type;

        /** OpenAI 兼容接口的基础 URL。 */
        private String baseUrl;

        /** 访问密钥。只从环境变量注入，不应写入提交的 YAML。 */
        private String apiKey;

        /** 单次模型请求超时时间。 */
        private Duration timeout = Duration.ofSeconds(60);

        /** 只剩这么多时间时不再发起第二次重试。 */
        private Duration retryMinRemaining = Duration.ofSeconds(15);

        /** 重试最多等待多久；未配置时等于 {@link #timeout}。 */
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
            // 未配置时等于本连接当前的 timeout；在读取时计算，timeout 后绑定也能跟上。
            return retryMaxWait != null ? retryMaxWait : timeout;
        }

        public void setRetryMaxWait(Duration retryMaxWait) {
            this.retryMaxWait = retryMaxWait;
        }
    }
}
