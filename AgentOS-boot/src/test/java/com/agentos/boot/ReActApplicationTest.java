package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
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

/** Boot、Provider、Storage、Core 和本地模型替身组成的最小纯文本纵向验收。 */
@Tag("db")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
@SpringBootTest(
        classes = AgentOSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "spring.config.import="})
class ReActApplicationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    private static Path workspace;
    private static LocalModelServer model;

    @BeforeAll
    static void prepareWorkspace() throws Exception {
        workspace = Files.createTempDirectory("agentos-boot");
        Path agent = workspace.resolve("agents/answer");
        Files.createDirectories(agent);
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\nname: answer\nprovider:\n  model: test-model\n---\nAnswer briefly.\n");
        model = LocalModelServer.start();
    }

    @AfterAll
    static void stopModel() {
        if (model != null) {
            model.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
        registry.add("agentos.workspace.root", () -> workspace.toString());
        registry.add("agentos.providers.primary.base-url", () -> model.baseUrl() + "/v1");
        registry.add("agentos.providers.primary.api-key", () -> "local-test-key");
        registry.add("agentos.embedding.enabled", () -> false);
    }

    @Test
    void plainTextChatPersistsSessionTurnMessagesAndOneModelCall(
            org.springframework.context.ApplicationContext context) {
        AgentService service = context.getBean(AgentService.class);
        var result =
                service.process(new ProcessRequest("cli", "boot-user", "answer", "hello", "cli"));

        assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
        assertThat(result.text()).isEqualTo("local answer");
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        assertThat(model.calls()).isOne();
        assertThat(context.getBeansOfType(LlmCallRecorder.class)).hasSize(1);
        assertThat(context.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class))
                .isEmpty();
        assertThat(context.getBeansOfType(org.springframework.ai.embedding.EmbeddingModel.class))
                .isEmpty();

        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(jdbc.queryForObject("select count(*) from sessions", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_turns", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from session_messages", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from llm_calls", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("select * from llm_calls")).doesNotContainKey("prompt");
    }
}
