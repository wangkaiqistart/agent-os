package com.agentos.tool.registry;

import com.agentos.core.tool.ToolPolicy;
import org.springframework.ai.tool.ToolCallback;

/** 显式回调可选的治理配置；callback对象本身也是重复发现身份。 */
public record ToolRegistration(String beanId, ToolCallback callback, ToolPolicy policy) {
    public ToolRegistration {
        java.util.Objects.requireNonNull(beanId);
        java.util.Objects.requireNonNull(callback);
        if (beanId.isBlank()) {
            throw new IllegalArgumentException("注册来源不能为空");
        }
    }
}
