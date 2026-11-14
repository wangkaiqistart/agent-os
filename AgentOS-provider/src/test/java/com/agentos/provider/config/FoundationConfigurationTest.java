package com.agentos.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.provider.chat.LoggingLlmCallRecorder;
import com.agentos.provider.support.TestSpringAiConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;

/**
 * ProviderAutoConfiguration 骨架：默认 Recorder、可替换 Recorder、配置绑定、不做包扫描。
 *
 * <p>只装配配置和 Recorder，不构建模型、不发请求，也不做 Phase3 的连接校验。
 */
class FoundationConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
                    .withUserConfiguration(TestSpringAiConfiguration.class);

    @Test
    @DisplayName("没有自定义 Recorder 时注册 LoggingLlmCallRecorder")
    void registersLoggingRecorderByDefault() {
        runner.run(context ->
                assertThat(context)
                        .hasSingleBean(LlmCallRecorder.class)
                        .getBean(LlmCallRecorder.class)
                        .isInstanceOf(LoggingLlmCallRecorder.class));
    }

    @Test
    @DisplayName("用户提供的 Recorder 替换默认实现")
    void userRecorderReplacesDefault() {
        LlmCallRecorder custom = record -> {};
        runner.withBean(LlmCallRecorder.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmCallRecorder.class);
                    assertThat(context.getBean(LlmCallRecorder.class)).isSameAs(custom);
                    assertThat(context).doesNotHaveBean(LoggingLlmCallRecorder.class);
                });
    }

    @Test
    @DisplayName("自动配置只注册显式 Bean，不扫描 com.agentos.provider 包")
    void doesNotComponentScanProviderPackage() {
        runner.run(context ->
                assertThat(context)
                        .doesNotHaveBean(ScanProbe.class)
                        .doesNotHaveBean(ChatModel.class)
                        .doesNotHaveBean(EmbeddingModel.class));
    }

    @Test
    @DisplayName("连接配置按 agentos.providers.<name> 绑定，未配 retry-max-wait 时等于 timeout")
    void bindsConnectionsAndDefaultsRetryMaxWaitToTimeout() {
        runner.withPropertyValues(
                        "agentos.default-provider=deepseek",
                        "agentos.providers.primary.type=openai",
                        "agentos.providers.primary.base-url=http://stub.local/v1",
                        "agentos.providers.primary.api-key=stub-key",
                        "agentos.providers.primary.timeout=45s",
                        "agentos.providers.deepseek.type=deepseek",
                        "agentos.providers.deepseek.timeout=45s",
                        "agentos.providers.deepseek.retry-min-remaining=5s",
                        "agentos.providers.deepseek.retry-max-wait=90s",
                        "agentos.embedding.dimensions=768",
                        "agentos.embedding.startup-check=sync")
                .run(context -> {
                    ProviderProperties props = context.getBean(ProviderProperties.class);
                    assertThat(props.getDefaultProvider()).isEqualTo("deepseek");
                    assertThat(props.getProviders()).containsOnlyKeys("primary", "deepseek");

                    ProviderProperties.Connection primary = props.getProviders().get("primary");
                    assertThat(primary.getType()).isEqualTo("openai");
                    assertThat(primary.getBaseUrl()).isEqualTo("http://stub.local/v1");
                    assertThat(primary.getApiKey()).isEqualTo("stub-key");
                    assertThat(primary.getTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(primary.getRetryMinRemaining()).isEqualTo(Duration.ofSeconds(15));
                    // 未配置时跟随本连接的 timeout，而不是 null 或固定值
                    assertThat(primary.getRetryMaxWait()).isEqualTo(Duration.ofSeconds(45));

                    ProviderProperties.Connection deepseek = props.getProviders().get("deepseek");
                    assertThat(deepseek.getRetryMinRemaining()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(deepseek.getRetryMaxWait()).isEqualTo(Duration.ofSeconds(90));

                    EmbeddingProperties embedding = context.getBean(EmbeddingProperties.class);
                    assertThat(embedding.getDimensions()).isEqualTo(768);
                    assertThat(embedding.getStartupCheck()).isEqualTo("sync");
                });
    }

    /** 放在 com.agentos.provider 包下的组件；只有包扫描才会把它注册进容器。 */
    @Component
    static class ScanProbe {}
}
