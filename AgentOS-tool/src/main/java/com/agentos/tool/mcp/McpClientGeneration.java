package com.agentos.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单调代次失效不可复活；旧快照始终引用原实例。 */
public final class McpClientGeneration {
    private final long id;
    private final AtomicBoolean available = new AtomicBoolean();
    private volatile McpSyncClient client;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile McpClientTransport transport;

    public McpClientGeneration(long id) {
        this.id = id;
    }

    public long id() {
        return id;
    }

    public boolean available() {
        return available.get();
    }

    synchronized void connected(McpSyncClient connected, McpClientTransport owned) {
        client = connected;
        transport = owned;
        if (closing.get()) {
            closeBy(System.nanoTime() + 1000000000L);
            throw new McpToolException("CLOSED");
        }
    }

    void activate() {
        available.set(true);
    }

    void deactivate() {
        available.set(false);
    }

    boolean closeBy(long deadline) {
        deactivate();
        if (closing.compareAndSet(false, true) && client != null) {
            Thread.ofVirtual().name("agentos-mcp-session-close").start(client::close);
        }
        var owned = transport;
        if (owned instanceof ManagedStdioTransport stdio) {
            return stdio.closeBy(deadline);
        }
        if (owned == null) {
            return true;
        }
        try {
            owned.closeGracefully()
                    .block(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
