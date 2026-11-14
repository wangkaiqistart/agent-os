package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.provider.config.EmbeddingProperties;
import com.agentos.provider.config.ProviderProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 验证 application.yaml 中 Provider 与向量化配置的边界。
 *
 * <p>只解析 YAML 并用替身环境变量绑定，不启动 Spring 上下文、不读 .env、不连数据库或模型。
 * 系统属性和真实环境变量会被移除，避免本机变量影响断言。
 */
class ProviderConfigurationYamlTest {

    private static final String YAML = "application.yaml";

    @Test
    @DisplayName("primary 的地址和密钥缺省为空，交给启动校验报出连接名和变量名")
    void primaryConnectionDefaultsToEmptyWhenEnvMissing() throws IOException {
        Binder binder = binderWith(Map.of());

        ProviderProperties props = bindProviders(binder);
        ProviderProperties.Connection primary = props.getProviders().get("primary");

        // 环境变量缺失时占位符解析为空串，而不是解析失败或保留 ${...} 原文
        assertThat(primary.getBaseUrl()).isEmpty();
        assertThat(primary.getApiKey()).isEmpty();
        assertThat(primary.getTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(primary.getRetryMinRemaining()).isEqualTo(Duration.ofSeconds(15));
        assertThat(primary.getRetryMaxWait()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("替身环境变量能替换 primary 的地址和密钥")
    void primaryConnectionUsesEnvWhenPresent() throws IOException {
        Binder binder =
                binderWith(
                        Map.of(
                                "AGENTOS_LLM_BASE_URL", "http://stub.local/v1",
                                "AGENTOS_LLM_API_KEY", "stub-key"));

        ProviderProperties.Connection primary = bindProviders(binder).getProviders().get("primary");

        assertThat(primary.getBaseUrl()).isEqualTo("http://stub.local/v1");
        assertThat(primary.getApiKey()).isEqualTo("stub-key");
    }

    @Test
    @DisplayName("向量化缺省地址以 /v1 结尾，启动校验默认后台执行")
    void embeddingDefaults() throws IOException {
        EmbeddingProperties embedding =
                binderWith(Map.of()).bind("agentos.embedding", EmbeddingProperties.class).get();

        assertThat(embedding.getBaseUrl())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(embedding.getApiKey()).isEmpty();
        assertThat(embedding.getStartupCheck()).isEqualTo("async");
        // 显式写在 YAML 里，而不是只依赖 Java 字段默认值
        assertThat(loadYaml().getProperty("agentos.embedding.startup-check")).hasToString("async");
    }

    @Test
    @DisplayName("Spring AI 模型自动配置全部关闭，工具调用上限保留")
    void springAiAutoConfigurationStaysDisabled() throws IOException {
        PropertySource<?> yaml = loadYaml();

        for (String key :
                List.of(
                        "spring.ai.model.chat",
                        "spring.ai.model.embedding",
                        "spring.ai.model.image",
                        "spring.ai.model.audio.speech",
                        "spring.ai.model.audio.transcription",
                        "spring.ai.model.moderation")) {
            assertThat(yaml.getProperty(key)).as(key).hasToString("none");
        }
        assertThat(yaml.getProperty("spring.ai.retry.max-attempts")).hasToString("1");
        assertThat(yaml.getProperty("spring.ai.chat.client.tool-calling.enabled"))
                .hasToString("false");
        assertThat(yaml.getProperty("spring.ai.tools.limits.on-limit-exceeded"))
                .hasToString("RETURN_ERROR_RESPONSE");
        assertThat(yaml.getProperty("spring.ai.tools.limits.max-total-tool-calls"))
                .hasToString("30");
    }

    private static ProviderProperties bindProviders(Binder binder) {
        return binder.bind("agentos", ProviderProperties.class).get();
    }

    private static PropertySource<?> loadYaml() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(YAML, new ClassPathResource(YAML));
        return sources.getFirst();
    }

    /** 只含 YAML 和替身环境变量的环境，移除系统属性与真实环境变量。 */
    private static Binder binderWith(Map<String, Object> fakeEnv) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("fakeEnv", fakeEnv));
        sources.addLast(loadYaml());
        return Binder.get(env);
    }
}
