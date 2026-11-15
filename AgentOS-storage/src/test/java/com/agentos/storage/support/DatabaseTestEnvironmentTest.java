package com.agentos.storage.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;

/** 数据库测试环境的分支全部离线验证，不依赖本机 Docker 状态。 */
class DatabaseTestEnvironmentTest {

    @Test
    @DisplayName("本地 Docker 可用：允许执行数据库测试")
    void enablesLocalTestsWhenDockerIsAvailable() {
        ConditionEvaluationResult result = DatabaseTestEnvironment.evaluate(() -> true, null);

        assertThat(result.isDisabled()).isFalse();
    }

    @Test
    @DisplayName("本地 Docker 不可用：明确跳过并说明没有连接数据库")
    void skipsLocalTestsWithAnExplicitReason() {
        ConditionEvaluationResult result = DatabaseTestEnvironment.evaluate(() -> false, null);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason())
                .hasValueSatisfying(
                        reason -> assertThat(reason).contains("Docker 不可用", "跳过", "未连接数据库"));
    }

    @Test
    @DisplayName("CI 中没有 Docker：报配置错误，不能作为跳过处理")
    void failsCiInsteadOfSkipping() {
        assertThatThrownBy(() -> DatabaseTestEnvironment.evaluate(() -> false, "true"))
                .isInstanceOf(ExtensionConfigurationException.class)
                .hasMessageContaining("CI=true")
                .hasMessageContaining("不允许跳过");
    }

    @Test
    @DisplayName("CI 中 Docker 可用：仍然执行数据库测试")
    void enablesCiTestsWhenDockerIsAvailable() {
        assertThat(DatabaseTestEnvironment.evaluate(() -> true, "true").isDisabled()).isFalse();
    }

    @Test
    @DisplayName("CI=false 不视为 CI，不依据变量是否存在误判")
    void acceptsExplicitLocalMode() {
        assertThat(DatabaseTestEnvironment.evaluate(() -> false, "false").isDisabled()).isTrue();
    }

    @Test
    @DisplayName("CI 标识大小写及两侧空白不改变必须执行的约束")
    void normalizesCiFlag() {
        assertThatThrownBy(() -> DatabaseTestEnvironment.evaluate(() -> false, " TRUE "))
                .isInstanceOf(ExtensionConfigurationException.class);
    }

    @Test
    @DisplayName("环境检测只探测一次，不启动容器或取得 JDBC 属性")
    void probesAvailabilityOnce() {
        AtomicInteger probes = new AtomicInteger();
        BooleanSupplier docker =
                () -> {
                    probes.incrementAndGet();
                    return false;
                };

        DatabaseTestEnvironment.evaluate(docker, null);

        assertThat(probes).hasValue(1);
    }

    @Test
    @DisplayName("探测本身抛异常：本地明确跳过，CI 必须失败，错误不携带探测器细节")
    void handlesProbeFailureWithoutLeakingDetails() {
        BooleanSupplier broken =
                () -> {
                    throw new IllegalStateException("SECRET-DOCKER-CONFIG");
                };

        assertThat(DatabaseTestEnvironment.evaluate(broken, null).isDisabled()).isTrue();
        assertThatThrownBy(() -> DatabaseTestEnvironment.evaluate(broken, "true"))
                .isInstanceOf(ExtensionConfigurationException.class)
                .hasMessageNotContaining("SECRET-DOCKER-CONFIG")
                .hasCause(null);
    }
}
