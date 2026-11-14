package com.agentos.boot.dev;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;

/**
 * 本地手工测试用的启动类，不连数据库，只装配 Provider 自动配置和测试 controller。
 *
 * <p>放在 test 源码里，不会打进正式的 jar：{@link ProviderTestController} 带 SSE 端点，宪章原则 II
 * 不允许核心阶段在正式产物里提前提供 SSE 端点。
 *
 * <p>刻意不用 {@code @SpringBootApplication}、也不带 {@code @Configuration}：正式入口会扫描整个
 * {@code com.agentos} 包，如果这里是组件，它的自动配置排除项会被一并带进正式应用。
 *
 * <p>运行（在仓库根目录，让 .env 能被读到；类路径要带上各模块的 classes 和 boot 的 test-classes）：
 *
 * <pre>{@code
 * mvn -B -o -q install -DskipTests
 * mvn -B -o -q dependency:build-classpath -pl AgentOS-boot -Dmdep.includeScope=test \
 *     -Dmdep.outputFile=/tmp/boot-cp.txt
 * CP="AgentOS-boot/target/test-classes:AgentOS-boot/target/classes:$(cat /tmp/boot-cp.txt)"
 * java -cp "$CP" com.agentos.boot.dev.DevTestApplication
 * }</pre>
 */
@EnableAutoConfiguration(
        excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
@ComponentScan("com.agentos.boot.dev")
public class DevTestApplication {

    public static void main(String[] args) {
        // boot 模块同时带了 webflux 和 webmvc，显式指定 servlet，保证用 MVC。
        new SpringApplicationBuilder(DevTestApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("agentos.dev-test.enabled=true")
                .run(args);
    }
}
