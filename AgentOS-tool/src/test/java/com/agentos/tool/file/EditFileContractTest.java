package com.agentos.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class EditFileContractTest {
    @TempDir Path root;

    private FileEditTools tools(FileAccessPolicy policy) {
        return new FileEditTools(policy, new WhitelistSandbox(List.of(), policy));
    }

    @Test
    void utf8BinaryIsRejectedBeforeEditingAndPreservesTheOriginal() throws Exception {
        byte[] original = new byte[] {'a', 0, 'b'};
        Files.write(root.resolve("binary"), original);
        var tools = tools(FileTestSupport.policy(root));
        try (var scope = FileTestSupport.scope()) {
            assertThatThrownBy(() ->
                            tools.edit_file(
                                    "binary", "a", "changed", FileTestSupport.context(scope)))
                    .hasMessageContaining("UNSUPPORTED_TYPE");
        }
        assertThat(Files.readAllBytes(root.resolve("binary"))).isEqualTo(original);
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(FileAccessPolicy.TEMP_PREFIX));
        }
    }

    @Test
    void cooperativeConcurrentEditorsPublishExactlyOneReplacement() throws Exception {
        Files.writeString(root.resolve("shared"), "original");
        var tools = tools(FileTestSupport.policy(root));
        var gate = new java.util.concurrent.CountDownLatch(1);
        var successful = new java.util.concurrent.atomic.AtomicInteger();
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (String replacement : List.of("editor-a", "editor-b")) {
            var future = new java.util.concurrent.CompletableFuture<Void>();
            futures.add(future);
            Thread.ofVirtual()
                    .start(() -> {
                        try (var scope = FileTestSupport.scope()) {
                            gate.await();
                            try {
                                tools.edit_file(
                                        "shared",
                                        "original",
                                        replacement,
                                        FileTestSupport.context(scope));
                                successful.incrementAndGet();
                            } catch (FileToolException conflict) {
                                assertThat(conflict.getMessage()).contains("CONFLICT");
                            }
                            future.complete(null);
                        } catch (Throwable failure) {
                            future.completeExceptionally(failure);
                        }
                    });
        }
        gate.countDown();
        for (var future : futures) {
            future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(successful).hasValue(1);
        assertThat(Files.readString(root.resolve("shared"))).isIn("editor-a", "editor-b");
    }

    @Test
    void exactlyOneLiteralOccurrenceIncludingWhitespaceIsReplacedWithoutTrimming()
            throws Exception {
        var policy = FileTestSupport.policy(root);
        var tools = tools(policy);
        for (String old : List.of("目标", "  ", "\n", "\t ")) {
            Files.writeString(root.resolve("file"), "before" + old + "after");
            try (var scope = FileTestSupport.scope()) {
                var result =
                        JsonMapper.builder()
                                .build()
                                .readTree(
                                        tools.edit_file(
                                                "file",
                                                old,
                                                "新内容",
                                                FileTestSupport.context(scope)));
                assertThat(result.path("replacements").asInt()).isEqualTo(1);
                assertThat(result.path("committed").asBoolean()).isTrue();
                assertThat(Files.readString(root.resolve("file"))).isEqualTo("before新内容after");
            }
        }
        Files.writeString(root.resolve("file"), "唯一");
        try (var scope = FileTestSupport.scope()) {
            tools.edit_file("file", "唯一", "", FileTestSupport.context(scope));
        }
        assertThat(Files.readString(root.resolve("file"))).isEmpty();
    }

    @Test
    void emptyZeroMultipleAndOverlappingMatchesPreserveTheOriginal() throws Exception {
        var tools = tools(FileTestSupport.policy(root));
        for (var test :
                List.of(
                        List.of("abc", ""),
                        List.of("abc", "missing"),
                        List.of("x x", "x"),
                        List.of("aaa", "aa"))) {
            Files.writeString(root.resolve("file"), test.get(0));
            try (var scope = FileTestSupport.scope()) {
                assertThatThrownBy(() ->
                                tools.edit_file(
                                        "file",
                                        test.get(1),
                                        "replacement",
                                        FileTestSupport.context(scope)))
                        .isInstanceOf(RuntimeException.class);
            }
            assertThat(Files.readString(root.resolve("file"))).isEqualTo(test.get(0));
        }
    }

    @Test
    void editingRequiresBothPermissionsAndBoundsOriginalAndResultBytes() throws Exception {
        Files.writeString(root.resolve("file"), "target");
        for (boolean readOnly : List.of(true, false)) {
            var policy =
                    new FileAccessPolicy(
                            root,
                            FileToolProperties.defaults(
                                    readOnly ? List.of(root) : List.of(),
                                    readOnly ? List.of() : List.of(root)));
            try (var scope = FileTestSupport.scope()) {
                assertThatThrownBy(() ->
                                tools(policy)
                                        .edit_file(
                                                "file",
                                                "target",
                                                "changed",
                                                FileTestSupport.context(scope)))
                        .hasMessageContaining("DENIED");
            }
            assertThat(Files.readString(root.resolve("file"))).isEqualTo("target");
        }
        var tools = tools(FileTestSupport.policy(root));
        try (var scope = FileTestSupport.scope()) {
            assertThatThrownBy(() ->
                            tools.edit_file(
                                    "file",
                                    "target",
                                    "中".repeat(349526),
                                    FileTestSupport.context(scope)))
                    .hasMessageContaining("TOO_LARGE");
        }
        assertThat(Files.readString(root.resolve("file"))).isEqualTo("target");
        Files.writeString(root.resolve("file"), "x".repeat(1048577));
        try (var scope = FileTestSupport.scope()) {
            assertThatThrownBy(
                            () -> tools.edit_file("file", "x", "z", FileTestSupport.context(scope)))
                    .hasMessageContaining("TOO_LARGE");
        }
    }

    @Test
    void digestDetectsSameSizeAndTimestampChangeAndPreparationFailuresPreserveOriginal()
            throws Exception {
        var policy = FileTestSupport.policy(root);
        var sandbox = new WhitelistSandbox(List.of(), policy);
        for (var stage :
                List.of(
                        AtomicFileCommitter.Stage.WRITTEN,
                        AtomicFileCommitter.Stage.FORCED,
                        AtomicFileCommitter.Stage.PUBLISH)) {
            Files.writeString(root.resolve("file"), "original");
            var tools =
                    new FileEditTools(
                            policy,
                            sandbox,
                            (current, temp) -> {
                                if (current == stage) {
                                    throw new java.io.IOException();
                                }
                            });
            try (var scope = FileTestSupport.scope()) {
                assertThatThrownBy(() ->
                                tools.edit_file(
                                        "file", "original", "new", FileTestSupport.context(scope)))
                        .isInstanceOf(RuntimeException.class);
            }
            assertThat(Files.readString(root.resolve("file"))).isEqualTo("original");
        }
        var timestamp = Files.getLastModifiedTime(root.resolve("file"));
        var tools =
                new FileEditTools(
                        policy,
                        sandbox,
                        (stage, temp) -> {
                            if (stage == AtomicFileCommitter.Stage.PUBLISH) {
                                Files.writeString(root.resolve("file"), "external");
                                Files.setLastModifiedTime(root.resolve("file"), timestamp);
                            }
                        });
        try (var scope = FileTestSupport.scope()) {
            assertThatThrownBy(() ->
                            tools.edit_file(
                                    "file", "original", "new", FileTestSupport.context(scope)))
                    .hasMessageContaining("CONFLICT");
        }
        assertThat(Files.readString(root.resolve("file"))).isEqualTo("external");
        try (var paths = Files.list(root)) {
            assertThat(paths.map(p -> p.getFileName().toString()))
                    .noneMatch(s -> s.startsWith(FileAccessPolicy.TEMP_PREFIX));
        }
    }
}
