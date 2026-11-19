package com.agentos.cli;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** 本地直接查询不要求HTTP令牌，不加载Provider名称投影或.env。 */
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出工具及安全来源状态")
public final class ToolListCommand implements Callable<Integer> {
    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    @Option(names = "--agent")
    String agent;

    @Option(names = "--json")
    boolean json;

    @Spec CommandSpec spec;
    private final ContextLauncher launcher;
    private final Path directory;
    private final Supplier<Path> configuration;

    public ToolListCommand(ContextLauncher launcher, Path directory, Supplier<Path> configuration) {
        this.launcher = launcher;
        this.directory = directory;
        this.configuration = configuration;
    }

    static Path config(Path directory, Path configured) {
        if (configured == null) {
            return null;
        }
        Path path = directory.resolve(configured).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new IllegalArgumentException("配置路径不可用");
        }
        return path;
    }

    public Integer call() {
        try {
            if (agent != null && (agent.isBlank() || agent.length() > 128)) {
                throw new IllegalArgumentException("Agent参数不合法");
            }
            Path config = config(directory, configuration.get());
            if (launcher == null) {
                spec.commandLine().getErr().println("工具目录需要完整应用启动器");
                return 1;
            }
            try (var context =
                    launcher.launchCatalog(
                            directory.resolve(workspace).toAbsolutePath().normalize(),
                            config,
                            false)) {
                var view = context.query().snapshot(agent);
                if (json) {
                    spec.commandLine()
                            .getOut()
                            .println(
                                    tools.jackson.databind.json.JsonMapper.builder()
                                            .build()
                                            .writeValueAsString(view));
                } else {
                    spec.commandLine().getOut().println("工具名\t来源\t状态\t授权\t可见\t说明");
                    for (var item : view.tools()) {
                        spec.commandLine()
                                .getOut()
                                .printf(
                                        "%s\t%s\t%s\t%s\t%s\t%s%n",
                                        item.name(),
                                        item.source(),
                                        item.status(),
                                        item.authorized(),
                                        item.visible(),
                                        item.description().replace('\n', ' ').replace('\r', ' '));
                    }
                    for (var source : view.sources()) {
                        spec.commandLine()
                                .getOut()
                                .printf(
                                        "来源 %s\t%s\t%s%n",
                                        source.source(), source.status(), source.reason());
                    }
                }
                return 0;
            }
        } catch (IllegalArgumentException invalid) {
            spec.commandLine().getErr().println("目录参数或配置不合法");
            return 2;
        } catch (AgentOSException invalid) {
            spec.commandLine().getErr().println("Agent或工具配置不合法");
            return invalid.getErrorCode() == ErrorCode.INVALID_ARGUMENT
                            || invalid.getErrorCode() == ErrorCode.NOT_FOUND
                    ? 2
                    : 1;
        } catch (RuntimeException failed) {
            spec.commandLine().getErr().println("工具目录查询失败，请检查配置");
            return 1;
        }
    }
}
