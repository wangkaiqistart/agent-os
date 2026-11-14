package com.agentos.provider.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/** ConnectionType 输出上限映射：OPENAI 写 max_completion_tokens，DEEPSEEK 写 max_tokens。 */
class ConnectionTypeTest {

    @Test
    void openaiWritesOnlyMaxCompletionTokens() {
        OpenAiChatOptions options =
                ConnectionType.OPENAI.applyOutputLimit(OpenAiChatOptions.builder(), 512).build();

        assertThat(options.getMaxCompletionTokens()).isEqualTo(512);
        assertThat(options.getMaxTokens()).isNull();
    }

    @Test
    void deepseekWritesOnlyMaxTokens() {
        OpenAiChatOptions options =
                ConnectionType.DEEPSEEK.applyOutputLimit(OpenAiChatOptions.builder(), 256).build();

        assertThat(options.getMaxTokens()).isEqualTo(256);
        assertThat(options.getMaxCompletionTokens()).isNull();
    }

    @Test
    void nullLimitWritesNoField() {
        for (ConnectionType type : ConnectionType.values()) {
            OpenAiChatOptions options =
                    type.applyOutputLimit(OpenAiChatOptions.builder(), null).build();

            assertThat(options.getMaxCompletionTokens()).as(type.name()).isNull();
            assertThat(options.getMaxTokens()).as(type.name()).isNull();
        }
    }

    @Test
    void nullLimitDoesNotClearExistingField() {
        // null 表示 Profile 没设置上限：方法什么也不写，也不能把调用方已写的字段清掉。
        OpenAiChatOptions options =
                ConnectionType.DEEPSEEK
                        .applyOutputLimit(OpenAiChatOptions.builder().maxTokens(64), null)
                        .build();

        assertThat(options.getMaxTokens()).isEqualTo(64);
    }
}
