package com.agentos.tool.process;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.ToolContextKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

/** 程序与argv独立传递；不隐式运行解释器，非零退出为结果且不重试。 */
public final class ShellTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path workspace;
    private final ShellToolProperties properties;
    private final ProcessEnvironmentPolicy environment;
    private final com.agentos.tool.sandbox.ToolApprovalGate gate;

    public ShellTools(Path workspace, ShellToolProperties properties) {
        this(
                workspace,
                properties,
                new com.agentos.tool.sandbox.WhitelistSandbox(List.of(), null, properties));
    }

    public ShellTools(Path workspace, ShellToolProperties properties, Sandbox sandbox) {
        this(workspace, properties, sandbox, new Redactor(List::of));
    }

    public ShellTools(
            Path workspace, ShellToolProperties properties, Sandbox sandbox, Redactor redactor) {
        this(
                workspace,
                properties,
                redactor,
                com.agentos.tool.sandbox.ToolApprovalGate.of(sandbox));
    }

    public ShellTools(
            Path workspace,
            ShellToolProperties properties,
            Redactor redactor,
            com.agentos.tool.sandbox.ToolApprovalGate gate) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.properties = properties;
        this.gate = gate;
        environment = new ProcessEnvironmentPolicy(properties);
        // 目录、事件和 begin 审计均早于真实调用，部署秘密必须在装配阶段就登记。
        redactor.registerSecrets(environment.configuredSecrets());
    }

    @Tool(
            name = "shell",
            description =
                    "执行一个程序并返回退出码与输出。参数逐个独立传递，不经过shell解释，管道、重定向、通配符不生效；"
                            + "需要这些功能时显式调用解释器，例如 executable=bash、args=[\"-c\",\"...\"]。"
                            + "未预先授权的命令执行前会请求用户确认，用户拒绝时不要重复同样的命令")
    public String shell(
            @ToolParam(description = "程序名（按PATH查找，如 git、ls）或绝对路径") String executable,
            @ToolParam(description = "独立参数列表，默认空", required = false) List<String> args,
            @ToolParam(description = "工作目录，工作区相对路径，默认工作区根", required = false) String cwd,
            ToolContext context) {
        if (context == null
                || !(context.getContext().get(ToolContextKeys.TOOL_SCOPE)
                        instanceof ToolExecutionScope scope)) {
            throw new IllegalStateException("进程工具需要显式scope");
        }
        scope.checkpoint();
        args = args == null ? List.of() : new java.util.ArrayList<>(args);
        long bytes = 0;
        if (args.size() > 128) {
            throw new ShellToolException("INVALID_ARGUMENT：参数超限");
        }
        for (String value : args) {
            if (value == null || value.indexOf(0) >= 0) {
                throw new ShellToolException("INVALID_ARGUMENT：参数无效");
            }
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 65536) {
                throw new ShellToolException("INVALID_ARGUMENT：参数超限");
            }
        }
        Path program = resolveExecutable(executable);
        Path directory = resolveCwd(cwd);
        var details = new java.util.ArrayList<String>();
        details.add(directory.toString());
        details.addAll(args);
        gate.enforce(new SandboxAction(ActionType.SHELL, program.toString(), details), context);
        // 预授权命令使用部署者为它配置的环境变量；临时授权的命令只继承基础变量。
        var env =
                environment.build(
                        properties
                                .preApproved(program, directory, workspace)
                                .map(ShellToolProperties.Command::env)
                                .orElse(java.util.Map.of()));
        scope.checkpoint();
        try (var process = ManagedProcess.start(program, args, directory, env, scope)) {
            var result = BoundedProcessOutput.collect(process, scope, properties.maxOutputBytes());
            var map = new LinkedHashMap<String, Object>();
            map.put("exitCode", result.exitCode());
            map.put("stdout", result.stdout());
            map.put("stderr", result.stderr());
            map.put("truncated", result.truncated());
            map.put("cleanupConfirmed", result.cleanupConfirmed());
            String encoded = JSON.writeValueAsString(map);
            while (encoded.length() > properties.maxResultChars()) {
                map.put("stdout", shorten((String) map.get("stdout")));
                map.put("stderr", shorten((String) map.get("stderr")));
                map.put("truncated", true);
                encoded = JSON.writeValueAsString(map);
            }
            return encoded;
        }
    }

    /** 含路径分隔符的按绝对路径处理，否则在 AgentOS 进程的 PATH 中查找；返回真实路径。 */
    private static Path resolveExecutable(String requested) {
        if (requested == null || requested.isBlank() || requested.indexOf(0) >= 0) {
            throw new ShellToolException("INVALID_ARGUMENT：程序不能为空");
        }
        try {
            if (requested.contains("/") || requested.contains(java.io.File.separator)) {
                Path path = Path.of(requested);
                if (!path.isAbsolute()) {
                    throw new ShellToolException("INVALID_ARGUMENT：程序路径须为绝对路径，或只写程序名");
                }
                return realExecutable(path);
            }
            String search = System.getenv("PATH");
            if (search != null) {
                for (String dir : search.split(java.io.File.pathSeparator)) {
                    if (dir.isBlank()) {
                        continue;
                    }
                    Path candidate = Path.of(dir).resolve(requested);
                    if (java.nio.file.Files.isRegularFile(candidate)
                            && java.nio.file.Files.isExecutable(candidate)) {
                        return candidate.toRealPath();
                    }
                }
            }
        } catch (java.io.IOException | java.nio.file.InvalidPathException invalid) {
            // 统一按找不到程序处理。
        }
        throw new ShellToolException("INVALID_ARGUMENT：找不到可执行程序 " + requested);
    }

    private static Path realExecutable(Path path) throws java.io.IOException {
        Path real = path.toRealPath();
        if (!java.nio.file.Files.isRegularFile(real) || !java.nio.file.Files.isExecutable(real)) {
            throw new java.io.IOException();
        }
        return real;
    }

    private Path resolveCwd(String requested) {
        try {
            Path cwd = requested == null || requested.isBlank() ? workspace : Path.of(requested);
            cwd = (cwd.isAbsolute() ? cwd : workspace.resolve(cwd)).toRealPath();
            if (!java.nio.file.Files.isDirectory(cwd)) {
                throw new java.io.IOException();
            }
            return cwd;
        } catch (java.io.IOException | java.nio.file.InvalidPathException invalid) {
            throw new ShellToolException("INVALID_ARGUMENT：工作目录不存在");
        }
    }

    private String shorten(String value) {
        int end = value.length() / 2;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
