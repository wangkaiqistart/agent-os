package com.agentos.tool.registry;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;

/** 内置工具契约采用snake_case参数；官方方法回调继续使用符合Java规范的参数名。 */
public final class BuiltinToolCallbacks {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BuiltinToolCallbacks() {}

    public static ToolCallback[] from(Object bean) {
        return Arrays.stream(ToolCallbacks.from(bean))
                .map(BuiltinToolCallbacks::adapt)
                .toArray(ToolCallback[]::new);
    }

    private static ToolCallback adapt(ToolCallback delegate) {
        Map<String, String> aliases =
                switch (delegate.getToolDefinition().name()) {
                    case "http_post" -> Map.of("content_type", "contentType");
                    case "edit_file" -> Map.of("old_text", "oldText", "new_text", "newText");
                    default -> Map.of();
                };
        if (aliases.isEmpty()) {
            return delegate;
        }
        var original = delegate.getToolDefinition();
        Map<String, Object> schema = object(original.inputSchema());
        var properties = new LinkedHashMap<String, Object>();
        if (!(schema.get("properties") instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("内置工具schema无效");
        }
        raw.forEach(
                (key, value) -> properties.put(publicName(String.valueOf(key), aliases), value));
        schema.put("properties", properties);
        if (schema.get("required") instanceof List<?> required) {
            schema.put(
                    "required",
                    required.stream()
                            .map(key -> publicName(String.valueOf(key), aliases))
                            .toList());
        }
        var definition =
                ToolDefinition.builder()
                        .name(original.name())
                        .description(original.description())
                        .inputSchema(JSON.writeValueAsString(schema))
                        .build();
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            public String call(String input) {
                return delegate.call(arguments(input));
            }

            public String call(String input, ToolContext context) {
                return delegate.call(arguments(input), context);
            }

            private String arguments(String input) {
                var values = object(input);
                aliases.forEach((exposed, javaName) -> {
                    if (values.containsKey(javaName)) {
                        throw new IllegalArgumentException("INVALID_ARGUMENT：参数名称无效");
                    }
                    if (values.containsKey(exposed)) {
                        values.put(javaName, values.remove(exposed));
                    }
                });
                return JSON.writeValueAsString(values);
            }
        };
    }

    private static String publicName(String name, Map<String, String> aliases) {
        return aliases.entrySet().stream()
                .filter(e -> e.getValue().equals(name))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(name);
    }

    private static Map<String, Object> object(String value) {
        try {
            Object parsed = JSON.readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException();
            }
            var result = new LinkedHashMap<String, Object>();
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_ARGUMENT：工具参数无效");
        }
    }
}
