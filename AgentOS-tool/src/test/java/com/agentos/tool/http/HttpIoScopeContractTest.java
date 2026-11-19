package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.support.LocalHttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpIoScopeContractTest {
    private ToolExecutionScope scope(Duration timeout) {
        return new ToolExecutionScope(
                null,
                "original",
                TurnExecutionBudget.start(Duration.ofSeconds(5)),
                timeout,
                () -> {});
    }

    @Test
    void exactByteBoundarySucceedsAndExtraByteFailsWithoutResponseLeak() throws Exception {
        try (var server = new LocalHttpServer();
                var client = HttpClient.newHttpClient()) {
            server.text("/exact", 200, "x".repeat(32));
            server.text("/over", 200, "SECRET" + "x".repeat(32));
            assertThat(
                            BoundedHttpIo.exchange(
                                            client,
                                            HttpRequest.newBuilder(URI.create(server.url("/exact")))
                                                    .build(),
                                            scope(Duration.ofSeconds(2)),
                                            32)
                                    .body())
                    .hasSize(32);
            assertThatThrownBy(() ->
                            BoundedHttpIo.exchange(
                                    client,
                                    HttpRequest.newBuilder(
                                                    URI.create(server.url("/over?token=SECRET")))
                                            .build(),
                                    scope(Duration.ofSeconds(2)),
                                    32))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("上限")
                    .hasMessageNotContaining("SECRET");
            assertThat(server.calls).hasValue(2);
        }
    }

    @Test
    void continuousBodyUsesOriginalScopeAndActuallyCancelsPeer() throws Exception {
        try (var server = new LocalHttpServer();
                var client = HttpClient.newHttpClient()) {
            var probe = server.slowBody("/slow");
            var scope = scope(Duration.ofMillis(150));
            long started = System.nanoTime();
            assertThatThrownBy(() ->
                            BoundedHttpIo.exchange(
                                    client,
                                    HttpRequest.newBuilder(URI.create(server.url("/slow"))).build(),
                                    scope,
                                    1048576))
                    .isInstanceOf(ToolExecutionScope.ToolTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(1150));
            assertThat(probe.awaitCancelledAndClosed(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.headersSent()).isTrue();
        }
    }
}
