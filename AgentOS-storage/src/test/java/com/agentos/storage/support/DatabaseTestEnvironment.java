package com.agentos.storage.support;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * 在容器启动和 Spring 读取 JDBC 属性之前检查 Docker。
 *
 * <p>本地不可用时明确跳过；CI 不可用时必须失败，不能让流水线悄悄漏掉数据库验证。
 */
public final class DatabaseTestEnvironment implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return evaluate(
                () -> DockerClientFactory.instance().isDockerAvailable(), System.getenv("CI"));
    }

    /** 可注入探测器，分支测试不需要实际连接 Docker。 */
    static ConditionEvaluationResult evaluate(BooleanSupplier dockerAvailable, String ci) {
        boolean available;
        try {
            available = dockerAvailable.getAsBoolean();
        } catch (RuntimeException unavailable) {
            // Docker 配置可能包含凭证或本机路径，不向 JUnit 异常传递原始探测详情。
            available = false;
        }
        if (available) {
            return ConditionEvaluationResult.enabled("Docker 可用，执行独立 PostgreSQL 数据库测试");
        }
        if (ci != null && "true".equalsIgnoreCase(ci.trim())) {
            throw new ExtensionConfigurationException("CI=true 且 Docker 不可用，数据库测试不允许跳过");
        }
        return ConditionEvaluationResult.disabled(
                "Docker 不可用：本地跳过 db 测试，未连接数据库；请启动 Docker Desktop");
    }
}
