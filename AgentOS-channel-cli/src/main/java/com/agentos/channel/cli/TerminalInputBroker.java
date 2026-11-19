package com.agentos.channel.cli;

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

    public String readChatLine() throws IOException {
        Pending pending = acquire(MAX_CHARS);
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
