package com.agentos.tool.registry;

import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.ToolDescriptor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

/** 启动时发现Java插件；按对象身份去重，业务方法仅由治理执行链调用。 */
public final class JavaToolDiscovery {
    private final ToolPolicyResolver policies;
    private final Redactor redactor;
    private final ToolDefinitionValidator validator = new ToolDefinitionValidator();

    public JavaToolDiscovery(ToolPolicyResolver policies, Redactor redactor) {
        this.policies = Objects.requireNonNull(policies);
        this.redactor = Objects.requireNonNull(redactor);
    }

    public List<ToolDescriptor> discover(
            Map<String, Object> beans,
            List<ToolRegistration> registrations,
            Collection<Object> excluded) {
        var explicit = new IdentityHashMap<ToolCallback, ToolRegistration>();
        for (var registration : registrations) {
            var prior = explicit.putIfAbsent(registration.callback(), registration);
            if (prior != null && !Objects.equals(prior.policy(), registration.policy())) {
                throw new IllegalArgumentException("同一回调存在冲突治理参数");
            }
        }
        var seen = new IdentityHashMap<Object, Boolean>();
        var callbacks = new IdentityHashMap<Object, Boolean>();
        var result = new ArrayList<ToolDescriptor>();
        beans.forEach((beanName, bean) -> {
            if (bean == null
                    || excluded.stream().anyMatch(value -> value == bean)
                    || seen.put(bean, true) != null) {
                return;
            }
            String source = "java:" + safeBeanId(beanName);
            if (bean instanceof com.agentos.tool.govern.GovernedToolCallback) {
                return;
            }
            if (bean instanceof ToolCallback callback) {
                var registration = explicit.get(callback);
                result.add(
                        describe(
                                callback,
                                registration == null
                                        ? source
                                        : "java:" + safeBeanId(registration.beanId()),
                                policies.resolve(
                                        registration == null
                                                ? (com.agentos.core.tool.ToolPolicy) null
                                                : registration.policy()),
                                callback));
                callbacks.put(callback, true);
            }
            var methods = new LinkedHashMap<String, Method>();
            for (var method :
                    ReflectionUtils.getUniqueDeclaredMethods(AopUtils.getTargetClass(bean))) {
                var tool = AnnotationUtils.findAnnotation(method, Tool.class);
                if (tool != null && !method.isBridge() && !method.isSynthetic()) {
                    policies.resolve(method);
                    String name = tool.name().isBlank() ? method.getName() : tool.name();
                    if (methods.putIfAbsent(name, method) != null) {
                        throw new IllegalArgumentException("Java工具名称重复：" + source);
                    }
                }
            }
            if (!methods.isEmpty()) {
                try {
                    for (var callback : ToolCallbacks.from(bean)) {
                        var method = methods.get(callback.getToolDefinition().name());
                        var invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
                        ReflectionUtils.makeAccessible(invocable);
                        // 官方from生成定义和转换器；仅补方法可访问性/代理可调用方法，不直接反射执行。
                        var adapted =
                                org.springframework.ai.tool.method.MethodToolCallback.builder()
                                        .toolDefinition(callback.getToolDefinition())
                                        .toolMetadata(callback.getToolMetadata())
                                        .toolMethod(invocable)
                                        .toolObject(bean)
                                        .toolCallResultConverter(
                                                org.springframework.ai.tool.support.ToolUtils
                                                        .getToolCallResultConverter(method))
                                        .build();
                        result.add(
                                describe(
                                        adapted,
                                        source,
                                        policies.resolve(method),
                                        new MethodIdentity(bean, method)));
                    }
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException("Java工具定义不合法：" + source);
                }
            }
        });
        explicit.forEach((callback, registration) -> {
            if (!callbacks.containsKey(callback)
                    && excluded.stream().noneMatch(value -> value == callback)) {
                result.add(
                        describe(
                                callback,
                                "java:" + safeBeanId(registration.beanId()),
                                policies.resolve(registration.policy()),
                                callback));
            }
        });
        var names = new java.util.HashSet<String>();
        for (var descriptor : result) {
            if (!names.add(descriptor.name())) {
                throw new IllegalArgumentException(
                        "Java工具名称冲突：" + descriptor.source() + "/" + descriptor.name());
            }
        }
        return List.copyOf(result);
    }

    private record MethodIdentity(Object bean, Method method) {}

    private ToolDescriptor describe(
            ToolCallback callback,
            String source,
            com.agentos.core.tool.ToolPolicy policy,
            Object identity) {
        var definition = Objects.requireNonNull(callback.getToolDefinition());
        var descriptor =
                new ToolDescriptor(
                        definition.name(),
                        redactor.redact(definition.description()),
                        definition.inputSchema(),
                        source,
                        null,
                        policy,
                        callback.getToolMetadata(),
                        callback,
                        identity,
                        ToolDescriptor.Availability.AVAILABLE,
                        0);
        validator.validateSchema(descriptor.inputSchema());
        return descriptor;
    }

    public static boolean hasToolMethods(Class<?> type) {
        return java.util.Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(type))
                .anyMatch(method -> AnnotationUtils.findAnnotation(method, Tool.class) != null);
    }

    private String safeBeanId(String name) {
        if (name != null && name.matches("[A-Za-z0-9_-]{1,64}")) {
            return name;
        }
        try {
            return "bean-"
                    + java.util.HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    Objects.requireNonNull(name)
                                                            .getBytes(StandardCharsets.UTF_8)))
                            .substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("平台缺少SHA-256");
        }
    }
}
