package com.agentos.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建最小本地运行时工作区：顺序重复执行时跳过已有文件，保留用户内容。
 *
 * <p>这里只创建共享目录、Bootstrap 和 MEMORY 模板，不创建 answer Agent、模型凭证或
 * mcp_servers.yaml。agents 下的具体 AGENT.md 仍需另行配置 provider.model。
 */
public final class WorkspaceInitializer {
    /** root 通常是项目下的 .AgentOS，和供 Codex 阅读的仓库根 AGENTS.md 不是同一个位置。 */
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
        // 这是先检查、后写入，不是并发原子创建；幂等语义针对正常顺序执行的 init。
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

    /** 本次实际创建与已存在而跳过的路径，供 CLI 统计，不包含文件正文或凭证。 */
    public record Result(List<Path> created, List<Path> skipped) {}
}
