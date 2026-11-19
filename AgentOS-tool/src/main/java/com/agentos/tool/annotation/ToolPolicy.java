package com.agentos.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 插件治理元数据；不是通用允许/拒绝策略引擎。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolPolicy {
    boolean idempotent() default false;

    String timeout() default "30s";

    int maxResultChars() default 10000;
}
