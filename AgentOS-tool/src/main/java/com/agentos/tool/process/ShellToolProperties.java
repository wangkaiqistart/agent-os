package com.agentos.tool.process;

import com.agentos.core.tool.SandboxViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 精确程序授权，cwd按真实目录组件授权；默认不允许程序或环境继承。 */
@ConfigurationProperties("agentos.tools.shell")
public record ShellToolProperties(
        List<Command> commands,
        List<String> inheritEnvironment,
        @DefaultValue("65536") int maxOutputBytes,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("10000") int maxResultChars) {
    public record Command(Path executable, List<Path> allowedCwds, Map<String, String> env) {
        public Command {
            try {
                if (executable == null || !executable.isAbsolute()) {
                    throw new IOException();
                }
                executable = executable.toRealPath();
                if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                    throw new IOException();
                }
            } catch (IOException failure) {
                throw new IllegalArgumentException("命令授权程序配置无效");
            }
            allowedCwds = allowedCwds == null ? List.of() : List.copyOf(allowedCwds);
            env = env == null ? Map.of() : Map.copyOf(env);
            env.forEach((key, value) -> validateEnvironmentEntry(key, value));
        }
    }

    public ShellToolProperties {
        commands = commands == null ? List.of() : List.copyOf(commands);
        inheritEnvironment =
                inheritEnvironment == null ? List.of() : List.copyOf(inheritEnvironment);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (maxOutputBytes < 1
                || maxOutputBytes > 65536
                || maxResultChars < 128
                || maxResultChars > 10000
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("进程工具限值不合法");
        }
        if (commands.stream().map(Command::executable).distinct().count() != commands.size()) {
            throw new IllegalArgumentException("命令授权程序重复");
        }
        for (String name : inheritEnvironment) {
            validateEnvironmentEntry(name, "");
        }
        if (inheritEnvironment.stream().distinct().count() != inheritEnvironment.size()) {
            throw new IllegalArgumentException("环境继承名单重复");
        }
    }

    static void validateEnvironmentEntry(String key, String value) {
        if (key == null
                || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || value == null
                || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("环境配置字段无效");
        }
    }

    public Command authorizeExecutable(String requested) {
        try {
            if (requested == null || requested.isBlank() || requested.indexOf(0) >= 0) {
                throw new IOException();
            }
            Path path = Path.of(requested);
            if (!path.isAbsolute()) {
                throw new IOException();
            }
            Path real = path.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                throw new IOException();
            }
            return commands.stream()
                    .filter(command -> command.executable().equals(real))
                    .findFirst()
                    .orElseThrow(() -> new SandboxViolationException("DENIED：程序未授权"));
        } catch (IOException | java.nio.file.InvalidPathException invalid) {
            throw new SandboxViolationException("DENIED：程序未授权");
        }
    }

    public Path authorizeCwd(Command command, Path workspace, String requested) {
        try {
            Path cwd = requested == null ? workspace : Path.of(requested);
            cwd = (cwd.isAbsolute() ? cwd : workspace.resolve(cwd)).toRealPath();
            if (!Files.isDirectory(cwd)) {
                throw new IOException();
            }
            for (Path root : command.allowedCwds()) {
                Path canonicalRoot =
                        (root.isAbsolute() ? root : workspace.resolve(root)).toRealPath();
                if (!Files.isDirectory(canonicalRoot)) {
                    throw new IOException();
                }
                if (cwd.startsWith(canonicalRoot)) {
                    return cwd;
                }
            }
        } catch (IOException | java.nio.file.InvalidPathException invalid) {
            throw new SandboxViolationException("DENIED：工作目录未授权");
        }
        throw new SandboxViolationException("DENIED：工作目录未授权");
    }

    public static ShellToolProperties defaults(List<Command> commands) {
        return new ShellToolProperties(commands, List.of(), 65536, Duration.ofSeconds(30), 10000);
    }
}
