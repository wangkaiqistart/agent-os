package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.LeaseLostException;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalHttpServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpPostDeadlineTest {
    @Test
    void receivedThenDisconnectedOrSlowResponseIsNeverResent() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                HttpPostContractTest.properties(),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.route("/disconnect", e -> e.getRequestBody().readAllBytes());
            server.route(
                    "/headers",
                    e -> {
                        e.getRequestBody().readAllBytes();
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        e.sendResponseHeaders(200, -1);
                    });
            var probe = server.slowBody("/body");
            for (String path : List.of("/disconnect", "/headers", "/body")) {
                int before = server.calls.get();
                try (var scope = HttpPostContractTest.scope(Duration.ofMillis(150))) {
                    assertThatThrownBy(() ->
                                    tools.http_post(
                                            server.url(path),
                                            "{}",
                                            null,
                                            HttpPostContractTest.context(scope)))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(scope.budget().submissionUnconfirmed()).isTrue();
                    assertThat(scope.executionState())
                            .isEqualTo(ToolExecutionScope.ExecutionState.POSSIBLY_STARTED);
                }
                assertThat(server.calls.get() - before).isEqualTo(1);
            }
            assertThat(probe.awaitCancelledAndClosed(Duration.ofSeconds(1))).isTrue();
        }
    }

    @Test
    void leaseLossAfterBodyArrivalKeepsExplicitSubmissionPending() throws Exception {
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                HttpPostContractTest.properties(),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.route(
                    "/lease",
                    exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        lost.set(true);
                        try {
                            release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            var budget = com.agentos.core.turn.TurnExecutionBudget.start(Duration.ofSeconds(2));
            try (var scope =
                    new ToolExecutionScope(
                            null,
                            "lease-after-body",
                            budget,
                            Duration.ofSeconds(2),
                            () -> {
                                if (lost.get()) {
                                    throw new LeaseLostException();
                                }
                            })) {
                assertThatThrownBy(() ->
                                tools.http_post(
                                        server.url("/lease"),
                                        "{}",
                                        null,
                                        HttpPostContractTest.context(scope)))
                        .isInstanceOf(LeaseLostException.class);
                assertThat(lost).isTrue();
                assertThat(server.calls).hasValue(1);
                assertThat(budget.submissionUnconfirmed()).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cancellationAndLeaseLossStopWaitingAndCauseZeroFurtherSubmissions() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                HttpPostContractTest.properties(),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            var probe = server.slowBody("/slow");
            try (var scope = HttpPostContractTest.scope(Duration.ofSeconds(2))) {
                Thread.ofVirtual()
                        .start(() -> {
                            try {
                                while (server.calls.get() == 0) {
                                    Thread.sleep(2);
                                }
                                scope.budget().cancel();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                assertThatThrownBy(() ->
                                tools.http_post(
                                        server.url("/slow"),
                                        "{}",
                                        null,
                                        HttpPostContractTest.context(scope)))
                        .isInstanceOf(TurnDeadlineExceededException.class);
                assertThat(server.calls).hasValue(1);
                assertThat(scope.budget().submissionUnconfirmed()).isTrue();
            }
            assertThat(probe.awaitCancelledAndClosed(Duration.ofSeconds(1))).isTrue();
            var budget = com.agentos.core.turn.TurnExecutionBudget.start(Duration.ofSeconds(1));
            try (var scope =
                    new ToolExecutionScope(
                            null,
                            "lease",
                            budget,
                            Duration.ofSeconds(1),
                            () -> {
                                throw new LeaseLostException();
                            })) {
                assertThatThrownBy(() ->
                                tools.http_post(
                                        server.url("/slow"),
                                        "{}",
                                        null,
                                        HttpPostContractTest.context(scope)))
                        .isInstanceOf(LeaseLostException.class);
                assertThat(budget.submissionUnconfirmed()).isFalse();
            }
            assertThat(server.calls).hasValue(1);
        }
    }
}
