package com.agentos.tool.registry;

import com.agentos.core.tool.ToolPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotationUtils;

/** 将方法注解和显式策略转为core治理契约；不支持的签名在官方适配前拒绝。 */
public final class ToolPolicyResolver {
    public ToolPolicy resolve(Method method) {
        rejectUnsupported(method.getGenericReturnType());
        for (Type parameter : method.getGenericParameterTypes()) {
            rejectUnsupported(parameter);
        }
        var annotation =
                AnnotationUtils.findAnnotation(
                        method, com.agentos.tool.annotation.ToolPolicy.class);
        if (annotation == null) {
            return defaults();
        }
        try {
            return resolve(
                    new ToolPolicy(
                            annotation.idempotent(),
                            DurationStyle.detectAndParse(annotation.timeout()),
                            annotation.maxResultChars()));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Java工具治理参数不合法");
        }
    }

    public ToolPolicy resolve(ToolPolicy explicit) {
        var policy = explicit == null ? defaults() : explicit;
        if (policy.maxResultChars() <= 0) {
            throw new IllegalArgumentException("工具结果上限必须为正数");
        }
        return policy;
    }

    public ToolPolicy defaults() {
        return new ToolPolicy(false, Duration.ofSeconds(30), 10000);
    }

    private void rejectUnsupported(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            rejectUnsupported(parameterized.getRawType());
            for (Type argument : parameterized.getActualTypeArguments()) {
                rejectUnsupported(argument);
            }
        } else if (type instanceof Class<?> candidate) {
            if (java.util.Optional.class.isAssignableFrom(candidate)
                    || java.util.concurrent.Future.class.isAssignableFrom(candidate)
                    || java.util.concurrent.CompletionStage.class.isAssignableFrom(candidate)
                    || org.reactivestreams.Publisher.class.isAssignableFrom(candidate)
                    || java.util.function.Function.class.isAssignableFrom(candidate)
                    || java.util.function.Supplier.class.isAssignableFrom(candidate)
                    || java.util.function.Consumer.class.isAssignableFrom(candidate)) {
                throw new IllegalArgumentException("Java工具签名不支持Optional、异步或函数类型");
            }
            if (candidate.isArray()) {
                rejectUnsupported(candidate.componentType());
            }
        }
    }
}
