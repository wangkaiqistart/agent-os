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
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ChatBodies;
import com.agentos.provider.support.ProviderContextRunners;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 有限重试：只对暂时性故障追加 1 次重试，其余错误立即失败。
 *
 * <p>覆盖 spec US2-1 ~ US2-3 与 SC-003。截止时间相关的场景放在 {@link DeadlineTest}。 连接超时固定 5 秒、重试最少剩余时间 1
 * 秒（见 {@link ProviderContextRunners#connection}），没有设置 retry-max-wait 时它等于连接超时。
 */
class RetryPolicyTest {

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
    @DisplayName("429 + Retry-After: 1 后成功：2 次请求，间隔不少于 1 秒（US2-1）")
    void retriesRateLimitedAfterRetryAfter() {
        stub.enqueue(error(429).withHeader("Retry-After", "1"))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    long start = System.nanoTime();
                    ProviderResult result = service.call(request());
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(stub.requestCount()).isEqualTo(2);
                    assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
                    assertThat(result.calls()).hasSize(2);
                    LlmCallRecord first = result.calls().get(0);
                    assertThat(first.attempt()).isEqualTo(1);
                    assertThat(first.success()).isFalse();
                    assertThat(first.failureKind()).isEqualTo(FailureKind.RATE_LIMITED);
                    assertThat(first.httpStatus()).isEqualTo(429);
                    LlmCallRecord second = result.calls().get(1);
                    assertThat(second.attempt()).isEqualTo(2);
                    assertThat(second.success()).isTrue();
                    assertThat(recorded).isEqualTo(result.calls());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter"})
    @DisplayName("结束原因为截断或内容过滤：是模型的正常输出，只发 1 次请求、记 1 条成功记录（007 FR-013）")
    void doesNotRetryTruncatedOrFilteredResponses(String finishReason) {
        stub.enqueue(
                StubResponse.json(
                        200, ChatBodies.OK.replace("\"stop\"", "\"" + finishReason + "\"")));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderResult result = context.getBean(ProviderService.class).call(request());

                    assertThat(stub.requestCount()).isEqualTo(1);
                    assertThat(result.calls())
                            .singleElement()
                            .satisfies(call -> {
                                assertThat(call.success()).isTrue();
                                assertThat(call.finishReason()).isEqualToIgnoringCase(finishReason);
                            });
                    assertThat(result.response().getResult().getMetadata().getFinishReason())
                            .isEqualToIgnoringCase(finishReason);
                });
    }

    @Test
    @DisplayName("503 连续两次：2 次请求后抛 PROVIDER_UNAVAILABLE，无 Retry-After 时等待约 500 毫秒（US2-2）")
    void failsAfterTwoServerErrors() {
        stub.enqueue(error(503)).enqueue(error(503));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    long start = System.nanoTime();
                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(error -> {
                                ProviderCallException failure = (ProviderCallException) error;
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                                assertThat(failure.getCalls()).hasSize(2);
                                assertThat(failure.getCalls())
                                        .allSatisfy(call -> {
                                            assertThat(call.success()).isFalse();
                                            assertThat(call.failureKind())
                                                    .isEqualTo(FailureKind.SERVER_ERROR);
                                            assertThat(call.httpStatus()).isEqualTo(503);
                                        });
                                assertThat(failure.getCalls().get(1).attempt()).isEqualTo(2);
                            });
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(stub.requestCount()).isEqualTo(2);
                    assertThat(elapsed).isBetween(Duration.ofMillis(450), Duration.ofSeconds(3));
                    assertThat(recorded).hasSize(2);
                });
    }

    @ParameterizedTest(name = "状态码 {0} 按 {1} 立即失败，只发 1 次请求（US2-3、SC-003）")
    @CsvSource({
        "401,AUTH",
        "403,AUTH",
        "400,INVALID_REQUEST",
        "404,INVALID_REQUEST",
        "422,INVALID_REQUEST",
        "501,SERVER_ERROR"
    })
    void doesNotRetryPermanentErrors(int status, FailureKind kind) {
        stub.enqueue(error(status));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(error -> {
                                ProviderCallException failure = (ProviderCallException) error;
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                                assertThat(failure.getCalls()).hasSize(1);
                                assertThat(failure.getCalls().getFirst().attempt()).isEqualTo(1);
                                assertThat(failure.getCalls().getFirst().failureKind())
                                        .isEqualTo(kind);
                                assertThat(failure.getCalls().getFirst().httpStatus())
                                        .isEqualTo(status);
                            });

                    assertThat(stub.requestCount()).isEqualTo(1);
                    assertThat(stub.unexpectedRequestCount()).isZero();
                });
    }

    @ParameterizedTest(name = "状态码 {0} 可以重试：第二次成功，共 2 次请求")
    @ValueSource(ints = {500, 502, 503, 504})
    void retriesTransientServerErrors(int status) {
        stub.enqueue(error(status)).enqueue(StubResponse.json(200, ChatBodies.OK));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderResult result = context.getBean(ProviderService.class).call(request());

                    assertThat(stub.requestCount()).isEqualTo(2);
                    assertThat(result.calls()).hasSize(2);
                    assertThat(result.calls().getFirst().failureKind())
                            .isEqualTo(FailureKind.SERVER_ERROR);
                    assertThat(result.calls().getLast().success()).isTrue();
                });
    }

    @Test
    @DisplayName("连接被拒绝：按 CONNECTION 重试 1 次，仍失败则报 Provider 故障")
    void retriesConnectionRefusedOnce() throws Exception {
        String closedUrl;
        try (StubModelServer closed = StubModelServer.start()) {
            closedUrl = closed.baseUrl();
        }

        runner(closedUrl)
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(error -> {
                                List<LlmCallRecord> calls =
                                        ((ProviderCallException) error).getCalls();
                                assertThat(calls).hasSize(2);
                                assertThat(calls)
                                        .allSatisfy(call -> {
                                            assertThat(call.failureKind())
                                                    .isEqualTo(FailureKind.CONNECTION);
                                            assertThat(call.httpStatus()).isNull();
                                        });
                            });
                });
    }

    @Test
    @DisplayName("没有截止时间：可重试失败照样重试 1 次")
    void retriesOnceWithoutDeadline() {
        stub.enqueue(error(503)).enqueue(StubResponse.json(200, ChatBodies.OK));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderResult result = context.getBean(ProviderService.class).call(request());

                    assertThat(stub.requestCount()).isEqualTo(2);
                    assertThat(result.calls()).hasSize(2);
                });
    }

    @Test
    @DisplayName("没有截止时间且 Retry-After 超过 retry-max-wait：不等待、不重试")
    void givesUpWhenRetryAfterExceedsMaxWaitWithoutDeadline() {
        // retry-max-wait 缺省等于连接超时 5 秒，30 秒的等待必须直接放弃。
        stub.enqueue(error(429).withHeader("Retry-After", "30"));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    long start = System.nanoTime();
                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(error ->
                                    assertThat(((ProviderCallException) error).getCalls())
                                            .hasSize(1));
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(stub.requestCount()).isEqualTo(1);
                    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
                });
    }

    @ParameterizedTest(name = "Retry-After 为 \"{0}\"：按 500 毫秒兜底后重试")
    @ValueSource(strings = {"abc", "-5", "Wed, 21 Oct 2099 07:28:00 GMT"})
    void fallsBackToDefaultWaitForUnusableRetryAfter(String headerValue) {
        stub.enqueue(error(429).withHeader("Retry-After", headerValue))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner(stub.baseUrl())
                .run(context -> {
                    long start = System.nanoTime();
                    ProviderResult result = context.getBean(ProviderService.class).call(request());
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(stub.requestCount()).isEqualTo(2);
                    assertThat(result.calls()).hasSize(2);
                    assertThat(elapsed).isBetween(Duration.ofMillis(450), Duration.ofSeconds(3));
                });
    }

    @Test
    @DisplayName("调用线程已被中断：记为 CANCELLED，不重试，中断标志保持")
    void doesNotRetryWhenThreadIsInterrupted() {
        stub.enqueue(StubResponse.hang()).enqueue(StubResponse.json(200, ChatBodies.OK));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    // 上下文已经启动完，再设置中断标志，避免干扰容器初始化。
                    Thread.currentThread().interrupt();
                    try {
                        assertThatThrownBy(() -> service.call(request()))
                                .isInstanceOf(ProviderCallException.class)
                                .satisfies(error -> {
                                    List<LlmCallRecord> calls =
                                            ((ProviderCallException) error).getCalls();
                                    assertThat(calls).hasSize(1);
                                    assertThat(calls.getFirst().failureKind())
                                            .isEqualTo(FailureKind.CANCELLED);
                                    assertThat(calls.getFirst().success()).isFalse();
                                });
                        assertThat(Thread.currentThread().isInterrupted()).isTrue();
                        assertThat(stub.requestCount()).isLessThanOrEqualTo(1);
                    } finally {
                        // 清除标志，不影响同一线程上的后续测试。
                        Thread.interrupted();
                    }
                });
    }

    @Test
    @DisplayName("等待 Retry-After 期间被中断：立即停止，不发第 2 次请求，中断标志恢复")
    void interruptDuringRetryAfterWaitStopsImmediately() {
        stub.enqueue(error(503).withHeader("Retry-After", "4"))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    try {
                        Future<Outcome> future =
                                executor.submit(() -> {
                                    try {
                                        service.call(request());
                                        return new Outcome(false, false, 0);
                                    } catch (ProviderCallException failure) {
                                        return new Outcome(
                                                true,
                                                Thread.currentThread().isInterrupted(),
                                                failure.getCalls().size());
                                    }
                                });
                        assertThat(stub.awaitRequests(1, Duration.ofSeconds(5))).isTrue();
                        // shutdownNow 会中断正在运行的工作线程。
                        executor.shutdownNow();

                        // 远小于 Retry-After 的 4 秒，证明等待被打断了。
                        Outcome outcome = future.get(3, TimeUnit.SECONDS);

                        assertThat(outcome.failed()).isTrue();
                        assertThat(outcome.interruptFlag()).isTrue();
                        assertThat(outcome.callCount()).isEqualTo(1);
                        assertThat(stub.requestCount()).isEqualTo(1);
                    } finally {
                        executor.shutdownNow();
                    }
                });
    }

    /** 工作线程里观察到的结果：是否失败、结束时中断标志是否仍在、异常里带的记录条数。 */
    private record Outcome(boolean failed, boolean interruptFlag, int callCount) {}

    private static StubResponse error(int status) {
        return StubResponse.json(
                status,
                "{\"error\":{\"message\":\"boom\",\"type\":\"test_error\",\"code\":\"test_code\"}}");
    }

    private ApplicationContextRunner runner(String baseUrl) {
        LlmCallRecorder collector = recorded::add;
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> collector)
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                ProviderContextRunners.connection(
                                        "primary", "openai", baseUrl, "primary-key")));
    }

    private static ProviderRequest request() {
        return new ProviderRequest(
                new Profile("primary", "m", null, null, null),
                List.of(new UserMessage("ping")),
                null,
                null,
                null,
                null);
    }
}
