package com.agentos.channel.cli;

import com.agentos.core.interaction.ApprovalDecision;
import com.agentos.core.interaction.ApprovalRequest;
import com.agentos.core.interaction.InteractionPort;
import com.agentos.core.interaction.InteractionRequest;
import com.agentos.core.interaction.InteractionResult;
import com.agentos.core.tool.ToolExecutionScope;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 单一固定reader；归属在开始读取前确定，取消或超长后仍丢弃旧行至结束。 */
public class TerminalInputBroker implements InteractionPort, AutoCloseable {
    private static final int MAX_CHARS = 4000;
    private final Object lock = new Object();
    private final Reader input;
    private final PrintWriter output;
    private final Thread reader;
    private Pending owner;
    private boolean closed;
    private boolean eof;
    private long generation;
    private volatile int retained;

    private static final class Pending {
        final long generation;
        final int maxChars;
        final CompletableFuture<InteractionResult> result = new CompletableFuture<>();

        Pending(long generation, int maxChars) {
            this.generation = generation;
            this.maxChars = maxChars;
        }
    }

    public TerminalInputBroker(Reader input, PrintWriter output) {
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        reader = Thread.ofVirtual().name("agentos-cli-input").start(this::read);
    }

    private Pending acquire(int maxChars) {
        synchronized (lock) {
            if (closed || eof) {
                return null;
            }
            if (owner != null) {
                return ownerBusy();
            }
            owner = new Pending(++generation, maxChars);
            retained = 0;
            lock.notifyAll();
            return owner;
        }
    }

    /**
     * 提问超时或取消后，旧请求仍持有 reader 直到这一行结束（迟到输入按规格丢弃）。聊天读取等它释放再接管，
     * 不把这种状态当作 BUSY 结束对话；仍有未结束的提问时才返回 BUSY。
     */
    private Pending acquireChat() throws InterruptedException {
        synchronized (lock) {
            while (!closed && !eof && owner != null && owner.result.isDone()) {
                lock.wait();
            }
            return acquire(MAX_CHARS);
        }
    }

    private Pending ownerBusy() {
        var busy = new Pending(-1, MAX_CHARS);
        busy.result.complete(InteractionResult.of(InteractionResult.Status.BUSY));
        return busy;
    }

