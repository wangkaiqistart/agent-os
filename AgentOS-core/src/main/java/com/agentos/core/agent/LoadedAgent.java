package com.agentos.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 加载到内存的 Agent 文档快照，供本轮 Prompt 构建使用。
 *
 * @param profile frontmatter 对应的运行参数
 * @param instructions AGENT.md 中 YAML 之后的任务正文
 * @param bootstrap 工作区共享文件的内容，按插入顺序拼入系统消息
 */
public record LoadedAgent(
        AgentProfile profile, String instructions, Map<String, String> bootstrap) {
    public LoadedAgent {
        profile = Objects.requireNonNull(profile, "profile");
        instructions = instructions == null ? "" : instructions;
        // 防御性复制并保留顺序，调用方继续修改原 Map 不影响这个快照。
        bootstrap =
                bootstrap == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bootstrap));
    }
}
