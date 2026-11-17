package com.agentos.boot;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/** 本地没有 Docker 时跳过数据库集成；CI 中缺 Docker 则失败，避免静默漏测。 */
public final class BootDatabaseTestEnvironment implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ignored) {
            available = false;
        }
        if (available) {
            return ConditionEvaluationResult.enabled("Docker 可用");
        }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            throw new ExtensionConfigurationException("CI=true 且 Docker 不可用");
        }
        return ConditionEvaluationResult.disabled("Docker 不可用，跳过 boot 数据库集成测试");
    }
}
