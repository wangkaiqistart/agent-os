package com.agentos.storage.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Phase 1 只证明容器及动态连接属性有效，不提前建表或执行迁移 spike。
 *
 * <p>CI 检查本用例实际执行，不能用环境分支的单元测试代替数据库连接证据。
 */
class PostgresConnectivityTest extends PostgresIntegrationTest {

    @Test
    @DisplayName("通过动态属性连接独立 PostgreSQL，执行只读 SELECT 1")
    void connectsUsingContainerProperties() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        DynamicPropertyRegistry registry = (name, supplier) -> properties.put(name, supplier.get());
        databaseProperties(registry);

        assertThat(properties.get("spring.datasource.url")).isEqualTo(POSTGRES.getJdbcUrl());
        assertThat(properties.get("spring.jpa.open-in-view")).isEqualTo(false);
        assertThat(properties.get("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(properties.get("spring.flyway.clean-disabled")).isEqualTo(true);
        try (Connection connection =
                        DriverManager.getConnection(
                                (String) properties.get("spring.datasource.url"),
                                (String) properties.get("spring.datasource.username"),
                                (String) properties.get("spring.datasource.password"));
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }
}
