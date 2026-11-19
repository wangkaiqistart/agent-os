package com.agentos.core.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** 内部注册描述；查询投影必须显式选择安全字段，不能序列化本对象。 */
public record ToolDescriptor(
        String name,
        String description,
        String inputSchema,
        String source,
        String originalName,
        ToolPolicy policy,
        ToolMetadata metadata,
        ToolCallback callback,
        Object registrationIdentity,
        Availability availability,
        long generation) {
    public ToolDescriptor {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("工具公开名不合法");
        }
        requireSource(source);
        if (!source.startsWith("mcp:") && name.startsWith("mcp_")) {
            throw new IllegalArgumentException("本地工具不能使用MCP保留前缀");
        }
        if (description == null || description.isBlank() || description.length() > 4000) {
            throw new IllegalArgumentException("工具描述必须为1至4000字符");
        }
        java.util.Objects.requireNonNull(inputSchema);
        java.util.Objects.requireNonNull(policy);
        java.util.Objects.requireNonNull(metadata);
        java.util.Objects.requireNonNull(callback);
        java.util.Objects.requireNonNull(registrationIdentity);
        java.util.Objects.requireNonNull(availability);
        if (generation < 0) {
            throw new IllegalArgumentException("工具代次不能为负数");
        }
    }

    public static void requireSource(String source) {
        if (source == null
                || !(source.equals("builtin")
                        || source.equals("unknown")
                        || source.matches("java:[A-Za-z0-9_-]{1,64}")
                        || source.matches("mcp:[a-z0-9-]{1,20}"))) {
            throw new IllegalArgumentException("工具来源标识不合法");
        }
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE,
        UNCONFIGURED
    }
}
