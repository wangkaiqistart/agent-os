package com.agentos.tool.support;

import com.agentos.tool.mcp.McpServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 三种真实本地协议替身；先断言客户端关闭，再用夹具兜底清理。 */
public final class LocalMcpServers implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path directory;
    private final String transport;
    private final Path script;
    private final Process server;

    public LocalMcpServers(String transport) throws Exception {
        this.transport = transport;
        directory = Files.createTempDirectory("mcp-fixture-");
        script =
                Path.of("../specs/005-expand-tool-capabilities/fixtures/mcp_test_server.py")
                        .toRealPath();
        control(Map.of());
        if (transport.equals("stdio")) {
            server = null;
        } else {
            var command = new ArrayList<String>();
            command.add(python().toString());
            command.addAll(arguments());
            var builder = new ProcessBuilder(command);
            builder.environment().clear();
            server = builder.start();
            waitFor(() -> Files.exists(directory.resolve("ready.json")), 2000);
        }
    }

    public static Path python() throws IOException {
        return Path.of("/usr/bin/python3").toRealPath();
    }

    public Path directory() {
        return directory;
    }

    public List<String> arguments() {
        return List.of(
                script.toString(),
                "--transport",
                transport,
                "--control",
                directory.resolve("control.json").toString(),
                "--stats",
                directory.resolve("stats.json").toString(),
                "--ready",
                directory.resolve("ready.json").toString());
    }

    public void control(Map<String, Object> values) throws IOException {
        Path temp = directory.resolve("control.pending");
        Files.writeString(temp, JSON.writeValueAsString(values));
        Files.move(
                temp,
                directory.resolve("control.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public JsonNode stats() throws IOException {
        return Files.exists(directory.resolve("stats.json"))
                ? JSON.readTree(Files.readString(directory.resolve("stats.json")))
                : JSON.createObjectNode();
    }

    public String url() throws IOException {
        return "http://127.0.0.1:"
                + JSON.readTree(Files.readString(directory.resolve("ready.json")))
                        .path("port")
                        .asInt();
    }

    public McpServerConfig configuration(String name) throws Exception {
        return new McpServerConfig(
                name,
                McpServerConfig.Transport.parse(transport),
                transport.equals("stdio") ? python() : null,
                transport.equals("stdio") ? arguments() : List.of(),
                directory,
                Map.of(),
                transport.equals("stdio") ? null : java.net.URI.create(url()),
                transport.equals("sse")
                        ? "/sse"
                        : transport.equals("streamable-http") ? "/mcp" : null,
                Map.of(),
                new com.agentos.core.tool.ToolPolicy(
                        false, java.time.Duration.ofSeconds(30), 10000),
                Map.of());
    }

    public static void waitFor(java.util.function.BooleanSupplier condition, long milliseconds)
            throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("替身状态未按期完成");
        }
    }

    @Override
    public void close() throws Exception {
        if (server != null) {
            server.destroyForcibly();
            server.waitFor();
        }
        if (Files.exists(directory.resolve("ready.json"))) {
            long pid =
                    JSON.readTree(Files.readString(directory.resolve("ready.json")))
                            .path("pid")
                            .asLong();
            ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
