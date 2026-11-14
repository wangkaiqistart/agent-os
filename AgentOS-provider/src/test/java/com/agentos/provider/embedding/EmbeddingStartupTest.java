package com.agentos.provider.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.provider.embedding.EmbeddingStatus;
import com.agentos.provider.config.EmbeddingProperties;
import com.agentos.provider.support.EmbeddingBodies;
import com.agentos.provider.support.EmbeddingTestApps;
import com.agentos.provider.support.ProviderContextRunners;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 向量化的启动校验与状态转换：默认后台校验不阻塞启动，失败分类，退避恢复，同步校验。
 *
 * <p>覆盖 spec US5-4、US5-5、US5-7 与 SC-020、FR-032。需要真实的 {@code ApplicationReadyEvent}
 * 的用例用 {@link EmbeddingTestApps} 启动完整的 SpringApplication；只验证静态配置的用例用
 * {@code ApplicationContextRunner}（它不发布就绪事件）。
 */
class EmbeddingStartupTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** 给启动后挂日志 appender 留的时间，远小于校验超时。 */
    private static final Duration LOG_ATTACH_DELAY = Duration.ofMillis(300);

    private StubModelServer stub;

    private final Logger serviceLogger =
            (Logger) LoggerFactory.getLogger(DefaultEmbeddingService.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = StubModelServer.start();
        appender.start();
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        stub.close();
    }

    @Test
    @DisplayName("后台校验很慢：启动不等它，状态先是 CHECKING，随后变 READY（US5-7、SC-020）")
    void slowValidationDoesNotBlockStartup() {
        stub.enqueue(
                StubResponse.json(200, EmbeddingBodies.ping(4)).withDelay(Duration.ofSeconds(2)));

        try (ConfigurableApplicationContext context = start(EmbeddingTestApps.Slow.class)) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            // run() 已经返回，但校验还没回来：说明启动没有等它。
            assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.CHECKING);
            await().atMost(WAIT)
                    .untilAsserted(() ->
                            assertThat(service.status().state())
                                    .isEqualTo(EmbeddingStatus.State.READY));
        }
    }

    @Test
    @DisplayName("校验完成前发起的调用：等校验结果，然后成功（US5-7）")
    void callDuringCheckingWaitsForValidation() {
        stub.enqueue(
                        StubResponse.json(200, EmbeddingBodies.ping(4))
                                .withDelay(Duration.ofSeconds(1)))
                .enqueue(StubResponse.json(200, EmbeddingBodies.vectors(1, 4, 0)));

        try (ConfigurableApplicationContext context = start(EmbeddingTestApps.Slow.class)) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);
            assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.CHECKING);

            float[] vector = service.embed("x");

            assertThat(vector).hasSize(4);
            assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.READY);
            assertThat(stub.requestCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("校验迟迟不完成：调用最多等一个校验超时，然后报“向量化校验中”")
    void callTimesOutWhileStillChecking() {
        // ApplicationContextRunner 不发布就绪事件，校验永远不会开始，状态一直是 CHECKING。
        runner("agentos.embedding.timeout=1s")
                .run(context -> {
                    DefaultEmbeddingService service =
                            context.getBean(DefaultEmbeddingService.class);
                    assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.CHECKING);

                    long start = System.nanoTime();
                    assertThatThrownBy(() -> service.embed("x"))
                            .isInstanceOf(AgentOSException.class)
                            .hasMessage("向量化校验中");
                    Duration waited = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(waited).isBetween(Duration.ofMillis(900), Duration.ofSeconds(4));
                    assertThat(stub.requestCount()).isZero();
                });
    }

    @Test
    @DisplayName("维度不一致（后台校验）：启动不受影响，标记配置错误，ERROR 日志写明配置值和实际值，之后调用报错（US5-4）")
    void dimensionMismatchMarksMisconfigured() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(8)).withDelay(LOG_ATTACH_DELAY));

        try (ConfigurableApplicationContext context = start(EmbeddingTestApps.Fast.class)) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            await().atMost(WAIT)
                    .untilAsserted(() -> {
                        assertThat(service.status().state())
                                .isEqualTo(EmbeddingStatus.State.MISCONFIGURED);
                        assertThat(service.status().reason()).contains("4").contains("8");
                        assertThat(appender.list)
                                .anySatisfy(event -> {
                                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                                    assertThat(event.getFormattedMessage())
                                            .contains("4")
                                            .contains("8");
                                });
                    });
            assertThatThrownBy(() -> service.embed("x"))
                    .isInstanceOf(AgentOSException.class)
                    .hasMessageContaining("向量化配置错误");
            // 配置错误不会自动重试：等过几个退避周期，请求数不变。
            sleep(Duration.ofMillis(800));
            assertThat(stub.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "校验返回 {0}：不可用，原因“{2}”，打 WARN（US5-5）")
    @CsvSource({
        "401,UNAUTHORIZED,鉴权失败",
        "400,ARREARS,欠费",
        "400,BAD_REQUEST,其他",
        "503,BAD_REQUEST,其他"
    })
    void validationFailureIsClassifiedAndWarned(int status, String body, String reason) {
        stub.enqueue(StubResponse.json(status, body(body)).withDelay(LOG_ATTACH_DELAY));

        try (ConfigurableApplicationContext context = start(EmbeddingTestApps.Slow.class)) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            // 状态先改、日志后写，两者之间有时间差，所以一起等。
            await().atMost(WAIT)
                    .untilAsserted(() -> {
                        assertThat(service.status().state())
                                .isEqualTo(EmbeddingStatus.State.UNAVAILABLE);
                        assertThat(service.status().reason()).isEqualTo(reason);
                        assertThat(appender.list)
                                .anySatisfy(event -> {
                                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                                    assertThat(event.getFormattedMessage()).contains(reason);
                                });
                    });
            assertThatThrownBy(() -> service.embed("x"))
                    .isInstanceOf(AgentOSException.class)
                    .hasMessage("向量化不可用：" + reason);
        }
    }

    @Test
    @DisplayName("校验请求不可达：不可用，原因“不可达”（US5-5）")
    void unreachableValidationIsClassified() {
        stub.enqueue(StubResponse.hang());

        try (ConfigurableApplicationContext context =
                start(EmbeddingTestApps.Slow.class, "agentos.embedding.timeout=1s")) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            await().atMost(WAIT)
                    .untilAsserted(() -> assertThat(service.status().reason()).isEqualTo("不可达"));
            assertThat(service.status().state()).isEqualTo(EmbeddingStatus.State.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("欠费后服务恢复：后台重新校验，无需重启即变为 READY（US5-5）")
    void recoversAfterArrears() {
        stub.enqueue(StubResponse.json(400, EmbeddingBodies.ARREARS))
                .enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)));

        try (ConfigurableApplicationContext context = start(EmbeddingTestApps.Fast.class)) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            await().atMost(WAIT)
                    .untilAsserted(() ->
                            assertThat(service.status().state())
                                    .isEqualTo(EmbeddingStatus.State.READY));
            assertThat(stub.requestCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("不可达后服务恢复：后台重新校验，变为 READY")
    void recoversAfterUnreachable() {
        stub.enqueue(StubResponse.hang()).enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)));

        try (ConfigurableApplicationContext context =
                start(EmbeddingTestApps.Fast.class, "agentos.embedding.timeout=1s")) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            await().atMost(WAIT)
                    .untilAsserted(() ->
                            assertThat(service.status().state())
                                    .isEqualTo(EmbeddingStatus.State.READY));
            assertThat(stub.requestCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("鉴权失败不重新校验：key 来自环境变量，运行期间不会变（US5-5）")
    void doesNotRevalidateAfterAuthFailure() {
        // 排了第 2 个成功响应：如果被测代码错误地重试，状态会变成 READY、请求数变 2。
        stub.enqueue(StubResponse.json(401, EmbeddingBodies.UNAUTHORIZED))
                .enqueue(StubResponse.json(200, EmbeddingBodies.ping(4)));

        try (ConfigurableApplicationContext context = start(EmbeddingTestApps.Fast.class)) {
            DefaultEmbeddingService service = context.getBean(DefaultEmbeddingService.class);

            await().atMost(WAIT)
                    .untilAsserted(() ->
                            assertThat(service.status().state())
                                    .isEqualTo(EmbeddingStatus.State.UNAVAILABLE));
            // 退避只有 200 毫秒，等够多个周期。
            sleep(Duration.ofSeconds(1));

            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(service.status().reason()).isEqualTo("鉴权失败");
        }
    }

    @Test
    @DisplayName("上下文关闭后，后台重新校验停止")
    void revalidationStopsWhenContextCloses() {
        // 欠费、可重新校验的失败一直排下去，让重新校验循环持续运行。
        for (int i = 0; i < 40; i++) {
            stub.enqueue(StubResponse.json(400, EmbeddingBodies.ARREARS));
        }
        ConfigurableApplicationContext context = start(EmbeddingTestApps.Fast.class);
        EmbeddingStartupValidator validator = context.getBean(EmbeddingStartupValidator.class);
        await().atMost(WAIT).until(() -> stub.requestCount() >= 3);
        assertThat(validator.isRunning()).isTrue();

        context.close();

        assertThat(validator.isRunning()).isFalse();
        int afterClose = stub.requestCount();
        sleep(Duration.ofMillis(800));
        assertThat(stub.requestCount()).isEqualTo(afterClose);
    }

    @Test
    @DisplayName("同步校验维度不一致：拒绝启动，错误指出配置值和实际值（US5-4）")
    void syncMismatchRefusesToStart() {
        stub.enqueue(StubResponse.json(200, EmbeddingBodies.ping(8)));

        runner("agentos.embedding.startup-check=sync")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessage(context);
                    assertThat(message).contains("4").contains("8");
                });
    }

    @Test
    @DisplayName("同步校验时服务不可达：启动成功，状态为不可用")
    void syncUnreachableStillStarts() {
        stub.enqueue(StubResponse.json(503, EmbeddingBodies.BAD_REQUEST));

        runner("agentos.embedding.startup-check=sync")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EmbeddingStatus status =
                            context.getBean(DefaultEmbeddingService.class).status();
                    assertThat(status.state()).isEqualTo(EmbeddingStatus.State.UNAVAILABLE);
                });
    }

    @ParameterizedTest(name = "{0}：启动失败，错误里有字段名和取值")
    @ValueSource(
            strings = {
                "agentos.embedding.dimensions=0",
                "agentos.embedding.dimensions=-1",
                "agentos.embedding.max-batch-size=0",
                "agentos.embedding.startup-check=lazy",
                "agentos.embedding.base-url="
            })
    void invalidEmbeddingConfigurationFailsStartup(String property) {
        String key = property.substring(0, property.indexOf('='));
        String field = key.substring(key.lastIndexOf('.') + 1);
        String value = property.substring(property.indexOf('=') + 1);

        runner(property)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context)).contains(field).contains(value);
                });
    }

    @Test
    @DisplayName("校验请求自己的超时盖过模型的连接超时：不会等满连接超时")
    void probeTimeoutOverridesConnectionTimeout() {
        stub.enqueue(StubResponse.hang());
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setBaseUrl(stub.baseUrl());
        properties.setApiKey("embed-key");
        properties.setModel("text-embedding-test");
        properties.setDimensions(4);
        // 模型本身的超时给 30 秒；校验用 1 秒，应当在 1 秒左右就判定不可达。
        properties.setTimeout(Duration.ofSeconds(30));
        EmbeddingProbe probe =
                new EmbeddingProbe(
                        DefaultEmbeddingService.buildModel(properties, ObservationRegistry.NOOP),
                        properties.getModel(),
                        properties.getDimensions(),
                        Duration.ofSeconds(1));

        long start = System.nanoTime();
        EmbeddingProbe.Result result = probe.probe();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.outcome()).isEqualTo(EmbeddingProbe.Outcome.UNREACHABLE);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    private static String body(String name) {
        return switch (name) {
            case "UNAUTHORIZED" -> EmbeddingBodies.UNAUTHORIZED;
            case "ARREARS" -> EmbeddingBodies.ARREARS;
            default -> EmbeddingBodies.BAD_REQUEST;
        };
    }

    private static String rootMessage(AssertableApplicationContext context) {
        Throwable root = context.getStartupFailure();
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("测试等待被中断", interrupted);
        }
    }

    /** 启动完整的 SpringApplication（会发布就绪事件），默认后台校验，维度 4。 */
    private ConfigurableApplicationContext start(Class<?> config, String... extra) {
        ConfigurableApplicationContext context =
                EmbeddingTestApps.start(
                        config, lastWins(ProviderContextRunners.concat(baseProperties(), extra)));
        // SpringApplication 启动时会重置日志系统，提前挂的 appender 会被摘掉，所以启动后再挂。
        // 用到日志断言的用例，校验响应都带了延迟，保证这里先挂上、日志后写出。
        ((Logger) LoggerFactory.getLogger(DefaultEmbeddingService.class)).addAppender(appender);
        return context;
    }

    /** 同名键只保留最后一个：命令行参数里的重复键会被拼成列表，Duration 之类解析不了。 */
    private static String[] lastWins(String[] properties) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
        for (String property : properties) {
            merged.put(property.substring(0, property.indexOf('=')), property);
        }
        return merged.values().toArray(String[]::new);
    }

    /** 只刷新上下文，不发布就绪事件。额外属性放最后，可以覆盖默认值。 */
    private ApplicationContextRunner runner(String... extra) {
        return ProviderContextRunners.base()
                .withPropertyValues(ProviderContextRunners.concat(baseProperties(), extra));
    }

    private String[] baseProperties() {
        return new String[] {
            "agentos.default-provider=primary",
            "agentos.providers.primary.type=openai",
            "agentos.providers.primary.base-url=" + stub.baseUrl(),
            "agentos.providers.primary.api-key=chat-key",
            "agentos.providers.primary.timeout=5s",
            "agentos.providers.primary.retry-min-remaining=1s",
            "agentos.embedding.base-url=" + stub.baseUrl(),
            "agentos.embedding.api-key=embed-key",
            "agentos.embedding.model=text-embedding-test",
            "agentos.embedding.dimensions=4",
            "agentos.embedding.max-batch-size=10",
            "agentos.embedding.timeout=5s",
            "agentos.embedding.startup-check=async"
        };
    }
}
