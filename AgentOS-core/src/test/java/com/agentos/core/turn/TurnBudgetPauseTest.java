package com.agentos.core.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.core.tool.ToolExecutionScope;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 等待人工授权时暂停整轮计时：暂停期间余量冻结，恢复后截止时间后移，取消仍立即生效。 */
class TurnBudgetPauseTest {
    @Test
    void pauseFreezesRemainingAndShiftsDeadlineAfterResume() throws Exception {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(300));
        var deadlineBefore = budget.deadline();
        long before = budget.remainingNanos();
        try (var paused = budget.pause()) {
            Thread.sleep(400);
            assertThat(budget.expired()).isFalse();
            assertThat(budget.hardStopped()).isFalse();
            // 暂停期间余量保持在暂停开始时的值。
            assertThat(budget.remainingNanos())
                    .isGreaterThan(before - TimeUnit.MILLISECONDS.toNanos(50));
        }
        assertThat(budget.pausedNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(400));
        assertThat(budget.deadline()).isAfter(deadlineBefore.plusMillis(350));
        assertThat(budget.expired()).isFalse();
        Thread.sleep(350);
        assertThat(budget.expired()).isTrue();
    }

    @Test
    void nestedPausesCountOnceAndCancelIsNotDelayed() throws Exception {
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(5));
        try (var outer = budget.pause()) {
            try (var inner = budget.pause()) {
                Thread.sleep(100);
            }
            Thread.sleep(100);
            budget.cancel();
            assertThat(budget.cancelled()).isTrue();
            // 取消后的一秒清理窗口不随暂停后移。
            assertThat(budget.remainingHardStopNanos())
                    .isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        }
        long paused = budget.pausedNanos();
        assertThat(paused)
                .isBetween(TimeUnit.MILLISECONDS.toNanos(200), TimeUnit.SECONDS.toNanos(2));
        // 重复关闭不再累计。
        var handle = budget.pause();
        handle.close();
        handle.close();
        assertThat(budget.pausedNanos()).isLessThan(paused + TimeUnit.MILLISECONDS.toNanos(50));
    }

    @Test
    void turnRunnerKeepsWaitingWhileTheTurnIsPaused() {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(200), Duration.ofMillis(100));
        String result =
                new TurnRunner()
                        .run(
                                () -> {
                                    try (var paused = budget.pause()) {
                                        Thread.sleep(600);
                                    }
                                    return "ok";
                                },
                                budget);
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void turnRunnerStillTimesOutWithoutPause() {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(100), Duration.ofMillis(100));
        assertThatThrownBy(() ->
                        new TurnRunner()
                                .run(
                                        () -> {
                                            Thread.sleep(2000);
                                            return "late";
                                        },
                                        budget))
                .isInstanceOf(TurnDeadlineExceededException.class);
    }

    @Test
    void scopePauseStopsBothToolAndTurnClocks() throws Exception {
        var budget = TurnExecutionBudget.start(Duration.ofMillis(300));
        try (var scope =
                new ToolExecutionScope(null, "pause", budget, Duration.ofMillis(200), () -> {})) {
            try (var paused = scope.pauseClock()) {
                Thread.sleep(400);
                scope.checkpoint();
            }
            assertThat(scope.remainingNanos()).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(100));
            scope.checkpoint();
            Thread.sleep(250);
            assertThatThrownBy(scope::checkpoint)
                    .isInstanceOf(ToolExecutionScope.ToolTimeoutException.class);
        }
    }
}
