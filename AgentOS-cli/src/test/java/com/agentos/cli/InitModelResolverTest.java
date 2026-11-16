package com.agentos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 使用合成 properties 与注入环境，绝不读取开发机配置。 */
class InitModelResolverTest {
    @TempDir Path directory;

    @Test
    void explicitModelDoesNotConsultLowerSources() throws Exception {
        Files.createDirectory(directory.resolve(".env"));
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
    void processModelTakesPriorityOverFile() throws Exception {
        Files.writeString(directory.resolve(".env"), "AGENTOS_LLM_MODEL=file\n");
        assertThat(new InitModelResolver(key -> "process", directory).resolve(null, null).model())
                .isEqualTo("process");
    }

    @Test
    void propertiesUsesUtf8EscapesContinuationsAndLastDuplicate() throws Exception {
        Files.writeString(
                directory.resolve(".env"),
                "AGENTOS_LLM_MODEL=old\npassword=synthetic-secret\nAGENTOS_LLM_MODEL=中文\\:模型\\\n  -last\\=v\n");
        assertThat(new InitModelResolver(key -> null, directory).resolve(null, null))
                .isEqualTo(new DefaultAgentOptions("中文:模型-last=v", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "${MODEL}", "{{MODEL}}", "<model>"})
    void invalidSelectedModelNeverFallsBack(String value) throws Exception {
        Files.writeString(directory.resolve(".env"), "AGENTOS_LLM_MODEL=valid\n");
        var resolver = new InitModelResolver(key -> value, directory);
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(value, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultAgentOptions(value, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingModelAndBlankProviderAreSafeInputErrors() {
        var resolver = new InitModelResolver(key -> null, directory);
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .hasMessageContaining("AGENTOS_LLM_MODEL");
        assertThatThrownBy(() -> resolver.resolve("model", "  ")).hasMessageContaining("provider");
    }

    @Test
    void propertiesDoesNotExecuteShellOrUnquoteValues() throws Exception {
        var resolver = new InitModelResolver(key -> null, directory);
        Files.writeString(directory.resolve(".env"), "export AGENTOS_LLM_MODEL=ignored\n");
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(directory.resolve(".env"), "AGENTOS_LLM_MODEL=\"quoted\"\n");
        assertThat(resolver.resolve(null, null).model()).isEqualTo("\"quoted\"");
    }

    @Test
    void errorsNeverExposeSourceContentOrRawParserMessage() throws Exception {
        var resolver = new InitModelResolver(key -> null, directory);
        Files.writeString(
                directory.resolve(".env"),
                "password=synthetic-secret\nAGENTOS_LLM_MODEL=\\uBROKEN\n");
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("synthetic-secret")
                .hasMessageNotContaining("BROKEN")
                .hasNoCause();
        Files.delete(directory.resolve(".env"));
        Files.createDirectory(directory.resolve(".env"));
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .isInstanceOf(IOException.class)
                .hasNoCause();
    }
}
