package com.agentos.boot.dev;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;

/**
 * 本地手工测试用的启动类，不连数据库，只装配 Provider 自动配置和测试 controller。
 *
 * <p>刻意不用 {@code @SpringBootApplication}、也不带 {@code @Configuration}：正式入口会扫描整个
 * {@code com.agentos} 包，如果这里是组件，它的自动配置排除项会被一并带进正式应用。
 * 现在它只在被显式当作启动源时才生效。
 *
 * <p>运行（在仓库根目录，让 .env 能被读到）：
 * {@code mvn -B -o -q install -DskipTests && java -cp "AgentOS-boot/target/classes:$(cat /tmp/boot-cp.txt)"
 * com.agentos.boot.dev.DevTestApplication}
 */
@EnableAutoConfiguration(
        excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
@ComponentScan("com.agentos.web.dev")
public class DevTestApplication {

    public static void main(String[] args) {
        // boot 模块同时带了 webflux 和 webmvc，显式指定 servlet，保证用 MVC。
        new SpringApplicationBuilder(DevTestApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("agentos.dev-test.enabled=true")
                .run(args);
    }
}
