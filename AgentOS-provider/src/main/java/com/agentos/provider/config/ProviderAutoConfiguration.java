package com.agentos.provider.config;

import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.provider.chat.LoggingLlmCallRecorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Provider 模块自动配置骨架。
 *
 * <p>US-1 后续任务会在此处完成配置校验、连接组装和 ProviderService 注册。
 * 本模块不注册 ChatModel 或 EmbeddingModel Bean，避免 Spring 容器扫描类型产生歧义。
 */
@AutoConfiguration
@EnableConfigurationProperties({ProviderProperties.class, EmbeddingProperties.class})
@ComponentScan(basePackages = "com.agentos.provider")
public class ProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LlmCallRecorder.class)
    LlmCallRecorder loggingLlmCallRecorder() {
        return new LoggingLlmCallRecorder();
    }
}
