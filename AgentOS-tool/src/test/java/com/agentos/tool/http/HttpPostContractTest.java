package com.agentos.tool.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalHttpServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

class HttpPostContractTest {
    static HttpToolProperties properties() {
        return new HttpToolProperties(
                List.of("127.0.0.1"), null, 3, 1048576, Duration.ofSeconds(2), 10000);
    }

    static ToolContext context(ToolExecutionScope scope) {
        return new ToolContext(Map.of(ToolContextKeys.TOOL_SCOPE, scope));
    }

    static ToolExecutionScope scope(Duration timeout) {
        return new ToolExecutionScope(
                null, "post", TurnExecutionBudget.start(Duration.ofSeconds(5)), timeout, () -> {});
    }

    @Test
    void utf8ByteCeilingAndStructuredResultTruncationAreIndependent() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(
                                new HttpToolProperties(
                                        List.of("127.0.0.1"),
                                        null,
                                        0,
                                        1048576,
                                        Duration.ofSeconds(2),
                                        256,
                                        32),
                                new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.text("/large-text", 200, "正文".repeat(1000));
            try (var scope = scope(Duration.ofSeconds(1))) {
                String result =
                        tools.http_post(
                                server.url("/large-text"),
                                "中".repeat(10) + "ab",
                                "text/plain",
                                context(scope));
                assertThat(result.length()).isLessThanOrEqualTo(256);
                var parsed = JsonMapper.builder().build().readTree(result);
                assertThat(parsed.path("truncated").asBoolean()).isTrue();
                assertThat(parsed.path("completion").asText()).isEqualTo("CONFIRMED");
            }
            try (var scope = scope(Duration.ofSeconds(1))) {
                assertThatThrownBy(() ->
                                tools.http_post(
                                        server.url("/large-text"),
                                        "中".repeat(11),
                                        "text/plain",
                                        context(scope)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(server.calls).hasValue(1);
            var callback =
                    java.util.Arrays.stream(
                                    com.agentos.tool.registry.BuiltinToolCallbacks.from(tools))
                            .filter(cb -> cb.getToolDefinition().name().equals("http_post"))
                            .findFirst()
                            .orElseThrow();
            var schema =
                    JsonMapper.builder()
                            .build()
                            .readTree(callback.getToolDefinition().inputSchema());
            assertThat(schema.path("properties").propertyNames())
                    .containsExactlyInAnyOrder("url", "body", "content_type");
            assertThat(schema.path("required").toString()).doesNotContain("content_type");
        }
    }

    @Test
    void utf8TextAndJsonHaveOneExactSubmissionAndBusinessFailureIsStillTransportSuccess()
            throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(properties(), new WhitelistSandbox(List.of("127.0.0.1")))) {
            var received = new AtomicReference<String>();
            var type = new AtomicReference<String>();
            server.route(
                    "/post",
                    e -> {
                        received.set(
                                new String(
                                        e.getRequestBody().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8));
                        type.set(e.getRequestHeaders().getFirst("Content-Type"));
                        byte[] body =
                                "{\"success\":false}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, body.length);
                        e.getResponseBody().write(body);
                    });
            for (String contentType : List.of("text/plain", "application/json", "default")) {
                try (var scope = scope(Duration.ofSeconds(1))) {
                    String body = contentType.equals("text/plain") ? "中文正文" : "{\"中文\":true}";
                    var result =
                            JsonMapper.builder()
                                    .build()
                                    .readTree(
                                            tools.http_post(
                                                    server.url("/post"),
                                                    body,
                                                    contentType.equals("default")
                                                            ? null
                                                            : contentType,
                                                    context(scope)));
                    assertThat(result.path("status").asInt()).isEqualTo(200);
                    assertThat(result.path("completion").asText()).isEqualTo("CONFIRMED");
                    assertThat(result.path("body").asText()).contains("false");
                    assertThat(received.get()).isEqualTo(body);
                    assertThat(type.get())
                            .contains(
                                    contentType.equals("text/plain")
                                            ? "text/plain"
                                            : "application/json",
                                    "UTF-8");
                }
            }
            assertThat(server.calls).hasValue(3);
        }
    }

    @Test
    void invalidInputAndDeniedTargetsDoNotReachTheServer() throws Exception {
        try (var server = new LocalHttpServer();
                var tools =
                        new HttpTools(properties(), new WhitelistSandbox(List.of("127.0.0.1")))) {
            server.text("/post", 200, "unused");
            for (var test :
                    List.of(
                            List.of(server.url("/post"), "{bad", "application/json"),
                            List.of(server.url("/post"), "x", "image/png"),
                            List.of(server.url("/post"), "x".repeat(1048577), "text/plain"),
                            List.of(
                                    server.url("/post").replace("http:", "ftp:"),
                                    "{}",
                                    "application/json"),
                            List.of(
                                    server.url("/post")
                                            .replace("127.0.0.1", "user:private@127.0.0.1"),
                                    "{}",
                                    "application/json"),
                            List.of(
                                    server.url("/post").replace("127.0.0.1", "localhost"),
                                    "{}",
                                    "application/json"))) {
                try (var scope = scope(Duration.ofSeconds(1))) {
                    assertThatThrownBy(() ->
                                    tools.http_post(
                                            test.get(0), test.get(1), test.get(2), context(scope)))
                            .isInstanceOf(RuntimeException.class)
                            .hasMessageNotContaining("private");
                }
            }
            assertThat(server.calls).hasValue(0);
        }
    }

    @Test
    void redirectsAndErrorOrLargeBodiesNeverLeakOrFollow() throws Exception {
        try (var server = new LocalHttpServer();
                var target = new LocalHttpServer();
                var tools =
                        new HttpTools(properties(), new WhitelistSandbox(List.of("127.0.0.1")))) {
            target.text("/target", 200, "unused");
            server.redirect("/redirect", target.url("/target"));
            server.text("/error", 500, "REMOTE_PRIVATE_SENTINEL");
            server.text("/large", 200, "REMOTE_PRIVATE_SENTINEL" + "x".repeat(1048576));
            for (String path : List.of("/redirect", "/error", "/large")) {
                try (var scope = scope(Duration.ofSeconds(1))) {
                    assertThatThrownBy(() ->
                                    tools.http_post(server.url(path), "{}", null, context(scope)))
                            .isInstanceOf(RuntimeException.class)
                            .hasMessageNotContaining("REMOTE_PRIVATE_SENTINEL");
                }
            }
            assertThat(server.calls).hasValue(3);
            assertThat(target.calls).hasValue(0);
        }
    }
}
