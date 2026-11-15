package com.agentos.core.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 读取每轮都可能变化的工作区 Bootstrap。 */
public final class ContextLoader {
    private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);
    private final Path workspaceRoot;
    private final Clock clock;

    public ContextLoader(Path workspaceRoot) {
        this(workspaceRoot, Clock.systemDefaultZone());
    }

    public ContextLoader(Path workspaceRoot, Clock clock) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot).toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
    }

    public LoadedAgent load(LoadedAgent agent) {
        Map<String, String> bootstrap = new LinkedHashMap<>();
        for (String name : new String[] {"AGENTS.md", "SOUL.md", "USER.md"}) {
            Path file = workspaceRoot.resolve(name).normalize();
            try {
                if (Files.isRegularFile(file)) {
                    bootstrap.put(name, Files.readString(file, StandardCharsets.UTF_8));
                } else {
                    log.warn("Bootstrap 文件缺失：{}", file);
                }
            } catch (IOException e) {
                log.warn("Bootstrap 文件读取失败：{}", file);
            }
        }
        return new LoadedAgent(agent.profile(), agent.instructions(), bootstrap);
    }

    public Map<String, String> bootstrap() {
        return loadBootstrap();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private Map<String, String> loadBootstrap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : new String[] {"AGENTS.md", "SOUL.md", "USER.md"}) {
            Path file = workspaceRoot.resolve(name);
            try {
                if (Files.isRegularFile(file)) {
                    result.put(name, Files.readString(file, StandardCharsets.UTF_8));
                } else {
                    log.warn("Bootstrap 文件缺失：{}", file);
                }
            } catch (IOException e) {
                log.warn("Bootstrap 文件读取失败：{}", file);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
