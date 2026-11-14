package com.agentos.provider.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.embedding.EmbeddingStatus;
import com.agentos.provider.support.EmbeddingBodies;
import com.agentos.provider.support.ProviderContextRunners;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 向量化服务：分批、顺序、维度检查、重试、未启用、日志。
 *
 * <p>覆盖 spec US5-1、US5-2、US5-3、US5-8 与 SC-012 ~ SC-014、FR-032 ~ FR-035。
 * 用同步校验（startup-check=sync），校验请求在创建 Bean 时发出，所以每个用例的第一个排队响应
 * 都是校验用的那条 "ping"。后台校验、状态转换另见 {@link EmbeddingStartupTest}。
 */
class EmbeddingServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StubModelServer stub;

    private final Logger serviceLogger =
            (Logger) LoggerFactory.getLogger(DefaultEmbeddingService.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = StubModelServer.start();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        stub.close();
    }

    @Test
    @DisplayName("向量化 1 段文本：返回 1 个 1024 维向量，路径 /v1/embeddings，请求显式带 dimensions（US5-1）")
    void embedsSingleTextWithExplicitDimensions() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(1024)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(1, 1024, 0)));

        runner(1024)
                .run(context -> {
                    DefaultEmbeddingService service =
                            context.getBean(DefaultEmbeddingService.class);

                    float[] vector = service.embed("你好");

                    assertThat(vector).hasSize(1024);
                    // 第 1 个请求是校验，第 2 个才是这次调用。
                    assertThat(stub.requestCount()).isEqualTo(2);
                    StubModelServer.StubRequest sent = stub.requests().get(1);
                    assertThat(sent.path()).isEqualTo("/v1/embeddings");
                    JsonNode body = JSON.readTree(sent.body());
                    assertThat(body.path("model").asString()).isEqualTo("text-embedding-test");
                    assertThat(body.path("dimensions").asInt()).isEqualTo(1024);
                    assertThat(body.path("input").size()).isEqualTo(1);
                    assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.READY);
                    assertThat(service.dimensions()).isEqualTo(1024);
                });
    }

    @Test
    @DisplayName("11 条、单次上限 10：分 2 次请求，返回 11 个向量，顺序与输入一致（US5-2、SC-013）")
    void splitsIntoBatchesAndKeepsOrder() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(10, 4, 0)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(1, 4, 10)));
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            texts.add("文本" + i);
        }

        runner(4)
                .run(context -> {
                    List<float[]> vectors =
                            context.getBean(DefaultEmbeddingService.class).embed(texts);

                    assertThat(vectors).hasSize(11);
                    for (int i = 0; i < 11; i++) {
                        assertThat(vectors.get(i)[0]).isEqualTo((float) i);
                    }
                    // 校验 1 次 + 分批 2 次。
                    assertThat(stub.requestCount()).isEqualTo(3);
                    assertThat(JSON.readTree(stub.requests().get(1).body()).path("input").size())
                            .isEqualTo(10);
                    assertThat(JSON.readTree(stub.requests().get(2).body()).path("input").size())
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("提交空列表：直接返回空结果，不发请求")
    void emptyListSendsNothing() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)));

        runner(4)
                .run(context -> {
                    List<float[]> vectors =
                            context.getBean(DefaultEmbeddingService.class).embed(List.of());

                    assertThat(vectors).isEmpty();
                    // 只有启动时的校验请求。
                    assertThat(stub.requestCount()).isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}：不创建向量化模型，调用得到“向量化未启用”（US5-3、SC-014）")
    @ValueSource(strings = {"agentos.embedding.enabled=false", "agentos.embedding.api-key="})
    void disabledCreatesNoModelAndFailsClearly(String override) {
        runner(4, override)
                .run(context -> {
                    DefaultEmbeddingService service =
                            context.getBean(DefaultEmbeddingService.class);

                    assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.DISABLED);
                    assertThat(service.hasModel()).isFalse();
                    assertThatThrownBy(() -> service.embed("x"))
                            .isInstanceOf(AgentOSException.class)
                            .hasMessage("向量化未启用")
                            .satisfies(error ->
                                    assertThat(((AgentOSException) error).getErrorCode())
                                            .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
                    assertThat(stub.requestCount()).isZero();
                });
    }

    @Test
    @DisplayName("校验通过后服务端换了模型、维度变了：本次调用失败、不返回任何向量、标记配置错误（US5-8、SC-012）")
    void dimensionDriftAfterValidationFailsAndMarksMisconfigured() {
        // 第 2 批的向量是 8 维，配置是 4 维；第 1 批本身是对的，但整次调用不能只交出一半。
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(10, 4, 0)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(1, 8, 10)));
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            texts.add("文本" + i);
        }

        runner(4)
                .run(context -> {
                    DefaultEmbeddingService service =
                            context.getBean(DefaultEmbeddingService.class);

                    assertThatThrownBy(() -> service.embed(texts))
                            .isInstanceOf(AgentOSException.class)
                            .hasMessageContaining("向量化配置错误")
                            .hasMessageContaining("4")
                            .hasMessageContaining("8");

                    assertThat(service.status().state())
                            .isEqualTo(EmbeddingStatus.State.MISCONFIGURED);
                    int before = stub.requestCount();
                    // 标记之后的调用直接报错，不再发请求。
                    assertThatThrownBy(() -> service.embed("x")).hasMessageContaining("向量化配置错误");
                    assertThat(stub.requestCount()).isEqualTo(before);
                });
    }

    @Test
    @DisplayName("503 重试 1 次后成功：共发 2 次调用请求（FR-034）")
    void retriesTransientFailureOnce() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)))
                .enqueue(StubResponse.json(503, EmbeddingBodies.BAD_REQUEST))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(1, 4, 0)));

        runner(4)
                .run(context -> {
                    long start = System.nanoTime();
                    float[] vector = context.getBean(DefaultEmbeddingService.class).embed("x");
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(vector).hasSize(4);
                    // 校验 1 次 + 失败 1 次 + 重试 1 次；无 Retry-After 时等约 500 毫秒。
                    assertThat(stub.requestCount()).isEqualTo(3);
                    assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(450));
                });
    }

    @Test
    @DisplayName("401 不重试：只发 1 次调用请求，错误里没有 key 和响应原文（FR-034）")
    void doesNotRetryAuthFailure() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)))
                .enqueue(StubResponse.json(401, EmbeddingBodies.UNAUTHORIZED));

        runner(4)
                .run(context -> {
                    DefaultEmbeddingService service =
                            context.getBean(DefaultEmbeddingService.class);

                    assertThatThrownBy(() -> service.embed("x"))
                            .isInstanceOf(AgentOSException.class)
                            .hasMessageNotContaining("embed-key")
                            .hasMessageNotContaining("bad key")
                            .satisfies(error ->
                                    assertThat(((AgentOSException) error).getErrorCode())
                                            .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));

                    assertThat(stub.requestCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("embedding_call 日志：有模型、条数、请求次数、token、成功与否、耗时，没有原文、向量值和 key（FR-035）")
    void logsCallWithoutTextVectorsOrKey() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(2, 4, 0, 7777.5f)));

        runner(4)
                .run(context -> {
                    context.getBean(DefaultEmbeddingService.class)
                            .embed(List.of("SECRET-EMBED-TEXT-A", "SECRET-EMBED-TEXT-B"));

                    ILoggingEvent event = embeddingCallEvent();
                    assertThat(value(event, "model")).isEqualTo("text-embedding-test");
                    assertThat(value(event, "textCount")).isEqualTo(2);
                    assertThat(value(event, "requestCount")).isEqualTo(1);
                    assertThat(value(event, "totalTokens")).isEqualTo(6);
                    assertThat(value(event, "success")).isEqualTo(true);
                    assertThat(value(event, "durationMs")).isNotNull();
                    assertThat(allLogText())
                            .doesNotContain("SECRET-EMBED-TEXT")
                            .doesNotContain("7777")
                            .doesNotContain("embed-key");
                });
    }

    @Test
    @DisplayName("失败的 embedding_call 日志：success=false，带脱敏摘要，同样不含原文和 key")
    void failedCallIsLoggedWithSafeSummary() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)))
                .enqueue(StubResponse.json(401, EmbeddingBodies.UNAUTHORIZED));

        runner(4)
                .run(context -> {
                    assertThatThrownBy(() ->
                                    context.getBean(DefaultEmbeddingService.class)
                                            .embed("SECRET-EMBED-TEXT-C"))
                            .isInstanceOf(AgentOSException.class);

                    ILoggingEvent event = embeddingCallEvent();
                    assertThat(value(event, "success")).isEqualTo(false);
                    assertThat(String.valueOf(value(event, "errorSummary")))
                            .contains("AUTH")
                            .doesNotContain("bad key");
                    assertThat(allLogText())
                            .doesNotContain("SECRET-EMBED-TEXT")
                            .doesNotContain("embed-key");
                });
    }

    private ILoggingEvent embeddingCallEvent() {
        return appender.list.stream()
                .filter(event -> "embedding_call".equals(value(event, "event")))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("没有找到 embedding_call 日志"));
    }

    private static Object value(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) {
            return null;
        }
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getKeyValuePairs() != null) {
                event.getKeyValuePairs()
                        .forEach(pair ->
                                text.append(pair.key).append('=').append(pair.value).append('\n'));
            }
        }
        return text.toString();
    }

    /** 同步校验：校验请求在创建 Bean 时发出。额外属性放最后，可以覆盖前面的默认值。 */
    private ApplicationContextRunner runner(int dimensions, String... extra) {
        return ProviderContextRunners.base()
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                ProviderContextRunners.connection(
                                        "primary", "openai", stub.baseUrl(), "chat-key"),
                                new String[] {
                                    "agentos.embedding.base-url=" + stub.baseUrl(),
                                    "agentos.embedding.api-key=embed-key",
                                    "agentos.embedding.model=text-embedding-test",
                                    "agentos.embedding.dimensions=" + dimensions,
                                    "agentos.embedding.max-batch-size=10",
                                    "agentos.embedding.timeout=5s",
                                    "agentos.embedding.startup-check=sync"
                                },
                                extra));
    }
}
