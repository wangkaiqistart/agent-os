package com.agentos.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** 只启动工具目录HTTP服务，配置/令牌缺失时不监听。 */
@Command(name = "serve", mixinStandardHelpOptions = true, description = "启动带令牌认证的只读工具目录")
public final class ToolServeCommand implements Callable<Integer> {
    @Option(names = "--workspace", defaultValue = ".AgentOS")
    Path workspace;

    @Spec CommandSpec spec;
    private final ContextLauncher launcher;
    private final Path directory;
    private final Supplier<Path> configuration;

    public ToolServeCommand(
            ContextLauncher launcher, Path directory, Supplier<Path> configuration) {
        this.launcher = launcher;
        this.directory = directory;
        this.configuration = configuration;
    }

    public Integer call() {
        if (launcher == null) {
            spec.commandLine().getErr().println("工具目录需要完整应用启动器");
            return 1;
        }
        try (var context =
                launcher.launchCatalog(
                        directory.resolve(workspace).toAbsolutePath().normalize(),
                        ToolListCommand.config(directory, configuration.get()),
                        true)) {
            context.await();
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (RuntimeException failed) {
            spec.commandLine().getErr().println("目录服务未启动，请检查启用、令牌和监听配置");
            return 1;
        }
    }
}
