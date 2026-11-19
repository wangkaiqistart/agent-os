package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentos.core.agent.AgentLoader;
import com.agentos.core.agent.ContextLoader;
import com.agentos.core.provider.ProviderCatalog;
import com.agentos.core.session.TurnStore;
import com.agentos.core.support.AgentFixtures;
import com.agentos.core.support.InMemorySessionManager;
import com.agentos.core.support.InMemoryTurnStore;
import com.agentos.core.tool.ToolCleanupUnconfirmedException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 故障时不重发外呼，终态、审计确认和生命周期分别验证。 */
class AgentServiceFailureTest {
    private record Fixture(DefaultAgentService service, ReActLoop loop, TurnStore turns) {}

    private Fixture fixture() throws Exception {
        var root = Files.createTempDirectory("agent-failure");
        AgentFixtures.agent(root, "assistant");
        var sessions = new InMemorySessionManager();
        var turns = spy(new InMemoryTurnStore(sessions));
        var loop = mock(ReActLoop.class);
        return new Fixture(
                new DefaultAgentService(
                        new AgentLoader(root.resolve("agents")),
                        new ContextLoader(root),
                        mock(ProviderCatalog.class),
                        sessions,
                        turns,
                        loop,
                        new TurnBudgetRegistry()),
                loop,
                turns);
    }

    private ProcessRequest request() {
        return new ProcessRequest("test", "u", "assistant", "hello", "test");
    }

    @Test
    void cleanupUncertaintyTerminatesWithoutReplayAndSeparatesPersistenceConfirmation()
            throws Exception {
        for (boolean finishFailure : java.util.List.of(false, true)) {
            var f = fixture();
            when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                    .thenThrow(new ToolCleanupUnconfirmedException());
            if (finishFailure) {
                doThrow(new IllegalStateException("测试收尾未确认"))
                        .when(f.turns)
                        .finishAndRelease(any(), anyString(), anyString(), nullable(String.class));
            }
            var result = f.service.process(request());
            assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
            assertThat(result.text()).contains("清理未确认", "本轮已停止", "禁止重放");
            assertThat(result.persistenceStatus())
                    .isEqualTo(
                            finishFailure
                                    ? PersistenceStatus.UNCONFIRMED
                                    : PersistenceStatus.CONFIRMED);
            verify(f.loop).run(any(), any(), anyString(), any(), any(), anyString(), any(), any());
            verify(f.turns)
                    .finishAndRelease(any(), eq("FAILED"), eq(result.text()), eq(result.text()));
        }
    }

    @Test
    void pendingSubmissionSurvivesFatalSignalsAndIndependentPersistenceFailure() throws Exception {
        for (String signal : java.util.List.of("deadline", "cancel", "lease", "audit", "cleanup")) {
            for (boolean finishFailure : java.util.List.of(false, true)) {
                var f = fixture();
                when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                        .thenAnswer(call -> {
                            var budget = call.getArgument(3, TurnExecutionBudget.class);
                            budget.beginSubmission();
                            switch (signal) {
                                case "cancel" -> {
                                    budget.cancel();
                                    throw new TurnDeadlineExceededException();
                                }
                                case "deadline" -> throw new TurnDeadlineExceededException();
                                case "lease" -> throw new LeaseLostException();
                                case "audit" -> {
                                    budget.auditUnconfirmed();
                                    throw new com.agentos.core.audit.AuditWriteException(
                                            "测试审计未确认", true);
                                }
                                default -> throw new ToolCleanupUnconfirmedException();
                            }
                        });
                if (finishFailure) {
                    doThrow(new IllegalStateException("测试收尾失败"))
                            .when(f.turns)
                            .finishAndRelease(
                                    any(), anyString(), anyString(), nullable(String.class));
                }
                var error = new java.util.concurrent.atomic.AtomicReference<TurnResult>();
                var result =
                        f.service.process(
                                request(),
                                new TurnSink() {
                                    public void onError(TurnResult value) {
                                        error.set(value);
                                    }
                                });
                assertThat(result.text()).as(signal).contains("提交可能已生效");
                assertThat(result.status())
                        .isEqualTo(
                                switch (signal) {
                                    case "deadline" -> TurnStatus.TIMED_OUT;
                                    case "cancel", "lease" -> TurnStatus.INTERRUPTED;
                                    default -> TurnStatus.FAILED;
                                });
                assertThat(error.get()).isEqualTo(result);
                assertThat(result.persistenceStatus())
                        .isEqualTo(
                                finishFailure || signal.equals("audit")
                                        ? PersistenceStatus.UNCONFIRMED
                                        : PersistenceStatus.CONFIRMED);
                verify(f.loop)
                        .run(any(), any(), anyString(), any(), any(), anyString(), any(), any());
                verify(f.turns)
                        .finishAndRelease(
                                any(), anyString(), eq(result.text()), nullable(String.class));
            }
        }
    }

