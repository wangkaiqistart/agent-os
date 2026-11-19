package com.agentos.tool.sandbox;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.ApprovalRequiredException;
import com.agentos.core.tool.ApprovalSubject;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 应用层白名单沙箱。每个动作有三种结果：直接放行、需要用户授权（{@link ApprovalRequiredException}）、硬拒绝。
 *
 * <ul>
 *   <li>FILE_READ：工作区或配置读根内放行，其余拒绝。
 *   <li>FILE_WRITE：配置写根内放行；工作区内需授权；其余拒绝。details 为展示行（如写入方式、字节数）。
 *   <li>SHELL：target 为真实可执行文件；details[0] 为真实 cwd，其余为参数。预授权命令且 cwd 在其允许目录内放行；
 *       cwd 在工作区内需授权；其余拒绝。
 *   <li>HTTP：details[0] 为方法（缺省 GET）。只允许 http/https 且不带 userinfo；域名白名单内放行，其余需授权。
 * </ul>
 *
 * <p>只比较 URI 中的主机，不根据 DNS 或内网地址隐式授权。
 */
public final class WhitelistSandbox implements Sandbox {
    // Agent 定义、MCP 配置和 Bootstrap 文件决定 Agent 的能力与身份，写入只允许逐次确认。
    private static final List<Pattern> SENSITIVE_WRITES =
            List.of(
                    Pattern.compile("agents/[^/]+/AGENT\\.md"),
                    Pattern.compile("mcp_servers\\.ya?ml"),
                    Pattern.compile("(AGENTS|SOUL|USER)\\.md"));
    private static final Pattern SECRET_QUERY =
            Pattern.compile(
                    "(?i)([?&](?:[a-z_]*key|token|access_token|secret|sign|signature|password)=)[^&#]*");

    private final List<String> domains;
    private final com.agentos.tool.file.FileAccessPolicy files;
    private final com.agentos.tool.process.ShellToolProperties shell;

    public WhitelistSandbox(List<String> domains) {
        this(domains, null);
    }

    public WhitelistSandbox(List<String> domains, com.agentos.tool.file.FileAccessPolicy files) {
        this(domains, files, null);
    }

