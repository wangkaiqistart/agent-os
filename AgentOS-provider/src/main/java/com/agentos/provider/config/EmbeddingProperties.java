package com.agentos.provider.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立 Embedding 服务配置。 */
@ConfigurationProperties("agentos.embedding")
public class EmbeddingProperties {
    private boolean enabled = true;
    private String baseUrl;
    private String apiKey;
    private String model = "text-embedding-v4";
    private int dimensions = 1024;
    private int maxBatchSize = 10;
    private Duration timeout = Duration.ofSeconds(30);
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
