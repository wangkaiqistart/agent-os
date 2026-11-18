package com.agentos.core.tool;

import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;

/** 重复工具声明的安全配置错误；入口只展示配置位置、字段和工具名称。 */
public final class DuplicateAgentToolException extends AgentOSException {
    private static final long serialVersionUID = 1L;

    public DuplicateAgentToolException(String agentName, String toolName) {
        super(
                ErrorCode.INVALID_ARGUMENT,
                "agents/" + agentName + "/AGENT.md 的 tools 包含重复工具：" + toolName);
    }
}
