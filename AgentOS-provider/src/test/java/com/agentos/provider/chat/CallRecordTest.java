package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ChatBodies;
import com.agentos.provider.support.ProviderContextRunners;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 调用记录：每次实际请求一条，空值与 0 不混用，关联标识原样带上。
 *
 * <p>覆盖 spec US3-1、US3-2、US3-3、US3-5 与 SC-005、SC-006、SC-016、FR-026。脱敏另见 {@link RedactionTest}。
 */
class CallRecordTest {

    /** 没有 usage 字段的响应。 */
    private static final String NO_USAGE =
            """
            {"id":"chatcmpl-nu","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"pong"}}]}
            """;

    /** usage 里带缓存命中数。 */
    private static final String CACHED_USAGE =
            """
            {"id":"chatcmpl-c","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"pong"}}],
             "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,
               "prompt_tokens_details":{"cached_tokens":64}}}
            """;

    /** 服务端明确返回 0 个缓存命中。 */
    private static final String ZERO_CACHED_USAGE =
            """
            {"id":"chatcmpl-z","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"pong"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,
               "prompt_tokens_details":{"cached_tokens":0}}}
            """;

    /** 输出 token 数为 50，用来对比输出上限。 */
    private static final String LONG_COMPLETION =
            """
            {"id":"chatcmpl-l","object":"chat.completion","created":1,"model":"actual-model",
             "choices":[{"index":0,"finish_reason":"length",
               "message":{"role":"assistant","content":"pong"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":50,"total_tokens":60}}
            """;

    private StubModelServer stub;

    private final List<LlmCallRecord> recorded = new CopyOnWriteArrayList<>();