    @Override
    public InteractionResult ask(InteractionRequest request, ToolExecutionScope scope) {
        scope.checkpoint();
        Pending pending = acquire(request.maxAnswerChars());
        if (pending == null) {
            return InteractionResult.of(InteractionResult.Status.EOF);
        }
        if (pending.generation == -1) {
            return pending.result.join();
        }
        output.println(request.question());
        output.flush();
        try {
            while (true) {
                scope.checkpoint();
                if (System.nanoTime() >= request.deadlineNanos()) {
                    return InteractionResult.of(InteractionResult.Status.TIMED_OUT);
                }
                try {
                    return pending.result.get(
                            Math.max(
                                    1,
                                    Math.min(
                                            scope.remainingNanos(),
                                            TimeUnit.MILLISECONDS.toNanos(10))),
                            TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException waiting) {
                    // 等待期间持续复核原租约和预算，不创建额外读取线程。
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    scope.checkpoint();
                    return InteractionResult.of(InteractionResult.Status.CANCELLED);
                } catch (java.util.concurrent.ExecutionException failed) {
                    return InteractionResult.of(InteractionResult.Status.EOF);
                }
            }
        } finally {
            pending.result.complete(InteractionResult.of(InteractionResult.Status.CANCELLED));
        }
    }

    /**
     * 以 Claude Code 的方式请求授权：展示动作摘要和编号选项，读取一行回答。
     *
     * <p>与提问共用同一个 reader 和 owner；超时或取消后迟到的那一行照常丢弃。
     */
    @Override
    public ApprovalDecision approve(ApprovalRequest request, ToolExecutionScope scope) {
        scope.checkpoint();
        Pending pending = acquire(ApprovalDecision.MAX_FEEDBACK_CHARS);
        if (pending == null) {
            return ApprovalDecision.of(ApprovalDecision.Outcome.EOF);
        }
        if (pending.generation == -1) {
            return ApprovalDecision.of(ApprovalDecision.Outcome.CANCELLED);
        }
        var subject = request.subject();
        var options = new java.util.ArrayList<ApprovalDecision.Outcome>();
        output.println();
        output.println("╭─ 需要授权：" + subject.title());
        subject.lines().forEach(line -> output.println("│ " + line));
        output.println("╰─");
        options.add(ApprovalDecision.Outcome.ALLOW_ONCE);
        output.println("  " + options.size() + ". 允许本次");
        if (subject.sessionGrantAllowed()) {
            options.add(ApprovalDecision.Outcome.ALLOW_SESSION);
            output.println("  " + options.size() + ". 允许，本会话不再询问" + subject.grantLabel());
        }
        options.add(ApprovalDecision.Outcome.DENY);
        output.println("  " + options.size() + ". 拒绝（也可以直接输入原因，告诉 Agent 该怎么做）");
        output.print("请选择 [1-" + options.size() + "]：");
        output.flush();
        try {
            while (true) {
                scope.checkpoint();
                long remaining = request.deadlineNanos() - System.nanoTime();
                if (remaining <= 0) {
                    output.println();
                    output.println("（等待授权超时，已拒绝）");
                    output.flush();
                    return ApprovalDecision.of(ApprovalDecision.Outcome.TIMED_OUT);
                }
                try {
                    var answer =
                            pending.result.get(
                                    Math.max(
                                            1,
                                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10))),
                                    TimeUnit.NANOSECONDS);
                    return decide(answer, options);
                } catch (java.util.concurrent.TimeoutException waiting) {
                    // 等待期间持续复核租约和取消；整轮计时由调用方暂停。
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    scope.checkpoint();
                    return ApprovalDecision.of(ApprovalDecision.Outcome.CANCELLED);
                } catch (java.util.concurrent.ExecutionException failed) {
                    return ApprovalDecision.of(ApprovalDecision.Outcome.EOF);
                }
            }
        } finally {
            pending.result.complete(InteractionResult.of(InteractionResult.Status.CANCELLED));
        }
    }

    /** 数字按展示的选项解析；y/n 是快捷键；空行拒绝；其他文字视为拒绝原因。 */
    static ApprovalDecision decide(
            InteractionResult answer, java.util.List<ApprovalDecision.Outcome> options) {
        switch (answer.status()) {
            case ANSWERED -> {
                String text = answer.answer().strip();
                if (text.matches("[0-9]+")) {
                    int index = Integer.parseInt(text) - 1;
                    if (index >= 0 && index < options.size()) {
                        return ApprovalDecision.of(options.get(index));
                    }
                    return ApprovalDecision.of(ApprovalDecision.Outcome.DENY);
                }
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("y") || lower.equals("yes")) {
                    return ApprovalDecision.of(ApprovalDecision.Outcome.ALLOW_ONCE);
                }
                if (text.isEmpty() || lower.equals("n") || lower.equals("no")) {
                    return ApprovalDecision.of(ApprovalDecision.Outcome.DENY);
                }
                return ApprovalDecision.deny(text);
            }
            case EOF -> {
                return ApprovalDecision.of(ApprovalDecision.Outcome.EOF);
            }
            case TOO_LONG -> {
                return ApprovalDecision.of(ApprovalDecision.Outcome.DENY);
            }
            default -> {
                return ApprovalDecision.of(ApprovalDecision.Outcome.CANCELLED);
            }
        }
    }

    public String readChatLine() throws IOException {
        Pending pending;
        try {
            pending = acquireChat();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("CANCELLED");
        }
        if (pending == null) {
            return null;
        }
        try {
            InteractionResult result = pending.result.get();
            if (result.status() == InteractionResult.Status.EOF) {
                return null;
            }
            if (result.status() != InteractionResult.Status.ANSWERED) {
                throw new IOException(result.status().name());
            }
            return result.answer();
        } catch (InterruptedException interrupted) {
            pending.result.complete(InteractionResult.of(InteractionResult.Status.CANCELLED));
            Thread.currentThread().interrupt();
            throw new IOException("CANCELLED");
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IOException("EOF");
        }
    }

    private void read() {
        try {
            while (true) {
                Pending pending;
                synchronized (lock) {
                    while (owner == null && !closed) {
                        lock.wait();
                    }
                    if (closed) {
                        return;
                    }
                    pending = owner;
                }
                var text = new StringBuilder();
                boolean ended = false;
                while (true) {
                    int value = input.read();
                    if (value == -1 || value == '\n') {
                        ended = value == -1;
                        break;
                    }
                    if (!pending.result.isDone()) {
                        text.append((char) value);
                        retained = text.length();
                        if (text.length() > pending.maxChars) {
                            pending.result.complete(
                                    InteractionResult.of(InteractionResult.Status.TOO_LONG));
                            text.setLength(0);
                        }
                    }
                    // 已结束请求只丢弃单个字符；不保留无换行余行。
                }
                synchronized (lock) {
                    eof = ended;
                    if (owner == pending) {
                        owner = null;
                    }
                    lock.notifyAll();
                }
                var finished = pending.result.getNow(null);
                if (finished != null && finished.status() == InteractionResult.Status.CANCELLED) {
                    // 这一行属于已超时或取消的提问，不会交给任何人；提示用户重新输入。
                    output.println("（上一个提问已结束，这行输入已丢弃，请重新输入）");
                    output.flush();
                }
                if (!pending.result.isDone()) {
                    String line = text.toString();
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    pending.result.complete(
                            ended && line.isEmpty()
                                    ? InteractionResult.of(InteractionResult.Status.EOF)
                                    : new InteractionResult(
                                            InteractionResult.Status.ANSWERED, line));
                }
                if (ended) {
                    return;
                }
            }
        } catch (IOException | InterruptedException stopped) {
            synchronized (lock) {
                eof = true;
                if (owner != null) {
                    owner.result.complete(InteractionResult.of(InteractionResult.Status.EOF));
                }
                owner = null;
                lock.notifyAll();
            }
        }
    }

    public int retainedCharacters() {
        return retained;
    }

    public int activeReaders() {
        synchronized (lock) {
            return owner == null ? 0 : 1;
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            if (owner != null) {
                owner.result.complete(InteractionResult.of(InteractionResult.Status.CANCELLED));
            }
            lock.notifyAll();
        }
        reader.interrupt();
    }
}
