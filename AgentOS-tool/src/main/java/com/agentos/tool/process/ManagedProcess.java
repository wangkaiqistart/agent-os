package com.agentos.tool.process;

import com.agentos.core.tool.ToolExecutionScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** 拥有根进程、可识别后代与流。取消真正终止受控进程树，不以Future取消代表OS动作结束。 */
public final class ManagedProcess implements AutoCloseable {
    private final Object ownershipLock = new Object();
    private final Process process;
    private final ToolExecutionScope scope;
    private final Consumer<ProcessHandle> destroy;
    private final Map<Long, ProcessHandle> descendants = new ConcurrentHashMap<>();
    private final AtomicBoolean terminating = new AtomicBoolean();
    private final CompletableFuture<Boolean> terminated = new CompletableFuture<>();
    private final AtomicBoolean monitoring = new AtomicBoolean(true);
    private final Thread monitor;
    private volatile ToolExecutionScope.Registration registration;
    private volatile boolean cleanupConfirmed = true;
    private volatile boolean unidentified;

    private ManagedProcess(Process process, ToolExecutionScope scope) {
        this(process, scope, ProcessHandle::destroyForcibly);
    }

    ManagedProcess(Process process, ToolExecutionScope scope, Consumer<ProcessHandle> destroy) {
        this.process = process;
        this.scope = scope;
        this.destroy = destroy;
        monitor =
                Thread.ofVirtual()
                        .name("agentos-process-tree")
                        .unstarted(() -> {
                            while (monitoring.get()) {
                                captureDescendants();
                                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                            }
                        });
        registration = scope.register(this::terminate);
        if (terminating.get()) {
            registration.close();
        }
        monitor.start();
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification =
                    "仅接收Shell/MCP按真实程序与cwd白名单授权的内部参数；ProcessBuilder独立传递argv，不隐含解释器，契约测试验证特殊字符零额外动作")
    public static ManagedProcess start(
            Path executable,
            List<String> args,
            Path cwd,
            Map<String, String> environment,
            ToolExecutionScope scope) {
        scope.checkpoint();
        var argv = new ArrayList<String>();
        argv.add(executable.toString());
        argv.addAll(List.copyOf(args));
        var builder = new ProcessBuilder(argv);
        builder.directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        scope.checkpoint();
        scope.possiblyStarted();
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new ShellToolException("UNAVAILABLE：进程启动失败");
        }
        try {
            return new ManagedProcess(process, scope);
        } catch (RuntimeException failure) {
            process.descendants().limit(1000).forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            } catch (IOException ignored) {
                /* 注册取消资源失败，保留原终止原因。 */
            }
            throw failure;
        }
    }

    private void captureDescendants() {
        try (var handles = process.descendants()) {
            var iterator = handles.iterator();
            int scanned = 0;
            while (iterator.hasNext()) {
                if (++scanned > 1000 || descendants.size() >= 1000) {
                    unidentified = true;
                    break;
                }
                var handle = iterator.next();
                descendants.putIfAbsent(handle.pid(), handle);
            }
        } catch (RuntimeException unavailable) {
            unidentified = true;
        }
    }

    /** 初始化成功后将长驻进程移交来源代次；不再随初始化scope关闭。 */
    public void transferOwnership() {
        synchronized (ownershipLock) {
            if (terminating.get()) {
                throw new IllegalStateException("进程已关闭");
            }
            registration.close();
        }
    }

    public Process process() {
        return process;
    }

    public boolean cleanupConfirmed() {
        return cleanupConfirmed;
    }

    public boolean terminate(long deadlineNanos) {
        if (!terminating.compareAndSet(false, true)) {
            try {
                return terminated.get(
                        Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException failure) {
                return false;
            }
        }
        boolean confirmed = false;
        try {
            captureDescendants();
            monitoring.set(false);
            monitor.interrupt();
            var controlled = new ArrayList<>(descendants.values());
            // 先收集并终止后代，让父进程有机会回收，再终止根；所有等待共享调用方deadline。
            for (ProcessHandle child : controlled) {
                if (child.isAlive()) {
                    destroy.accept(child);
                }
            }
            long childWait =
                    Math.min(deadlineNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(120));
            while (controlled.stream().anyMatch(ProcessHandle::isAlive)
                    && System.nanoTime() < childWait) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            }
            if (process.isAlive()) {
                destroy.accept(process.toHandle());
            }
            while ((process.isAlive() || controlled.stream().anyMatch(ProcessHandle::isAlive))
                    && System.nanoTime() < deadlineNanos) {
                LockSupport.parkNanos(
                        Math.min(
                                TimeUnit.MILLISECONDS.toNanos(2),
                                Math.max(1, deadlineNanos - System.nanoTime())));
            }
            confirmed =
                    !unidentified
                            && !process.isAlive()
                            && controlled.stream().noneMatch(ProcessHandle::isAlive);
            try {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            } catch (IOException failure) {
                confirmed = false;
            }
        } finally {
            cleanupConfirmed = confirmed;
            if (registration != null && confirmed) {
                registration.close();
            }
            terminated.complete(confirmed);
        }
        return confirmed;
    }

    @Override
    public void close() {
        if (!terminating.get()) {
            terminate(
                    System.nanoTime()
                            + Math.min(
                                    Math.min(TimeUnit.SECONDS.toNanos(1), scope.remainingNanos()),
                                    scope.budget().remainingHardStopNanos()));
        }
    }
}