    public WhitelistSandbox(
            List<String> domains,
            com.agentos.tool.file.FileAccessPolicy files,
            com.agentos.tool.process.ShellToolProperties shell) {
        this.files = files;
        this.shell = shell;
        this.domains = domains.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        for (String entry : this.domains) {
            String host = entry.startsWith("*.") ? entry.substring(2) : entry;
            try {
                var uri = URI.create("https://" + host);
                if (host.isBlank()
                        || !host.equals(uri.getHost())
                        || uri.getPort() != -1
                        || !uri.getRawPath().isEmpty()
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        || uri.getRawUserInfo() != null
                        || host.contains("*")
                        || (entry.startsWith("*.")
                                && (host.matches("[0-9.]+") || host.contains(":")))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("HTTP 白名单必须是域名或精确 IP");
            }
        }
    }

    @Override
    public void enforce(SandboxAction action) {
        switch (action.type()) {
            case FILE_READ -> {
                if (files == null) {
                    throw new SandboxViolationException("DENIED：文件未授权");
                }
                new com.agentos.tool.file.FileTargetResolver(files).resolve(action.target(), false);
            }
            case FILE_WRITE -> checkWrite(action);
            case SHELL -> checkShell(action);
            case HTTP -> checkHttp(action);
            default -> throw new SandboxViolationException("沙箱拒绝未知动作");
        }
    }

    private void checkWrite(SandboxAction action) {
        if (files == null) {
            throw new SandboxViolationException("DENIED：文件未授权");
        }
        // 工作区与写根之外、受保护路径在解析时即硬拒绝。
        var version =
                new com.agentos.tool.file.FileTargetResolver(files).resolve(action.target(), true);
        if (files.preApprovedWrite(version.target())) {
            return;
        }
        var lines = new ArrayList<String>();
        lines.add("文件  " + version.safePath());
        lines.addAll(action.details());
        boolean sensitive =
                SENSITIVE_WRITES.stream().anyMatch(p -> p.matcher(version.safePath()).matches());
        throw new ApprovalRequiredException(
                new ApprovalSubject(
                        ActionType.FILE_WRITE,
                        "写入文件",
                        lines,
                        "file-write:workspace",
                        "工作区内的文件写入",
                        !sensitive));
    }

    private void checkShell(SandboxAction action) {
        Path executable;
        Path cwd;
        try {
            executable = Path.of(action.target());
            if (!executable.isAbsolute()
                    || !executable.equals(executable.toRealPath())
                    || !Files.isRegularFile(executable)
                    || !Files.isExecutable(executable)
                    || action.details().isEmpty()) {
                throw new IllegalArgumentException();
            }
            cwd = Path.of(action.details().getFirst());
            if (!cwd.isAbsolute() || !cwd.equals(cwd.toRealPath()) || !Files.isDirectory(cwd)) {
                throw new IllegalArgumentException();
            }
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new SandboxViolationException("DENIED：程序或工作目录不可用");
        }
        List<String> args = action.details().subList(1, action.details().size());
        if (shell != null
                && shell.preApproved(
                                executable,
                                cwd,
                                files == null ? Path.of("").toAbsolutePath() : files.workspace())
                        .isPresent()) {
            return;
        }
        var workspace = files == null ? null : files.workspaceRealPath().orElse(null);
        if (workspace == null || !cwd.startsWith(workspace)) {
            throw new SandboxViolationException("DENIED：工作目录不在工作区内，且该命令未预先授权");
        }
        String relative = workspace.relativize(cwd).toString().replace('\\', '/');
        String scope = args.isEmpty() ? executable.toString() : executable + " " + args.getFirst();
        throw new ApprovalRequiredException(
                new ApprovalSubject(
                        ActionType.SHELL,
                        "执行命令",
                        List.of(
                                "程序  " + executable,
                                "参数  " + (args.isEmpty() ? "（无）" : quote(args)),
                                "目录  " + (relative.isEmpty() ? "." : relative)),
                        "shell:" + scope,
                        "「" + scope + "」",
                        true));
    }

    private void checkHttp(SandboxAction action) {
        URI uri;
        try {
            uri = URI.create(action.target());
        } catch (RuntimeException e) {
            throw new SandboxViolationException("沙箱拒绝无效 HTTP 地址");
        }
        String host = uri.getHost();
        if (host == null
                || uri.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new SandboxViolationException("沙箱拒绝不安全的 HTTP 地址");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        // 主机名来自 URI 解析，不带 user-info、路径或查询串。
        boolean allowed =
                domains.stream()
                        .anyMatch(d ->
                                d.startsWith("*.")
                                        ? normalized.endsWith(d.substring(1))
                                                && normalized.length() > d.length() - 1
                                        : normalized.equals(d));
        if (allowed) {
            return;
        }
        String method =
                action.details().isEmpty()
                        ? "GET"
                        : action.details().getFirst().toUpperCase(Locale.ROOT);
        throw new ApprovalRequiredException(
                new ApprovalSubject(
                        ActionType.HTTP,
                        method.equals("GET") ? "访问网址" : "发送 HTTP " + method + " 请求",
                        List.of("方法  " + method, "地址  " + displayUrl(action.target())),
                        "http:" + method + ":" + normalized,
                        "「" + method + " " + normalized + "」",
                        true));
    }

    /** 展示用 URL：遮蔽查询串中的密钥类参数值。 */
    static String displayUrl(String url) {
        return SECRET_QUERY.matcher(url).replaceAll("$1***");
    }

    private static String quote(List<String> args) {
        var joined = new StringBuilder();
        for (String arg : args) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(
                    arg.isEmpty() || arg.matches(".*[\\s'\"].*")
                            ? "'" + arg.replace("'", "'\\''") + "'"
                            : arg);
        }
        return joined.toString();
    }
}
