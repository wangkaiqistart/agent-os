package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.cli.AgentOSCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** 真正执行 CLI init/chat、boot、模型替身及 PostgreSQL，不绕过默认选择与启动器。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
class DefaultAgentCliIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir Path directory;

    @Test
    void initThenDefaultChatLoadsOnlyChosenWorkspaceAndPersistsConfirmedTurn() throws Exception {
        Path chosen = directory.resolve("chosen");
        Path conflicting = directory.resolve(".AgentOS");
        // 两个目录都由 init 生成 Agent；测试不手写 main。
        assertThat(
                        AgentOSCli.execute(
                                null,
                                directory,
                                key -> null,
                                "init",
                                "--workspace",
                                conflicting.toString(),
                                "--model",
                                "wrong-model"))
                .isZero();
        Files.writeString(conflicting.resolve("AGENTS.md"), "WRONG_WORKSPACE_MARKER");
        Map<Path, String> oldFiles = new LinkedHashMap<>();
        try (var files = Files.walk(conflicting)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                oldFiles.put(file, Files.readString(file));
            }
        }
        assertThat(
                        AgentOSCli.execute(
                                null,
                                directory,
                                key -> null,
                                "init",
                                "--workspace",
                                chosen.toString(),
                                "--model",
                                "cli-test-model"))
                .isZero();
        Files.writeString(chosen.resolve("AGENTS.md"), "CHOSEN_BOOTSTRAP_MARKER");
        try (LocalModelServer model = LocalModelServer.start()) {
            var opened = new AtomicReference<ConfigurableApplicationContext>();
            var values = new LinkedHashMap<String, Object>();
            values.put("spring.config.import", "");
            values.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            values.put("spring.datasource.username", POSTGRES.getUsername());
            values.put("spring.datasource.password", POSTGRES.getPassword());
            values.put("agentos.workspace.root", conflicting.toString());
            values.put("agentos.providers.primary.base-url", model.baseUrl() + "/v1");
            values.put("agentos.providers.primary.api-key", "synthetic-local-key");
            values.put("agentos.providers.deepseek.api-key", "");
            values.put("agentos.embedding.enabled", false);
            values.put("logging.file.name", directory.resolve("test.log").toString());
            var launcher =
                    new SpringContextLauncher(() ->
                            new SpringApplicationBuilder(AgentOSApplication.class)
                                    .environment(
                                            SpringContextLauncherTest.isolatedEnvironment(values))
                                    .registerShutdownHook(false)
                                    .initializers(opened::set));
            assertThat(
                            AgentOSCli.execute(
                                    launcher,
                                    directory,
                                    key -> null,
                                    "chat",
                                    "--workspace",
                                    chosen.toString(),
                                    "--message",
                                    "你好"))
                    .isZero();
            assertThat(opened.get().isActive()).isFalse();
            assertThat(opened.get().getEnvironment().getProperty("agentos.workspace.root"))
                    .isEqualTo(chosen.toString());
            assertThat(opened.get().getClass().getName()).doesNotContain("Servlet", "Reactive");
            assertThat(model.calls()).isOne();
            var request = JsonMapper.builder().build().readTree(model.requests().getFirst());
            assertThat(request.path("model").asString()).isEqualTo("cli-test-model");
            assertThat(request.path("tools").isMissingNode() || request.path("tools").isEmpty())
                    .isTrue();
            String messages = request.path("messages").toString();
            assertThat(messages)
                    .contains("CHOSEN_BOOTSTRAP_MARKER", "助手")
                    .doesNotContain("WRONG_WORKSPACE_MARKER");

            // 关闭第一个 Spring 上下文后重新启动；第二条消息应沿同一 active 会话恢复第一轮历史。
            assertThat(
                            AgentOSCli.execute(
                                    launcher,
                                    directory,
                                    key -> null,
                                    "chat",
                                    "--workspace",
                                    chosen.toString(),
                                    "--message",
                                    "继续刚才的话题"))
                    .isZero();
            assertThat(model.calls()).isEqualTo(2);
            var resumed = JsonMapper.builder().build().readTree(model.requests().get(1));
            assertThat(resumed.path("messages").toString())
                    .contains("你好", "local answer", "继续刚才的话题");
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    POSTGRES.getJdbcUrl(),
                                    POSTGRES.getUsername(),
                                    POSTGRES.getPassword()));
            assertThat(jdbc.queryForObject("select count(*) from sessions", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("select profile_name from sessions", String.class))
                    .isEqualTo("main");
            assertThat(jdbc.queryForObject("select lease_owner from sessions", String.class))
                    .isNull();
            assertThat(jdbc.queryForObject("select count(*) from agent_turns", Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from agent_turns where status = 'SUCCEEDED'",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForList(
                                    "select type from session_messages order by id", String.class))
                    .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
            assertThat(jdbc.queryForObject("select count(*) from llm_calls", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from tool_invocations", Integer.class))
                    .isZero();
        }
        for (var old : oldFiles.entrySet()) {
            assertThat(Files.readString(old.getKey())).isEqualTo(old.getValue());
        }
    }
}
