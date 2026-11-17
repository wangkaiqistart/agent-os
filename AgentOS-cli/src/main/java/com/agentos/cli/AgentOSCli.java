package com.agentos.cli;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import picocli.CommandLine;

/**
 * 命令行入口。
 *
 * <p>本模块可以单独运行（{@code java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version}），
 * {@code AgentOS-boot} 的启动类也委托给 {@link #execute(String...)}，两边共用同一套命令解析。
 *
 * <p>这里使用 Picocli。Picocli 负责把命令行字符串，例如
 * {@code AgentOS --version}，解析成 Java 方法调用；本类不负责业务逻辑。
 */
public final class AgentOSCli {

    private AgentOSCli() {}

    public static void main(String[] args) {
        // 独立运行 CLI 模块时使用这一入口。
        System.exit(execute(args));
    }

    /** 解析并执行命令，返回退出码，不调用 {@code System.exit}。 */
    public static int execute(String... args) {
        return execute(null, args);
    }

    /**
     * 由 boot 模块传入 Spring 上下文启动器；独立 CLI 保持无上下文行为，便于 init/help/version
     * 在没有数据库和模型配置时运行。
     */
    public static int execute(ContextLauncher launcher, String... args) {
        return execute(launcher, Path.of(""), System::getenv, args);
    }

    /** 嵌入式调用可指定执行目录和环境查询；不引入额外的命令行配置选项。 */
    public static int execute(
            ContextLauncher launcher,
            Path directory,
            Function<String, String> environment,
            String... args) {
        return execute(launcher, directory, environment, new SystemCliIdentity(), args);
    }

    /** 测试或嵌入式宿主可注入身份；生产入口仍固定使用当前操作系统账号。 */
    public static int execute(
            ContextLauncher launcher,
            Path directory,
            Function<String, String> environment,
            CliIdentity identity,
            String... args) {
        return commandLine(launcher, directory, environment, identity).execute(args);
    }

    public static CommandLine commandLine(
            ContextLauncher launcher, Path directory, Function<String, String> environment) {
        return commandLine(launcher, directory, environment, new SystemCliIdentity());
    }

    /** 构造命令树时注入身份，避免测试通过命令行参数伪造使用者标识。 */
    public static CommandLine commandLine(
            ContextLauncher launcher,
            Path directory,
            Function<String, String> environment,
            CliIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path executionDirectory = directory.toAbsolutePath().normalize();
        ConfigPathRef configPath = new ConfigPathRef();
        ProviderNameCatalog providers =
                new LocalProviderNameCatalog(
                        new ProviderNameProjectionLoader(),
                        executionDirectory,
                        configPath::get,
                        environment,
                        Path.of(System.getProperty("user.home", ".")));
        CommandLine commandLine = new CommandLine(new AgentOSCommand(configPath));
        commandLine.addSubcommand(
                "init",
                new InitCommand(
                        new WorkspaceInitializer(),
                        new InitModelResolver(environment, executionDirectory),
                        directory,
                        providers));
        commandLine.addSubcommand(
                "chat", new ChatCommand(launcher, directory, null, identity, providers));
        // 参数解析也可能包含用户输入原值，统一输出安全用法说明，不泄露解析器原文。
        commandLine.setParameterExceptionHandler((error, arguments) -> {
            error.getCommandLine().getErr().println("命令参数不合法，请使用 --help 检查参数");
            return 2;
        });
        return commandLine;
    }
}
