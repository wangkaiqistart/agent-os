package com.agentos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.process.ShellToolProperties;
import com.agentos.tool.registry.ToolRegistry;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalMcpServers;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpFailureBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"stdio", "sse", "streamable-http"})
    void oversizedAndDisconnectedResponsesNeverReplay(String mode) throws Exception {
        for (String fault : List.of("largeResponse", "disconnectCall")) {
            try (var fixture = new LocalMcpServers(mode);
                    var scope =
                            new ToolExecutionScope(
                                    null,
                                    "init",
                                    TurnExecutionBudget.start(Duration.ofSeconds(3)),
                                    Duration.ofSeconds(3),
                                    () -> {})) {
                fixture.control(Map.of(fault, true));
                var config = fixture.configuration("fault");
                var transport =
                        mode.equals("stdio")
                                ? new ManagedStdioTransport(
                                        config,
                                        ShellToolProperties.defaults(
                                                List.of(
                                                        new ShellToolProperties.Command(
                                                                LocalMcpServers.python(),
                                                                List.of(fixture.directory()),
                                                                Map.of()))),
                                        scope)
                                : new McpHttpTransportFactory(
                                                new WhitelistSandbox(List.of("127.0.0.1")))
                                        .create(config);
                try (var client =
                        McpClient.sync(transport)
                                .initializationTimeout(Duration.ofMillis(500))
                                .requestTimeout(Duration.ofMillis(250))
                                .build()) {
                    client.initialize();
                    assertThatThrownBy(() ->
                                    client.callTool(
                                            new McpSchema.CallToolRequest("echo", Map.of())))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(fixture.stats().path("call").asInt()).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void redirectNeverReceivesCredentialsOrRequests() throws Exception {
        try (var source = new LocalMcpServers("sse");
                var target = new LocalMcpServers("streamable-http")) {
            source.control(Map.of("redirect", target.url() + "/mcp"));
            var transport =
                    new McpHttpTransportFactory(new WhitelistSandbox(List.of("127.0.0.1")))
                            .create(source.configuration("redirect"));
            try (var client =
                    McpClient.sync(transport)
                            .initializationTimeout(Duration.ofMillis(200))
                            .build()) {
                assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
            }
            assertThat(target.stats().path("requests").asInt()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shortDeadlineInvalidatesOnlyItsOriginalSourceAndShutdownStopsRecovery(boolean cancel)
            throws Exception {
        try (var slow = new LocalMcpServers("streamable-http");
                var healthy = new LocalMcpServers("streamable-http")) {
            slow.control(Map.of("callDelay", 2));
            var registry = new ToolRegistry(List.of());
            var service =
                    McpTestSupport.service(
                            List.of(slow.configuration("slow"), healthy.configuration("healthy")),
                            slow.directory(),
                            registry);
            service.start();
            McpTestSupport.available(service, "slow");
            McpTestSupport.available(service, "healthy");
            var generation = service.generation("slow");
            var callback =
                    registry.resolve(
                                    McpTestSupport.profile(
                                            List.of("mcp_slow__echo"), List.of("slow")))
                            .executionCallbacks()
                            .getFirst();
            var context =
                    McpTestSupport.context(
                            "mcp_slow__echo",
                            cancel ? Duration.ofSeconds(2) : Duration.ofMillis(150));
            var budget =
                    (TurnExecutionBudget) context.getContext().get(ToolContextKeys.TURN_BUDGET);
            var result = new CompletableFuture<String>();
            long begin = System.nanoTime();
            Thread.ofVirtual()
                    .start(() -> {
                        try {
                            result.complete(callback.call("{}", context));
                        } catch (RuntimeException failure) {
                            result.completeExceptionally(failure);
                        }
                    });
            if (cancel) {
                LocalMcpServers.waitFor(
                        () -> {
                            try {
                                return slow.stats().path("call").asInt() > 0;
                            } catch (java.io.IOException failure) {
                                return false;
                            }
                        },
                        500);
                budget.cancel();
            }
            assertThatThrownBy(() -> result.get(1150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - begin))
                    .isLessThan(Duration.ofMillis(1150));
            assertThat(generation.available()).isFalse();
            assertThat(slow.stats().path("call").asInt()).isEqualTo(1);
            var good =
                    registry.resolve(
                                    McpTestSupport.profile(
                                            List.of("mcp_healthy__echo"), List.of("healthy")))
                            .executionCallbacks()
                            .getFirst();
            assertThat(
                            good.call(
                                    "{}",
                                    McpTestSupport.context(
                                            "mcp_healthy__echo", Duration.ofSeconds(1))))
                    .contains("echo");
            service.close();
            assertThat(service.sources())
                    .allSatisfy(s ->
                            assertThat(s.state())
                                    .isEqualTo(
                                            com.agentos.core.tool.ToolSourceDescriptor.State
                                                    .CLOSED));
            int before = slow.stats().path("initialize").asInt();
            Thread.sleep(1100);
            assertThat(slow.stats().path("initialize").asInt()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdio", "streamable-http"})
    void remoteBusinessErrorKeepsConnectionAndReturnsRemoteText(String mode) throws Exception {
        try (var fixture = new LocalMcpServers(mode)) {
            var registry = new ToolRegistry(List.of());
            try (var service =
                    McpTestSupport.service(
                            List.of(fixture.configuration("biz")), fixture.directory(), registry)) {
                service.start();
                McpTestSupport.available(service, "biz");
                var generation = service.generation("biz");
                var callback =
                        registry.resolve(
                                        McpTestSupport.profile(
                                                List.of("mcp_biz__echo"), List.of("biz")))
                                .executionCallbacks()
                                .getFirst();
                fixture.control(Map.of("isError", true, "errorText", "REMOTE_BIZ_SENTINEL"));
                assertThatThrownBy(() ->
                                callback.call(
                                        "{}",
                                        McpTestSupport.context(
                                                "mcp_biz__echo", Duration.ofSeconds(2))))
                        .hasMessageContaining("REMOTE_ERROR")
                        .hasMessageContaining("REMOTE_BIZ_SENTINEL");
                // 远端业务错误不是断连：同一代次保持可用，下一次调用直接成功，不触发重连。
                assertThat(generation.available()).isTrue();
                assertThat(service.generation("biz")).isSameAs(generation);
                fixture.control(Map.of());
                assertThat(
                                callback.call(
                                        "{}",
                                        McpTestSupport.context(
                                                "mcp_biz__echo", Duration.ofSeconds(2))))
                        .contains("echo");
                assertThat(fixture.stats().path("call").asInt()).isEqualTo(2);
                assertThat(fixture.stats().path("initialize").asInt()).isEqualTo(1);
            }
        }
    }

    @Test
    void stdioDoesNotInheritUnlistedHostEnvironment() throws Exception {
        try (var fixture = new LocalMcpServers("stdio")) {
            fixture.control(Map.of("inspectEnv", List.of("HOME", "PATH", "ALLOW_BUSINESS")));
            var original = fixture.configuration("env");
            var config =
                    new McpServerConfig(
                            original.name(),
                            original.transport(),
                            original.command(),
                            original.args(),
                            original.cwd(),
                            Map.of("ALLOW_BUSINESS", "fixture-visible"),
                            null,
                            null,
                            Map.of(),
                            original.policy(),
                            Map.of());
            var registry = new ToolRegistry(List.of());
            try (var service =
                    McpTestSupport.service(List.of(config), fixture.directory(), registry)) {
                service.start();
                McpTestSupport.available(service, "env");
                var callback =
                        registry.resolve(
                                        McpTestSupport.profile(
                                                List.of("mcp_env__echo"), List.of("env")))
                                .executionCallbacks()
                                .getFirst();
                assertThat(
                                callback.call(
                                        "{}",
                                        McpTestSupport.context(
                                                "mcp_env__echo", Duration.ofSeconds(1))))
                        .contains("fixture-visible", "\\\"HOME\\\": null", "\\\"PATH\\\": null");
            }
            LocalMcpServers.waitFor(
                    () -> {
                        try {
                            return fixture.stats().path("closed").asInt() > 0;
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    },
                    1000);
        }
    }
}
