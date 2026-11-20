package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnSink;
import com.agentos.core.turn.TurnStatus;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** 交互式 chat 使用流式输出：文字到达即显示、回答不重复打印、工具直接返回的结果仍打印。 */
class ChatCommandStreamingTest {
    @TempDir Path workspace;
    private final AgentService service = mock(AgentService.class);

    /** 记录每段文字第一次出现在终端上的时刻。 */
    private static final class TimedScreen extends Writer {
        final StringBuilder text = new StringBuilder();
        final ConcurrentHashMap<String, Long> shown = new ConcurrentHashMap<>();

        @Override
        public synchronized void write(char[] buffer, int offset, int length) {
            text.append(buffer, offset, length);
            long now = System.nanoTime();
            for (String chunk : List.of("第一段", "第二段", "第三段")) {
                if (text.indexOf(chunk) >= 0) {
                    shown.putIfAbsent(chunk, now);
                }
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        synchronized String screen() {
            return text.toString();
        }
    }

    private ContextLauncher launcher() {
        return root ->
                new ContextLauncher.RunningContext() {
                    @Override
                    public AgentService agentService() {
                        return service;
                    }

                    @Override
                    public void close() {}
                };
    }

    private int chat(TimedScreen screen, String input) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents/main"));
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: main\nprovider:\n  model: test-model\n---\n指令\n");
        Files.writeString(
                workspace.resolve("application.yaml"),
                "agentos:\n  providers:\n    primary:\n      type: openai\n");
        var command =
                new ChatCommand(launcher(), workspace, new BufferedReader(new StringReader(input)));
        var line = new CommandLine(command);
        line.setOut(new PrintWriter(screen, true));
        line.setErr(new PrintWriter(screen, true));
        return line.execute("--workspace", workspace.toString());
    }

    private static Consumer<TurnEventPayload> sender(TurnSink sink, UUID turn) {
        UUID session = UUID.randomUUID();
        long[] seq = {0};
        return payload ->
                sink.onEvent(new TurnEvent(1, session, turn, ++seq[0], Instant.now(), payload));
    }

    @Test
    void textAppearsWithinOneSecondOfArrivalAndAnswerIsNotRepeated() throws Exception {
        var arrivals = new ConcurrentHashMap<String, Long>();
        AtomicReference<TurnSink> used = new AtomicReference<>();
        when(service.process(any(), any(), any()))
                .thenAnswer(invocation -> {
                    TurnSink sink = invocation.getArgument(1);
                    used.set(sink);
                    UUID turn = UUID.randomUUID();
                    var send = sender(sink, turn);
                    send.accept(new TurnEventPayload.RunStarted("main", "cli"));
                    send.accept(new TurnEventPayload.StepStarted(1));
                    send.accept(new TurnEventPayload.MessageStarted("m1", 1, null));
                    for (String chunk : List.of("第一段", "第二段", "第三段")) {
                        Thread.sleep(300);
                        arrivals.put(chunk, System.nanoTime());
                        send.accept(new TurnEventPayload.MessageDelta("m1", chunk));
                    }
                    send.accept(new TurnEventPayload.MessageEnded("m1"));
                    send.accept(
                            new TurnEventPayload.RunCompleted(
                                    "第一段第二段第三段", 1, PersistenceStatus.CONFIRMED));
                    return new TurnResult(
                            turn,
                            TurnStatus.SUCCEEDED,
                            "第一段第二段第三段",
                            1,
                            PersistenceStatus.CONFIRMED);
                });
        var screen = new TimedScreen();

        assertThat(chat(screen, "你好\n/exit\n")).isZero();

        assertThat(used.get().streaming()).isTrue();
        for (String chunk : List.of("第一段", "第二段", "第三段")) {
            long lag = screen.shown.get(chunk) - arrivals.get(chunk);
            assertThat(lag).as(chunk + " 显示延迟").isLessThan(1_000_000_000L);
        }
        assertThat(screen.screen().split("第一段第二段第三段", -1)).hasSize(2);
        Files.createDirectories(Path.of("target", "007-demo"));
        StringBuilder transcript = new StringBuilder(screen.screen());
        transcript.append("\n--- 每段文字从到达到显示的时间差 ---\n");
        for (String chunk : List.of("第一段", "第二段", "第三段")) {
            transcript
                    .append(chunk)
                    .append(": ")
                    .append((screen.shown.get(chunk) - arrivals.get(chunk)) / 1_000)
                    .append(" µs\n");
        }
        Files.writeString(Path.of("target", "007-demo", "chat-streaming.txt"), transcript);
    }

    @Test
    void directToolResultIsStillPrinted() throws Exception {
        when(service.process(any(), any(), any()))
                .thenAnswer(invocation -> {
                    TurnSink sink = invocation.getArgument(1);
                    UUID turn = UUID.randomUUID();
                    var send = sender(sink, turn);
                    send.accept(new TurnEventPayload.RunStarted("main", "cli"));
                    send.accept(new TurnEventPayload.StepStarted(1));
                    send.accept(new TurnEventPayload.MessageStarted("m1", 1, null));
                    send.accept(new TurnEventPayload.MessageDelta("m1", "查一下"));
                    send.accept(new TurnEventPayload.MessageEnded("m1"));
                    send.accept(
                            new TurnEventPayload.ToolStarted("c1", "weather", "builtin", 1, "{}"));
                    send.accept(
                            new TurnEventPayload.ToolFinished(
                                    "c1",
                                    "weather",
                                    TurnEventPayload.ToolStatus.SUCCESS,
                                    5,
                                    false,
                                    "sunny",
                                    true));
                    send.accept(
                            new TurnEventPayload.RunCompleted(
                                    "DIRECT_RESULT", 1, PersistenceStatus.CONFIRMED));
                    return new TurnResult(
                            turn,
                            TurnStatus.SUCCEEDED,
                            "DIRECT_RESULT",
                            1,
                            PersistenceStatus.CONFIRMED);
                });
        var screen = new TimedScreen();

        assertThat(chat(screen, "天气\n/exit\n")).isZero();

        assertThat(screen.screen()).contains("查一下", "⚙ weather", "✓ weather  成功", "DIRECT_RESULT");
        Files.createDirectories(Path.of("target", "007-demo"));
        Files.writeString(Path.of("target", "007-demo", "chat-tools.txt"), screen.screen());
    }
}
