package com.agentos.provider.config;

import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.chat.ChatModelFactory;
import com.agentos.provider.chat.DefaultProviderService;
import com.agentos.provider.chat.LoggingLlmCallRecorder;
import com.agentos.provider.chat.ProviderConnection;
import com.agentos.provider.chat.RequestValidator;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Provider 模块自动配置：校验连接配置，按名称建好连接，注册 {@link ProviderService}。
 *
 * <p>本模块不注册 ChatModel 或 EmbeddingModel Bean：模型对象只存在于不可变的连接映射里，
 * 避免有人靠扫描容器里的 ChatModel 区分 Provider。
 *
 * <p>Spring Boot 会通过 {@code META-INF/spring/AutoConfiguration.imports} 找到本类，
 * 所以使用方不需要手工 {@code @Import} 它。
 *
 * <p>只用 {@code @Bean} 显式注册，不做包扫描：自动配置里扫描包会把同包组件（含测试类）反复注册进容器。
 */
@AutoConfiguration
@EnableConfigurationProperties({ProviderProperties.class, EmbeddingProperties.class})
public class ProviderAutoConfiguration {

    /** 属性源里把所有来源合并成一个视图的特殊源，它返回的值已经解析过占位符。 */
    private static final String MERGED_VIEW = "configurationProperties";

    @Bean
    @ConditionalOnMissingBean(LlmCallRecorder.class)
    LlmCallRecorder loggingLlmCallRecorder() {
        // 这是默认记录器。后续如果业务模块提供数据库记录器，条件注解会让这个 Bean 不再创建。
        return new LoggingLlmCallRecorder();
    }

    @Bean
    @ConditionalOnMissingBean(ProviderService.class)
    ProviderService providerService(
            ProviderProperties properties,
            ConfigurableEnvironment environment,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            LlmCallRecorder recorder) {
        // 容器里没有观测注册表时用 NOOP，模型调用照常进行，只是不产生观测。
        ChatModelFactory factory =
                new ChatModelFactory(
                        toolCallingManager,
                        observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
        Map<String, ProviderConnection> connections =
                new ProviderConnectionAssembler(
                                properties, key -> rawProperty(environment, key), factory)
                        .assemble();
        return new DefaultProviderService(
                new RequestValidator(connections, properties.getDefaultProvider()), recorder);
    }

    /** 读取配置原文：此时 ${ENV_VAR} 还没有被解析，才能从中取出环境变量名。 */
    private static String rawProperty(ConfigurableEnvironment environment, String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (MERGED_VIEW.equals(source.getName())) {
                continue;
            }
            Object value = source.getProperty(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
