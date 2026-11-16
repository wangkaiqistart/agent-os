package com.agentos.tool.registry;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.error.AgentOSException;
import com.agentos.core.error.ErrorCode;
import com.agentos.core.tool.ToolCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** 全局只注册固定内置能力；每次请求按 AGENT.md 的 tools 名单形成最小授权集合。 */
public final class ToolRegistry implements ToolCatalog {
    private final Map<String, ToolCallback> callbacks;

    public ToolRegistry(List<ToolCallback> callbacks) {
        ToolCallingChatOptions.validateToolCallbacks(callbacks);
        var map = new LinkedHashMap<String, ToolCallback>();
        callbacks.forEach(c -> map.put(c.getToolDefinition().name(), c));
        this.callbacks = Map.copyOf(map);
    }

    public void validate(AgentProfile profile) {
        for (String name : profile.tools()) {
            if (!callbacks.containsKey(name)) {
                throw new AgentOSException(
                        ErrorCode.INVALID_ARGUMENT,
                        "agents/" + profile.name() + "/AGENT.md 的 tools 包含未注册工具");
            }
        }
    }

    public List<ToolCallback> callbacks(AgentProfile profile) {
        validate(profile);
        return profile.tools().stream().map(callbacks::get).toList();
    }
}
