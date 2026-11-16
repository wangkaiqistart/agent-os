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

/**
 * 读取工作区共享的 AGENTS.md、SOUL.md、USER.md，并提供 Prompt 中使用的当地日期。
 *
 * <p>这里的 AGENTS.md 是运行时模型约束，位于 .AgentOS 等工作区内部，
 * 与仓库根目录给 Codex 开发时使用的 AGENTS.md 不是同一文件。
 * 缺失或读取失败只记录警告并跳过，不阻断最小问答链路。
 */
public final class ContextLoader {
    private static final Logger log = LoggerFactory.getLogger(ContextLoader.class);
    private final Path workspaceRoot;
    private final Clock clock;

    public ContextLoader(Path workspaceRoot) {
        this(workspaceRoot, Clock.systemDefaultZone());
    }

    /** Clock 可替换，测试能固定日期而不依赖运行机器当天的系统时间。 */
    public ContextLoader(Path workspaceRoot, Clock clock) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot).toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
    }

    /** 返回带最新 Bootstrap 的新快照，不原地修改之前加载的 Agent。 */
    public LoadedAgent load(LoadedAgent agent) {
        // LinkedHashMap 固定注入顺序，避免多次构建 Prompt 时共享文件的排列漂移。
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

    /** 只需要共享文件时使用的独立入口；同样每次读盘，不缓存。 */
    public Map<String, String> bootstrap() {
        return loadBootstrap();
    }

    /** 日期跟随注入 Clock 的时区，默认是应用进程的系统时区。 */
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
