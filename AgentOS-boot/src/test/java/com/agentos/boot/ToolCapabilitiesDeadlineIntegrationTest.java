package com.agentos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.boot.support.LocalModelServer;
import com.agentos.core.interaction.InteractionPort;
import com.agentos.core.interaction.TurnRuntimeCapabilities;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 五类慢动作各20次；每个样本只有一个整轮deadline，清理证据先于外层shutdown。 */
class ToolCapabilitiesDeadlineIntegrationTest extends ToolCapabilitiesFixture {
    @Test
    void fiveSlowKindsShareTurnDeadlineAndConfirmCleanup(ApplicationContext context)
            throws Exception {
        // 独立预热数据库、模型、HTTP、进程和MCP；不把预热样本算进分位数。
        awaitRemote(context);
        run(context, "http_get", "warm", arguments("http_get"), TurnSink.NOOP);
        run(
                context,
                "shell",
                "warm",
                Map.of("executable", python.toString(), "args", List.of("-c", "print('warm')")),
                TurnSink.NOOP);
        run(context, "mcp_fixture__echo", "warm", Map.of(), TurnSink.NOOP);
        for (String agent : List.of("allowed", "remote")) {
            Path file = workspace.resolve("agents/" + agent + "/AGENT.md");
            Files.writeString(
                    file,
                    Files.readString(file)
                            .replace("---\n使用", "settings:\n  turn_timeout: 800ms\n---\n使用"));
        }
        var evidence = new LinkedHashMap<String, Object>();
        var jdbc = context.getBean(JdbcTemplate.class);
        for (String tool :
                List.of("http_get", "mcp_fixture__echo", "shell", "ask_user", "web_search")) {
            var samples = new ArrayList<Double>();
            for (int i = 0; i < 20; i++) {
                if (tool.startsWith("mcp_")) {
                    Files.writeString(control, "{}");
                    awaitRemote(context);
                    Files.writeString(control, "{\"callDelay\":5}");
                }
                int callsBefore =
                        tool.startsWith("mcp_")
                                ? JSON.readTree(Files.readString(stats)).path("call").asInt()
                                : 0;
                long remotePid =
                        tool.startsWith("mcp_")
                                ? JSON.readTree(Files.readString(stats)).path("pid").asLong()
                                : 0;
                network.set(0);
                prompts.set(0);
                Files.deleteIfExists(workspace.resolve("slow.pid"));
                var cleaned = new AtomicBoolean();
                slow = tool.equals("http_get") || tool.equals("web_search");
                var args =
                        tool.equals("shell")
                                ? Map.<String, Object>of(
                                        "executable",
                                        python.toString(),
                                        "args",
                                        List.of(
                                                "-c",
                                                "import os,time; from pathlib import Path; Path('slow.pid').write_text(str(os.getpid()));\nwhile True: print('output', flush=True); time.sleep(.001)"))
                                : arguments(tool);
                String id = "deadline-" + i;
                model.reset(response(id, tool, args), LocalModelServer.textResponse("不应继续"));
                InteractionPort port =
                        (request, scope) -> {
                            prompts.incrementAndGet();
                            scope.register(end -> {
                                cleaned.set(true);
                                return true;
                            });
                            while (true) {
                                scope.checkpoint();
                                java.util.concurrent.locks.LockSupport.parkNanos(
                                        TimeUnit.MILLISECONDS.toNanos(5));
                            }
                        };
                long start = System.nanoTime();
                var result =
                        context.getBean(AgentService.class)
                                .process(
                                        new ProcessRequest(
                                                "test",
                                                UUID.randomUUID().toString(),
                                                tool.startsWith("mcp_") ? "remote" : "allowed",
                                                "慢动作测试",
                                                "test"),
                                        TurnSink.NOOP,
                                        new TurnRuntimeCapabilities(port));
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                samples.add(elapsed);
                assertThat(result.status())
                        .as(tool + i + " " + result)
                        .isEqualTo(TurnStatus.TIMED_OUT);
                assertThat(model.calls()).as(tool + i).isEqualTo(1);
                var rows =
                        jdbc.queryForList(
                                "select status,attempts,tool_call_id from tool_invocations where turn_id=?",
                                result.turnId());
                assertThat(rows).hasSize(1);
                // 底层取消可能先报告失败；整轮仍须超时且审计必须收尾并确认持久化。
                assertThat(rows.getFirst().get("status"))
                        .as(tool + i)
                        .isIn("timeout", "interrupted", "failed");
                assertThat(result.persistenceStatus())
                        .as(tool + i)
                        .isEqualTo(PersistenceStatus.CONFIRMED);
                assertThat(rows.getFirst())
                        .containsEntry("tool_call_id", id)
                        .containsEntry("attempts", 1);
                if (slow) {
                    assertThat(network).hasValue(1);
                    long end = start + TimeUnit.MILLISECONDS.toNanos(1800);
                    while (active.get() != 0 && System.nanoTime() < end) {
                        Thread.sleep(5);
                    }
                    assertThat(active).as("服务端已观察取消").hasValue(0);
                } else if (tool.equals("shell")) {
                    assertThat(Files.exists(workspace.resolve("slow.pid"))).isTrue();
                    long pid = Long.parseLong(Files.readString(workspace.resolve("slow.pid")));
                    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                            .as(tool + i + " pid=" + pid)
                            .isFalse();
                } else if (tool.equals("ask_user")) {
                    assertThat(prompts).hasValue(1);
                    assertThat(cleaned).isTrue();
                } else {
                    assertThat(
                                    JSON.readTree(Files.readString(stats)).path("call").asInt()
                                            - callsBefore)
                            .isEqualTo(1);
                    assertThat(
                                    ProcessHandle.of(remotePid)
                                            .map(ProcessHandle::isAlive)
                                            .orElse(false))
                            .isFalse();
                }
                assertThat((System.nanoTime() - start) / 1_000_000.0)
                        .as(tool + "共享清理窗口")
                        .isLessThanOrEqualTo(1800);
            }
            var sorted = samples.stream().sorted().toList();
            assertThat(sorted.get(18)).isLessThanOrEqualTo(1800);
            evidence.put(
                    tool,
                    Map.of(
                            "samplesMs",
                            samples,
                            "p95Ms",
                            sorted.get(18),
                            "budgetMs",
                            800,
                            "cleanupWindowMs",
                            1000,
                            "actions",
                            20,
                            "auditComplete",
                            20,
                            "cleanupConfirmed",
                            20));
        }
        slow = false;
        Files.writeString(
                Path.of("target/tool-deadline-samples.json"), JSON.writeValueAsString(evidence));
    }
}
