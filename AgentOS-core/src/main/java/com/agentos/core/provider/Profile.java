package com.agentos.core.provider;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;

/**
 * 一次 Agent 聊天调用使用的模型与生成参数。
 *
 * <p>ProviderProperties 说的是“连接到哪个服务”，Profile 说的是“这次调用使用哪个模型以及如何生成”。
 * 两者故意分开，便于同一个 Provider 连接服务多个 Agent。
 *
 * @param provider Provider 名称；为空时由上层使用默认 Provider
 * @param model 本次请求使用的模型名称
 * @param reasoningEffort 推理强度，具体含义由模型服务决定
 * @param maxOutputTokens 输出 Token 上限
 * @param temperature 采样温度，当前约束为 0 到 2
 */
public record Profile(
        String provider,
        String model,
        String reasoningEffort,
        Integer maxOutputTokens,
        Double temperature) {

    /**
     * 创建 Profile 并校验参数格式；模型是否为空由 ProviderService 外呼前校验。
     *
     * <p>这里尽早失败，把错误挡在真正发出网络请求之前。
     */
    public Profile {
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "maxOutputTokens 必须为正数");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new AgentOSException(ErrorCode.INVALID_ARGUMENT, "temperature 必须在 0 到 2 之间");
        }
    }
}
