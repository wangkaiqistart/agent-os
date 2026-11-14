package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.provider.config.ProviderProperties;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import com.agentos.provider.support.TestSpringAiConfiguration;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ChatModelFactory 行为测试：用本地 HTTP 替身观察最终发出的请求，不访问真实模型。
 *
 * <p>ToolCallingManager 与 ObservationRegistry 来自 Spring AI 官方自动配置，测试不自建。
 */
@SpringJUnitConfig(TestSpringAiConfiguration.class)
class ChatModelFactoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OK_BODY =
            """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"profile-model",
             "choices":[{"index":0,"finish_reason":"stop",
               "message":{"role":"assistant","content":"pong"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    private static final String TOOL_CALL_BODY =
            """
            {"id":"chatcmpl-2","object":"chat.completion","created":1,"model":"profile-model",
             "choices":[{"index":0,"finish_reason":"tool_calls",
               "message":{"role":"assistant","content":null,"tool_calls":[
                 {"id":"call_1","type":"function",
                  "function":{"name":"get_weather","arguments":"{\\"city\\":\\"Beijing\\"}"}}]}}],
             "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
            """;

    private static final String ERROR_BODY =
            """
            {"error":{"message":"stub failure","type":"server_error"}}
            """;

    private final ToolCallingManager toolCallingManager;

    private final TestObservationRegistry observationRegistry;

    private StubModelServer server;

    private ChatModelFactory factory;

    @Autowired
    ChatModelFactoryTest(
            ToolCallingManager toolCallingManager, TestObservationRegistry observationRegistry) {
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
    }

    @BeforeEach
    void setUp() throws Exception {
        server = StubModelServer.start();
        observationRegistry.clear();
        factory = new ChatModelFactory(toolCallingManager, observationRegistry);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void connectionLevelOptionsCarryNoGenerationDefaults() {
        OpenAiChatOptions defaults = factory.create(connection("openai")).getOptions();

        assertThat(defaults.getBaseUrl()).isEqualTo(server.baseUrl());
        assertThat(defaults.getMaxRetries()).isZero();
        assertThat(defaults.getTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.getTemperature()).isNull();
        assertThat(defaults.getMaxTokens()).isNull();
        assertThat(defaults.getMaxCompletionTokens()).isNull();
    }

    @Test
    void openaiRequestUsesProfileModelAndMaxCompletionTokensWithoutTemperature() throws Exception {
        server.enqueue(StubResponse.json(200, OK_BODY));
        OpenAiChatModel model = factory.create(connection("openai"));
        OpenAiChatOptions.Builder request = OpenAiChatOptions.builder().model("profile-model");
        ConnectionType.OPENAI.applyOutputLimit(request, 128);

        ChatResponse response = model.call(new Prompt("ping", request.build()));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("pong");
        assertThat(server.requestCount()).isEqualTo(1);
        StubModelServer.StubRequest sent = server.requests().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/chat/completions");
        JsonNode body = JSON.readTree(sent.body());
        assertThat(body.get("model").asString()).isEqualTo("profile-model");
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(128);
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    void deepseekRequestUsesMaxTokens() throws Exception {
        server.enqueue(StubResponse.json(200, OK_BODY));
        OpenAiChatModel model = factory.create(connection("deepseek"));
        OpenAiChatOptions.Builder request = OpenAiChatOptions.builder().model("deepseek-flash");
        ConnectionType.DEEPSEEK.applyOutputLimit(request, 64);

        model.call(new Prompt("ping", request.build()));

        JsonNode body = JSON.readTree(server.requests().getFirst().body());
        assertThat(body.get("model").asString()).isEqualTo("deepseek-flash");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(64);
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    void toolDefinitionsAreSentButCallbackIsNotExecuted() throws Exception {
        server.enqueue(StubResponse.json(200, TOOL_CALL_BODY));
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback weather = countingTool("get_weather", invocations);
        OpenAiChatModel model = factory.create(connection("openai"));
        OpenAiChatOptions options =
                OpenAiChatOptions.builder()
                        .model("profile-model")
                        // 2.0.2 的 OpenAiChatModel 只用注入的 Manager 解析工具定义，执行留给 ReActLoop。
                        .toolCallbacks(List.of(weather))
                        .build();

        ChatResponse response = model.call(new Prompt("weather?", options));

        assertThat(invocations).hasValue(0);
        assertThat(server.requestCount()).isEqualTo(1);
        assertThat(response.getResult().getOutput().getToolCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("get_weather"));
        JsonNode tools = JSON.readTree(server.requests().getFirst().body()).get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("function").get("name").asString()).isEqualTo("get_weather");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void retryableErrorIsSentOnlyOnce(int status) {
        // 队列里多放几个错误响应：如果 SDK 内置重试没关掉，请求数会大于 1。
        for (int i = 0; i < 3; i++) {
            server.enqueue(StubResponse.json(status, ERROR_BODY));
        }
        OpenAiChatModel model = factory.create(connection("openai"));
        Prompt prompt = new Prompt("ping", OpenAiChatOptions.builder().model("m").build());

        assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(RuntimeException.class);
        assertThat(server.requestCount()).isEqualTo(1);
    }

    @Test
    void callIsObservedThroughInjectedRegistry() {
        server.enqueue(StubResponse.json(200, OK_BODY));
        OpenAiChatModel model = factory.create(connection("openai"));

        model.call(new Prompt("ping", OpenAiChatOptions.builder().model("profile-model").build()));

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("gen_ai.client.operation")
                .that()
                .hasBeenStarted()
                .hasBeenStopped();
    }

    private ProviderProperties.Connection connection(String type) {
        ProviderProperties.Connection connection = new ProviderProperties.Connection();
        connection.setType(type);
        connection.setBaseUrl(server.baseUrl());
        connection.setApiKey("test-key");
        connection.setTimeout(Duration.ofSeconds(5));
        return connection;
    }

    private static ToolCallback countingTool(String name, AtomicInteger invocations) {
        ToolDefinition definition =
                ToolDefinition.builder()
                        .name(name)
                        .description("测试工具")
                        .inputSchema(
                                """
                                {"type":"object","properties":{"city":{"type":"string"}}}
                                """)
                        .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                invocations.incrementAndGet();
                return "sunny";
            }
        };
    }
}
