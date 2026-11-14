package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ProviderContextRunners;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 启动校验：逐条覆盖 contracts/configuration.md「启动校验」表中与聊天连接有关的行。
 *
 * <p>用 ApplicationContextRunner 只刷新上下文，验证静态配置与连接校验；
 * 不负责 ApplicationReadyEvent 生命周期，也不发任何网络请求。向量化相关校验在 US5 里测。
 */
class StartupValidationTest {

    /** 一定没有设置过的环境变量；占位符解析后得到空串，用来模拟"凭证为空"。 */
    private static final String UNSET_PRIMARY_KEY = "AGENTOS_TEST_UNSET_PRIMARY_KEY";

    private static final String UNSET_DEEPSEEK_KEY = "AGENTOS_TEST_UNSET_DEEPSEEK_KEY";

    private static final String URL = "http://127.0.0.1:1/v1";

    @Test
    @DisplayName("默认连接不存在：启动失败，错误含默认连接名")
    void failsWhenDefaultConnectionDoesNotExist() {
        runner("agentos.default-provider=ghost")
                .withPropertyValues(
                        ProviderContextRunners.connection("primary", "openai", URL, "k"))
                .run(context -> assertFailureContains(context, "ghost"));
    }

    @Test
    @DisplayName("没有配置任何连接：启动失败，错误含默认连接名")
    void failsWhenNoConnectionConfigured() {
        runner("agentos.default-provider=primary")
                .run(context -> assertFailureContains(context, "primary"));
    }

