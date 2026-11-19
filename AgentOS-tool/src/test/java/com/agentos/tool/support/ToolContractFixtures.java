package com.agentos.tool.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 隔离资源夹具。先观察工具清理结果，再关闭夹具；夹具兜底回收不代表工具清理成功。 */
public final class ToolContractFixtures implements AutoCloseable {
    private final Path directory = Files.createTempDirectory("agentos-tool-contract-");
    private final List<Process> processes = new ArrayList<>();
    private final LocalHttpServer http = new LocalHttpServer();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch cancellation = new CountDownLatch(1);

    public ToolContractFixtures() throws IOException {}

    public Path directory() {
        return directory;
    }

    public LocalHttpServer http() {
        return http;
    }

    public long nanoTime() {
        return nanos.get();
    }

    public void advance(Duration elapsed) {
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("测试时钟不能倒退");
        }
        nanos.addAndGet(elapsed.toNanos());
    }

    public void cancel() {
        cancelled.set(true);
        cancellation.countDown();
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    public boolean awaitCancellation(Duration timeout) throws InterruptedException {
        return cancellation.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** 仅启动测试明确指定的命令，清空宿主环境，不使用 shell 拼接。 */
    public Process startProcess(List<String> argv) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(argv));
        builder.directory(directory.toFile());
        builder.environment().clear();
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    @Override
    public void close() throws IOException {
        for (Process process : processes) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        http.close();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
