package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.provider.CallMode;
import com.agentos.core.provider.FailureKind;
import com.agentos.core.provider.LlmCallRecord;
import com.agentos.core.provider.LlmCallRecorder;
import com.agentos.provider.config.ProviderAutoConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 默认 Recorder 写出的 llm_call 事件在 ECS JSON 文件日志里字段齐全。
 *
 * <p>完全离线：{@code spring.config.location} 指向空的临时目录，不加载 application.yaml，
 * 也就不会经它的 {@code spring.config.import} 读取 .env；日志格式和文件路径由本测试的命令行参数设置。
 * 不连数据库、不调模型，Recorder 来自真实的 ProviderAutoConfiguration。
 */
class LlmCallJsonLogTest {

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @TempDir Path tempDir;

    @Test
    @DisplayName("成功与失败记录写成 ECS JSON，26 个字段都在，null 与 0 不混")
    void recorderWritesEveryFieldAsEcsJson() throws Exception {
        Path configDir = Files.createDirectory(tempDir.resolve("config"));
        Path logFile = tempDir.resolve("agentos.log");
        try (ConfigurableApplicationContext context =
                new SpringApplicationBuilder(RecorderOnlyConfig.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.config.location=" + configDir.toUri(),
                                "--logging.file.name=" + logFile,
                                "--logging.structured.format.file=ecs",
                                "--spring.main.banner-mode=off")) {
            // 证明离线：环境里没有 application.yaml 或 .env 的属性源
            List<String> sourceNames =
                    context.getEnvironment().getPropertySources().stream()
                            .map(PropertySource::getName)
                            .toList();
            assertThat(sourceNames)
                    .noneMatch(name -> name.contains(".env"))
                    .noneMatch(name -> name.contains("application.yaml"));

            LlmCallRecorder recorder = context.getBean(LlmCallRecorder.class);
            recorder.record(successRecord());
            recorder.record(failureRecord());
        }

        List<JsonNode> events =
                Files.readAllLines(logFile).stream()
                        .filter(line -> line.contains("llm_call"))
                        .map(JSON::readTree)
                        .toList();
        assertThat(events).hasSize(2);

        JsonNode success = events.get(0);
        // ECS 把 log.level 写成嵌套对象 {"log":{"level":...}}
        assertThat(success.at("/log/level").asString()).isEqualTo("INFO");
        assertFields(
                success,
                """
                {"event":"llm_call",
                 "sessionId":"11111111-1111-1111-1111-111111111111",
                 "turnId":"22222222-2222-2222-2222-222222222222",
                 "iteration":3,"provider":"primary","requestedModel":"gpt-5.6-luna",
                 "responseModel":"gpt-5.6-luna-2026-10-01","responseId":"chatcmpl-abc",
                 "mode":"STREAM","attempt":2,"finishReason":"stop","toolCallCount":0,
                 "imageCount":1,"promptTokens":120,"completionTokens":30,"totalTokens":150,
                 "cacheReadTokens":0,"cacheWriteTokens":null,"requestsRemaining":99,
                 "tokensRemaining":5000,"outputCapHonored":true,"success":true,
                 "failureKind":null,"httpStatus":200,"errorSummary":null,
                 "startedAt":"2026-11-14T08:30:00.123Z","durationMs":1532}
                """);

        JsonNode failure = events.get(1);
        assertThat(failure.at("/log/level").asString()).isEqualTo("INFO");
        assertFields(
                failure,
                """
                {"event":"llm_call","sessionId":null,"turnId":null,"iteration":null,
                 "provider":"deepseek","requestedModel":"deepseek-flash",
                 "responseModel":null,"responseId":null,"mode":"CALL","attempt":1,
                 "finishReason":null,"toolCallCount":null,"imageCount":0,
                 "promptTokens":null,"completionTokens":null,"totalTokens":null,
                 "cacheReadTokens":null,"cacheWriteTokens":null,"requestsRemaining":null,
                 "tokensRemaining":null,"outputCapHonored":null,"success":false,
                 "failureKind":"RATE_LIMITED","httpStatus":429,
                 "errorSummary":"RATE_LIMITED status=429 type=rate_limit_error",
                 "startedAt":"2026-11-14T08:30:00.123Z","durationMs":0}
                """);
    }

    /** 逐个字段比较；字段缺失时 path() 返回 MissingNode，与 null 字面量不相等。 */
    private static void assertFields(JsonNode actual, String expectedJson) {
        JsonNode expected = JSON.readTree(expectedJson);
        assertThat(expected.size()).isEqualTo(27);
        for (String name : expected.propertyNames()) {
            assertThat(actual.path(name)).as(name).isEqualTo(expected.get(name));
        }
    }

    private static LlmCallRecord successRecord() {
        return new LlmCallRecord(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                3,
                "primary",
                "gpt-5.6-luna",
                "gpt-5.6-luna-2026-10-01",
                "chatcmpl-abc",
                CallMode.STREAM,
                2,
                "stop",
                0,
                1,
                120,
                30,
                150,
                0L,
                null,
                99L,
                5000L,
                Boolean.TRUE,
                true,
                null,
                200,
                null,
                Instant.parse("2026-11-14T08:30:00.123Z"),
                1532L);
    }

    private static LlmCallRecord failureRecord() {
        return new LlmCallRecord(
                null,
                null,
                null,
                "deepseek",
                "deepseek-flash",
                null,
                null,
                CallMode.CALL,
                1,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                FailureKind.RATE_LIMITED,
                429,
                "RATE_LIMITED status=429 type=rate_limit_error",
                Instant.parse("2026-11-14T08:30:00.123Z"),
                0L);
    }

    /** 只导入 Provider 自动配置，拿到默认 Recorder；不启用其他自动配置。 */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ProviderAutoConfiguration.class)
    static class RecorderOnlyConfig {}
}
