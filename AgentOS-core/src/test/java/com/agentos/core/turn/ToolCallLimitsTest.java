package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.audit.ToolInvocationRecord;
import com.agentos.core.audit.ToolInvocationRecorder;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.tool.Redactor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ToolCallLimitsTest {
    @TempDir Path workspace;

    @Test
    void managerLimitSkipsCallbacksAndAuditsEverySkippedId() throws Exception {
        AgentFixtures.agent(workspace, "a");
        LoadedAgent agent = AgentFixtures.loader(workspace).load("a");
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        var context =
                new TurnContext(
                        UUID.randomUUID(), UUID.randomUUID(), agent, "cli", 1, budget.deadline());
        var executed = new AtomicInteger();
        var skipped = new ArrayList<ToolInvocationRecord>();
        ToolInvocationRecorder recorder = mock(ToolInvocationRecorder.class);
        when(recorder.begin(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            skipped.add(invocation.getArgument(0));
                            return null;
                        })
                .when(recorder)
                .skipped(any());
        ToolCallback callback =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("weather")
                                .description("天气")
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(
                            String input, org.springframework.ai.chat.model.ToolContext tc) {
                        executed.incrementAndGet();
                        ((ToolCallLedger) tc.getContext().get(ToolContextKeys.TOOL_CALL_LEDGER))
                                .next("weather");
                        return "ok";
                    }

                    public String call(String input) {
                        return "ok";
                    }
                };
        var response = response("one", "two");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .withPropertyValues(
                        "spring.ai.tools.limits.max-total-tool-calls=1",
                        "spring.ai.tools.limits.max-calls-per-tool-default=1",
                        "spring.ai.tools.limits.on-limit-exceeded=RETURN_ERROR_RESPONSE")
                .run(application -> {
                    var executor =
                            new ToolExecutor(
                                    application.getBean(ToolCallingManager.class),
                                    recorder,
                                    new Redactor(List::of));
                    var result =
                            executor.execute(
                                    List.of(new UserMessage("天气")),
                                    response,
                                    List.of(callback),
                                    context,
                                    budget,
                                    () -> {});
                    assertThat(executed).hasValue(1);
                    assertThat(skipped)
                            .singleElement()
                            .satisfies(record -> {
                                assertThat(record.toolCallId()).isEqualTo("two");
                                assertThat(record.status()).isEqualTo("limit_skipped");
                                assertThat(record.attempts()).isZero();
                                assertThat(record.inputJson())
                                        .hasSizeLessThanOrEqualTo(10000)
                                        .contains("摘要截断")
                                        .doesNotContain("SKIP_SECRET");
                            });
                    assertThat(result.added()).hasSize(2);
                    var next =
                            executor.execute(
                                    result.history(),
                                    response("three"),
                                    List.of(callback),
                                    context,
                                    budget,
                                    () -> {});
                    assertThat(executed).hasValue(1);
                    assertThat(skipped).hasSize(2);
                    assertThat(skipped.getLast().toolCallId()).isEqualTo("three");
                    assertThat(next.added()).hasSize(2);
                });
    }

    private static ChatResponse response(String... ids) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(
                                                java.util.Arrays.stream(ids)
                                                        .map(id ->
                                                                new AssistantMessage.ToolCall(
                                                                        id,
                                                                        "function",
                                                                        "weather",
                                                                        id.equals("two")
                                                                                ? "{\"token\":\"SKIP_SECRET\",\"padding\":\""
                                                                                        + "x"
                                                                                                .repeat(
                                                                                                        12000)
                                                                                        + "\"}"
                                                                                : "{}"))
                                                        .toList())
                                        .build())));
    }
}
