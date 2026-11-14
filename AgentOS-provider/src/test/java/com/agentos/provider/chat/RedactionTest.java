package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderCallException;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ChatBodies;
import com.agentos.provider.support.ProviderContextRunners;
import com.agentos.provider.support.StubModelServer;
import com.agentos.provider.support.StubModelServer.StubResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 脱敏：日志、调用记录、异常消息、异常链和堆栈里都不能出现 key、Prompt、工具参数。
 *
 * <p>覆盖 spec US3-4、SC-007、FR-024。用 Logback 的 ListAppender 挂在根日志上，抓全部日志事件， 包括第三方库在 DEBUG 级别输出的内容。
 */
class RedactionTest {

    private static final String API_KEY = "sk-LIVE-API-KEY-0001";

    private static final String PROMPT = "SECRET-PROMPT-TEXT";

    private static final String CONTEXT_VALUE = "SECRET-CONTEXT-VALUE";

    /** 放进服务端错误体各个字段里的"密钥"；它们属于不可信输入，不能被原样带出。 */
    private static final String ERROR_SECRET = "sk-SECRET-TEST";

    /** TWO_TOOL_CALLS 响应里的工具调用参数值。 */
    private static final String TOOL_ARGUMENT = "Beijing";

    private static final String ERROR_BODY =
            "{\"error\":{\"message\":\"Incorrect API key provided: "
                    + ERROR_SECRET
                    + "\",\"type\":\""
                    + ERROR_SECRET
                    + "\",\"code\":\""
                    + ERROR_SECRET
                    + "\"}}";

    private StubModelServer stub;

    private final List<LlmCallRecord> recorded = new CopyOnWriteArrayList<>();

    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private Level originalLevel;

    @BeforeEach
    void setUp() throws Exception {
        stub = StubModelServer.start();
        originalLevel = rootLogger.getLevel();
        // 放到最低级别，连第三方库的 DEBUG / TRACE 日志也一起抓。
        rootLogger.setLevel(Level.ALL);
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(appender);
        rootLogger.setLevel(originalLevel);
        appender.stop();
        stub.close();
    }

    @Test
    @DisplayName("401 错误体里的密钥：不出现在日志、记录、异常消息、异常链和堆栈里（US3-4、SC-007）")
    void authErrorBodySecretDoesNotLeak() {
        stub.enqueue(StubResponse.json(401, ERROR_BODY));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(this::assertNothingLeaks);

                    assertNoLeakInLogsAndRecords();
                });
    }

    @Test
    @DisplayName("5xx 错误体里的密钥：重试两次后失败，同样不泄露")
    void serverErrorBodySecretDoesNotLeak() {
        stub.enqueue(StubResponse.json(503, ERROR_BODY))
                .enqueue(StubResponse.json(503, ERROR_BODY));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(this::assertNothingLeaks);

                    assertNoLeakInLogsAndRecords();
                });
    }

    @Test
    @DisplayName("连接失败：异常链里没有原始 SDK 异常，key 不出现")
    void connectionFailureDoesNotLeak() throws Exception {
        String closedUrl;
        try (StubModelServer closed = StubModelServer.start()) {
            closedUrl = closed.baseUrl();
        }

        runner(closedUrl)
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request()))
                            .isInstanceOf(ProviderCallException.class)
                            .satisfies(this::assertNothingLeaks);

                    assertNoLeakInLogsAndRecords();
                });
    }

    @Test
    @DisplayName("成功调用：日志里没有 key、Prompt、应用侧上下文和工具调用参数")
    void successfulCallWithToolsDoesNotLeak() {
        stub.enqueue(StubResponse.json(200, ChatBodies.TWO_TOOL_CALLS));

        runner(stub.baseUrl())
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);
                    ProviderRequest request =
                            new ProviderRequest(
                                    new Profile("primary", "m", null, null, null),
                                    List.of(new UserMessage(PROMPT)),
                                    List.of(tool("get_weather"), tool("get_time")),
                                    Map.of("secretKey", CONTEXT_VALUE),
                                    null,
                                    null);

                    service.call(request);

                    assertNoLeakInLogsAndRecords();
                    // 工具调用参数只在响应里，不能出现在记录中。
                    assertThat(recorded).hasSize(1);
                    assertThat(recorded.getFirst().toolCallCount()).isEqualTo(2);
                });
    }

    private void assertNothingLeaks(Throwable error) {
        ProviderCallException failure = (ProviderCallException) error;
        StringBuilder surface = new StringBuilder();
        surface.append(failure.getMessage()).append('\n');
        failure.getCalls().forEach(call -> surface.append(call.errorSummary()).append('\n'));
        // 异常链：不能带着原始 SDK 异常，那里面有响应体。
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        surface.append(stackTrace);
        assertCleanOfSecrets(surface.toString(), "异常消息、记录摘要和堆栈");
    }

    private void assertNoLeakInLogsAndRecords() {
        // 没抓到任何事件时下面的检查会空转通过，先确认日志确实被捕获了。
        assertThat(appender.list).as("应当捕获到日志事件").isNotEmpty();
        StringBuilder logs = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            logs.append(event.getFormattedMessage()).append('\n');
            // 没有 key-value 的事件，Logback 返回 null 而不是空列表。
            if (event.getKeyValuePairs() != null) {
                event.getKeyValuePairs()
                        .forEach(pair ->
                                logs.append(pair.key).append('=').append(pair.value).append('\n'));
            }
            event.getMDCPropertyMap()
                    .forEach((key, value) ->
                            logs.append(key).append('=').append(value).append('\n'));
            if (event.getThrowableProxy() != null) {
                logs.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        recorded.forEach(record -> logs.append(record).append('\n'));
        assertCleanOfSecrets(logs.toString(), "全部日志与调用记录");
    }

    private static void assertCleanOfSecrets(String text, String where) {
        assertThat(text)
                .as("%s 不能含有 API key", where)
                .doesNotContain(API_KEY)
                .as("%s 不能含有服务端错误体里的密钥", where)
                .doesNotContain(ERROR_SECRET)
                .as("%s 不能含有 Prompt 原文", where)
                .doesNotContain(PROMPT)
                .as("%s 不能含有应用侧上下文", where)
                .doesNotContain(CONTEXT_VALUE)
                .as("%s 不能含有工具调用参数", where)
                .doesNotContain(TOOL_ARGUMENT);
    }

    private ApplicationContextRunner runner(String baseUrl) {
        LlmCallRecorder collector = recorded::add;
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> collector)
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                ProviderContextRunners.connection(
                                        "primary", "openai", baseUrl, API_KEY)));
    }

    private static ProviderRequest request() {
        return new ProviderRequest(
                new Profile("primary", "m", null, null, null),
                List.of(new UserMessage(PROMPT)),
                null,
                Map.of("secretKey", CONTEXT_VALUE),
                null,
                null);
    }

    private static ToolCallback tool(String name) {
        ToolDefinition definition =
                ToolDefinition.builder()
                        .name(name)
                        .description("测试工具")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
        AtomicInteger invocations = new AtomicInteger();
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
