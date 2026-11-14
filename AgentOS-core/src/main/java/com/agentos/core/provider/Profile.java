package com.agentos.core.provider;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;

/** 一次 Agent 聊天调用使用的模型与生成参数。 */
public record Profile(
        String provider,
        String model,
        String reasoningEffort,
        Integer maxOutputTokens,
        Double temperature) {

    /** 创建 Profile 并校验参数格式；模型是否为空由 ProviderService 外呼前校验。 */
    public Profile {
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "maxOutputTokens 必须为正数");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "temperature 必须在 0 到 2 之间");
        }
    }
}
