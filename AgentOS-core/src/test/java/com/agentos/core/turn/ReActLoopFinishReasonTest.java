package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemoryMessageStore;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.support.ScriptedProviderService;
import com.agentos.core.tool.ToolCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 结束原因为 length / content_filter 时本轮以错误码失败：不执行工具、不写助手消息、不重试。 */
class ReActLoopFinishReasonTest {
    @TempDir Path workspace;

    private final ToolCallingManager manager = mock(ToolCallingManager.class);
    private final InMemoryMessageStore messages = new InMemoryMessageStore();
    private InMemoryTurnStore turns;

    private DefaultAgentService service(ScriptedProviderService provider) throws Exception {
        AgentFixtures.agent(workspace, "a");
        Files.writeString(
                workspace.resolve("agents/a/AGENT.md"),
                "---\nname: a\nprovider:\n  model: test-model\ntools: [weather]\n---\n助手\n");
        var sessions = new InMemorySessionManager();
        turns = new InMemoryTurnStore(sessions);
        ToolCallback weather =
                new ToolCallback() {
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("weather")
                                .description("天气")
                                .inputSchema("{\"type\":\"object\"}")
                                .build();
                    }

                    public String call(String input) {
                        throw new AssertionError("截断响应里的工具不应执行");
                    }
                };
        ToolCatalog catalog =
                new ToolCatalog() {
                    public void validate(AgentProfile p) {}

                    public List<ToolCallback> callbacks(AgentProfile p) {
                        return List.of(weather);
                    }
                };
        var loop =
                new ReActLoop(
                        provider,
                        messages,
                        new PromptBuilder(new ContextLoader(workspace)),
                        new TurnGuard(turns),
                        catalog,
                        new ToolExecutor(manager));
        return new DefaultAgentService(
                AgentFixtures.loader(workspace),
                new ContextLoader(workspace),
                mock(ProviderCatalog.class),
                sessions,
                turns,
                loop,
                new TurnBudgetRegistry());
    }

    private static ChatResponse response(String finishReason, boolean withToolCall) {
        var message =
                AssistantMessage.builder()
                        .content("半截回答")
                        .toolCalls(
                                withToolCall
                                        ? List.of(
                                                new AssistantMessage.ToolCall(
                                                        "call-1", "function", "weather", "{}"))
                                        : List.of())
                        .build();
        return new ChatResponse(
                List.of(
                        new Generation(
                                message,
                                ChatGenerationMetadata.builder()
                                        .finishReason(finishReason)
                                        .build())));
    }

    private static TurnSink sink(boolean streaming) {
        return new TurnSink() {
            @Override
            public boolean streaming() {
                return streaming;
            }
        };
    }

    @ParameterizedTest
    @CsvSource({
        "length, false, false, OUTPUT_TRUNCATED",
        "LENGTH, true, false, OUTPUT_TRUNCATED",
        "length, false, true, OUTPUT_TRUNCATED",
        "length, true, true, OUTPUT_TRUNCATED",
        "content_filter, false, false, CONTENT_FILTERED",
        "CONTENT_FILTER, true, false, CONTENT_FILTERED",
        "content_filter, true, true, CONTENT_FILTERED"
    })
    void truncatedOrFilteredResponseFailsWithCode(
            String finishReason, boolean streaming, boolean withToolCall, TurnFailureCode code)
            throws Exception {
        var provider = new ScriptedProviderService().enqueue(response(finishReason, withToolCall));
        var result =
                service(provider)
                        .process(new ProcessRequest("cli", "u", "a", "hi", "cli"), sink(streaming));

        assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo(code);
        assertThat(provider.calls()).isOne();
        verifyNoInteractions(manager);
        assertThat(messages.messages(result.turnId()))
                .noneMatch(message -> message instanceof AssistantMessage);
        assertThat(turns.result(result.turnId()).errorMessage()).startsWith(code.name() + "：");
    }

    @ParameterizedTest
    @CsvSource({"stop", "STOP"})
    void normalStopStillSucceeds(String finishReason) throws Exception {
        var provider = new ScriptedProviderService().enqueue(response(finishReason, false));
        var result =
                service(provider)
                        .process(new ProcessRequest("cli", "u", "a", "hi", "cli"), sink(false));

        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.failureCode()).isNull();
        assertThat(result.text()).isEqualTo("半截回答");
    }
}
