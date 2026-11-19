package com.agentos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentos.tool.file.FileAccessPolicy;
import com.agentos.tool.file.FileTargetResolver;
import com.agentos.tool.file.FileToolProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAccessSandboxTest {
    @TempDir Path workspace;

    @Test
    void defaultsDenyAndRealComponentsPreventPrefixAndTraversalBypasses() throws Exception {
        var allowed = Files.createDirectory(workspace.resolve("allowed"));
        var outside = Files.createDirectory(workspace.resolve("allowed-other"));
        Files.writeString(allowed.resolve("yes"), "ok");
        Files.writeString(outside.resolve("secret"), "outside-sentinel");
        var denied =
                new FileTargetResolver(
                        new FileAccessPolicy(
                                workspace, FileToolProperties.defaults(List.of(), List.of())));
        assertThatThrownBy(() -> denied.resolve("allowed/yes", false))
                .hasMessageContaining("DENIED");
        var resolver =
                new FileTargetResolver(
                        new FileAccessPolicy(
                                workspace,
                                FileToolProperties.defaults(List.of(allowed), List.of(allowed))));
        for (String input :
                List.of(
                        "allowed-other/secret",
                        "allowed/../allowed-other/secret",
                        outside.resolve("secret").toString())) {
            assertThatThrownBy(() -> resolver.resolve(input, false))
                    .hasMessageContaining("DENIED")
                    .hasMessageNotContaining(outside.toString())
                    .hasMessageNotContaining("outside-sentinel");
        }
        Files.createSymbolicLink(allowed.resolve("escape"), outside.resolve("secret"));
        Files.createSymbolicLink(workspace.resolve("entry"), allowed.resolve("yes"));
        assertThatThrownBy(() -> resolver.resolve("allowed/escape", false))
                .hasMessageContaining("DENIED");
        assertThat(resolver.resolve("entry", false).target())
                .isEqualTo(allowed.resolve("yes").toRealPath());
        Files.createSymbolicLink(allowed.resolve("parent-link"), outside);
        assertThatThrownBy(() -> resolver.resolve("allowed/parent-link/new", true))
                .hasMessageContaining("DENIED");
        assertThat(resolver.resolve("allowed/new", true).exists()).isFalse();
    }

    @Test
    void lockRootAndTemporaryNamesCannotBeAccessedEvenUnderAllowedRoot() throws Exception {
        var policy =
                new FileAccessPolicy(
                        workspace,
                        FileToolProperties.defaults(List.of(workspace), List.of(workspace)));
        Files.createDirectories(policy.lockRoot());
        Files.writeString(policy.lockRoot().resolve("secret.lock"), "lock");
        Files.writeString(workspace.resolve(".agentos-write-private.tmp"), "private");
        var resolver = new FileTargetResolver(policy);
        for (String input :
                List.of(
                        ".tool-locks/secret.lock",
                        ".tool-locks/new",
                        ".agentos-write-private.tmp")) {
            assertThatThrownBy(() -> resolver.resolve(input, false)).hasMessageContaining("DENIED");
            assertThatThrownBy(() -> resolver.resolve(input, true)).hasMessageContaining("DENIED");
        }
    }
}
