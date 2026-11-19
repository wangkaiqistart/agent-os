package com.agentos.tool.mcp;

import com.agentos.core.tool.ToolExecutionScope;
import com.agentos.tool.process.ManagedProcess;
import com.agentos.tool.process.ProcessEnvironmentPolicy;
import com.agentos.tool.process.ShellToolProperties;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 仅拥有进程与有界管道；JSON映射、协议会话及请求关联均由官方SDK负责。 */
public final class ManagedStdioTransport implements McpClientTransport {
    private static final int MAX_BYTES = 1048576;
    private final McpServerConfig config;
    private final ShellToolProperties shells;
    private final ToolExecutionScope scope;
    private final JacksonMcpJsonMapper mapper =
            new JacksonMcpJsonMapper(JsonMapper.builder().build());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    private volatile ManagedProcess process;
    private volatile Consumer<Throwable> exceptionHandler = ignored -> {};

    public ManagedStdioTransport(
            McpServerConfig config, ShellToolProperties shells, ToolExecutionScope scope) {
        this.config = config;
        this.shells = shells;
        this.scope = scope;
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        exceptionHandler = handler;
    }

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> {
            // 程序与 cwd 来自部署者的 mcp_servers.yaml，本身就是授权；不复用模型 shell 工具的命令白名单。
            java.nio.file.Path executable;
            java.nio.file.Path cwd;
            try {
                if (config.command() == null
                        || !config.command().isAbsolute()
                        || config.cwd() == null) {
                    throw new IOException();
                }
                executable = config.command().toRealPath();
                cwd = config.cwd().toRealPath();
                if (!java.nio.file.Files.isRegularFile(executable)
                        || !java.nio.file.Files.isExecutable(executable)
                        || !java.nio.file.Files.isDirectory(cwd)) {
                    throw new IOException();
                }
            } catch (IOException | RuntimeException invalid) {
                throw new McpToolException("CONNECT_FAILED");
            }
            synchronized (writeLock) {
                if (closed.get() || process != null) {
                    throw new McpToolException("CLOSED");
                }
                process =
                        ManagedProcess.start(
                                executable,
                                config.args(),
                                cwd,
                                new ProcessEnvironmentPolicy(shells).build(config.env()),
                                scope);
            }
            Thread.ofVirtual()
                    .name("agentos-mcp-stderr")
                    .start(() -> {
                        try {
                            var input = process.process().getErrorStream();
                            input.transferTo(java.io.OutputStream.nullOutputStream());
                        } catch (IOException closedStream) {
                            /* 关闭时流可能先行失效。 */
                        }
                    });
            Thread.ofVirtual()
                    .name("agentos-mcp-stdout")
                    .start(() -> {
                        try {
                            var input = process.process().getInputStream();
                            var line = new byte[MAX_BYTES];
                            int length = 0;
                            int next;
                            while (!closed.get() && (next = input.read()) != -1) {
                                if (next == '\n') {
                                    if (length > 0) {
                                        String text =
                                                new String(line, 0, length, StandardCharsets.UTF_8);
                                        var message =
                                                McpSchema.deserializeJsonRpcMessage(mapper, text);
                                        handler.apply(Mono.just(message))
                                                .subscribe(ignored -> {}, this::fail);
                                    }
                                    length = 0;
                                } else {
                                    if (length == MAX_BYTES) {
                                        throw new McpToolException("MESSAGE_TOO_LARGE");
                                    }
                                    line[length++] = (byte) next;
                                }
                            }
                            if (!closed.get()) {
                                fail(new McpToolException("CONNECT_FAILED"));
                            }
                        } catch (IOException | RuntimeException invalid) {
                            if (!closed.get()) {
                                fail(new McpToolException("CONNECT_FAILED"));
                            }
                        }
                    });
        });
    }

    private void fail(Throwable ignored) {
        exceptionHandler.accept(new McpToolException("CONNECT_FAILED"));
        closeBy(System.nanoTime() + 1000000000L);
    }

    public void transferOwnership() {
        process.transferOwnership();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                byte[] data = mapper.writeValueAsBytes(message);
                if (data.length > MAX_BYTES) {
                    throw new McpToolException("MESSAGE_TOO_LARGE");
                }
                synchronized (writeLock) {
                    if (closed.get() || process == null) {
                        throw new McpToolException("CLOSED");
                    }
                    var output = process.process().getOutputStream();
                    output.write(data);
                    output.write('\n');
                    output.flush();
                }
            } catch (IOException invalid) {
                throw new McpToolException("CONNECT_FAILED");
            }
        });
    }

    public boolean closeBy(long deadline) {
        closed.set(true);
        var owned = process;
        if (owned == null) {
            return true;
        }
        try {
            owned.process().getOutputStream().close();
            owned.process()
                    .waitFor(
                            Math.min(
                                    100,
                                    Math.max(
                                            0,
                                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                                    deadline - System.nanoTime()))),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (IOException failure) {
            /* 仍继续强制回收。 */
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return owned.terminate(deadline);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> closeBy(System.nanoTime() + 1000000000L));
    }

    @Override
    public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
        return mapper.convertValue(value, type);
    }
}
