package com.agentos.tool.registry;

import com.agentos.core.agent.AgentProfile;
import com.agentos.core.tool.ToolCatalog;
import com.agentos.core.tool.UnknownAgentToolException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 全局只注册固定内置能力；每次请求按 AGENT.md 的 tools 名单形成最小授权集合。 */
public final class ToolRegistry implements ToolCatalog {
    private final Map<String, ToolCallback> callbacks;
    private final JsonMapper json = JsonMapper.builder().build();

    public ToolRegistry(List<ToolCallback> callbacks) {
        if (callbacks == null) {
            throw new IllegalArgumentException("工具目录不能为空");
        }
        var map = new LinkedHashMap<String, ToolCallback>();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                throw new IllegalArgumentException("工具定义不能为空");
            }
            var definition = callback.getToolDefinition();
            String name = definition.name();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("工具名称不能为空");
            }
            if (definition.description() == null || definition.description().isBlank()) {
                throw new IllegalArgumentException("工具描述不能为空：" + name);
            }
            validateSchema(name, definition.inputSchema());
            if (map.putIfAbsent(name, callback) != null) {
                throw new IllegalStateException("工具名称重复：" + name);
            }
        }
        ToolCallingChatOptions.validateToolCallbacks(List.copyOf(map.values()));
        this.callbacks = Map.copyOf(map);
    }

    private void validateSchema(String name, String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("工具输入约束不能为空：" + name);
        }
        try {
            JsonNode schema = json.readTree(inputSchema);
            if (schema == null || !schema.isObject()) {
                throw new IllegalArgumentException("工具输入约束必须是 JSON 对象：" + name);
            }
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw new IllegalArgumentException("工具输入约束不是合法 JSON：" + name, invalid);
        }
    }

    public void validate(AgentProfile profile) {
        for (String name : profile.tools()) {
            if (!callbacks.containsKey(name)) {
                throw new UnknownAgentToolException(profile.name(), name);
            }
        }
    }

    public List<ToolCallback> callbacks(AgentProfile profile) {
        validate(profile);
        return profile.tools().stream().map(callbacks::get).toList();
    }
}
