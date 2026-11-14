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

/**
 * 主链路模型（OpenAI 兼容中转）的真实冒烟测试。
 *
 * <p>只在 {@code mvn verify -Pllm} 且设置了 AGENTOS_LLM_API_KEY 时运行。密钥、地址、模型都从环境变量取，
 * 不写进代码，也不打印。中转接口不遵守输出上限（spec SC-015），所以这里只检查请求能成功、记录有值，
 * 不检查结束原因。
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "AGENTOS_LLM_API_KEY", matches = ".+")
class PrimarySmokeTest {

    @Test
    @DisplayName("同步调用返回非空文本，记录里的实际模型和 token 用量有值（US1-5）")
    void callReturnsTextAndRecordsUsage() {
        ProviderContextRunners.base()
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                new String[] {
                                    "agentos.providers.primary.type=openai",
                                    "agentos.providers.primary.base-url=${AGENTOS_LLM_BASE_URL:}",
                                    "agentos.providers.primary.api-key=${AGENTOS_LLM_API_KEY:}",
                                    "agentos.providers.primary.timeout=120s",
                                    "agentos.providers.primary.retry-min-remaining=15s"
                                }))
                .run(context -> {
                    // 推理模型：不发 temperature，用推理强度和输出上限；上限给足，避免推理吃光额度。
                    Profile profile =
                            new Profile(
                                    "primary",
                                    System.getenv("AGENTOS_LLM_MODEL"),
                                    "low",
                                    2048,
                                    null);
                    ProviderRequest request =
                            new ProviderRequest(
                                    profile,
                                    List.of(new UserMessage("请只回复一个词：好")),
                                    null,
                                    null,
                                    null,
                                    null);

                    ProviderResult result = context.getBean(ProviderService.class).call(request);

                    assertThat(result.response().getResult().getOutput().getText()).isNotBlank();
                    assertThat(result.calls()).singleElement().satisfies(this::assertRecordFilled);
                });
    }

    private void assertRecordFilled(LlmCallRecord record) {
        assertThat(record.success()).isTrue();
        assertThat(record.responseModel()).isNotBlank();
        assertThat(record.totalTokens()).isNotNull().isPositive();
    }
}
