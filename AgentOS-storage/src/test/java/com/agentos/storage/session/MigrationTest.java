package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.storage.support.PostgresIntegrationTest;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationTest extends PostgresIntegrationTest {
    @Test
    void migratesFiveTablesWithoutVectorExtension() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "select count(*) from information_schema.tables where table_schema='public' and table_name in ('sessions','agent_turns','session_messages','llm_calls','tool_invocations')")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(5);
        }
    }
}
