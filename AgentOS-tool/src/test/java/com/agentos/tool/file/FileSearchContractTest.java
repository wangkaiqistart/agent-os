package com.agentos.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileSearchContractTest {
    @TempDir Path root;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FileSearchTools tools(FileToolProperties properties) {
        var policy = new FileAccessPolicy(root, properties);
        return new FileSearchTools(policy, new WhitelistSandbox(List.of(), policy));
    }

    private FileSearchTools tools() {
        return tools(FileToolProperties.defaults(List.of(root), List.of()));
    }

    @Test
    void utf8BinaryIsCountedAsSkippedWhileTextWhitespaceRemainsSearchable() throws Exception {
        byte[] original = new byte[] {'a', 0, 'b'};
        Files.write(root.resolve("binary"), original);
        Files.writeString(root.resolve("text"), "first\n\ta match\r\n");
        try (var scope = FileTestSupport.scope()) {
            var result =
                    JSON.readTree(tools().grep(".", "a", null, FileTestSupport.context(scope)));
            assertThat(result.path("matches")).hasSize(1);
            assertThat(result.path("matches").get(0).path("path").asText()).isEqualTo("text");
            assertThat(result.path("matches").get(0).path("line").asInt()).isEqualTo(2);
            assertThat(result.path("skipped").path("unsupported_type").asInt()).isEqualTo(1);
            assertThat(result.path("incomplete").asBoolean()).isTrue();
        }
        assertThat(Files.readAllBytes(root.resolve("binary"))).isEqualTo(original);
    }

    @Test
    void everyDirectoryAndFilePassesTheInjectedSandboxBeforeAccess() throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/secret"), "restricted-sentinel");
        var policy =
                new FileAccessPolicy(root, FileToolProperties.defaults(List.of(root), List.of()));
        var visited = new java.util.ArrayList<String>();
        var subChecks = new java.util.concurrent.atomic.AtomicInteger();
        var tools =
                new FileSearchTools(
                        policy,
                        action -> {
                            visited.add(action.target());
                            if (action.target().endsWith("sub")
                                    && subChecks.incrementAndGet() == 2) {
                                throw new com.agentos.core.tool.SandboxViolationException("DENIED");
                            }
                        });
        try (var scope = FileTestSupport.scope()) {
            String raw = tools.grep(".", "sentinel", null, FileTestSupport.context(scope));
            assertThat(JSON.readTree(raw).path("matches")).isEmpty();
            assertThat(JSON.readTree(raw).path("incomplete").asBoolean()).isTrue();
            assertThat(visited).noneMatch(p -> p.endsWith("secret"));
        }
    }

    @Test
    void literalCaseSensitiveGrepUsesOneBasedLinesAndSortedRelativePaths() throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("z.txt"), "a.b twice a.b\nA.B\n");
        Files.writeString(root.resolve("sub/a.txt"), "first\na.b\n");
        try (var scope = FileTestSupport.scope()) {
            var result =
                    JSON.readTree(tools().grep(".", "a.b", null, FileTestSupport.context(scope)));
            assertThat(result.path("matches")).hasSize(2);
            assertThat(result.path("matches").get(0).path("path").asText()).isEqualTo("sub/a.txt");
            assertThat(result.path("matches").get(0).path("line").asInt()).isEqualTo(2);
            assertThat(result.path("matches").get(1).path("line").asInt()).isEqualTo(1);
            assertThat(result.path("incomplete").asBoolean()).isFalse();
            assertThat(result.path("truncated").asBoolean()).isFalse();
            var limited =
                    JSON.readTree(tools().grep(".", "a.b", 1, FileTestSupport.context(scope)));
            assertThat(limited.path("matches")).hasSize(1);
            assertThat(limited.path("truncated").asBoolean()).isTrue();
            var empty =
                    JSON.readTree(
                            tools().grep(".", "missing", null, FileTestSupport.context(scope)));
            assertThat(empty.path("matches")).isEmpty();
            assertThat(empty.path("incomplete").asBoolean()).isFalse();
        }
    }

    @Test
    void globSupportsOnlyPortableLiteralStarQuestionAndDoubleStarGrammar() throws Exception {
        Files.createDirectories(root.resolve("a/deep"));
        Files.writeString(root.resolve("a.txt"), "");
        Files.writeString(root.resolve("a/b.txt"), "");
        Files.writeString(root.resolve("a/deep/c.txt"), "");
        try (var scope = FileTestSupport.scope()) {
            var context = FileTestSupport.context(scope);
            assertThat(
                            JSON.readTree(tools().glob(".", "*.txt", null, context))
                                    .path("paths")
                                    .toString())
                    .isEqualTo("[\"a.txt\"]");
            assertThat(JSON.readTree(tools().glob(".", "**/?.txt", null, context)).path("paths"))
                    .hasSize(3);
            for (String pattern :
                    List.of(
                            "/absolute",
                            "../*",
                            "a/../b",
                            "[abc]",
                            "{a,b}",
                            "a\u0000b",
                            "x".repeat(1001))) {
                assertThatThrownBy(() -> tools().glob(".", pattern, null, context))
                        .hasMessageContaining("INVALID_ARGUMENT");
            }
        }
    }

    @Test
    void skippedEncodingSizeLinksAndScanCeilingProduceIncompleteWithoutOutsideData()
            throws Exception {
        Path outside = Files.createTempDirectory("outside-search-");
        try {
            Files.writeString(outside.resolve("private-name"), "PRIVATE_CONTENT_SENTINEL");
            Files.createSymbolicLink(root.resolve("outside-link"), outside);
            Files.createSymbolicLink(root.resolve("outside-file"), outside.resolve("private-name"));
            Files.write(root.resolve("invalid"), new byte[] {(byte) 0xff});
            Files.write(root.resolve("binary"), new byte[] {0, 1, 2});
            Files.writeString(root.resolve("too-large"), "x".repeat(1048577));
            try (var scope = FileTestSupport.scope()) {
                String raw = tools().grep(".", "PRIVATE", null, FileTestSupport.context(scope));
                var result = JSON.readTree(raw);
                assertThat(result.path("matches")).isEmpty();
                assertThat(result.path("incomplete").asBoolean()).isTrue();
                assertThat(result.path("skipped").path("unsupported_encoding").asInt())
                        .isEqualTo(1);
                assertThat(result.path("skipped").path("too_large").asInt()).isEqualTo(1);
                assertThat(raw)
                        .doesNotContain(
                                "private-name", "PRIVATE_CONTENT_SENTINEL", outside.toString());
            }
            var limited =
                    new FileToolProperties(
                            List.of(root),
                            List.of(),
                            Path.of(".locks"),
                            1048576,
                            200,
                            2,
                            Duration.ofSeconds(30),
                            10000);
            try (var scope = FileTestSupport.scope()) {
                assertThat(
                                JSON.readTree(
                                                tools(limited)
                                                        .glob(
                                                                ".",
                                                                "**",
                                                                null,
                                                                FileTestSupport.context(scope)))
                                        .path("incomplete")
                                        .asBoolean())
                        .isTrue();
            }
        } finally {
            Files.deleteIfExists(outside.resolve("private-name"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void resultAndScanLimitsAndCancellationAreIndependent() throws Exception {
        for (int i = 0; i < 1001; i++) {
            Files.writeString(root.resolve("file-" + i), "match");
        }
        try (var scope = FileTestSupport.scope()) {
            var result =
                    JSON.readTree(tools().grep(".", "match", null, FileTestSupport.context(scope)));
            assertThat(result.path("matches").size()).isLessThanOrEqualTo(200);
            assertThat(result.path("truncated").asBoolean()).isTrue();
            assertThat(result.path("incomplete").asBoolean()).isTrue();
        }
        try (var scope = FileTestSupport.scope()) {
            scope.budget().cancel();
            assertThatThrownBy(() -> tools().glob(".", "**", null, FileTestSupport.context(scope)))
                    .isInstanceOf(com.agentos.core.turn.TurnDeadlineExceededException.class);
        }
    }
}
