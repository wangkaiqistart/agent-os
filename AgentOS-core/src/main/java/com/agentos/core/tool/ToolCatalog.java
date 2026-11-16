package com.agentos.core.tool;

import com.agentos.core.agent.AgentProfile;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 根据 Agent 配置取得其允许使用的官方 ToolCallback，Core 不依赖具体工具实现。
 * 当前仅有契约，ReActLoop 仍传空工具列表；声明接口不代表授权和执行已接通。
 */
public interface ToolCatalog {
    /** 规划中的启动/调用前校验入口：检查 Agent 声明的工具是否存在、可用。 */
    void validate(AgentProfile profile);

    /** 返回该 Agent 可暴露给模型的回调集合；不能把全局所有工具无差别返回。 */
    List<ToolCallback> callbacks(AgentProfile profile);
}
