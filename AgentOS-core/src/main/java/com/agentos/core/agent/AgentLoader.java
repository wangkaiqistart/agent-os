package com.agentos.core.agent;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.provider.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 安全读取 .AgentOS/agents 下的 Agent 文档。每轮调用都会重新读取。 */
public final class AgentLoader {
    private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);
    private static final List<String> KNOWN_UNSUPPORTED =
            List.of("identity", "mcp_servers", "channels", "schedules", "bootstrap");
    private final Path agentsRoot;

    public AgentLoader(Path agentsRoot) {
        this.agentsRoot =
                Objects.requireNonNull(agentsRoot, "agentsRoot").toAbsolutePath().normalize();
    }

    public LoadedAgent load(String name) {
        if (name == null
                || name.isBlank()
                || name.contains("..")
                || name.contains("/")
                || name.contains("\\")) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "Agent 名称不合法");
        }
        Path dir = agentsRoot.resolve(name).normalize();
        if (!dir.startsWith(agentsRoot) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentOSException(ErrorCode.NOT_FOUND, "Agent 目录不存在：" + name);
        }
        Path file = dir.resolve("AGENT.md");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "缺少 Agent 文件：" + file);
        }
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Frontmatter parsed = parse(source, file);
            Map<String, Object> root = parsed.values();
            String frontName = text(root.get("name"));
            if (!name.equals(frontName)) {
                throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "字段 name 必须等于目录名：" + file);
            }
            Map<String, Object> provider = map(root.get("provider"));
            String model = text(provider.get("model"));
            if (model == null || model.isBlank()) {
                throw new AgentOSException(
                        ErrorCode.INVALID_ARGUMENT, "字段 provider.model 必填：" + file);
            }
            Profile profile =
                    new Profile(
                            text(provider.get("name")),
                            model,
                            text(provider.get("reasoning_effort")),
                            integerOrNull(provider.get("max_output_tokens")),
                            decimal(provider.get("temperature")));
            AgentSettings settings = settings(map(root.get("settings")));
            List<String> tools = strings(root.get("tools"));
            if (tools.isEmpty()) {
                log.info("Agent {} 未声明工具，按无工具权限加载", name);
            }
            for (String key : root.keySet()) {
                if (KNOWN_UNSUPPORTED.contains(key)) {
                    log.warn("Agent {} 的配置键 {} 暂不支持", name, key);
                }
            }
            return new LoadedAgent(
                    new AgentProfile(name, text(root.get("description")), profile, tools, settings),
                    parsed.body(),
                    Map.of());
        } catch (IOException | RuntimeException e) {
            if (e instanceof AgentOSException a) {
                throw a;
            }
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "无法解析 Agent 文件：" + file, e);
        }
    }

    private static AgentSettings settings(Map<String, Object> values) {
        return new AgentSettings(
                integer(values.getOrDefault("max_iterations", 10)),
                integer(values.getOrDefault("max_history_turns", 20)),
                integer(values.getOrDefault("max_history_tokens", 32_000)),
                duration(values.getOrDefault("turn_timeout", "300s")));
    }

    private static Frontmatter parse(String source, Path file) {
        if (!source.startsWith("---")) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "缺少 frontmatter：" + file);
        }
        int end = source.indexOf("\n---", 3);
        if (end < 0) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "frontmatter 未闭合：" + file);
        }
        String yaml = source.substring(3, end);
        try {
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            if (!(value instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("frontmatter 必须是对象");
            }
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(String.valueOf(k), v));
            String body = source.substring(end + 4).replaceFirst("^\\r?\\n", "");
            return new Frontmatter(result, body);
        } catch (RuntimeException e) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "frontmatter 解析失败：" + file, e);
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(AgentLoader::text)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value) {
        return value == null
                ? 0
                : value instanceof Number n
                        ? n.intValue()
                        : Integer.parseInt(String.valueOf(value));
    }

    private static Integer integerOrNull(Object value) {
        return value == null ? null : integer(value);
    }

    private static Double decimal(Object value) {
        return value == null
                ? null
                : value instanceof Number n
                        ? n.doubleValue()
                        : Double.parseDouble(String.valueOf(value));
    }

    private static java.time.Duration duration(Object value) {
        String raw = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        try {
            if (raw.endsWith("ms")) {
                return java.time.Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2)));
            }
            if (raw.endsWith("s")) {
                return java.time.Duration.ofSeconds(
                        Long.parseLong(raw.substring(0, raw.length() - 1)));
            }
            if (raw.endsWith("m")) {
                return java.time.Duration.ofMinutes(
                        Long.parseLong(raw.substring(0, raw.length() - 1)));
            }
            return java.time.Duration.parse(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("turn_timeout 不合法：" + value, e);
        }
    }

    private record Frontmatter(Map<String, Object> values, String body) {}
}
