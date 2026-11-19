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
    private final Sandbox sandbox;

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
        this.workspace = workspace.toAbsolutePath().normalize();
        this.properties = properties;
        this.sandbox = sandbox;
        environment = new ProcessEnvironmentPolicy(properties);
        // 目录、事件和 begin 审计均早于真实调用，部署秘密必须在装配阶段就登记。
        redactor.registerSecrets(environment.configuredSecrets());
    }

    @Tool(name = "shell", description = "执行精确授权的真实程序与独立argv，不隐含shell解释；白名单不限制程序内部文件或网络行为")
    public String shell(
            @ToolParam(description = "真实绝对程序路径") String executable,
            @ToolParam(description = "独立参数列表，默认空", required = false) List<String> args,
            @ToolParam(description = "授权工作目录，默认工作根", required = false) String cwd,
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
        sandbox.enforce(new SandboxAction(ActionType.SHELL, executable));
        var command = properties.authorizeExecutable(executable);
        var directory = properties.authorizeCwd(command, workspace, cwd);
        var env = environment.build(command.env());
        scope.checkpoint();
        try (var process =
                ManagedProcess.start(command.executable(), args, directory, env, scope)) {
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

    private String shorten(String value) {
        int end = value.length() / 2;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
