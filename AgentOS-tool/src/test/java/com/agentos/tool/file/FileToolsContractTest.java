package com.agentos.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileToolsContractTest {
    @TempDir Path workspace;
    @TempDir Path external;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void createOverwriteReadAndWriteOnlyAreIndependent() throws Exception {
        var tools = new FileTools(FileTestSupport.policy(workspace));
        try (var scope = FileTestSupport.scope()) {
            var ctx = FileTestSupport.context(scope);
            assertThat(
                            JSON.readTree(tools.write_file("note.txt", "你好", null, ctx))
                                    .path("committed")
                                    .asBoolean())
                    .isTrue();
            assertThat(Files.readString(workspace.resolve("note.txt"))).isEqualTo("你好");
            assertThat(JSON.readTree(tools.read_file("note.txt", ctx)).path("content").asText())
                    .isEqualTo("你好");
            assertThatThrownBy(() -> tools.write_file("note.txt", "bad", false, ctx))
                    .hasMessageContaining("CONFLICT");
            assertThat(Files.readString(workspace.resolve("note.txt"))).isEqualTo("你好");
            assertThat(
                            JSON.readTree(tools.write_file("note.txt", "更新", true, ctx))
                                    .path("bytesWritten")
                                    .asInt())
                    .isEqualTo(6);
            assertThatThrownBy(() -> tools.write_file("absent/child", "x", false, ctx))
                    .hasMessageContaining("UNAVAILABLE");
            // 工作区外的读写根相互独立：只配写根不代表可以读回。
            var writeOnly =
                    new FileTools(
                            new FileAccessPolicy(
                                    workspace,
                                    FileToolProperties.defaults(List.of(), List.of(external))));
            String outside = external.resolve("note.txt").toString();
            writeOnly.write_file(outside, "仅写", true, ctx);
            assertThat(Files.readString(external.resolve("note.txt"))).isEqualTo("仅写");
            assertThatThrownBy(() -> writeOnly.read_file(outside, ctx))
                    .hasMessageContaining("DENIED");
        }
    }

    @Test
    void strictUtf8RegularFilesAndByteLimitApplyBeforeCharacterTruncation() throws Exception {
        var tools = new FileTools(FileTestSupport.policy(workspace));
        Files.write(workspace.resolve("invalid"), new byte[] {(byte) 0xc3, (byte) 0x28});
        Files.writeString(workspace.resolve("exact"), "x".repeat(1048576));
        Files.write(workspace.resolve("large"), new byte[1048577]);
        try (var scope = FileTestSupport.scope()) {
            var ctx = FileTestSupport.context(scope);
            assertThatThrownBy(() -> tools.read_file("invalid", ctx))
                    .hasMessageContaining("UNSUPPORTED_ENCODING");
            assertThatThrownBy(() -> tools.read_file(".", ctx))
                    .hasMessageContaining("UNSUPPORTED_TYPE");
            assertThat(JSON.readTree(tools.read_file("exact", ctx)).path("truncated").asBoolean())
                    .isTrue();
            assertThatThrownBy(() -> tools.read_file("large", ctx))
                    .hasMessageContaining("TOO_LARGE");
            assertThatThrownBy(() -> tools.write_file("oversized", "中".repeat(349526), false, ctx))
                    .hasMessageContaining("TOO_LARGE");
            assertThat(Files.exists(workspace.resolve("oversized"))).isFalse();
        }
    }

    @Test
    void utf8BinaryIsRejectedButEmptyAndOrdinaryWhitespaceRemainText() throws Exception {
        var tools = new FileTools(FileTestSupport.policy(workspace));
        Files.write(workspace.resolve("binary"), new byte[] {'a', 0, 'b'});
        Files.writeString(workspace.resolve("empty"), "");
        String text = "中文\t内容\r\n下一行\n\f结束";
        Files.writeString(workspace.resolve("text"), text);
        try (var scope = FileTestSupport.scope()) {
            var ctx = FileTestSupport.context(scope);
            assertThatThrownBy(() -> tools.read_file("binary", ctx))
                    .hasMessageContaining("UNSUPPORTED_TYPE");
            assertThat(JSON.readTree(tools.read_file("empty", ctx)).path("content").asText())
                    .isEmpty();
            assertThat(JSON.readTree(tools.read_file("text", ctx)).path("content").asText())
                    .isEqualTo(text);
        }
        assertThat(Files.readAllBytes(workspace.resolve("binary")))
                .containsExactly((byte) 'a', (byte) 0, (byte) 'b');
    }

    @Test
    void directListingIsSortedBoundedAndHidesInternalFiles() throws Exception {
        Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(workspace.resolve("sub/hidden-child"), "x");
        for (int i = 0; i < 205; i++) {
            Files.writeString(workspace.resolve(String.format("file-%03d", i)), "");
        }
        Files.writeString(workspace.resolve(".agentos-write-sentinel.tmp"), "private");
        var tools = new FileTools(FileTestSupport.policy(workspace));
        try (var scope = FileTestSupport.scope()) {
            var result = JSON.readTree(tools.list_dir(".", null, FileTestSupport.context(scope)));
            assertThat(result.path("entries").size()).isBetween(1, 200);
            assertThat(result.path("entries").get(0).path("name").asText()).isEqualTo("file-000");
            assertThat(result.path("truncated").asBoolean()).isTrue();
            assertThat(result.toString()).doesNotContain("hidden-child", ".tool-locks", "sentinel");
            assertThatThrownBy(() -> tools.list_dir(".", 201, FileTestSupport.context(scope)))
                    .hasMessageContaining("INVALID_ARGUMENT");
        }
    }

    @Test
    void cancelledScopePreventsCreation() {
        var tools = new FileTools(FileTestSupport.policy(workspace));
        try (var scope = FileTestSupport.scope()) {
            scope.budget().cancel();
            assertThatThrownBy(() ->
                            tools.write_file(
                                    "cancelled", "x", false, FileTestSupport.context(scope)))
                    .isInstanceOf(com.agentos.core.turn.TurnDeadlineExceededException.class);
            assertThat(Files.exists(workspace.resolve("cancelled"))).isFalse();
        }
    }

    @Test
    void growthAfterMetadataCheckStillUsesByteCapAndSchemaHidesScope() throws Exception {
        var tools = new FileTools(FileTestSupport.policy(workspace));
        Files.writeString(workspace.resolve("growing"), "small");
        var checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        var budget =
                com.agentos.core.turn.TurnExecutionBudget.start(java.time.Duration.ofSeconds(3));
        try (var scope =
                new com.agentos.core.tool.ToolExecutionScope(
                        null,
                        "growth",
                        budget,
                        java.time.Duration.ofSeconds(3),
                        () -> {
                            if (checkpoints.incrementAndGet() == 3) {
                                try {
                                    Files.write(workspace.resolve("growing"), new byte[1048577]);
                                } catch (java.io.IOException failure) {
                                    throw new java.io.UncheckedIOException(failure);
                                }
                            }
                        })) {
            assertThatThrownBy(() -> tools.read_file("growing", FileTestSupport.context(scope)))
                    .hasMessageContaining("TOO_LARGE");
        }
        for (var callback : org.springframework.ai.support.ToolCallbacks.from(tools)) {
            var schema = JSON.readTree(callback.getToolDefinition().inputSchema());
            assertThat(schema.path("type").asText()).isEqualTo("object");
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(schema.toString()).doesNotContain("ToolContext", "context", "scope");
        }
        try (var scope = FileTestSupport.scope()) {
            var result =
                    JSON.readTree(
                            tools.write_file(
                                    "exact-write",
                                    "x".repeat(1048576),
                                    false,
                                    FileTestSupport.context(scope)));
            assertThat(result.path("bytesWritten").asInt()).isEqualTo(1048576);
            assertThat(Files.size(workspace.resolve("exact-write"))).isEqualTo(1048576);
        }
    }

    @Test
    void enumerationCapIsIndependentOfResultCap() throws Exception {
        for (int i = 0; i < 8; i++) {
            Files.writeString(workspace.resolve("item-" + i), "");
        }
        var properties =
                new FileToolProperties(
                        List.of(workspace),
                        List.of(),
                        Path.of(".tool-locks"),
                        1048576,
                        200,
                        3,
                        java.time.Duration.ofSeconds(30),
                        10000);
        var tools = new FileTools(new FileAccessPolicy(workspace, properties));
        try (var scope = FileTestSupport.scope()) {
            var result = JSON.readTree(tools.list_dir(".", null, FileTestSupport.context(scope)));
            assertThat(result.path("entries").size()).isEqualTo(3);
            assertThat(result.path("incomplete").asBoolean()).isTrue();
            assertThat(result.path("truncated").asBoolean()).isFalse();
        }
    }
}