    @Test
    void laterConfirmedSubmissionCannotEraseAnEarlierPendingSubmission() throws Exception {
        for (boolean confirmFirst : java.util.List.of(false, true)) {
            var f = fixture();
            when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                    .thenAnswer(call -> {
                        var budget = call.getArgument(3, TurnExecutionBudget.class);
                        var earlier = budget.beginSubmission();
                        var later = budget.beginSubmission();
                        later.confirm();
                        later.confirm();
                        if (confirmFirst) {
                            earlier.confirm();
                        }
                        return new ReActLoop.Result(TurnStatus.SUCCEEDED, "完成", 2);
                    });
            var result = f.service.process(request());
            if (confirmFirst) {
                assertThat(result.text()).isEqualTo("完成");
            } else {
                assertThat(result.text()).contains("提交可能已生效");
            }
            assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.CONFIRMED);
        }
    }

    @Test
    void auditUncertaintyCannotBecomeConfirmedThroughSuccessfulFinish() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenAnswer(call -> {
                    call.getArgument(3, TurnExecutionBudget.class).auditUnconfirmed();
                    return new ReActLoop.Result(TurnStatus.SUCCEEDED, "answer", 1);
                });
        var result = f.service.process(request());
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
        verify(f.loop, times(1))
                .run(any(), any(), anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    void recoveredTerminalStateWinsOverLateLocalSuccess() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ReActLoop.Result(TurnStatus.SUCCEEDED, "late", 1));
        doReturn(new TurnStore.FinishResult("INTERRUPTED", true, "本轮已中断", "owner lost"))
                .when(f.turns)
                .finishAndRelease(
                        any(), anyString(), anyString(), nullable(String.class), anyInt());
        assertThat(f.service.process(request()).status()).isEqualTo(TurnStatus.INTERRUPTED);
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(
            org.springframework.boot.test.system.OutputCaptureExtension.class)
    void unknownStartNeverReplaysAndNeverCallsModelWhenUnconfirmed(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var f = fixture();
        doThrow(new IllegalStateException("synthetic commit unknown"))
                .when(f.turns)
                .startTurn(any(), any(), anyString(), anyString(), any());
        var result = f.service.process(request());
        assertThat(result.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
        verify(f.turns, times(1)).startTurn(any(), any(), anyString(), anyString(), any());
        verify(f.loop, never())
                .run(any(), any(), anyString(), any(), any(), anyString(), any(), any());
        assertThat(output.getAll())
                .contains(
                        result.turnId().toString(),
                        "stage=start",
                        "stage=confirm",
                        "IllegalStateException",
                        "UNCONFIRMED")
                .doesNotContain("synthetic commit unknown");
    }

    @Test
    void lateTimeoutUsesAlreadyRecoveredDatabaseState() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenAnswer(call -> {
                    call.getArgument(3, TurnExecutionBudget.class).beginSubmission();
                    throw new TurnDeadlineExceededException();
                });
        doReturn(new TurnStore.FinishResult("INTERRUPTED", true, "本轮已中断", "recovered"))
                .when(f.turns)
                .finishAndRelease(any(), anyString(), anyString(), nullable(String.class));
        var result = f.service.process(request());
        assertThat(result.status()).isEqualTo(TurnStatus.INTERRUPTED);
        assertThat(result.text()).isEqualTo("本轮已中断");
    }

    @Test
    void finishFailureIsUnconfirmedAndDoesNotRepeatLoop() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(new ReActLoop.Result(TurnStatus.SUCCEEDED, "answer", 1));
        doThrow(new IllegalStateException("synthetic database failure"))
                .when(f.turns)
                .finishAndRelease(
                        any(), anyString(), anyString(), nullable(String.class), anyInt());
        assertThat(f.service.process(request()).persistenceStatus())
                .isEqualTo(PersistenceStatus.UNCONFIRMED);
        verify(f.loop, times(1))
                .run(any(), any(), anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    void closingCancelsWaitAndReleasesOwnLeaseWithoutWaitingForModel() throws Exception {
        var f = fixture();
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString(), any(), any()))
                .thenAnswer(call -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        stopped.countDown();
                    }
                    throw new LeaseLostException();
                });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> f.service.process(request()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            if (!(AutoCloseable.class.isInstance(f.service))) {
                result.cancel(true);
                assertThat(f.service).isInstanceOf(AutoCloseable.class);
            }
            ((AutoCloseable) (Object) f.service).close();
            assertThat(result.get(2, TimeUnit.SECONDS).status()).isEqualTo(TurnStatus.INTERRUPTED);
            assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
            verify(f.turns).finishAndRelease(any(), eq("INTERRUPTED"), anyString(), anyString());
        }
    }
}