    private final Logger serviceLogger =
            (Logger) LoggerFactory.getLogger(DefaultProviderService.class);

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
    @DisplayName("429 后成功：2 条记录，第 1 条失败带脱敏错误，第 2 条成功带用量（US3-1、SC-005）")
    void retryProducesTwoRecords() {
        stub.enqueue(
                        StubResponse.json(
                                        429,
                                        "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit\","
                                                + "\"code\":\"rate_limited\"}}")
                                .withHeader("Retry-After", "1"))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderResult result = context.getBean(ProviderService.class).call(request(profile()));

            assertThat(recorded).hasSize(2).isEqualTo(result.calls());
            assertThat(recorded).hasSize(stub.requestCount());
            LlmCallRecord failed = recorded.get(0);
            assertThat(failed.success()).isFalse();
            assertThat(failed.attempt()).isEqualTo(1);
            assertThat(failed.failureKind()).isEqualTo(FailureKind.RATE_LIMITED);
            assertThat(failed.httpStatus()).isEqualTo(429);
            assertThat(failed.errorSummary())
                    .contains("RATE_LIMITED", "429")
                    .doesNotContain("slow down");
            assertThat(failed.promptTokens()).isNull();
            LlmCallRecord succeeded = recorded.get(1);
            assertThat(succeeded.success()).isTrue();
            assertThat(succeeded.attempt()).isEqualTo(2);
            assertThat(succeeded.promptTokens()).isEqualTo(3);
            assertThat(succeeded.failureKind()).isNull();
            assertThat(succeeded.errorSummary()).isNull();
        });
    }

    @Test
    @DisplayName("成功记录的基础字段完整（provider、模型、响应 ID、方式、结束原因、耗时）")
    void successRecordHasAllBasicFields() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.provider()).isEqualTo("primary");
            assertThat(record.requestedModel()).isEqualTo("requested-model");
            assertThat(record.responseModel()).isEqualTo("actual-model");
            assertThat(record.responseId()).isEqualTo("chatcmpl-1");
            assertThat(record.mode()).isEqualTo(CallMode.CALL);
            assertThat(record.attempt()).isEqualTo(1);
            assertThat(record.finishReason()).isEqualToIgnoringCase("stop");
            assertThat(record.toolCallCount()).isZero();
            assertThat(record.promptTokens()).isEqualTo(3);
            assertThat(record.completionTokens()).isEqualTo(1);
            assertThat(record.totalTokens()).isEqualTo(4);
            assertThat(record.startedAt()).isNotNull();
            assertThat(record.durationMs()).isNotNegative();
        });
    }

    @Test
    @DisplayName("响应没有 usage：三项 token 为 null，不是 0（US3-2、SC-006）")
    void missingUsageIsNullNotZero() {
        stub.enqueue(StubResponse.json(200, NO_USAGE));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.promptTokens()).isNull();
            assertThat(record.completionTokens()).isNull();
            assertThat(record.totalTokens()).isNull();
        });
    }

    @Test
    @DisplayName("没有缓存字段、没有限流头：缓存 token 与限流余量为 null（US3-2、SC-006）")
    void missingCacheAndRateLimitAreNull() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.cacheReadTokens()).isNull();
            assertThat(record.cacheWriteTokens()).isNull();
            assertThat(record.requestsRemaining()).isNull();
            assertThat(record.tokensRemaining()).isNull();
        });
    }

    @Test
    @DisplayName("带 cached_tokens：缓存读取等于该值，缓存写入为 null（US3-3）")
    void cachedTokensAreRecorded() {
        stub.enqueue(StubResponse.json(200, CACHED_USAGE));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.cacheReadTokens()).isEqualTo(64L);
            assertThat(record.cacheWriteTokens()).isNull();
        });
    }

    @Test
    @DisplayName("服务端明确返回 cached_tokens=0：记为 0，不记为 null（FR-023）")
    void explicitZeroCacheIsRecordedAsZero() {
        stub.enqueue(StubResponse.json(200, ZERO_CACHED_USAGE));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            assertThat(recorded.getFirst().cacheReadTokens()).isZero();
        });
    }

    @Test
    @DisplayName("带 x-ratelimit-remaining-* 头：限流余量有值")
    void rateLimitHeadersAreRecorded() {
        stub.enqueue(
                StubResponse.json(200, ChatBodies.OK)
                        .withHeader("x-ratelimit-remaining-requests", "99")
                        .withHeader("x-ratelimit-remaining-tokens", "12345"));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.requestsRemaining()).isEqualTo(99L);
            assertThat(record.tokensRemaining()).isEqualTo(12345L);
        });
    }

    @Test
    @DisplayName("只带一个限流头：另一项为 null，不记为 0")
    void partialRateLimitHeadersLeaveTheOtherNull() {
        stub.enqueue(
                StubResponse.json(200, ChatBodies.OK)
                        .withHeader("x-ratelimit-remaining-requests", "7"));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.requestsRemaining()).isEqualTo(7L);
            assertThat(record.tokensRemaining()).isNull();
        });
    }

    @Test
    @DisplayName("传入 CallCorrelation：原样带上；不传：为 null（US3-5）")
    void correlationIsCarriedThrough() {
        UUID session = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        stub.enqueue(StubResponse.json(200, ChatBodies.OK))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            service.call(
                    new ProviderRequest(
                            profile(),
                            List.of(new UserMessage("ping")),
                            null,
                            null,
                            null,
                            new CallCorrelation(session, turn, 3)));
            service.call(request(profile()));

            LlmCallRecord with = recorded.get(0);
            assertThat(with.sessionId()).isEqualTo(session);
            assertThat(with.turnId()).isEqualTo(turn);
            assertThat(with.iteration()).isEqualTo(3);
            LlmCallRecord without = recorded.get(1);
            assertThat(without.sessionId()).isNull();
            assertThat(without.turnId()).isNull();
            assertThat(without.iteration()).isNull();
        });
    }

    @Test
    @DisplayName(
            "输出上限：completionTokens 超过上限 → false 且 ProviderService 打 WARN；未超过 → true；没设上限 → null（SC-016）")
    void outputCapHonoredIsComputedAndWarned() {
        stub.enqueue(StubResponse.json(200, LONG_COMPLETION))
                .enqueue(StubResponse.json(200, ChatBodies.OK))
                .enqueue(StubResponse.json(200, LONG_COMPLETION));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            service.call(request(new Profile("primary", "m", null, 10, null)));
            assertThat(recorded.get(0).outputCapHonored()).isFalse();
            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).doesNotContain("pong");
                    });

            appender.list.clear();
            service.call(request(new Profile("primary", "m", null, 10, null)));
            assertThat(recorded.get(1).outputCapHonored()).isTrue();
            assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);

            // Profile 没设上限：即使输出很长也没有"是否遵守"可言。
            service.call(request(profile()));
            assertThat(recorded.get(2).outputCapHonored()).isNull();
        });
    }

    @Test
    @DisplayName("记录输出抛异常：异常原样抛出，不重发请求（FR-025）")
    void recorderFailureIsNotSwallowedAndDoesNotResend() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK))
                .enqueue(StubResponse.json(200, ChatBodies.OK));
        LlmCallRecorder broken =
                record -> {
                    throw new IllegalStateException("recorder down");
                };

        ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> broken)
                .withPropertyValues(runnerProperties())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request(profile())))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("recorder down");

                    assertThat(stub.requestCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("容器的 ObservationRegistry 收到了模型调用观测（FR-026）")
    void observationRegistryReceivesModelCall() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile()));

            TestObservationRegistryAssert.assertThat(context.getBean(TestObservationRegistry.class))
                    .hasObservationWithNameEqualTo("gen_ai.client.operation");
        });
    }

    private ApplicationContextRunner runner() {
        LlmCallRecorder collector = recorded::add;
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> collector)
                .withPropertyValues(runnerProperties());
    }

    private String[] runnerProperties() {
        return ProviderContextRunners.concat(
                new String[] {"agentos.default-provider=primary"},
                ProviderContextRunners.connection(
                        "primary", "openai", stub.baseUrl(), "primary-key"));
    }

    private static Profile profile() {
        return new Profile("primary", "requested-model", null, null, null);
    }

    private static ProviderRequest request(Profile profile) {
        return new ProviderRequest(
                profile, List.of(new UserMessage("ping")), null, null, null, null);
    }
}
