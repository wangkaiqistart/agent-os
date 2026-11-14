package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.provider.CallMode;
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
import com.agentos.provider.support.SseChunks;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 流式调用：文本增量按序交给输出端，结束时交出与同步调用等价的完整结果。
 *
 * <p>覆盖 spec US4-1 ~ US4-4 与 SC-004、SC-009、FR-014、FR-019。连接超时 5 秒、重试最少剩余时间 1 秒。
 */
class StreamingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    @DisplayName("文本分 3 段到达：输出端依次收到 3 段，聚合结果是完整文本，请求带 include_usage")
    void textDeltasReachSinkInOrder() {
        stub.enqueue(
                StubResponse.sse(
                        200,
                        List.of(
                                SseChunks.role(),
                                SseChunks.text("北京"),
                                SseChunks.text("今天"),
                                SseChunks.text("晴"),
                                SseChunks.finish("stop"),
                                SseChunks.usage(3, 4),
                                SseChunks.DONE)));

        runner().run(context -> {
            List<String> received = new ArrayList<>();

            ProviderResult result =
                    context.getBean(ProviderService.class).stream(request(), received::add);

            assertThat(received).containsExactly("北京", "今天", "晴");
            assertThat(text(result)).isEqualTo("北京今天晴");
            JsonNode body = JSON.readTree(stub.requests().getFirst().body());
            assertThat(stub.requests().getFirst().path()).isEqualTo("/v1/chat/completions");
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
        });
    }

    @Test
    @DisplayName("工具调用参数拆成 3 个分片：聚合结果里参数完整，输出端没有文本，工具没有被执行（US4-1、SC-009）")
    void splitToolCallArgumentsAreAssembled() {
        stub.enqueue(
                StubResponse.sse(
                        200,
                        List.of(
                                SseChunks.role(),
                                SseChunks.toolCallStart(0, "call_a", "get_weather"),
                                SseChunks.toolCallArguments(0, "{\"ci"),
                                SseChunks.toolCallArguments(0, "ty\":\"Bei"),
                                SseChunks.toolCallArguments(0, "jing\"}"),
                                SseChunks.finish("tool_calls"),
                                SseChunks.usage(5, 2),
                                SseChunks.DONE)));
        AtomicInteger invocations = new AtomicInteger();

        runner().run(context -> {
            List<String> received = new ArrayList<>();
            ProviderRequest request =
                    new ProviderRequest(
                            profile(),
                            List.of(new UserMessage("天气")),
                            List.of(tool("get_weather", invocations)),
                            null,
                            null,
                            null);

            ProviderResult result =
                    context.getBean(ProviderService.class).stream(request, received::add);

            AssistantMessage output = result.response().getResult().getOutput();
            assertThat(output.getToolCalls()).hasSize(1);
            assertThat(output.getToolCalls().getFirst().id()).isEqualTo("call_a");
            assertThat(output.getToolCalls().getFirst().name()).isEqualTo("get_weather");
            assertThat(output.getToolCalls().getFirst().arguments())
                    .isEqualTo("{\"city\":\"Beijing\"}");
            assertThat(received).isEmpty();
            assertThat(invocations).hasValue(0);
            assertThat(result.calls().getFirst().toolCallCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("正常流式：只有 1 条记录，mode=STREAM，用量不重复累加（US4-4）")
    void normalStreamProducesOneRecordWithoutDoubleCountingUsage() {
        stub.enqueue(
                StubResponse.sse(
                        200,
                        List.of(
                                SseChunks.role(),
                                SseChunks.text("a"),
                                SseChunks.text("b"),
                                SseChunks.finish("stop"),
                                SseChunks.usage(3, 4),
                                SseChunks.DONE)));

        runner().run(context -> {
            ProviderResult result =
                    context.getBean(ProviderService.class).stream(request(), text -> {});

            assertThat(recorded).hasSize(1).isEqualTo(result.calls());
            LlmCallRecord record = recorded.getFirst();
            assertThat(record.mode()).isEqualTo(CallMode.STREAM);
            assertThat(record.attempt()).isEqualTo(1);
            assertThat(record.success()).isTrue();
            assertThat(record.promptTokens()).isEqualTo(3);
            assertThat(record.completionTokens()).isEqualTo(4);
            assertThat(record.totalTokens()).isEqualTo(7);
            assertThat(record.responseId()).isEqualTo("chatcmpl-s1");
            assertThat(record.responseModel()).isEqualTo("actual-model");
            assertThat(record.finishReason()).isEqualToIgnoringCase("stop");
        });
    }

    @Test
    @DisplayName("服务端没发用量分片：三项 token 记为 null，不是 0（FR-023）")
    void streamWithoutUsageChunkRecordsNullTokens() {
        stub.enqueue(
                StubResponse.sse(
                        200,
                        List.of(
                                SseChunks.role(),
                                SseChunks.text("a"),
                                SseChunks.finish("stop"),
                                SseChunks.DONE)));

        runner().run(context -> {
            context.getBean(ProviderService.class).stream(request(), text -> {});

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.promptTokens()).isNull();
            assertThat(record.completionTokens()).isNull();
            assertThat(record.totalTokens()).isNull();
            assertThat(record.cacheReadTokens()).isNull();
            assertThat(record.requestsRemaining()).isNull();
        });
    }

    @Test
    @DisplayName("流式用量带缓存命中数：缓存读取等于该值，缓存写入为 null（FR-022、FR-023）")
    void streamRecordsCachedTokens() {
        stub.enqueue(
                StubResponse.sse(
                        200,
                        List.of(
                                SseChunks.role(),
                                SseChunks.text("a"),
                                SseChunks.finish("stop"),
                                SseChunks.usage(100, 5, 64),
                                SseChunks.DONE)));

        runner().run(context -> {
            context.getBean(ProviderService.class).stream(request(), text -> {});

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.cacheReadTokens()).isEqualTo(64L);
            assertThat(record.cacheWriteTokens()).isNull();
            assertThat(record.promptTokens()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("流式用量明确返回 cached_tokens=0：记为 0，不记为 null（FR-023）")
    void streamRecordsExplicitZeroCachedTokens() {
        stub.enqueue(
                StubResponse.sse(
                        200,
                        List.of(
                                SseChunks.role(),
                                SseChunks.text("a"),
                                SseChunks.finish("stop"),
                                SseChunks.usage(10, 2, 0),
                                SseChunks.DONE)));

        runner().run(context -> {
            context.getBean(ProviderService.class).stream(request(), text -> {});

            assertThat(recorded.getFirst().cacheReadTokens()).isZero();
        });
    }

    @Test
    @DisplayName("已知限制：流式响应的限流头拿不到，记为 null 而不是 0（FR-023）")
    void streamCannotSeeRateLimitHeaders() {
        // Spring AI 2.0.2 的流式走 SDK 的 createStreaming，不暴露响应头，限流余量固定是 EmptyRateLimit。
        // 服务端明明发了限流头，记录里仍然是 null。这个用例用来记录这个限制：
        // 以后升级 Spring AI 后它如果失败，说明框架支持了，应该改成断言有值。
        stub.enqueue(
                StubResponse.sse(
                                200,
                                List.of(
                                        SseChunks.role(),
                                        SseChunks.text("a"),
                                        SseChunks.finish("stop"),
                                        SseChunks.usage(3, 1),
                                        SseChunks.DONE))
                        .withHeader("x-ratelimit-remaining-requests", "99")
                        .withHeader("x-ratelimit-remaining-tokens", "12345"));

        runner().run(context -> {
            context.getBean(ProviderService.class).stream(request(), text -> {});

            LlmCallRecord record = recorded.getFirst();
            assertThat(record.requestsRemaining()).isNull();
            assertThat(record.tokensRemaining()).isNull();
        });
    }

    @Test
    @DisplayName("流式与同步等价：同样的内容，文本、结束原因、用量一致")
    void streamResultMatchesCallResult() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK))
                .enqueue(
                        StubResponse.sse(
                                200,
                                List.of(
                                        SseChunks.role(),
                                        SseChunks.text("po"),
                                        SseChunks.text("ng"),
                                        SseChunks.finish("stop"),
                                        SseChunks.usage(3, 1),
                                        SseChunks.DONE)));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            ProviderResult called = service.call(request());
            ProviderResult streamed = service.stream(request(), text -> {});

            assertThat(text(streamed)).isEqualTo(text(called));
            LlmCallRecord viaCall = called.calls().getFirst();
            LlmCallRecord viaStream = streamed.calls().getFirst();
            assertThat(viaStream.finishReason()).isEqualToIgnoringCase(viaCall.finishReason());
            assertThat(viaStream.promptTokens()).isEqualTo(viaCall.promptTokens());
            assertThat(viaStream.completionTokens()).isEqualTo(viaCall.completionTokens());
            assertThat(viaStream.totalTokens()).isEqualTo(viaCall.totalTokens());
        });
    }

    @Test
    @DisplayName("还没有交出增量就遇到 503：按 US2 的规则重试 1 次，输出端只收到成功那次的文本（US4-3）")
    void retriesBeforeAnyDeltaIsDelivered() {
        stub.enqueue(StubResponse.json(503, "{\"error\":{\"type\":\"overloaded\"}}"))
                .enqueue(
                        StubResponse.sse(
                                200,
                                List.of(
                                        SseChunks.role(),
                                        SseChunks.text("好"),
                                        SseChunks.finish("stop"),
                                        SseChunks.usage(1, 1),
                                        SseChunks.DONE)));

        runner().run(context -> {
            List<String> received = new ArrayList<>();

            ProviderResult result =
                    context.getBean(ProviderService.class).stream(request(), received::add);

            assertThat(stub.requestCount()).isEqualTo(2);
            assertThat(received).containsExactly("好");
            assertThat(result.calls()).hasSize(2);
            assertThat(result.calls())
                    .allSatisfy(call -> assertThat(call.mode()).isEqualTo(CallMode.STREAM));
            assertThat(result.calls().get(0).failureKind()).isEqualTo(FailureKind.SERVER_ERROR);
            assertThat(result.calls().get(1).success()).isTrue();
        });
    }

    @Test
    @DisplayName("已交出增量后流被切断：失败并留下记录，不重发请求（US4-2、FR-019）")
    void doesNotRetryAfterDeltaWasDelivered() {
        // 第 2 个响应是成功的：如果被测代码错误地重试，输出端会收到它的文本，请求数也会变成 2。
        stub.enqueue(StubResponse.sseThenDrop(200, List.of(SseChunks.role(), SseChunks.text("部分"))))
                .enqueue(
                        StubResponse.sse(
                                200,
                                List.of(
                                        SseChunks.role(),
                                        SseChunks.text("重试的文本"),
                                        SseChunks.finish("stop"),
                                        SseChunks.DONE)));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);
            List<String> received = new ArrayList<>();

            assertThatThrownBy(() -> service.stream(request(), received::add))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        List<LlmCallRecord> calls = ((ProviderCallException) error).getCalls();
                        assertThat(calls).hasSize(1);
                        assertThat(calls.getFirst().success()).isFalse();
                        assertThat(calls.getFirst().mode()).isEqualTo(CallMode.STREAM);
                    });

            assertThat(received).containsExactly("部分");
            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(recorded).hasSize(1);
        });
    }

    @Test
    @DisplayName("分片持续到来但越过截止时间：在截止时间 + 1 秒内取消并失败，已交出增量后不重试（SC-004）")
    void slowStreamIsCancelledAtDeadline() {
        Duration budget = Duration.ofSeconds(2);
        List<String> chunks = new ArrayList<>();
        chunks.add(SseChunks.role());
        for (int i = 0; i < 8; i++) {
            chunks.add(SseChunks.text("段" + i));
        }
        chunks.add(SseChunks.finish("stop"));
        chunks.add(SseChunks.DONE);
        stub.enqueue(StubResponse.sse(200, chunks).withChunkInterval(Duration.ofMillis(600)))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);
            List<String> received = new CopyOnWriteArrayList<>();

            long start = System.nanoTime();
            assertThatThrownBy(() -> service.stream(requestWithin(budget), received::add))
                    .isInstanceOf(ProviderCallException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).isLessThanOrEqualTo(budget.plus(Duration.ofSeconds(1)));
            assertThat(received).isNotEmpty().hasSizeLessThan(8);
            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(recorded).hasSize(1);
            assertThat(recorded.getFirst().failureKind()).isEqualTo(FailureKind.CONNECTION);
        });
    }

    @Test
    @DisplayName("首字节迟迟不到：在截止时间附近取消并失败，不等满连接超时")
    void silentStreamIsCancelledAtDeadline() {
        Duration budget = Duration.ofMillis(1500);
        stub.enqueue(StubResponse.hang());

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            long start = System.nanoTime();
            assertThatThrownBy(() -> service.stream(requestWithin(budget), text -> {}))
                    .isInstanceOf(ProviderCallException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).isLessThanOrEqualTo(budget.plus(Duration.ofSeconds(1)));
            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(recorded.getFirst().failureKind()).isEqualTo(FailureKind.CONNECTION);
        });
    }

    @Test
    @DisplayName("输出端抛异常：记为 CANCELLED，停止接收，不重试，也不改动中断标志")
    void sinkFailureIsRecordedAsCancelled() {
        stub.enqueue(
                        StubResponse.sse(
                                200,
                                List.of(
                                        SseChunks.role(),
                                        SseChunks.text("甲"),
                                        SseChunks.text("乙"),
                                        SseChunks.finish("stop"),
                                        SseChunks.DONE)))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            assertThatThrownBy(() ->
                            service.stream(
                                    request(),
                                    text -> {
                                        throw new IllegalStateException("sink closed");
                                    }))
                    .isInstanceOf(ProviderCallException.class)
                    .hasMessageNotContaining("sink closed")
                    .satisfies(error ->
                            assertThat(((ProviderCallException) error).getCalls())
                                    .singleElement()
                                    .satisfies(call ->
                                            assertThat(call.failureKind())
                                                    .isEqualTo(FailureKind.CANCELLED)));

            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(stub.requestCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("调用线程已被中断：记为 CANCELLED，不重试，中断标志恢复")
    void interruptedThreadIsRecordedAsCancelledAndFlagRestored() {
        stub.enqueue(StubResponse.hang()).enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> service.stream(request(), text -> {}))
                        .isInstanceOf(ProviderCallException.class)
                        .satisfies(error -> {
                            List<LlmCallRecord> calls = ((ProviderCallException) error).getCalls();
                            assertThat(calls).hasSize(1);
                            assertThat(calls.getFirst().failureKind())
                                    .isEqualTo(FailureKind.CANCELLED);
                        });
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(stub.requestCount()).isLessThanOrEqualTo(1);
            } finally {
                // 清除标志，不影响同一线程上的后续测试。
                Thread.interrupted();
            }
        });
    }

    private static String text(ProviderResult result) {
        return result.response().getResult().getOutput().getText();
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

    private static ProviderRequest request() {
        return new ProviderRequest(
                profile(), List.of(new UserMessage("ping")), null, null, null, null);
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

    private static ToolCallback tool(String name, AtomicInteger invocations) {
        ToolDefinition definition =
                ToolDefinition.builder()
                        .name(name)
                        .description("测试工具")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                invocations.incrementAndGet();
                return "unused";
            }
        };
    }
}
