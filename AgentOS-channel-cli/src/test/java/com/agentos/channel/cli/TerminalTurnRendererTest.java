package com.agentos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentos.core.turn.PersistenceStatus;
import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnFailureCode;
import com.agentos.core.turn.TurnStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 终端渲染按 contracts/cli-output.md：文字原样、工具一行、终态一行、不输出控制序列。 */
class TerminalTurnRendererTest {
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final UUID turn = UUID.randomUUID();
    private long seq;

    private TerminalTurnRenderer renderer(boolean interactive) {
        return new TerminalTurnRenderer(new PrintWriter(out), new PrintWriter(err), interactive);
    }

    private void send(TerminalTurnRenderer renderer, TurnEventPayload payload) {
        renderer.onEvent(new TurnEvent(1, UUID.randomUUID(), turn, ++seq, Instant.now(), payload));
    }

    private void textStep(TerminalTurnRenderer r, int iteration, String id, String... chunks) {
        send(r, new TurnEventPayload.StepStarted(iteration));
        send(r, new TurnEventPayload.MessageStarted(id, iteration, null));
        for (String chunk : chunks) {
            send(r, new TurnEventPayload.MessageDelta(id, chunk));
        }
        send(r, new TurnEventPayload.MessageEnded(id));
    }

    private static TurnEventPayload.ToolFinished finished(
            TurnEventPayload.ToolStatus status, String summary, boolean possiblyExecuted) {
        return new TurnEventPayload.ToolFinished(
                "c1", "write_file", status, 1500, false, summary, possiblyExecuted);
    }

    @Test
    void interactiveStreamsTextToolsAndTerminalWithoutRepeatingAnswer() {
        var r = renderer(true);
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        assertThat(r.currentTurnId()).contains(turn);
        textStep(r, 1, "m1", "我先", "写文件。");
        send(
                r,
                new TurnEventPayload.ToolStarted(
                        "c1",
                        "write_file",
                        "builtin",
                        1,
                        "{\"path\":\"a.md\",\n\"content\":\"x\"}"));
        send(
                r,
                new TurnEventPayload.ApprovalRequested(
                        UUID.randomUUID(),
                        "c1",
                        "FILE_WRITE",
                        "写入文件",
                        java.util.List.of("文件 a.md"),
                        true));
        send(r, finished(TurnEventPayload.ToolStatus.SUCCESS, "已写入 a.md", true));
        textStep(r, 2, "m2", "已完成");
        send(r, new TurnEventPayload.RunCompleted("已完成", 2, PersistenceStatus.CONFIRMED));

        String screen = out.toString();
        String nl = System.lineSeparator();
        assertThat(screen)
                .startsWith(
                        "我先写文件。"
                                + nl
                                + "  ⚙ write_file  {\"path\":\"a.md\", \"content\":\"x\"}"
                                + nl)
                .contains("  ✓ write_file  成功 · 1.5s · 已写入 a.md" + nl + "已完成" + nl)
                .contains("  ── 完成 · 模型调用 2 次 · 工具 1 次 · ")
                .doesNotContain("写入文件")
                .doesNotContain("\u001b");
        assertThat(screen.split("已完成", -1)).hasSize(2);
        assertThat(r.finalAnswerStreamed()).isTrue();
        assertThat(r.currentTurnId()).isEmpty();
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void successWithoutToolsPrintsNoTerminalLine() {
        var r = renderer(true);
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        textStep(r, 1, "m1", "你好");
        send(r, new TurnEventPayload.RunCompleted("你好", 1, PersistenceStatus.CONFIRMED));
        assertThat(out.toString()).isEqualTo("你好" + System.lineSeparator());
    }

    @Test
    void returnDirectAnswerIsNotMarkedAsStreamed() {
        var r = renderer(true);
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        textStep(r, 1, "m1", "查一下");
        send(r, new TurnEventPayload.RunCompleted("sunny", 1, PersistenceStatus.CONFIRMED));
        assertThat(r.finalAnswerStreamed()).isFalse();
    }

    @Test
    void failureAfterPartialTextMarksIncompleteAnswer() {
        var r = renderer(true);
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        textStep(r, 1, "m1", "半截");
        send(
                r,
                new TurnEventPayload.RunFailed(
                        TurnFailureCode.OUTPUT_TRUNCATED,
                        TurnStatus.FAILED,
                        "模型输出达到上限被截断",
                        1,
                        PersistenceStatus.CONFIRMED,
                        false));
        String nl = System.lineSeparator();
        assertThat(out.toString())
                .startsWith("半截" + nl + "  （回答未完成）" + nl + "  ── 失败：模型输出达到上限被截断 · ");
        assertThat(r.terminalRendered()).isTrue();
    }

    @Test
    void cancelledWithPossibleSideEffectSaysSo() {
        var r = renderer(true);
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        send(r, new TurnEventPayload.ToolStarted("c1", "write_file", "builtin", 1, "{}"));
        send(r, finished(TurnEventPayload.ToolStatus.INTERRUPTED, null, true));
        send(r, new TurnEventPayload.RunCancelled("本轮已取消", 1, PersistenceStatus.CONFIRMED, true));
        assertThat(out.toString())
                .contains("  ✗ write_file  已中断（可能已执行） · 1.5s" + System.lineSeparator())
                .contains("  ── 已取消；该操作可能已生效 · ");
    }

    @Test
    void summariesAreFlattenedAndClippedToEightyCharacters() {
        String clipped = TerminalTurnRenderer.clip("第一行\n第二行\u001b[31m" + "长".repeat(200));
        assertThat(clipped).doesNotContain("\n").doesNotContain("\u001b").endsWith("…");
        assertThat(clipped.codePointCount(0, clipped.length())).isEqualTo(80);
        assertThat(TerminalTurnRenderer.clip(null)).isEmpty();
    }

    @Test
    void modelControlCharactersAreNotWrittenToTerminal() {
        var r = renderer(true);
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        textStep(r, 1, "m1", "红\u001b[31m字\n下一行");
        assertThat(out.toString()).isEqualTo("红[31m字\n下一行");
    }

    @Test
    void progressOnlyModeWritesToolAndTerminalLinesToStderr() {
        var r = renderer(false);
        assertThat(r.streaming()).isFalse();
        send(r, new TurnEventPayload.RunStarted("main", "cli"));
        textStep(r, 1, "m1", "先查");
        send(r, new TurnEventPayload.ToolStarted("c1", "write_file", "builtin", 1, "{}"));
        send(r, finished(TurnEventPayload.ToolStatus.DENIED, "DENIED：用户拒绝了该操作", false));
        textStep(r, 2, "m2", "答");
        send(r, new TurnEventPayload.RunCompleted("答", 2, PersistenceStatus.CONFIRMED));
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString())
                .contains("  ⚙ write_file  {}", "  ✗ write_file  已拒绝 · 1.5s", "── 完成")
                .doesNotContain("先查");
        assertThat(r.finalAnswerStreamed()).isFalse();
    }
}
