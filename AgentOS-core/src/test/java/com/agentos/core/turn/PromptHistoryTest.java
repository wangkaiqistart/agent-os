package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.agent.AgentSettings;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.agent.LoadedAgent;
import com.agentos.core.provider.Profile;
import com.agentos.core.session.HistoryTurn;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/** Prompt 历史按完整轮次恢复，并同时遵守轮次和 token 两道上限。 */
class PromptHistoryTest {
    @Test
    void keepsTheNewestTwentyCompleteTurns() throws Exception {
        PromptBuilder builder = builder();
        LoadedAgent agent = agent(new AgentSettings(10, 20, 100_000, Duration.ofMinutes(5)));
        List<HistoryTurn> history = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            history.add(
                    turn(
                            "u" + i,
                            new UserMessage("question-" + i),
                            new AssistantMessage("answer-" + i)));
        }

        List<Message> prompt = builder.build(agent, history, "current");

        assertThat(prompt).extracting(Message::getText).doesNotContain("question-1", "answer-1");
        assertThat(prompt)
                .extracting(Message::getText)
                .contains("question-2", "answer-21", "current");
    }

    @Test
    void tokenLimitDropsOldestWholeTurnsAndKeepsEqualBoundary() throws Exception {
        PromptBuilder builder = builder();
        List<Message> firstMessages =
                List.of(new UserMessage("old question"), new AssistantMessage("old answer"));
        List<Message> secondMessages =
                List.of(new UserMessage("new question"), new AssistantMessage("new answer"));
        int firstTokens = tokens(firstMessages);
        int secondTokens = tokens(secondMessages);
        int configured = (int) Math.ceil((firstTokens + secondTokens) / 0.9d);
        LoadedAgent agent = agent(new AgentSettings(10, 20, configured, Duration.ofMinutes(5)));

        List<Message> prompt =
                builder.build(
                        agent,
                        List.of(
                                turn("old", firstMessages.toArray(Message[]::new)),
                                turn("new", secondMessages.toArray(Message[]::new))),
                        "current");

        assertThat(prompt)
                .extracting(Message::getText)
                .contains("old question", "new answer", "current");
    }

    @Test
    void oversizedTurnIsDroppedWithoutTruncatingMessagesOrCurrentUser() throws Exception {
        PromptBuilder builder = builder();
        LoadedAgent agent = agent(new AgentSettings(10, 20, 1, Duration.ofMinutes(5)));
        List<Message> toolRound =
                List.of(
                        AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        "call-1",
                                                        "function",
                                                        "http_get",
                                                        "{\"url\":\"https://example.com\"}")))
                                .build(),
                        ToolResponseMessage.builder()
                                .responses(
                                        List.of(
                                                new ToolResponseMessage.ToolResponse(
                                                        "call-1", "http_get", "result")))
                                .build());

        List<Message> prompt =
                builder.build(
                        agent, List.of(turn("tool", toolRound.toArray(Message[]::new))), "current");

        assertThat(prompt)
                .extracting(Message::getText)
                .containsExactly(prompt.getFirst().getText(), "current");
    }

    @Test
    void failedTurnsOnlyExposeUserMessageAndSafeIncompleteNotice() throws Exception {
        PromptBuilder builder = builder();
        LoadedAgent agent = agent(new AgentSettings(10, 20, 1_000, Duration.ofMinutes(5)));
        HistoryTurn failed =
                new HistoryTurn(
                        UUID.randomUUID(),
                        "FAILED",
                        "database password=secret and internal stack",
                        List.of(
                                new UserMessage("keep me"),
                                new AssistantMessage("do not leak me")));

        List<Message> prompt = builder.build(agent, List.of(failed), "current");

        assertThat(prompt)
                .extracting(Message::getText)
                .contains("keep me", "current")
                .doesNotContain("do not leak me", "secret", "internal stack");
        assertThat(prompt.get(2).getText()).contains("上一轮处理未完成：处理失败");
    }

    @Test
    void zeroHistoryLimitsAndNinetyPercentFloorNeverLeakHistory() throws Exception {
        PromptBuilder builder = builder();
        HistoryTurn old =
                turn("old", new UserMessage("old question"), new AssistantMessage("old answer"));

        assertThat(
                        builder.build(
                                agent(new AgentSettings(10, 0, 100, Duration.ofMinutes(5))),
                                List.of(old),
                                "current"))
                .extracting(Message::getText)
                .containsExactly(
                        builder.build(
                                        agent(new AgentSettings(10, 0, 100, Duration.ofMinutes(5))),
                                        "current")
                                .getFirst()
                                .getText(),
                        "current");
        // max_history_tokens=1 has an effective floor budget of zero, so it also carries no
        // history.
        assertThat(
                        builder.build(
                                agent(new AgentSettings(10, 20, 1, Duration.ofMinutes(5))),
                                List.of(old),
                                "current"))
                .extracting(Message::getText)
                .endsWith("current")
                .doesNotContain("old question", "old answer");
    }

    @Test
    void refreshesBootstrapAndDateForEachPromptBuild() throws Exception {
        var workspace = Files.createTempDirectory("prompt-refresh");
        Files.writeString(workspace.resolve("AGENTS.md"), "bootstrap-v1");
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T23:59:00Z"));
        var context = new ContextLoader(workspace, new MutableClock(now));
        var builder = new PromptBuilder(context);
        LoadedAgent agent = agent(AgentSettings.defaults());

        String first = builder.build(agent, "one").getFirst().getText();
        Files.writeString(workspace.resolve("AGENTS.md"), "bootstrap-v2");
        now.set(Instant.parse("2026-01-02T00:01:00Z"));
        String second = builder.build(agent, "two").getFirst().getText();

        assertThat(first).contains("bootstrap-v1", "2026-01-01");
        assertThat(second).contains("bootstrap-v2", "2026-01-02").doesNotContain("bootstrap-v1");
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(AtomicReference<Instant> instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @Test
    void truncatedFilteredAndCancelledTurnsKeepOnlyUserMessageAndFixedNotice() throws Exception {
        PromptBuilder builder = builder();
        LoadedAgent agent = agent(new AgentSettings(10, 20, 100_000, Duration.ofMinutes(5)));
        List<HistoryTurn> history =
                List.of(
                        failed("t1", "FAILED", "OUTPUT_TRUNCATED：模型输出达到上限被截断", "q-truncated"),
                        failed("t2", "FAILED", "CONTENT_FILTERED：回答被内容安全策略过滤", "q-filtered"),
                        failed("t3", "CANCELLED", "本轮已取消", "q-cancelled"),
                        failed("t4", "FAILED", "内部处理失败：X", "q-other"));

        List<Message> prompt = builder.build(agent, history, "current");
        List<String> texts = prompt.stream().map(Message::getText).toList();

        assertThat(texts)
                .contains(
                        "q-truncated",
                        "上一轮处理未完成：上一轮回答因长度上限被截断",
                        "q-filtered",
                        "上一轮处理未完成：上一轮回答被内容策略过滤",
                        "q-cancelled",
                        "上一轮处理未完成：本轮已被用户取消",
                        "q-other",
                        "上一轮处理未完成：处理失败")
                .doesNotContain("PARTIAL_ANSWER")
                .noneMatch(text -> text != null && text.contains("OUTPUT_TRUNCATED"))
                .noneMatch(text -> text != null && text.contains("内部处理失败"));
    }

    private static HistoryTurn failed(String id, String status, String error, String question) {
        return new HistoryTurn(
                UUID.nameUUIDFromBytes(id.getBytes()),
                status,
                error,
                List.of(new UserMessage(question), new AssistantMessage("PARTIAL_ANSWER")));
    }

    private PromptBuilder builder() throws Exception {
        var workspace = Files.createTempDirectory("prompt-history");
        Files.writeString(workspace.resolve("AGENTS.md"), "bootstrap");
        return new PromptBuilder(new ContextLoader(workspace));
    }

    private LoadedAgent agent(AgentSettings settings) {
        return new LoadedAgent(
                new AgentProfile(
                        "a",
                        null,
                        new Profile(null, "model", null, null, null),
                        List.of(),
                        settings),
                "instructions",
                java.util.Map.of());
    }

    private static HistoryTurn turn(String id, Message... messages) {
        return new HistoryTurn(
                UUID.nameUUIDFromBytes(id.getBytes()), "SUCCEEDED", null, List.of(messages));
    }

    private static int tokens(List<Message> messages) {
        var estimator = new JTokkitTokenCountEstimator();
        return messages.stream()
                .mapToInt(message ->
                        estimator.estimate(
                                message.getText() == null ? message.toString() : message.getText()))
                .sum();
    }
}
