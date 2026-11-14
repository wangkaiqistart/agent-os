package com.agentos.provider.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ProviderContextRunners;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * DeepSeek 官方接口的真实冒烟测试。
 *
 * <p>只在 {@code mvn verify -Pllm} 且设置了 AGENTOS_DEEPSEEK_API_KEY 时运行。DeepSeek 认 {@code max_tokens}，
 * 会忽略 {@code max_completion_tokens}，所以这里能验证输出上限真的生效（spec SC-015）。
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "AGENTOS_DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekSmokeTest {

    @Test
    @DisplayName("同步调用返回非空文本，记录里的实际模型和 token 用量有值")
    void callReturnsTextAndRecordsUsage() {
        runner().run(context -> {
            ProviderResult result =
                    context.getBean(ProviderService.class).call(request(1024, "请只回复一个词：好"));

            assertThat(result.response().getResult().getOutput().getText()).isNotBlank();
            assertThat(result.calls()).singleElement().satisfies(this::assertRecordFilled);
        });
    }

    @Test
    @DisplayName("输出上限很小时结束原因是 LENGTH，输出 token 不超过上限（SC-015）")
    void tinyOutputCapEndsWithLength() {
        int cap = 20;
        runner().run(context -> {
            ProviderResult result =
                    context.getBean(ProviderService.class)
                            .call(request(cap, "请写一篇不少于一千字的文章，介绍 Java 虚拟线程。"));

            LlmCallRecord record = result.calls().getFirst();
            assertThat(record.finishReason()).isEqualToIgnoringCase("length");
            assertThat(record.completionTokens()).isNotNull().isLessThanOrEqualTo(cap);
        });
    }

    private ApplicationContextRunner runner() {
        return ProviderContextRunners.base()
                .withPropertyValues(
                        "agentos.default-provider=deepseek",
                        "agentos.providers.deepseek.type=deepseek",
                        "agentos.providers.deepseek.base-url="
                                + "${AGENTOS_DEEPSEEK_BASE_URL:https://api.deepseek.com}",
                        "agentos.providers.deepseek.api-key=${AGENTOS_DEEPSEEK_API_KEY:}",
                        "agentos.providers.deepseek.timeout=60s",
                        "agentos.providers.deepseek.retry-min-remaining=15s");
    }

    private static ProviderRequest request(int maxOutputTokens, String text) {
        Profile profile =
                new Profile(
                        "deepseek",
                        System.getenv("AGENTOS_DEEPSEEK_MODEL"),
                        null,
                        maxOutputTokens,
                        null);
        return new ProviderRequest(profile, List.of(new UserMessage(text)), null, null, null, null);
    }

    private void assertRecordFilled(LlmCallRecord record) {
        assertThat(record.success()).isTrue();
        assertThat(record.responseModel()).isNotBlank();
        assertThat(record.totalTokens()).isNotNull().isPositive();
    }
}
