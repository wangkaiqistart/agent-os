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
    void auditUncertaintyCannotBecomeConfirmedThroughSuccessfulFinish() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString()))
                .thenAnswer(call -> {
                    call.getArgument(3, TurnExecutionBudget.class).auditUnconfirmed();
                    return new ReActLoop.Result(TurnStatus.SUCCEEDED, "answer", 1);
                });
        var result = f.service.process(request());
        assertThat(result.persistenceStatus()).isEqualTo(PersistenceStatus.UNCONFIRMED);
        verify(f.loop, times(1)).run(any(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void recoveredTerminalStateWinsOverLateLocalSuccess() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString()))
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
        verify(f.loop, never()).run(any(), any(), anyString(), any(), any(), anyString());
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
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString()))
                .thenThrow(new TurnDeadlineExceededException());
        doReturn(new TurnStore.FinishResult("INTERRUPTED", true, "本轮已中断", "recovered"))
                .when(f.turns)
                .finishAndRelease(any(), anyString(), anyString(), nullable(String.class));
        assertThat(f.service.process(request()).status()).isEqualTo(TurnStatus.INTERRUPTED);
    }

    @Test
    void finishFailureIsUnconfirmedAndDoesNotRepeatLoop() throws Exception {
        var f = fixture();
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(new ReActLoop.Result(TurnStatus.SUCCEEDED, "answer", 1));
        doThrow(new IllegalStateException("synthetic database failure"))
                .when(f.turns)
                .finishAndRelease(
                        any(), anyString(), anyString(), nullable(String.class), anyInt());
        assertThat(f.service.process(request()).persistenceStatus())
                .isEqualTo(PersistenceStatus.UNCONFIRMED);
        verify(f.loop, times(1)).run(any(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void closingCancelsWaitAndReleasesOwnLeaseWithoutWaitingForModel() throws Exception {
        var f = fixture();
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(f.loop.run(any(), any(), anyString(), any(), any(), anyString()))
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
