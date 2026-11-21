package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.ToolContextKeys;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 全部内置工具与远程来源的共同边界，不能用直接callback调用冒充应用验收。 */
@Import(ToolCapabilityMatrixIntegrationTest.CleanupConfiguration.class)
class ToolCapabilityMatrixIntegrationTest extends ToolCapabilitiesFixture {
    @Test
    void cleanupUncertaintyStopsBatchAndModelWithConfirmedPostgresAudit(ApplicationContext context)
            throws Exception {
        var probe = context.getBean(CleanupProbe.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        var directory = Files.createDirectories(workspace.resolve("agents/cleanup"));
        Files.writeString(
                directory.resolve("AGENT.md"),
                "---\nname: cleanup\nprovider:\n  model: test-model\n"
                        + "tools: [cleanup_probe, write_file]\n---\n测试清理终止。\n");
        for (boolean businessFailure : List.of(false, true)) {
            probe.actions.set(0);
            var target = workspace.resolve("after-cleanup.txt");
            Files.deleteIfExists(target);
            String firstId = "cleanup-" + businessFailure;
            var calls =
                    List.of(
                            Map.of(
                                    "id",
                                    firstId,
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            "cleanup_probe",
                                            "arguments",
                                            JSON.writeValueAsString(
                                                    Map.of("businessFailure", businessFailure)))),
                            Map.of(
                                    "id",
                                    "next-write",
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            "write_file",
                                            "arguments",
                                            JSON.writeValueAsString(
                                                    Map.of(
                                                            "path",
                                                            "after-cleanup.txt",
                                                            "content",
                                                            "不应写入")))));
            model.reset(
                    JSON.writeValueAsString(
                            Map.of(
                                    "id",
                                    "cleanup-batch",
                                    "object",
                                    "chat.completion",
                                    "model",
                                    "test-model",
                                    "choices",
                                    List.of(
                                            Map.of(
                                                    "index",
                                                    0,
                                                    "message",
                                                    Map.of(
                                                            "role",
                                                            "assistant",
                                                            "content",
                                                            "",
                                                            "tool_calls",
                                                            calls),
                                                    "finish_reason",
                                                    "tool_calls")))),
                    LocalModelServer.textResponse("不应继续"));
            var feedback = new ArrayList<com.agentos.core.turn.TurnEventPayload.ToolFinished>();
            var result =
                    context.getBean(AgentService.class)
                            .process(
                                    new ProcessRequest(
                                            "test",
                                            UUID.randomUUID().toString(),
                                            "cleanup",
                                            "测试清理终止",
                                            "test"),
                                    new TurnSink() {
                                        // 007 起工具结束事件按单个工具发出，清理未确认时也会送达，状态为结果未确认。
                                        public void onEvent(com.agentos.core.turn.TurnEvent event) {
                                            if (event.payload()
                                                    instanceof
                                                    com.agentos.core.turn.TurnEventPayload
                                                                    .ToolFinished
                                                            finished) {
                                                feedback.add(finished);
                                            }
                                        }
                                    });
            assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
            assertThat(result.text()).contains("清理未确认", "本轮已停止");
            assertThat(model.calls()).isOne();
            assertThat(probe.actions).hasValue(1);
            assertThat(Files.exists(target)).isFalse();
            assertThat(feedback)
                    .singleElement()
                    .satisfies(finished -> {
                        // 事件状态与审计行一致：业务失败为 FAILED，否则为结果未确认。
                        assertThat(finished.status())
                                .isEqualTo(
                                        businessFailure
                                                ? com.agentos.core.turn.TurnEventPayload.ToolStatus
                                                        .FAILED
                                                : com.agentos.core.turn.TurnEventPayload.ToolStatus
                                                        .UNCONFIRMED);
                    });
            assertThat(result.persistenceStatus())
                    .isEqualTo(com.agentos.core.turn.PersistenceStatus.CONFIRMED);
            var rows =
                    jdbc.queryForList(
                            "select tool_call_id,tool_source,status,attempts,error_message from tool_invocations where turn_id=?",
                            result.turnId());
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                    .containsEntry("tool_call_id", firstId)
                    .containsEntry("tool_source", "java:cleanup-probe")
                    .containsEntry("attempts", 1)
                    .containsEntry("status", businessFailure ? "failed" : "unconfirmed");
            assertThat(rows.getFirst().get("error_message").toString()).contains("清理未确认");
            assertThat(
                            jdbc.queryForMap(
                                    "select status,final_text from agent_turns where turn_id=?",
                                    result.turnId()))
                    .containsEntry("status", "FAILED")
                    .containsEntry("final_text", result.text());
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from llm_calls where turn_id=?",
                                    Integer.class,
                                    result.turnId()))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from session_messages where turn_id=? and type='TOOL'",
                                    Integer.class,
                                    result.turnId()))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from sessions s join agent_turns t on s.session_id=t.session_id where t.turn_id=? and s.lease_owner is not null",
                                    Integer.class,
                                    result.turnId()))
                    .isZero();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CleanupConfiguration {
        @Bean("cleanup-probe")
        CleanupProbe cleanupProbe() {
            return new CleanupProbe();
        }
    }

    static class CleanupProbe {
        final AtomicInteger actions = new AtomicInteger();

        @Tool(name = "cleanup_probe", description = "模拟资源未确认清理")
        public String run(boolean businessFailure, ToolContext context) {
            actions.incrementAndGet();
            var scope = (ToolExecutionScope) context.getContext().get(ToolContextKeys.TOOL_SCOPE);
            scope.register(deadline -> false);
            if (businessFailure) {
                throw new IllegalStateException("普通业务失败");
            }
            return "已执行";
        }
    }

    @Test
    void everyToolHonorsVisibilityArgumentsAuditAndCancellation(ApplicationContext context)
            throws Exception {
        awaitRemote(context);
        var jdbc = context.getBean(JdbcTemplate.class);
        var all = new ArrayList<>(TOOLS);
        all.add("mcp_fixture__echo");
        for (String tool : all) {
            for (String mode :
                    List.of(
                            "allowed",
                            "denied",
                            "unknown-field",
                            "invalid",
                            "begin-failure",
                            "complete-failure",
                            "cancel",
                            "lease",
                            "resource")) {
                Files.writeString(workspace.resolve("sample.txt"), "before target after");
                Files.deleteIfExists(workspace.resolve("created.txt"));
                Files.deleteIfExists(workspace.resolve("action.txt"));
                Map<String, Object> args = new LinkedHashMap<>(arguments(tool));
                if (tool.equals("http_get")) {
                    args.remove("body");
                }
                if (mode.equals("invalid")) {
                    args.clear();
                    args.put("invalid", true);
                }
                if (mode.equals("unknown-field")) {
                    args.put("unexpected", true);
                }
                if (mode.equals("resource")) {
                    switch (tool) {
                        case "http_get", "http_post" -> args.put("url", "http://outside.invalid/");
                        case "read_file", "write_file", "edit_file", "list_dir", "grep", "glob" ->
                                args.put("path", "../outside");
                        case "shell" -> args.put("args", Collections.nCopies(129, "x"));
                        case "ask_user" -> args.put("question", "x".repeat(4001));
                        case "web_search" -> args.put("query", "x".repeat(1001));
                        default -> args.put("unexpected", true);
                    }
                }
                var results = new ArrayList<String>();
                var result =
                        run(
                                context,
                                tool,
                                mode,
                                args,
                                new TurnSink() {
                                    public void onToolResult(String id, String name, String value) {
                                        results.add(value);
                                    }
                                });
                boolean acted = mode.equals("allowed") || mode.equals("complete-failure");
                String label = tool + "/" + mode;
                assertThat(Files.exists(workspace.resolve("created.txt")))
                        .as(label)
                        .isEqualTo(tool.equals("write_file") && acted);
                assertThat(Files.exists(workspace.resolve("action.txt")))
                        .as(label)
                        .isEqualTo(tool.equals("shell") && acted);
                assertThat(Files.readString(workspace.resolve("sample.txt")))
                        .as(label)
                        .isEqualTo(
                                tool.equals("edit_file") && acted
                                        ? "before changed after"
                                        : "before target after");
                if (List.of("http_get", "http_post", "web_search").contains(tool)) {
                    assertThat(network.get()).as(label).isEqualTo(acted ? 1 : 0);
                }
                if (tool.equals("ask_user")) {
                    assertThat(prompts.get()).as(label).isEqualTo(acted ? 1 : 0);
                }
                if (List.of(
                                "denied",
                                "invalid",
                                "unknown-field",
                                "begin-failure",
                                "cancel",
                                "lease")
                        .contains(mode)) {
                    assertThat(accesses.get()).as(label).isZero();
                }
                var rows =
                        jdbc.queryForList(
                                "select tool_call_id,tool_source,status,attempts from tool_invocations where turn_id=?",
                                result.turnId());
                if (mode.equals("begin-failure")) {
                    assertThat(rows).as(label).isEmpty();
                } else {
                    assertThat(rows).as(label).hasSize(1);
                    assertThat(rows.getFirst())
                            .as(label)
                            .containsEntry("tool_call_id", tool + "-" + mode)
                            .containsEntry(
                                    "tool_source",
                                    tool.startsWith("mcp_") ? "mcp:fixture" : "builtin");
                    if (mode.equals("complete-failure")) {
                        assertThat(rows.getFirst()).containsEntry("status", "unconfirmed");
                    }
                    if (List.of("denied", "invalid", "unknown-field", "cancel", "lease")
                            .contains(mode)) {
                        assertThat(rows.getFirst()).containsEntry("attempts", 0);
                    }
                }
                if (!List.of("begin-failure", "complete-failure", "cancel", "lease")
                        .contains(mode)) {
                    assertThat(result.status()).as(label).isEqualTo(TurnStatus.SUCCEEDED);
                    assertThat(model.calls()).as(label).isEqualTo(2);
                    assertThat(model.requests().getLast()).as(label).contains(tool + "-" + mode);
                    assertThat(results).as(label).hasSize(1);
                }
                var visible = JSON.readTree(model.requests().getFirst()).path("tools");
                assertThat(visible.size())
                        .as(label)
                        .isEqualTo(mode.equals("denied") ? 0 : tool.startsWith("mcp_") ? 1 : 11);
            }
        }
    }
}
