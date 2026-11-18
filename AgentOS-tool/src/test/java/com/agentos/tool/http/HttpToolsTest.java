package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.SandboxViolationException;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalHttpServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class HttpToolsTest {
    private ToolContext context() {
        return new ToolContext(
                Map.of(
                        ToolContextKeys.TURN_BUDGET,
                        TurnExecutionBudget.start(Duration.ofSeconds(4))));
    }

    private HttpTools tools(int cap, Duration timeout) {
        return new HttpTools(
                new HttpToolProperties(
                        List.of("127.0.0.1"), "AgentOS-test", 3, cap, timeout, 10_000),
                new WhitelistSandbox(List.of("127.0.0.1")));
    }

    @Test
    void getsBodyWithUserAgentAndFollowsExactlyThreeRelativeRedirects() throws Exception {
        try (var server = new LocalHttpServer();
                var tools = tools(1048576, Duration.ofSeconds(2))) {
            server.redirect("/one", "/two");
            server.redirect("/two", "/three");
            server.redirect("/three", "/ok");
            server.route(
                    "/ok",
                    e -> {
                        assertThat(e.getRequestHeaders().getFirst("User-Agent"))
                                .isEqualTo("AgentOS-test");
                        byte[] bytes = "天气晴".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                    });
            assertThat(tools.http_get(server.url("/one"), context())).isEqualTo("天气晴");
            assertThat(server.calls).hasValue(4);
        }
    }

    @Test
    void rejectsFourthHopAndOutsideHostBeforeSendingRequest() throws Exception {
        try (var allowed = new LocalHttpServer();
                var outside = new LocalHttpServer();
                var tools = tools(1048576, Duration.ofSeconds(2))) {
            outside.text("/secret", 200, "never");
            allowed.redirect("/outside", outside.url("/secret").replace("127.0.0.1", "localhost"));
            assertThatThrownBy(() -> tools.http_get(allowed.url("/outside"), context()))
                    .isInstanceOf(SandboxViolationException.class);
            assertThat(outside.calls).hasValue(0);
            allowed.redirect("/r0", "/r1");
            allowed.redirect("/r1", "/r2");
            allowed.redirect("/r2", "/r3");
            allowed.redirect("/r3", outside.url("/secret"));
            assertThatThrownBy(() -> tools.http_get(allowed.url("/r0"), context()))
                    .isInstanceOf(SandboxViolationException.class);
            assertThat(outside.calls).hasValue(0);
            allowed.redirect("/inner", "http://10.0.0.1/private");
            assertThatThrownBy(() -> tools.http_get(allowed.url("/inner"), context()))
                    .isInstanceOf(SandboxViolationException.class);
        }
    }

    @Test
    void missingLocationNon2xxAndOversizedBodyHaveSafeErrors() throws Exception {
        try (var server = new LocalHttpServer();
                var tools = tools(16, Duration.ofSeconds(2))) {
            server.redirect("/missing", null);
            server.text("/bad", 500, "BODY_SECRET");
            server.text("/big", 200, "a".repeat(17));
            for (String path : List.of("/missing", "/bad", "/big")) {
                assertThatThrownBy(() ->
                                tools.http_get(server.url(path) + "?token=URL_SECRET", context()))
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageNotContaining("BODY_SECRET")
                        .hasMessageNotContaining("URL_SECRET");
            }
        }
    }

    @Test
    void explicitZeroRedirectPolicyNeverFollowsTheNextHop() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        "test",
                                        0,
                                        1024,
                                        Duration.ofSeconds(2),
                                        10_000),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.redirect("/start", "/end");
            server.text("/end", 200, "unexpected");
            assertThatThrownBy(() -> tools.http_get(server.url("/start"), context()))
                    .isInstanceOf(SandboxViolationException.class);
            assertThat(server.calls).hasValue(1);
        }
    }

    @Test
    void bodyConsumptionSharesOneDeadlineInsteadOfResettingOnChunks() throws Exception {
        try (var server = new LocalHttpServer();
                var tools = tools(1048576, Duration.ofMillis(120))) {
            server.route(
                    "/slow",
                    e -> {
                        e.sendResponseHeaders(200, 0);
                        e.getResponseBody().write('a');
                        e.getResponseBody().flush();
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        e.getResponseBody().write('b');
                    });
            long start = System.nanoTime();
            assertThatThrownBy(() -> tools.http_get(server.url("/slow"), context()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.MILLISECONDS.toNanos(600));
        }
    }
}
