package com.agentos.tool.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.Redactor;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BraveSearchProviderContractTest {
    @Test
    void actualProtocolMapsResultsAndNeverRequestsHitPagesOrRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var hits = new AtomicInteger();
        server.createContext(
                "/search",
                exchange -> {
                    requests.incrementAndGet();
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    assertThat(exchange.getRequestURI().getRawQuery())
                            .contains("q=hello+world", "count=1");
                    assertThat(exchange.getRequestHeaders().getFirst("X-Subscription-Token"))
                            .isEqualTo("search-secret-sentinel");
                    byte[] bytes =
                            "{\"web\":{\"results\":[{\"title\":\"title search-secret-sentinel\",\"url\":\"https://example.org/item\",\"description\":\"summary\"},{\"title\":\"extra\",\"url\":\"https://example.org/extra\",\"description\":\"summary\"}]}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/hits",
                exchange -> {
                    hits.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/hits");
                    exchange.sendResponseHeaders(307, -1);
                    exchange.close();
                });
        server.createContext(
                "/fail",
                exchange -> {
                    byte[] bytes = "search-secret-sentinel".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var redactor = new Redactor(List::of);
            var sandbox = new WhitelistSandbox(List.of("127.0.0.1"));
            try (var s = WebSearchContractTest.scope()) {
                var result =
                        new BraveSearchProvider(
                                        WebSearchContractTest.config(
                                                url + "/search", "search-secret-sentinel"),
                                        sandbox,
                                        redactor)
                                .search("hello world", 1, s);
                assertThat(result.items()).hasSize(1);
                assertThat(result.truncated()).isTrue();
                assertThat(result.toString()).doesNotContain("search-secret-sentinel");
            }
            for (String path : List.of("/redirect", "/fail")) {
                try (var s = WebSearchContractTest.scope()) {
                    assertThatThrownBy(() ->
                                    new BraveSearchProvider(
                                                    WebSearchContractTest.config(
                                                            url + path, "search-secret-sentinel"),
                                                    sandbox,
                                                    redactor)
                                            .search("q", 1, s))
                            .hasMessageContaining("FAILED")
                            .hasMessageNotContaining("search-secret-sentinel");
                }
            }
            try (var s = WebSearchContractTest.scope()) {
                assertThatThrownBy(() ->
                                new BraveSearchProvider(
                                                WebSearchContractTest.config(
                                                        url + "/search", "key"),
                                                new WhitelistSandbox(List.of()),
                                                redactor)
                                        .search("q", 1, s))
                        .isInstanceOf(com.agentos.core.tool.SandboxViolationException.class);
            }
            assertThat(requests).hasValue(1);
            assertThat(hits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyMalformedUnsafeAndOversizedResponsesRemainDistinct() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies =
                java.util.Map.of(
                        "/empty",
                        "{\"web\":{\"results\":[]}}",
                        "/bad",
                        "{\"web\":{\"results\":\"search-secret-sentinel\"}}",
                        "/big",
                        "x".repeat(2048),
                        "/unsafe",
                        "{\"web\":{\"results\":[{\"title\":\"unsafe\",\"url\":\"https://user:password@example.org\",\"description\":\"text\"}]}}");
        bodies.forEach((path, body) ->
                server.createContext(
                        path,
                        exchange -> {
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                            exchange.close();
                        }));
        server.start();
        try {
            for (String path : bodies.keySet()) {
                try (var scope = WebSearchContractTest.scope()) {
                    var provider =
                            new BraveSearchProvider(
                                    new SearchProviderConfig(
                                            "brave",
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + path,
                                            "search-secret-sentinel",
                                            1000,
                                            10,
                                            1024,
                                            java.time.Duration.ofSeconds(2)),
                                    new WhitelistSandbox(List.of("127.0.0.1")),
                                    new Redactor(List::of));
                    if (path.equals("/bad") || path.equals("/big")) {
                        assertThatThrownBy(() -> provider.search("query", 10, scope))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageNotContaining("search-secret-sentinel");
                    } else {
                        var result = provider.search("query", 10, scope);
                        assertThat(result.items()).isEmpty();
                        assertThat(result.complete()).isTrue();
                        assertThat(result.truncated()).isEqualTo(path.equals("/unsafe"));
                    }
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
