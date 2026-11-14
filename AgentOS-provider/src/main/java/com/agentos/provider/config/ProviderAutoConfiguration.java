package com.agentos.provider.config;

import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.provider.embedding.EmbeddingStatus;
import com.agentos.provider.chat.ChatModelFactory;
import com.agentos.provider.chat.DefaultProviderService;
import com.agentos.provider.chat.LoggingLlmCallRecorder;
import com.agentos.provider.chat.ProviderConnection;
import com.agentos.provider.chat.RequestValidator;
import com.agentos.provider.embedding.DefaultEmbeddingService;
import com.agentos.provider.embedding.EmbeddingBackoff;
import com.agentos.provider.embedding.EmbeddingStartupValidator;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
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

    private static final String ASYNC = "async";

    private static final String SYNC = "sync";

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

    @Bean
    @ConditionalOnMissingBean(EmbeddingBackoff.class)
    EmbeddingBackoff embeddingBackoff() {
        return EmbeddingBackoff.exponential();
    }

    /**
     * 全局唯一的向量化服务。不注册 EmbeddingModel Bean：禁用时一个模型都不创建（FR-030、FR-031）。
     *
     * <p>TODO(US-5)：配置校验迁入 ConfigLoader；US-1 临时由 Spring placeholder 解析环境变量，本类负责结构和必填校验。
     */
    @Bean
    DefaultEmbeddingService embeddingService(
            EmbeddingProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        validate(properties);
        DefaultEmbeddingService service =
                DefaultEmbeddingService.create(
                        properties,
                        observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
        if (SYNC.equalsIgnoreCase(properties.getStartupCheck()) && service.pending()) {
            // 同步校验：在启动阶段完成。维度不一致拒绝启动，其他失败只标记状态，启动继续。
            EmbeddingStatus result = service.validate();
            if (result.state() == EmbeddingStatus.State.MISCONFIGURED) {
                throw new IllegalStateException("向量化维度校验失败，拒绝启动：" + result.reason());
            }
        }
        return service;
    }

    /** 就绪后在后台校验、失败时退避重新校验；应用关闭时停止。 */
    @Bean
    EmbeddingStartupValidator embeddingStartupValidator(
            DefaultEmbeddingService service, EmbeddingBackoff backoff) {
        return new EmbeddingStartupValidator(service, backoff);
    }

    /** 按 contracts/configuration.md「启动校验」表检查向量化配置，问题一次性报出。 */
    private static void validate(EmbeddingProperties properties) {
        List<String> problems = new ArrayList<>();
        if (properties.getDimensions() <= 0) {
            problems.add("agentos.embedding.dimensions 必须是正数，当前是 " + properties.getDimensions());
        }
        if (properties.getMaxBatchSize() <= 0) {
            problems.add(
                    "agentos.embedding.max-batch-size 必须是正数，当前是 " + properties.getMaxBatchSize());
        }
        String check = properties.getStartupCheck();
        if (!ASYNC.equalsIgnoreCase(check) && !SYNC.equalsIgnoreCase(check)) {
            problems.add("agentos.embedding.startup-check 只能是 async 或 sync，当前是 " + check);
        }
        boolean active =
                properties.isEnabled()
                        && properties.getApiKey() != null
                        && !properties.getApiKey().isBlank();
        if (active) {
            // 地址为空时 SDK 会退回 OpenAI 官方地址，把百炼的 key 发到别处，必须在启动时拦住。
            if (isBlank(properties.getBaseUrl())) {
                problems.add("agentos.embedding.base-url 不能为空，当前是 " + properties.getBaseUrl());
            }
            if (isBlank(properties.getModel())) {
                problems.add("agentos.embedding.model 不能为空，当前是 " + properties.getModel());
            }
            if (properties.getTimeout() == null
                    || properties.getTimeout().isZero()
                    || properties.getTimeout().isNegative()) {
                problems.add("agentos.embedding.timeout 必须是正数，当前是 " + properties.getTimeout());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("向量化配置有误：" + String.join("；", problems));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
