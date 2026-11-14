package com.agentos.provider.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.provider.support.ProviderContextRunners;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("同步调用返回非空文本，记录里的实际模型和 token 用量有值（US1-5）")
    void callReturnsTextAndRecordsUsage() {
        runner().run(context -> {
            ProviderResult result =
                    context.getBean(ProviderService.class).call(request("请只回复一个词：好"));

            assertThat(result.response().getResult().getOutput().getText()).isNotBlank();
            assertThat(result.calls()).singleElement().satisfies(this::assertRecordFilled);
        });
    }

    @Test
    @DisplayName("流式调用：输出端收到文本，聚合结果非空，记录为 STREAM 且用量有值")
    void streamDeliversTextAndRecordsUsage() {
        runner().run(context -> {
            StringBuilder received = new StringBuilder();

            ProviderResult result =
                    context.getBean(ProviderService.class).stream(
                            request("请只回复一个词：好"), received::append);

            assertThat(received.toString()).isNotBlank();
            assertThat(result.response().getResult().getOutput().getText()).isNotBlank();
            assertThat(result.calls()).singleElement().satisfies(this::assertStreamRecordFilled);
        });
    }

    @Test
    @DisplayName("流式调用：聚合结果里的工具调用完整，参数是合法 JSON，工具没有被执行（SC-010）")
    void streamAssemblesCompleteToolCall() {
        runner().run(context -> {
            AtomicInteger invocations = new AtomicInteger();
            ProviderRequest base = request("请调用 get_weather 工具查询北京的天气，不要直接回答。");
            ProviderRequest request =
                    new ProviderRequest(
                            base.profile(),
                            base.messages(),
                            List.of(weatherTool(invocations)),
                            null,
                            null,
                            null);

            ProviderResult result =
                    context.getBean(ProviderService.class).stream(request, text -> {});

            List<AssistantMessage.ToolCall> toolCalls =
                    result.response().getResult().getOutput().getToolCalls();
            assertThat(toolCalls).isNotEmpty();
            assertThat(toolCalls.getFirst().name()).isEqualTo("get_weather");
            // 参数被拆在多个分片里，拼完整后必须是能解析的 JSON，并带上必填的 city。
            assertThat(JSON.readTree(toolCalls.getFirst().arguments()).path("city").asString())
                    .isNotBlank();
            assertThat(invocations).hasValue(0);
        });
    }

    private ApplicationContextRunner runner() {
        return ProviderContextRunners.base()
                .withPropertyValues(
                        ProviderContextRunners.concat(
                                new String[] {"agentos.default-provider=primary"},
                                new String[] {
                                    "agentos.providers.primary.type=openai",
                                    "agentos.providers.primary.base-url=${AGENTOS_LLM_BASE_URL:}",
                                    "agentos.providers.primary.api-key=${AGENTOS_LLM_API_KEY:}",
                                    "agentos.providers.primary.timeout=120s",
                                    "agentos.providers.primary.retry-min-remaining=15s"
                                }));
    }

    /** 推理模型：不发 temperature，用推理强度和输出上限；上限给足，避免推理吃光额度。 */
    private static ProviderRequest request(String text) {
        Profile profile =
                new Profile("primary", System.getenv("AGENTOS_LLM_MODEL"), "low", 2048, null);
        return new ProviderRequest(profile, List.of(new UserMessage(text)), null, null, null, null);
    }

    private void assertStreamRecordFilled(LlmCallRecord record) {
        assertThat(record.mode()).isEqualTo(CallMode.STREAM);
        assertRecordFilled(record);
    }

    private static ToolCallback weatherTool(AtomicInteger invocations) {
        ToolDefinition definition =
                ToolDefinition.builder()
                        .name("get_weather")
                        .description("查询城市天气")
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"city\"]}")
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

    private void assertRecordFilled(LlmCallRecord record) {
        assertThat(record.success()).isTrue();
        assertThat(record.responseModel()).isNotBlank();
        assertThat(record.totalTokens()).isNotNull().isPositive();
    }
}
