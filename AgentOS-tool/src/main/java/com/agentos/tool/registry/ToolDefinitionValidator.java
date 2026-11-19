package com.agentos.tool.registry;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 注册及执行共用的官方schema校验边界；先拒绝资源引用，再调用官方校验器。 */
public final class ToolDefinitionValidator {
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private final JsonMapper json =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator(json);

    public void validateSchema(String schema) {
        try {
            Map<String, Object> value = schema(schema);
            if (!validator.validateSchema(value).valid()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            // 不保留解析器/校验器cause，它们可能包含远端定义原文及地址。
            throw new IllegalArgumentException("工具输入约束不合法或不受支持");
        }
    }

    public void validateInput(String schema, String input) {
        try {
            if (input == null) {
                throw new IllegalArgumentException();
            }
            Object value = json.readValue(input, Object.class);
            if (!(value instanceof Map) || !validator.validate(schema(schema), value).valid()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("参数不符合工具输入约束");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(String text) {
        if (text == null
                || text.length() > 65536
                || text.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException();
        }
        JsonNode node = json.readTree(text);
        if (node == null || !node.isObject() || !"object".equals(node.path("type").asString())) {
            throw new IllegalArgumentException();
        }
        inspect(node, 1);
        return json.convertValue(node, Map.class);
    }

    private void inspect(JsonNode node, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException();
        }
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (key.equals("$schema")
                        && (!value.isString() || !DIALECT.equals(value.asString()))) {
                    throw new IllegalArgumentException();
                }
                if ((key.equals("$ref")
                                || key.equals("$dynamicRef")
                                || key.equals("$recursiveRef")
                                || key.equals("$id"))
                        && (!value.isString() || !value.asString().startsWith("#"))) {
                    throw new IllegalArgumentException();
                }
                inspect(value, depth + 1);
            }
        } else if (node.isArray()) {
            node.forEach(value -> inspect(value, depth + 1));
        }
    }
}
