package com.agentos.tool.process;

import com.agentos.core.tool.ToolExecutionScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** stdout/stderr共享保留预算，并发排空；超限后继续丢弃，避免子进程管道堵塞。 */
public final class BoundedProcessOutput {
    public record Result(
            int exitCode,
            String stdout,
            String stderr,
            boolean truncated,
            boolean cleanupConfirmed,
            int retainedBytes) {}

    private final int cap;
    private final Object outputLock = new Object();
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private int retained;
    private boolean truncated;

    private BoundedProcessOutput(int cap) {
        this.cap = cap;
    }

    private void drain(
            InputStream input, ByteArrayOutputStream retainedStream, ToolExecutionScope scope) {
        byte[] buffer = new byte[4096];
        try {
            for (; ; ) {
                scope.checkpoint();
                int read = input.read(buffer);
                if (read < 0) {
                    return;
                }
                synchronized (outputLock) {
                    int keep = Math.min(read, cap - retained);
                    retainedStream.write(buffer, 0, keep);
                    retained += keep;
                    if (read > keep) {
                        truncated = true;
                    }
                }
            }
        } catch (IOException failure) {
            scope.checkpoint();
            throw new ShellToolException("UNAVAILABLE：进程输出读取失败");
        }
    }

    public static Result collect(ManagedProcess managed, ToolExecutionScope scope, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 65536) {
            throw new IllegalArgumentException("进程输出上限无效");
        }
        var output = new BoundedProcessOutput(maxBytes);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Process process = managed.process();
        List<Future<?>> readers =
                List.of(
                        executor.submit(
                                () -> output.drain(process.getInputStream(), output.stdout, scope)),
                        executor.submit(() ->
                                output.drain(process.getErrorStream(), output.stderr, scope)));
        try {
            process.getOutputStream().close();
            while (process.isAlive()) {
                scope.checkpoint();
                process.waitFor(
                        Math.min(scope.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(10)),
                        TimeUnit.NANOSECONDS);
            }
            for (Future<?> reader : readers) {
                reader.get(Math.max(1, scope.remainingNanos()), TimeUnit.NANOSECONDS);
            }
            scope.checkpoint();
            boolean confirmed = managed.terminate(System.nanoTime() + scope.remainingNanos());
            synchronized (output.outputLock) {
                return new Result(
                        process.exitValue(),
                        output.stdout.toString(StandardCharsets.UTF_8),
                        output.stderr.toString(StandardCharsets.UTF_8),
                        output.truncated,
                        confirmed,
                        output.retained);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            scope.close();
            if (scope.budget().expired() || scope.budget().cancelled()) {
                throw new com.agentos.core.turn.TurnDeadlineExceededException();
            }
            throw new com.agentos.core.turn.LeaseLostException();
        } catch (java.util.concurrent.TimeoutException timeout) {
            scope.close();
            if (scope.budget().expired() || scope.budget().cancelled()) {
                throw new com.agentos.core.turn.TurnDeadlineExceededException();
            }
            throw new ToolExecutionScope.ToolTimeoutException();
        } catch (java.util.concurrent.ExecutionException failure) {
            scope.close();
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ShellToolException("EXECUTION_UNCERTAIN：进程输出失败");
        } catch (IOException failure) {
            scope.close();
            throw new ShellToolException("EXECUTION_UNCERTAIN：进程输入关闭失败");
        } catch (RuntimeException failure) {
            scope.close();
            throw failure;
        } finally {
            readers.forEach(reader -> reader.cancel(true));
            executor.shutdownNow();
        }
    }
}
