package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnDeadlineExceededException;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalHttpServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 验证 HTTP 响应头之后的 body 消费也共享同一个轮次预算。 */
class HttpToolsDeadlineTest {
    @Test
    void slowContinuousBodyIsCancelledAtSharedDeadline() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "AgentOS-test",
                                        3,
                                        1024 * 1024,
                                        Duration.ofSeconds(2),
                                        10_000),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.route(
                    "/slow",
                    exchange -> {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('a');
                        exchange.getResponseBody().flush();
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        exchange.getResponseBody().write('b');
                    });
            var budget = TurnExecutionBudget.start(Duration.ofMillis(120));
            assertThatThrownBy(() ->
                            tools.http_get(
                                    server.url("/slow"),
                                    new ToolContext(Map.of(ToolContextKeys.TURN_BUDGET, budget))))
                    .isInstanceOf(TurnDeadlineExceededException.class);
        }
    }

    @Test
    void expiredBudgetDoesNotSendHttpRequest() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "AgentOS-test",
                                        3,
                                        1024,
                                        Duration.ofSeconds(2),
                                        10_000),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.text("/never", 200, "unexpected");
            var budget = TurnExecutionBudget.start(Duration.ofNanos(1));
            while (!budget.expired()) {
                Thread.onSpinWait();
            }
            assertThatThrownBy(() ->
                            tools.http_get(
                                    server.url("/never"),
                                    new ToolContext(Map.of(ToolContextKeys.TURN_BUDGET, budget))))
                    .isInstanceOf(TurnDeadlineExceededException.class);
            org.assertj.core.api.Assertions.assertThat(server.calls).hasValue(0);
        }
    }

    @Test
    void sustainedBodyP95RemainsBoundedAcrossTwentyRequestsWithoutRetry() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "test",
                                        3,
                                        1048576,
                                        Duration.ofSeconds(2),
                                        10000),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.route(
                    "/chunks",
                    e -> {
                        e.sendResponseHeaders(200, 0);
                        try {
                            for (int i = 0; i < 100; i++) {
                                e.getResponseBody().write('a');
                                e.getResponseBody().flush();
                                Thread.sleep(5);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            var samples = new java.util.ArrayList<Long>();
            for (int n = 0; n < 20; n++) {
                var budget = TurnExecutionBudget.start(Duration.ofMillis(100));
                long start = System.nanoTime();
                assertThatThrownBy(() ->
                                tools.http_get(
                                        server.url("/chunks"),
                                        new ToolContext(
                                                Map.of(ToolContextKeys.TURN_BUDGET, budget))))
                        .isInstanceOf(TurnDeadlineExceededException.class);
                samples.add(
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - start));
            }
            samples.sort(Long::compareTo);
            org.assertj.core.api.Assertions.assertThat(samples.get(18)).isLessThanOrEqualTo(1100);
            org.assertj.core.api.Assertions.assertThat(server.calls).hasValue(20);
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info(
                            "deadline_acceptance scenario=slow_body samples=20 budget_ms=100 p95_ms={}",
                            samples.get(18));
        }
    }
}
