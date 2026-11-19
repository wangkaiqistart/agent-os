package com.agentos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.interaction.ApprovalDecision;
import com.agentos.core.interaction.ApprovalRequest;
import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.ApprovalSubject;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.TurnExecutionBudget;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalInputBrokerApprovalTest {
    private static ToolExecutionScope scope() {
        return new ToolExecutionScope(
                null,
                "call",
                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                Duration.ofSeconds(5),
                () -> {});
    }

    private static ApprovalRequest request(boolean sessionGrant, Duration wait) {
        return new ApprovalRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "call",
                new ApprovalSubject(
                        ActionType.SHELL,
                        "执行命令",
                        List.of("程序  /usr/bin/git", "参数  status", "目录  ."),
                        "shell:/usr/bin/git status",
                        "「/usr/bin/git status」",
                        sessionGrant),
                System.nanoTime() + wait.toNanos());
    }

    private static ApprovalDecision answer(String line, boolean sessionGrant) {
        var output = new StringWriter();
        try (var broker =
                        new TerminalInputBroker(
                                new StringReader(line + "\n"), new PrintWriter(output));
                var scope = scope()) {
            return broker.approve(request(sessionGrant, Duration.ofSeconds(2)), scope);
        }
    }

    @Test
    void numberedChoicesShortcutsAndFeedbackFollowClaudeCodeStyle() {
        assertThat(answer("1", true).outcome()).isEqualTo(ApprovalDecision.Outcome.ALLOW_ONCE);
        assertThat(answer("y", true).outcome()).isEqualTo(ApprovalDecision.Outcome.ALLOW_ONCE);
        assertThat(answer("2", true).outcome()).isEqualTo(ApprovalDecision.Outcome.ALLOW_SESSION);
        assertThat(answer("3", true).outcome()).isEqualTo(ApprovalDecision.Outcome.DENY);
        assertThat(answer("", true).outcome()).isEqualTo(ApprovalDecision.Outcome.DENY);
        // 没有会话级选项时编号顺延，2 即拒绝。
        assertThat(answer("2", false).outcome()).isEqualTo(ApprovalDecision.Outcome.DENY);
        var feedback = answer("先运行测试再提交", true);
        assertThat(feedback.outcome()).isEqualTo(ApprovalDecision.Outcome.DENY);
        assertThat(feedback.feedback()).isEqualTo("先运行测试再提交");
    }

    @Test
    void promptShowsSubjectAndOnlyOffersSessionGrantWhenAllowed() {
        var output = new StringWriter();
        try (var broker =
                        new TerminalInputBroker(new StringReader("1\n"), new PrintWriter(output));
                var scope = scope()) {
            broker.approve(request(false, Duration.ofSeconds(2)), scope);
        }
        assertThat(output.toString())
                .contains("需要授权：执行命令", "程序  /usr/bin/git", "1. 允许本次", "2. 拒绝")
                .doesNotContain("本会话不再询问");
    }

    @Test
    void timeoutDeniesAndLateLineIsDiscardedBeforeNextChat() throws Exception {
        var input = new PipedReader();
        var writer = new PipedWriter(input);
        var output = new StringWriter();
        try (var broker = new TerminalInputBroker(input, new PrintWriter(output, true));
                var scope = scope()) {
            assertThat(broker.approve(request(true, Duration.ofMillis(100)), scope).outcome())
                    .isEqualTo(ApprovalDecision.Outcome.TIMED_OUT);
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            try {
                var chat = executor.submit(broker::readChatLine);
                writer.write("1\n下一条消息\n");
                writer.flush();
                // 迟到的 "1" 不能被当成授权，也不能变成聊天输入。
                assertThat(chat.get(1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("下一条消息");
            } finally {
                writer.close();
                executor.shutdownNow();
            }
        }
        assertThat(output.toString()).contains("等待授权超时");
    }
}
