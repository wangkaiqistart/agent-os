package com.agentos.core.provider;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import java.util.List;

/** 所有已发出尝试均失败的 Provider 异常。 */
public class ProviderCallException extends AgentOSException {

    private static final long serialVersionUID = 1L;

    private final List<LlmCallRecord> calls;

    public ProviderCallException(String message, List<LlmCallRecord> calls) {
        super(ErrorCode.PROVIDER_UNAVAILABLE, message);
        this.calls = List.copyOf(calls);
    }

    public List<LlmCallRecord> getCalls() {
        return calls;
    }
}
