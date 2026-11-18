package com.agentos.core.tool;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;

/** 未注册工具的配置错误；仅由配置位置和工具名生成提示，供入口定向展示。 */
public final class UnknownAgentToolException extends AgentOSException {
    private static final long serialVersionUID = 1L;

    public UnknownAgentToolException(String agentName, String toolName) {
        super(
                ErrorCode.INVALID_ARGUMENT,
                "agents/" + agentName + "/AGENT.md 的 tools 包含未注册工具：" + toolName);
    }
}
