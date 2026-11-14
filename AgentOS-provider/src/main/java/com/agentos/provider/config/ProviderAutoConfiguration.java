package com.agentos.provider.config;

import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.provider.chat.LoggingLlmCallRecorder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Provider 模块自动配置骨架。
 *
 * <p>US-1 后续任务会在此处完成配置校验、连接组装和 ProviderService 注册。
 * 本模块不注册 ChatModel 或 EmbeddingModel Bean，避免 Spring 容器扫描类型产生歧义。
 *
 * <p>Spring Boot 会通过 {@code META-INF/spring/AutoConfiguration.imports} 找到本类，
 * 所以使用方不需要手工 {@code @Import} 它。
 *
 * <p>只用 {@code @Bean} 显式注册，不做包扫描：自动配置里扫描包会把同包组件（含测试类）反复注册进容器。
 */
@AutoConfiguration
@EnableConfigurationProperties({ProviderProperties.class, EmbeddingProperties.class})
public class ProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LlmCallRecorder.class)
    LlmCallRecorder loggingLlmCallRecorder() {
        // 这是默认记录器。后续如果业务模块提供数据库记录器，条件注解会让这个 Bean 不再创建。
        return new LoggingLlmCallRecorder();
    }
}
