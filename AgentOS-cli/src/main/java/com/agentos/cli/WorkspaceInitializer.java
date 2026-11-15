package com.agentos.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 幂等创建本地 AgentOS 工作区。 */
public final class WorkspaceInitializer {
    public Result initialize(Path root) throws IOException {
        List<Path> created = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        for (String directory : new String[] {"agents", "skills", "output", "memory", "logs"}) {
            Path path = root.resolve(directory);
            if (Files.exists(path)) {
                skipped.add(path);
            } else {
                Files.createDirectories(path);
                created.add(path);
            }
        }
        createFile(
                root.resolve("memory/MEMORY.md"), "# MEMORY\n\n这里记录可复用的项目记忆。\n", created, skipped);
        createFile(
                root.resolve("AGENTS.md"), "# 工作区 Agent 指南\n\n请遵守项目约束并保护敏感信息。\n", created, skipped);
        createFile(root.resolve("SOUL.md"), "# 默认人格\n\n保持清晰、可靠、简洁。\n", created, skipped);
        createFile(root.resolve("USER.md"), "# 用户偏好\n\n在这里填写偏好。\n", created, skipped);
        return new Result(created, skipped);
    }

    private static void createFile(
            Path path, String content, List<Path> created, List<Path> skipped) throws IOException {
        if (Files.exists(path)) {
            skipped.add(path);
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        created.add(path);
    }

    public record Result(List<Path> created, List<Path> skipped) {}
}
