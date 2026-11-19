package com.agentos.tool.mcp;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** 每个实际HTTP目标先同源/域名授权，再附配置凭证；禁跳转和恢复重放。 */
public final class McpHttpTransportFactory {
    private final Sandbox sandbox;

    public McpHttpTransportFactory(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    private void authorize(URI origin, URI actual) {
        try {
            McpServerConfig.validateUri(actual);
        } catch (RuntimeException invalid) {
            throw new McpToolException("DENIED");
        }
        if (!sameOrigin(origin, actual)) {
            throw new McpToolException("DENIED");
        }
        sandbox.enforce(new SandboxAction(ActionType.HTTP, actual.toString()));
    }

    public McpClientTransport create(McpServerConfig server) {
        McpSafeDiagnostics.configure();
        authorize(server.url(), server.url().resolve(server.endpoint()));
        var builder =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10));
        io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
                customizer =
                        (request, method, uri, body, context) -> {
                            authorize(server.url(), uri);
                            server.headers().forEach(request::header);
                        };
        if (server.transport() == McpServerConfig.Transport.SSE) {
            return HttpClientSseClientTransport.builder(server.url().toString())
                    .sseEndpoint(server.endpoint())
                    .clientBuilder(builder)
                    .connectTimeout(Duration.ofSeconds(10))
                    .maxResponseSize(1048576)
                    .httpRequestCustomizer(customizer)
                    .messageEndpointValidator((origin, endpoint) -> {
                        try {
                            authorize(server.url(), origin.resolve(endpoint));
                        } catch (RuntimeException denied) {
                            throw new io.modelcontextprotocol.client.transport
                                    .InvalidSseMessageEndpointException("DENIED", "DENIED");
                        }
                    })
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(server.url().toString())
                .endpoint(server.endpoint())
                .clientBuilder(builder)
                .connectTimeout(Duration.ofSeconds(10))
                .maxResponseSize(1048576)
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(customizer)
                .build();
    }

    public static boolean sameOrigin(URI expected, URI target) {
        if (expected == null
                || target == null
                || expected.getHost() == null
                || target.getHost() == null
                || expected.getScheme() == null
                || target.getScheme() == null) {
            return false;
        }
        return expected.getScheme().equalsIgnoreCase(target.getScheme())
                && expected.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(expected) == effectivePort(target);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1
                ? "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80
                : uri.getPort();
    }
}
