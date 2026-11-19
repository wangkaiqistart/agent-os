package com.agentos.tool.mcp;

import com.agentos.core.tool.Redactor;
import com.agentos.core.tool.Sandbox;
import com.agentos.core.tool.ToolDescriptor;
import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.core.tool.ToolSourceDescriptor;
import com.agentos.core.turn.TurnExecutionBudget;
import com.agentos.tool.process.ShellToolProperties;
import com.agentos.tool.registry.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** 每来源后台初始化和未来重连；目录整体发布，失败不阻塞本地就绪。 */
public final class McpClientService implements AutoCloseable {
    private final Map<String, ToolSourceState> entries = new LinkedHashMap<>();
    private final Path workspace;
    private final ShellToolProperties shells;
    private final Sandbox sandbox;
    private final ToolRegistry registry;
    private final Function<ToolDescriptor, ToolDescriptor> governor;
    private final List<ToolDescriptor> local;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("agentos-mcp-reconnect").factory());
    private final Redactor redactor;
    private List<ToolSourceDescriptor> configurationFailures = List.of();

    public synchronized void configurationFailures(List<McpConfigurationLoader.Failure> failures) {
        configurationFailures =
                failures.stream()
                        .map(f ->
                                new ToolSourceDescriptor(
                                        f.source(),
                                        ToolSourceDescriptor.State.UNAVAILABLE,
                                        ToolSourceDescriptor.Reason.INVALID_DEFINITION))
                        .distinct()
                        .toList();
    }

    public McpClientService(
            List<McpServerConfig> servers,
            Path workspace,
            ShellToolProperties shells,
            Sandbox sandbox,
            ToolRegistry registry,
            Function<ToolDescriptor, ToolDescriptor> governor) {
        this(servers, workspace, shells, sandbox, registry, governor, new Redactor(List::of));
    }

    public McpClientService(
            List<McpServerConfig> servers,
            Path workspace,
            ShellToolProperties shells,
            Sandbox sandbox,
            ToolRegistry registry,
            Function<ToolDescriptor, ToolDescriptor> governor,
            Redactor redactor) {
        this.workspace = workspace;
        this.shells = shells;
        this.sandbox = sandbox;
        this.registry = registry;
        this.governor = governor;
        this.redactor = redactor;
        this.local = registry.descriptors();
        registry.unavailableGovernor(governor);
        for (var server : servers) {
            if (entries.putIfAbsent(server.name(), new ToolSourceState(server)) != null) {
                throw new McpToolException("INVALID_DEFINITION");
            }
        }
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        McpSafeDiagnostics.configure();
        synchronized (this) {
            publish();
        }
        for (var source : entries.values()) {
            Thread.ofVirtual().name("agentos-mcp-initialize").start(() -> connect(source));
        }
    }

    private void connect(ToolSourceState source) {
        final McpClientGeneration generation;
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            generation = new McpClientGeneration(sequence.incrementAndGet());
            source.generation = generation;
            source.state = ToolSourceDescriptor.State.CONNECTING;
            source.reason = null;
            publish();
        }
        var budget = TurnExecutionBudget.start(Duration.ofSeconds(10));
        synchronized (this) {
            source.initDeadline = budget.deadlineNanos();
        }
        try (var scope =
                new ToolExecutionScope(
                        null, "mcp-init", budget, Duration.ofSeconds(10), () -> {})) {
            McpClientTransport transport;
            var config = source.config;
            if (config.transport() == McpServerConfig.Transport.STDIO) {
                var resolved =
                        new McpServerConfig(
                                config.name(),
                                config.transport(),
                                config.command(),
                                config.args(),
                                (config.cwd().isAbsolute()
                                        ? config.cwd()
                                        : workspace.resolve(config.cwd())),
                                config.env(),
                                null,
                                null,
                                Map.of(),
                                config.policy(),
                                config.tools());
                transport = new ManagedStdioTransport(resolved, shells, scope);
            } else {
                transport = new McpHttpTransportFactory(sandbox).create(config);
            }
            var bounded = new McpDiscoveryTransport(transport, scope);
            var client =
                    McpClient.sync(bounded)
                            .requestTimeout(Duration.ofSeconds(30))
                            .initializationTimeout(Duration.ofSeconds(10))
                            .build();
            generation.connected(client, transport);
            var completed = new java.util.concurrent.CompletableFuture<List<ToolDescriptor>>();
            Thread.ofVirtual()
                    .name("agentos-mcp-discover")
                    .start(() -> {
                        try {
                            client.initialize();
                            completed.complete(
                                    McpToolRegistration.discover(
                                            config,
                                            client,
                                            generation,
                                            deadline ->
                                                    invalidate(
                                                            config.name(),
                                                            generation,
                                                            ToolSourceDescriptor.Reason
                                                                    .CONNECT_FAILED,
                                                            deadline),
                                            redactor));
                        } catch (RuntimeException invalid) {
                            completed.completeExceptionally(new McpToolException("CONNECT_FAILED"));
                        }
                    });
            List<ToolDescriptor> candidate;
            try {
                candidate =
                        completed.get(
                                Math.max(1, source.initDeadline - System.nanoTime()),
                                TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new McpToolException("CLOSED");
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException invalid) {
                throw new McpToolException("CONNECT_FAILED");
            }
            scope.checkpoint();
            var validator = new com.agentos.tool.registry.ToolDefinitionValidator();
            candidate.forEach(d -> validator.validateSchema(d.inputSchema()));
            bounded.discoveryComplete();
            synchronized (this) {
                if (closed.get()
                        || source.generation != generation
                        || source.state != ToolSourceDescriptor.State.CONNECTING) {
                    throw new McpToolException("CLOSED");
                }
                if (transport instanceof ManagedStdioTransport stdio) {
                    stdio.transferOwnership();
                }
                source.known = candidate.stream().map(governor).toList();
                generation.activate();
                source.state = ToolSourceDescriptor.State.AVAILABLE;
                source.failures = 0;
                source.nextReconnectAt = null;
                publish();
            }
            transport.setExceptionHandler(ignored ->
                    invalidate(
                            config.name(),
                            generation,
                            ToolSourceDescriptor.Reason.CONNECT_FAILED,
                            System.nanoTime() + 1000000000L));
        } catch (RuntimeException failure) {
            invalidate(
                    source.config.name(),
                    generation,
                    ToolSourceDescriptor.Reason.CONNECT_FAILED,
                    System.nanoTime() + 1000000000L);
        }
    }

    public synchronized List<ToolSourceDescriptor> sources() {
        var result = new ArrayList<>(configurationFailures);
        entries.values().stream()
                .map(s -> new ToolSourceDescriptor("mcp:" + s.config.name(), s.state, s.reason))
                .forEach(result::add);
        return List.copyOf(result);
    }

    public synchronized McpClientGeneration generation(String name) {
        ToolSourceState source = entries.get(name);
        if (source == null) {
            throw new McpToolException("NOT_CONFIGURED");
        }
        return source.generation;
    }

    public void invalidate(String name, ToolSourceDescriptor.Reason reason) {
        invalidate(name, generation(name), reason, System.nanoTime() + 1000000000L);
    }

    private boolean invalidate(
            String name,
            McpClientGeneration expected,
            ToolSourceDescriptor.Reason reason,
            long deadline) {
        synchronized (this) {
            var source = entries.get(name);
            expected.deactivate();
            if (!closed.get()
                    && source.generation == expected
                    && source.state != ToolSourceDescriptor.State.UNAVAILABLE) {
                source.state = ToolSourceDescriptor.State.UNAVAILABLE;
                source.reason = reason;
                publish();
                long delay = McpReconnectScheduler.delay(++source.failures).toMillis();
                source.nextReconnectAt = java.time.Instant.now().plusMillis(delay);
                scheduler.schedule(
                        () ->
                                Thread.ofVirtual()
                                        .name("agentos-mcp-reconnect")
                                        .start(() -> connect(source)),
                        Math.max(
                                0,
                                java.time.Duration.between(
                                                java.time.Instant.now(), source.nextReconnectAt)
                                        .toMillis()),
                        TimeUnit.MILLISECONDS);
            }
        }
        return expected.closeBy(deadline);
    }

    private void publish() {
        var descriptors = new ArrayList<>(local);
        for (var source : entries.values()) {
            for (var d : source.known) {
                descriptors.add(
                        new ToolDescriptor(
                                d.name(),
                                d.description(),
                                d.inputSchema(),
                                d.source(),
                                d.originalName(),
                                d.policy(),
                                d.metadata(),
                                d.callback(),
                                d.registrationIdentity(),
                                source.generation.available()
                                        ? ToolDescriptor.Availability.AVAILABLE
                                        : ToolDescriptor.Availability.UNAVAILABLE,
                                d.generation()));
            }
        }
        registry.publish(descriptors, sources());
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        List<McpClientGeneration> generations;
        synchronized (this) {
            generations = entries.values().stream().map(s -> s.generation).toList();
            entries.values()
                    .forEach(s -> {
                        s.state = ToolSourceDescriptor.State.CLOSING;
                        s.generation.deactivate();
                    });
            publish();
        }
        long deadline = System.nanoTime() + 1000000000L;
        var workers =
                generations.stream()
                        .map(g -> {
                            var result = new java.util.concurrent.CompletableFuture<Boolean>();
                            Thread.ofVirtual().start(() -> result.complete(g.closeBy(deadline)));
                            return result;
                        })
                        .toList();
        for (var worker : workers) {
            try {
                worker.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException e) {
                /* 共享关闭窗口已到，不续时。 */
            }
        }
        synchronized (this) {
            entries.values()
                    .forEach(s -> {
                        s.state = ToolSourceDescriptor.State.CLOSED;
                        s.reason = ToolSourceDescriptor.Reason.CLOSED;
                    });
            publish();
        }
    }
}
