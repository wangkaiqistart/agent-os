package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 离线Demo核对资料、调用ID与审计；真实模型能力由独立llm评估负责。 */
class ToolSummaryDemoIntegrationTest extends ToolCapabilitiesFixture {
    @Test
    void weatherNewsAndRepositoryReturnToSameEntry(ApplicationContext context) throws Exception {
        Path skill = Files.createDirectories(workspace.resolve("skills/news"));
        Files.writeString(skill.resolve("SKILL.md"), "读取公开技术资料并总结。Spring AI提供ToolCallback。");
        var evidence = new LinkedHashMap<String, Object>();
        var jdbc = context.getBean(JdbcTemplate.class);
        for (String name : List.of("weather", "news", "github")) {
            String tools =
                    name.equals("weather")
                            ? "[http_get]"
                            : name.equals("news") ? "[read_file, web_search]" : "[repository_info]";
            Path agent = Files.createDirectories(workspace.resolve("agents/" + name));
            Files.writeString(
                    agent.resolve("AGENT.md"),
                    "---\nname: "
                            + name
                            + "\nprovider:\n  model: test-model\ntools: "
                            + tools
                            + "\n---\n生成今日总结。\n");
            String answer =
                    switch (name) {
                        case "weather" -> "北京晴，22摄氏度。";
                        case "news" -> "科技日报：Spring AI使用ToolCallback。";
                        default -> "GitHub日报：agent-os仓库使用Java，开放问题3个，今日提交2个。";
                    };
            var calls = new ArrayList<String>();
            if (name.equals("weather")) {
                calls.add(response("weather-call", "http_get", Map.of("url", url() + "/weather")));
            } else if (name.equals("news")) {
                calls.add(
                        response(
                                "skill-call", "read_file", Map.of("path", "skills/news/SKILL.md")));
                calls.add(response("search-call", "web_search", Map.of("query", "Spring AI")));
            } else {
                calls.add(response("repository-call", "repository_info", Map.of()));
            }
            calls.add(LocalModelServer.textResponse(answer));
            model.reset(calls.toArray(String[]::new));
            var events = new ArrayList<String>();
            var result =
                    context.getBean(AgentService.class)
                            .process(
                                    new ProcessRequest(
                                            "demo",
                                            UUID.randomUUID().toString(),
                                            name,
                                            "生成今日总结",
                                            "test"),
                                    new TurnSink() {
                                        public void onToolResult(
                                                String id, String tool, String value) {
                                            events.add(id);
                                        }
                                    });
            assertThat(result.status()).isEqualTo(TurnStatus.SUCCEEDED);
            assertThat(result.text()).isEqualTo(answer);
            var audits =
                    jdbc.queryForList(
                            "select tool_name,tool_call_id,status,result_text from tool_invocations where turn_id=? order by id",
                            result.turnId());
            assertThat(audits).hasSize(name.equals("news") ? 2 : 1);
            assertThat(events)
                    .containsExactlyElementsOf(
                            audits.stream()
                                    .map(row -> row.get("tool_call_id").toString())
                                    .toList());
            for (var row : audits) {
                assertThat(row.get("status")).isEqualTo("success");
                assertThat(model.requests().getLast()).contains(row.get("tool_call_id").toString());
            }
            evidence.put(
                    name,
                    Map.of(
                            "status",
                            "PASS",
                            "mode",
                            "scripted-local-model",
                            "answer",
                            answer,
                            "audits",
                            audits,
                            "eventIds",
                            events,
                            "modelCalls",
                            model.calls(),
                            "definitions",
                            JSON.readTree(model.requests().getFirst()).path("tools"),
                            "evaluation",
                            "NOT_RUN"));
        }
        Files.writeString(
                Path.of("target/demo-summary-results.json"), JSON.writeValueAsString(evidence));
    }
}
