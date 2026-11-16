package com.agentos.tool.govern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.audit.AuditWriteException;
import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.TurnDeadlineExceededException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentOsToolExecutionExceptionProcessorTest {
    @Test
    void fatalErrorsPassThroughRealOfficialManagerWhileBusinessErrorIsSafe() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class))
                .withBean(
                        ToolExecutionExceptionProcessor.class,
                        AgentOsToolExecutionExceptionProcessor::new)
                .run(ctx -> {
                    for (RuntimeException error :
                            List.of(
                                    new AuditWriteException("internal token=secret"),
                                    new LeaseLostException(),
                                    new TurnDeadlineExceededException(),
                                    new IllegalStateException("password=secret"))) {
                        ToolCallback callback =
                                new ToolCallback() {
                                    public ToolDefinition getToolDefinition() {
                                        return GovernedToolCallbackTest.DEF;
                                    }

                                    public String call(String input) {
                                        throw new ToolExecutionException(
                                                getToolDefinition(), error);
                                    }
                                };
                        var prompt =
                                new Prompt(
                                        List.of(new UserMessage("hi")),
                                        ToolCallingChatOptions.builder()
                                                .toolCallbacks(callback)
                                                .build());
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
                                                                                        "http_get",
                                                                                        "{}")))
                                                                .build())));
                        if (error instanceof IllegalStateException) {
                            assertThat(
                                            ctx.getBean(ToolCallingManager.class)
                                                    .executeToolCalls(prompt, response)
                                                    .conversationHistory()
                                                    .getLast()
                                                    .getText())
                                    .doesNotContain("secret");
                        } else {
                            assertThatThrownBy(() ->
                                            ctx.getBean(ToolCallingManager.class)
                                                    .executeToolCalls(prompt, response))
                                    .isSameAs(error);
                        }
                    }
                });
    }
}
