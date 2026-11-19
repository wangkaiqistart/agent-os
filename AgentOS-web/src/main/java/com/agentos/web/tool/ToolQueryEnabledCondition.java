package com.agentos.web.tool;

/** 缺有效令牌时不注册路由，不影响本地能力。 */
public final class ToolQueryEnabledCondition
        implements org.springframework.context.annotation.Condition {
    public boolean matches(
            org.springframework.context.annotation.ConditionContext context,
            org.springframework.core.type.AnnotatedTypeMetadata metadata) {
        var properties =
                org.springframework.boot.context.properties.bind.Binder.get(
                                context.getEnvironment())
                        .bind("agentos.tools.query", ToolQueryProperties.class)
                        .orElse(new ToolQueryProperties(false, "127.0.0.1", 8080, ""));
        return properties != null && properties.enabled() && properties.validToken();
    }
}
