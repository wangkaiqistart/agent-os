package com.agentos.provider.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentos.core.provider.embedding.EmbeddingService;
import com.agentos.core.provider.embedding.EmbeddingStatus;
import com.agentos.provider.support.EmbeddingTestApps;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 真实向量化服务（阿里云百炼 text-embedding-v4）的冒烟测试。
 *
 * <p>只在 {@code mvn verify -Pllm} 且设置了 AGENTOS_EMBEDDING_API_KEY 时运行。地址、密钥、模型、维度都从环境变量
 * 取，不写进代码，也不打印。启动时的后台校验本身就是一次真实的向量化请求。
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "AGENTOS_EMBEDDING_API_KEY", matches = ".+")
class EmbeddingSmokeTest {

    @Test
    @DisplayName("后台校验完成后为 READY，向量化返回配置维度的向量（US5-6）")
    void validatesInBackgroundThenEmbeds() {
        int dimensions = Integer.parseInt(env("AGENTOS_EMBEDDING_DIMENSIONS", "1024"));
        // 聊天连接不是本测试的对象，只是默认连接必须存在；地址不会被访问。
        try (ConfigurableApplicationContext context =
                EmbeddingTestApps.start(
                        EmbeddingTestApps.Slow.class,
                        "agentos.default-provider=primary",
                        "agentos.providers.primary.type=openai",
                        "agentos.providers.primary.base-url=http://127.0.0.1:1/v1",
                        "agentos.providers.primary.api-key=unused",
                        "agentos.providers.primary.timeout=5s",
                        "agentos.providers.primary.retry-min-remaining=1s",
                        "agentos.embedding.base-url="
                                + env(
                                        "AGENTOS_EMBEDDING_BASE_URL",
                                        "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                        "agentos.embedding.api-key=" + System.getenv("AGENTOS_EMBEDDING_API_KEY"),
                        "agentos.embedding.model="
                                + env("AGENTOS_EMBEDDING_MODEL", "text-embedding-v4"),
                        "agentos.embedding.dimensions=" + dimensions,
                        "agentos.embedding.startup-check=async")) {
            EmbeddingService service = context.getBean(EmbeddingService.class);

            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() ->
                            assertThat(service.status().state())
                                    .isEqualTo(EmbeddingStatus.State.READY));
            float[] vector = service.embed("你好，AgentOS");

            assertThat(vector).hasSize(dimensions);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
