package com.agentos.provider.support;

import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 测试共享配置：使用 Spring AI 官方自动配置，不在测试里裸建 Manager。
 *
 * <p>测试也应该尽量走真实的 Spring 配置路径，否则测试通过却不能证明生产环境的 Bean 装配正确。
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(ToolCallingAutoConfiguration.class)
public class TestSpringAiConfiguration {

    @Bean
    TestObservationRegistry observationRegistry() {
        // TestObservationRegistry 可以记录并断言 Observation，避免测试依赖真实监控系统。
        return TestObservationRegistry.create();
    }
}
