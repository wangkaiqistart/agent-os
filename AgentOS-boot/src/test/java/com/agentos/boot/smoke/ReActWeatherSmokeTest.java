package com.agentos.boot.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.AgentOSApplication;
import com.agentos.boot.BootDatabaseTestEnvironment;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

/** 显式 llm profile 才发真实请求；数据库、工作区与用户历史完全隔离。 */
@Tag("llm")
@Tag("db")
@EnabledIfEnvironmentVariable(named = "AGENTOS_LLM_API_KEY", matches = ".+")
@ExtendWith(BootDatabaseTestEnvironment.class)
@Testcontainers
class ReActWeatherSmokeTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"));

    @TempDir Path directory;

    @Test
    void primaryUsesLocalWeatherAndPersistsEveryCall() throws Exception {
        var calls = new AtomicInteger();
        HttpServer weather = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        weather.createContext(
                "/weather",
                exchange -> {
                    try (exchange) {
                        calls.incrementAndGet();
                        byte[] body =
                                "{\"city\":\"北京\",\"temperature_c\":8,\"wind_m_s\":3,\"precipitation_mm\":0,\"token\":\"SYNTHETIC_WEATHER_SECRET\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        weather.start();
        try {
            String url = "http://127.0.0.1:" + weather.getAddress().getPort() + "/weather";
            writeAgent(url);
            var environment = new StandardEnvironment();
            environment
                    .getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment
                    .getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            var values = new LinkedHashMap<String, Object>();
            values.put("spring.config.import", "");
            values.put("spring.datasource.url", POSTGRES.getJdbcUrl());
            values.put("spring.datasource.username", POSTGRES.getUsername());
            values.put("spring.datasource.password", POSTGRES.getPassword());
            values.put("agentos.providers.primary.base-url", System.getenv("AGENTOS_LLM_BASE_URL"));
            values.put("agentos.providers.primary.api-key", System.getenv("AGENTOS_LLM_API_KEY"));
            values.put("agentos.providers.deepseek.api-key", "");
            values.put("agentos.embedding.enabled", false);
            values.put("agentos.tools.http.allowed-domains", "127.0.0.1");
            values.put("logging.file.name", directory.resolve("smoke.log").toString());
            environment
                    .getPropertySources()
                    .addFirst(new MapPropertySource("weather-smoke", values));
            try (var context =
                    new SpringApplicationBuilder(AgentOSApplication.class)
                            .environment(environment)
                            .web(WebApplicationType.NONE)
                            .registerShutdownHook(false)
                            .run("--agentos.workspace.root=" + directory)) {
                var result =
                        context.getBean(AgentService.class)
                                .process(
                                        new ProcessRequest(
                                                "smoke",
                                                "weather-smoke",
                                                "weather",
                                                "查一下北京天气并告诉我穿什么",
                                                "test"));
                assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
                assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
                assertThat(result.text()).containsAnyOf("穿", "外套", "衣");
                var jdbc = jdbc();
                verifyTurn(jdbc, result.turnId(), calls.get());
                assertThat(result.iterations()).isBetween(2, 6);
                assertThat(
                                jdbc.queryForObject(
                                        "select max(iteration) from llm_calls where turn_id=?",
                                        Integer.class,
                                        result.turnId()))
                        .isEqualTo(result.iterations());
                assertSafe(Files.readString(directory.resolve("smoke.log")));
                String evidenceTarget = System.getenv("AGENTOS_WEATHER_EVIDENCE");
                if (evidenceTarget != null) {
                    Files.writeString(
                            Path.of(evidenceTarget + ".local"),
                            "model="
                                    + System.getenv("AGENTOS_LLM_MODEL")
                                    + "\niterations="
                                    + result.iterations()
                                    + "\ntool="
                                    + calls.get()
                                    + "\nllm="
                                    + jdbc.queryForObject(
                                            "select count(*) from llm_calls where turn_id=?",
                                            Integer.class,
                                            result.turnId())
                                    + "\nmessages="
                                    + jdbc.queryForObject(
                                            "select count(*) from session_messages where turn_id=?",
                                            Integer.class,
                                            result.turnId()));
                }
            }
        } finally {
            weather.stop(0);
        }
    }

    /** 额外 opt-in：真实 met.no + 可执行 JAR 的两次独立 CLI 进程，第二次使用 --new。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "AGENTOS_WEATHER_DEMO", matches = "true")
    void realWeatherCliAndNewPreserveArchivedHistory() throws Exception {
        writeAgent("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=39.9&lon=116.4");
        Path jar =
                Path.of(System.getProperty("agentos.weather-demo.jar", "target/AgentOS.jar"))
                        .toAbsolutePath();
        assertThat(jar).isRegularFile();
        runCli(jar, false);
        var jdbc = jdbc();
        var old =
                jdbc.queryForObject(
                        "select session_id from sessions where profile_name='weather' and channel='cli'",
                        java.util.UUID.class);
        Map<String, List<Map<String, Object>>> before = history(jdbc, old);
        runCli(jar, true);
        assertThat(history(jdbc, old)).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select status from sessions where session_id=?",
                                String.class,
                                old))
                .isEqualTo("archived");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from sessions where status='active' and profile_name='weather' and channel='cli'",
                                Integer.class))
                .isOne();
        var turns =
                jdbc.queryForList(
                        "select t.turn_id from agent_turns t join sessions s on t.session_id=s.session_id where s.channel='cli' order by started_at",
                        java.util.UUID.class);
        assertThat(turns).hasSize(2);
        for (var turn : turns) {
            verifyTurn(jdbc, turn, null);
        }
        assertSafe(Files.readString(directory.resolve("demo.log")));
        // 只保存可公开的回答和计数；不把连接、凭据、完整日志或用户配置写进报告。
        Path evidence = directory.resolve("weather-evidence.txt");
        Files.writeString(
                evidence,
                jdbc.queryForList(
                                "select status, iterations, final_text from agent_turns where session_id in (select session_id from sessions where channel='cli') order by started_at")
                        + "\nllm="
                        + jdbc.queryForObject(
                                "select count(*) from llm_calls where session_id in (select session_id from sessions where channel='cli')",
                                Integer.class)
                        + "\ntool="
                        + jdbc.queryForObject(
                                "select count(*) from tool_invocations where session_id in (select session_id from sessions where channel='cli')",
                                Integer.class)
                        + "\nmessages="
                        + jdbc.queryForObject(
                                "select count(*) from session_messages where session_id in (select session_id from sessions where channel='cli')",
                                Integer.class));
        String evidenceTarget = System.getenv("AGENTOS_WEATHER_EVIDENCE");
        if (evidenceTarget != null) {
            Files.copy(
                    evidence,
                    Path.of(evidenceTarget),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeAgent(String url) throws Exception {
        Path agent = Files.createDirectories(directory.resolve("agents/weather"));
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", "weather");
        frontmatter.put(
                "provider",
                Map.of(
                        "name",
                        "primary",
                        "model",
                        System.getenv("AGENTOS_LLM_MODEL"),
                        "reasoning_effort",
                        "low",
                        "max_output_tokens",
                        2048));
        frontmatter.put("tools", List.of("http_get"));
        frontmatter.put("settings", Map.of("max_iterations", 6, "turn_timeout", "300s"));
        Files.writeString(
                agent.resolve("AGENT.md"),
                "---\n"
                        + new Yaml().dump(frontmatter)
                        + "---\n你是天气穿衣助手。必须先用 http_get 读取以下网址，根据气温、风速和降水给出简短中文穿衣建议。"
                        + "不得捏造天气；取数成功后直接回答，不要重复取数。网址："
                        + url
                        + "\n");
        for (String file : List.of("AGENTS.md", "SOUL.md", "USER.md")) {
            Files.writeString(directory.resolve(file), "请简短、准确地回答。\n");
        }
    }

    private void runCli(Path jar, boolean fresh) throws Exception {
        var args =
                new java.util.ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-jar",
                                jar.toString(),
                                "chat",
                                "--workspace",
                                directory.toString(),
                                "--profile",
                                "weather",
                                "--message",
                                "查一下北京天气并告诉我穿什么"));
        if (fresh) {
            args.add("--new");
        }
        var builder =
                new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment()
                .keySet()
                .removeIf(key ->
                        key.startsWith("AGENTOS_")
                                || key.startsWith("SPRING_")
                                || key.equals("JAVA_TOOL_OPTIONS")
                                || key.equals("JDK_JAVA_OPTIONS"));
        for (String key :
                List.of("AGENTOS_LLM_API_KEY", "AGENTOS_LLM_BASE_URL", "AGENTOS_LLM_MODEL")) {
            builder.environment().put(key, System.getenv(key));
        }
        builder.environment()
                .putAll(
                        Map.of(
                                "AGENTOS_DB_URL",
                                POSTGRES.getJdbcUrl(),
                                "AGENTOS_DB_USERNAME",
                                POSTGRES.getUsername(),
                                "AGENTOS_DB_PASSWORD",
                                POSTGRES.getPassword(),
                                "AGENTOS_EMBEDDING_ENABLED",
                                "false",
                                "AGENTOS_TOOLS_HTTP_ALLOWEDDOMAINS",
                                "api.met.no",
                                "LOGGING_FILE_NAME",
                                directory.resolve("demo.log").toString(),
                                "SPRING_CONFIG_IMPORT",
                                ""));
        Path output = directory.resolve(fresh ? "cli-new.txt" : "cli.txt");
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(Duration.ofSeconds(330).toMillis(), TimeUnit.MILLISECONDS))
                    .isTrue();
            String text = Files.readString(output);
            assertSafe(text);
            assertThat(process.exitValue()).as("CLI 退出码；安全输出：%s", text).isZero();
            assertThat(text).containsAnyOf("穿", "外套", "衣");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static Map<String, List<Map<String, Object>>> history(
            JdbcTemplate jdbc, java.util.UUID session) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table :
                List.of("agent_turns", "session_messages", "llm_calls", "tool_invocations")) {
            result.put(
                    table,
                    jdbc.queryForList(
                            "select * from "
                                    + table
                                    + " where session_id=? order by "
                                    + (table.equals("agent_turns") ? "turn_id" : "id"),
                            session));
        }
        return result;
    }

    private static void verifyTurn(JdbcTemplate jdbc, java.util.UUID turn, Integer httpCalls) {
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_turns where turn_id=?",
                                String.class,
                                turn))
                .isEqualTo("SUCCEEDED");
        var records =
                jdbc.queryForList(
                        "select * from tool_invocations where turn_id=? order by id", turn);
        assertThat(records)
                .hasSizeBetween(1, 10)
                .allSatisfy(row -> {
                    assertThat(row.get("tool_name")).isEqualTo("http_get");
                    assertThat(row.get("status")).isEqualTo("success");
                    assertSafe(row.toString());
                });
        if (httpCalls != null) {
            assertThat(records).hasSize(httpCalls);
        }
        var llm = jdbc.queryForList("select * from llm_calls where turn_id=? order by id", turn);
        assertThat(llm).hasSizeBetween(2, 12);
        assertThat(
                        llm.stream()
                                .filter(row -> Boolean.TRUE.equals(row.get("success")))
                                .map(row -> ((Number) row.get("tool_call_count")).intValue())
                                .reduce(0, Integer::sum))
                .isEqualTo(records.size());
        var session =
                jdbc.queryForObject(
                        "select session_id from agent_turns where turn_id=?",
                        java.util.UUID.class,
                        turn);
        assertThat(
                        jdbc.queryForObject(
                                "select lease_owner from sessions where session_id=?",
                                String.class,
                                session))
                .isNull();
        assertThat(llm).allSatisfy(row -> assertThat(row.get("session_id")).isEqualTo(session));
        assertThat(records).allSatisfy(row -> assertThat(row.get("session_id")).isEqualTo(session));
        var messages =
                jdbc.queryForList(
                        "select * from session_messages where turn_id=? order by id", turn);
        assertThat(messages.getFirst().get("type")).isEqualTo("USER");
        assertThat(messages.getLast().get("type")).isEqualTo("ASSISTANT");
        String stored = messages.toString();
        for (var row : records) {
            String id = row.get("tool_call_id").toString();
            assertThat(stored).contains(id);
            assertThat(
                            messages.stream()
                                    .filter(m -> m.get("tool_calls") != null)
                                    .map(m -> m.get("tool_calls").toString())
                                    .reduce("", String::concat))
                    .contains(id);
            assertThat(
                            messages.stream()
                                    .filter(m -> m.get("tool_responses") != null)
                                    .map(m -> m.get("tool_responses").toString())
                                    .reduce("", String::concat))
                    .contains(id);
        }
        assertSafe(jdbc.queryForList("select * from llm_calls where turn_id=?", turn).toString());
    }

    private static void assertSafe(String text) {
        // 不让断言失败把真实密钥本身加入 Surefire 错误消息。
        for (String secret :
                List.of(System.getenv("AGENTOS_LLM_API_KEY"), "SYNTHETIC_WEATHER_SECRET")) {
            assertThat(text.contains(secret)).as("输出与审计中无密钥原值").isFalse();
        }
    }
}