    @Test
    @DisplayName("默认连接 key 为空：启动失败，错误含连接名和从 ${...} 取出的环境变量名（US1-6）")
    void failsWhenDefaultConnectionKeyIsEmpty() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.connection(
                                "primary", "openai", URL, "${" + UNSET_PRIMARY_KEY + ":}"))
                .run(context -> assertFailureContains(context, "primary", UNSET_PRIMARY_KEY));
    }

    @Test
    @DisplayName("默认连接和可选连接 key 都为空：错误只要求补默认连接的变量")
    void failsOnDefaultConnectionEvenWhenOptionalKeyAlsoEmpty() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection(
                                        "primary", "openai", URL, "${" + UNSET_PRIMARY_KEY + ":}"),
                                ProviderContextRunners.connection(
                                        "deepseek",
                                        "deepseek",
                                        URL,
                                        "${" + UNSET_DEEPSEEK_KEY + ":}")))
                .run(context -> {
                    assertFailureContains(context, "primary", UNSET_PRIMARY_KEY);
                    assertThat(failureMessage(context)).doesNotContain(UNSET_DEEPSEEK_KEY);
                });
    }

    @Test
    @DisplayName("只补上 primary 的 key 后启动成功，deepseek 标记为不可用而不是阻断启动（US1-6）")
    void startsWhenOnlyDefaultKeyIsProvided() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection(
                                        "primary", "openai", URL, "primary-key"),
                                ProviderContextRunners.connection(
                                        "deepseek",
                                        "deepseek",
                                        URL,
                                        "${" + UNSET_DEEPSEEK_KEY + ":}")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProviderService service = context.getBean(ProviderService.class);

                    // 不可用连接在外呼前失败，错误指出变量名
                    assertThatThrownBy(() -> service.call(request("deepseek")))
                            .isInstanceOf(AgentOSException.class)
                            .hasMessage("provider 不可用：deepseek，缺少环境变量 " + UNSET_DEEPSEEK_KEY);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "ollama", "gemini"})
    @DisplayName("类型不是 openai / deepseek：启动失败，错误含连接名、类型和“暂不支持”")
    void failsOnUnsupportedType(String type) {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection("primary", "openai", URL, "k"),
                                ProviderContextRunners.connection("extra", type, URL, "k")))
                .run(context -> assertFailureContains(context, "extra", type, "暂不支持"));
    }

    @Test
    @DisplayName("没有写 type：启动失败，错误含连接名")
    void failsWhenTypeMissing() {
        runner(
                        "agentos.default-provider=primary",
                        "agentos.providers.primary.base-url=" + URL,
                        "agentos.providers.primary.api-key=k")
                .run(context -> assertFailureContains(context, "primary", "type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-5s"})
    @DisplayName("timeout 不是正数：启动失败，错误含连接名")
    void failsOnNonPositiveTimeout(String timeout) {
        runner("agentos.default-provider=primary", "agentos.providers.primary.timeout=" + timeout)
                .withPropertyValues(
                        "agentos.providers.primary.type=openai",
                        "agentos.providers.primary.base-url=" + URL,
                        "agentos.providers.primary.api-key=k")
                .run(context -> assertFailureContains(context, "primary", "timeout"));
    }

    @Test
    @DisplayName("base-url 为空：启动失败，错误含连接名")
    void failsOnEmptyBaseUrl() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(ProviderContextRunners.connection("primary", "openai", "", "k"))
                .run(context -> assertFailureContains(context, "primary", "base-url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "6s"})
    @DisplayName("retry-min-remaining 非正数或大于 timeout（5s）：启动失败，错误含连接名和两个值")
    void failsOnInvalidRetryMinRemaining(String value) {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection("primary", "openai", URL, "k"),
                                new String[] {
                                    "agentos.providers.primary.retry-min-remaining=" + value
                                }))
                .run(context -> assertFailureContains(context, "primary", "retry-min-remaining"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "1s"})
    @DisplayName("retry-max-wait 非正数或小于 retry-min-remaining（2s）：启动失败，错误含连接名")
    void failsOnInvalidRetryMaxWait(String value) {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection("primary", "openai", URL, "k"),
                                new String[] {
                                    "agentos.providers.primary.retry-min-remaining=2s",
                                    "agentos.providers.primary.retry-max-wait=" + value
                                }))
                .run(context -> assertFailureContains(context, "primary", "retry-max-wait"));
    }

    @Test
    @DisplayName("retry-min-remaining 等于 timeout、retry-max-wait 等于 retry-min-remaining：边界值合法")
    void acceptsBoundaryRetrySettings() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection("primary", "openai", URL, "k"),
                                new String[] {
                                    "agentos.providers.primary.retry-min-remaining=5s",
                                    "agentos.providers.primary.retry-max-wait=5s"
                                }))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("Primary 与 primary 同时配置：启动失败，错误指出名称冲突")
    void failsWhenNamesDifferOnlyByCase() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection("primary", "openai", URL, "k"),
                                ProviderContextRunners.connection("Primary", "openai", URL, "k")))
                .run(context -> assertFailureContains(context, "primary", "重复"));
    }

    @Test
    @DisplayName("容器里有 ProviderService，但没有任何 ChatModel / EmbeddingModel Bean（FR-004、FR-030）")
    void registersProviderServiceButNoModelBeans() {
        runner("agentos.default-provider=primary")
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                ProviderContextRunners.connection("primary", "openai", URL, "k"),
                                ProviderContextRunners.connection(
                                        "deepseek", "deepseek", URL, "k")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProviderService.class);
                    assertThat(context).doesNotHaveBean(ChatModel.class);
                    assertThat(context).doesNotHaveBean(EmbeddingModel.class);
                });
    }

    private static ApplicationContextRunner runner(String... properties) {
        return ProviderContextRunners.base().withPropertyValues(properties);
    }

    private static ProviderRequest request(String provider) {
        return new ProviderRequest(
                new Profile(provider, "m", null, null, null),
                List.of(new UserMessage("ping")),
                null,
                null,
                null,
                null);
    }

    /** 启动失败时取最深一层的异常消息，避免 Spring 的包装层改变断言。 */
    private static String failureMessage(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable root = context.getStartupFailure();
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static void assertFailureContains(
            AssertableApplicationContext context, String... fragments) {
        assertThat(failureMessage(context)).contains(fragments);
    }
}
