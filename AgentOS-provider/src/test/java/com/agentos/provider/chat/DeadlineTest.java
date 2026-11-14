package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ProviderContextRunners;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 截止时间：任何调用都不能拖过本轮剩余时间，重试要受 retry-min-remaining 约束。
 *
 * <p>覆盖 spec US2-4 ~ US2-6、SC-004、SC-021。真实配置里连接超时 120 秒、最少剩余 15 秒，这里按比例缩短： 连接超时 5 秒、最少剩余 1
 * 秒（见 {@link ProviderContextRunners#connection}）。每个场景的总耗时都不得超过"剩余时间 + 1 秒"。
 */
class DeadlineTest {

    /** SC-004 允许的容差。 */
    private static final Duration TOLERANCE = Duration.ofSeconds(1);

    private StubModelServer stub;

    private final List<LlmCallRecord> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = StubModelServer.start();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    @DisplayName("等待 Retry-After 后剩余时间小于 retry-min-remaining：不等待、不重试（US2-4、SC-021）")
    void givesUpWhenRemainingAfterWaitIsBelowMinimum() {
        // 剩余 2.5 秒，等 2 秒后只剩 0.5 秒，小于 1 秒的门槛。
        Duration budget = Duration.ofMillis(2500);
        stub.enqueue(error(429).withHeader("Retry-After", "2"));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            long start = System.nanoTime();
            assertThatThrownBy(() -> service.call(requestWithin(budget)))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        ProviderCallException failure = (ProviderCallException) error;
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                        assertThat(failure.getCalls()).hasSize(1);
                        assertThat(failure.getCalls().getFirst().failureKind())
                                .isEqualTo(FailureKind.RATE_LIMITED);
                    });
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(stub.requestCount()).isEqualTo(1);
            // 没有等待 2 秒，直接失败。
            assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
            assertThat(recorded).hasSize(1);
        });
    }

    @Test
    @DisplayName("Retry-After 大于本轮剩余时间：不等待、不重试，总耗时不超过预算（SC-004）")
    void givesUpWhenRetryAfterExceedsRemainingBudget() {
        Duration budget = Duration.ofSeconds(3);
        stub.enqueue(error(429).withHeader("Retry-After", "30"));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            long start = System.nanoTime();
            assertThatThrownBy(() -> service.call(requestWithin(budget)))
                    .isInstanceOf(ProviderCallException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(elapsed).isLessThanOrEqualTo(budget.plus(TOLERANCE));
            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        });
    }

    @Test
    @DisplayName("剩余时间够门槛但不够一个连接超时：发第 2 次请求，其超时缩短到剩余时间（US2-6、SC-021）")
    void retriesWithTimeoutShortenedToRemainingBudget() {
        // 连接超时 5 秒，本轮只有 3 秒：等 0.5 秒后还剩约 2.5 秒，大于 1 秒门槛，应当重试。
        Duration budget = Duration.ofSeconds(3);
        stub.enqueue(error(503)).enqueue(StubResponse.hang());

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            long start = System.nanoTime();
            assertThatThrownBy(() -> service.call(requestWithin(budget)))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        List<LlmCallRecord> calls = ((ProviderCallException) error).getCalls();
                        assertThat(calls).hasSize(2);
                        assertThat(calls.get(1).attempt()).isEqualTo(2);
                        assertThat(calls.get(1).failureKind()).isEqualTo(FailureKind.CONNECTION);
                    });
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(stub.requestCount()).isEqualTo(2);
            // 第 2 次请求挂起，只能靠缩短后的超时在截止时间附近结束，而不是等满 5 秒。
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
            assertThat(elapsed).isLessThanOrEqualTo(budget.plus(TOLERANCE));
        });
    }

    @Test
    @DisplayName("连接超时大于本轮剩余时间、替身不响应：在剩余时间附近超时失败，不等满连接超时（US2-5）")
    void firstAttemptTimesOutAtDeadlineInsteadOfConnectionTimeout() {
        // 连接超时 5 秒，剩余只有 1.5 秒。
        Duration budget = Duration.ofMillis(1500);
        stub.enqueue(StubResponse.hang());

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            long start = System.nanoTime();
            assertThatThrownBy(() -> service.call(requestWithin(budget)))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        List<LlmCallRecord> calls = ((ProviderCallException) error).getCalls();
                        // 超时后预算已经用完，不会再发第 2 次请求。
                        assertThat(calls).hasSize(1);
                        assertThat(calls.getFirst().failureKind())
                                .isEqualTo(FailureKind.CONNECTION);
                    });
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1000));
            assertThat(elapsed).isLessThanOrEqualTo(budget.plus(TOLERANCE));
        });
    }

    @Test
    @DisplayName("调用前截止时间已过：抛 ProviderCallException，没有记录，不发请求")
    void expiredDeadlineSendsNothing() {
        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);
            ProviderRequest expired =
                    new ProviderRequest(
                            profile(),
                            List.of(new UserMessage("ping")),
                            null,
                            null,
                            Instant.now().minusSeconds(1),
                            null);

            assertThatThrownBy(() -> service.call(expired))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        ProviderCallException failure = (ProviderCallException) error;
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                        assertThat(failure.getCalls()).isEmpty();
                    });

            assertThat(stub.requestCount()).isZero();
            assertThat(recorded).isEmpty();
        });
    }

    private static StubResponse error(int status) {
        return StubResponse.json(
                status,
                "{\"error\":{\"message\":\"boom\",\"type\":\"test_error\",\"code\":\"test_code\"}}");
    }

    private ApplicationContextRunner runner() {
        LlmCallRecorder collector = recorded::add;
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> collector)
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                ProviderContextRunners.connection(
                                        "primary", "openai", stub.baseUrl(), "primary-key")));
    }

    private static Profile profile() {
        return new Profile("primary", "m", null, null, null);
    }

    /** 截止时间在调用发起时才计算，避免上下文启动耗时吃掉预算。 */
    private static ProviderRequest requestWithin(Duration budget) {
        return new ProviderRequest(
                profile(),
                List.of(new UserMessage("ping")),
                null,
                null,
                Instant.now().plus(budget),
                null);
    }
}
