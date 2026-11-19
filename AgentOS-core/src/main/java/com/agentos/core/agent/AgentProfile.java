package com.agentos.core.agent;

import com.agentos.core.provider.Profile;
import com.agentos.core.tool.DuplicateAgentToolException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 从 AGENT.md frontmatter 派生的不可变配置，不包含 Markdown 任务正文。
 *
 * @param name Agent 名称，应与所在目录名一致
 * @param description 给人阅读的任务说明，当前不据此选择模型
 * @param providerProfile 模型连接名、模型名及生成参数，不存访问凭证
 * @param tools Agent 声明的工具名；调用前由 ToolCatalog 校验并形成最小授权集合
 * @param settings 迭代、历史和整轮时间限制
 */
public record AgentProfile(
        String name,
        String description,
        Profile providerProfile,
        List<String> tools,
        List<String> mcpServers,
        AgentSettings settings) {

    public AgentProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent name 不能为空");
        }
        providerProfile = Objects.requireNonNull(providerProfile, "providerProfile");
        // 去空白并形成不可修改列表；重复声明必须报错，不能静默改变授权配置。
        if (tools == null) {
            tools = List.of();
        } else {
            List<String> normalized = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String tool : tools) {
                if (tool == null) {
                    continue;
                }
                String toolName = tool.trim();
                if (toolName.isEmpty()) {
                    continue;
                }
                if (!seen.add(toolName)) {
                    throw new DuplicateAgentToolException(name, toolName);
                }
                normalized.add(toolName);
            }
            tools = List.copyOf(normalized);
        }
        var servers = new ArrayList<String>();
        var seenServers = new HashSet<String>();
        if (mcpServers != null) {
            for (String server : mcpServers) {
                if (server == null || server.isBlank()) {
                    continue;
                }
                String normalized = server.trim();
                if (!normalized.matches("[a-z0-9-]{1,20}") || !seenServers.add(normalized)) {
                    throw new IllegalArgumentException("MCP服务声明无效或重复");
                }
                servers.add(normalized);
            }
        }
        mcpServers = List.copyOf(servers);
        settings = settings == null ? AgentSettings.defaults() : settings;
    }

    public AgentProfile(
            String name,
            String description,
            Profile providerProfile,
            List<String> tools,
            AgentSettings settings) {
        this(name, description, providerProfile, tools, List.of(), settings);
    }
}
