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

/**
 * 从工作区 agents 目录读取指定 Agent，把 AGENT.md 分成运行配置和任务正文。
 *
 * <p>frontmatter 中的 provider/settings 等字段派生为不可变值对象；正文交给 PromptBuilder
 * 作为系统指令。本类不调用模型，也不加载工作区共享 Bootstrap，那部分由 ContextLoader 负责。
 *
 * <p>每轮重新读取，不缓存文件。这里使用 SnakeYAML，未走 Spring Environment，
 * 所以正文和模型名里的环境变量占位符不会自动按 Spring 的规则展开。
 */
public final class AgentLoader {
    private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);
    private static final List<String> KNOWN_UNSUPPORTED =
            List.of("identity", "channels", "schedules", "bootstrap");
    private final Path agentsRoot;

    public AgentLoader(Path agentsRoot) {
        this.agentsRoot =
                Objects.requireNonNull(agentsRoot, "agentsRoot").toAbsolutePath().normalize();
    }

    /**
     * 按目录名定位 Agent，拒绝带路径片段的名称与末级软链接目录/文件。
     * 这些检查限制本入口的文件定位，不代替未来工具执行所需的完整沙箱。
     */
    public LoadedAgent load(String name) {
        if (name == null
                || name.isBlank()
                || name.contains("..")
                || name.contains("/")
                || name.contains("\\")) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "Agent 名称不合法");
        }
        // 名称只能是一个目录名，不能通过 ../ 或路径分隔符选择其他文件。
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
            // 请求名称、目录名称和配置 name 必须一致，避免“加载 A 却以 B 身份运行”。
            String frontName = text(root.get("name"));
            if (!name.equals(frontName)) {
                throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "字段 name 必须等于目录名：" + file);
            }
            Map<String, Object> provider = map(root.get("provider"));
            String model = text(provider.get("model"));
            if (model == null
                    || model.isBlank()
                    || model.contains("${")
                    || model.contains("{{")
                    || (model.startsWith("<") && model.endsWith(">"))) {
                throw new AgentOSException(
                        ErrorCode.INVALID_ARGUMENT, "字段 provider.model 必须是具体非空白模型名：" + file);
            }
            // Provider 名称可留空让 Catalog 使用默认连接；模型名必须由 Agent 明确给出。
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
            // 某些设计字段尚未实现：提醒用户而非静默宣称这些配置已经生效。
            for (String key : root.keySet()) {
                if (KNOWN_UNSUPPORTED.contains(key)) {
                    log.warn("Agent {} 的配置键 {} 暂不支持", name, key);
                }
            }
            return new LoadedAgent(
                    new AgentProfile(
                            name,
                            text(root.get("description")),
                            profile,
                            tools,
                            serverNames(root.get("mcp_servers")),
                            settings),
                    parsed.body(),
                    Map.of());
        } catch (IOException | RuntimeException e) {
            // 业务异常保留原错误码；底层解析细节只保留在 cause，不作为公开错误文案直接拼出。
            if (e instanceof AgentOSException a) {
                throw a;
            }
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "无法解析 Agent 文件：" + file, e);
        }
    }

    /** 缺省值与 AgentSettings.defaults 保持同一口径；数字、时长先转换，再由值对象校验。 */
    private static AgentSettings settings(Map<String, Object> values) {
        return new AgentSettings(
                integer(values.getOrDefault("max_iterations", 10)),
                integer(values.getOrDefault("max_history_turns", 20)),
                integer(values.getOrDefault("max_history_tokens", 32_000)),
                duration(values.getOrDefault("turn_timeout", "30s")));
    }

    /** 解析文件开头由 --- 包围的 YAML；后面的 Markdown 保持为任务指令文本。 */
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
            // SafeConstructor 读取基础数据类型，不允许 YAML 自行构造任意 Java 对象。
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

    /** 配置段缺失或不是对象时视为空段，必填字段的错误由上层按业务含义报告。 */
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /** 工具名只从列表读取；空项清掉，授权与工具是否存在由后续工具目录负责。 */
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

    private static List<String> serverNames(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(v -> !(v instanceof String))) {
            throw new IllegalArgumentException("mcp_servers必须是服务名列表");
        }
        return values.stream().map(String.class::cast).toList();
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

    /** 支持 250ms、300s、5m 等简写；其他值交给 ISO-8601 Duration 解析。 */
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
