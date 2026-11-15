package com.agentos.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一次读取到内存的 Agent 文档。 */
public record LoadedAgent(
        AgentProfile profile, String instructions, Map<String, String> bootstrap) {
    public LoadedAgent {
        profile = Objects.requireNonNull(profile, "profile");
        instructions = instructions == null ? "" : instructions;
        bootstrap =
                bootstrap == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bootstrap));
    }
}
