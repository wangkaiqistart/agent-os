package com.agentos.core.tool;

import com.agentos.core.agent.AgentProfile;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** AgentOS 工具名单端口，core 不依赖具体工具实现。 */
public interface ToolCatalog {
    void validate(AgentProfile profile);

    List<ToolCallback> callbacks(AgentProfile profile);
}
