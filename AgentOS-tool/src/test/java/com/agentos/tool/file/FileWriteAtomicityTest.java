package com.agentos.tool.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWriteAtomicityTest {
    @TempDir Path workspace;

    @Test
    void everyPrecommitFaultLeavesOriginalAndNoTemporaryFile() throws Exception {
        var resolver = new FileTargetResolver(FileTestSupport.policy(workspace));
        for (var stage : AtomicFileCommitter.Stage.values()) {
            if (stage == AtomicFileCommitter.Stage.CLEANUP) {
                continue;
            }
            Files.writeString(workspace.resolve("target"), "original");
            var committer =
                    new AtomicFileCommitter(
                            resolver,
                            (at, temp) -> {
                                if (at == stage) {
                                    throw new IOException("fault");
                                }
                            });
            try (var scope = FileTestSupport.scope()) {
                assertThatThrownBy(() ->
                                committer.commit(
                                        resolver.resolve("target", true),
                                        "replacement".getBytes(StandardCharsets.UTF_8),
                                        true,
                                        scope))
                        .isInstanceOf(RuntimeException.class);
            }
            assertThat(Files.readString(workspace.resolve("target"))).isEqualTo("original");
            try (var paths = Files.list(workspace)) {
                assertThat(
                                paths.noneMatch(p ->
                                        p.getFileName().toString().startsWith(".agentos-write-")))
                        .isTrue();
            }
        }
    }

    @Test
    void createCollisionAndVersionChangeCannotReplaceExistingData() throws Exception {
        var resolver = new FileTargetResolver(FileTestSupport.policy(workspace));
        try (var scope = FileTestSupport.scope()) {
            var fresh = resolver.resolve("new", true);
            Files.writeString(workspace.resolve("new"), "collision");
            assertThatThrownBy(() ->
                            new AtomicFileCommitter(resolver)
                                    .commit(fresh, new byte[] {1}, false, scope))
                    .hasMessageContaining("CONFLICT");
            assertThat(Files.readString(workspace.resolve("new"))).isEqualTo("collision");
            var changed = resolver.resolve("new", true);
            Files.writeString(workspace.resolve("new"), "modified-longer");
            assertThatThrownBy(() ->
                            new AtomicFileCommitter(resolver)
                                    .commit(changed, new byte[] {2}, true, scope))
                    .hasMessageContaining("CONFLICT");
            assertThat(Files.readString(workspace.resolve("new"))).isEqualTo("modified-longer");
        }
    }

    @Test
    void committedCleanupFailureReportsCommittedAndNeverRepublishes() throws Exception {
        var resolver = new FileTargetResolver(FileTestSupport.policy(workspace));
        var publishes = new java.util.concurrent.atomic.AtomicInteger();
        var committer =
                new AtomicFileCommitter(
                        resolver,
                        (stage, temp) -> {
                            if (stage == AtomicFileCommitter.Stage.PUBLISH) {
                                publishes.incrementAndGet();
                            }
                            if (stage == AtomicFileCommitter.Stage.CLEANUP) {
                                throw new IOException("cleanup");
                            }
                        });
        try (var scope = FileTestSupport.scope()) {
            var result =
                    committer.commit(
                            resolver.resolve("target", true), new byte[] {42}, false, scope);
            assertThat(result.committed()).isTrue();
            assertThat(result.cleanupWarning()).isEqualTo("CLEANUP_UNCONFIRMED");
            assertThat(Files.readAllBytes(workspace.resolve("target"))).containsExactly((byte) 42);
            assertThat(publishes).hasValue(1);
        }
    }

    @Test
    void stableSidecarSerializesIndependentCoordinatorsAndSurvivesRelease() throws Exception {
        var policy = FileTestSupport.policy(workspace);
        var first = new FileWriteCoordinator(policy);
        var second = new FileWriteCoordinator(policy);
        Path target = workspace.toRealPath().resolve("target");
        var entered = new CountDownLatch(1);
        try (var scope = FileTestSupport.scope();
                var lease = first.acquire(target, scope)) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var future =
                        executor.submit(() -> {
                            try (var another = FileTestSupport.scope();
                                    var held = second.acquire(target, another)) {
                                entered.countDown();
                            }
                        });
                assertThat(entered.await(50, TimeUnit.MILLISECONDS)).isFalse();
                lease.close();
                future.get(1, TimeUnit.SECONDS);
                assertThat(entered.getCount()).isZero();
            } finally {
                executor.shutdownNow();
            }
        }
        try (var locks = Files.list(policy.lockRoot())) {
            assertThat(locks.map(p -> p.getFileName().toString()).toList())
                    .hasSize(1)
                    .allMatch(n -> n.matches("[a-f0-9]{64}\\.lock"));
        }
    }

    @Test
    void unsupportedPublicationAndChangedParentNeverFallBackToDestructiveWrite() throws Exception {
        var resolver = new FileTargetResolver(FileTestSupport.policy(workspace));
        for (boolean overwrite : java.util.List.of(false, true)) {
            Files.deleteIfExists(workspace.resolve("target"));
            if (overwrite) {
                Files.writeString(workspace.resolve("target"), "original");
            }
            var committer =
                    new AtomicFileCommitter(
                            resolver,
                            (stage, temp) -> {
                                if (stage == AtomicFileCommitter.Stage.TEMP_CREATED) {
                                    assertThat(Files.getPosixFilePermissions(temp))
                                            .isEqualTo(
                                                    java.nio.file.attribute.PosixFilePermissions
                                                            .fromString("rw-------"));
                                }
                                if (stage == AtomicFileCommitter.Stage.PUBLISH) {
                                    if (overwrite) {
                                        throw new java.nio.file.AtomicMoveNotSupportedException(
                                                "fixture", "fixture", "unsupported");
                                    }
                                    throw new UnsupportedOperationException("unsupported hardlink");
                                }
                            });
            try (var scope = FileTestSupport.scope()) {
                assertThatThrownBy(() ->
                                committer.commit(
                                        resolver.resolve("target", true),
                                        new byte[] {1},
                                        overwrite,
                                        scope))
                        .hasMessageContaining("UNAVAILABLE");
            }
            if (overwrite) {
                assertThat(Files.readString(workspace.resolve("target"))).isEqualTo("original");
            } else {
                assertThat(Files.exists(workspace.resolve("target"))).isFalse();
            }
        }
        Files.createDirectory(workspace.resolve("parent"));
        Files.writeString(workspace.resolve("parent/child"), "original");
        var expected = resolver.resolve("parent/child", true);
        Files.move(workspace.resolve("parent"), workspace.resolve("moved"));
        Files.createDirectory(workspace.resolve("parent"));
        Files.writeString(workspace.resolve("parent/child"), "other");
        try (var scope = FileTestSupport.scope()) {
            assertThatThrownBy(() ->
                            new AtomicFileCommitter(resolver)
                                    .commit(expected, new byte[] {1}, true, scope))
                    .hasMessageContaining("CONFLICT");
        }
        assertThat(Files.readString(workspace.resolve("parent/child"))).isEqualTo("other");
        assertThat(Files.readString(workspace.resolve("moved/child"))).isEqualTo("original");
    }

    @Test
    void cancellationAndLeaseLossImmediatelyBeforePublicationPreserveOriginal() throws Exception {
        var resolver = new FileTargetResolver(FileTestSupport.policy(workspace));
        for (boolean cancelled : java.util.List.of(true, false)) {
            Files.writeString(workspace.resolve("target"), "original");
            var invalidated = new java.util.concurrent.atomic.AtomicBoolean();
            var budget =
                    com.agentos.core.turn.TurnExecutionBudget.start(
                            java.time.Duration.ofSeconds(3));
            try (var scope =
                    new com.agentos.core.tool.ToolExecutionScope(
                            null,
                            "stop",
                            budget,
                            java.time.Duration.ofSeconds(3),
                            () -> {
                                if (invalidated.get() && !cancelled) {
                                    throw new com.agentos.core.turn.LeaseLostException();
                                }
                            })) {
                var committer =
                        new AtomicFileCommitter(
                                resolver,
                                (stage, temp) -> {
                                    if (stage == AtomicFileCommitter.Stage.PUBLISH) {
                                        invalidated.set(true);
                                        if (cancelled) {
                                            budget.cancel();
                                        }
                                    }
                                });
                assertThatThrownBy(() ->
                                committer.commit(
                                        resolver.resolve("target", true),
                                        new byte[] {1},
                                        true,
                                        scope))
                        .isInstanceOf(
                                cancelled
                                        ? com.agentos.core.turn.TurnDeadlineExceededException.class
                                        : com.agentos.core.turn.LeaseLostException.class);
            }
            assertThat(Files.readString(workspace.resolve("target"))).isEqualTo("original");
        }
    }

    @Test
    void externalSidecarLockWaitingUsesOriginalToolDeadline() throws Exception {
        var policy = FileTestSupport.policy(workspace);
        Files.createDirectories(policy.lockRoot());
        var target = workspace.toRealPath().resolve("blocked");
        Path sidecar = policy.lockRoot().resolve(FileWriteCoordinator.lockName(target));
        try (var external =
                        java.nio.channels.FileChannel.open(
                                sidecar,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.WRITE);
                var held = external.lock()) {
            var budget =
                    com.agentos.core.turn.TurnExecutionBudget.start(
                            java.time.Duration.ofSeconds(3));
            try (var scope =
                    new com.agentos.core.tool.ToolExecutionScope(
                            null, "blocked", budget, java.time.Duration.ofMillis(45), () -> {})) {
                long started = System.nanoTime();
                assertThatThrownBy(() -> new FileWriteCoordinator(policy).acquire(target, scope))
                        .isInstanceOf(
                                com.agentos.core.tool.ToolExecutionScope.ToolTimeoutException
                                        .class);
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(java.time.Duration.ofMillis(500));
            }
        }
        try (var scope = FileTestSupport.scope();
                var lease = new FileWriteCoordinator(policy).acquire(target, scope)) {
            assertThat(Files.exists(sidecar)).isTrue();
        }
    }
}
