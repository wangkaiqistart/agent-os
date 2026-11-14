package com.agentos.provider.chat;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * US-1 支持的 OpenAI 兼容连接类型。
 *
 * <p>配置文件里使用字符串（例如 {@code openai}），Java 代码里使用枚举，
 * 这样可以在应用启动或第一次使用时把拼写错误转换成明确的业务异常。
 */
public enum ConnectionType {
    OPENAI("openai"),
    DEEPSEEK("deepseek");

    private final String value;

    ConnectionType(String value) {
        // value 是配置文件中使用的稳定字符串，不直接暴露 Java 枚举名作为配置协议。
        this.value = value;
    }

    public static ConnectionType parse(String raw) {
        // 忽略大小写，允许 OPENAI、openai 等写法。
        for (ConnectionType type : values()) {
            if (type.value.equalsIgnoreCase(raw)) {
                return type;
            }
        }
        // 不返回 null：null 会让错误延迟到更深处，最后变成难定位的空指针异常。
        throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "暂不支持的 provider 类型：" + raw);
    }

    /**
     * 把 Profile 的输出上限写进请求级选项。
     *
     * <p>OpenAI 新协议用 {@code max_completion_tokens}（推理模型不接受 {@code max_tokens}），
     * DeepSeek 只认 {@code max_tokens}。两个字段在 Spring AI 的 Builder 里互斥，这里每种类型只写一个。
     *
     * @param builder 请求级选项 Builder，原样返回便于链式调用
     * @param maxOutputTokens Profile 的输出上限；null 表示未设置，不写任何字段
     */
    public OpenAiChatOptions.Builder applyOutputLimit(
            OpenAiChatOptions.Builder builder, Integer maxOutputTokens) {
        if (maxOutputTokens == null) {
            // 不写字段，让模型服务使用自己的默认值，也不清掉调用方已写的值。
            return builder;
        }
        return switch (this) {
            case OPENAI -> builder.maxCompletionTokens(maxOutputTokens);
            case DEEPSEEK -> builder.maxTokens(maxOutputTokens);
        };
    }
}
