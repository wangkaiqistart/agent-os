package com.agentos.provider.support;

import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provider 测试共享配置：使用 Spring AI 官方自动配置，不在测试里裸建 Manager。 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(ToolCallingAutoConfiguration.class)
public class TestSpringAiConfiguration {

    @Bean
    TestObservationRegistry observationRegistry() {
        return TestObservationRegistry.create();
    }
}
