package com.agentos.core.agent;

import com.agentos.core.provider.Profile;
import java.util.List;
import java.util.Objects;

/**
 * 从 AGENT.md frontmatter 派生的不可变配置，不包含 Markdown 任务正文。
 *
 * @param name Agent 名称，应与所在目录名一致
 * @param description 给人阅读的任务说明，当前不据此选择模型
 * @param providerProfile 模型连接名、模型名及生成参数，不存访问凭证
 * @param tools Agent 声明的工具名；当前最小循环尚不执行工具
 * @param settings 迭代、历史和整轮时间限制
 */
public record AgentProfile(
        String name,
        String description,
        Profile providerProfile,
        List<String> tools,
        AgentSettings settings) {

    public AgentProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent name 不能为空");
        }
        providerProfile = Objects.requireNonNull(providerProfile, "providerProfile");
        // 去空白、去重并形成不可修改列表，避免配置加载后被请求间共享修改。
        tools =
                tools == null
                        ? List.of()
                        : tools.stream()
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .distinct()
                                .toList();
        settings = settings == null ? AgentSettings.defaults() : settings;
    }
}
