package com.agentos.tool.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.interaction.InteractionPort;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.provider.Profile;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnContext;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnEventPublisher;
import com.agentos.core.turn.TurnExecutionBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ToolContext;

/** ask_user 发出提问 / 结果事件：问题已脱敏，答案正文不进事件。 */
class AskUserToolsEventsTest {
    private final List<TurnEventPayload> events = new ArrayList<>();

    private ToolContext context(InteractionPort port) {
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        var turn =
                new TurnContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new LoadedAgent(
                                new AgentProfile(
                                        "a",
                                        "",
                                        new Profile(null, "m", null, null, null),
                                        List.of("ask_user"),
                                        null),
                                "",
                                Map.of()),
                        "cli",
                        1,
                        Instant.now().plusSeconds(5));
        var scope =
                new ToolExecutionScope(turn, "call-ask", budget, Duration.ofSeconds(5), () -> {});
        TurnEventPublisher publisher = events::add;
        return new ToolContext(
                Map.of(
                        ToolContextKeys.TOOL_SCOPE,
                        scope,
                        ToolContextKeys.RUNTIME_CAPABILITIES,
                        new TurnRuntimeCapabilities(port),
                        ToolContextKeys.TURN_EVENTS,
                        publisher));
    }

    @ParameterizedTest
    @CsvSource({
        "ANSWERED, ANSWERED",
        "TIMED_OUT, TIMED_OUT",
        "UNSUPPORTED, UNATTENDED",
        "CANCELLED, CANCELLED",
        "TOO_LONG, TOO_LONG",
        "EOF, CANCELLED"
    })
    void publishesRequestAndOutcomeWithoutAnswerText(
            InteractionResult.Status status, TurnEventPayload.InputOutcome expected) {
        var tools =
                new AskUserTools(
                        new InteractionToolProperties(null, null, null),
                        new Redactor(() -> List.of("KNOWN_SECRET")));
        InteractionPort port =
                (request, scope) ->
                        status == InteractionResult.Status.ANSWERED
                                ? new InteractionResult(status, "ANSWER_SENTINEL")
                                : InteractionResult.of(status);
        var context = context(port);
        if (status == InteractionResult.Status.ANSWERED) {
            assertThat(tools.ask_user("城市？KNOWN_SECRET", context)).contains("ANSWER_SENTINEL");
        } else {
            assertThatThrownBy(() -> tools.ask_user("城市？KNOWN_SECRET", context))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThat(events).hasSize(2);
        var requested = (TurnEventPayload.InputRequested) events.get(0);
        var resolved = (TurnEventPayload.InputResolved) events.get(1);
        assertThat(requested.toolCallId()).isEqualTo("call-ask");
        assertThat(requested.question()).startsWith("城市？").doesNotContain("KNOWN_SECRET");
        assertThat(resolved.requestId()).isEqualTo(requested.requestId());
        assertThat(resolved.outcome()).isEqualTo(expected);
        assertThat(events.toString()).doesNotContain("ANSWER_SENTINEL");
    }
}
