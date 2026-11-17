package com.agentos.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 从与 Boot 相同的 YAML 配置中投影 Provider 名称。
 *
 * <p>解析过程中只把 {@code agentos.providers} 的 key 写入结果集合；连接类型、地址、凭据和模型值
 * 不会进入返回对象，也不会写日志。该类不启动 Spring、不访问网络。
 */
public final class ProviderNameProjectionLoader {
    private static final String CONFIG_FILE = "application.yaml";
    private final ClassLoader classLoader;

    public ProviderNameProjectionLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ProviderNameProjectionLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** 从明确文件读取名称投影；文件不存在、不可读或 YAML 错误均为配置错误。 */
    public Set<String> load(Path config) {
        Objects.requireNonNull(config, "config");
        Path normalized = config.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(normalized)) {
                throw configurationError("配置文件不存在或不可读");
            }
            try (InputStream input = Files.newInputStream(normalized)) {
                return project(input);
            }
        } catch (ConfigurationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw configurationError("配置文件无法读取或解析");
        }
    }

    /** 从 classpath application.yaml 读取名称投影；资源不存在时为配置错误。 */
    public Set<String> loadClasspath() {
        try (InputStream input = classLoader.getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                throw configurationError("未找到 AgentOS 配置文件");
            }
            return project(input);
        } catch (ConfigurationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw configurationError("classpath 配置文件无法读取或解析");
        }
    }

    /**
     * 按 CLI 契约解析配置路径。显式路径失败不回退；未指定时按固定本地顺序搜索，最后尝试 classpath。
     */
    public Resolution resolve(
            Path executionDirectory,
            Path explicit,
            Function<String, String> environment,
            Path userHome) {
        Objects.requireNonNull(executionDirectory, "executionDirectory");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(userHome, "userHome");
        if (explicit != null) {
            Path path = resolveAgainst(executionDirectory, explicit);
            // 读取一次以确保显式配置在命令进入业务逻辑前即可被分类为错误。
            load(path);
            return new Resolution(path, false);
        }
        for (Path candidate : candidates(executionDirectory, environment, userHome)) {
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && Files.isReadable(candidate)) {
                load(candidate);
                return new Resolution(candidate.toAbsolutePath().normalize(), false);
            }
        }
        // classpath 资源不能转换成 Path；返回一个标记让 LocalProviderNameCatalog 直接从资源读取。
        if (classLoader.getResource(CONFIG_FILE) != null) {
            loadClasspath();
            return new Resolution(null, true);
        }
        throw configurationError("未找到 AgentOS 配置文件");
    }

    /** 读取已解析的 Resolution。 */
    public Set<String> load(Resolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        return resolution.classpath() ? loadClasspath() : load(resolution.path());
    }

    private static Path resolveAgainst(Path directory, Path path) {
        return (path.isAbsolute() ? path : directory.resolve(path)).toAbsolutePath().normalize();
    }

    private static Set<Path> candidates(
            Path executionDirectory, Function<String, String> environment, Path userHome) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        Path base = executionDirectory.toAbsolutePath().normalize();
        paths.add(base.resolve(CONFIG_FILE));
        paths.add(base.resolve("config").resolve(CONFIG_FILE));
        String xdg = environment.apply("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            paths.add(Path.of(xdg).resolve("agentos").resolve(CONFIG_FILE));
        }
        paths.add(userHome.resolve(".config").resolve("agentos").resolve(CONFIG_FILE));
        return paths;
    }

    private static Set<String> project(InputStream input) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        } catch (Exception e) {
            throw configurationError("AgentOS 配置 YAML 格式不合法");
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw configurationError("AgentOS 配置必须是 YAML 对象");
        }
        Object agentos = rootMap.get("agentos");
        if (!(agentos instanceof Map<?, ?> agentMap)) {
            throw configurationError("缺少 agentos.providers 配置");
        }
        Object providers = agentMap.get("providers");
        if (!(providers instanceof Map<?, ?> providerMap) || providerMap.isEmpty()) {
            throw configurationError("缺少 agentos.providers 配置");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object key : providerMap.keySet()) {
            String name = key == null ? "" : String.valueOf(key).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw configurationError("agentos.providers 未包含有效名称");
        }
        return Collections.unmodifiableSet(names);
    }

    private static ConfigurationException configurationError(String message) {
        return new ConfigurationException(message);
    }

    public record Resolution(Path path, boolean classpath) {}

    /** 配置错误由命令入口映射为退出码 2。 */
    public static final class ConfigurationException extends IllegalArgumentException {
        public ConfigurationException(String message) {
            super(message);
        }
    }
}
