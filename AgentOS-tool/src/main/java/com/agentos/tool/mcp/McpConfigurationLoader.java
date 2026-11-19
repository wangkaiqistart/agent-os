package com.agentos.tool.mcp;

import com.agentos.core.tool.ToolPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 安全读取MCP配置；逐来源错误只记录安全身份和字段分类，不保留解析器异常。 */
public final class McpConfigurationLoader {
    public record Failure(String source, String field) {}

    public record Result(List<McpServerConfig> servers, List<Failure> failures) {
        public Result {
            servers = List.copyOf(servers);
            failures = List.copyOf(failures);
        }
    }

    private static final Pattern REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final Function<String, String> environment;

    public McpConfigurationLoader(Function<String, String> environment) {
        this.environment = java.util.Objects.requireNonNull(environment);
    }

    public Result load(Path file) {
        return load(file, ignored -> {});
    }

    /** 仅登记配置中 env/header 的完整值及其实际引用分量，不枚举宿主环境。 */
    public Result load(Path file, Consumer<String> secretSink) {
        java.util.Objects.requireNonNull(secretSink);
        if (!Files.exists(file)) {
            return new Result(List.of(), List.of());
        }
        Object loaded;
        try {
            if (Files.size(file) > 1048576) {
                throw new IOException();
            }
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setCodePointLimit(1048576);
            options.setNestingDepthLimit(64);
            try (var reader = Files.newBufferedReader(file)) {
                loaded = new Yaml(new SafeConstructor(options)).load(reader);
            }
        } catch (IOException | RuntimeException invalid) {
            return new Result(List.of(), List.of(new Failure("mcp:unknown", "yaml")));
        }
        var servers = new ArrayList<McpServerConfig>();
        var failures = new ArrayList<Failure>();
        var names = new java.util.HashSet<String>();
        if (!(loaded instanceof Map<?, ?> root)
                || !(root.get("servers") instanceof List<?> entries)
                || root.size() != 1) {
            return new Result(List.of(), List.of(new Failure("mcp:unknown", "servers")));
        }
        for (Object entry : entries) {
            String name = "unknown";
            String field = "server";
            try {
                var raw = map(entry);
                Object rawName = raw.get("name");
                if (!(rawName instanceof String candidate)
                        || !candidate.matches("[a-z0-9-]{1,20}")) {
                    field = "name";
                    throw new IllegalArgumentException();
                }
                name = (String) rawName;
                if (!names.add(name)) {
                    String repeated = name;
                    servers.removeIf(server -> server.name().equals(repeated));
                    field = "name";
                    throw new IllegalArgumentException();
                }
                field = "transport";
                var transport = McpServerConfig.Transport.parse(text(raw.get("transport")));
                var allowed =
                        transport == McpServerConfig.Transport.STDIO
                                ? Set.of(
                                        "name",
                                        "transport",
                                        "command",
                                        "args",
                                        "cwd",
                                        "env",
                                        "timeout",
                                        "idempotent",
                                        "max-result-chars",
                                        "tools")
                                : transport == McpServerConfig.Transport.SSE
                                        ? Set.of(
                                                "name",
                                                "transport",
                                                "url",
                                                "sse-endpoint",
                                                "headers",
                                                "timeout",
                                                "idempotent",
                                                "max-result-chars",
                                                "tools")
                                        : Set.of(
                                                "name",
                                                "transport",
                                                "url",
                                                "endpoint",
                                                "headers",
                                                "timeout",
                                                "idempotent",
                                                "max-result-chars",
                                                "tools");
                if (!allowed.containsAll(raw.keySet())) {
                    field = "fields";
                    throw new IllegalArgumentException();
                }
                field = "references";
                Path command =
                        transport == McpServerConfig.Transport.STDIO
                                ? Path.of(resolve(text(raw.get("command"))))
                                : null;
                var args = new ArrayList<String>();
                if (raw.containsKey("args")) {
                    if (!(raw.get("args") instanceof List<?> values)) {
                        throw new IllegalArgumentException();
                    }
                    for (Object value : values) {
                        args.add(resolve(text(value)));
                    }
                }
                Path cwd =
                        transport == McpServerConfig.Transport.STDIO
                                ? Path.of(resolve(text(raw.getOrDefault("cwd", "."))))
                                : null;
                var env = resolvedMap(raw.getOrDefault("env", Map.of()), false, secretSink);
                var headers = resolvedMap(raw.getOrDefault("headers", Map.of()), true, secretSink);
                java.net.URI url =
                        transport == McpServerConfig.Transport.STDIO
                                ? null
                                : java.net.URI.create(resolve(text(raw.get("url"))));
                String endpointKey =
                        transport == McpServerConfig.Transport.SSE ? "sse-endpoint" : "endpoint";
                String endpoint =
                        raw.containsKey(endpointKey) ? resolve(text(raw.get(endpointKey))) : null;
                field = "policy";
                var policy = policy(raw, new ToolPolicy(false, Duration.ofSeconds(30), 10000));
                var toolPolicies = new LinkedHashMap<String, ToolPolicy>();
                if (raw.containsKey("tools")) {
                    for (var tool : map(raw.get("tools")).entrySet()) {
                        var overrides = map(tool.getValue());
                        if (!Set.of("idempotent", "timeout", "max-result-chars")
                                .containsAll(overrides.keySet())) {
                            throw new IllegalArgumentException();
                        }
                        toolPolicies.put(tool.getKey(), policy(overrides, policy));
                    }
                }
                field = "definition";
                servers.add(
                        new McpServerConfig(
                                name,
                                transport,
                                command,
                                args,
                                cwd,
                                env,
                                url,
                                endpoint,
                                headers,
                                policy,
                                toolPolicies));
            } catch (RuntimeException invalid) {
                failures.add(new Failure("mcp:" + name, field));
            }
        }
        return new Result(servers, failures);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException();
        }
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException();
            }
            result.put((String) key, item);
        });
        return result;
    }

    private String text(Object value) {
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException();
        }
        return string;
    }

    private Map<String, String> resolvedMap(
            Object value, boolean headers, Consumer<String> secretSink) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : map(value).entrySet()) {
            String raw = text(entry.getValue());
            if (headers
                    && entry.getKey()
                            .toLowerCase(java.util.Locale.ROOT)
                            .matches(".*(authorization|token|key|secret).*")
                    && !raw.contains("${")) {
                throw new IllegalArgumentException();
            }
            String resolved = resolve(raw, secretSink);
            secretSink.accept(resolved);
            result.put(entry.getKey(), resolved);
        }
        return result;
    }

    private String resolve(String raw) {
        return resolve(raw, ignored -> {});
    }

    private String resolve(String raw, Consumer<String> secretSink) {
        var matcher = REF.matcher(raw);
        var result = new StringBuilder();
        while (matcher.find()) {
            String value = environment.apply(matcher.group(1));
            if (value == null || value.indexOf(0) >= 0) {
                throw new IllegalArgumentException();
            }
            secretSink.accept(value);
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        if (result.toString().contains("${")) {
            throw new IllegalArgumentException();
        }
        return result.toString();
    }

    private ToolPolicy policy(Map<String, Object> raw, ToolPolicy fallback) {
        boolean idempotent =
                raw.containsKey("idempotent") ? bool(raw.get("idempotent")) : fallback.idempotent();
        Duration timeout =
                raw.containsKey("timeout")
                        ? DurationStyle.detectAndParse(text(raw.get("timeout")))
                        : fallback.timeout();
        int chars = fallback.maxResultChars();
        if (raw.containsKey("max-result-chars")) {
            Object value = raw.get("max-result-chars");
            if (!(value instanceof Integer number)) {
                throw new IllegalArgumentException();
            }
            chars = number;
        }
        var policy = new ToolPolicy(idempotent, timeout, chars);
        McpServerConfig.validatePolicy(policy);
        return policy;
    }

    private boolean bool(Object value) {
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException();
        }
        return b;
    }
}
