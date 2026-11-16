package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.support.AgentFixtures;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ToolExecutorTest {
    @TempDir Path directory;

    @Test
    void officialManagerExecutesOncePreservesIdsOrderAndFullHistory() throws Exception {
        AgentFixtures.agent(directory, "a");
        LoadedAgent agent = AgentFixtures.loader(directory).load("a");
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        var turn =
                new TurnContext(
                        UUID.randomUUID(), UUID.randomUUID(), agent, "cli", 1, budget.deadline());
        var seen = new ArrayList<String>();
        var count = new AtomicInteger();
        List<ToolCallback> callbacks =
                List.of(callback("a", false, seen, count), callback("b", true, seen, count));
        var calls = List.of(call("1", "a"), call("2", "b"), call("3", "a"));
        var response = response(calls);
        List<Message> history = List.of(new SystemMessage("sys"), new UserMessage("hi"));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var executor = new ToolExecutor(ctx.getBean(ToolCallingManager.class));
                    var result =
                            executor.execute(history, response, callbacks, turn, budget, () -> {});
                    assertThat(seen).containsExactly("1", "2", "3");
                    assertThat(count).hasValue(3);
                    assertThat(result.history())
                            .hasSize(4)
                            .startsWith(history.toArray(Message[]::new));
                    assertThat(result.added()).hasSize(2);
                    assertThat(((ToolResponseMessage) result.added().getLast()).getResponses())
                            .extracting(ToolResponseMessage.ToolResponse::id)
                            .containsExactly("1", "2", "3");
                    assertThat(result.returnDirect()).isFalse();
                    var direct =
                            executor.execute(
                                    history,
                                    response(List.of(call("4", "b"))),
                                    callbacks,
                                    turn,
                                    budget,
                                    () -> {});
                    assertThat(direct.returnDirect()).isTrue();
                });
    }

    @Test
    void malformedIdsDoNotCallManager() throws Exception {
        AgentFixtures.agent(directory, "a");
        var agent = AgentFixtures.loader(directory).load("a");
        var manager = org.mockito.Mockito.mock(ToolCallingManager.class);
        var executor = new ToolExecutor(manager);
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(2));
        var context =
                new TurnContext(
                        UUID.randomUUID(), UUID.randomUUID(), agent, "cli", 1, budget.deadline());
        assertThatThrownBy(() ->
                        executor.execute(
                                List.of(new UserMessage("x")),
                                response(List.of(call("same", "a"), call("same", "a"))),
                                List.of(),
                                context,
                                budget,
                                () -> {}))
                .isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verifyNoInteractions(manager);
    }

    @Test
    void fatalManagerAbortStopsTheRestOfTheBatchWithoutReturningPartialHistory() throws Exception {
        AgentFixtures.agent(directory, "a");
        var agent = AgentFixtures.loader(directory).load("a");
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(2));
        var context =
                new TurnContext(
                        UUID.randomUUID(), UUID.randomUUID(), agent, "test", 1, budget.deadline());
        var seen = new ArrayList<String>();
        var executions = new AtomicInteger();
        var first = callback("ok", false, seen, executions);
        ToolCallback fatal =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("fatal")
                                .description("test")
                                .inputSchema("{}")
                                .build();
                    }

                    public String call(String input) {
                        throw new com.agentos.core.audit.AuditWriteException("审计未确认");
                    }
                };
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .run(ctx -> {
                    var executor = new ToolExecutor(ctx.getBean(ToolCallingManager.class));
                    assertThatThrownBy(() ->
                                    executor.execute(
                                            List.of(new UserMessage("hi")),
                                            response(
                                                    List.of(
                                                            call("one", "ok"),
                                                            call("two", "fatal"),
                                                            call("three", "ok"))),
                                            List.of(first, fatal),
                                            context,
                                            budget,
                                            () -> {}))
                            .isInstanceOf(com.agentos.core.audit.AuditWriteException.class);
                    assertThat(executions).hasValue(1);
                    assertThat(seen).containsExactly("one");
                });
    }

    private ToolCallback callback(
            String name, boolean direct, List<String> seen, AtomicInteger count) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("test")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(direct).build();
            }

            public String call(String input) {
                throw new AssertionError("需要上下文");
            }

            public String call(String input, ToolContext context) {
                var ledger =
                        (ToolCallLedger) context.getContext().get(ToolContextKeys.TOOL_CALL_LEDGER);
                seen.add(ledger.next(name).id());
                count.incrementAndGet();
                assertThat(context.getContext().get(ToolContextKeys.TURN_CONTEXT))
                        .isInstanceOf(TurnContext.class);
                return "result";
            }
        };
    }

    private AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private ChatResponse response(List<AssistantMessage.ToolCall> calls) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder().content("").toolCalls(calls).build())));
    }
}
