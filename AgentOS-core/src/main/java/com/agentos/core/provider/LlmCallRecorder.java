package com.agentos.core.provider;

/** Provider 调用记录输出，可在 US-2 替换为 llm_calls 持久化实现。 */
@FunctionalInterface
public interface LlmCallRecorder {

    /**
     * 记录一次实际尝试的结果。
     *
     * <p>实现抛出的异常不能被 ProviderService 吞掉，也不能因此触发重发请求。
     */
    void record(LlmCallRecord record);
}
