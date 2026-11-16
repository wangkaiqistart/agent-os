package com.agentos.core.tool;

import com.agentos.core.agent.AgentProfile;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 根据 Agent 配置取得其允许使用的官方 ToolCallback，Core 不依赖具体工具实现。
 * 实现由 tool 模块提供；ReActLoop 只接收名单内的治理 callback。
 */
public interface ToolCatalog {
    /** 建轮次和模型外呼前的校验入口：检查 Agent 声明的工具是否存在、可用。 */
    void validate(AgentProfile profile);

    /** 返回该 Agent 可暴露给模型的回调集合；不能把全局所有工具无差别返回。 */
    List<ToolCallback> callbacks(AgentProfile profile);
}
