package com.agentos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.process.ShellToolProperties;
import com.agentos.tool.sandbox.WhitelistSandbox;
import com.agentos.tool.support.LocalMcpServers;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpTransportContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"stdio", "sse", "streamable-http"})
    void realOfficialClientInitializesDiscoversCallsAndCloses(String mode) throws Exception {
        try (var fixture = new LocalMcpServers(mode);
                var scope =
                        new ToolExecutionScope(
                                null,
                                "mcp-init",
                                TurnExecutionBudget.start(Duration.ofSeconds(10)),
                                Duration.ofSeconds(10),
                                () -> {})) {
            var config = fixture.configuration("test");
            var shells =
                    ShellToolProperties.defaults(
                            List.of(
                                    new ShellToolProperties.Command(
                                            LocalMcpServers.python(),
                                            List.of(fixture.directory()),
                                            Map.of())));
            var transport =
                    mode.equals("stdio")
                            ? new ManagedStdioTransport(config, shells, scope)
                            : new McpHttpTransportFactory(
                                            new WhitelistSandbox(List.of("127.0.0.1")))
                                    .create(config);
            var client =
                    McpClient.sync(transport)
                            .requestTimeout(Duration.ofSeconds(2))
                            .initializationTimeout(Duration.ofSeconds(2))
                            .build();
            client.initialize();
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactly("echo");
            var result =
                    client.callTool(
                            new McpSchema.CallToolRequest(
                                    "echo", Map.of("value", "fixture-value")));
            assertThat(result.isError()).isFalse();
            assertThat(fixture.stats().path("call").asInt()).isEqualTo(1);
            client.close();
            if (mode.equals("stdio") || mode.equals("sse")) {
                LocalMcpServers.waitFor(
                        () -> {
                            try {
                                return fixture.stats().path("closed").asInt() > 0;
                            } catch (java.io.IOException failure) {
                                return false;
                            }
                        },
                        1500);
            }
        }
    }

    @Test
    void normalizedOriginsRespectDefaultPortsAndNeverAuthorizeOtherAllowedOrigins() {
        assertThat(
                        McpHttpTransportFactory.sameOrigin(
                                URI.create("https://EXAMPLE.com/base"),
                                URI.create("https://example.com:443/next")))
                .isTrue();
        assertThat(
                        McpHttpTransportFactory.sameOrigin(
                                URI.create("http://example.com"),
                                URI.create("http://example.com:80/next")))
                .isTrue();
        for (String other :
                List.of("https://example.com", "http://other.example", "http://example.com:81")) {
            assertThat(
                            McpHttpTransportFactory.sameOrigin(
                                    URI.create("http://example.com"), URI.create(other)))
                    .isFalse();
        }
    }

    @Test
    void sseCrossOriginMessageEndpointGetsNoRequestsOrCredentials() throws Exception {
        try (var source = new LocalMcpServers("sse");
                var target = new LocalMcpServers("streamable-http")) {
            for (String endpoint :
                    List.of(
                            target.url() + "/mcp",
                            target.url().replace("http:", "https:") + "/mcp",
                            target.url().replace("127.0.0.1", "localhost") + "/mcp")) {
                source.control(Map.of("messageEndpoint", endpoint));
                var original = source.configuration("safe");
                var config =
                        new McpServerConfig(
                                original.name(),
                                original.transport(),
                                null,
                                List.of(),
                                original.cwd(),
                                Map.of(),
                                original.url(),
                                original.endpoint(),
                                Map.of("Authorization", "Bearer fixture-sentinel"),
                                original.policy(),
                                Map.of());
                var transport =
                        new McpHttpTransportFactory(
                                        new WhitelistSandbox(List.of("127.0.0.1", "localhost")))
                                .create(config);
                var client =
                        McpClient.sync(transport)
                                .initializationTimeout(Duration.ofMillis(300))
                                .requestTimeout(Duration.ofMillis(300))
                                .build();
                try {
                    assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
                } finally {
                    client.close();
                }
                assertThat(target.stats().path("requests").asInt()).isZero();
                assertThat(target.stats().path("credentialRequests").asInt()).isZero();
            }
        }
    }
}
