package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.FailureKind;
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
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 聊天调用附带图片：图片与文本一起按原顺序发出，非图片媒体在外呼前失败。
 *
 * <p>覆盖 spec US6-1 ~ US6-4 与 SC-017 ~ SC-019、FR-036 ~ FR-038。
 * {@code openai} 与 {@code deepseek} 两个连接指向同一个替身，用来证明两种类型的行为一致。
 */
class ImageInputTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final byte[] PNG_A = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};

    private static final byte[] PNG_B = {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7, 6};

    private static final String SECRET_URL = "http://images.invalid/secret-image-path.png";

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

    @ParameterizedTest(name = "{0} 连接：图片以 data URL 发出，且排在文本之后（US6-1、SC-017）")
    @ValueSource(strings = {"openai-conn", "deepseek-conn"})
    void inlineImageIsSentWithTextInOrder(String provider) {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            UserMessage message = UserMessage.builder().text("这是什么颜色").media(png(PNG_A)).build();

            context.getBean(ProviderService.class).call(request(provider, message));

            JsonNode content = firstUserContent();
            assertThat(content.isArray()).isTrue();
            assertThat(content.get(0).path("type").asString()).isEqualTo("text");
            assertThat(content.get(0).path("text").asString()).isEqualTo("这是什么颜色");
            assertThat(content.get(1).path("type").asString()).isEqualTo("image_url");
            assertThat(content.get(1).path("image_url").path("url").asString())
                    .isEqualTo(dataUrl(PNG_A));
        });
    }

    @Test
    @DisplayName("两张图片：按添加顺序发出")
    void multipleImagesKeepTheirOrder() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            UserMessage message =
                    UserMessage.builder().text("比较两张图").media(png(PNG_A), png(PNG_B)).build();

            context.getBean(ProviderService.class).call(request("openai-conn", message));

            JsonNode content = firstUserContent();
            assertThat(content).hasSize(3);
            assertThat(content.get(1).path("image_url").path("url").asString())
                    .isEqualTo(dataUrl(PNG_A));
            assertThat(content.get(2).path("image_url").path("url").asString())
                    .isEqualTo(dataUrl(PNG_B));
        });
    }

    @Test
    @DisplayName("图片网址：原样作为 image_url 发出，系统不预先下载（US6-2）")
    void imageUrlIsPassedThroughWithoutDownloading() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK));
        // 网址指向本替身自己：如果被测代码偷偷下载，替身会多收到一个请求。
        String imageUrl = stub.baseUrl() + "/images/cat.png";

        runner().run(context -> {
            UserMessage message =
                    UserMessage.builder()
                            .text("看图")
                            .media(
                                    Media.builder()
                                            .mimeType(MimeTypeUtils.IMAGE_PNG)
                                            .data(URI.create(imageUrl))
                                            .build())
                            .build();

            context.getBean(ProviderService.class).call(request("openai-conn", message));

            assertThat(firstUserContent().get(1).path("image_url").path("url").asString())
                    .isEqualTo(imageUrl);
            assertThat(stub.requestCount()).isEqualTo(1);
            assertThat(stub.requests().getFirst().path()).isEqualTo("/v1/chat/completions");
        });
    }

    @ParameterizedTest(name = "{0}：外呼前失败，错误指明媒体类型，请求数为 0（US6-3、SC-018）")
    @ValueSource(strings = {"audio/mpeg", "video/mp4", "application/pdf", "text/plain"})
    void nonImageMediaFailsBeforeAnyRequest(String mime) {
        runner().run(context -> {
            UserMessage message =
                    UserMessage.builder()
                            .text("听一下")
                            .media(
                                    Media.builder()
                                            .mimeType(MimeType.valueOf(mime))
                                            .data(PNG_A)
                                            .build())
                            .build();
            ProviderService service = context.getBean(ProviderService.class);

            assertThatThrownBy(() -> service.call(request("openai-conn", message)))
                    .isInstanceOf(AgentOSException.class)
                    .hasMessage("不支持的媒体类型：" + mime)
                    .satisfies(error ->
                            assertThat(((AgentOSException) error).getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_ARGUMENT));

            assertThat(stub.requestCount()).isZero();
            assertThat(recorded).isEmpty();
        });
    }

    @Test
    @DisplayName("非用户消息携带媒体：外呼前失败，不静默丢弃也不转发")
    void mediaOnNonUserMessageFailsBeforeAnyRequest() {
        runner().run(context -> {
            Message assistant =
                    AssistantMessage.builder().content("上一轮的回答").media(List.of(png(PNG_A))).build();
            ProviderRequest request =
                    new ProviderRequest(
                            new Profile("openai-conn", "m", null, null, null),
                            List.of(assistant, new UserMessage("继续")),
                            null,
                            null,
                            null,
                            null);
            ProviderService service = context.getBean(ProviderService.class);

            assertThatThrownBy(() -> service.call(request))
                    .isInstanceOf(AgentOSException.class)
                    .hasMessageContaining("只有用户消息可以携带图片")
                    .satisfies(error ->
                            assertThat(((AgentOSException) error).getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_ARGUMENT));

            assertThat(stub.requestCount()).isZero();
        });
    }

    @Test
    @DisplayName("记录里的 imageCount 等于图片数；没有图片时为 0（US6-4）")
    void recordCountsImages() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            service.call(
                    request(
                            "openai-conn",
                            UserMessage.builder()
                                    .text("两张")
                                    .media(png(PNG_A), png(PNG_B))
                                    .build()));
            service.call(request("openai-conn", new UserMessage("没有图")));

            assertThat(recorded.get(0).imageCount()).isEqualTo(2);
            assertThat(recorded.get(1).imageCount()).isZero();
        });
    }

    @Test
    @DisplayName("服务拒绝图片（400）：按参数错误失败，不重试，失败记录里也有图片数（FR-038）")
    void serviceRejectionIsNotRetriedAndKeepsImageCount() {
        stub.enqueue(
                        StubResponse.json(
                                400,
                                "{\"error\":{\"message\":\"image too large\","
                                        + "\"type\":\"invalid_request_error\","
                                        + "\"code\":\"image_too_large\"}}"))
                .enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);
            UserMessage message = UserMessage.builder().text("看").media(png(PNG_A)).build();

            assertThatThrownBy(() -> service.call(request("openai-conn", message)))
                    .isInstanceOf(ProviderCallException.class)
                    .satisfies(error -> {
                        List<LlmCallRecord> calls = ((ProviderCallException) error).getCalls();
                        assertThat(calls).hasSize(1);
                        assertThat(calls.getFirst().failureKind())
                                .isEqualTo(FailureKind.INVALID_REQUEST);
                        assertThat(calls.getFirst().imageCount()).isEqualTo(1);
                    });

            assertThat(stub.requestCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("日志和记录里只有图片数量，没有图片数据和网址（US6-4、SC-019）")
    void logsAndRecordsDoNotContainImageDataOrUrl() {
        stub.enqueue(StubResponse.json(200, ChatBodies.OK))
                .enqueue(StubResponse.json(500, "{\"error\":{\"type\":\"server_error\"}}"))
                .enqueue(StubResponse.json(500, "{\"error\":{\"type\":\"server_error\"}}"));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);
            Media inline = png(PNG_A);
            Media remote =
                    Media.builder()
                            .mimeType(MimeTypeUtils.IMAGE_PNG)
                            .data(URI.create(SECRET_URL))
                            .build();
            UserMessage message = UserMessage.builder().text("看").media(inline, remote).build();

            service.call(request("openai-conn", message));
            assertThatThrownBy(() -> service.call(request("openai-conn", message)))
                    .isInstanceOf(ProviderCallException.class);

            String logs = allLogText();
            assertThat(logs)
                    .doesNotContain(Base64.getEncoder().encodeToString(PNG_A))
                    .doesNotContain("secret-image-path")
                    .doesNotContain("images.invalid");
            assertThat(recorded).isNotEmpty();
            assertThat(recorded).allSatisfy(call -> assertThat(call.imageCount()).isEqualTo(2));
        });
    }

    private JsonNode firstUserContent() {
        JsonNode body = JSON.readTree(stub.requests().getFirst().body());
        for (JsonNode message : body.path("messages")) {
            if ("user".equals(message.path("role").asString())) {
                return message.path("content");
            }
        }
        throw new AssertionError("请求里没有用户消息");
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
        recorded.forEach(record -> text.append(record).append('\n'));
        return text.toString();
    }

    private static Media png(byte[] data) {
        return Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(data).build();
    }

    private static String dataUrl(byte[] data) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(data);
    }

    private static ProviderRequest request(String provider, Message message) {
        return new ProviderRequest(
                new Profile(provider, "m", null, null, null),
                List.of(message),
                null,
                null,
                null,
                null);
    }

    /** openai-conn 与 deepseek-conn 是两个不同类型的连接，指向同一个替身。 */
    private ApplicationContextRunner runner() {
        LlmCallRecorder collector = recorded::add;
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> collector)
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=openai-conn"},
                                ProviderContextRunners.connection(
                                        "openai-conn", "openai", stub.baseUrl(), "k1"),
                                ProviderContextRunners.connection(
                                        "deepseek-conn", "deepseek", stub.baseUrl(), "k2")));
    }
}
