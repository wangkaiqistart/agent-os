package com.agentos.provider.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立 Embedding 服务配置。
 *
 * <p>Embedding 与聊天模型是两条不同链路，因此使用 {@code agentos.embedding} 单独配置。
 * {@code @ConfigurationProperties} 会把 YAML 中的 kebab-case 键绑定到 Java 的 camelCase 属性。
 */
@ConfigurationProperties("agentos.embedding")
public class EmbeddingProperties {
    /** 是否启用语义向量服务。 */
    private boolean enabled = true;

    /** OpenAI 兼容 Embedding 接口地址。 */
    private String baseUrl;

    /** 访问接口的密钥，实际值来自环境变量。 */
    private String apiKey;

    /** 使用的 Embedding 模型名称。 */
    private String model = "text-embedding-v4";

    /** 每个向量包含多少个浮点数。 */
    private int dimensions = 1024;

    /** 单次批量请求最多发送多少段文本。 */
    private int maxBatchSize = 10;

    /** 单次远程调用的超时时间。 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 启动检查策略，例如同步或异步。 */
    private String startupCheck = "async";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getStartupCheck() {
        return startupCheck;
    }

    public void setStartupCheck(String startupCheck) {
        this.startupCheck = startupCheck;
    }
}
