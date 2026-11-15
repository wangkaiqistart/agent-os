package com.agentos.core.agent;

import com.agentos.core.provider.Profile;
import java.util.List;
import java.util.Objects;

/** 已加载 Agent 的不可变配置。 */
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
