package com.agentos.core.turn;

import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderResult;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.session.MessageStore;
import com.agentos.core.session.TurnSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/** 无工具的最小 ReAct 循环；工具请求在后续故事由 ToolExecutor 接管。 */
public final class ReActLoop {
    private final ProviderService provider;
    private final MessageStore messages;
    private final PromptBuilder promptBuilder;
    private final TurnGuard guard;

    public ReActLoop(
            ProviderService provider,
            MessageStore messages,
            PromptBuilder promptBuilder,
            TurnGuard guard) {
        this.provider = Objects.requireNonNull(provider);
        this.messages = Objects.requireNonNull(messages);
        this.promptBuilder = Objects.requireNonNull(promptBuilder);
        this.guard = Objects.requireNonNull(guard);
    }

    public Result run(
            TurnSnapshot turn,
            LoadedAgent agent,
            String userMessage,
            TurnExecutionBudget budget,
            TurnSink sink) {
        List<Message> prompt = promptBuilder.build(agent, List.of(), userMessage);
        guard.checkpoint(turn, budget);
        var request =
                new ProviderRequest(
                        agent.profile().providerProfile(),
                        prompt,
                        List.of(),
                        Map.of("turnContext", turn.turnId().toString()),
                        budget.deadline(),
                        new CallCorrelation(turn.sessionId(), turn.turnId(), 1));
        ProviderResult result =
                sink.streaming() ? provider.stream(request, sink::onDelta) : provider.call(request);
        guard.checkpoint(turn, budget);
        var generation = result.response().getResults().getFirst();
        String text = generation.getOutput().getText();
        if (!generation.getOutput().getToolCalls().isEmpty()) {
            return new Result(TurnStatus.FAILED, "当前版本暂不支持工具调用", 1);
        }
        if (text == null || text.isBlank()) {
            return new Result(TurnStatus.FAILED, "模型返回了空回答", 1);
        }
        messages.append(turn, 1, List.of(new AssistantMessage(text)));
        return new Result(TurnStatus.SUCCEEDED, text, 1);
    }

    public record Result(TurnStatus status, String text, int iterations) {}
}
