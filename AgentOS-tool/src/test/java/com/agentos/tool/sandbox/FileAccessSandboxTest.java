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
    @TempDir Path external;

    @Test
    void workspaceIsReadableAndOutsideRootsPreventPrefixAndTraversalBypasses() throws Exception {
        // 工作区内默认可读；工作区外只有配置的根可访问，同名前缀、穿越与软链接都不能扩大范围。
        Files.writeString(workspace.resolve("inside"), "ok");
        var allowed = Files.createDirectory(external.resolve("allowed"));
        var outside = Files.createDirectory(external.resolve("allowed-other"));
        Files.writeString(allowed.resolve("yes"), "ok");
        Files.writeString(outside.resolve("secret"), "outside-sentinel");
        var defaults =
                new FileTargetResolver(
                        new FileAccessPolicy(
                                workspace, FileToolProperties.defaults(List.of(), List.of())));
        assertThat(defaults.resolve("inside", false).exists()).isTrue();
        assertThatThrownBy(() -> defaults.resolve(allowed.resolve("yes").toString(), false))
                .hasMessageContaining("DENIED");
        var resolver =
                new FileTargetResolver(
                        new FileAccessPolicy(
                                workspace,
                                FileToolProperties.defaults(List.of(allowed), List.of(allowed))));
        for (String input :
                List.of(
                        outside.resolve("secret").toString(),
                        allowed.resolve("../allowed-other/secret").toString(),
                        "../" + external.getFileName() + "/allowed-other/secret")) {
            assertThatThrownBy(() -> resolver.resolve(input, false))
                    .hasMessageContaining("DENIED")
                    .hasMessageNotContaining(outside.toString())
                    .hasMessageNotContaining("outside-sentinel");
        }
        Files.createSymbolicLink(allowed.resolve("escape"), outside.resolve("secret"));
        Files.createSymbolicLink(workspace.resolve("entry"), allowed.resolve("yes"));
        Files.createSymbolicLink(workspace.resolve("leak"), outside.resolve("secret"));
        assertThatThrownBy(() -> resolver.resolve(allowed.resolve("escape").toString(), false))
                .hasMessageContaining("DENIED");
        assertThatThrownBy(() -> resolver.resolve("leak", false)).hasMessageContaining("DENIED");
        assertThat(resolver.resolve("entry", false).target())
                .isEqualTo(allowed.resolve("yes").toRealPath());
        Files.createSymbolicLink(allowed.resolve("parent-link"), outside);
        assertThatThrownBy(
                        () -> resolver.resolve(allowed.resolve("parent-link/new").toString(), true))
                .hasMessageContaining("DENIED");
        assertThat(resolver.resolve(allowed.resolve("new").toString(), true).exists()).isFalse();
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
