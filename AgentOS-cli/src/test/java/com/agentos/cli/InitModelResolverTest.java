package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** init 只接受显式 --model；环境变量和执行目录 .env 永远不参与回退。 */
class InitModelResolverTest {
    @TempDir Path directory;

    @Test
    void explicitModelDoesNotConsultLowerSources() throws Exception {
        Files.writeString(directory.resolve(".env"), "AGENTOS_LLM_MODEL=secret-model\n");
        var resolver =
                new InitModelResolver(
                        key -> {
                            throw new AssertionError("不应读环境");
                        },
                        directory);
        assertThat(resolver.resolve("显式:model", "custom"))
                .isEqualTo(new DefaultAgentOptions("显式:model", "custom"));
    }

    @Test
    void missingModelNeverReadsEnvironmentOrDotEnv() {
        var resolver =
                new InitModelResolver(
                        key -> {
                            throw new AssertionError("不应读环境");
                        },
                        directory);
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--model");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "${MODEL}", "{{MODEL}}", "<model>"})
    void invalidExplicitModelIsRejected(String value) {
        var resolver = new InitModelResolver(key -> "ignored", directory);
        assertThatThrownBy(() -> resolver.resolve(value, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerDefaultsToPrimaryAndBlankProviderIsRejected() throws Exception {
        assertThat(
                        new InitModelResolver(key -> null, directory)
                                .resolve("model", null)
                                .providerName())
                .isEqualTo("primary");
        assertThatThrownBy(
                        () -> new InitModelResolver(key -> null, directory).resolve("model", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
    }
}
