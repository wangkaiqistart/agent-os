package com.agentos.core.provider;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import java.util.List;

/**
 * 所有已发出尝试均失败的 Provider 异常。
 *
 * <p>除了对外暴露统一的 {@link ErrorCode#PROVIDER_UNAVAILABLE}，还保留每次尝试的审计记录，
 * 方便调用方记录失败原因和耗时。
 */
public class ProviderCallException extends AgentOSException {

    private static final long serialVersionUID = 1L;

    private final List<LlmCallRecord> calls;

    public ProviderCallException(String message, List<LlmCallRecord> calls) {
        super(ErrorCode.PROVIDER_UNAVAILABLE, message);
        // List.copyOf 生成不可变快照，避免异常创建后外部继续修改审计数据。
        this.calls = List.copyOf(calls);
    }

    /** 返回本次逻辑调用已经发生的全部尝试。 */
    public List<LlmCallRecord> getCalls() {
        return calls;
    }
}
