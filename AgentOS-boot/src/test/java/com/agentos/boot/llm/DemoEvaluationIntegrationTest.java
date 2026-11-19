package com.agentos.boot.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.AgentOSApplication;
import com.agentos.boot.BootDatabaseTestEnvironment;
import com.agentos.core.provider.CallCorrelation;
import com.agentos.core.provider.Profile;
import com.agentos.core.provider.ProviderRequest;
import com.agentos.core.provider.ProviderService;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** 显式llm且有环境凭证才运行；三个Demo每项三次官方isPass评估。 */
@Tag("llm")
@Tag("db")
@EnabledIfEnvironmentVariable(named = "AGENTOS_LLM_API_KEY", matches = ".+")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
class DemoEvaluationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir Path root;
    static final String WEATHER = "北京晴，温度22摄氏度。";
    static final String NEWS = "今日技术日报：Spring AI 2.0提供ToolCallback；AgentOS使用JDK21。";
    static final String REPOSITORY = "仓库agent-os使用Java，开放问题3个，今日提交2个。";

    @Test
    void threeDemosUseActualToolDataAndOfficialEvaluators() throws Exception {
        var http =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var actions = new java.util.concurrent.atomic.AtomicInteger();
        http.createContext(
                "/weather",
                exchange -> {
                    try (exchange) {
                        actions.incrementAndGet();
                        byte[] body = WEATHER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        http.start();
        try {
            Files.createDirectories(root.resolve("skills/news"));
            Files.writeString(root.resolve("skills/news/SKILL.md"), NEWS);
            String weatherUrl = "http://127.0.0.1:" + http.getAddress().getPort() + "/weather";
            String modelName = require("AGENTOS_LLM_MODEL");
            for (String name : List.of("weather", "news", "github")) {
                Path agent = Files.createDirectories(root.resolve("agents/" + name));
                String tools =
                        name.equals("weather")
                                ? "[http_get]"
                                : name.equals("news")
                                        ? "[read_file, http_get]"
                                        : "[repository_info]";
                String instruction =
                        name.equals("weather")
                                ? "必须调用http_get读取" + weatherUrl + "，只根据返回资料回答天气。"
                                : name.equals("news")
                                        ? "先read_file读取skills/news/SKILL.md，再http_get读取"
                                                + weatherUrl
                                                + "。只根据工具返回资料总结技术日报与天气。"
                                        : "必须调用repository_info查询仓库后总结仓库日报，不能猜测。";
                Files.writeString(
                        agent.resolve("AGENT.md"),
                        "---\nname: "
                                + name
                                + "\nprovider:\n  model: "
                                + modelName
                                + "\n  max_output_tokens: 2048\ntools: "
                                + tools
                                + "\nsettings:\n  turn_timeout: 300s\n---\n"
                                + instruction);
            }
            var environment = new StandardEnvironment();
            environment
                    .getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment
                    .getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            var values = new LinkedHashMap<String, Object>();
            values.put("spring.config.import", "");
            values.put("agentos.workspace.root", root.toString());
            values.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            values.put("spring.datasource.username", POSTGRES.getUsername());
            values.put("spring.datasource.password", POSTGRES.getPassword());
            values.put("agentos.embedding.enabled", false);
            values.put("agentos.providers.primary.base-url", require("AGENTOS_LLM_BASE_URL"));
            values.put("agentos.providers.primary.api-key", require("AGENTOS_LLM_API_KEY"));
            String deepseekKey = System.getenv("AGENTOS_DEEPSEEK_API_KEY");
            boolean independent =
                    deepseekKey != null
                            && !deepseekKey.isBlank()
                            && System.getenv("AGENTOS_DEEPSEEK_MODEL") != null;
            values.put("agentos.providers.deepseek.api-key", independent ? deepseekKey : "");
            if (independent) {
                values.put(
                        "agentos.providers.deepseek.base-url",
                        Optional.ofNullable(System.getenv("AGENTOS_DEEPSEEK_BASE_URL"))
                                .orElse("https://api.deepseek.com"));
            }
            values.put("agentos.tools.http.allowed-domains", "127.0.0.1");
            values.put("agentos.tools.file.read-allowed-paths[0]", root.toString());
            values.put("logging.file.name", root.resolve("demo.log").toString());
            environment
                    .getPropertySources()
                    .addFirst(new MapPropertySource("isolated-demo", values));
            try (var context =
                    new SpringApplicationBuilder(AgentOSApplication.class, Configuration.class)
                            .environment(environment)
                            .web(WebApplicationType.NONE)
                            .registerShutdownHook(false)
                            .run()) {
                var service = context.getBean(AgentService.class);
                var provider = context.getBean(ProviderService.class);
                Profile evaluatorProfile =
                        new Profile(
                                independent ? "deepseek" : "primary",
                                independent ? System.getenv("AGENTOS_DEEPSEEK_MODEL") : modelName,
                                null,
                                2048,
                                null);
                ChatModel evaluatorModel =
                        prompt ->
                                provider.call(
                                                new ProviderRequest(
                                                        evaluatorProfile,
                                                        prompt.getInstructions(),
                                                        List.of(),
                                                        Map.of(),
                                                        Instant.now().plusSeconds(120),
                                                        CallCorrelation.none()))
                                        .response();
                var evidence = new LinkedHashMap<String, Object>();
                for (String name : List.of("weather", "news", "github")) {
                    var result =
                            service.process(
                                    new ProcessRequest(
                                            "evaluation",
                                            UUID.randomUUID().toString(),
                                            name,
                                            "请按要求生成今日总结",
                                            "test"));
                    assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                    var rows =
                            context.getBean(JdbcTemplate.class)
                                    .queryForList(
                                            "select tool_name,tool_call_id,status,result_text from tool_invocations where turn_id=? order by id",
                                            result.turnId());
                    assertThat(rows)
                            .isNotEmpty()
                            .allSatisfy(row -> {
                                assertThat(row.get("status")).isEqualTo("success");
                                assertThat(row.get("tool_call_id")).isNotNull();
                            });
                    var names = rows.stream().map(row -> row.get("tool_name").toString()).toList();
                    assertThat(names)
                            .contains(
                                    name.equals("github")
                                            ? "repository_info"
                                            : name.equals("news") ? "read_file" : "http_get");
                    if (name.equals("news")) {
                        assertThat(names).contains("http_get");
                    }
                    // 评估资料只取实际工具返回，不能使用预期常量替代未执行的动作。
                    var documents =
                            rows.stream()
                                    .map(row -> new Document(row.get("result_text").toString()))
                                    .toList();
                    Evaluator evaluator =
                            name.equals("news")
                                    ? new RelevancyEvaluator(ChatClient.builder(evaluatorModel))
                                    : FactCheckingEvaluator.builder(
                                                    ChatClient.builder(evaluatorModel))
                                            .build();
                    var passes = new ArrayList<Boolean>();
                    for (int i = 0; i < 3; i++) {
                        passes.add(
                                evaluator
                                        .evaluate(
                                                new EvaluationRequest(
                                                        "根据实际资料总结" + name,
                                                        documents,
                                                        result.text()))
                                        .isPass());
                    }
                    evidence.put(
                            name,
                            Map.of(
                                    "isPass",
                                    passes,
                                    "provider",
                                    evaluatorProfile.provider(),
                                    "sameModel",
                                    !independent,
                                    "toolNames",
                                    names,
                                    "turnId",
                                    result.turnId().toString(),
                                    "dataCount",
                                    documents.size()));
                    Files.writeString(
                            Path.of("target/demo-evaluation-results.json"),
                            JsonMapper.builder().build().writeValueAsString(evidence));
                    assertThat(passes.stream().filter(Boolean::booleanValue).count())
                            .as(name)
                            .isGreaterThanOrEqualTo(2);
                }
                assertThat(actions.get()).isGreaterThanOrEqualTo(2);
            }
        } finally {
            http.stop(0);
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少显式测试环境变量：" + name);
        }
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        RepositoryTools repositoryTools() {
            return new RepositoryTools();
        }
    }

    static class RepositoryTools {
        @Tool(name = "repository_info", description = "读取测试仓库的当前事实")
        @com.agentos.tool.annotation.ToolPolicy(idempotent = true)
        public String repositoryInfo() {
            return REPOSITORY;
        }
    }
}
