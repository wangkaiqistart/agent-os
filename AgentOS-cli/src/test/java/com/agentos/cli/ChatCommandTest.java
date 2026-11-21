package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.session.SessionManager;
import com.agentos.core.tool.UnknownAgentToolException;
import com.agentos.core.turn.AgentService;
import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.ProcessRequest;
import com.agentos.core.turn.TurnResult;
import com.agentos.core.turn.TurnStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ChatCommandTest {
    @TempDir Path workspace;
    private final AgentService service = mock(AgentService.class);
    private final boolean[] closed = new boolean[1];
    private final StringWriter output = new StringWriter();

    @BeforeEach
    void config() throws Exception {
        Files.writeString(
                workspace.resolve("application.yaml"),
                "agentos:\n  providers:\n    primary:\n      type: openai\n");
    }

    private ContextLauncher launcher() {
        return root ->
                new ContextLauncher.RunningContext() {
                    @Override
                    public AgentService agentService() {
                        return service;
                    }

                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
    }

    private void agent(String name) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents").resolve(name));
        Files.writeString(
                dir.resolve("AGENT.md"),
                "---\nname: " + name + "\nprovider:\n  model: test-model\n---\n中文指令\n");
    }

    private int chat(ContextLauncher launcher, String... arguments) {
        var line = AgentOSCli.commandLine(launcher, workspace, key -> null);
        line.setOut(new PrintWriter(output));
        line.setErr(new PrintWriter(output));
        var args =
                new java.util.ArrayList<>(
                        java.util.List.of("chat", "--workspace", workspace.toString()));
        if (!java.util.List.of(arguments).contains("--message")) {
            args.addAll(java.util.List.of("--message", "hello"));
        }
        args.addAll(java.util.List.of(arguments));
        return line.execute(args.toArray(String[]::new));
    }

    @Test
    void mainWinsAndExplicitProfileOverridesEvenWhenOtherAgentIsBroken() throws Exception {
        agent("main");
        agent("custom");
        Files.writeString(workspace.resolve("agents/main/AGENT.md"), "broken");
        when(service.process(any(), any())).thenReturn(success());
        assertThat(chat(launcher(), "--profile", "custom")).isZero();
        assertThat(closed[0]).isTrue();
        var request = ArgumentCaptor.forClass(ProcessRequest.class);
        verify(service).process(request.capture(), any());
        assertThat(request.getValue().agentName()).isEqualTo("custom");
        agent("main");
        assertThat(chat(launcher())).isZero();
        verify(service, org.mockito.Mockito.times(2)).process(request.capture(), any());
        assertThat(request.getValue().agentName()).isEqualTo("main");
    }

    @Test
    void oldSingleAgentStillWorksAndMultipleAgentsListCandidates() throws Exception {
        agent("legacy");
        when(service.process(any(), any())).thenReturn(success());
        assertThat(chat(launcher())).isZero();
        agent("another");
        assertThat(
                        chat(root -> {
                            throw new AssertionError("选择失败不应启动");
                        }))
                .isEqualTo(2);
        assertThat(output.toString()).contains("another", "legacy", "--profile");
    }

    @Test
    void invalidSelectionsNeverLaunchOrFallBack() throws Exception {
        ContextLauncher forbidden =
                root -> {
                    throw new AssertionError("不应启动");
                };
        assertThat(chat(forbidden)).isEqualTo(2);
        assertThat(output.toString()).contains("init");
        agent("custom");
        Files.createDirectories(workspace.resolve("agents/main"));
        assertThat(chat(forbidden)).isEqualTo(2);
        assertThat(chat(forbidden, "--profile", "missing")).isEqualTo(2);
        assertThat(chat(forbidden, "--profile", "../custom")).isEqualTo(2);
        assertThat(chat(forbidden, "--profile", " ")).isEqualTo(2);
        verifyNoInteractions(service);
    }

    @Test
    void startupAndProcessingFailuresHaveSafeCodesAndCloseContext() throws Exception {
        agent("main");
        assertThat(
                        chat(root -> {
                            throw new IllegalStateException("synthetic-secret");
                        }))
                .isEqualTo(1);
        when(service.process(any(), any()))
                .thenThrow(new IllegalStateException("synthetic-secret"));
        assertThat(chat(launcher())).isEqualTo(3);
        assertThat(closed[0]).isTrue();
        assertThat(output.toString()).doesNotContain("synthetic-secret", "Exception");
    }

    @Test
    void duplicateToolsShowSafeConfigurationLocationWithoutLaunchingContext() throws Exception {
        agent("main");
        Files.writeString(
                workspace.resolve("agents/main/AGENT.md"),
                "---\nname: main\nprovider:\n  model: test-model\ntools: [http_get, http_get]\n---\nprivate-agent-body\n");
        assertThat(
                        chat(root -> {
                            throw new AssertionError("重复声明不得启动上下文");
                        }))
                .isEqualTo(2);
        assertThat(output.toString())
                .contains("agents/main/AGENT.md", "tools", "http_get", "重复")
                .doesNotContain("private-agent-body", "Exception");
        verifyNoInteractions(service);
    }

    @Test
    void duplicateToolErrorNeverExposesCauseDuringProcessing() throws Exception {
        agent("main");
        var error = new com.agentos.core.tool.DuplicateAgentToolException("main", "http_get");
        error.initCause(new IllegalStateException("synthetic-cause-secret"));
        when(service.process(any(), any())).thenThrow(error);
        assertThat(chat(launcher())).isEqualTo(2);
        assertThat(output.toString())
                .contains("agents/main/AGENT.md", "tools", "http_get", "重复")
                .doesNotContain("synthetic-cause-secret", "Exception");
        assertThat(closed[0]).isTrue();
    }

    @Test
    void unknownToolShowsConfigurationLocationWithoutExposingCause() throws Exception {
        agent("main");
        var error = new UnknownAgentToolException("main", "does_not_exist");
        error.initCause(new IllegalStateException("synthetic-cause-secret"));
        when(service.process(any(), any())).thenThrow(error);

        assertThat(chat(launcher())).isEqualTo(2);
        assertThat(output.toString())
                .contains("agents/main/AGENT.md 的 tools 包含未注册工具：does_not_exist")
                .doesNotContain("synthetic-cause-secret", "Exception");
        assertThat(closed[0]).isTrue();
    }

    @Test
    void unrelatedArgumentErrorsKeepSafeOutputAndDoNotExposeCauses() throws Exception {
        agent("main");
        when(service.process(any(), any()))
                .thenThrow(
                        new AgentOSException(
                                ErrorCode.INVALID_ARGUMENT,
                                "synthetic-secret",
                                new IllegalStateException("synthetic-cause-secret")));

        assertThat(chat(launcher())).isEqualTo(2);
        assertThat(output.toString())
                .contains("对话请求失败，请检查 Agent 和运行配置")
                .doesNotContain("synthetic-secret", "synthetic-cause-secret", "Exception");
        assertThat(closed[0]).isTrue();
    }

    @Test
    void unsuccessfulOrUnconfirmedTurnIsNotReportedAsSuccessAndBlankMessageDoesNotLaunch()
            throws Exception {
        agent("main");
        when(service.process(any(), any()))
                .thenReturn(
                        new TurnResult(
                                UUID.randomUUID(),
                                TurnStatus.FAILED,
                                "安全失败",
                                1,
                                PersistenceStatus.UNCONFIRMED));
        assertThat(chat(launcher())).isEqualTo(3);
        assertThat(output.toString()).contains("持久化未确认");
        assertThat(
                        chat(
                                root -> {
                                    throw new AssertionError("空消息不启动");
                                },
                                "--message",
                                " "))
                .isEqualTo(2);
    }

    @RepeatedTest(20)
    void newArchivesOnlyTheSelectedAgentBeforeProcessing() throws Exception {
        agent("main");
        when(service.process(any(), any())).thenReturn(success());
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.archiveIfIdle(any())).thenReturn(true);
        ContextLauncher withSessions =
                root ->
                        new ContextLauncher.RunningContext() {
                            @Override
                            public AgentService agentService() {
                                return service;
                            }

                            @Override
                            public SessionManager sessionManager() {
                                return sessions;
                            }
                        };

        assertThat(chat(withSessions, "--new")).isZero();
        verify(sessions)
                .archiveIfIdle(
                        org.mockito.ArgumentMatchers.argThat(key ->
                                key.channel().equals("cli") && key.profileName().equals("main")));
        verify(service).process(any(), any());
    }

    @Test
    void newRefusesToArchiveBusySessionAndDoesNotCallModel() throws Exception {
        agent("main");
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.archiveIfIdle(any()))
                .thenThrow(new AgentOSException(ErrorCode.SESSION_BUSY, "上一条消息还在处理"));
        ContextLauncher withSessions =
                root ->
                        new ContextLauncher.RunningContext() {
                            @Override
                            public AgentService agentService() {
                                return service;
                            }

                            @Override
                            public SessionManager sessionManager() {
                                return sessions;
                            }
                        };

        assertThat(chat(withSessions, "--new")).isEqualTo(3);
        verifyNoInteractions(service);
    }

    @Test
    void newWithoutAnActiveSessionStartsNormally() throws Exception {
        agent("main");
        when(service.process(any(), any())).thenReturn(success());
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.archiveIfIdle(any())).thenReturn(false);
        ContextLauncher withSessions =
                root ->
                        new ContextLauncher.RunningContext() {
                            @Override
                            public AgentService agentService() {
                                return service;
                            }

                            @Override
                            public SessionManager sessionManager() {
                                return sessions;
                            }
                        };

        assertThat(chat(withSessions, "--new")).isZero();
        verify(sessions).archiveIfIdle(any());
        verify(service).process(any(), any());
    }

    @Test
    void normalizedWorkspaceReachesLauncherAndInteractiveInputClosesOnExitOrEof() throws Exception {
        agent("main");
        when(service.process(any(), any(), any())).thenReturn(success());
        var passed = new java.util.concurrent.atomic.AtomicReference<Path>();
        ContextLauncher recording =
                root -> {
                    passed.set(root);
                    return launcher().launch(root);
                };
        for (String input : java.util.List.of("\nhello\n/exit\nignored\n", "hello\n")) {
            var command =
                    new ChatCommand(
                            recording,
                            workspace,
                            new java.io.BufferedReader(new java.io.StringReader(input)));
            var line = new picocli.CommandLine(command);
            line.setOut(new PrintWriter(output));
            line.setErr(new PrintWriter(output));
            closed[0] = false;
            assertThat(line.execute("--workspace", "x/..")).isZero();
            assertThat(passed.get()).isEqualTo(workspace.toAbsolutePath().normalize());
            assertThat(closed[0]).isTrue();
        }
        verify(service, org.mockito.Mockito.times(2)).process(any(), any(), any());
        assertThat(output.toString()).contains("消息不能为空");
    }

    @Test
    void busySingleMessageExitsThreeAndInteractiveBusyCanContinue() throws Exception {
        agent("main");
        when(service.process(any(), any()))
                .thenThrow(new AgentOSException(ErrorCode.SESSION_BUSY, "synthetic-secret"));
        assertThat(chat(launcher())).isEqualTo(3);
        assertThat(output.toString()).contains("上一条消息还在处理").doesNotContain("synthetic-secret");
        org.mockito.Mockito.doThrow(new AgentOSException(ErrorCode.SESSION_BUSY, "busy"))
                .doReturn(success())
                .when(service)
                .process(any(), any(), any());
        var command =
                new ChatCommand(
                        launcher(),
                        workspace,
                        new java.io.BufferedReader(
                                new java.io.StringReader("busy\nretry\n/exit\n")));
        var line = new picocli.CommandLine(command);
        line.setOut(new PrintWriter(output));
        line.setErr(new PrintWriter(output));
        assertThat(line.execute("--workspace", workspace.toString())).isZero();
        assertThat(output.toString()).contains("上一条消息还在处理", "answer");
        assertThat(closed[0]).isTrue();
    }

    @Test
    void injectedIdentityIsUsedForSessionRequestAndNoUserOverrideOptionExists() throws Exception {
        agent("main");
        when(service.process(any(), any())).thenReturn(success());
        var command =
                new ChatCommand(
                        launcher(),
                        workspace,
                        null,
                        () -> "synthetic-os-user",
                        () -> java.util.Set.of("primary"));
        var line = new picocli.CommandLine(command);
        line.setOut(new PrintWriter(output));
        line.setErr(new PrintWriter(output));
        assertThat(line.execute("--workspace", workspace.toString(), "--message", "hello"))
                .isZero();
        var request = ArgumentCaptor.forClass(ProcessRequest.class);
        verify(service).process(request.capture(), any());
        assertThat(request.getValue().userId()).isEqualTo("synthetic-os-user");
    }

    /** 让桩服务像真实服务一样向输出端投递一轮带工具的事件，再返回成功结果。 */
    private static Object deliverToolTurn(org.mockito.invocation.InvocationOnMock invocation) {
        com.agentos.core.turn.TurnSink sink = invocation.getArgument(1);
        UUID turn = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        long[] seq = {0};
        java.util.function.Consumer<com.agentos.core.turn.TurnEventPayload> send =
                payload ->
                        sink.onEvent(
                                new com.agentos.core.turn.TurnEvent(
                                        1,
                                        session,
                                        turn,
                                        ++seq[0],
                                        java.time.Instant.now(),
                                        payload));
        send.accept(new com.agentos.core.turn.TurnEventPayload.RunStarted("main", "cli"));
        send.accept(new com.agentos.core.turn.TurnEventPayload.StepStarted(1));
        send.accept(new com.agentos.core.turn.TurnEventPayload.MessageStarted("m1", 1, null));
        send.accept(new com.agentos.core.turn.TurnEventPayload.MessageDelta("m1", "先查"));
        send.accept(new com.agentos.core.turn.TurnEventPayload.MessageEnded("m1"));
        send.accept(
                new com.agentos.core.turn.TurnEventPayload.ToolStarted(
                        "c1", "http_get", "builtin", 1, "{\"url\":\"https://example.com\"}"));
        send.accept(
                new com.agentos.core.turn.TurnEventPayload.ToolFinished(
                        "c1",
                        "http_get",
                        com.agentos.core.turn.TurnEventPayload.ToolStatus.SUCCESS,
                        12,
                        false,
                        "ok",
                        true));
        send.accept(new com.agentos.core.turn.TurnEventPayload.StepStarted(2));
        send.accept(new com.agentos.core.turn.TurnEventPayload.MessageStarted("m2", 2, null));
        send.accept(new com.agentos.core.turn.TurnEventPayload.MessageDelta("m2", "answer"));
        send.accept(new com.agentos.core.turn.TurnEventPayload.MessageEnded("m2"));
        send.accept(
                new com.agentos.core.turn.TurnEventPayload.RunCompleted(
                        "answer", 2, PersistenceStatus.CONFIRMED));
        return success();
    }

    @Test
    void singleMessageKeepsStdoutAndWritesToolProgressToStderr() throws Exception {
        agent("main");
        when(service.process(any(), any())).thenAnswer(ChatCommandTest::deliverToolTurn);
        var out = new StringWriter();
        var err = new StringWriter();
        var line = AgentOSCli.commandLine(launcher(), workspace, key -> null);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));

        int exit = line.execute("chat", "--workspace", workspace.toString(), "--message", "hello");

        assertThat(exit).isZero();
        // 标准输出与 007 之前逐字节一致：只有最终回答一行。
        assertThat(out.toString()).isEqualTo("answer" + System.lineSeparator());
        assertThat(err.toString())
                .contains("⚙ http_get", "✓ http_get  成功", "── 完成 · 模型调用 2 次 · 工具 1 次")
                .doesNotContain("先查", "answer");
        var sink = ArgumentCaptor.forClass(com.agentos.core.turn.TurnSink.class);
        verify(service).process(any(), sink.capture());
        assertThat(sink.getValue().streaming()).isFalse();
    }

    @Test
    void interactiveUnconfirmedFailureDoesNotRepeatTheTerminalLineText() throws Exception {
        agent("main");
        UUID turn = UUID.randomUUID();
        when(service.process(any(), any(), any()))
                .thenAnswer(invocation -> {
                    com.agentos.core.turn.TurnSink sink = invocation.getArgument(1);
                    sink.onEvent(
                            new com.agentos.core.turn.TurnEvent(
                                    1,
                                    UUID.randomUUID(),
                                    turn,
                                    1,
                                    java.time.Instant.now(),
                                    new com.agentos.core.turn.TurnEventPayload.RunStarted(
                                            "main", "cli")));
                    sink.onEvent(
                            new com.agentos.core.turn.TurnEvent(
                                    1,
                                    UUID.randomUUID(),
                                    turn,
                                    2,
                                    java.time.Instant.now(),
                                    new com.agentos.core.turn.TurnEventPayload.RunFailed(
                                            com.agentos.core.turn.TurnFailureCode.AUDIT_UNCONFIRMED,
                                            TurnStatus.FAILED,
                                            "审计写入未确认",
                                            0,
                                            PersistenceStatus.UNCONFIRMED,
                                            false)));
                    return new TurnResult(
                            turn,
                            TurnStatus.FAILED,
                            "审计写入未确认",
                            0,
                            PersistenceStatus.UNCONFIRMED,
                            com.agentos.core.turn.TurnFailureCode.AUDIT_UNCONFIRMED);
                });
        var command =
                new ChatCommand(
                        launcher(),
                        workspace,
                        new java.io.BufferedReader(new java.io.StringReader("hi\n")));
        var line = new picocli.CommandLine(command);
        line.setOut(new PrintWriter(output));
        line.setErr(new PrintWriter(output));

        line.execute("--workspace", workspace.toString());

        String screen = output.toString();
        assertThat(screen).contains("── 失败：审计写入未确认", "持久化未确认，turnId=" + turn);
        assertThat(screen.split("审计写入未确认", -1)).as("失败原因只出现一次").hasSize(2);
    }

    private static TurnResult success() {
        return new TurnResult(
                UUID.randomUUID(), TurnStatus.SUCCEEDED, "answer", 1, PersistenceStatus.CONFIRMED);
    }
}
