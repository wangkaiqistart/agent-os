package com.agentos.provider.support;

import com.agentos.provider.config.ProviderAutoConfiguration;
import com.agentos.provider.embedding.EmbeddingBackoff;
import java.time.Duration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 用真实的 {@code SpringApplication} 启动 Provider 模块，让 {@code ApplicationReadyEvent} 真的被发布。
 *
 * <p>{@code ApplicationContextRunner} 只刷新上下文，不会发布就绪事件，没法测"后台校验不阻塞启动"。
 * 这里不开 Web 服务器、不读 application.yaml，只装配 Provider 自动配置和测试用的官方 Bean。
 */
public final class EmbeddingTestApps {

    private EmbeddingTestApps() {}

    /** 默认退避（1 分钟起）：测试窗口内不会触发后台重新校验，状态保持校验结果不变。 */
    @Configuration(proxyBeanMethods = false)
    @Import(TestSpringAiConfiguration.class)
    @ImportAutoConfiguration(ProviderAutoConfiguration.class)
    public static class Slow {}

    /** 退避缩短到 200 毫秒：用来观察后台重新校验和恢复。 */
    @Configuration(proxyBeanMethods = false)
    @Import(TestSpringAiConfiguration.class)
    @ImportAutoConfiguration(ProviderAutoConfiguration.class)
    public static class Fast {

        @Bean
        EmbeddingBackoff fastBackoff() {
            return failures -> Duration.ofMillis(200);
        }
    }

    /**
     * 启动应用并返回上下文。
     *
     * @param config {@link Slow} 或 {@link Fast}
     * @param properties {@code key=value} 形式的属性，后面的覆盖前面的
     */
    public static ConfigurableApplicationContext start(Class<?> config, String... properties) {
        // 用命令行参数而不是 properties()：后者优先级最低，shell 里导出的真实 AGENTOS_EMBEDDING_*
        // 环境变量会盖过它，测试就会打到真实服务。
        String[] arguments = new String[properties.length];
        for (int i = 0; i < properties.length; i++) {
            arguments[i] = "--" + properties[i];
        }
        return new SpringApplicationBuilder(config)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(arguments);
    }
}
