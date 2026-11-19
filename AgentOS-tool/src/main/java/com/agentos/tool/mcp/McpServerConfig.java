package com.agentos.tool.mcp;

import com.agentos.core.tool.ToolPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 内部连接配置；禁止序列化本对象到模型或目录查询。 */
public record McpServerConfig(
        String name,
        Transport transport,
        Path command,
        List<String> args,
        Path cwd,
        Map<String, String> env,
        URI url,
        String endpoint,
        Map<String, String> headers,
        ToolPolicy policy,
        Map<String, ToolPolicy> tools) {
    public enum Transport {
        STDIO,
        SSE,
        STREAMABLE_HTTP;

        public static Transport parse(String value) {
            return switch (value) {
                case "stdio" -> STDIO;
                case "sse" -> SSE;
                case "streamable-http" -> STREAMABLE_HTTP;
                default -> throw new IllegalArgumentException("transport");
            };
        }
    }

    public McpServerConfig {
        if (name == null || !name.matches("[a-z0-9-]{1,20}")) {
            throw new IllegalArgumentException("name");
        }
        Objects.requireNonNull(transport, "transport");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        policy =
                policy == null
                        ? new ToolPolicy(false, java.time.Duration.ofSeconds(30), 10000)
                        : policy;
        if (transport == Transport.STDIO) {
            if (command == null
                    || !command.isAbsolute()
                    || cwd == null
                    || url != null
                    || endpoint != null
                    || !headers.isEmpty()) {
                throw new IllegalArgumentException("stdio");
            }
            if (args.size() > 128
                    || args.stream().anyMatch(value -> value.indexOf(0) >= 0)
                    || args.stream()
                                    .mapToLong(value ->
                                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                                    .length)
                                    .sum()
                            > 65536) {
                throw new IllegalArgumentException("args");
            }
        } else {
            if (url == null || command != null || !args.isEmpty() || !env.isEmpty()) {
                throw new IllegalArgumentException("http");
            }
            validateUri(url);
            endpoint = endpoint == null ? transport == Transport.SSE ? "/sse" : "/mcp" : endpoint;
            URI actual;
            try {
                actual = url.resolve(endpoint);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("endpoint");
            }
            validateUri(actual);
            if (!McpHttpTransportFactory.sameOrigin(url, actual)) {
                throw new IllegalArgumentException("endpoint");
            }
        }
        for (String key : tools.keySet()) {
            if (!key.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("tools");
            }
        }
        for (var entry : headers.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9-]+")
                    || entry.getValue().indexOf('\r') >= 0
                    || entry.getValue().indexOf('\n') >= 0) {
                throw new IllegalArgumentException("headers");
            }
        }
        validatePolicy(policy);
        tools.values().forEach(McpServerConfig::validatePolicy);
    }

    static void validatePolicy(ToolPolicy policy) {
        if (policy.maxResultChars() < 128
                || policy.maxResultChars() > 10000
                || policy.timeout().compareTo(java.time.Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("policy");
        }
    }

    public static void validateUri(URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getPort() < -1
                || uri.getPort() == 0
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("url");
        }
    }
}
