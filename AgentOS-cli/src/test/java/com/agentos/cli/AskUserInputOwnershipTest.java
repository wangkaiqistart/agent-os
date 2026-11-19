package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.interaction.InteractionRequest;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.TurnExecutionBudget;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AskUserInputOwnershipTest {
    private ToolExecutionScope scope() {
        return new ToolExecutionScope(
                null,
                "call",
                TurnExecutionBudget.start(Duration.ofSeconds(3), Duration.ofSeconds(1)),
                Duration.ofSeconds(2),
                () -> {});
    }

    private InteractionRequest request(ToolExecutionScope s) {
        return new InteractionRequest(
                java.util.UUID.randomUUID(), null, "call", "问题", s.deadlineNanos());
    }

    @Test
    void chatAndAnswersUseTheSameOwnerAndEofIsExplicit() throws Exception {
        try (var broker =
                        new CliInputBroker(
                                new StringReader("chat\nanswer\nnext\n"),
                                new PrintWriter(new StringWriter()));
                var s = scope()) {
            assertThat(broker.readChatLine()).isEqualTo("chat");
            assertThat(broker.ask(request(s), s))
                    .isEqualTo(new InteractionResult(InteractionResult.Status.ANSWERED, "answer"));
            assertThat(broker.readChatLine()).isEqualTo("next");
            assertThat(broker.ask(request(s), s).status()).isEqualTo(InteractionResult.Status.EOF);
        }
    }

    @Test
    void overflowIsImmediateBoundedAndLateLineRemainsOwnedUntilDiscarded() throws Exception {
        var input = new PipedReader(256);
        var writer = new PipedWriter(input);
        try (var broker = new CliInputBroker(input, new PrintWriter(new StringWriter()));
                var s = scope()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var prefixWritten = new java.util.concurrent.CountDownLatch(1);
            var feedRemainder = new java.util.concurrent.CountDownLatch(1);
            var remainderWritten = new java.util.concurrent.CountDownLatch(1);
            var stopFeeding = new java.util.concurrent.CountDownLatch(1);
            try {
                var answer = executor.submit(() -> broker.ask(request(s), s));
                var feeding =
                        executor.submit(() -> {
                            try {
                                writer.write("x".repeat(100000));
                                writer.flush();
                                prefixWritten.countDown();
                                // PipedReader 会将最后写线程死亡视为管道失效；整条输入由同一存活线程提供。
                                assertThat(feedRemainder.await(1, TimeUnit.SECONDS)).isTrue();
                                writer.write("\nnext\n");
                                writer.flush();
                                remainderWritten.countDown();
                                stopFeeding.await(1, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                assertThat(answer.get(1, TimeUnit.SECONDS).status())
                        .isEqualTo(InteractionResult.Status.TOO_LONG);
                assertThat(broker.retainedCharacters()).isLessThanOrEqualTo(4001);
                assertThat(broker.activeReaders()).isEqualTo(1);
                assertThat(broker.ask(request(s), s).status())
                        .isEqualTo(InteractionResult.Status.BUSY);
                assertThat(prefixWritten.await(1, TimeUnit.SECONDS)).isTrue();
                feedRemainder.countDown();
                assertThat(remainderWritten.await(1, TimeUnit.SECONDS)).isTrue();
                long until = System.nanoTime() + 1000000000L;
                while (broker.activeReaders() != 0 && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                assertThat(broker.readChatLine()).isEqualTo("next");
                stopFeeding.countDown();
                feeding.get(1, TimeUnit.SECONDS);
            } finally {
                feedRemainder.countDown();
                stopFeeding.countDown();
                writer.close();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void cancellationStopsWaitingAndLateAnswerCannotReachNextRequest() throws Exception {
        var input = new PipedReader();
        var writer = new PipedWriter(input);
        try (var broker = new CliInputBroker(input, new PrintWriter(new StringWriter()));
                var s = scope()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var answer = executor.submit(() -> broker.ask(request(s), s));
                long until = System.nanoTime() + 1000000000L;
                while (broker.activeReaders() == 0 && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                s.budget().cancel();
                assertThatThrownBy(() -> answer.get(1, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(
                                com.agentos.core.turn.TurnDeadlineExceededException.class);
                try (var next = scope()) {
                    assertThat(broker.ask(request(next), next).status())
                            .isEqualTo(InteractionResult.Status.BUSY);
                }
                writer.write("late\nreal\n");
                writer.flush();
                until = System.nanoTime() + 1000000000L;
                while (broker.activeReaders() != 0 && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                assertThat(broker.readChatLine()).isEqualTo("real");
            } finally {
                writer.close();
                executor.shutdownNow();
            }
        }
    }
}
