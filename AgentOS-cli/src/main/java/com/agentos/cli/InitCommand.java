package com.agentos.cli;

import com.agentos.core.error.AgentOSException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** 只准备本地文件；成功不意味着 Provider、数据库或模型权限已验证。 */
@Command(name = "init", mixinStandardHelpOptions = true, description = "创建工作区与默认 main Agent")
public final class InitCommand implements Callable<Integer> {
    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    @Option(names = "--model")
    String model;

    @Option(names = "--provider")
    String provider;

    @Spec CommandSpec spec;
    private final WorkspaceInitializer initializer;
    private final InitModelResolver resolver;
    private final Path directory;
    private final ProviderNameCatalog providers;

    public InitCommand() {
        this(
                new WorkspaceInitializer(),
                new InitModelResolver(System::getenv, Path.of("")),
                Path.of(""),
                () -> Set.of("primary"));
    }

    InitCommand(WorkspaceInitializer initializer, InitModelResolver resolver, Path directory) {
        this(initializer, resolver, directory, () -> Set.of("primary"));
    }

    InitCommand(
            WorkspaceInitializer initializer,
            InitModelResolver resolver,
            Path directory,
            ProviderNameCatalog providers) {
        this.initializer = initializer;
        this.resolver = resolver;
        this.directory = directory;
        this.providers = providers;
    }

    @Override
    public Integer call() {
        try {
            // Provider 名称目录和显式名称必须在任何工作区写入前校验；已有合法 main 也不能掩盖错误配置。
            providers.names();
            if (provider != null && !providers.contains(provider)) {
                throw new IllegalArgumentException("Provider 不存在：" + provider);
            }
            Path root = directory.resolve(workspace).toAbsolutePath().normalize();
            WorkspaceInitializer.Result result =
                    initializer.initialize(
                            root,
                            () -> {
                                DefaultAgentOptions options = resolver.resolve(model, provider);
                                String selected = options.providerName();
                                if (!providers.contains(selected)) {
                                    throw new IllegalArgumentException("Provider 不存在：" + selected);
                                }
                                return options;
                            });
            var out = spec.commandLine().getOut();
            out.printf(
                    "仅本地文件已准备：新建 %d 项，跳过 %d 项；聊天仍需配置外部连接。%n",
                    result.created().size(), result.skipped().size());
            result.created().forEach(path -> out.println("新建：" + path));
            result.skipped().forEach(path -> out.println("跳过：" + path));
            return 0;
        } catch (AgentOSException | IllegalArgumentException e) {
            // 本地校验器只提供安全文件/字段说明；不转发底层解析异常的 cause。
            spec.commandLine().getErr().println("初始化未完成：" + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            spec.commandLine().getErr().println("初始化未完成：文件或路径操作失败，请检查类型、权限和执行目录配置");
            return 1;
        }
    }
}
