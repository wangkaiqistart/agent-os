package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** 真实启动器的属性优先级测试，不装配数据库，不替换成假 launcher。 */
class SpringContextLauncherTest {
    @TempDir Path directory;

    @Test
    void selectedWorkspaceOverridesOrdinaryConfigurationAndContextCloses() {
        var opened = new AtomicReference<ConfigurableApplicationContext>();
        var environment =
                isolatedEnvironment(
                        Map.of(
                                "agentos.workspace.root",
                                directory.resolve("wrong").toString(),
                                "spring.config.import",
                                "",
                                "logging.file.name",
                                directory.resolve("test.log").toString()));
        SpringContextLauncher launcher =
                new SpringContextLauncher(() ->
                        new SpringApplicationBuilder(Fixture.class)
                                .environment(environment)
                                .registerShutdownHook(false)
                                .initializers(opened::set));
        try (var running = launcher.launch(directory)) {
            assertThat(opened.get().getEnvironment().getProperty("agentos.workspace.root"))
                    .isEqualTo(directory.toString());
            assertThat(opened.get().getClass().getName()).doesNotContain("Servlet", "Reactive");
            assertThat(opened.get().isActive()).isTrue();
        }
        assertThat(opened.get().isActive()).isFalse();
    }

    @Test
    void nullOrNonNormalizedWorkspaceDoesNotStartContext() {
        SpringContextLauncher launcher =
                new SpringContextLauncher(() -> {
                    throw new AssertionError("不可启动");
                });
        assertThatThrownBy(() -> launcher.launch(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> launcher.launch(Path.of("relative")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> launcher.launch(directory.resolve("x/../y")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 去除机器环境与 JVM 属性，只使用合成本地连接；仍低于 launcher 的 commandLineArgs。 */
    static StandardEnvironment isolatedEnvironment(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-test", values));
        return environment;
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture {}
}
