package com.agentos.cli;

import com.agentos.core.agent.AgentLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 离线准备工作区与普通的 main Agent；不启动 Spring，不探测 Provider 或数据库。
 * 所有预检查和必要的模型解析先于写入。失败保留已创建项，重试只补缺项，不做跨文件回滚。
 */
public final class WorkspaceInitializer {
    private final FileCreator creator;

    public WorkspaceInitializer() {
        this((file, content) ->
                Files.writeString(
                        file,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE));
    }

    /** 测试可确定性地注入 I/O 故障或竞争创建，不依赖当前用户的 chmod 权限。 */
    WorkspaceInitializer(FileCreator creator) {
        this.creator = Objects.requireNonNull(creator, "creator");
    }

    /** source 只在 main 缺失时调用，合法已有文件不需要重新提供模型。 */
    public Result initialize(Path root, OptionsSource source) throws IOException {
        Objects.requireNonNull(root, "root");
        Path main = root.resolve("agents/main/AGENT.md");
        List<Path> directories =
                List.of(
                        root,
                        root.resolve("agents"),
                        root.resolve("agents/main"),
                        root.resolve("skills"),
                        root.resolve("output"),
                        root.resolve("memory"),
                        root.resolve("logs"));
        Map<Path, String> templates = new LinkedHashMap<>();
        templates.put(root.resolve("memory/MEMORY.md"), "# MEMORY\n\n这里记录可复用的项目记忆。\n");
        templates.put(root.resolve("AGENTS.md"), "# 工作区 Agent 指南\n\n请遵守项目约束并保护敏感信息。\n");
        templates.put(root.resolve("SOUL.md"), "# 默认人格\n\n保持清晰、可靠、简洁。\n");
        templates.put(root.resolve("USER.md"), "# 用户偏好\n\n在这里填写偏好。\n");
        for (Path dir : directories) {
            checkType(dir, true);
        }
        for (Path file : templates.keySet()) {
            checkType(file, false);
        }
        checkType(main, false);
        String mainContent = null;
        if (Files.exists(main, LinkOption.NOFOLLOW_LINKS)) {
            new AgentLoader(root.resolve("agents")).load("main");
        } else {
            mainContent = mainContent(source.get());
        }
        // 到此全部输入/已有内容已校验，缺模型时不会创建 root。
        List<Path> created = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        for (Path dir : directories) {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                checkType(dir, true);
                skipped.add(dir);
            } else {
                try {
                    if (dir.equals(root)) {
                        Files.createDirectories(dir);
                    } else {
                        Files.createDirectory(dir);
                    }
                    checkType(dir, true);
                    created.add(dir);
                } catch (FileAlreadyExistsException e) {
                    checkType(dir, true);
                    skipped.add(dir);
                }
            }
        }
        for (var entry : templates.entrySet()) {
            createFile(entry.getKey(), entry.getValue(), directories, created, skipped);
        }
        if (mainContent == null) {
            skipped.add(main);
        } else {
            createFile(main, mainContent, directories, created, skipped);
        }
        // 竞争者创建 main 后必须重新验证，损坏的赢家不能报告成功。
        new AgentLoader(root.resolve("agents")).load("main");
        return new Result(List.copyOf(created), List.copyOf(skipped));
    }

    private void createFile(
            Path file,
            String content,
            List<Path> directories,
            List<Path> created,
            List<Path> skipped)
            throws IOException {
        for (Path dir : directories) {
            checkType(dir, true);
        }
        checkType(file, false);
        try {
            creator.create(file, content);
            created.add(file);
        } catch (FileAlreadyExistsException e) {
            checkType(file, false);
            skipped.add(file);
        }
    }

    /** NOFOLLOW_LINKS 同时识别悬空链接，避免把它当成缺失文件。 */
    private static void checkType(Path path, boolean directory) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (directory
                        ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("工作区路径类型或链接冲突");
        }
    }

    /** 用序列化器处理特殊字符，模型输入不能变成额外 YAML 字段或工具授权。 */
    private static String mainContent(DefaultAgentOptions options) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put(
                "name",
                options.providerName() == null || options.providerName().isBlank()
                        ? "primary"
                        : options.providerName());
        provider.put("model", options.model());
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", "main");
        frontmatter.put("provider", provider);
        frontmatter.put("tools", List.of());
        DumperOptions yamlOptions = new DumperOptions();
        yamlOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return "---\n"
                + new Yaml(yamlOptions).dump(frontmatter)
                + "---\n你是通用中文助手。请清晰、准确地回答用户问题，并保护敏感信息。\n";
    }

    @FunctionalInterface
    public interface OptionsSource {
        DefaultAgentOptions get() throws IOException;
    }

    @FunctionalInterface
    interface FileCreator {
        void create(Path file, String content) throws IOException;
    }

    /** 路径级统计，不含文件正文、凭据或完整配置。 */
    public record Result(List<Path> created, List<Path> skipped) {}
}
