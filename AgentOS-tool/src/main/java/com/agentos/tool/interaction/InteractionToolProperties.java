package com.agentos.tool.interaction;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 交互上限只允许收紧，工具策略仍受整轮预算。 */
@ConfigurationProperties("agentos.tools.interaction")
public record InteractionToolProperties(
        Integer maxQuestionChars, Integer maxAnswerChars, Duration timeout) {
    public InteractionToolProperties {
        maxQuestionChars = maxQuestionChars == null ? Integer.valueOf(4000) : maxQuestionChars;
        maxAnswerChars = maxAnswerChars == null ? Integer.valueOf(4000) : maxAnswerChars;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (maxQuestionChars < 1
                || maxQuestionChars > 4000
                || maxAnswerChars < 1
                || maxAnswerChars > 4000
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("交互限值非法");
        }
    }
}
