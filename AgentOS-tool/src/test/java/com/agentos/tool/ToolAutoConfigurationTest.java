package com.agentos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.turn.ToolExecutor;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ToolAutoConfigurationTest {
    private ApplicationContextRunner runner() {
        var recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(i -> i.getArgument(0));
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                ToolAutoConfiguration.class, ToolCallingAutoConfiguration.class))
                .withBean(ToolInvocationRecorder.class, () -> recorder)
                .withBean(Redactor.class, () -> new Redactor(List::of))
                .withPropertyValues("spring.ai.tools.resolution.fallback.enabled=true");
    }

    @Test
    void uniqueOfficialManagerAndSafeResolverNeverExecuteOutsideBean() {
        var outside = new AtomicInteger();
        runner().withBean(
                        "outside",
                        java.util.function.Function.class,
                        () ->
                                (java.util.function.Function<String, String>)
                                        input -> {
                                            outside.incrementAndGet();
                                            return "LEAK";
                                        })
                .run(ctx -> {
                    assertThat(ctx)
                            .hasSingleBean(ToolCallingManager.class)
                            .hasSingleBean(ToolCallbackResolver.class)
                            .hasSingleBean(ToolExecutionExceptionProcessor.class)
                            .hasSingleBean(ToolCatalog.class);
                    var budget = TurnExecutionBudget.start(Duration.ofSeconds(2));
                    var agent =
                            new com.agentos.core.agent.LoadedAgent(
                                    new com.agentos.core.agent.AgentProfile(
                                            "a",
                                            "",
                                            new com.agentos.core.provider.Profile(
                                                    null, "m", null, null, null),
                                            List.of(),
                                            null),
                                    "",
                                    Map.of());
                    var context =
                            new TurnContext(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    agent,
                                    "test",
                                    1,
                                    budget.deadline());
                    var response =
                            new ChatResponse(
                                    List.of(
                                            new Generation(
                                                    AssistantMessage.builder()
                                                            .content("")
                                                            .toolCalls(
                                                                    List.of(
                                                                            new AssistantMessage
                                                                                    .ToolCall(
                                                                                    "id",
                                                                                    "function",
                                                                                    "outside",
                                                                                    "{}")))
                                                            .build())));
                    var result =
                            new ToolExecutor(ctx.getBean(ToolCallingManager.class))
                                    .execute(
                                            List.of(new UserMessage("hi")),
                                            response,
                                            List.of(),
                                            context,
                                            budget,
                                            () -> {});
                    assertThat(
                                    ((ToolResponseMessage) result.history().getLast())
                                            .getResponses()
                                            .getFirst()
                                            .responseData())
                            .contains("没有");
                    assertThat(outside).hasValue(0);
                    verify(ctx.getBean(ToolInvocationRecorder.class)).begin(any());
                    verify(ctx.getBean(ToolInvocationRecorder.class)).complete(any());
                });
    }

    @Test
    void officialLimitsStillApplyAndRequestCallbackWins() {
        runner().withPropertyValues(
                        "spring.ai.tools.limits.max-total-tool-calls=1",
                        "spring.ai.tools.limits.max-calls-per-tool-default=1",
                        "spring.ai.tools.limits.on-limit-exceeded=RETURN_ERROR_RESPONSE")
                .run(ctx -> {
                    var executions = new AtomicInteger();
                    ToolCallback cb =
                            new ToolCallback() {
                                public ToolDefinition getToolDefinition() {
                                    return ToolDefinition.builder()
                                            .name("outside")
                                            .description("request scoped")
                                            .inputSchema("{\"type\":\"object\"}")
                                            .build();
                                }

                                public String call(String s) {
                                    executions.incrementAndGet();
                                    return "request-scoped";
                                }
                            };
                    var response =
                            new ChatResponse(
                                    List.of(
                                            new Generation(
                                                    AssistantMessage.builder()
                                                            .content("")
                                                            .toolCalls(
                                                                    List.of(
                                                                            new AssistantMessage
                                                                                    .ToolCall(
                                                                                    "one",
                                                                                    "function",
                                                                                    "outside",
                                                                                    "{}"),
                                                                            new AssistantMessage
                                                                                    .ToolCall(
                                                                                    "two",
                                                                                    "function",
                                                                                    "outside",
                                                                                    "{}")))
                                                            .build())));
                    var result =
                            ctx.getBean(ToolCallingManager.class)
                                    .executeToolCalls(
                                            new Prompt(
                                                    List.of(new UserMessage("hi")),
                                                    ToolCallingChatOptions.builder()
                                                            .toolCallbacks(cb)
                                                            .build()),
                                            response);
                    assertThat(executions).hasValue(1);
                    assertThat(
                                    ((ToolResponseMessage) result.conversationHistory().getLast())
                                            .getResponses())
                            .hasSize(2);
                    assertThat(
                                    ((ToolResponseMessage) result.conversationHistory().getLast())
                                            .getResponses()
                                            .getFirst()
                                            .responseData())
                            .isEqualTo("request-scoped");
                });
    }

    @Test
    void queryUsesRegisteredDirectoryWhenExecutionCatalogIsCustomized() {
        runner().withBean(
                        "customCatalog",
                        ToolCatalog.class,
                        () -> mock(ToolCatalog.class),
                        definition -> definition.setPrimary(true))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var query = ctx.getBean(com.agentos.core.tool.ToolCatalogQuery.class);
                    assertThat(query.snapshot(null).tools())
                            .extracting(com.agentos.core.tool.ToolCatalogView.Item::name)
                            .contains("http_get", "shell", "read_file");
                });
    }

    @Test
    void invalidOrNullAttemptLimitsFailWithSafeConfigurationError() {
        for (String mode : List.of("null_total", "negative", "null_per_tool")) {
            var properties =
                    new org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties();
            if (mode.equals("null_per_tool")) {
                properties.getLimits().setMaxCallsPerToolDefault(null);
            } else {
                properties.getLimits().setMaxTotalToolCalls(mode.equals("negative") ? -2 : null);
            }
            var httpProperties =
                    new com.agentos.tool.http.HttpToolProperties(
                            List.of(), null, 3, 0, null, 10000);
            try (var http =
                    new com.agentos.tool.http.HttpTools(
                            httpProperties,
                            new com.agentos.tool.sandbox.WhitelistSandbox(List.of()))) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                new ToolAutoConfiguration()
                                        .toolCatalog(
                                                http,
                                                httpProperties,
                                                mock(ToolInvocationRecorder.class),
                                                new Redactor(List::of),
                                                properties))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("工具限次");
            }
        }
    }
}
