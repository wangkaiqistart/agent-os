package com.agentos.storage.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.storage.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationTest extends PostgresIntegrationTest {
    @Test
    void upgradesOriginalV1WithoutRepairOrChangingHistory() throws Exception {
        // 固定已发布的 V1 快照，避免测试与被改坏的正式迁移一起漂移。
        var legacy =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .schemas("legacy_upgrade")
                        .locations("classpath:db/legacy-v1")
                        .load();
        legacy.migrate();
        assertThat(legacy.info().current().getChecksum()).isEqualTo(129473985);

        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            connection.setSchema("legacy_upgrade");
            statement.executeUpdate(
                    "INSERT INTO sessions(session_id, profile_name, channel, user_id) VALUES ('11111111-1111-1111-1111-111111111111', 'legacy', 'test', 'test')");
            statement.executeUpdate(
                    "INSERT INTO agent_turns(turn_id, session_id, trigger, status, deadline_at) VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'test', 'SUCCEEDED', now())");
            statement.executeUpdate(
                    "INSERT INTO tool_invocations(session_id, turn_id, iteration, tool_call_id, tool_name, tool_source, status, result_text, idempotent) VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 1, 'legacy-call', 'http_get', 'builtin', 'success', 'legacy result', true)");
            String history = historySnapshot(connection);

            var current =
                    Flyway.configure()
                            .dataSource(
                                    POSTGRES.getJdbcUrl(),
                                    POSTGRES.getUsername(),
                                    POSTGRES.getPassword())
                            .schemas("legacy_upgrade")
                            .load();
            // migrate 会先校验已执行脚本；不执行 repair，仅追加 V2、V3。
            assertThat(current.migrate().migrationsExecuted).isEqualTo(2);
            current.validate();
            assertThat(current.info().current().getVersion().getVersion()).isEqualTo("3");
            assertThat(current.info().applied())
                    .filteredOn(migration ->
                            migration.getVersion() != null
                                    && migration.getVersion().getVersion().equals("1"))
                    .extracting(migration -> migration.getChecksum())
                    .containsExactly(129473985);
            assertThat(historySnapshot(connection)).isEqualTo(history);

            assertThat(
                            statement.executeUpdate(
                                    "INSERT INTO tool_invocations(session_id, turn_id, iteration, tool_call_id, tool_name, tool_source, status, idempotent) VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 1, 'unconfirmed-call', 'http_get', 'builtin', 'unconfirmed', true)"))
                    .isEqualTo(1);
            assertThatThrownBy(() ->
                            statement.executeUpdate(
                                    "UPDATE tool_invocations SET status = 'invalid' WHERE tool_call_id = 'unconfirmed-call'"))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
            // V3：用户取消的轮次可以落 CANCELLED，未列出的状态仍被拒绝。
            assertThat(
                            statement.executeUpdate(
                                    "UPDATE agent_turns SET status = 'CANCELLED' WHERE turn_id = '22222222-2222-2222-2222-222222222222'"))
                    .isEqualTo(1);
            assertThatThrownBy(() ->
                            statement.executeUpdate(
                                    "UPDATE agent_turns SET status = 'CANCELLING' WHERE turn_id = '22222222-2222-2222-2222-222222222222'"))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
            assertThat(current.migrate().migrationsExecuted).isZero();
        }
    }

    private static String historySnapshot(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT to_jsonb(s)::text || to_jsonb(t)::text || to_jsonb(i)::text FROM sessions s JOIN agent_turns t USING (session_id) JOIN tool_invocations i USING (turn_id) WHERE i.tool_call_id = 'legacy-call'")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

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
            try (var constraints =
                    connection
                            .createStatement()
                            .executeQuery(
                                    "select pg_get_constraintdef(oid) from pg_constraint where conrelid='tool_invocations'::regclass and contype='c'")) {
                String definitions = "";
                while (constraints.next()) {
                    definitions += constraints.getString(1);
                }
                assertThat(definitions).contains("unconfirmed");
            }
            try (var constraints =
                    connection
                            .createStatement()
                            .executeQuery(
                                    "select pg_get_constraintdef(oid) from pg_constraint where conrelid='agent_turns'::regclass and contype='c'")) {
                String definitions = "";
                while (constraints.next()) {
                    definitions += constraints.getString(1);
                }
                assertThat(definitions).contains("CANCELLED");
            }
        }
    }
}
