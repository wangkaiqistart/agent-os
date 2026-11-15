package com.agentos.storage.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 仅连接独立 Testcontainers PostgreSQL 的数据库测试基类。
 *
 * <p>环境条件必须放在 Testcontainers 扩展之前；条件不通过时不能启动容器或读取 JDBC 属性。
 * 不使用 disabledWithoutDocker：它会把 CI 的数据库缺失同样当成跳过。
 * 子类仍须显式选择自己的 Spring 测试配置，本类不加载应用配置或 .env。
 */
@Tag("db")
@ExtendWith(DatabaseTestEnvironment.class)
@Testcontainers
public abstract class PostgresIntegrationTest {

    @Container
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    protected static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.open-in-view", () -> false);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.clean-disabled", () -> true);
        registry.add("spring.docker.compose.enabled", () -> false);
    }
}
