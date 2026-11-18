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
        when(service.process(any())).thenReturn(success());
        assertThat(chat(launcher(), "--profile", "custom")).isZero();
        assertThat(closed[0]).isTrue();
        var request = ArgumentCaptor.forClass(ProcessRequest.class);
        verify(service).process(request.capture());
        assertThat(request.getValue().agentName()).isEqualTo("custom");
        agent("main");
        assertThat(chat(launcher())).isZero();
        verify(service, org.mockito.Mockito.times(2)).process(request.capture());
        assertThat(request.getValue().agentName()).isEqualTo("main");
    }

    @Test
    void oldSingleAgentStillWorksAndMultipleAgentsListCandidates() throws Exception {
        agent("legacy");
        when(service.process(any())).thenReturn(success());
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
        when(service.process(any())).thenThrow(new IllegalStateException("synthetic-secret"));
        assertThat(chat(launcher())).isEqualTo(3);
        assertThat(closed[0]).isTrue();
        assertThat(output.toString()).doesNotContain("synthetic-secret", "Exception");
    }

    @Test
    void unknownToolShowsConfigurationLocationWithoutExposingCause() throws Exception {
        agent("main");
        var error = new UnknownAgentToolException("main", "does_not_exist");
        error.initCause(new IllegalStateException("synthetic-cause-secret"));
        when(service.process(any())).thenThrow(error);

        assertThat(chat(launcher())).isEqualTo(2);
        assertThat(output.toString())
                .contains("agents/main/AGENT.md 的 tools 包含未注册工具：does_not_exist")
                .doesNotContain("synthetic-cause-secret", "Exception");
        assertThat(closed[0]).isTrue();
    }

    @Test
    void unrelatedArgumentErrorsKeepSafeOutputAndDoNotExposeCauses() throws Exception {
        agent("main");
        when(service.process(any()))
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
        when(service.process(any()))
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
        when(service.process(any())).thenReturn(success());
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
        verify(service).process(any());
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
        when(service.process(any())).thenReturn(success());
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
        verify(service).process(any());
    }

    @Test
    void normalizedWorkspaceReachesLauncherAndInteractiveInputClosesOnExitOrEof() throws Exception {
        agent("main");
        when(service.process(any())).thenReturn(success());
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
        verify(service, org.mockito.Mockito.times(2)).process(any());
        assertThat(output.toString()).contains("消息不能为空");
    }

    @Test
    void busySingleMessageExitsThreeAndInteractiveBusyCanContinue() throws Exception {
        agent("main");
        when(service.process(any()))
                .thenThrow(new AgentOSException(ErrorCode.SESSION_BUSY, "synthetic-secret"));
        assertThat(chat(launcher())).isEqualTo(3);
        assertThat(output.toString()).contains("上一条消息还在处理").doesNotContain("synthetic-secret");
        org.mockito.Mockito.doThrow(new AgentOSException(ErrorCode.SESSION_BUSY, "busy"))
                .doReturn(success())
                .when(service)
                .process(any());
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
        when(service.process(any())).thenReturn(success());
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
        verify(service).process(request.capture());
        assertThat(request.getValue().userId()).isEqualTo("synthetic-os-user");
    }

    private static TurnResult success() {
        return new TurnResult(
                UUID.randomUUID(), TurnStatus.SUCCEEDED, "answer", 1, PersistenceStatus.CONFIRMED);
    }
}
