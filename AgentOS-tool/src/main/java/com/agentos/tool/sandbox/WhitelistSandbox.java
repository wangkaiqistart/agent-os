package com.agentos.tool.sandbox;

import com.agentos.core.tool.ActionType;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.SandboxAction;
import com.agentos.core.tool.SandboxViolationException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** 默认拒绝的 HTTP 白名单；只比较 URI 中的主机，不根据 DNS 或内网地址隐式授权。 */
public final class WhitelistSandbox implements Sandbox {
    private final List<String> domains;

    public WhitelistSandbox(List<String> domains) {
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
        if (action.type() != ActionType.HTTP) {
            throw new SandboxViolationException("沙箱拒绝非 HTTP 动作");
        }
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
        boolean allowed =
                domains.stream()
                        .anyMatch(d ->
                                d.startsWith("*.")
                                        ? normalized.endsWith(d.substring(1))
                                                && normalized.length() > d.length() - 1
                                        : normalized.equals(d));
        // 主机名来自 URI 解析，不带 user-info、路径或查询串。
        if (!allowed) {
            throw new SandboxViolationException("沙箱拒绝 HTTP 域名：" + normalized);
        }
    }
}
