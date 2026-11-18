package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.cli.AgentOSCli;
import com.agentos.cli.CliIdentity;
import com.agentos.cli.ContextLauncher;
import com.agentos.core.session.SessionManager;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** 通过真实 CLI 适配器验证身份隔离和 --new 的数据库边界。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class CliAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    static final JsonMapper JSON = JsonMapper.builder().build();
    static Path workspace;
    static LocalModelServer model;

    @jakarta.annotation.Resource AgentService service;
    @jakarta.annotation.Resource SessionManager sessions;
    @jakarta.annotation.Resource JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() throws Exception {
        workspace = Files.createTempDirectory("cli-acceptance");
        Files.writeString(
                workspace.resolve("application.yaml"),
                "agentos:\n  providers:\n    primary:\n      type: openai\n");
        writeAgent("main");
        writeAgent("isolated");
        model = LocalModelServer.start();
    }

    @AfterAll
    static void close() {
        if (model != null) {
            model.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "local-test-key");
        registry.add("agentos.embedding.enabled", () -> false);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
    }

    @Test
    void unknownToolReportsAgentFileAndNameWithoutExternalCallsOrRecords() throws Exception {
        model.reset();
        String profile = "unknown-tool-" + UUID.randomUUID();
        String user = "cli-unknown-" + UUID.randomUUID();
        Path agent = Files.createDirectories(workspace.resolve("agents").resolve(profile));
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\nname: "
                        + profile
                        + "\nprovider:\n  model: test-model\ntools: [does_not_exist]\n---\nprivate-agent-body\n");
        var output = new java.io.StringWriter();
        var line = AgentOSCli.commandLine(sharedLauncher(), workspace, key -> null, () -> user);
        line.setOut(new java.io.PrintWriter(output));
        line.setErr(new java.io.PrintWriter(output));

        assertThat(
                        line.execute(
                                "--config",
                                workspace.resolve("application.yaml").toString(),
                                "chat",
                                "--workspace",
                                workspace.toString(),
                                "--profile",
                                profile,
                                "--message",
                                "private-user-message"))
                .isEqualTo(2);
        assertThat(output.toString())
                .contains("agents/" + profile + "/AGENT.md", "tools", "does_not_exist")
                .doesNotContain("private-agent-body", "private-user-message", "Exception");
        assertThat(model.requests()).isEmpty();
        assertThat(counts(user, profile)).isEqualTo(new Counts(0, 0, 0, 0, 0, 0));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from tool_invocations i join sessions s on s.session_id=i.session_id"
                                        + " where s.channel='cli' and s.user_id=? and s.profile_name=?",
                                Integer.class,
                                user,
                                profile))
                .isZero();
    }

    @Test
    void identityAndAgentIsolationMatrixHasTwentyPairsWithoutHistoryLeaks() throws Exception {
        model.reset();
        int pairs = 20;
        int leaks = 0;
        for (int i = 0; i < pairs; i++) {
            String userA = "cli-isolation-a-" + UUID.randomUUID();
            String userB = "cli-isolation-b-" + UUID.randomUUID();
            String markerA = "identity-a-" + i + "-" + UUID.randomUUID();
            String markerB = "agent-b-" + i + "-" + UUID.randomUUID();

            assertThat(chat(userA, "main", markerA, false)).isZero();
            assertThat(chat(userB, "isolated", markerB, false)).isZero();
            assertThat(chat(userA, "main", markerA + " followup", false)).isZero();
            assertThat(chat(userB, "isolated", markerB + " followup", false)).isZero();

            int base = i * 4;
            leaks += countLeaks(requestMessages(base), markerA, markerB);
            leaks += countLeaks(requestMessages(base + 1), markerB, markerA);
            leaks += countLeaks(requestMessages(base + 2), markerA, markerB);
            leaks += countLeaks(requestMessages(base + 3), markerB, markerA);
            assertThat(countSessions(userA, "main")).isEqualTo(1);
            assertThat(countSessions(userB, "isolated")).isEqualTo(1);
        }
        assertThat(model.requests()).hasSize(pairs * 4);
        assertThat(leaks).isZero();
        writeMetric(
                "cli-identity-isolation.json",
                Map.of("status", "PASS", "runs", pairs, "leaks", leaks));
    }

    @Test
    void idleNewSessionMatrixUsesRealCliAndPreservesArchivedHistory() throws Exception {
        model.reset();
        String user = "cli-new-idle-" + UUID.randomUUID();
        int runs = 20;
        for (int i = 0; i < runs; i++) {
            Counts before = counts(user, "main");
            assertThat(chat(user, "main", "idle-new-" + i, true)).isZero();
            Counts after = counts(user, "main");
            assertThat(after.sessions()).isEqualTo(before.sessions() + 1);
            assertThat(after.activeSessions()).isOne();
            assertThat(after.archivedSessions()).isEqualTo(after.sessions() - 1);
            assertThat(after.turns()).isEqualTo(before.turns() + 1);
            assertThat(after.messages()).isEqualTo(before.messages() + 2);
            assertThat(after.llmCalls()).isEqualTo(before.llmCalls() + 1);
        }
        Counts result = counts(user, "main");
        assertThat(result.sessions()).isEqualTo(runs);
        assertThat(result.archivedSessions()).isEqualTo(runs - 1);
        writeMetric(
                "cli-new-session-idle.json",
                Map.of(
                        "status",
                        "PASS",
                        "idle_runs",
                        runs,
                        "idle_failures",
                        0,
                        "idle_archived_sessions",
                        result.archivedSessions()));
    }

    @Test
    void busyNewSessionMatrixUsesRealCliAndAddsNoRecords() throws Exception {
        model.reset();
        String user = "cli-new-busy-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.onRequest(() -> {
            entered.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("模型闸门被中断");
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending =
                    executor.submit(() ->
                            service.process(
                                    new ProcessRequest(
                                            "cli", user, "main", "busy-seed", "acceptance")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Counts before = counts(user, "main");
            int runs = 20;
            int failures = 0;
            for (int i = 0; i < runs; i++) {
                if (chat(user, "main", "busy-new-" + i, true) != 3
                        || !counts(user, "main").equals(before)) {
                    failures++;
                }
            }
            assertThat(failures).isZero();
            release.countDown();
            var result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
            writeMetric(
                    "cli-new-session-busy.json",
                    Map.of(
                            "status",
                            "PASS",
                            "busy_runs",
                            runs,
                            "busy_failures",
                            failures,
                            "busy_new_records",
                            0));
        } finally {
            release.countDown();
        }
    }

    private static void writeAgent(String name) throws Exception {
        Path directory = Files.createDirectories(workspace.resolve("agents").resolve(name));
        Files.writeString(
                directory.resolve("AGENT.md"),
                "---\nname: " + name + "\nprovider:\n  model: test-model\n---\n隔离验收助手\n");
    }

    private int chat(String user, String profile, String message, boolean newSession) {
        var arguments =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                "--config",
                                workspace.resolve("application.yaml").toString(),
                                "chat",
                                "--workspace",
                                workspace.toString(),
                                "--profile",
                                profile,
                                "--message",
                                message));
        if (newSession) {
            arguments.add("--new");
        }
        return AgentOSCli.execute(
                sharedLauncher(),
                workspace,
                key -> null,
                (CliIdentity) () -> user,
                arguments.toArray(String[]::new));
    }

    private ContextLauncher sharedLauncher() {
        return root ->
                new ContextLauncher.RunningContext() {
                    @Override
                    public AgentService agentService() {
                        return service;
                    }

                    @Override
                    public SessionManager sessionManager() {
                        return sessions;
                    }

                    @Override
                    public void close() {
                        // 测试复用同一个真实 Spring 上下文；每个 CLI 执行仍通过 try-with-resources 关闭适配器。
                    }
                };
    }

    private String requestMessages(int index) throws Exception {
        return JSON.readTree(model.requests().get(index)).path("messages").toString();
    }

    private static int countLeaks(String messages, String own, String other) {
        return messages.contains(own) && !messages.contains(other) ? 0 : 1;
    }

    private int countSessions(String user, String profile) {
        return jdbc.queryForObject(
                "select count(*) from sessions where channel='cli' and user_id=? and profile_name=?",
                Integer.class,
                user,
                profile);
    }

    private Counts counts(String user, String profile) {
        String predicate = " where s.channel='cli' and s.user_id=? and s.profile_name=?";
        int all =
                jdbc.queryForObject(
                        "select count(*) from sessions s" + predicate,
                        Integer.class,
                        user,
                        profile);
        int active =
                jdbc.queryForObject(
                        "select count(*) from sessions s" + predicate + " and s.status='active'",
                        Integer.class,
                        user,
                        profile);
        int archived =
                jdbc.queryForObject(
                        "select count(*) from sessions s" + predicate + " and s.status='archived'",
                        Integer.class,
                        user,
                        profile);
        int turns =
                jdbc.queryForObject(
                        "select count(*) from agent_turns t join sessions s on s.session_id=t.session_id"
                                + predicate,
                        Integer.class,
                        user,
                        profile);
        int messages =
                jdbc.queryForObject(
                        "select count(*) from session_messages m join sessions s on s.session_id=m.session_id"
                                + predicate,
                        Integer.class,
                        user,
                        profile);
        int llmCalls =
                jdbc.queryForObject(
                        "select count(*) from llm_calls l join sessions s on s.session_id=l.session_id"
                                + predicate,
                        Integer.class,
                        user,
                        profile);
        return new Counts(all, active, archived, turns, messages, llmCalls);
    }

    private static void writeMetric(String file, Map<String, ?> values) throws Exception {
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.writeString(
                target.resolve(file), JSON.writeValueAsString(new LinkedHashMap<>(values)));
    }

    private record Counts(
            int sessions,
            int activeSessions,
            int archivedSessions,
            int turns,
            int messages,
            int llmCalls) {}
}
