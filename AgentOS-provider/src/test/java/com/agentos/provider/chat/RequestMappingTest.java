package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.CallCorrelation;
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
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 请求组装与结果映射：直接检查最终发给替身的 HTTP 请求体。
 *
 * <p>覆盖 FR-008 ~ FR-011、FR-015、FR-016 与 SC-008、SC-009、SC-015。
 */
class RequestMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StubModelServer openaiStub;

    private StubModelServer deepseekStub;

    private final List<LlmCallRecord> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        openaiStub = StubModelServer.start();
        deepseekStub = StubModelServer.start();
    }

    @AfterEach
    void tearDown() {
        openaiStub.close();
        deepseekStub.close();
    }

    @Test
    @DisplayName("base-url 写到 /v1 时，请求路径是 /v1/chat/completions，且为 POST")
    void postsToChatCompletionsUnderBaseUrl() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class)
                    .call(request(new Profile("openai-conn", "m", null, null, null)));

            StubModelServer.StubRequest sent = openaiStub.requests().getFirst();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        });
    }

    @Test
    @DisplayName("设了推理强度和输出上限、没设温度：有 reasoning_effort，无 temperature（SC-008）")
    void sendsOnlyFieldsSetInProfile() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class)
                    .call(request(new Profile("openai-conn", "gpt-x", "low", 256, null)));

            JsonNode body = sentBody(openaiStub);
            assertThat(body.get("model").asString()).isEqualTo("gpt-x");
            assertThat(body.get("reasoning_effort").asString()).isEqualTo("low");
            assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(256);
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.has("max_tokens")).isFalse();
        });
    }

    @Test
    @DisplayName("什么生成参数都没设：请求体里没有 temperature、推理强度和输出上限")
    void omitsEveryUnsetGenerationParameter() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class)
                    .call(request(new Profile("openai-conn", "gpt-x", null, null, null)));

            JsonNode body = sentBody(openaiStub);
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.has("reasoning_effort")).isFalse();
            assertThat(body.has("max_tokens")).isFalse();
            assertThat(body.has("max_completion_tokens")).isFalse();
        });
    }

    @Test
    @DisplayName("设了温度时才发送温度")
    void sendsTemperatureWhenSet() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class)
                    .call(request(new Profile("openai-conn", "gpt-x", null, null, 0.5)));

            assertThat(sentBody(openaiStub).get("temperature").asDouble()).isEqualTo(0.5);
        });
    }

    @Test
    @DisplayName("输出上限按连接类型映射：openai 发 max_completion_tokens，deepseek 发 max_tokens（SC-015）")
    void mapsOutputCapByConnectionType() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));
        deepseekStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            service.call(request(new Profile("openai-conn", "m", null, 128, null)));
            service.call(request(new Profile("deepseek-conn", "m", null, 64, null)));

            JsonNode openai = sentBody(openaiStub);
            assertThat(openai.get("max_completion_tokens").asInt()).isEqualTo(128);
            assertThat(openai.has("max_tokens")).isFalse();

            JsonNode deepseek = sentBody(deepseekStub);
            assertThat(deepseek.get("max_tokens").asInt()).isEqualTo(64);
            assertThat(deepseek.has("max_completion_tokens")).isFalse();
        });
    }

    @Test
    @DisplayName("不传工具时请求体没有 tools 字段（FR-010）")
    void omitsToolsWhenNoneProvided() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class)
                    .call(request(new Profile("openai-conn", "m", null, null, null)));

            assertThat(sentBody(openaiStub).has("tools")).isFalse();
        });
    }

    @Test
    @DisplayName("toolContext 的键和值都不出现在请求体里（FR-011）")
    void toolContextNeverReachesTheWire() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));
        AtomicInteger invocations = new AtomicInteger();
        ProviderRequest withContext =
                new ProviderRequest(
                        new Profile("openai-conn", "m", null, null, null),
                        List.of(new UserMessage("ping")),
                        List.of(tool("get_weather", invocations)),
                        Map.of("secretContextKey", "secretContextValue-42"),
                        null,
                        null);

        runner().run(context -> {
            context.getBean(ProviderService.class).call(withContext);

            String raw = openaiStub.requests().getFirst().bodyText();
            assertThat(raw)
                    .doesNotContain("secretContextKey")
                    .doesNotContain("secretContextValue-42");
            // 工具定义本身要发出去，说明上面的断言不是因为整个 tools 都被丢掉才通过
            assertThat(sentBody(openaiStub).get("tools")).hasSize(1);
        });
    }

    @Test
    @DisplayName("模型返回 2 个工具调用：ID、名称、参数、顺序原样带回，工具执行 0 次（US1-3、SC-009）")
    void returnsToolCallsVerbatimWithoutExecutingThem() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.TWO_TOOL_CALLS));
        AtomicInteger invocations = new AtomicInteger();
        ProviderRequest withTools =
                new ProviderRequest(
                        new Profile("openai-conn", "m", null, null, null),
                        List.of(new UserMessage("天气和时间")),
                        List.of(tool("get_weather", invocations), tool("get_time", invocations)),
                        null,
                        null,
                        null);

        runner().run(context -> {
            ProviderResult result = context.getBean(ProviderService.class).call(withTools);

            AssistantMessage output = result.response().getResult().getOutput();
            assertThat(output.getToolCalls()).hasSize(2);
            assertThat(output.getToolCalls().get(0).id()).isEqualTo("call_a");
            assertThat(output.getToolCalls().get(0).name()).isEqualTo("get_weather");
            assertThat(output.getToolCalls().get(0).arguments())
                    .isEqualTo("{\"city\":\"Beijing\"}");
            assertThat(output.getToolCalls().get(1).id()).isEqualTo("call_b");
            assertThat(output.getToolCalls().get(1).name()).isEqualTo("get_time");
            assertThat(output.getToolCalls().get(1).arguments()).isEqualTo("{\"zone\":\"UTC\"}");
            assertThat(invocations).hasValue(0);
            // 系统没有自行发起下一次模型调用
            assertThat(openaiStub.requestCount()).isEqualTo(1);
            // 只有工具调用、没有文本，仍然是成功结果
            assertThat(result.calls())
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.success()).isTrue();
                        assertThat(record.finishReason()).isEqualToIgnoringCase("tool_calls");
                        assertThat(record.toolCallCount()).isEqualTo(2);
                    });
        });
    }

    @Test
    @DisplayName("成功调用产生 1 条记录：连接名、模型、用量、关联标识、模式、尝试序号")
    void successfulCallProducesOneRecord() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));
        UUID session = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        ProviderRequest correlated =
                new ProviderRequest(
                        new Profile("openai-conn", "requested-model", null, null, null),
                        List.of(new UserMessage("ping")),
                        null,
                        null,
                        null,
                        new CallCorrelation(session, turn, 3));

        runner().run(context -> {
            ProviderResult result = context.getBean(ProviderService.class).call(correlated);

            assertThat(result.response().getResult().getOutput().getText()).isEqualTo("pong");
            assertThat(recorded).hasSize(1);
            assertThat(result.calls()).isEqualTo(recorded);
            LlmCallRecord record = recorded.getFirst();
            assertThat(record.provider()).isEqualTo("openai-conn");
            assertThat(record.requestedModel()).isEqualTo("requested-model");
            assertThat(record.responseModel()).isEqualTo("actual-model");
            assertThat(record.responseId()).isEqualTo("chatcmpl-1");
            assertThat(record.mode()).isEqualTo(CallMode.CALL);
            assertThat(record.attempt()).isEqualTo(1);
            assertThat(record.success()).isTrue();
            assertThat(record.finishReason()).isEqualToIgnoringCase("stop");
            assertThat(record.promptTokens()).isEqualTo(3);
            assertThat(record.completionTokens()).isEqualTo(1);
            assertThat(record.totalTokens()).isEqualTo(4);
            assertThat(record.sessionId()).isEqualTo(session);
            assertThat(record.turnId()).isEqualTo(turn);
            assertThat(record.iteration()).isEqualTo(3);
            assertThat(record.startedAt()).isNotNull();
            assertThat(record.durationMs()).isNotNegative();
            assertThat(record.failureKind()).isNull();
        });
    }

    @Test
    @DisplayName("响应没有候选结果：按 PROTOCOL 失败，1 条失败记录，不重试（FR-016）")
    void emptyChoicesFailsAsProtocolError() {
        // 多排一个响应：如果被测代码错误地重试，请求数会变成 2
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.EMPTY_CHOICES));
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            assertThatThrownBy(() ->
                            service.call(
                                    request(new Profile("openai-conn", "m", null, null, null))))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        ProviderCallException failure = (ProviderCallException) error;
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE);
                        assertThat(failure.getCalls()).hasSize(1);
                        assertThat(failure.getCalls().getFirst().success()).isFalse();
                        assertThat(failure.getCalls().getFirst().failureKind())
                                .isEqualTo(FailureKind.PROTOCOL);
                    });

            assertThat(openaiStub.requestCount()).isEqualTo(1);
            assertThat(recorded).hasSize(1);
            assertThat(recorded.getFirst().failureKind()).isEqualTo(FailureKind.PROTOCOL);
        });
    }

    @Test
    @DisplayName("记录输出抛出的异常不会被吞掉，也不会触发重发请求")
    void recorderFailurePropagatesWithoutResending() {
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));
        openaiStub.enqueue(StubResponse.json(200, ChatBodies.OK));
        LlmCallRecorder broken =
                record -> {
                    throw new IllegalStateException("recorder down");
                };

        runner(broken)
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() ->
                                    service.call(
                                            request(
                                                    new Profile(
                                                            "openai-conn", "m", null, null, null))))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("recorder down");

                    assertThat(openaiStub.requestCount()).isEqualTo(1);
                });
    }

    private ApplicationContextRunner runner() {
        return runner(recorded::add);
    }

    /** openai-conn 与 deepseek-conn 是两个不同类型、指向不同替身的连接，openai-conn 为默认。 */
    private ApplicationContextRunner runner(LlmCallRecorder recorder) {
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> recorder)
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=openai-conn"},
                                ProviderContextRunners.connection(
                                        "openai-conn", "openai", openaiStub.baseUrl(), "k1"),
                                ProviderContextRunners.connection(
                                        "deepseek-conn",
                                        "deepseek",
                                        deepseekStub.baseUrl(),
                                        "k2")));
    }

    private static JsonNode sentBody(StubModelServer stub) {
        return JSON.readTree(stub.requests().getFirst().body());
    }

    private static ProviderRequest request(Profile profile) {
        return new ProviderRequest(
                profile, List.of(new UserMessage("ping")), null, null, null, null);
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
