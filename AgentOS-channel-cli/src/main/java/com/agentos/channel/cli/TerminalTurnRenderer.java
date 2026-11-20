package com.agentos.channel.cli;

import com.agentos.core.turn.TurnEvent;
import com.agentos.core.turn.TurnEventPayload;
import com.agentos.core.turn.TurnSink;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 把运行事件渲染到终端，格式见 specs/007-run-event-stream/contracts/cli-output.md。
 *
 * <p>交互模式：文字增量边到达边打印，工具开始 / 结束各一行，终态一行，全部写标准输出；确认与提问的提示由输入
 * broker 负责，这里不打印。非交互模式（{@code chat --message}）：不打印文字，工具行与成功终态行写标准错误，
 * 最终回答和失败说明仍由命令本身打印，保持原有输出契约。不输出任何 ANSI 控制序列。
 *
 * <p>事件由发布器串行投递，但终态可能来自另一个线程，所以状态读写用锁保护。不打日志。
 */
public final class TerminalTurnRenderer implements TurnSink {
    private static final int SUMMARY_CHARS = 80;
    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean interactive;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean lineOpen;
    private boolean textShown;
    private StringBuilder message = new StringBuilder();
    private String lastMessage = "";
    private int steps;
    private int tools;
    private long startedNanos = System.nanoTime();
    private UUID turnId;
    private boolean finalAnswerStreamed;
    private boolean terminalRendered;

    public TerminalTurnRenderer(PrintWriter out, PrintWriter err, boolean interactive) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.interactive = interactive;
    }

    @Override
    public boolean streaming() {
        return interactive;
    }

    @Override
    public void onEvent(TurnEvent event) {
        lock.lock();
        try {
            render(event);
        } finally {
            lock.unlock();
        }
    }

    /** 当前正在执行的轮次标识；收到轮次开始后才有，终态后清空。 */
    public Optional<UUID> currentTurnId() {
        lock.lock();
        try {
            return Optional.ofNullable(turnId);
        } finally {
            lock.unlock();
        }
    }

    /** 最终回答是否已经边生成边显示过；为 false 时命令需要自己打印回答（例如工具直接返回结果）。 */
    public boolean finalAnswerStreamed() {
        lock.lock();
        try {
            return finalAnswerStreamed;
        } finally {
            lock.unlock();
        }
    }

    /** 交互模式下是否已经打印了失败或取消的终态行；为 false 时命令自己打印失败说明。 */
    public boolean terminalRendered() {
        lock.lock();
        try {
            return terminalRendered;
        } finally {
            lock.unlock();
        }
    }

    private void render(TurnEvent event) {
        switch (event.payload()) {
            case TurnEventPayload.RunStarted started -> {
                turnId = event.turnId();
                startedNanos = System.nanoTime();
            }
            case TurnEventPayload.StepStarted step -> steps++;
            case TurnEventPayload.MessageStarted started -> message = new StringBuilder();
            case TurnEventPayload.MessageDelta delta -> {
                message.append(delta.text());
                if (interactive) {
                    // 模型文字可能带控制字符：去掉换行和制表符以外的控制字符，避免改写终端状态。
                    out.print(delta.text().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""));
                    out.flush();
                    lineOpen = true;
                    textShown = true;
                }
            }
            case TurnEventPayload.MessageEnded ended -> lastMessage = message.toString();
            case TurnEventPayload.ToolStarted started -> {
                tools++;
                line("  ⚙ " + started.toolName() + "  " + clip(started.argumentsSummary()));
            }
            case TurnEventPayload.ToolFinished finished -> line(toolFinished(finished));
            case TurnEventPayload.RunCompleted completed -> {
                finalAnswerStreamed =
                        interactive && textShown && lastMessage.equals(completed.text());
                closeLine();
                if (tools > 0) {
                    line("  ── 完成 · " + counts());
                }
                turnId = null;
            }
            case TurnEventPayload.RunFailed failed -> {
                if (interactive) {
                    closeLine();
                    if (textShown) {
                        out.println("  （回答未完成）");
                    }
                    out.println("  ── 失败：" + failed.message() + " · " + counts());
                    out.flush();
                    terminalRendered = true;
                }
                turnId = null;
            }
            case TurnEventPayload.RunCancelled cancelled -> {
                if (interactive) {
                    closeLine();
                    out.println(
                            "  ── 已取消"
                                    + (cancelled.possiblyExecuted() ? "；该操作可能已生效" : "")
                                    + " · "
                                    + counts());
                    out.flush();
                    terminalRendered = true;
                }
                turnId = null;
            }
            default -> {
                // 确认与提问的提示由输入 broker 打印；步骤结束等其他事件不需要显示。
            }
        }
    }

    private String toolFinished(TurnEventPayload.ToolFinished finished) {
        boolean ok = finished.status() == TurnEventPayload.ToolStatus.SUCCESS;
        StringBuilder text = new StringBuilder("  ");
        text.append(ok ? "✓ " : "✗ ").append(finished.toolName()).append("  ");
        text.append(statusText(finished.status()));
        if (!ok && finished.possiblyExecuted()) {
            text.append("（可能已执行）");
        }
        text.append(" · ").append(duration(finished.durationMs()));
        String summary = clip(finished.resultSummary());
        if (!summary.isEmpty()) {
            text.append(" · ").append(summary);
        }
        return text.toString();
    }

    private String counts() {
        return "模型调用 "
                + steps
                + " 次 · 工具 "
                + tools
                + " 次 · "
                + duration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    /** 过程行：交互模式写标准输出，非交互模式写标准错误；先结束未换行的文字。 */
    private void line(String text) {
        PrintWriter target = interactive ? out : err;
        closeLine();
        target.println(text);
        target.flush();
    }

    private void closeLine() {
        if (lineOpen) {
            out.println();
            out.flush();
            lineOpen = false;
        }
    }

    private static String statusText(TurnEventPayload.ToolStatus status) {
        return switch (status) {
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            case TIMEOUT -> "超时";
            case DENIED -> "已拒绝";
            case SKIPPED -> "已跳过";
            case INTERRUPTED -> "已中断";
            case UNCONFIRMED -> "结果未确认";
        };
    }

    static String duration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    /** 去掉换行与控制字符、压成一行并限长；完整内容在审计表里。 */
    static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("[\\p{Cntrl}&&[^\\t]]+", " ").replace('\t', ' ').strip();
        if (flat.codePointCount(0, flat.length()) <= SUMMARY_CHARS) {
            return flat;
        }
        int end = flat.offsetByCodePoints(0, SUMMARY_CHARS - 1);
        return flat.substring(0, end) + "…";
    }
}
