package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
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
 * 按名称路由与外呼前校验：两个同协议连接指向两个替身，请求只能到达名称对应的那一个。
 *
 * <p>覆盖 spec US1-1、US1-4 与 SC-001、SC-002：校验失败时两个替身的请求数都必须为 0。
 */
class ProviderRoutingTest {

    /** 一个肯定没有设置过的环境变量，占位符解析后得到空串，用来模拟"凭证为空"。 */
    private static final String UNSET_ENV = "AGENTOS_TEST_NEVER_SET_KEY";

    private StubModelServer primaryStub;

    private StubModelServer internalStub;

    private final List<LlmCallRecord> recorded = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        primaryStub = StubModelServer.start();
        internalStub = StubModelServer.start();
    }

    @AfterEach
    void tearDown() {
        primaryStub.close();
        internalStub.close();
    }

    @Test
    @DisplayName("按名称调用 primary 时，请求只到达 primary 的替身（US1-1、SC-001）")
    void routesToNamedConnectionOnly() {
        primaryStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            service.call(request(profile("primary", "model-p")));

            assertThat(primaryStub.requestCount()).isEqualTo(1);
            assertThat(internalStub.requestCount()).isZero();
        });
    }

    @Test
    @DisplayName("按名称调用 internal 时，primary 收不到任何请求")
    void routesToSecondConnectionOnly() {
        internalStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            service.call(request(profile("internal", "model-i")));

            assertThat(internalStub.requestCount()).isEqualTo(1);
            assertThat(primaryStub.requestCount()).isZero();
        });
    }

    @Test
    @DisplayName("provider 名称不区分大小写")
    void providerNameIsCaseInsensitive() {
        internalStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile("INTERNAL", "m")));

            assertThat(internalStub.requestCount()).isEqualTo(1);
            assertThat(primaryStub.requestCount()).isZero();
        });
    }

    @Test
    @DisplayName("Profile 没写 provider 时使用 default-provider")
    void usesDefaultProviderWhenProfileHasNone() {
        primaryStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile(null, "m")));

            assertThat(primaryStub.requestCount()).isEqualTo(1);
            assertThat(internalStub.requestCount()).isZero();
        });
    }

    @Test
    @DisplayName("记录里的 provider 是解析后的连接名，模型是 Profile 的模型")
    void recordCarriesResolvedConnectionName() {
        internalStub.enqueue(StubResponse.json(200, ChatBodies.OK));

        runner().run(context -> {
            context.getBean(ProviderService.class).call(request(profile("INTERNAL", "model-i")));

            assertThat(recorded)
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.provider()).isEqualTo("internal");
                        assertThat(record.requestedModel()).isEqualTo("model-i");
                    });
        });
    }

    @Test
    @DisplayName("未知 provider：外呼前失败，两个替身都没有收到请求（US1-4、SC-002）")
    void unknownProviderFailsBeforeAnyRequest() {
        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            assertThatThrownBy(() -> service.call(request(profile("ghost", "m"))))
                    .isInstanceOf(AgentOSException.class)
                    .hasMessage("未知的 provider：ghost")
                    .satisfies(error ->
                            assertThat(((AgentOSException) error).getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_ARGUMENT));

            assertNoRequestSent();
        });
    }

    @Test
    @DisplayName("连接不可用：消息指出连接名和缺少的环境变量（US1-4、SC-002）")
    void unavailableConnectionFailsBeforeAnyRequest() {
        runner("${" + UNSET_ENV + ":}")
                .run(context -> {
                    ProviderService service = context.getBean(ProviderService.class);

                    assertThatThrownBy(() -> service.call(request(profile("internal", "m"))))
                            .isInstanceOf(AgentOSException.class)
                            .hasMessage("provider 不可用：internal，缺少环境变量 " + UNSET_ENV)
                            .satisfies(error ->
                                    assertThat(((AgentOSException) error).getErrorCode())
                                            .isEqualTo(ErrorCode.INVALID_ARGUMENT));

                    assertNoRequestSent();
                });
    }

    @Test
    @DisplayName("model 为空或空白：外呼前失败，不改用其他模型（US1-4、SC-002）")
    void blankModelFailsBeforeAnyRequest() {
        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            for (String model : new String[] {null, "", "   "}) {
                assertThatThrownBy(() -> service.call(request(profile("primary", model))))
                        .isInstanceOf(AgentOSException.class)
                        .hasMessage("Profile 缺少聊天模型")
                        .satisfies(error ->
                                assertThat(((AgentOSException) error).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_ARGUMENT));
            }

            assertNoRequestSent();
        });
    }

    @Test
    @DisplayName("外呼前校验失败不产生调用记录")
    void preCallValidationFailureProducesNoRecord() {
        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);

            assertThatThrownBy(() -> service.call(request(profile("ghost", "m"))))
                    .isInstanceOf(AgentOSException.class);

            assertThat(recorded).isEmpty();
        });
    }

    @Test
    @DisplayName("截止时间已过：抛 ProviderCallException，不发请求、不产生记录")
    void expiredDeadlineFailsWithoutRequestOrRecord() {
        runner().run(context -> {
            ProviderService service = context.getBean(ProviderService.class);
            ProviderRequest expired =
                    new ProviderRequest(
                            profile("primary", "m"),
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

            assertNoRequestSent();
            assertThat(recorded).isEmpty();
        });
    }

    private void assertNoRequestSent() {
        assertThat(primaryStub.requestCount()).isZero();
        assertThat(internalStub.requestCount()).isZero();
    }

    private ApplicationContextRunner runner() {
        return runner("internal-key");
    }

    /** primary 为默认连接，internal 的密钥由参数决定，便于模拟"凭证为空"。 */
    private ApplicationContextRunner runner(String internalKey) {
        LlmCallRecorder collector = recorded::add;
        return ProviderContextRunners.base()
                .withBean(LlmCallRecorder.class, () -> collector)
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                ProviderContextRunners.connection(
                                        "primary", "openai", primaryStub.baseUrl(), "primary-key"),
                                ProviderContextRunners.connection(
                                        "internal",
                                        "openai",
                                        internalStub.baseUrl(),
                                        internalKey)));
    }

    private static Profile profile(String provider, String model) {
        return new Profile(provider, model, null, null, null);
    }

    private static ProviderRequest request(Profile profile) {
        return new ProviderRequest(
                profile, List.of(new UserMessage("ping")), null, null, null, null);
    }
}
